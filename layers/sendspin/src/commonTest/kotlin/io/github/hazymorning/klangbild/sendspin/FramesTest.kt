package io.github.hazymorning.klangbild.sendspin

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FramesTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val reassembler = Reassembler(limit = 16) { it != 9 }

    @Test
    fun `a message in one piece passes as it is`() {
        val m = bytes(4, 1, 2, 3)
        assertThat(reassembler.accept(m)).isSameInstanceAs(m)
    }

    @Test
    fun `fragments join into their message`() {
        assertThat(reassembler.accept(bytes(1, 2, 0, 0x7b))).isNull()
        assertThat(reassembler.accept(bytes(1, 0, 0x22))).isNull()
        assertThat(reassembler.accept(bytes(1, 1, 0x7d))).isNotNull().isEqualTo(bytes(0, 0x7b, 0x22, 0x7d))
        // The next message is on its own again.
        assertThat(reassembler.accept(bytes(4, 5))).isNotNull().isEqualTo(bytes(4, 5))
    }

    @Test
    fun `a message nobody reads is followed but not kept`() {
        assertThat(reassembler.accept(bytes(1, 2, 9, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17))).isNull()
        assertThat(reassembler.accept(bytes(1, 1, 1))).isNull()
        assertThat(reassembler.accept(bytes(0, 1))).isNotNull().isEqualTo(bytes(0, 1))
    }

    @Test
    fun `every malformed sequence ends the connection`() {
        val cases = listOf(
            listOf(bytes()),
            listOf(bytes(1)),
            listOf(bytes(1, 2)),
            listOf(bytes(1, 2, 1, 5)),
            listOf(bytes(1, 6, 0, 5)),
            listOf(bytes(1, 0, 5)),
            listOf(bytes(1, 1, 5)),
            listOf(bytes(1, 2, 0, 5), bytes(1, 2, 0, 5)),
            listOf(bytes(1, 2, 0, 5), bytes(4, 1)),
            listOf(bytes(1, 2, 0) + ByteArray(17)),
        )
        for (messages in cases) {
            val r = Reassembler(limit = 16) { true }
            assertFailsWith<ProtocolException> { messages.forEach { r.accept(it) } }
        }
    }

    @Test
    fun `numbers are read big-endian`() {
        val m = bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xfe, 0x80, 0, 0, 1)
        assertThat(m.int64(0)).isEqualTo(-2L)
        assertThat(m.uint32(8)).isEqualTo(0x80000001L)
    }
}
