package io.github.hazymorning.klangbild.ui

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.ui.stage.EmojiText
import org.junit.Test

class EmojiTextTest {
    @Test
    fun `glues emoji to words and scales them`() {
        val a = EmojiText.annotate("Mondlicht 🌙 Tape ✨")
        assertThat(a.text).isEqualTo("Mondlicht\u00A0🌙\u00A0Tape\u00A0✨")
        assertThat(a.spanStyles).hasSize(2)
        assertThat(EmojiText.annotate("Ohne Emoji").spanStyles).isEmpty()
        assertThat(EmojiText.containsEmoji("Family 👨‍👩‍👧")).isTrue()
        assertThat(EmojiText.containsEmoji("夜に駆ける")).isFalse()
    }
}
