package io.github.hazymorning.klangbild.gradle

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.util.CodePointTrie
import com.ibm.icu.util.UResourceBundle
import com.ibm.icu.util.VersionInfo
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.inject.Inject

/**
 * Writes LineBreakTables.kt for BalancedBreaker (see [lineBreakTablesKt]) from the ICU4J in
 * [icu], which runs on a class path of its own, so its version can differ from the build's:
 * ICU 68.2 is the ICU of Android 12 and of Robolectric's graphics, whose breaks the app's
 * pictures show.
 *
 * The module that holds the breaker registers it (see layers/ui) and adds its output to its
 * sources.
 */
@CacheableTask
abstract class GenerateLineBreaks : DefaultTask() {
    /** ICU4J, whose compiled rules and Unicode data the tables hold. */
    @get:Classpath
    abstract val icu: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val workers: WorkerExecutor

    @TaskAction
    fun generate() {
        workers.classLoaderIsolation { classpath.from(icu) }.submit(Write::class.java) {
            packageName.set(this@GenerateLineBreaks.packageName)
            output.set(this@GenerateLineBreaks.output)
        }
    }

    /** Runs in a class loader of its own, with [icu]'s classes rather than any ICU4J of the build's. */
    abstract class Write : WorkAction<Write.Parameters> {
        interface Parameters : WorkParameters {
            val packageName: Property<String>
            val output: DirectoryProperty
        }

        override fun execute() {
            val dir = parameters.output.get().asFile.resolve(parameters.packageName.get().replace('.', '/'))
            dir.deleteRecursively()
            dir.mkdirs()
            dir.resolve("LineBreakTables.kt").writeText(lineBreakTablesKt(parameters.packageName.get()))
        }
    }
}

/**
 * The Kotlin source of LineBreakTables.kt for BalancedBreaker, from the ICU4J on the class
 * path: ICU's line break rules as ICU itself compiled them, which of them each language uses,
 * and the emoji properties that Android's line breaker (minikin) checks on its own.
 *
 * ICU ships each rule set as a state machine (a .brk file): every code point has a category,
 * and a table gives, for each state and category, the next state and whether a break was
 * found. Running that machine gives exactly ICU's break opportunities, tailorings included,
 * which a second reading of UAX #14 would only come close to. Each file has categories of its
 * own; they are written as one list of ranges (the code points where any file's category
 * changes) and each file's category for every range.
 */
private fun lineBreakTablesKt(packageName: String): String = buildString {
    val icu = VersionInfo.ICU_VERSION
    val unicode = UCharacter.getUnicodeVersion()
    val choices = ruleChoices()
    val names = choices.values.distinct()
    val files = names.map { BrkFile.read(it) }
    val starts = (0..0x10FFFF).filter { c -> c == 0 || files.any { it.categories[c] != it.categories[c - 1] } }
    appendLine("// Written by the build from ICU ${icu.major}.${icu.minor} (Unicode ${unicode.major}.${unicode.minor}), see GenerateLineBreaks in build-logic.")
    appendLine("package $packageName")
    appendLine()
    appendLine("/** ICU ${icu.major}.${icu.minor}'s line break rules as state machines, the rules each language uses, and Unicode's emoji properties. */")
    appendLine("internal object LineBreakTables {")
    appendLine("    /** Where the rule sets' categories change: ${starts.size} code points, as differences in groups of seven bits. */")
    appendLine("    private val starts: IntArray by lazy {")
    appendLine("        readStarts(")
    appendChunks(base64(varints(starts.zipWithNext { a, b -> b - a })), indent = 12)
    appendLine("        )")
    appendLine("    }")
    appendLine()
    appendLine("    /** The rule sets: ${names.joinToString { "$it.brk" }}. */")
    appendLine("    private val sets: Array<Lazy<LineBreakRules>> = arrayOf(")
    for ((i, file) in files.withIndex()) {
        appendLine("        // ${names[i]}.brk: ${file.categoryCount} categories, ${file.stateCount} states.")
        appendLine("        lazy {")
        appendLine("            LineBreakRules(")
        appendLine("                starts = starts,")
        appendLine("                categoryCount = ${file.categoryCount},")
        appendLine("                lookAheadSize = ${file.lookAheadSize},")
        appendLine("                beginRequired = ${file.beginRequired},")
        appendLine("                categories = decodeBase64(")
        appendChunks(base64(ByteArray(starts.size) { file.categories[starts[it]].toByte() }), indent = 20)
        appendLine("                ),")
        appendLine("                table = decodeBase64(")
        appendChunks(base64(file.table), indent = 20)
        appendLine("                ),")
        appendLine("            )")
        appendLine("        },")
    }
    appendLine("    )")
    appendLine()
    appendLine("    /**")
    appendLine("     * The rule set ICU uses without a strictness for a language (\"ja\", or with its script,")
    appendLine("     * \"zh_Hant\"; \"\" for every language without rules of its own).")
    appendLine("     */")
    appendLine("    private val choices: Map<String, Int> = mapOf(")
    for ((locale, file) in choices) appendLine("        \"$locale\" to ${names.indexOf(file)},")
    appendLine("    )")
    appendLine()
    appendLine("    /** The rules for [language] (and [script]). */")
    appendLine("    fun rules(language: String, script: String): LineBreakRules {")
    appendLine("        val choice = choices[\"${'$'}{language}_${'$'}script\"] ?: choices[language] ?: choices.getValue(\"\")")
    appendLine("        return sets[choice].value")
    appendLine("    }")
    for ((name, property) in listOf("emoji" to UProperty.EMOJI, "emojiModifier" to UProperty.EMOJI_MODIFIER, "emojiBase" to UProperty.EMOJI_MODIFIER_BASE)) {
        appendLine()
        appendLine("    /** The code points with the property ${UCharacter.getPropertyName(property, UProperty.NameChoice.LONG)}, as first and last of each range. */")
        appendLine("    val $name: IntArray = intArrayOf(")
        for (line in propertyRanges(property).chunked(12)) appendLine("        ${line.joinToString(" ") { "0x%X,".format(it) }}")
        appendLine("    )")
    }
    appendLine("}")
}

/**
 * The rule file each locale of ICU's break iterator data takes for line breaking without a
 * strictness ("line"), under "" for the root, which every locale not listed uses.
 */
private fun ruleChoices(): Map<String, String> {
    val base = "${dataPath()}/brkitr"
    val loader = BrkFile::class.java.classLoader
    // A locale's entry, or else its parent's ("zh_Hant_HK", "zh_Hant", "zh"), or else root's.
    fun entry(locale: String, key: String): String {
        var name = locale
        while (true) {
            val found = runCatching { UResourceBundle.getBundleInstance(base, name, loader).get("boundaries").getString(key) }.getOrNull()
            if (found != null) return found.removeSuffix(".brk")
            check(name != "root") { "root has no $key" }
            name = if ('_' in name) name.substringBeforeLast('_') else "root"
        }
    }
    val root = entry("root", "line")
    val out = LinkedHashMap<String, String>()
    out[""] = root
    val installed = UResourceBundle.getBundleInstance(base, "res_index", loader).get("InstalledLocales")
    val locales = (0 until installed.size).map { installed.get(it).key }.sorted()
    for (locale in locales) {
        val file = entry(locale, "line")
        if (file != root) out[locale] = file
    }
    // A language's own entry stands for all its regions; a script's entry only where it differs.
    return out.filterKeys { key -> key.isEmpty() || key.count { it == '_' } == 0 || out[key] != out[key.substringBefore('_')] }
        .mapKeys { (key, _) -> key.split('_').take(2).joinToString("_") }
}

/** Where ICU4J keeps its data: under icudt<version>b, or under icudata since version 74. */
private fun dataPath(): String {
    val loader = BrkFile::class.java.classLoader
    return listOf("icudt${VersionInfo.ICU_VERSION.major}b", "icudata").map { "com/ibm/icu/impl/data/$it" }
        .first { loader.getResource("$it/brkitr/root.res") != null }
}

/** [text] as string literals of at most 120 characters, one per line. */
private fun StringBuilder.appendChunks(text: String, indent: Int) {
    for (chunk in text.chunked(120)) appendLine("${" ".repeat(indent)}\"$chunk\",")
}

private fun base64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

/** Each number in groups of seven bits, the low group first, every group but the last with the high bit set. */
private fun varints(values: List<Int>): ByteArray {
    val out = ByteArrayOutputStream()
    for (value in values) {
        var v = value
        while (v >= 0x80) {
            out.write(v and 0x7f or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }
    return out.toByteArray()
}

/** The code points with [property], as first and last (inclusive) of each range. */
private fun propertyRanges(property: Int): List<Int> {
    val out = ArrayList<Int>()
    var start = -1
    for (c in 0..0x110000) {
        val has = c <= 0x10FFFF && UCharacter.hasBinaryProperty(c, property)
        if (has && start < 0) start = c
        if (!has && start >= 0) {
            out += start
            out += c - 1
            start = -1
        }
    }
    return out
}

/**
 * One of ICU's compiled break rule files (data format "Brk ", versions 6 and 7, as written
 * since ICU 68): its forward state table and the code points' categories. The reverse table
 * and the rules' source are not needed for going forward through a text.
 */
private class BrkFile(
    val categoryCount: Int,
    val stateCount: Int,
    val lookAheadSize: Int,
    val beginRequired: Boolean,
    /** Rows of one byte each: accepting, look-ahead, tag index, then the next state per category. */
    val table: ByteArray,
    /** The category of every code point. */
    val categories: IntArray,
) {
    companion object {
        fun read(name: String): BrkFile {
            val path = "${dataPath()}/brkitr/$name.brk"
            val bytes = BrkFile::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
                ?: error("$path is not in ICU4J ${VersionInfo.ICU_VERSION}")
            val buffer = ByteBuffer.wrap(bytes)
            // The data's header (UDataInfo) says the byte order: ICU4J's own data is big-endian.
            buffer.order(if (bytes[8].toInt() == 1) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            val headerSize = buffer.getShort(0).toInt() and 0xffff
            check(bytes.copyOfRange(12, 16).decodeToString() == "Brk ") { "$name is not break iterator data" }
            // Format 6 (ICU 68 to 76) and 7 (since ICU 77, without the category for the text's
            // beginning) have the layout read here.
            check(bytes[16].toInt() in 6..7) { "$name has data format ${bytes[16]}, only 6 and 7 are known" }
            fun int(at: Int) = buffer.getInt(headerSize + at)
            check(int(0) == 0xb1a0) { "$name: wrong magic number" }
            val categoryCount = int(12)
            val forward = int(16)
            val trie = int(32)
            val trieLength = int(36)
            val stateCount = int(forward)
            val rowLength = int(forward + 4)
            val lookAheadSize = int(forward + 12)
            val flags = int(forward + 16)
            check(flags and 4 != 0) { "$name has rows of 16 bits; only rows of 8 bits are read" }
            check(rowLength == 3 + categoryCount) { "$name: unexpected row length" }
            val tableStart = headerSize + forward + 20
            val table = bytes.copyOfRange(tableStart, tableStart + stateCount * rowLength)
            val trieBuffer = ByteBuffer.wrap(bytes, headerSize + trie, trieLength).slice().order(buffer.order())
            val codePointTrie = CodePointTrie.fromBinary(null, null, trieBuffer)
            val categories = IntArray(0x110000) { codePointTrie.get(it) }
            check(categories.all { it in 0 until categoryCount }) { "$name: a category out of range" }
            return BrkFile(categoryCount, stateCount, lookAheadSize, flags and 2 != 0, table, categories)
        }
    }
}
