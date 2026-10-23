package io.github.hazymorning.klangbild.data.ma

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.startsWith
import io.github.hazymorning.klangbild.domain.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/** Music Assistant's queue items (as `player_queues/items` returns them) as tracks around the current one. */
class MaQueueTest {
    private val base = "http://192.168.1.5:8095"

    private val items = Json.parseToJsonElement(
        """
        [
          {"queue_id":"q1","queue_item_id":"a","name":"Lumen Drive - Neonlicht über der A40","duration":251,
           "media_item":{"media_type":"track","name":"Neonlicht über der A40","version":"",
             "artists":[{"name":"Lumen Drive","item_id":"1","provider":"library","media_type":"artist"}],
             "album":{"name":"Nachtfahrt","year":2024,"item_id":"9","provider":"library","media_type":"album"}},
           "image":{"type":"thumb","path":"/music/nachtfahrt/cover.jpg","provider":"filesystem--x","remotely_accessible":false,"proxy_id":"3f2a"},
           "index":0,"available":true},
          {"queue_id":"q1","queue_item_id":"b","name":"Arvo Pärt - Spiegel im Spiegel (Live)","duration":180.5,
           "media_item":{"media_type":"track","name":"Spiegel im Spiegel","version":"Live","artists":[{"name":"Arvo Pärt"}],
             "album":{"name":"Alina","year":1999}},
           "image":{"type":"thumb","path":"https://i.scdn.co/image/ab67616d00001e02abc","provider":"spotify","remotely_accessible":true,"proxy_id":"77aa"}},
          {"queue_id":"q1","queue_item_id":"c","name":"Radio X","duration":null,
           "media_item":{"media_type":"radio","name":"Radio X"},"streamdetails":{"stream_title":"Jemand - Etwas"},"image":null},
          {"queue_id":"q1","queue_item_id":"d","name":"Nur ein Name","duration":0}
        ]
        """,
    ).jsonArray

    @Test
    fun `items become tracks with their version, year and picture`() {
        val w = MaQueue.window(items, offset = 10, currentIndex = 11, baseUrl = base)
        val played = w.played.single()
        assertThat(played.title).isEqualTo("Neonlicht über der A40")
        assertThat(played.artists).containsExactlyInAnyOrder("Lumen Drive")
        assertThat(played.album).isEqualTo("Nachtfahrt")
        assertThat(played.year).isEqualTo(2024)
        assertThat(played.durationMs).isEqualTo(251_000L)
        // A local picture comes through Music Assistant's proxy, the larger size first.
        assertThat(played.artworkUrl).isEqualTo("$base/imageproxy/3f2a?size=1024")
        assertThat(played.artworkFallbackUrl).isEqualTo("$base/imageproxy/3f2a?size=512")
        // Which item of the queue it is.
        assertThat(played.queueItemId).isEqualTo("a")

        val current = w.current!!
        assertThat(current.title).isEqualTo("Spiegel im Spiegel (Live)")
        assertThat(current.durationMs).isEqualTo(180_500L)
        // A public picture is fetched where it is (the largest rendition), the proxy behind it.
        assertThat(current.artworkUrl).isEqualTo("https://i.scdn.co/image/ab67616d0000b273abc")
        assertThat(current.artworkFallbackUrl).isEqualTo("$base/imageproxy/77aa?size=512")

        val (radio, plain) = w.coming
        assertThat(radio.title).isEqualTo("Jemand - Etwas")
        assertThat(radio.source).isEqualTo("Radio X")
        assertThat(radio.artworkUrl).isNull()
        assertThat(plain.title).isEqualTo("Nur ein Name")
        assertThat(plain.durationMs).isNull()
        assertThat(plain.queueItemId).isEqualTo("d")
    }

    @Test
    fun `the queue as it stands says where it is and what comes next, or that nothing does`() {
        fun item(id: String, title: String) =
            """{"queue_item_id":"$id","name":"$title","media_item":{"media_type":"track","name":"$title","artists":[{"name":"A"}]}}"""
        val now = MaQueue.now(
            Json.parseToJsonElement("""{"queue_id":"q1","current_index":3,"items":9,"current_item":${item("a", "Eins")},"next_item":${item("b", "Zwei")}}""").jsonObject,
            "http://192.168.1.5:8095",
        )
        assertThat(now.index).isEqualTo(3)
        assertThat(now.items).isEqualTo(9)
        assertThat(now.current!!.title).isEqualTo("Eins")
        assertThat(now.next!!.title).isEqualTo("Zwei")
        assertThat(now.nextId).isEqualTo("b")
        val last = MaQueue.now(Json.parseToJsonElement("""{"current_index":8,"current_item":${item("z", "Neun")},"next_item":null}""").jsonObject, "http://h")
        assertThat(last.next).isNull()
        assertThat(last.nextId).isNull()
    }

    @Test
    fun `the window reads far enough around the current item, never before the start`() {
        assertThat(MaQueue.range(5)).isEqualTo(0 to 5 + 1 + MaQueue.REACH)
        assertThat(MaQueue.range(100)).isEqualTo(100 - MaQueue.REACH to 2 * MaQueue.REACH + 1)
        // The current item outside what was read: nothing current, nothing to come.
        val w = MaQueue.window(items, offset = 10, currentIndex = 30, baseUrl = base)
        assertThat(w.current).isNull()
        assertThat(w.coming).isEmpty()
        assertThat(w.played).hasSize(4)
    }

    private val spoken = Json.parseToJsonElement(
        """
        [
          {"queue_id":"q1","queue_item_id":"a","name":"Der Process","duration":32400,"extra_attributes":{"playback_speed":1.25},
           "media_item":{"media_type":"audiobook","name":"Der Process","version":"","authors":["Franz Kafka",{"name":"Max Brod"}],
             "narrators":["Ulrich Matthes"],
             "metadata":{"description":"<p>Lang …</p>","chapters":[
               {"position":2,"name":"Gespräch mit Frau Grubach","start":1834.2,"end":null},
               {"position":1,"name":"Verhaftung","start":0.0,"end":1834.2},
               {"position":3,"name":"003","start":3600.0,"end":null}]}}},
          {"queue_id":"q1","queue_item_id":"b","name":"Folge 112","duration":2875,
           "media_item":{"media_type":"podcast_episode","name":"Folge 112","podcast":{"name":"Zeit für Wissen","media_type":"podcast"},
             "metadata":{"chapters":[]}}}
        ]
        """,
    ).jsonArray

    @Test
    fun `a book brings its authors, chapters and speed, an episode its show`() {
        val w = MaQueue.window(spoken, offset = 0, currentIndex = 0, baseUrl = base)
        val book = w.current!!
        assertThat(book.kind).isEqualTo(MediaKind.AUDIOBOOK)
        assertThat(book.artists).containsExactly("Franz Kafka", "Max Brod")
        assertThat(book.album).isNull()
        // Sorted by start; each ends where the next begins, the last at the book's end.
        assertThat(book.chapters.map { it.name }).containsExactly("Verhaftung", "Gespräch mit Frau Grubach", "003")
        assertThat(book.chapters.map { it.endMs }).containsExactly(1_834_200L, 3_600_000L, 32_400_000L)
        assertThat(w.speed).isCloseTo(1.25f, 1e-6f)
        val episode = w.coming.single()
        assertThat(episode.kind).isEqualTo(MediaKind.PODCAST)
        assertThat(episode.artists).containsExactlyInAnyOrder("Zeit für Wissen")
        assertThat(episode.chapters).isEmpty()
        // Music plays at its own pace.
        assertThat(MaQueue.window(items, offset = 10, currentIndex = 11, baseUrl = base).speed).isEqualTo(1f)
        // A book isn't a song, so no lyrics are requested for it.
        assertThat(w.currentSong).isNull()
    }

    @Test
    fun `the current song and the next come with their address and the words they bring`() {
        val songs = Json.parseToJsonElement(
            """
            [
              {"queue_id":"q1","queue_item_id":"a","name":"Lumen Drive - Neonlicht über der A40","duration":251,
               "media_item":{"media_type":"track","name":"Neonlicht über der A40","uri":"library://track/12",
                 "metadata":{"lyrics":null,"lrc_lyrics":"[00:05.00]Erste Zeile\n[00:09.00]Zweite Zeile"}}},
              {"queue_id":"q1","queue_item_id":"b","name":"Waldhaus - Unter Moos","duration":213,
               "media_item":{"media_type":"track","name":"Unter Moos","uri":"spotify://track/4x2","metadata":{}}},
              {"queue_id":"q1","queue_item_id":"c","name":"Radio X","media_item":{"media_type":"radio","name":"Radio X","uri":"library://radio/3"}}
            ]
            """,
        ).jsonArray
        val w = MaQueue.window(songs, offset = 0, currentIndex = 0, baseUrl = base)
        val current = w.currentSong!!
        assertThat(current.uri).isEqualTo("library://track/12")
        assertThat(current.lrcLyrics).isNotNull().startsWith("[00:05.00]")
        assertThat(current.lyrics).isNull()
        // The item as Music Assistant gave it, to ask for its words with.
        assertThat(current.item["name"].toString()).isEqualTo("\"Neonlicht über der A40\"")
        val next = w.nextSong!!
        assertThat(next.uri).isEqualTo("spotify://track/4x2")
        assertThat(next.lrcLyrics).isNull()
        // A station isn't a song.
        assertThat(MaQueue.window(songs, offset = 0, currentIndex = 1, baseUrl = base).nextSong).isNull()
    }

    @Test
    fun `the queue says where it stands in its item to the millisecond, on its own clock`() {
        val queue = Json.parseToJsonElement(
            """{"state":"playing","elapsed_time":61.25,"elapsed_time_last_updated":1792630000.125,"playback_speed":1.5}""",
        ).jsonObject
        assertThat(MaQueue.position(queue)).isEqualTo(QueuePosition(elapsedMs = 61_250, atMs = 1_792_630_000_125, playing = true))
        val paused = Json.parseToJsonElement("""{"state":"paused","elapsed_time":3,"elapsed_time_last_updated":1792630000,"playback_speed":40}""").jsonObject
        assertThat(MaQueue.position(paused)).isEqualTo(QueuePosition(elapsedMs = 3_000, atMs = 1_792_630_000_000, playing = false))
        assertThat(MaQueue.position(Json.parseToJsonElement("""{"state":"playing"}""").jsonObject)).isNull()
    }
}

