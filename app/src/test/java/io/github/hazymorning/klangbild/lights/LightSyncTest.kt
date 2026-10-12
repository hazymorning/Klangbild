package io.github.hazymorning.klangbild.lights

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.config.RoomLightLevel
import io.github.hazymorning.klangbild.config.RoomLights
import io.github.hazymorning.klangbild.data.ha.EntityState
import io.github.hazymorning.klangbild.render.CoverField
import io.github.hazymorning.klangbild.render.FieldScene
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

/** The lights behind the TV against a stand-in Home Assistant, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LightSyncTest {
    private val left = "light.tv_left"
    private val right = "light.tv_right"
    private val pair = RoomLights(left = left, right = right)

    /** A cover red on the left, blue on the right. */
    private fun field(): FieldScene {
        val n = 48
        val thumb = IntArray(n * n) { i -> if (i % n < n / 2) 0xFFC8321E.toInt() else 0xFF1E46B4.toInt() }
        return CoverField.build(thumb, n, CoverPalette.analyze(thumb), Appearance.DARK, 1f, layout = null)
    }

    /** A cover of four colors, one per quarter: the field at the edges keeps changing as it moves. */
    private fun fourColors(): FieldScene {
        val n = 48
        val quarters = intArrayOf(0xFFC8321E.toInt(), 0xFF1E46B4.toInt(), 0xFFE0B020.toInt(), 0xFF2E8B57.toInt())
        val thumb = IntArray(n * n) { i -> quarters[(if (i % n < n / 2) 0 else 1) + (if (i / n < n / 2) 0 else 2)] }
        return CoverField.build(thumb, n, CoverPalette.analyze(thumb), Appearance.DARK, 1f, layout = null)
    }

    private class Call(val at: Long, val domain: String, val service: String, val id: String?, val data: JsonObject?) {
        val brightness get() = data?.get("brightness")?.jsonPrimitive?.int
        val transition get() = data?.get("transition")?.jsonPrimitive?.double
        val name get() = "$domain.$service"
    }

    /**
     * Home Assistant as far as the lights go: it applies light calls to the states with the
     * call's context, keeps snapshot scenes (`scene.create`) and restores them (`scene.turn_on`).
     */
    private inner class FakeHa(val scope: TestScope, initial: Map<String, EntityState>) : LightBridge {
        val states = MutableStateFlow(initial)
        override val lights = states
        val calls = mutableListOf<Call>()
        var followed: Set<String> = emptySet()
        private var n = 0
        val scenes = HashMap<String, EntityState>()

        /** The states Home Assistant sends once the lights are followed (a moment later), if any. */
        var onFollow: Map<String, EntityState>? = null

        /** How the next calls fail: null answers (an error), or a cancellation (a timeout). */
        var failing = false
        var cancelling = 0

        /** Home Assistant restarted: its snapshot scenes are gone. */
        var scenesLost = false

        override fun follow(ids: Set<String>) {
            followed = ids
            onFollow?.let { later ->
                scope.backgroundScope.launch {
                    delay(400)
                    states.value = states.value + later.filterKeys { it in ids }
                }
            }
        }

        override suspend fun call(domain: String, service: String, entityId: String?, data: JsonObject?): String? {
            val t = scope.testScheduler.currentTime
            calls += Call(t, domain, service, entityId, data)
            if (cancelling > 0 && domain == "light") {
                cancelling--
                throw kotlinx.coroutines.CancellationException("no answer")
            }
            if (failing && domain == "light") return null
            val ctx = "ours-${n++}"
            when ("$domain.$service") {
                "scene.create" -> {
                    val id = data!!["snapshot_entities"]!!.jsonArray.single().jsonPrimitive.content
                    scenes["scene." + data["scene_id"]!!.jsonPrimitive.content] = states.value.getValue(id)
                }
                "scene.turn_on" -> {
                    if (scenesLost) return null
                    val was = scenes[entityId] ?: return null
                    set(was.entityId, was.state, was.attributes, t, ctx)
                }
                "scene.delete" -> scenes.remove(entityId)
                "light.turn_on", "light.turn_off" -> {
                    val old = states.value.getValue(entityId!!)
                    val attrs = buildJsonObject {
                        old.attributes.forEach { (k, v) -> put(k, v) }
                        data?.get("brightness")?.let { put("brightness", it) }
                        data?.get("xy_color")?.let { put("xy_color", it); put("color_mode", "xy") }
                        data?.get("color_temp_kelvin")?.let { put("color_temp_kelvin", it); put("color_mode", "color_temp") }
                    }
                    set(entityId, if (service == "turn_off") "off" else "on", attrs, t, ctx)
                }
            }
            return ctx
        }

        private fun set(id: String, state: String, attrs: JsonObject, t: Long, ctx: String) {
            val old = states.value.getValue(id)
            states.value = states.value + (id to old.copy(state = state, attributes = attrs, lastUpdated = t / 1000.0 + 0.001, context = ctx))
        }

        /** Someone else changes a light. */
        fun byHand(id: String, state: String, attrs: JsonObject = JsonObject(emptyMap())) {
            val t = scope.testScheduler.currentTime
            val old = states.value.getValue(id)
            states.value = states.value + (
                id to old.copy(state = state, attributes = JsonObject(old.attributes + attrs), lastUpdated = t / 1000.0 + 0.002, context = "someone", contextUser = "u1")
                )
        }

        fun to(id: String) = calls.filter { it.id == id && it.domain == "light" }
        fun named(name: String) = calls.filter { it.name == name }
    }

    /** A light that takes colors and color temperatures and fades (`supported_features` 32). */
    private fun light(
        id: String,
        on: Boolean,
        brightness: Int = 180,
        kelvin: Int? = 2700,
        modes: List<String> = listOf("color_temp", "xy"),
        features: Int = 32,
    ) = EntityState(
        id,
        if (on) "on" else "off",
        buildJsonObject {
            if (on) {
                if (modes != listOf("onoff")) put("brightness", brightness)
                put("color_mode", if ("color_temp" in modes) "color_temp" else modes.first())
                if ("color_temp" in modes) kelvin?.let { put("color_temp_kelvin", it) }
            }
            putJsonArray("supported_color_modes") { modes.forEach { add(it) } }
            if ("color_temp" in modes) {
                put("min_color_temp_kelvin", 2_200)
                put("max_color_temp_kelvin", 6_500)
            }
            put("supported_features", features)
        },
        0.0,
        0.0,
        "before",
    )

    private fun TestScope.sync(): LightSync =
        LightSync(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))

    /** The lights' colors for a field, made once (as the controller does): the same field never fades the lights anew. */
    private val made = HashMap<Pair<FieldScene, Boolean>, CoverLightColors>()

    private fun coverColors(f: FieldScene, moving: Boolean) = made.getOrPut(f to moving) { CoverLightColors(f, moving) }

    private fun target(
        song: String,
        playing: Boolean = true,
        level: RoomLightLevel = RoomLightLevel.P20,
        motion: Float = 0f,
        f: FieldScene = field,
        lights: RoomLights = pair,
    ) = LightSync.Target(lights, level, coverColors(f, motion > 0f), song, playing, night = 0f, warmth = 0.06f, motion = motion, fieldTime = { 0.0 })

    private val field = field()

    @Test
    fun `the lights' states arrive after they are followed and the lights are still taken over`() = runTest {
        // Home Assistant sends a light's state only once it is followed, a moment later.
        val ha = FakeHa(this, emptyMap())
        ha.onFollow = mapOf(left to light(left, on = true), right to light(right, on = true))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.to(left)).hasSize(1)
        assertThat(ha.to(right)).hasSize(1)
        // Their states were saved first, in Home Assistant's own snapshot scenes.
        assertThat(ha.named("scene.create").map { it.data.toString() }).containsExactly(
            """{"scene_id":"klangbild_tv_left","snapshot_entities":["light.tv_left"]}""",
            """{"scene_id":"klangbild_tv_right","snapshot_entities":["light.tv_right"]}""",
        )
        // And they are given back as they were.
        s.update(null)
        testScheduler.advanceTimeBy(LightSync.IDLE_RELEASE_MS + 5_000)
        assertThat(ha.named("scene.turn_on").map { it.id }).containsExactly("scene.klangbild_tv_left", "scene.klangbild_tv_right")
        assertThat(ha.named("scene.turn_on").map { it.transition }.toSet()).containsExactly(LightSync.RESTORE_TRANSITION_MS / 1000.0)
        val back = ha.states.value.getValue(left)
        assertThat(back.number("color_temp_kelvin")).isEqualTo(2700.0)
        assertThat(back.number("brightness")).isEqualTo(180.0)
        // The scenes are deleted when done.
        assertThat(ha.named("scene.delete").map { it.id }).containsExactly("scene.klangbild_tv_left", "scene.klangbild_tv_right")
        assertThat(ha.scenes).isEmpty()
    }

    @Test
    fun `a light that shows up later joins in`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(LightSync.STATES_WAIT_MS + 2_000)
        assertThat(ha.to(left)).hasSize(1)
        assertThat(ha.to(right)).isEmpty()
        // The right light was unknown (unplugged); now it is there, and saved before it joins in.
        ha.states.value = ha.states.value + (right to light(right, on = true))
        testScheduler.advanceTimeBy(3_000)
        assertThat(ha.to(right)).hasSize(1)
        assertThat(ha.to(left)).hasSize(1)
        assertThat(ha.scenes.keys).containsExactly("scene.klangbild_tv_left", "scene.klangbild_tv_right")
    }

    @Test
    fun `with only lights that are on, a light that is off stays off until someone switches it on`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = false)))
        val s = sync()
        s.attach(ha)
        s.update(target("a").copy(onlyOn = true))
        testScheduler.advanceTimeBy(30_000)
        assertThat(ha.to(left)).hasSize(1)
        assertThat(ha.to(right)).isEmpty()
        assertThat(ha.scenes.keys).containsExactly("scene.klangbild_tv_left")
        ha.byHand(right, "on", buildJsonObject { put("brightness", 120) })
        testScheduler.advanceTimeBy(3_000)
        assertThat(ha.to(right)).hasSize(1)
        assertThat(ha.scenes.keys).containsExactly("scene.klangbild_tv_left", "scene.klangbild_tv_right")
        // Given back on, as it was when it joined in.
        s.update(null)
        testScheduler.advanceTimeBy(LightSync.IDLE_RELEASE_MS + 5_000)
        assertThat(ha.named("light.turn_off")).isEmpty()
        assertThat(ha.states.value.getValue(right).state).isEqualTo("on")
    }

    @Test
    fun `with only lights that are on, a light switched off and on again follows the music again and ends as before the music`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true, brightness = 200, kelvin = 2700), right to light(right, on = true)))
        val s = sync()
        s.attach(ha)
        s.update(target("a").copy(onlyOn = true))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.to(left)).hasSize(1)
        // Switched off: let go, never switched on by Klangbild.
        ha.byHand(left, "off")
        testScheduler.advanceTimeBy(3_000)
        s.update(target("b", f = fourColors()).copy(onlyOn = true))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.to(left)).hasSize(1)
        assertThat(ha.states.value.getValue(left).state).isEqualTo("off")
        // Switched on again (in the colors it last had from Klangbild): it follows the music again.
        ha.byHand(left, "on")
        testScheduler.advanceTimeBy(3_000)
        assertThat(ha.to(left)).hasSize(2)
        // After the music it is as it was before the music, not as it came back on.
        s.update(null)
        testScheduler.advanceTimeBy(LightSync.IDLE_RELEASE_MS + 5_000)
        val back = ha.states.value.getValue(left)
        assertThat(back.state).isEqualTo("on")
        assertThat(back.number("brightness")).isEqualTo(200.0)
        assertThat(back.number("color_temp_kelvin")).isEqualTo(2700.0)
        assertThat(ha.scenes).isEmpty()
    }

    @Test
    fun `chosen while they glow, only lights that are on sends the lights switched on for the music back off`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true, brightness = 120), right to light(right, on = false)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.states.value.getValue(right).state).isEqualTo("on")
        val before = ha.calls.size
        s.update(target("a").copy(onlyOn = true))
        testScheduler.advanceTimeBy(5_000)
        val back = ha.calls.drop(before)
        // The light that was off: dimmed, then its scene (off). The other keeps its colors: no call.
        assertThat(back.filter { it.id == left || it.id == "scene.klangbild_tv_left" }).isEmpty()
        assertThat(back.single { it.id == right }.brightness).isEqualTo(LightSync.DARKEST)
        assertThat(back.filter { it.id == "scene.klangbild_tv_right" }.map { it.name }).containsExactly("scene.turn_on", "scene.delete").inOrder()
        assertThat(ha.states.value.getValue(right).state).isEqualTo("off")
        // It stays off with the next song; the other goes back as it was after the music.
        s.update(target("b", f = fourColors()).copy(onlyOn = true))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.calls.drop(before).filter { it.id == right }).hasSize(1)
        s.update(null)
        testScheduler.advanceTimeBy(LightSync.IDLE_RELEASE_MS + 5_000)
        assertThat(ha.states.value.getValue(left).number("brightness")).isEqualTo(120.0)
        assertThat(ha.states.value.getValue(right).state).isEqualTo("off")
    }

    @Test
    fun `turned back to turn on, a light that is off joins in`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = false)))
        val s = sync()
        s.attach(ha)
        s.update(target("a").copy(onlyOn = true))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.to(right)).isEmpty()
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        // Saved, then on dark in its color and up, as when the music starts.
        assertThat(ha.scenes.keys).contains("scene.klangbild_tv_right")
        val (pre, up) = ha.to(right)
        assertThat(pre.brightness).isEqualTo(LightSync.DARKEST)
        assertThat(up.brightness).isGreaterThan(LightSync.DARKEST)
    }

    @Test
    fun `a failing call is tried once more, for its light only`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = true)))
        val s = sync()
        s.attach(ha)
        ha.failing = true
        s.update(target("a"))
        testScheduler.advanceTimeBy(120_000)
        // The first call and one more, not a call every few seconds forever.
        assertThat(ha.to(left)).hasSize(2)
        assertThat(ha.to(right)).hasSize(2)
    }

    @Test
    fun `a call that times out never freezes its light`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = true)))
        val s = sync()
        s.attach(ha)
        ha.cancelling = 2
        s.update(target("a"))
        testScheduler.advanceTimeBy(3_000)
        // The next song reaches both lights again.
        s.update(target("b", f = fourColors()))
        testScheduler.advanceTimeBy(10_000)
        assertThat(ha.to(left).size).isAtLeast(2)
        assertThat(ha.to(right).size).isAtLeast(2)
        assertThat(ha.to(left).last().brightness).isNotNull()
    }

    @Test
    fun `takes the lights over in the cover's colors, an off one without a flash`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = false)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(3_000)
        assertThat(ha.followed).containsExactly(left, right)
        // The light that was on goes straight to its color ...
        val l = ha.to(left).single()
        assertThat(l.service).isEqualTo("turn_on")
        assertThat(l.brightness).isEqualTo(51)
        assertThat(l.data!!["xy_color"]!!.jsonArray).hasSize(2)
        // ... the one that was off first comes on at the very least, in its new color, then rises.
        val (pre, up) = ha.to(right)
        assertThat(pre.brightness).isEqualTo(LightSync.DARKEST)
        assertThat(pre.transition).isEqualTo(0.0)
        assertThat(pre.data!!["xy_color"]).isEqualTo(up.data!!["xy_color"])
        assertThat(up.at - pre.at).isEqualTo(LightSync.PRE_ON_MS)
        // Different colors left and right: the cover is red there and blue here.
        assertThat(l.data["xy_color"]).isNotEqualTo(up.data["xy_color"])
    }

    @Test
    fun `each light shows what it can, a color, a white, a brightness or just on`() = runTest {
        val white = "light.white_only"
        val dimmer = "light.dimmer"
        val plug = "light.plug"
        val ha = FakeHa(
            this,
            mapOf(
                white to light(white, on = true, modes = listOf("color_temp")),
                dimmer to light(dimmer, on = true, modes = listOf("brightness"), features = 0),
                plug to light(plug, on = false, modes = listOf("onoff"), features = 0),
            ),
        )
        val s = sync()
        s.attach(ha)
        s.update(target("a", lights = RoomLights(left = white, center = dimmer, right = plug)))
        testScheduler.advanceTimeBy(5_000)
        // A white light gets a white in its own range; never a color it would turn into another white.
        val w = ha.to(white).single().data!!
        assertThat(w.keys).containsExactly("color_temp_kelvin", "brightness", "transition")
        assertThat(w["color_temp_kelvin"]!!.jsonPrimitive.int).isIn(2_200..6_500)
        // A dimmer gets a brightness, and no fade it cannot do.
        assertThat(ha.to(dimmer).single().data!!.keys).containsExactly("brightness")
        // A plain switch is only switched on, with nothing else.
        val p = ha.to(plug).single()
        assertThat(p.service).isEqualTo("turn_on")
        assertThat(p.data!!.keys).isEmpty()
        // The next song changes nothing a switch could show.
        s.update(target("b", f = fourColors(), lights = RoomLights(left = white, center = dimmer, right = plug)))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.to(plug)).hasSize(1)
    }

    @Test
    fun `quick skips cost nothing and the song that stays is shown`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = true)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        val before = ha.calls.size
        val green = IntArray(48 * 48) { 0xFF2E8B57.toInt() }
        val other = CoverField.build(green, 48, CoverPalette.analyze(green), Appearance.DARK, 1f, null)
        for ((i, song) in listOf("b", "c", "d").withIndex()) {
            s.update(target(song, f = if (i == 2) other else field))
            testScheduler.advanceTimeBy(400)
        }
        testScheduler.advanceTimeBy(5_000)
        val after = ha.calls.drop(before)
        // One call per light, for the last song, in a long fade.
        assertThat(after.map { it.id }).containsExactly(left, right)
        assertThat(after.map { it.transition }.toSet()).containsExactly(LightSync.TRACK_TRANSITION_MS / 1000.0)
    }

    @Test
    fun `a pause dims to half, playing brings it back`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = true)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        s.update(target("a", playing = false))
        testScheduler.advanceTimeBy(1_000)
        assertThat(ha.to(left)).hasSize(1)
        testScheduler.advanceTimeBy(3_000)
        val dim = ha.to(left).last()
        assertThat(dim.brightness).isEqualTo(26)
        s.update(target("a", playing = true))
        testScheduler.advanceTimeBy(3_000)
        assertThat(ha.to(left).last().brightness).isEqualTo(51)
        assertThat(ha.to(left).last().transition).isEqualTo(LightSync.RESUME_TRANSITION_MS / 1000.0)
    }

    @Test
    fun `still colors send nothing, moving ones drift in long fades, never faster than a call a second`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true), right to light(right, on = true)))
        val s = sync()
        s.attach(ha)
        val colors = coverColors(fourColors(), moving = true)
        s.update(LightSync.Target(pair, RoomLightLevel.P20, colors, "a", true, 0f, 0.06f, 0f, { 0.0 }))
        testScheduler.advanceTimeBy(60_000)
        assertThat(ha.to(left)).hasSize(1)

        var t = 0.0
        val moving = LightSync.Target(pair, RoomLightLevel.P20, colors, "a", true, 0f, 0.06f, 1f, { t })
        s.update(moving)
        // Five minutes: the colors move on from light to light slowly, and only visible steps are sent.
        repeat(600) {
            t += 0.5
            testScheduler.advanceTimeBy(500)
        }
        val drift = ha.to(left).drop(1)
        assertThat(drift.size).isAtMost(31)
        assertThat(drift).isNotEmpty()
        assertThat(drift.map { it.transition }.toSet()).containsExactly(LightSync.DRIFT_TRANSITION_MS / 1000.0)
        assertThat(ha.to(left).zipWithNext { a, b -> b.at - a.at }.minOrNull()!!).isAtLeast(LightSync.MIN_GAP_MS)
    }

    @Test
    fun `a light someone switches off is theirs and stays as they left it`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true, brightness = 200), right to light(right, on = true, brightness = 90, kelvin = 4000)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        ha.byHand(left, "off")
        testScheduler.advanceTimeBy(2_000)
        s.update(target("b"))
        testScheduler.advanceTimeBy(5_000)
        assertThat(ha.to(left)).hasSize(1)
        assertThat(ha.to(right)).hasSize(2)

        // The music ends: after a while the other light is given back as it was; the switched-off one is left alone.
        s.update(null)
        testScheduler.advanceTimeBy(LightSync.IDLE_RELEASE_MS + 5_000)
        assertThat(ha.to(left)).hasSize(1)
        assertThat(ha.named("scene.turn_on").map { it.id }).containsExactly("scene.klangbild_tv_right")
        val back = ha.states.value.getValue(right)
        assertThat(back.number("brightness")).isEqualTo(90.0)
        assertThat(back.number("color_temp_kelvin")).isEqualTo(4000.0)
        assertThat(ha.states.value.getValue(left).state).isEqualTo("off")
    }

    @Test
    fun `switched off in the menu the lights are given back at once, one that was off goes dark and then off`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true, brightness = 120), right to light(right, on = false)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        val before = ha.calls.size
        s.update(target("a", level = RoomLightLevel.OFF))
        testScheduler.advanceTimeBy(5_000)
        val back = ha.calls.drop(before)
        assertThat(back.first { it.id == "scene.klangbild_tv_left" }.name).isEqualTo("scene.turn_on")
        assertThat(ha.states.value.getValue(left).number("brightness")).isEqualTo(120.0)
        // The one that was off: dimmed in a fade, then its scene (off) without one. A faded off
        // leaves some lights glowing at their lowest level.
        val dim = back.single { it.id == right }
        assertThat(dim.brightness).isEqualTo(LightSync.DARKEST)
        val off = back.single { it.id == "scene.klangbild_tv_right" && it.service == "turn_on" }
        assertThat(off.data).isNull()
        assertThat(off.at - dim.at).isEqualTo(LightSync.OFF_FADE_MS + LightSync.OFF_SETTLE_MS)
        assertThat(ha.states.value.getValue(right).state).isEqualTo("off")
        assertThat(ha.followed).isEmpty()
    }

    @Test
    fun `without its snapshot a light is given back from what was noted`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true, brightness = 120, kelvin = 3000), right to light(right, on = false)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        // Home Assistant restarted: the snapshot scenes are gone.
        ha.scenesLost = true
        val before = ha.calls.size
        s.update(target("a", level = RoomLightLevel.OFF))
        testScheduler.advanceTimeBy(5_000)
        val back = ha.calls.drop(before).filter { it.domain == "light" }
        val on = back.single { it.id == left }
        assertThat(on.service).isEqualTo("turn_on")
        assertThat(on.brightness).isEqualTo(120)
        assertThat(on.data!!["color_temp_kelvin"]).isEqualTo(JsonPrimitive(3000))
        assertThat(back.filter { it.id == right }.map { it.service }).containsExactly("turn_on", "turn_off").inOrder()
        assertThat(back.last { it.id == right }.data).isNull()
    }

    @Test
    fun `when the app closes the lights go back through the link they were taken with`() = runTest {
        val ha = FakeHa(this, mapOf(left to light(left, on = true, brightness = 120), right to light(right, on = true, brightness = 60)))
        val s = sync()
        s.attach(ha)
        s.update(target("a"))
        testScheduler.advanceTimeBy(5_000)
        val before = ha.calls.size
        val job = s.release()
        s.attach(null)
        testScheduler.advanceTimeBy(5_000)
        assertThat(job.isCompleted).isTrue()
        assertThat(ha.calls.drop(before).filter { it.name == "scene.turn_on" }.map { it.id })
            .containsExactly("scene.klangbild_tv_left", "scene.klangbild_tv_right")
        assertThat(ha.states.value.getValue(left).number("brightness")).isEqualTo(120.0)
        assertThat(ha.states.value.getValue(right).number("brightness")).isEqualTo(60.0)
        // Nothing more after that.
        val done = ha.calls.size
        testScheduler.advanceTimeBy(60_000)
        assertThat(ha.calls.size).isEqualTo(done)
    }

    @Test
    fun `colors for the lights are their hue at its purest, grays a neutral white`() {
        val day = LightColor.whiteXy(LightColor.WHITE_DAY_K)
        val gray = LightColor.of(floatArrayOf(0.2f, 0.2f, 0.2f), warmth = 0f)
        assertThat(gray.distance(day)).isLessThan(0.002f)
        // Never the amber of a 2700 K bulb by day; at night a warmer white.
        assertThat(gray.distance(LightColor.whiteXy(2700f))).isGreaterThan(0.05f)
        assertThat(LightColor.of(floatArrayOf(0.2f, 0.2f, 0.2f), warmth = 1f).x).isGreaterThan(day.x)
        // A pale sky blue (as the window view's sky) is a clear blue light, far from white.
        val sky = LightColor.of(io.github.hazymorning.klangbild.color.Oklab.toLinear(io.github.hazymorning.klangbild.color.Lch(0.82f, 0.05f, 245f).toLab()), warmth = 0f)
        assertThat(sky.distance(day)).isGreaterThan(0.12f)
        assertThat(sky.y).isLessThan(day.y - 0.05f)
        // A pure green stays a real color: Home Assistant fits it to what the light can show.
        val green = LightColor.of(floatArrayOf(0f, 1f, 0f), warmth = 0f)
        assertThat(green.x + green.y).isAtMost(1f)
        assertThat(green.y).isGreaterThan(0.5f)
        // Never below the floor while on.
        assertThat(LightColor.brightness(0f)).isEqualTo(LightColor.FLOOR)
        assertThat(LightColor.brightness(0.2f)).isEqualTo(51)
        // A white light: warm for a warm color, cool for a cool one, always within its range.
        assertThat(LightColor.kelvin(floatArrayOf(0.8f, 0.2f, 0.05f), 0f, 2_200, 6_500)).isLessThan(LightColor.kelvin(floatArrayOf(0.1f, 0.3f, 0.9f), 0f, 2_200, 6_500))
        assertThat(LightColor.kelvin(floatArrayOf(0.1f, 0.3f, 0.9f), 0f, 2_200, 4_000)).isAtMost(4_000)
    }
}
