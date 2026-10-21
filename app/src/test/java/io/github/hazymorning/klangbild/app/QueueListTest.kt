package io.github.hazymorning.klangbild.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.UpNext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueListTest {
    private fun song(title: String, ms: Long? = 200_000L) =
        TrackInfo(title, listOf("Amal Haddad"), album = "Cairo Nights", durationMs = ms, queueItemId = "q-$title")

    @Test
    fun `the song the list announced moves it up by one, the rows and their small covers kept`() {
        val cover = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val list = QueueList(UpNext.of(listOf(song("A"), song("B"), song("C")), remaining = 3)!!, mapOf("q-A" to cover, "q-B" to cover))
        val up = list.movedUp(song("A"))!!
        assertThat(up.upNext.rows.map { it.id }).containsExactly("q-B", "q-C").inOrder()
        assertThat(up.upNext.count).isEqualTo(2)
        assertThat(up.upNext.durationMs).isEqualTo(400_000L)
        assertThat(up.covers.keys).containsExactly("q-B")
        // A length not known stays unknown.
        assertThat(QueueList(UpNext.of(listOf(song("A", ms = null), song("B")), remaining = 2)!!).movedUp(song("A"))!!.upNext.durationMs).isNull()
    }

    @Test
    fun `any other song, or the last one, has no list until its queue is read`() {
        val list = QueueList(UpNext.of(listOf(song("A"), song("B")), remaining = 2)!!)
        assertThat(list.movedUp(song("B"))).isNull()
        assertThat(list.movedUp(song("Elsewhere"))).isNull()
        assertThat(QueueList(UpNext.of(listOf(song("A")), remaining = 1)!!).movedUp(song("A"))).isNull()
    }
}
