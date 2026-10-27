package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.domain.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class EntityStoreTest {
    private fun state(s: String): EntityState = EntityState.of(Json.parseToJsonElement(s).jsonObject)!!

    private val playing = """{"entity_id":"media_player.wz","state":"playing",
        "attributes":{"media_title":"A","media_position":10},
        "last_changed":"2026-10-10T18:00:00.500000+00:00","last_updated":"2026-10-10T18:00:01+00:00",
        "context":{"id":"01K","parent_id":"01P","user_id":null}}"""

    @Test
    fun `reads a state object as the REST API and state events carry it`() {
        val st = state(playing)
        assertThat(st.entityId).isEqualTo("media_player.wz")
        assertThat(st.state).isEqualTo("playing")
        assertThat(st.string("media_title")).isEqualTo("A")
        assertThat(st.number("media_position")).isEqualTo(10.0)
        assertThat(st.lastUpdated).isEqualTo(1_791_655_201.0)
        assertThat(st.context).isEqualTo("01K")
        assertThat(st.contextParent).isEqualTo("01P")
        assertThat(st.contextUser).isNull()
        // Without last_updated, last_changed is used; anything without an entity id isn't a state.
        assertThat(state("""{"entity_id":"sun.sun","state":"below_horizon","last_changed":"2026-10-10T18:00:00+00:00"}""").lastUpdated)
            .isEqualTo(1_791_655_200.0)
        assertThat(EntityState.of(Json.parseToJsonElement("""{"state":"on"}""").jsonObject)).isNull()
        assertThat(EntityState.of(null as JsonObject?)).isNull()
    }

    @Test
    fun `a state never replaces a newer one`() {
        val store = EntityStore()
        assertThat(store.put(state(playing))).isTrue()
        val paused = state(playing.replace("\"playing\"", "\"paused\"").replace("18:00:01+00:00", "18:00:05+00:00"))
        assertThat(store.put(paused)).isTrue()
        assertThat(store.snapshot().getValue("media_player.wz").state).isEqualTo("paused")
        // A slow REST answer from before the event changes nothing.
        assertThat(store.put(state(playing))).isFalse()
        assertThat(store.snapshot().getValue("media_player.wz").state).isEqualTo("paused")
        // The same state again is no change either.
        assertThat(store.put(paused)).isFalse()
    }

    @Test
    fun `entities are forgotten when removed or no longer followed`() {
        val store = EntityStore()
        store.put(state(playing))
        store.put(state("""{"entity_id":"sun.sun","state":"above_horizon","last_changed":"2026-10-10T18:00:00+00:00"}"""))
        store.retain(setOf("sun.sun"))
        assertThat(store.snapshot().keys).containsExactlyInAnyOrder("sun.sun")
        assertThat(store.remove("sun.sun")).isTrue()
        assertThat(store.remove("sun.sun")).isFalse()
        assertThat(store.snapshot()).isEmpty()
    }
}
