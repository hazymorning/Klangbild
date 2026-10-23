package io.github.hazymorning.klangbild.data.ha

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkUrlsTest {
    @Test
    fun `asks cdns for larger renditions`() {
        assertThat(ArtworkUrls.upgrade("http://192.168.1.6:8095/imageproxy/ab12?size=512&fmt=jpg"))
            .isEqualTo("http://192.168.1.6:8095/imageproxy/ab12?size=1024&fmt=jpg")
        assertThat(ArtworkUrls.upgrade("http://192.168.1.6:8095/imageproxy/ab12?size=0"))
            .isEqualTo("http://192.168.1.6:8095/imageproxy/ab12?size=0")
        assertThat(ArtworkUrls.upgrade("https://i.scdn.co/image/ab67616d00001e02aabbcc"))
            .isEqualTo("https://i.scdn.co/image/ab67616d0000b273aabbcc")
        assertThat(ArtworkUrls.upgrade("https://e-cdns-images.dzcdn.net/images/cover/8a3d1f/500x500-000000-80-0-0.jpg"))
            .isEqualTo("https://e-cdns-images.dzcdn.net/images/cover/8a3d1f/1000x1000-000000-80-0-0.jpg")
        assertThat(ArtworkUrls.upgrade("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/source/600x600bb.jpg"))
            .isEqualTo("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/source/1400x1400bb.jpg")
        assertThat(ArtworkUrls.upgrade("https://resources.tidal.com/images/a1/b2/c3/d4/e5/640x640.jpg"))
            .isEqualTo("https://resources.tidal.com/images/a1/b2/c3/d4/e5/1280x1280.jpg")
        assertThat(ArtworkUrls.upgrade("https://static.qobuz.com/images/covers/ab/cd/0123456789_600.jpg"))
            .isEqualTo("https://static.qobuz.com/images/covers/ab/cd/0123456789_max.jpg")
        assertThat(ArtworkUrls.upgrade("https://lh3.googleusercontent.com/abcDEF=w120-h120-l90-rj"))
            .isEqualTo("https://lh3.googleusercontent.com/abcDEF=w1200-h1200-l90-rj")
    }

    @Test
    fun `unknown urls stay untouched`() {
        val url = "https://cdn-profiles.tunein.com/s25111/images/logog.png"
        assertThat(ArtworkUrls.upgrade(url)).isEqualTo(url)
    }

    @Test
    fun `candidates try the upgrade first and keep the original`() {
        assertThat(ArtworkUrls.candidates("https://i.scdn.co/image/ab67616d00001e02xyz", null, "http://ha/api/x"))
            .containsExactly(
                "https://i.scdn.co/image/ab67616d0000b273xyz",
                "https://i.scdn.co/image/ab67616d00001e02xyz",
                "http://ha/api/x",
            ).inOrder()
    }
}
