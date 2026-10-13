package io.github.hazymorning.klangbild.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.text.TestTexts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The quick settings' own buttons: what each kind of entity does, how they are kept, and how
 * they stand in the row (after the built-in entries, only while their entity can be used).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickActionsTest {
    private val en = TestTexts.english()
    private val ungroup = QuickAction("Ungroup", "script.ungroup_speakers")
    private val movie = QuickAction("Movie light", "scene.movie")
    private val party = QuickAction("Party mode", "input_boolean.party")

    @Test
    fun `what a button does follows from its entity`() {
        assertThat(ActionKind.of("script.ungroup")).isEqualTo(ActionKind.RUN)
        assertThat(ActionKind.of("automation.night")).isEqualTo(ActionKind.RUN)
        assertThat(ActionKind.of("scene.movie")).isEqualTo(ActionKind.ACTIVATE)
        assertThat(ActionKind.of("button.restart")).isEqualTo(ActionKind.PRESS)
        assertThat(ActionKind.of("input_button.doorbell")).isEqualTo(ActionKind.PRESS)
        assertThat(ActionKind.of("switch.socket")).isEqualTo(ActionKind.TOGGLE)
        assertThat(ActionKind.of("input_boolean.party")).isEqualTo(ActionKind.TOGGLE)
        assertThat(ActionKind.of("light.floor")).isEqualTo(ActionKind.TOGGLE)
        // Nothing that locks, opens or plays.
        for (other in listOf("lock.front_door", "cover.blinds", "media_player.tv", "sensor.x", "nonsense")) {
            assertThat(ActionKind.of(other)).isNull()
        }
    }

    @Test
    fun `buttons are kept as names and entities, and what cannot be read is left out`() {
        val text = QuickActions.encode(listOf(ungroup, movie))
        assertThat(QuickActions.decode(text)).containsExactly(ungroup, movie).inOrder()
        assertThat(QuickActions.decode("""[{"name":"Front door","entity":"lock.front"},{"name":"Go","entity":"script.go"},7,"x"]"""))
            .containsExactly(QuickAction("Go", "script.go"))
        assertThat(QuickActions.decode("not json")).isEmpty()
        assertThat(QuickActions.decode(null)).isEmpty()
    }

    @Test
    fun `a name is plain and short, and without one the entity's own words stand`() {
        assertThat(QuickActions.valid("  Ungroup   all  ", "script.ungroup")).isEqualTo(QuickAction("Ungroup all", "script.ungroup"))
        assertThat(QuickActions.valid("", "script.ungroup_speakers")).isEqualTo(QuickAction("Ungroup speakers", "script.ungroup_speakers"))
        assertThat(QuickActions.valid("x".repeat(50), "scene.a")!!.name).hasLength(QuickActions.MAX_NAME)
        assertThat(QuickActions.valid("Open", "lock.front")).isNull()
        assertThat(QuickActions.valid("Odd", "script.Not Valid")).isNull()
    }

    @Test
    fun `a script keeps its variables and any button its condition, other kinds take no variables`() {
        val vars = Json.parseToJsonElement("""{"mode":"party","volume":0.4,"rooms":["a","b"]}""").jsonObject
        val grouped = Condition("media_player.wz", "group_members", Condition.Test.ABOVE, "1")
        val party = QuickAction("Party", "script.party", vars, grouped)
        val scene = QuickAction("Movie", "scene.movie", condition = Condition("sun.sun", null, Condition.Test.IS, "below_horizon"))
        assertThat(QuickActions.decode(QuickActions.encode(listOf(party, scene)))).containsExactly(party, scene).inOrder()
        // Variables only go to a script; odd names, too many and odd conditions are no button.
        assertThat(QuickActions.valid("Movie", "scene.movie", vars)!!.variables).isEmpty()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","variables":{"not ok":1}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","variables":${(1..20).joinToString(",", "{", "}") { "\"v$it\":$it" }}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","variables":[1]}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","when":{"entity":"sensor.t","test":"above","value":"warm"}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","when":{"entity":"sensor.t","test":"near","value":"1"}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","when":{"entity":"sensor.t","attribute":"a b","test":"is","value":"1"}}"""))).isNull()
        // What the buttons need followed: their entities and what their conditions look at.
        assertThat(QuickActions.entities(listOf(party, scene))).containsExactly("script.party", "media_player.wz", "scene.movie", "sun.sun").inOrder()
    }

    @Test
    fun `a condition compares like Home Assistant, words without case, numbers as numbers, a list by its count`() {
        fun c(test: Condition.Test, value: String, attribute: String? = null) = Condition("x.y", attribute, test, value)
        val attrs = obj("""{"group_members":["a","b","c"],"volume_level":0.35,"source":"Radio","muted":false}""")
        assertThat(c(Condition.Test.IS, "Playing").holds("playing", attrs)).isTrue()
        assertThat(c(Condition.Test.IS_NOT, "playing").holds("paused", attrs)).isTrue()
        assertThat(c(Condition.Test.IS, "21").holds("21.0", attrs)).isTrue()
        assertThat(c(Condition.Test.ABOVE, "20").holds("21.5", attrs)).isTrue()
        assertThat(c(Condition.Test.BELOW, "20,5").holds("20.4", attrs)).isTrue()
        assertThat(c(Condition.Test.ABOVE, "20").holds("unavailable", attrs)).isFalse()
        assertThat(c(Condition.Test.ABOVE, "1", "group_members").holds("playing", attrs)).isTrue()
        assertThat(c(Condition.Test.IS, "3", "group_members").holds("playing", attrs)).isTrue()
        assertThat(c(Condition.Test.BELOW, "0.5", "volume_level").holds("playing", attrs)).isTrue()
        assertThat(c(Condition.Test.IS, "radio", "source").holds("playing", attrs)).isTrue()
        assertThat(c(Condition.Test.IS, "false", "muted").holds("playing", attrs)).isTrue()
        // An attribute that is not there: never is, always is not. An entity that is not there: never.
        assertThat(c(Condition.Test.IS, "x", "gone").holds("playing", attrs)).isFalse()
        assertThat(c(Condition.Test.IS_NOT, "x", "gone").holds("playing", attrs)).isTrue()
        assertThat(c(Condition.Test.IS_NOT, "x").holds(null, null)).isFalse()
    }

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `the store keeps the buttons`(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = SettingsStore(context)
        val grouped = ungroup.copy(
            variables = Json.parseToJsonElement("""{"mode":"all"}""").jsonObject,
            condition = Condition("media_player.wz", "group_members", Condition.Test.ABOVE, "1"),
        )
        val values = listOf(OwnValue("Outside", "sensor.outside"), OwnValue("", "weather.home", "temperature"))
        store.update({ it.copy(quickActions = listOf(grouped, party), ownValues = values, showValues = false) }, Settings())
        val back = store.settings.first()
        assertThat(back.quickActions).containsExactly(grouped, party).inOrder()
        assertThat(back.ownValues).containsExactly(*values.toTypedArray()).inOrder()
        assertThat(back.showValues).isFalse()
        store.update({ it.copy(quickActions = emptyList(), ownValues = emptyList()) }, back)
        assertThat(store.settings.first().quickActions).isEmpty()
        assertThat(store.settings.first().ownValues).isEmpty()
    }

    @Test
    fun `the buttons come after the built-in entries, only while their entity can be used`() {
        val s = Settings(quickActions = listOf(ungroup, movie, party))
        val ctx = QuickContext(music = true, actions = mapOf(0 to ActionState.READY, 2 to ActionState.ON))
        val rows = QuickMenu.rows(s, ctx, en)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.VIEW, QuickItem.ACTION, QuickItem.ACTION, QuickItem.SETTINGS).inOrder()
        // The scene's entity is not there (unavailable): its button is left out, the others keep their places.
        assertThat(rows.filter { it.item == QuickItem.ACTION }.map { it.key.action }).containsExactly(0, 2).inOrder()
        assertThat(rows[1].label).isEqualTo("Ungroup")
        assertThat(rows[1].value).isEqualTo("Run")
        assertThat(rows[2].value).isEqualTo("On")
        // Every value a button can show, so its place never changes width.
        assertThat(rows[1].values).containsExactly("Run", "Done", "Failed").inOrder()
        assertThat(rows[2].values).containsExactly("On", "Off", "Failed").inOrder()
    }

    @Test
    fun `a button says what it does, then how it went`() {
        val t = TestTexts.german
        assertThat(QuickMenu.actionValue(ActionKind.RUN, ActionState.READY, en)).isEqualTo("Run")
        assertThat(QuickMenu.actionValue(ActionKind.ACTIVATE, ActionState.READY, en)).isEqualTo("Activate")
        assertThat(QuickMenu.actionValue(ActionKind.PRESS, ActionState.READY, en)).isEqualTo("Press")
        assertThat(QuickMenu.actionValue(ActionKind.RUN, ActionState.DONE, en)).isEqualTo("Done")
        assertThat(QuickMenu.actionValue(ActionKind.ACTIVATE, ActionState.FAILED, en)).isEqualTo("Failed")
        assertThat(QuickMenu.actionValue(ActionKind.RUN, ActionState.READY, t)).isEqualTo("Ausführen")
        assertThat(QuickMenu.actionValue(ActionKind.TOGGLE, ActionState.OFF, t)).isEqualTo("Aus")
    }
}
