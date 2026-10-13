package io.github.hazymorning.klangbild.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.text.TestTexts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Podcasts and audiobooks: what kind plays, its chapters and where in them the listener is. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpokenTest {
    private val de = TestTexts.german
    private val en = TestTexts.english()
    private val numbered: (Int) -> String = { n -> de.format(R.string.chapter_number, n) }
    private fun raw(position: Int, name: String?, start: Double, end: Double? = null) = Chapters.Raw(position, name, start, end)

    private val book = TrackInfo(
        title = "Der Process",
        artists = listOf("Franz Kafka"),
        durationMs = 3_000_000L,
        kind = MediaKind.AUDIOBOOK,
        chapters = Chapters.of(
            listOf(raw(1, "Verhaftung", 0.0), raw(2, "Gespräch mit Frau Grubach", 1000.0), raw(3, "Chapter 03", 2000.0)),
            3_000_000L,
        ),
    )

    @Test
    fun `the kind comes from Music Assistant's address or type`() {
        assertThat(MediaKind.fromUri("library://audiobook/12")).isEqualTo(MediaKind.AUDIOBOOK)
        assertThat(MediaKind.fromUri("podcastfeed--a1b2://podcast_episode/xyz")).isEqualTo(MediaKind.PODCAST)
        assertThat(MediaKind.fromUri("radiobrowser--x://radio/1")).isEqualTo(MediaKind.RADIO)
        assertThat(MediaKind.fromUri("spotify--x://track/1")).isNull()
        assertThat(MediaKind.fromUri("http://example.org/audiobook/1.mp3")).isNull()
        assertThat(MediaKind.fromUri(null)).isNull()
        assertThat(MediaKind.fromType("track")).isEqualTo(MediaKind.MUSIC)
        assertThat(MediaKind.PODCAST.spoken).isTrue()
        assertThat(MediaKind.RADIO.spoken).isFalse()
    }

    @Test
    fun `chapters are sorted and closed, broken ones left out`() {
        val ch = Chapters.of(
            listOf(raw(3, "C", 600.0), raw(1, "A", 0.0, 290.0), raw(2, "B", 300.0, 250.0), raw(4, "D", 9999.0)),
            900_000L,
        )
        // B's end lies before its start: it runs to C instead; D starts after the end.
        assertThat(ch.map { it.name }).containsExactly("A", "B", "C").inOrder()
        assertThat(ch.map { it.startMs to it.endMs }).containsExactly(0L to 290_000L, 300_000L to 600_000L, 600_000L to 900_000L).inOrder()
        // One chapter is no chapter; without a duration the last open one is dropped.
        assertThat(Chapters.of(listOf(raw(1, "A", 0.0)), 900_000L)).isEmpty()
        assertThat(Chapters.of(listOf(raw(1, "A", 0.0), raw(2, "B", 10.0), raw(3, "C", 20.0)), null)).hasSize(2)
    }

    @Test
    fun `the chapter at a position, a gap belongs to none`() {
        val ch = Chapters.of(listOf(raw(1, "A", 0.0, 290.0), raw(2, "B", 300.0)), 600_000L)
        assertThat(Chapters.at(ch, 0)).isEqualTo(0)
        assertThat(Chapters.at(ch, 289_999)).isEqualTo(0)
        assertThat(Chapters.at(ch, 295_000)).isNull()
        assertThat(Chapters.at(ch, 300_000)).isEqualTo(1)
        // Past the end of the last one (rounding): still the last.
        assertThat(Chapters.at(ch, 600_500)).isEqualTo(1)
        assertThat(Chapters.at(emptyList(), 5)).isNull()
    }

    @Test
    fun `chapters are named as a book would name them`() {
        fun label(name: String?) = Chapters.label(Chapter(name, 0, 1), 6, numbered)
        assertThat(label("Die Verhaftung")).isEqualTo("Die Verhaftung")
        assertThat(label("007")).isEqualTo("Kapitel 7")
        assertThat(label("Chapter 07")).isEqualTo("Kapitel 7")
        assertThat(label("Track 12")).isEqualTo("Kapitel 12")
        assertThat(label("Kapitel 3")).isEqualTo("Kapitel 3")
        assertThat(label(null)).isEqualTo("Kapitel 7")
        // A number inside a real name is part of it.
        assertThat(label("1984 - Teil eins")).isEqualTo(TrackText.typeset("1984 – Teil eins"))
    }

    @Test
    fun `the place in the book, and where a jump lands`() {
        val place = ChapterPlace.at(book, 1_500_000L, numbered)!!
        assertThat(place.label).isEqualTo("Gespräch mit Frau Grubach")
        assertThat(place.startMs to place.endMs).isEqualTo(1_000_000L to 2_000_000L)
        assertThat(place.next).isEqualTo("Kapitel 3")
        assertThat(ChapterPlace.at(book, 2_500_000L, numbered)!!.next).isNull()
        assertThat(ChapterPlace.at(book.copy(chapters = emptyList()), 5L, numbered)).isNull()
        assertThat(ChapterPlace.at(book, null, numbered)).isNull()

        val ch = book.chapters
        // Back: to the chapter's start, or to the one before if just after a start.
        assertThat(Chapters.jump(ch, 1_500_000L, -1)).isEqualTo(1_000_000L)
        assertThat(Chapters.jump(ch, 1_001_000L, -1)).isEqualTo(0L)
        assertThat(Chapters.jump(ch, 1_000L, -1)).isEqualTo(0L)
        // Ahead: to the next one, none after the last.
        assertThat(Chapters.jump(ch, 1_500_000L, +1)).isEqualTo(2_000_000L)
        assertThat(Chapters.jump(ch, 2_500_000L, +1)).isNull()
    }

    @Test
    fun `speed and times in words`() {
        assertThat(TimeText.speed(1f, de)).isNull()
        assertThat(TimeText.speed(1.004f, de)).isNull()
        assertThat(TimeText.speed(1.5f, de)).isEqualTo("Tempo\u00A01,5×")
        assertThat(TimeText.speed(1.25f, de)).isEqualTo("Tempo\u00A01,25×")
        assertThat(TimeText.speed(2f, de)).isEqualTo("Tempo\u00A02×")
        assertThat(TimeText.speed(0.75f, de)).isEqualTo("Tempo\u00A00,75×")
        assertThat(de.clock(1_791_483_667_000L, java.time.ZoneId.of("Europe/Berlin"))).isEqualTo("20:21")
        // In English: a decimal point, and the time in 12 hours as the system is set.
        assertThat(TimeText.speed(1.5f, en)).isEqualTo("Speed\u00A01.5×")
        assertThat(en.clock(1_791_483_667_000L, java.time.ZoneId.of("Europe/Berlin"))).isEqualTo("8:21\u00A0PM")
        assertThat(TestTexts.english(clock24 = true).clock(1_791_483_667_000L, java.time.ZoneId.of("Europe/Berlin"))).isEqualTo("20:21")
    }

    @Test
    fun `a book at one and a half times runs through its time faster`() {
        val p = PlayerSnapshot(PlayerStatus.PLAYING, book, positionMs = 10_000, positionAtEpochMs = 1_000_000, speed = 1.5f)
        assertThat(Progress.positionAt(p, nowMs = 1_004_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(16_000)
        assertThat(Progress.positionAt(p.copy(status = PlayerStatus.PAUSED), nowMs = 1_004_000, skewMs = 0, receivedAtMs = 0)).isEqualTo(10_000)
    }
}
