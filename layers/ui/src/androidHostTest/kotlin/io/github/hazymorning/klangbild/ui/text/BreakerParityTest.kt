package io.github.hazymorning.klangbild.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.ScenePalette
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.ui.composeRule
import io.github.hazymorning.klangbild.ui.stage.EmojiText
import io.github.hazymorning.klangbild.ui.stage.StageStyles
import io.github.hazymorning.klangbild.ui.stage.glueParentheses
import io.github.hazymorning.klangbild.ui.stage.glueShortWords
import io.github.hazymorning.klangbild.ui.stage.isMostlyCjk
import io.github.hazymorning.klangbild.ui.stage.titleLadder
import io.github.hazymorning.klangbild.ui.theme.Faces
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
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
 * the texts the tests and fixtures write as titles at every step of the ladder and as the words
 * of songs, and the app's own sentences in the menu's, setup's and rest screen's styles, at the
 * widths those screens give them and at the densities of the pictures.
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
    private val balanced = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

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
        val texts = (testTexts() + strings).distinct()
        check(texts.size > 300) { "only ${texts.size} texts found" }
        val cases = ArrayList<Case>()
        for ((i, text) in texts.withIndex()) {
            val density = DENSITIES[i % DENSITIES.size]
            cases += titleCases(text, density)
            cases += lyricsCases(text, density)
            if (text in strings) cases += sentenceCases(text, density)
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

    /** The text as the stage sets a title: at every step, in its column and in Cover Flow's. */
    private fun titleCases(text: String, density: Float): List<Case> {
        val title = glueShortWords(glueParentheses(EmojiText.annotate(text, dimTo = Color.White.copy(alpha = 0.62f))))
        val cjk = isMostlyCjk(text)
        return titleLadder.flatMap { step ->
            val style = styles.title(step, cjk).merge(TextStyle(lineBreak = LineBreak.Heading))
            listOf(false, true).map { flow -> Case("title ${step.size}sp${if (flow) " flow" else ""}", title, style, px(grid(density, flow).columnWidth, density), density) }
        }
    }

    /** The text as the words of a song, at each of their sizes (as in Lyrics.kt). */
    private fun lyricsCases(text: String, density: Float): List<Case> {
        val part = glueShortWords(glueParentheses(AnnotatedString(text)))
        val cjk = isMostlyCjk(text)
        return LYRICS.map { step ->
            val size = if (cjk) step[0] * StageStyles.CJK_SCALE else step[0]
            val style = faces.titleType(size, step[1].toInt(), lineHeight = size * step[2] + if (cjk) 6f else 0f, tracking = if (cjk) 0f else step[3])
                .merge(TextStyle(lineBreak = LineBreak.Heading, textDirection = TextDirection.Content, textAlign = TextAlign.Left))
            Case("lyrics ${step[0]}sp", part, style, px(grid(density, false).columnWidth, density), density)
        }
    }

    /** One of the app's sentences as the menu explains a line, as the setup's lead and text, and as the rest screen's sentence. */
    private fun sentenceCases(text: String, density: Float): List<Case> {
        val plain = AnnotatedString(text)
        val grid = grid(density, false)
        return listOf(
            Case("menu", plain, faces.textType(18f, 420, lineHeight = 26f).merge(TextStyle(lineBreak = balanced)), px(grid.art - 24f, density), density),
            Case("setup", plain, faces.textType(19f, 420, lineHeight = 27f).merge(TextStyle(lineBreak = balanced)), px(grid.columnWidth, density), density),
            Case("setup lead", plain, faces.textType(21f, 520, lineHeight = 27f).merge(TextStyle(lineBreak = balanced)), px(grid.columnWidth, density), density),
            Case("rest", plain, faces.textType(19f, 420, lineHeight = 27f).merge(TextStyle(lineBreak = balanced, textAlign = TextAlign.Center)), px(480f, density), density),
        )
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
        for (dir in listOf("ui", "art-testing", "domain-testing", "app-testing")) {
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

        /** The lyrics' ladder: size (sp), weight, leading, tracking (em), as in Lyrics.kt. */
        val LYRICS = listOf(floatArrayOf(30f, 420f, 1.15f, -0.005f), floatArrayOf(26f, 430f, 1.16f, 0f), floatArrayOf(24f, 430f, 1.16f, 0f))
    }
}
