package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

/** A bio as Music Assistant keeps it, made fit for the screen: without pronunciations, in whole sentences, and paced. */
class ArtistBioTest {
    private fun sentences(text: String) = ArtistBio.of(text)!!.paragraphs

    @Test
    fun `nothing to show is no bio`() {
        assertThat(ArtistBio.of(null)).isNull()
        assertThat(ArtistBio.of("  \n\n ")).isNull()
        assertThat(ArtistBio.of("(/ˈkræft.vɛrk/)")).isNull()
    }

    @Test
    fun `paragraphs keep apart, and a sentence ends where the next one starts`() {
        val bio = sentences("Lumen Drive is a German synthwave duo. It was formed in 2016 in Essen!\n\nTheir first album came out in 2018. Is it their best?")
        assertThat(bio).containsExactly(
            listOf("Lumen Drive is a German synthwave duo.", "It was formed in 2016 in Essen!"),
            listOf("Their first album came out in 2018.", "Is it their best?"),
        )
    }

    @Test
    fun `abbreviations, initials and German dates end no sentence`() {
        assertThat(sentences("J. S. Bach wrote it, e.g. for Leipzig. It is in D minor.").single())
            .containsExactly("J. S. Bach wrote it, e.g. for Leipzig.", "It is in D minor.")
        assertThat(sentences("Die Band wurde am 1. Juli 1970 gegründet. Im 20. Jahrhundert spielte sie z. B. in Berlin, u. a. mit Dr. Mabuse.").single())
            .containsExactly("Die Band wurde am 1. Juli 1970 gegründet.", "Im 20. Jahrhundert spielte sie z. B. in Berlin, u. a. mit Dr. Mabuse.")
        // A year ends one: four figures are no date of the month.
        assertThat(sentences("It came out in 1991. The tour followed.").single()).containsExactly("It came out in 1991.", "The tour followed.")
    }

    @Test
    fun `HTML from another source reads as plain text`() {
        assertThat(sentences("<p>Lumen Drive is a German synthwave duo.</p>\n<p>Their first album came\nout in 2018 &amp; sold well.<br/>It&#39;s called &quot;Neon&quot;.</p>"))
            .containsExactly(
                listOf("Lumen Drive is a German synthwave duo."),
                listOf("Their first album came out in 2018 & sold well."),
                listOf("It's called \"Neon\"."),
            )
        assertThat(sentences("<b>Kraftwerk</b> is a <a href=\"https://example.com/k\">German band</a>.<sup>[1]</sup>").single())
            .containsExactly("Kraftwerk is a German band.")
        // Escaped twice, the tags still go.
        assertThat(sentences("&lt;p&gt;Theo Lind is a Swedish pianist.&lt;/p&gt;").single()).containsExactly("Theo Lind is a Swedish pianist.")
        assertThat(ArtistBio.of("<p></p><br>")).isNull()
        // Without tags, a line break still starts a paragraph.
        assertThat(sentences("Lumen Drive is a duo.\nThey met in 2014 &amp; stayed.")).containsExactly(listOf("Lumen Drive is a duo."), listOf("They met in 2014 & stayed."))
    }

    @Test
    fun `a sentence keeps the quotes and brackets that close after its last mark`() {
        assertThat(sentences("They called it \"Neon.\" Then came the second (and last) album.").single())
            .containsExactly("They called it \"Neon.\"", "Then came the second (and last) album.")
    }

    @Test
    fun `how a name is spoken goes, what it means stays`() {
        assertThat(sentences("Kraftwerk (German: [ˈkʁaftvɛʁk], lit. 'power station') is a German band (formed 1970).").single())
            .containsExactly("Kraftwerk is a German band (formed 1970).")
        assertThat(sentences("Björk (/bjɜːrk/ BYURK; born 21 November 1965) is an Icelandic singer.").single())
            .containsExactly("Björk is an Icelandic singer.")
        assertThat(sentences("Kraftwerk [ˈkʁaftvɛʁk] ist eine Band aus Düsseldorf.").single())
            .containsExactly("Kraftwerk ist eine Band aus Düsseldorf.")
        assertThat(sentences("Sigur Rós (Aussprache: [ˈsɪːɣʏr ˈrouːs]) ist eine Band aus Reykjavík.").single())
            .containsExactly("Sigur Rós ist eine Band aus Reykjavík.")
    }

    @Test
    fun `a page stands long enough to be read, and the pages follow the song round`() {
        assertThat(BioPacing.durationMs(0)).isEqualTo(BioPacing.MIN_MS)
        assertThat(BioPacing.durationMs(300)).isEqualTo(BioPacing.START_MS + 300 * BioPacing.MS_PER_CHAR)
        assertThat(BioPacing.durationMs(10_000)).isEqualTo(BioPacing.MAX_MS)
        val pages = intArrayOf(300, 300, 100)
        val first = BioPacing.durationMs(300)
        assertThat(BioPacing.pageAt(null, pages)).isEqualTo(0)
        assertThat(BioPacing.pageAt(0, pages)).isEqualTo(0)
        assertThat(BioPacing.pageAt(first - 1, pages)).isEqualTo(0)
        assertThat(BioPacing.pageAt(first, pages)).isEqualTo(1)
        assertThat(BioPacing.pageAt(2 * first, pages)).isEqualTo(2)
        // Once all have stood, the first comes again.
        assertThat(BioPacing.pageAt(2 * first + BioPacing.MIN_MS, pages)).isEqualTo(0)
        // A single page stands throughout.
        assertThat(BioPacing.pageAt(10 * 60_000L, intArrayOf(500))).isEqualTo(0)
    }
}
