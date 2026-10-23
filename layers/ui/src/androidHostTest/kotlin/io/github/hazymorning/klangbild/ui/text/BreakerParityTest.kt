package io.github.hazymorning.klangbild.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.ScenePalette
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.ui.composeRule
import io.github.hazymorning.klangbild.ui.stage.EmojiText
import io.github.hazymorning.klangbild.ui.stage.StageStyles
import io.github.hazymorning.klangbild.ui.stage.glueBetweenDashes
import io.github.hazymorning.klangbild.ui.stage.glueParentheses
import io.github.hazymorning.klangbild.ui.stage.glueShortWords
import io.github.hazymorning.klangbild.ui.stage.isMostlyCjk
import io.github.hazymorning.klangbild.ui.stage.lyricsLadder
import io.github.hazymorning.klangbild.ui.stage.lyricsStyle
import io.github.hazymorning.klangbild.ui.stage.titleLadder
import io.github.hazymorning.klangbild.ui.theme.Faces
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.textType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * [BalancedBreaker] breaks the texts the screens set where Android's balanced line break does:
 * the texts the tests and fixtures write, and a few for the hard cases ([EXTRA]), as titles at
 * every step of the ladder (also glued between a movement's dashes) and as the words of songs,
 * and the app's own sentences in the menu's, setup's and rest screen's styles with the setup's
 * address, at the widths those screens give them and at each density of the pictures. Texts in
 * Japanese or Chinese also break by those languages' rules.
 *
 * Robolectric runs Android 12's line breaker and ICU 68 (whatever the SDK), and drops the
 * strictness of a LineBreak, so its breaks are those of ICU's default rules, as the breaker's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BreakerParityTest {
    @get:Rule
    val compose = composeRule()

    private lateinit var resolver: FontFamily.Resolver
    private lateinit var faces: Faces
    private val styles by lazy { StageStyles(ScenePalette(Appearance.DARK, 0xFF101010.toInt(), 0xFFEEEEEE.toInt(), 0xFFBBBBBB.toInt(), 0xFF999999.toInt()), faces) }

    /** A text in a style (with the balanced line break it had) at a width (px) and a density. */
    private class Case(val label: String, val text: AnnotatedString, val style: TextStyle, val width: Int, val density: Float)

    @Test
    fun `the texts the screens set break as on Android`() {
        // The typefaces as the screens have them.
        compose.setContent {
            resolver = LocalFontFamilyResolver.current
            faces = faces()
        }
        compose.waitForIdle()
        val strings = appStrings()
        val texts = (testTexts() + EXTRA + strings).distinct()
        check(texts.size > 300) { "only ${texts.size} texts found" }
        val cases = ArrayList<Case>()
        for (density in DENSITIES) {
            for (text in texts) {
                cases += titleCases(text, density)
                cases += lyricsCases(text, density)
                if (text in strings) cases += sentenceCases(AnnotatedString(text), density)
            }
            for (address in addresses()) cases += sentenceCases(address, density).filter { it.label == "setup" }.map { Case("setup address", it.text, it.style, it.width, it.density) }
        }
        val measurers = DENSITIES.associateWith { TextMeasurer(resolver, Density(it), LayoutDirection.Ltr, cacheSize = 0) }
        val breakers = measurers.mapValues { BalancedBreaker(it.value) }
        val counts = LinkedHashMap<String, IntArray>()
        val failures = ArrayList<String>()
        for (case in cases) {
            val android = measurers.getValue(case.density).measure(case.text, case.style, constraints = Constraints(maxWidth = case.width))
            val expected = IntArray(android.lineCount) { android.getLineEnd(it) }
            val actual = breakers.getValue(case.density).lineEnds(case.text, case.style, case.width)
            val count = counts.getOrPut(case.label) { IntArray(2) }
            count[0]++
            if (!expected.contentEquals(actual)) {
                count[1]++
                if (failures.size < 40) failures += "${case.label} @${case.density} w=${case.width} \"${case.text.text}\" android=${expected.toList()} breaker=${actual.toList()}"
            }
        }
        val wrong = counts.values.sumOf { it[1] }
        println("${cases.size - wrong} of ${cases.size} cases break as on Android (${texts.size} texts)")
        for ((label, count) in counts) println("  $label: ${count[0] - count[1]} of ${count[0]}")
        for (f in failures) println("  differs: $f")
        assertThat(wrong, name = "cases that break differently").isEqualTo(0)
    }

    /** The text as the stage sets a title: at every step, glued between dashes too, in its column and in Cover Flow's. */
    private fun titleCases(text: String, density: Float): List<Case> {
        val title = glueShortWords(glueParentheses(EmojiText.annotate(text, dimTo = Color.White.copy(alpha = 0.62f))))
        val cjk = isMostlyCjk(text)
        val versions = listOfNotNull(title, glueBetweenDashes(title))
        return titleLadder.flatMap { step ->
            locales(cjk).flatMap { locale ->
                val style = styles.title(step, cjk).merge(TextStyle(localeList = locale))
                versions.flatMap { t -> listOf(false, true).map { flow -> Case("title ${step.size}sp${if (flow) " flow" else ""}", t, style, px(grid(density, flow).columnWidth, density), density) } }
            }
        }
    }

    /** The text as the words of a song, at each of their sizes. */
    private fun lyricsCases(text: String, density: Float): List<Case> {
        val part = glueShortWords(glueParentheses(AnnotatedString(text)))
        val cjk = isMostlyCjk(text)
        return lyricsLadder.flatMap { step ->
            locales(cjk).map { locale ->
                Case("lyrics ${step.size}sp", part, lyricsStyle(faces, step, cjk).merge(TextStyle(localeList = locale)), px(grid(density, false).columnWidth, density), density)
            }
        }
    }

    /** Japanese, Chinese and Korean texts in no language, and in Japanese and Chinese, whose rules differ a little. */
    private fun locales(cjk: Boolean): List<LocaleList?> = if (cjk) listOf(null, LocaleList("ja"), LocaleList("zh")) else listOf(null)

    /**
     * A text as the menu explains a line, as the setup's lead and text, and as the rest screen's
     * sentence (in their styles, as MenuScreen.kt, SetupScreen.kt and RestScreen.kt set them).
     */
    private fun sentenceCases(text: AnnotatedString, density: Float): List<Case> {
        val grid = grid(density, false)
        return listOf(
            Case("menu", text, faces.textType(18f, 420, lineHeight = 26f).merge(TextStyle(lineBreak = sentenceLineBreak)), px(grid.art - 24f, density), density),
            Case("setup", text, faces.textType(19f, 420, lineHeight = 27f).merge(TextStyle(lineBreak = sentenceLineBreak)), px(grid.columnWidth, density), density),
            Case("setup lead", text, faces.textType(21f, 520, lineHeight = 27f).merge(TextStyle(lineBreak = sentenceLineBreak)), px(grid.columnWidth, density), density),
            Case("rest", text, faces.textType(19f, 420, lineHeight = 27f).merge(TextStyle(lineBreak = sentenceLineBreak, textAlign = TextAlign.Center)), px(480f, density), density),
        )
    }

    /** The setup's address in both languages, with its figures a little apart, as SetupScreen.kt writes it. */
    private fun addresses(): List<AnnotatedString> = listOf(TestTexts.english(), TestTexts.german).flatMap { t ->
        listOf("192.168.178.23:8765", "10.0.0.5:8765", "[fd00::1:2]:8765").map { url ->
            buildAnnotatedString {
                append(t[Strings.setupInBrowser])
                append(" ")
                withStyle(SpanStyle(letterSpacing = 0.03.em)) { append(url) }
            }
        }
    }

    private fun grid(density: Float, flow: Boolean) = StageGrid(ScreenSpec((960 * density).roundToInt(), (540 * density).roundToInt(), density), flow)

    private fun px(dp: Float, density: Float) = (dp * density).roundToInt()

    /** Every string of strings.xml, in English and German, with its line feeds. */
    private fun appStrings(): Set<String> = listOf("values", "values-de").flatMap { dir ->
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(System.getProperty("klangbild.strings")!!, "$dir/strings.xml"))
        val strings = doc.documentElement.getElementsByTagName("string")
        (0 until strings.length).map { (strings.item(it) as Element).textContent.replace("\\n", "\n") }
    }.filter { s -> s.any { it.isLetter() } }.toSet()

    /** Every text the tests and fixtures write: the string literals with words in them, the words of synced lyrics without their times. */
    private fun testTexts(): List<String> {
        val texts = LinkedHashSet<String>()
        for (dir in listOf("ui/src/androidHostTest", "ui/src/commonTest", "art-testing/src", "domain-testing/src", "app-testing/src")) {
            val root = File(System.getProperty("klangbild.corpus.$dir")!!)
            // This test's own words are left out.
            root.walk().filter { it.isFile && it.name.endsWith(".kt") && "/ui/text/" !in it.path.replace('\\', '/') }.sortedBy { it.path }.forEach { file ->
                for (literal in literals(file.readText())) {
                    for (line in literal.split('\n')) {
                        val words = line.replace(Regex("^\\s*\\[\\d+:\\d+(\\.\\d+)?]"), "").trim()
                        if (isWords(words)) texts += words
                    }
                }
            }
        }
        return texts.toList()
    }

    private fun isWords(s: String): Boolean =
        s.count { it.isLetter() } >= 2 && '$' !in s && !s.matches(Regex("[a-z0-9_./%-]+")) && !s.matches(Regex("[A-Za-z0-9_]+\\.[A-Za-z0-9_.]+"))

    /** The string literals of Kotlin source [src], escapes read. */
    private fun literals(src: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it }
                    out += src.substring(i + 3, end)
                    i = end + 3
                }
                src[i] == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < src.length && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            when (val e = src[i + 1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'u' -> {
                                    sb.append(src.substring(i + 2, i + 6).toInt(16).toChar())
                                    i += 4
                                }
                                else -> sb.append(e)
                            }
                            i += 2
                        } else {
                            sb.append(src[i++])
                        }
                    }
                    out += sb.toString()
                    i++
                }
                // Names in backticks may hold apostrophes.
                src[i] == '`' -> i = src.indexOf('`', i + 1).let { if (it < 0) src.length else it + 1 }
                src[i] == '\'' -> i += if (src.startsWith("'\\", i)) src.indexOf('\'', i + 2) - i + 1 else 3
                else -> i++
            }
        }
        return out
    }

    private companion object {
        /** The densities of the pictures: mdpi, xhdpi (1080p) and xxxhdpi (4K). */
        val DENSITIES = listOf(1f, 2f, 4f)

        /**
         * Texts for what the others lack: lines that score the same ("la la la"), right-to-left text
         * with numbers and Latin in it, and Japanese and Chinese with long vowel marks, small kana,
         * ellipses and dashes.
         */
        val EXTRA = listOf(
            "la la la la la",
            "Yeah Yeah Yeah",
            "Na na na na na na na na na na na na",
            "Bye bye bye bye bye bye",
            "حبيبي 2024 (Remix)",
            "شمس – Acoustic Live",
            "שיר 3 – Version 2",
            "メロディーを探して",
            "ドラッグストアーの夜",
            "クリスマス・ストーリー（ライブ）",
            "月光下的心——现场版",
            "夜曲……第二部",
        )
    }
}
