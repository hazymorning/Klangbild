package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/** When a button of one's own shows: an entity's state or one of its attributes, compared with a value. */
class ConditionTest {
    @Test
    fun `a condition compares like Home Assistant, words without case, numbers as numbers, a list by its count`() {
        fun c(test: Condition.Test, value: String, attribute: String? = null) = Condition("x.y", attribute, test, value)
        val attrs = Json.parseToJsonElement("""{"group_members":["a","b","c"],"volume_level":0.35,"source":"Radio","muted":false}""").jsonObject
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
}
