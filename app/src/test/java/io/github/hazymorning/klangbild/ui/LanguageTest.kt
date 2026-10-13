package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.config.ActionState
import io.github.hazymorning.klangbild.config.FavoriteState
import io.github.hazymorning.klangbild.config.LyricsChoice
import io.github.hazymorning.klangbild.config.MenuContext
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.MenuRow
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.config.ViewChoice
import io.github.hazymorning.klangbild.config.QuickAction
import io.github.hazymorning.klangbild.config.QuickContext
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.QuickMenu
import io.github.hazymorning.klangbild.config.RoomLights
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsMenu
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.ma.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.domain.SleepChoice
import io.github.hazymorning.klangbild.domain.SpeakerGroup
import io.github.hazymorning.klangbild.domain.SpeakerLabel
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.setup.SetupPage
import io.github.hazymorning.klangbild.setup.SetupServer
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import io.github.hazymorning.klangbild.ui.theme.textType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Both languages, everywhere: every menu line, value and explanation, the quick settings, the
 * status line, the rest and setup screens and the phone's setup page say nothing in German
 * when English is chosen; every menu line and the quick settings fit their room in both
 * languages; and no English sentence is longer than its German one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LanguageTest {
    @get:Rule
    val compose = composeRule()

    private val en = TestTexts.english()
    private val de = TestTexts.german

    /** A resource file's texts: strings, plural forms and array items, by name. */
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val out = LinkedHashMap<String, String>()
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val e = root.childNodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> out[name] = e.textContent
                "plurals", "string-array" -> {
                    val items = e.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        out["$name:${item.getAttribute("quantity").ifEmpty { j.toString() }}"] = item.textContent
                    }
                }
            }
        }
        return out
    }

    private fun words(s: String) = Regex("""\p{L}+""").findAll(s).map { it.value.lowercase() }.toList()

    /** Words of the German texts that no English text has: none of them may show in English. */
    private val germanOnly: Set<String> by lazy {
        val english = strings("values").values.flatMap(::words).toSet()
        strings("values-de").values.flatMap(::words).filter { it !in english }.toSet()
    }

    private fun assertEnglish(where: String, text: String?) {
        if (text == null) return
        assertWithMessage("%s says \"%s\"", where, text).that(words(text).filter { it in germanOnly }).isEmpty()
        assertWithMessage("%s says \"%s\"", where, text).that(text).doesNotContainMatch("[äöüÄÖÜß„]")
    }

    // ---- what the menu can show ------------------------------------------------------------

    private val configured = Settings(haUrl = "http://192.168.1.10:8123", haToken = "t", entityId = "media_player.living_room")

    /**
     * Every value of every line: each setting stepped through its values, in every state of the
     * connections, the sleep timer and the language.
     */
    private fun allRows(t: Texts): List<MenuRow> {
        val bases = listOf(
            Settings(),
            configured,
            configured.copy(maUrl = "http://192.168.1.5:8095", maToken = "t"),
            configured.copy(roomLights = RoomLights("light.left", "light.middle", "light.right")),
        )
        val contexts = buildList {
            for (link in LinkState.entries + null) for (ma in MaLink.entries + null) add(MenuContext(link, ma = ma))
            for (sleep in SleepChoice.entries) for (kind in MediaKind.entries + null) {
                add(MenuContext(LinkState.CONNECTED, sleep = sleep, sleepAt = Fixtures.at(23, 10), kind = kind))
            }
            for (language in AppLanguage.entries) add(MenuContext(LinkState.CONNECTED, language = language))
            for (appearance in io.github.hazymorning.klangbild.color.Appearance.entries) add(MenuContext(LinkState.CONNECTED, appearance))
        }
        val out = LinkedHashSet<MenuRow>(SettingsMenu.groups(t))
        fun rows(settings: Settings, ctx: MenuContext) = SettingsMenu.sections.flatMap { SettingsMenu.rows(settings, ctx, t, it) }
        for (base in bases) {
            for (ctx in contexts) out += rows(base, ctx)
            for (entry in MenuEntry.entries) {
                var s = base
                repeat(STEPS) {
                    s = SettingsMenu.step(entry, s, +1, wrap = true) ?: return@repeat
                    for (ctx in contexts.take(3)) out += rows(s, ctx)
                }
            }
        }
        return out.toList()
    }

    /**
     * The quick settings in every state they can be in; with [buttons] also with buttons of one's
     * own, of every kind and in every state, and with [party] also during a party.
     */
    private fun allQuick(t: Texts, buttons: Boolean = false, party: Boolean = buttons): List<QuickState> = buildList {
        val own = if (!buttons) emptyList() else listOf(
            QuickAction("Ungroup", "script.ungroup"), QuickAction("Movie", "scene.movie"),
            QuickAction("Bell", "button.bell"), QuickAction("Party", "switch.party"),
        )
        val settings = listOf(Settings(), configured.copy(roomLights = RoomLights("light.left", null, null))).map { it.copy(quickActions = own) }
        val states = if (!buttons) listOf(emptyMap()) else ActionState.entries.map { st -> own.indices.associateWith { st } }
        for (s in settings) for (music in listOf(true, false)) for (landscape in listOf(true, false)) for (actions in states) {
            for (favorite in FavoriteState.entries + null) for (shuffle in listOf(true, false, null)) for (pending in ViewChoice.entries + null) {
                for (lyrics in LyricsChoice.entries + null) for (code in if (party) listOf(true, false, null) else listOf(null)) {
                    val rows = QuickMenu.rows(s, QuickContext(landscape, pending, music, favorite, shuffle, actions, lyrics, code), t)
                    add(QuickState(rows, 0))
                }
            }
        }
    }

    @Test
    fun theMenuSaysNothingInGerman() {
        assertEnglish("menu heading", en[R.string.menu_heading])
        assertEnglish("menu hint", en[R.string.menu_hint])
        assertEnglish("menu hint on the groups", en[R.string.menu_hint_groups])
        for (section in MenuSection.entries) {
            assertEnglish("section ${section.name}", en[section.title])
            assertEnglish("section ${section.name} explanation", en[section.about])
        }
        for (row in allRows(en)) {
            assertEnglish("${row.entry} label", row.label)
            assertEnglish("${row.entry} value", row.value)
            assertEnglish("${row.entry} explanation", row.description)
        }
    }

    @Test
    fun theQuickSettingsSayNothingInGerman() {
        for (quick in allQuick(en, buttons = true)) for (row in quick.rows) {
            assertEnglish("quick ${row.item} label", row.label)
            assertEnglish("quick ${row.item} value", row.value)
        }
        for (item in QuickItem.entries) for (value in QuickMenu.values(item, en)) assertEnglish("quick ${item.name} value", value)
    }

    @Test
    fun theStatusLineSaysNothingInGerman() {
        val groups = listOf(
            SpeakerGroup("Living room", listOf("Kitchen", "Bath")),
            SpeakerGroup("Living room", listOf("Kitchen")),
            SpeakerGroup(null, listOf("Kitchen", "Bath")),
            SpeakerGroup(null, (1..14).map { "Room $it" }),
            SpeakerGroup("Living room", listOf("Kitchen"), everywhere = true),
        ).map { SpeakerLabel.group(it, en) }
        val volumes = listOf(SpeakerLabel.volume(0.35f, false, en), SpeakerLabel.volume(0.35f, true, en), null)
        for (status in PlayerStatus.entries) for (muted in listOf(true, false)) for (flash in listOf(true, false)) {
            for (volume in volumes) for (group in groups + null) for (always in listOf(true, false)) {
                for (pace in listOf(null, TimeText.speed(1.5f, en), StageTexts.sleep(Fixtures.at(23, 10), en, Fixtures.zone))) {
                    assertEnglish("status line", StageTexts.lead(status, muted, flash, volume, group, always, en, pace))
                }
            }
        }
        val next = TrackInfo("Spiegel im Spiegel", listOf("Tasmin Little", "Martin Roscoe", "Paul Weber"))
        assertEnglish("up next", StageTexts.next(next, en)?.removePrefix("Spiegel im Spiegel"))
        assertEnglish("lost link", StageTexts.line(true, next, en))
        assertEnglish("chapter", en.format(R.string.chapter_number, 7))
        for (reason in RestReason.entries) {
            val lines = io.github.hazymorning.klangbild.ui.RestTexts.lines(reason, "Living room", "media_player.living_room", en)
            assertEnglish("rest $reason", lines.label)
            assertEnglish("rest $reason", lines.sentence)
        }
    }

    /** What the screen shows: one composition, its content swapped. */
    private var screen by mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})

    /** The texts [content] shows. */
    private fun shownTexts(content: @androidx.compose.runtime.Composable () -> Unit): List<String> {
        screen = content
        compose.waitForIdle()
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
    }

    @Test
    fun theScreensSayNothingInGerman() {
        compose.setContent { Fixtures.Still(en) { screen() } }
        val track = Fixtures.track(SyntheticCover.NIGHT_DRIVE)
        val scene = Fixtures.scene(track)
        val shown = mutableListOf<Pair<String, String>>()
        for (r in SetupReason.entries) for (i in listOf(SetupInfo("http://192.168.1.23:8765", "123456", null), SetupInfo(null, "123456", null))) {
            shown += shownTexts { SetupScreen(r, i, scene) }.map { "setup screen $r" to it }
        }
        for (reason in RestReason.entries) {
            shown += shownTexts { RestScreen(reason, "Living room", "media_player.living_room", Fixtures.at(21, 47), zone = Fixtures.zone) }
                .map { "rest screen $reason" to it }
        }
        // English music, so that everything the stage shows must be English.
        val song = TrackInfo("Night drive", listOf("Lumen Drive"), "Neon roads", durationMs = 251_000L)
        val next = TrackInfo("Morning light", listOf("Tasmin Little", "Martin Roscoe", "Paul Weber"))
        for (linkDown in listOf(false, true)) {
            val model = Fixtures.model(song, next = next, group = "Living room", t = en, linkDown = linkDown, scene = scene)
            shown += shownTexts { Stage(model) }.map { "stage" to it }
        }
        assertWithMessage("texts found").that(shown.size).isAtLeast(10)
        for ((where, text) in shown) assertEnglish(where, text)
    }

    @Test
    fun theSetupPageSaysNothingInGerman() {
        val html = SetupPage.html(en, prefilledCode = null)
        val script = Regex("""const T = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        for ((key, value) in Json.parseToJsonElement(script) as JsonObject) assertEnglish("page script $key", (value as JsonPrimitive).content)
        val visible = html
            .replace(Regex("""<script.*?</script>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<style.*?</style>""", RegexOption.DOT_MATCHES_ALL), " ")
        for (attribute in Regex("""(?:placeholder|aria-label|title|alt)="([^"]*)"""").findAll(visible)) assertEnglish("page attribute", attribute.groupValues[1])
        assertEnglish("page", visible.replace(Regex("<[^>]*>"), " "))
        assertWithMessage("the page's language").that(html).contains("<html lang=\"en\">")
        // The lists the page shows: states and weather in English words.
        val found = SetupServer.parseSpeakers(
            """[{"entity_id":"media_player.a","state":"playing","attributes":{}},{"entity_id":"media_player.b","state":"buffering","attributes":{}},
               {"entity_id":"light.a","state":"unavailable","attributes":{}},{"entity_id":"weather.a","state":"lightning-rainy","attributes":{}}]""",
            en,
        ) as io.github.hazymorning.klangbild.setup.DiscoverResult.Ok
        for (state in found.speakers.map { it.state } + found.lights.map { it.state } + found.weather.map { it.state }) assertEnglish("page list", state)
    }

    @Test
    fun everyEnglishTextIsTranslatedAndNoSentenceIsLongerThanItsGerman() {
        val english = strings("values")
        val german = strings("values-de")
        val untranslatable = setOf("app_name", "language_english", "language_german")
        assertWithMessage("texts without a German version").that(english.keys - german.keys - untranslatable).isEmpty()
        assertWithMessage("German texts without an English one").that(german.keys - english.keys).isEmpty()
        for ((name, text) in english) {
            assertEnglish("resource $name", text)
            val sentence = text.trimEnd().let { it.contains(' ') && (it.endsWith('.') || it.endsWith('?')) }
            if (sentence) assertWithMessage("%s: \"%s\" against \"%s\"", name, text, german[name]).that(text.length).isAtMost(german.getValue(name).length)
        }
    }

    // ---- fit -------------------------------------------------------------------------------

    private lateinit var measurer: TextMeasurer
    private var density = 1f

    private fun measuring() {
        compose.setContent {
            measurer = rememberTextMeasurer()
            density = LocalDensity.current.density
        }
        compose.waitForIdle()
    }

    private fun width(text: String, style: TextStyle) = measurer.measure(AnnotatedString(text), style, maxLines = 1).size.width / density

    @Test
    fun everyMenuLineFitsTheColumnInBothLanguages() {
        measuring()
        val column = StageGrid(ScreenSpec.TV_1080).columnWidth
        val label = textType(MenuGrid.LABEL_SP, MenuGrid.LABEL_WEIGHT)
        val value = textType(MenuGrid.VALUE_SP, MenuGrid.VALUE_WEIGHT)
        for (t in listOf(en, de)) {
            val lines = allRows(t).map { Triple(it.label, it.value, it.level != null) }.toSet()
            compose.runOnIdle {
                for ((l, v, slider) in lines) {
                    val needed = width(l, label) + MenuGrid.LABEL_GAP + (if (slider) MenuGrid.SLIDER_WIDTH + MenuGrid.SLIDER_GAP else 0f) + width(v, value)
                    assertWithMessage("\"$l\" with \"$v\" (${t.locale})").that(needed).isAtMost(column)
                }
            }
        }
    }

    /**
     * A row longer than the room glides to show the chosen entry whole (in German the built-in
     * entries alone need it), so every entry fits the room on its own.
     */
    @Test
    fun everyQuickSettingFitsTheScreenInBothLanguages() {
        measuring()
        val layout = StageGrid(ScreenSpec.TV_1080)
        val right = ScreenSpec.TV_1080.widthDp - layout.margin
        val label = textType(QuickGrid.LABEL_SP, QuickGrid.LABEL_WEIGHT)
        val value = textType(QuickGrid.VALUE_SP, QuickGrid.VALUE_WEIGHT)
        for (t in listOf(en, de)) {
            val states = allQuick(t, buttons = true).distinctBy { q -> q.rows.map { it.item to it.value } }
            compose.runOnIdle {
                for (quick in states) {
                    val widths = quickWidths(quick, measurer, label, value, density, widest = true)
                    quick.rows.forEachIndexed { k, row ->
                        assertWithMessage("${row.label}: ${row.values} (${t.locale})").that(QuickGrid.LEFT + widths[k]).isAtMost(right)
                    }
                }
            }
        }
    }

    private companion object {
        /** Enough steps to go through every setting's values. */
        const val STEPS = 14
    }
}
