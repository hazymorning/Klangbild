package io.github.hazymorning.klangbild.ui

import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.domain.TrackInfo

/**
 * Invented releases for the screenshot tests. They cover the hard cases: classical
 * work titles, many performers, Japanese and Arabic script, emoji, radio without a cover, a
 * missing album, almost black and almost white covers. Covers are painted by
 * [io.github.hazymorning.klangbild.art.SyntheticCovers]; no real artwork lives in the repo.
 */
object Catalog {
    const val SCHEME = "test://cover/"

    private fun cover(c: SyntheticCover) = SCHEME + c.name

    /** An audiobook with chapters (named and numbered), read at its own pace or faster. */
    val book = TrackInfo(
        title = "Der Process",
        artists = listOf("Franz Kafka"),
        durationMs = 9_000_000L,
        artworkUrl = cover(SyntheticCover.MIST),
        kind = io.github.hazymorning.klangbild.domain.MediaKind.AUDIOBOOK,
        chapters = io.github.hazymorning.klangbild.domain.Chapters.of(
            listOf(
                io.github.hazymorning.klangbild.domain.Chapters.Raw(1, "Verhaftung", 0.0, null),
                io.github.hazymorning.klangbild.domain.Chapters.Raw(2, "Gespräch mit Frau Grubach – dann Fräulein Bürstner", 1_830.0, null),
                io.github.hazymorning.klangbild.domain.Chapters.Raw(3, "003", 3_710.0, null),
                io.github.hazymorning.klangbild.domain.Chapters.Raw(4, "004", 5_400.0, null),
            ),
            9_000_000L,
        ),
    )

    /** A podcast episode without chapters, and the next one. */
    val episode = TrackInfo(
        title = "Folge 112: Wie Wolken entstehen und warum sie nicht herunterfallen",
        artists = listOf("Zeit für Wissen"),
        durationMs = 2_875_000L,
        artworkUrl = cover(SyntheticCover.SALT_WATER),
        kind = io.github.hazymorning.klangbild.domain.MediaKind.PODCAST,
    )
    val nextEpisode = TrackInfo("Folge 113: Der Mond", listOf("Zeit für Wissen"), kind = io.github.hazymorning.klangbild.domain.MediaKind.PODCAST)

    /**
     * Invented words for the stills, as Music Assistant gives them (LRC). "Neonlicht über der
     * A40" has a break after its first verse (an empty line) and a long line that takes two rows.
     */
    val neonLyrics = """
        [00:14.20]Die Stadt schläft unter Glas
        [00:18.60]und ich fahre ohne Ziel
        [00:23.10]Die Ampeln zählen leise mit
        [00:27.40]wie oft ich an dich denk
        [00:31.90]
        [00:41.80]Neonlicht über der A40
        [00:46.30]malt deinen Namen auf den Asphalt
        [00:50.90]Ich halte an, wo keiner hält
        [00:55.20]und warte, bis es hell wird
        [01:00.10]Das Radio spielt nur noch Rauschen
        [01:04.60]und irgendwo in diesem Rauschen bist du
        [01:10.00]Neonlicht über der A40
        [01:14.50]so hell, so weit, so spät
        [01:19.20]Ich zähl die Lichter bis zur Ausfahrt
        [01:23.80]und fang von vorne an
        [01:28.30]Wir waren schneller als die Nacht
        [01:32.90]Jetzt bin ich langsamer als der Regen
        [01:37.40]Die Scheibenwischer schlagen Takt
        [01:41.90]zu einem Lied, das keiner kennt
        [01:46.50]
        [02:05.00]Neonlicht über der A40
        [02:09.50]malt deinen Namen auf den Asphalt
        [02:14.10]Ich halte an, wo keiner hält
        [02:18.60]und warte, bis es hell wird
        [02:23.00]
    """.trimIndent()

    /** Words for "Salzwasser", for a song change from words to words. */
    val saltWords = """
        [00:02.00]Das Meer nimmt alles mit
        [00:06.50]was wir am Ufer liegen ließen
        [00:11.00]Salzwasser auf der Haut
        [00:15.50]und keiner, der uns sucht
        [00:20.00]Wir treiben, bis es dunkel wird
    """.trimIndent()

    /** English words for the English stills (an invented song to the same cover). */
    val nightLyrics = """
        [00:12.00]The city sleeps beneath the glass
        [00:16.40]and I drive without a plan
        [00:20.90]The traffic lights are counting quietly
        [00:25.30]how often I still think of you
        [00:29.80]
        [00:40.00]Neon light across the highway
        [00:44.50]paints your name onto the road
        [00:49.00]I stop where no one ever stops
        [00:53.40]and wait until the morning comes
        [00:58.00]The radio plays only static
        [01:02.50]and somewhere in that static there is you
        [01:07.00]Neon light across the highway
        [01:11.40]so bright, so wide, so late
        [01:16.00]I count the lights until the exit
        [01:20.50]and then I start again
        [01:25.00]We were faster than the night
        [01:29.40]Now I am slower than the rain
        [01:34.00]
    """.trimIndent()

    /** Japanese words, for the smaller size of their script. */
    val nightJapaneseLyrics = """
        [00:10.00]夜の街を走る
        [00:14.00]光が消えるまで
        [00:18.00]君の声が聞こえる
        [00:22.00]遠い空の向こうから
        [00:26.00]夜の街を走る
    """.trimIndent()

    /** Arabic words, right to left. */
    val arabicLyrics = """
        [00:10.00]الليل طويل
        [00:14.00]وأنا أنتظر حتى الصباح
        [00:18.00]القمر فوق المدينة
        [00:22.00]والشوارع نائمة
    """.trimIndent()

    /** Long lines: a smaller size, so that each still takes no more than its rows. */
    val longLyrics = """
        [00:10.00]Wir haben die ganze Nacht auf dem Dach gesessen und den Zügen nachgesehen
        [00:16.00]bis die letzte Bahn vorbei war und die Stadt ganz leise wurde
        [00:22.00]Du hast gesagt, dass alles irgendwann vorbeigeht, und ich habe dir geglaubt
        [00:28.00]Und dann kam der Morgen
    """.trimIndent()

    /** For the queue's list, in English: a song of an album, the album's next songs, then the next record. */
    val desert = TrackInfo("Desert Song", listOf("Amal Haddad"), "Cairo Nights", 2016, 289_000, artworkUrl = cover(SyntheticCover.TERRACOTTA), queueItemId = "3")
    val cairoNights = listOf(
        TrackInfo("Old Town Rain", listOf("Amal Haddad"), "Cairo Nights", 2016, 252_000, artworkUrl = cover(SyntheticCover.TERRACOTTA), queueItemId = "4"),
        TrackInfo("Saffron", listOf("Amal Haddad"), "Cairo Nights", 2016, 238_000, artworkUrl = cover(SyntheticCover.TERRACOTTA), queueItemId = "5"),
        TrackInfo("Night Ferry", listOf("Amal Haddad"), "Cairo Nights", 2016, 303_000, artworkUrl = cover(SyntheticCover.TERRACOTTA), queueItemId = "6"),
    )
    val northernHours = listOf(
        TrackInfo("Polar Night", listOf("Ida Holm"), "Northern Hours", 2021, 312_000, artworkUrl = cover(SyntheticCover.NOCTURNE), queueItemId = "7"),
        TrackInfo("Snowfield", listOf("Ida Holm"), "Northern Hours", 2021, 280_000, artworkUrl = cover(SyntheticCover.NOCTURNE), queueItemId = "8"),
        TrackInfo("Aurora Street", listOf("Ida Holm"), "Northern Hours", 2021, 231_000, artworkUrl = cover(SyntheticCover.NOCTURNE), queueItemId = "9"),
    )

    val tracks: List<TrackInfo> = listOf(
        TrackInfo(
            "Neonlicht über der A40", listOf("Lumen Drive"), "Nachtfahrt", 2024, 251_000,
            artworkUrl = cover(SyntheticCover.NIGHT_DRIVE),
        ),
        TrackInfo(
            "Suite bergamasque, L. 75: III. Clair de lune", listOf("Clara Wendt"),
            "Debussy: Préludes & Suite bergamasque", 2019, 302_000,
            artworkUrl = cover(SyntheticCover.MIST),
        ),
        TrackInfo(
            "Kreis, Quadrat, Linie", listOf("Ensemble Dessau"), "Formen im Raum", 1998, 312_000,
            artworkUrl = cover(SyntheticCover.BAUHAUS),
        ),
        TrackInfo(
            "夜に駆ける", listOf("月島ミナ"), "夜光", 2021, 261_000,
            artworkUrl = cover(SyntheticCover.NOCTURNE),
        ),
        TrackInfo(
            "Klaviersonate Nr. 11 A-Dur, KV 331: III. Alla Turca", listOf("Wolfgang Amadeus Mozart"),
            source = "Radio Morgenlicht",
        ),
        TrackInfo(
            "أنت عمري", listOf("فرقة النيل"), "ليالي القاهرة", 2016, 289_000,
            artworkUrl = cover(SyntheticCover.TERRACOTTA),
        ),
        TrackInfo(
            "Salzwasser (feat. Ida Holm) - Radio Edit", listOf("Nordlicht Kollektiv"), "Gezeiten", 2023, 214_000,
            artworkUrl = cover(SyntheticCover.SALT_WATER),
        ),
        TrackInfo(
            "Symphonie Nr. 9 d-Moll, op. 125: IV. Presto – Allegro assai – Allegro assai vivace (alla marcia) – Andante maestoso",
            listOf(
                "Kammerphilharmonie an der Spree", "Johanna Reiter", "Mira Lindqvist",
                "Tomasz Wierzbicki", "Anselm Krüger", "Chor der Stadtkirche",
            ),
            "Beethoven: Die Sinfonien", 2020, 1_451_000,
            artworkUrl = cover(SyntheticCover.CHAMBER_MUSIC),
        ),
        TrackInfo(
            "🌙 Mondlicht-Tape ✨", listOf("lofi stube"), "Nachtschicht", 2022, 167_000,
            artworkUrl = cover(SyntheticCover.PASTEL),
        ),
        TrackInfo(
            "Unter Moos", listOf("Waldhaus"), album = null, year = null, durationMs = 213_000,
            artworkUrl = cover(SyntheticCover.MOSS),
        ),
        TrackInfo(
            "Konfetti im Kopf", listOf("Die Bunten", "Kalle Sommer"), "Sommerfest", 2025, 185_000,
            artworkUrl = cover(SyntheticCover.CONFETTI),
        ),
        TrackInfo(
            "Weißes Rauschen", listOf("Hanna Stein"), "Leerraum", 2017, 400_000,
            artworkUrl = cover(SyntheticCover.WHITE_SPACE),
        ),
    )
}
