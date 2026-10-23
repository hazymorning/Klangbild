package io.github.hazymorning.klangbild.gradle

import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.DateFormatSymbols
import com.ibm.icu.text.DateTimePatternGenerator
import com.ibm.icu.text.DecimalFormat
import com.ibm.icu.text.NumberFormat
import com.ibm.icu.text.PluralRules
import com.ibm.icu.text.SimpleDateFormat
import com.ibm.icu.util.LocaleData
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.VersionInfo
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Writes the app's texts as Kotlin, the same for every platform: the words in strings.xml
 * (values/ is English, values-de/ German; a word a language lacks is the English one, as Android
 * finds it among its resources) and how each region of those languages writes numbers and dates,
 * from Unicode's CLDR as ICU has it.
 */
@CacheableTask
abstract class GenerateTexts : DefaultTask() {
    /** The folders values/ and values-<language>/, each with its strings.xml. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val strings: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = strings.get().asFile
        val languages = root.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .associate { (if (it.name == "values") "en" else it.name.removePrefix("values-")) to read(File(it, "strings.xml")) }
            .toSortedMap(compareBy<String> { it != "en" }.thenBy { it })
        val english = languages.getValue("en")
        for ((language, words) in languages) {
            val rules = PluralRules.forLocale(ULocale(language))
            check(rules.keywords == setOf("one", "other") && rules.select(1.0) == "one" && rules.select(2.0) == "other") {
                "$language counts differently from one and more; Texts.plural knows only that"
            }
            for (name in words.strings.keys + words.plurals.keys + words.arrays.keys) {
                check(name in english.strings || name in english.plurals || name in english.arrays) { "$name in $language has no English text" }
            }
        }
        val out = output.get().asFile.resolve(PACKAGE.replace('.', '/'))
        out.deleteRecursively()
        out.mkdirs()
        File(out, "Strings.kt").writeText(stringsKt(english))
        File(out, "Words.kt").writeText(wordsKt(languages))
        File(out, "Regions.kt").writeText(regionsKt(languages.keys))
    }

    /** A language's strings.xml. */
    private class Language(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, List<String>>,
    )

    private fun read(file: File): Language {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val arrays = LinkedHashMap<String, List<String>>()
        for (element in doc.documentElement.childElements()) {
            val name = element.getAttribute("name")
            when (element.tagName) {
                "string" -> strings[name] = text(element)
                "plurals" -> plurals[name] = element.childElements().associate { it.getAttribute("quantity") to text(it) }
                "string-array" -> arrays[name] = element.childElements().map(::text)
                else -> error("${file.path}: <${element.tagName}> is not a text")
            }
        }
        return Language(strings, plurals, arrays)
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /**
     * The text of [element] as Android's resource compiler makes it: spans dropped, runs of
     * white space as one space and none at either end (except within double quotes, which go),
     * and its escapes read (\n, \t, \', \", \\, \@, \?, \uXXXX).
     */
    private fun text(element: Element): String {
        val raw = StringBuilder()
        fun collect(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> raw.append(node.nodeValue)
                Node.ELEMENT_NODE -> for (i in 0 until node.childNodes.length) collect(node.childNodes.item(i))
            }
        }
        collect(element)
        val out = StringBuilder()
        var quoted = false
        var space = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '"' -> quoted = !quoted
                c == '\\' -> {
                    val next = raw.getOrNull(++i) ?: error("${element.getAttribute("name")}: a backslash at the end")
                    if (space) out.append(' ').also { space = false }
                    when (next) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(raw.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        '\'', '"', '\\', '@', '?', '#' -> out.append(next)
                        else -> error("${element.getAttribute("name")}: unknown escape \\$next")
                    }
                }
                c == '\'' && !quoted -> error("${element.getAttribute("name")}: an apostrophe must be escaped (\\') or typographic (’)")
                // Only ASCII white space runs together; a no-break space stays as it is.
                c in " \t\n\r\u000B\u000C" && !quoted -> space = out.isNotEmpty()
                else -> {
                    if (space) out.append(' ').also { space = false }
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    private fun stringsKt(english: Language): String = buildString {
        header()
        appendLine("/** The app's texts, by their names in strings.xml. */")
        appendLine("object Strings {")
        english.strings.entries.forEachIndexed { i, (name, text) ->
            appendLine("    /** ${doc(text)} */")
            appendLine("    val ${camel(name)} = StringId($i)")
        }
        appendLine("}")
        appendLine()
        appendLine("/** The app's texts with a form for one and one for more, by their names in strings.xml. */")
        appendLine("object Plurals {")
        english.plurals.entries.forEachIndexed { i, (name, forms) ->
            appendLine("    /** ${doc(forms.getValue("other"))} */")
            appendLine("    val ${camel(name)} = PluralId($i)")
        }
        appendLine("}")
    }

    private fun wordsKt(languages: Map<String, Language>): String = buildString {
        header()
        val english = languages.getValue("en")
        appendLine("/** The words of each language the app has, by its language code. */")
        appendLine("internal val WORDS: Map<String, Words> = mapOf(")
        for ((language, words) in languages) {
            appendLine("    ${literal(language)} to Words(")
            appendLine("        strings = arrayOf(")
            for (name in english.strings.keys) appendLine("            ${literal(words.strings[name] ?: english.strings.getValue(name))},")
            appendLine("        ),")
            for (quantity in listOf("one", "other")) {
                appendLine("        $quantity = arrayOf(")
                for (name in english.plurals.keys) {
                    val forms = words.plurals[name] ?: english.plurals.getValue(name)
                    appendLine("            ${literal(forms[quantity] ?: forms.getValue("other"))},")
                }
                appendLine("        ),")
            }
            appendLine("        countWords = arrayOf(${(words.arrays["count_words"] ?: english.arrays.getValue("count_words")).joinToString { literal(it) }}),")
            appendLine("    ),")
        }
        appendLine(")")
    }

    /**
     * Every region of [languages] that CLDR knows (en-GB, de-AT, ...) and each language itself,
     * with how it writes numbers, percentages, dates and the time of day.
     */
    private fun regionsKt(languages: Set<String>): String = buildString {
        header()
        val locales = (languages.map { ULocale(it) } + ULocale.getAvailableLocales().filter {
            it.language in languages && it.country.isNotEmpty() && it.script.isEmpty() && it.variant.isEmpty()
        }).distinct().sortedBy { it.toLanguageTag() }
        val names = LinkedHashMap<List<String>, String>()
        fun names(list: List<String>): String = names.getOrPut(list) { "NAMES_${names.size}" }
        val entries = locales.map { locale ->
            val number = NumberFormat.getNumberInstance(locale) as DecimalFormat
            val percent = NumberFormat.getPercentInstance(locale) as DecimalFormat
            val symbols = number.decimalFormatSymbols
            check(percent.groupingSize == number.groupingSize && percent.secondaryGroupingSize == number.secondaryGroupingSize) { "$locale groups percentages differently" }
            check(number.negativeSuffix.isEmpty() && percent.positivePrefix.isEmpty()) { "$locale writes numbers in a way Texts doesn't know" }
            val pattern = DateTimePatternGenerator.getInstance(locale).getBestPattern(DATE_SKELETON)
            check(pattern.replace(Regex("'[^']*'"), "").none { it.isLetter() && it !in "EMd" }) { "$locale writes dates as $pattern, which Texts can't" }
            val dates = DateFormatSymbols.getInstance(locale)
            val months = names(dates.getMonths(DateFormatSymbols.FORMAT, DateFormatSymbols.WIDE).toList())
            // Monday first, as kotlinx-datetime counts the days of the week.
            val weekdays = dates.getWeekdays(DateFormatSymbols.FORMAT, DateFormatSymbols.WIDE)
            val days = names((2..8).map { weekdays[if (it == 8) 1 else it] })
            // Android's own guess when no 12 or 24 hour clock is set: whether the region's long time has 24 hours.
            val clock24 = (DateFormat.getTimeInstance(DateFormat.LONG, locale) as SimpleDateFormat).toPattern().contains('H')
            locale.toLanguageTag() to listOf(
                literal(symbols.decimalSeparatorString), literal(symbols.groupingSeparatorString), literal(number.negativePrefix),
                literal(percent.positiveSuffix), number.groupingSize.toString(), number.secondaryGroupingSize.toString(),
                number.minimumGroupingDigits.toString(), literal(pattern), months, days,
                literal(dates.amPmStrings[0]), literal(dates.amPmStrings[1]), clock24.toString(),
            )
        }
        for ((list, name) in names) appendLine("private val $name = arrayOf(${list.joinToString { literal(it) }})")
        appendLine()
        appendLine("/** How each language the app has, and each of its regions, writes numbers and dates (CLDR ${LocaleData.getCLDRVersion()}, by way of ICU ${VersionInfo.ICU_VERSION}). */")
        appendLine("internal val REGIONS: Map<String, Region> = mapOf(")
        for ((tag, values) in entries) {
            appendLine("    ${literal(tag)} to Region(${values.joinToString()}),")
        }
        appendLine(")")
    }

    private fun StringBuilder.header() {
        appendLine("// Written by the build from strings.xml and Unicode's CLDR (GenerateTexts in build-logic).")
        appendLine("package $PACKAGE")
        appendLine()
    }

    private fun camel(name: String): String = name.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercaseChar) }.joinToString("")

    private fun doc(text: String): String = text.replace("*/", "*&#47;").replace("\n", " ").let { if (it.length > 90) it.take(89) + "…" else it }

    private fun literal(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                // Spaces that are not plain spaces, and characters that are not seen, stay visible in the code.
                else -> if (c.code < 0x20 || c.code in INVISIBLE) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private companion object {
        const val PACKAGE = "io.github.hazymorning.klangbild.text"

        val INVISIBLE = listOf(0x7f..0xa0, 0xad..0xad, 0x2000..0x200f, 0x2028..0x202f, 0x205f..0x206f, 0xfeff..0xfeff).flatMap { it }.toSet()

        /** The date with its weekday: "Saturday, October 10". */
        const val DATE_SKELETON = "EEEEMMMMd"
    }
}
