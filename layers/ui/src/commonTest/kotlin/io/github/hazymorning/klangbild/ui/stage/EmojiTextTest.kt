package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.ui.text.AnnotatedString
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.each
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import kotlin.test.Test

class EmojiTextTest {
    /** The parts of [text] set as emoji. */
    private fun emoji(text: AnnotatedString) = text.spanStyles.map { text.text.substring(it.start, it.end) }

    @Test
    fun `glues emoji to the words around them and sets them smaller`() {
        val a = EmojiText.annotate("Mondlicht 🌙 Tape ✨")
        assertThat(a.text).isEqualTo("Mondlicht 🌙 Tape ✨")
        assertThat(emoji(a)).containsExactly("🌙", "✨")
        assertThat(a.spanStyles.map { it.item.fontSize.value }).each { it.isLessThan(1f) }
    }

    @Test
    fun `a family is one emoji, and Japanese is none`() {
        assertThat(emoji(EmojiText.annotate("Family 👨‍👩‍👧"))).containsExactly("👨‍👩‍👧")
        assertThat(EmojiText.annotate("夜に駆ける").spanStyles).isEmpty()
        assertThat(EmojiText.annotate("Ohne Emoji").spanStyles).isEmpty()
    }
}
