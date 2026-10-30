package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

/** Synced lyrics: LRC read tolerantly (tidied or raw), and where the lyrics are as the track plays. */
class LyricsTest {
    private fun times(l: Lyrics) = l.lines.map { it.startMs }
    private fun texts(l: Lyrics) = l.lines.map { it.text }

    @Test
    fun `tidied LRC as Music Assistant 2_10 gives it`() {
        val l = Lrc.parse(
            """
            [00:12.30]Die Straße glänzt im Regen
            [00:17.05]und alle Ampeln stehen still
            [00:21.90]Ich fahre ohne Ziel
            """.trimIndent(),
        )!!
        assertThat(times(l)).containsExactly(12_300L, 17_050L, 21_900L)
        assertThat(texts(l)).containsExactly("Die Straße glänzt im Regen", "und alle Ampeln stehen still", "Ich fahre ohne Ziel")
        assertThat(l.breaks).isEmpty()
    }

    @Test
    fun `raw LRC of earlier versions reads the same`() {
        val raw = "\uFEFF[ar:Lumen Drive]\r\n[ti:Neonlicht]\r\n[length: 04:11]\r\n[#made by hand]\r\n" +
            "[00:40.00][01:20.00]Neonlicht, Neonlicht\r\n" +
            "[00:12.3]<00:12.30>Die <00:12.80>Straße <00:13.40>glänzt\r\n" +
            "[00:17:05] und   alle Ampeln\r\n" +
            "[00:21.905]Ich fahre - ohne Ziel\r\n"
        val l = Lrc.parse(raw)!!
        // In time order; the chorus once per time; a word's own times gone; a colon before the fraction
        // read as a point; a spaced hyphen set as a dash that stays with the word before it.
        assertThat(times(l)).containsExactly(12_300L, 17_050L, 21_905L, 40_000L, 80_000L)
        assertThat(texts(l)).containsExactly(
            "Die Straße glänzt", "und alle Ampeln", "Ich fahre\u00A0– ohne Ziel", "Neonlicht, Neonlicht", "Neonlicht, Neonlicht",
        )
    }

    @Test
    fun `lyrics still sung after the track ends belong to a longer recording`() {
        val l = Lrc.parse("[00:10.00]Erste Zeile\n[03:58.00]Letzte Zeile")!!
        assertThat(l.fit(240_000)).isTrue()
        // A length a few seconds short, as another copy of the recording may have it.
        assertThat(l.fit(232_000)).isTrue()
        // The radio edit of a longer album version.
        assertThat(l.fit(200_000)).isFalse()
    }

    @Test
    fun `an offset brings the lyrics sooner or later`() {
        val text = "[offset:+500]\n[00:10.00]Eins\n[00:20.00]Zwei"
        assertThat(times(Lrc.parse(text)!!)).containsExactly(9_500L, 19_500L)
        assertThat(times(Lrc.parse(text.replace("+500", "-250"))!!)).containsExactly(10_250L, 20_250L)
    }

    @Test
    fun `plain lyrics are none, and so are lyrics with one time for all`() {
        assertThat(Lrc.parse(null)).isNull()
        assertThat(Lrc.parse("  \n ")).isNull()
        assertThat(Lrc.parse("Die Straße glänzt im Regen\nund alle Ampeln stehen still\nIch fahre ohne Ziel")).isNull()
        // A time here and there in plain lyrics.
        assertThat(Lrc.parse("Strophe eins\nZeile zwei\n[02:30] Solo\nZeile vier\nZeile fünf")).isNull()
        assertThat(Lrc.parse("[00:00.00]Eins\n[00:00.00]Zwei\n[00:00.00]Drei\n[00:05.00]Vier")).isNull()
        // Only one line with a time.
        assertThat(Lrc.parse("[00:10.00]Eins")).isNull()
    }

    @Test
    fun `synced lyrics in the plain field are found there`() {
        val lrc = "[00:10.00]Eins\n[00:20.00]Zwei"
        assertThat(times(Lrc.of(lyrics = lrc, lrcLyrics = null)!!)).containsExactly(10_000L, 20_000L)
        assertThat(Lrc.of(lyrics = "Eins\nZwei", lrcLyrics = null)).isNull()
        // The synced field wins.
        assertThat(texts(Lrc.of(lyrics = "[00:01.00]Alt\n[00:02.00]Alt", lrcLyrics = lrc)!!)).containsExactly("Eins", "Zwei")
    }

    @Test
    fun `lines sung together are one line, each on its own row`() {
        val l = Lrc.parse("[00:10.00]Ich rufe\n[00:10.10](ich rufe)\n[00:10.00]Ich rufe\n[00:14.00]und keiner hört")!!
        assertThat(texts(l)).containsExactly("Ich rufe\n(ich rufe)", "und keiner hört")
        assertThat(times(l)).containsExactly(10_000L, 14_000L)
    }

    @Test
    fun `an empty line is a break when the singing rests a while`() {
        val l = Lrc.parse(
            """
            [00:00.00]
            [00:05.00]Erste Zeile
            [00:09.00]Zweite Zeile
            [00:13.00]
            [00:15.00] ♪
            [00:30.00]Nach dem Solo
            [00:33.00]
            [00:35.00]Gleich weiter
            [00:40.00][Instrumental]
            [01:00.00]Ende
            [01:04.00]
            """.trimIndent(),
        )!!
        assertThat(texts(l)).containsExactly("Erste Zeile", "Zweite Zeile", "Nach dem Solo", "Gleich weiter", "Ende")
        // Not before the first line, not for a breath (two seconds), rests in a row as one, and the end.
        assertThat(l.breaks).containsExactly(13_000L, 40_000L, 64_000L)
    }

    @Test
    fun `the line sung last is in the middle, highlighted while it is sung`() {
        val l = Lrc.parse("[00:05.00]Eins\n[00:09.00]Zwei\n[00:13.00]\n[00:30.00]Drei")!!
        assertThat(l.placeAt(0)).isEqualTo(LyricPlace(0, lit = false))
        assertThat(l.placeAt(5_000)).isEqualTo(LyricPlace(0, lit = true))
        assertThat(l.placeAt(10_000)).isEqualTo(LyricPlace(1, lit = true))
        assertThat(l.placeAt(13_000)).isEqualTo(LyricPlace(1, lit = false))
        assertThat(l.placeAt(29_999)).isEqualTo(LyricPlace(1, lit = false))
        assertThat(l.placeAt(30_000)).isEqualTo(LyricPlace(2, lit = true))
    }

    @Test
    fun `the last line dims once it is sung, so a long outro is quiet`() {
        // Lines four seconds apart: the last one is sung as long.
        val l = Lrc.parse("[00:05.00]Eins\n[00:09.00]Zwei\n[00:13.00]Drei\n[00:17.00]Vier")!!
        assertThat(l.placeAt(20_999)).isEqualTo(LyricPlace(3, lit = true))
        assertThat(l.placeAt(21_000)).isEqualTo(LyricPlace(3, lit = false))
        assertThat(l.placeAt(200_000)).isEqualTo(LyricPlace(3, lit = false))
        // In a fast song a little longer, and in a slow one not for the whole outro.
        val fast = Lrc.parse("[00:05.00]Eins\n[00:06.00]Zwei\n[00:07.00]Drei")!!
        assertThat(fast.placeAt(9_999).lit).isTrue()
        assertThat(fast.placeAt(10_000).lit).isFalse()
        val slow = Lrc.parse("[00:05.00]Eins\n[00:25.00]Zwei\n[00:45.00]Drei")!!
        assertThat(slow.placeAt(52_999).lit).isTrue()
        assertThat(slow.placeAt(53_000).lit).isFalse()
    }

    @Test
    fun `a line comes a little early, so it lands with the voice`() {
        val l = Lrc.parse("[00:05.00]Eins\n[00:09.00]Zwei")!!
        val cursor = LyricsCursor(l)
        assertThat(cursor.at(9_000 - LyricsCursor.LEAD_MS - 1)).isEqualTo(LyricPlace(0, lit = true))
        assertThat(cursor.at(9_000 - LyricsCursor.LEAD_MS)).isEqualTo(LyricPlace(1, lit = true))
    }

    @Test
    fun `a correction of the position never steps back, a jump does`() {
        val l = Lrc.parse("[00:05.00]Eins\n[00:09.00]Zwei\n[00:13.00]Drei\n[00:40.00]Vier")!!
        val cursor = LyricsCursor(l, leadMs = 0)
        assertThat(cursor.at(9_200).index).isEqualTo(1)
        // A report a little behind: the line stays.
        assertThat(cursor.at(8_400).index).isEqualTo(1)
        assertThat(cursor.at(8_900).index).isEqualTo(1)
        // No position (the track is leaving): it stays where it is.
        assertThat(cursor.at(null).index).isEqualTo(1)
        // A jump ahead, and one back.
        assertThat(cursor.at(41_000).index).isEqualTo(3)
        assertThat(cursor.at(6_000).index).isEqualTo(0)
        // Paused: the same moment again and again.
        repeat(3) { assertThat(cursor.at(6_000)).isEqualTo(LyricPlace(0, lit = true)) }
    }
}
