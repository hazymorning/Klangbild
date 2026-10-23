package io.github.hazymorning.klangbild.data.ha

/**
 * "Get the highest available resolution": many artwork URLs encode their size. These
 * rewrites ask the same CDN for a larger rendition. Every upgraded URL is tried first and
 * the original stays as fallback, so a wrong guess costs one failed request, never the cover.
 */
object ArtworkUrls {
    private val maImageProxy = Regex("""(/imageproxy[/?][^#]*?[?&]size=)(\d+)""")
    private val spotify = Regex("""(i\.scdn\.co/image/ab67616d0000)(1e02|4851|b273)""")
    private val deezer = Regex("""(dzcdn\.net/images/(?:cover|artist)/[0-9a-f]+/)(\d+)x(\d+)(-[^/]*)?(\.jpg|\.png)""")
    private val apple = Regex("""(mzstatic\.com/image/thumb/.+/)(\d+)x(\d+)(bb|cc|sr)?(\.jpg|\.png|\.webp)""")
    private val tidal = Regex("""(resources\.tidal\.com/images/.+/)(\d+)x(\d+)(\.jpg)""")
    private val qobuz = Regex("""(static\.qobuz\.com/images/covers/.+_)(\d+|small|large|thumbnail)(\.jpg)""")
    private val google = Regex("""(googleusercontent\.com/[^=]+|ggpht\.com/[^=]+)=w\d+-h\d+([^\s]*)""")

    /** Music Assistant's imageproxy offers 0 (original) and 80 to 1024; 1024 is plenty for TV. */
    const val MA_SIZE = 1024

    fun upgrade(url: String): String {
        maImageProxy.find(url)?.let { m ->
            val size = m.groupValues[2].toInt()
            return if (size in 1 until MA_SIZE) url.replaceRange(m.groups[2]!!.range, MA_SIZE.toString()) else url
        }
        spotify.find(url)?.let { m ->
            // b273 = 640 px, the largest public rendition
            return url.replaceRange(m.groups[2]!!.range, "b273")
        }
        deezer.find(url)?.let { m ->
            val size = m.groupValues[2].toInt()
            if (size < 1000) {
                val rest = m.groupValues[4].ifEmpty { "-000000-80-0-0" }
                return url.replaceRange(m.range, m.groupValues[1] + "1000x1000" + rest + m.groupValues[5])
            }
            return url
        }
        apple.find(url)?.let { m ->
            val size = m.groupValues[2].toInt()
            if (size < 1400) {
                return url.replaceRange(m.range, m.groupValues[1] + "1400x1400" + m.groupValues[4].ifEmpty { "bb" } + m.groupValues[5])
            }
            return url
        }
        tidal.find(url)?.let { m ->
            if (m.groupValues[2].toInt() < 1280) return url.replaceRange(m.range, m.groupValues[1] + "1280x1280" + m.groupValues[4])
            return url
        }
        qobuz.find(url)?.let { m ->
            return url.replaceRange(m.range, m.groupValues[1] + "max" + m.groupValues[3])
        }
        google.find(url)?.let { m ->
            return url.replaceRange(m.range, m.groupValues[1] + "=w1200-h1200" + m.groupValues[2].replace(Regex("""^-?s\d+"""), ""))
        }
        return url
    }

    /** Ordered, de-duplicated list of URLs to try for a cover. */
    fun candidates(vararg urls: String?): List<String> {
        val out = LinkedHashSet<String>()
        for (u in urls) {
            if (u.isNullOrBlank()) continue
            out += upgrade(u)
            out += u
        }
        return out.toList()
    }
}
