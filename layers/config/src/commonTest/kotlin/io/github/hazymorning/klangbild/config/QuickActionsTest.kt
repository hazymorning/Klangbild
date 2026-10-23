package io.github.hazymorning.klangbild.config

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasLength
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.domain.Condition
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.text.TestTexts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/**
 * The quick settings' own buttons: what each kind of entity does, how they are kept, and how
 * they stand in the row (after the built-in entries, only while their entity can be used).
 */
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
        assertThat(QuickActions.decode(text)).containsExactly(ungroup, movie)
        assertThat(QuickActions.decode("""[{"name":"Front door","entity":"lock.front"},{"name":"Go","entity":"script.go"},7,"x"]"""))
            .containsExactlyInAnyOrder(QuickAction("Go", "script.go"))
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
        assertThat(QuickActions.decode(QuickActions.encode(listOf(party, scene)))).containsExactly(party, scene)
        // Variables only go to a script; odd names, too many variables and odd conditions don't make a button.
        assertThat(QuickActions.valid("Movie", "scene.movie", vars)!!.variables).isEmpty()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","variables":{"not ok":1}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","variables":${(1..20).joinToString(",", "{", "}") { "\"v$it\":$it" }}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","variables":[1]}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","when":{"entity":"sensor.t","test":"above","value":"warm"}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","when":{"entity":"sensor.t","test":"near","value":"1"}}"""))).isNull()
        assertThat(QuickActions.from(obj("""{"entity":"script.x","when":{"entity":"sensor.t","attribute":"a b","test":"is","value":"1"}}"""))).isNull()
        // What the buttons need followed: their entities and what their conditions look at.
        assertThat(QuickActions.entities(listOf(party, scene)).toList()).containsExactly("script.party", "media_player.wz", "scene.movie", "sun.sun")
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
    fun `the buttons come after the built-in entries, only while their entity can be used`() {
        val s = Settings(quickActions = listOf(ungroup, movie, party))
        val ctx = QuickContext(music = true, actions = mapOf(0 to ActionState.READY, 2 to ActionState.ON))
        val rows = QuickMenu.rows(s, ctx, en)
        assertThat(rows.map { it.item }).containsExactly(QuickItem.QUEUE, QuickItem.LANDSCAPE, QuickItem.ACTION, QuickItem.ACTION, QuickItem.SETTINGS)
        // The scene's entity is not there (unavailable): its button is left out, the others keep their places.
        assertThat(rows.filter { it.item == QuickItem.ACTION }.map { it.key.action }).containsExactly(0, 2)
        assertThat(rows[2].label).isEqualTo("Ungroup")
        assertThat(rows[2].value).isEqualTo("Run")
        assertThat(rows[3].value).isEqualTo("On")
        // Every value a button can show, so its place never changes width.
        assertThat(rows[2].values).containsExactly("Run", "Done", "Failed")
        assertThat(rows[3].values).containsExactly("On", "Off", "Failed")
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
