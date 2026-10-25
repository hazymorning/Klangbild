package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.domain.EntityState
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class PlayerParserTest {
    private val base = "http://192.168.1.5:8123"
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun entity(state: String, attrs: String) = EntityState("media_player.wz", state, obj(attrs), 0.0, 0.0)

    @Test
    fun `maps a music assistant player`() {
        val p = PlayerParser.player(
            entity(
                "playing",
                """{"media_title":"Bohemian Rhapsody (Remastered 2011)","media_artist":"Queen / David Bowie",
                   "media_album_name":"A Night at the Opera","media_duration":355,"media_position":73,
                   "media_position_updated_at":"2026-10-08T18:21:07.512345+00:00",
                   "entity_picture":"http://192.168.1.6:8095/imageproxy/ab?size=512&fmt=jpg",
                   "entity_picture_local":"/api/media_player_proxy/media_player.wz?token=t&cache=c",
                   "app_id":"music_assistant","mass_player_type":"player","active_queue":"q1","friendly_name":"Wohnzimmer"}""",
            ),
            base,
        )
        assertThat(p.status).isEqualTo(PlayerStatus.PLAYING)
        assertThat(p.isMusicAssistant).isTrue()
        assertThat(p.activeQueue).isEqualTo("q1")
        val t = p.track!!
        assertThat(t.title).isEqualTo("Bohemian Rhapsody (Remastered 2011)")
        assertThat(t.artists).containsExactly("Queen", "David Bowie")
        assertThat(t.durationMs).isEqualTo(355_000)
        assertThat(t.artworkUrl).isEqualTo("http://192.168.1.6:8095/imageproxy/ab?size=1024&fmt=jpg")
        assertThat(t.artworkFallbackUrl).isEqualTo("http://192.168.1.5:8123/api/media_player_proxy/media_player.wz?token=t&cache=c")
        assertThat(p.positionMs).isEqualTo(73_000)
        assertThat(p.positionAtEpochMs).isEqualTo(1791483667512L)
    }

    @Test
    fun `group members without this speaker, volume and mute`() {
        val leader = PlayerParser.player(
            entity(
                "playing",
                """{"media_title":"X","group_members":["media_player.wz","media_player.kueche","media_player.bad","media_player.kueche"],
                   "volume_level":0.35,"is_volume_muted":false}""",
            ),
            base,
        )
        assertThat(leader.groupMembers).containsExactly("media_player.kueche", "media_player.bad")
        assertThat(leader.volume).isNotNull().isCloseTo(0.35f, 1e-6f)
        assertThat(leader.muted).isFalse()

        // A member lists the leader first. It is one of the others, too.
        val member = PlayerParser.player(
            entity("playing", """{"media_title":"X","group_members":["media_player.kueche","media_player.wz"],"is_volume_muted":true}"""),
            base,
        )
        assertThat(member.groupMembers).containsExactlyInAnyOrder("media_player.kueche")
        assertThat(member.volume).isNull()
        assertThat(member.muted).isTrue()

        val alone = PlayerParser.player(entity("playing", """{"media_title":"X","group_members":["media_player.wz"]}"""), base)
        assertThat(alone.groupMembers).isEmpty()
        val noAttribute = PlayerParser.player(entity("playing", """{"media_title":"X","group_members":null}"""), base)
        assertThat(noAttribute.groupMembers).isEmpty()
    }

    @Test
    fun `shuffle as the player reports it, none where it cannot shuffle`() {
        // Music Assistant's players: SHUFFLE_SET (32768) among their features.
        val on = PlayerParser.player(entity("playing", """{"media_title":"X","shuffle":true,"supported_features":4127295}"""), base)
        assertThat(on.shuffle).isNotNull().isTrue()
        val off = PlayerParser.player(entity("playing", """{"media_title":"X","shuffle":false,"supported_features":32768}"""), base)
        assertThat(off.shuffle).isNotNull().isFalse()
        // Can shuffle, has not said yet: off.
        val unsaid = PlayerParser.player(entity("playing", """{"media_title":"X","supported_features":32768}"""), base)
        assertThat(unsaid.shuffle).isNotNull().isFalse()
        // Cannot shuffle (a radio app's player): no shuffle at all, whatever an attribute says.
        val cannot = PlayerParser.player(entity("playing", """{"media_title":"X","shuffle":false,"supported_features":21437}"""), base)
        assertThat(cannot.shuffle).isNull()
        // No features reported: the attribute alone.
        assertThat(PlayerParser.player(entity("playing", """{"media_title":"X","shuffle":true}"""), base).shuffle).isNotNull().isTrue()
        assertThat(PlayerParser.player(entity("playing", """{"media_title":"X"}"""), base).shuffle).isNull()
    }

    @Test
    fun `state strings and missing data`() {
        assertThat(PlayerParser.status("buffering")).isEqualTo(PlayerStatus.BUFFERING)
        assertThat(PlayerParser.status("standby")).isEqualTo(PlayerStatus.IDLE)
        assertThat(PlayerParser.status("unavailable")).isEqualTo(PlayerStatus.UNAVAILABLE)
        assertThat(PlayerParser.status("weird")).isEqualTo(PlayerStatus.UNKNOWN)
        val off = PlayerParser.player(entity("off", """{"friendly_name":"WZ"}"""), base)
        assertThat(off.track).isNull()
        val idle = PlayerParser.player(entity("idle", """{"media_title":""}"""), base)
        assertThat(idle.track).isNull()
        assertThat(PlayerParser.parseInstant("2026-10-08T18:21:07+00:00")).isEqualTo(1791483667000L)
        assertThat(PlayerParser.parseInstant("2026-10-08T18:21+00:00")).isEqualTo(1791483660000L)
        assertThat(PlayerParser.parseInstant("nonsense")).isNull()
    }

    @Test
    fun `parses get_queue response with next item and radio`() {
        val response = obj(
            """{"media_player.wz":{"queue_id":"q1","name":"Erdgeschoss","current_index":5,"items":12,"elapsed_time":73,
              "current_item":{"queue_item_id":"9c","name":"Queen - Bohemian Rhapsody","duration":355,
                "media_item":{"media_type":"track","name":"Bohemian Rhapsody","version":"Remastered 2011",
                  "image":"https://i.scdn.co/image/ab67616d00001e02cafe",
                  "artists":[{"name":"Queen"}],"album":{"name":"A Night at the Opera","image":null}}},
              "next_item":{"name":"x","duration":200,"media_item":{"media_type":"radio","name":"SWR2","image":"/api/x.png","artists":[]},
                "stream_title":"Mozart – Klaviersonate Nr. 11"}}}""",
        )
        val q = PlayerParser.queue(response, "media_player.wz", base)!!
        assertThat(q.current!!.title).isEqualTo("Bohemian Rhapsody (Remastered 2011)")
        assertThat(q.current!!.artists).containsExactlyInAnyOrder("Queen")
        assertThat(q.current!!.artworkUrl).isEqualTo("https://i.scdn.co/image/ab67616d0000b273cafe")
        assertThat(q.next!!.title).isEqualTo("Mozart – Klaviersonate Nr. 11")
        assertThat(q.next!!.source).isEqualTo("SWR2")
        assertThat(q.next!!.artworkUrl).isEqualTo("http://192.168.1.5:8123/api/x.png")
        // The active queue's name: the group player the speaker plays in.
        assertThat(q.name).isEqualTo("Erdgeschoss")
        // Where the queue stands.
        assertThat(q.currentIndex).isEqualTo(5)
    }

    @Test
    fun `a music assistant group player is recognized`() {
        val group = PlayerParser.player(
            entity("playing", """{"media_title":"X","mass_player_type":"group","group_members":["media_player.kueche","media_player.bad"]}"""),
            base,
        )
        assertThat(group.isGroupPlayer).isTrue()
        assertThat(group.groupMembers).containsExactly("media_player.kueche", "media_player.bad")
        assertThat(PlayerParser.player(entity("playing", """{"media_title":"X","mass_player_type":"player"}"""), base).isGroupPlayer).isFalse()
    }

    @Test
    fun `an audiobook keeps its authors and never shows its description as an album`() {
        val book = PlayerParser.player(
            entity(
                "playing",
                """{"media_title":"Der Process","media_artist":"Franz Kafka/Max Brod","media_content_id":"library://audiobook/12",
                   "media_album_name":"Josef K. wird eines Morgens verhaftet.\n\nEin Roman über …","media_duration":32400,
                   "app_id":"music_assistant","mass_player_type":"player"}""",
            ),
            base,
        ).track!!
        assertThat(book.kind).isEqualTo(MediaKind.AUDIOBOOK)
        assertThat(book.artists).containsExactly("Franz Kafka", "Max Brod")
        assertThat(book.album).isNull()
        assertThat(book.durationMs).isEqualTo(32_400_000L)
        // Even a short blurb: a book has no album.
        val short = PlayerParser.player(
            entity("playing", """{"media_title":"Momo","media_artist":"Michael Ende","media_content_id":"audiobookshelf--x://audiobook/7","media_album_name":"Ein Märchen."}"""),
            base,
        ).track!!
        assertThat(short.album).isNull()
    }

    @Test
    fun `an episode names its show where an artist would be`() {
        val episode = PlayerParser.player(
            entity(
                "playing",
                """{"media_title":"Folge 112: Wie Wolken entstehen","media_content_id":"podcastfeed--y://podcast_episode/abc",
                   "media_album_name":"Zeit für Wissen","media_duration":2875}""",
            ),
            base,
        ).track!!
        assertThat(episode.kind).isEqualTo(MediaKind.PODCAST)
        assertThat(episode.artists).containsExactlyInAnyOrder("Zeit für Wissen")
        assertThat(episode.album).isNull()
    }

    @Test
    fun `music stays music, a station is a station, a description is never an album`() {
        val song = PlayerParser.player(
            entity("playing", """{"media_title":"X","media_artist":"AC/DC","media_content_id":"spotify--z://track/1","media_album_name":"Back in Black"}"""),
            base,
        ).track!!
        assertThat(song.kind).isEqualTo(MediaKind.MUSIC)
        // A band's own slash stays.
        assertThat(song.artists).containsExactlyInAnyOrder("AC/DC")
        assertThat(song.album).isEqualTo("Back in Black")
        val station = PlayerParser.player(
            entity("playing", """{"media_title":"Y","media_content_id":"radiobrowser--r://radio/swr2","media_album_name":"SWR2"}"""),
            base,
        ).track!!
        assertThat(station.kind).isEqualTo(MediaKind.RADIO)
        // Music Assistant names the station where the album would be: it is the source, no album.
        assertThat(station.source).isEqualTo("SWR2")
        assertThat(station.album).isNull()
        val described = PlayerParser.player(
            entity("playing", """{"media_title":"Z","media_album_name":"${"Ein langer Text über diese Aufnahme. ".repeat(6)}"}"""),
            base,
        ).track!!
        assertThat(described.kind).isEqualTo(MediaKind.MUSIC)
        assertThat(described.album).isNull()
    }

    @Test
    fun `get_queue says what kind an item is`() {
        val response = obj(
            """{"media_player.wz":{"queue_id":"q1","current_index":0,
              "current_item":{"queue_item_id":"1","name":"Der Process","duration":32400,
                "media_item":{"media_type":"audiobook","uri":"library://audiobook/12","name":"Der Process","version":"","image":null}},
              "next_item":{"queue_item_id":"2","name":"Folge 3","duration":1800,
                "media_item":{"media_type":"podcast_episode","uri":"x://podcast_episode/3","name":"Folge 3","version":""}}}}""",
        )
        val q = PlayerParser.queue(response, "media_player.wz", base)!!
        assertThat(q.current!!.kind).isEqualTo(MediaKind.AUDIOBOOK)
        assertThat(q.current!!.title).isEqualTo("Der Process")
        assertThat(q.next!!.kind).isEqualTo(MediaKind.PODCAST)
    }
}
