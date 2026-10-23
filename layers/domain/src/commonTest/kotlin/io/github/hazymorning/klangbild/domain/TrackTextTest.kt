package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.endsWith
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNull
import assertk.assertions.startsWith
import kotlin.test.Test

class TrackTextTest {
    /** What the German texts put for the artists left out. */
    private val others = "u.\u00A0a."

    private fun t(title: String, artists: List<String> = listOf("A"), album: String? = null) =
        TrackText.from(TrackInfo(title, artists, album), others)

    @Test
    fun `splits classical work and movement`() {
        val x = t("Suite bergamasque, L. 75: III. Clair de lune")
        assertThat(x.work).isEqualTo("Suite bergamasque, L.\u00A075")
        assertThat(x.title).isEqualTo("III. Clair de lune")

        val y = t("Symphony No. 9 in D Minor, Op. 125 \"Choral\": IV. Presto - Allegro assai")
        assertThat(y.title).isEqualTo("IV. Presto\u00A0– Allegro assai")
        assertThat(y.work).isEqualTo("Symphony No.\u00A09 in D Minor, Op.\u00A0125 \"Choral\"")

        val z = t("Goldberg-Variationen, BWV 988: Aria")
        assertThat(z.work).isEqualTo("Goldberg-Variationen, BWV\u00A0988")
        assertThat(z.title).isEqualTo("Aria")
    }

    @Test
    fun `keeps ordinary colons in pop titles`() {
        val x = t("Interlude: Shine")
        assertThat(x.work).isNull()
        assertThat(x.title).isEqualTo("Interlude: Shine")
    }

    @Test
    fun `moves featured artists and version notes out of the title`() {
        val x = t("Get Lucky (feat. Pharrell Williams & Nile Rodgers) - Radio Edit", listOf("Daft Punk"))
        assertThat(x.title).isEqualTo("Get Lucky")
        assertThat(x.version).isEqualTo("Radio Edit")
        assertThat(x.artists).isEqualTo("Daft Punk, Pharrell Williams & Nile Rodgers")

        val y = t("Wish You Were Here - 2011 Remaster")
        assertThat(y.title).isEqualTo("Wish You Were Here")
        assertThat(y.version).isEqualTo("2011 Remaster")

        val z = t("Heroes (Remastered 2017)")
        assertThat(z.title).isEqualTo("Heroes")
        assertThat(z.version).isEqualTo("Remastered 2017")

        // Not a version note: must stay.
        assertThat(t("(I Can't Get No) Satisfaction").title).isEqualTo("(I Can't Get No) Satisfaction")
        assertThat(t("Live").title).isEqualTo("Live")
    }

    @Test
    fun `formats artists and shortens long lists`() {
        assertThat(TrackText.formatArtists(emptyList(), others)).isNull()
        assertThat(TrackText.formatArtists(listOf("Nina Simone"), others)).isEqualTo("Nina Simone")
        assertThat(TrackText.formatArtists(listOf("A", "B"), others)).isEqualTo("A & B")
        assertThat(TrackText.formatArtists(listOf("A", "B", "C"), others)).isEqualTo("A, B & C")
        val orchestra = listOf(
            "Berliner Philharmoniker", "Herbert von Karajan", "Wiener Singverein",
            "Gundula Janowitz", "Hilde Rössel-Majdan", "Waldemar Kmentt", "Walter Berry",
        )
        val s = TrackText.formatArtists(orchestra, others)!!
        assertThat(s).endsWith(" u.\u00A0a.")
        assertThat(s.length).isLessThanOrEqualTo(TrackText.MAX_ARTISTS_CHARS)
        assertThat(s).startsWith("Berliner Philharmoniker, Herbert von Karajan")
    }

    @Test
    fun `drops an album that only repeats the title`() {
        assertThat(t("Blackstar", album = "Blackstar").album).isNull()
        assertThat(t("Lazarus", album = "Blackstar").album).isEqualTo("Blackstar")
    }

    @Test
    fun `drops emoji at the edges of a title but keeps inner ones`() {
        assertThat(t("🌙 Mondlicht-Tape ✨").title).isEqualTo("Mondlicht-Tape")
        assertThat(t("Love ❤️ Song").title).isEqualTo("Love ❤️ Song")
        assertThat(t("🔥🔥🔥").title).isEqualTo("🔥🔥🔥")
    }

    @Test
    fun `offers artist lines from complete to short`() {
        val x = TrackText.from(TrackInfo("T", listOf("Kammerphilharmonie an der Spree", "Johanna Reiter", "Paul Weber")), others)
        assertThat(x.artistOptions()).containsExactlyInAnyOrder(
            "Kammerphilharmonie an der Spree, Johanna Reiter & Paul Weber",
            "Kammerphilharmonie an der Spree, Johanna Reiter u.\u00A0a.",
            "Kammerphilharmonie an der Spree u.\u00A0a.",
        )
        assertThat(x.shortArtists).isEqualTo("Kammerphilharmonie an der Spree u.\u00A0a.")
        assertThat(TrackText.from(TrackInfo("T", listOf("A", "B")), others).shortArtists).isEqualTo("A & B")
    }

    @Test
    fun `keeps abbreviations with their numbers`() {
        assertThat(TrackText.typeset("Klaviersonate Nr. 11 A-Dur, KV 331")).isEqualTo("Klaviersonate Nr.\u00A011 A-Dur, KV\u00A0331")
        assertThat(TrackText.typeset("Sinfonie Nr. 9 d-Moll, op. 125")).isEqualTo("Sinfonie Nr.\u00A09 d-Moll, op.\u00A0125")
        assertThat(TrackText.typeset("Mr. Brightside")).isEqualTo("Mr. Brightside")
    }
}
