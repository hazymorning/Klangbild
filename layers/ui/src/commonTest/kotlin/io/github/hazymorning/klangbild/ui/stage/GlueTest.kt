package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.ui.text.AnnotatedString
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** Which words of a title stay together on a line. */
class GlueTest {
    private fun glued(title: String) = glueShortWords(AnnotatedString(title)).text.replace(NBSP, '+')

    @Test
    fun `a short word stays with the next, but the glue never runs on`() {
        assertThat(glued("Spiegel im Spiegel")).isEqualTo("Spiegel im+Spiegel")
        assertThat(glued("We are never apart")).isEqualTo("We+are never apart")
        assertThat(glued("Do it for you")).isEqualTo("Do+it for+you")
        assertThat(glued("Der Mann im Mond")).isEqualTo("Der+Mann im+Mond")
        // Only words of letters count: no abbreviation and no number.
        assertThat(glued("Op. 9 No. 2")).isEqualTo("Op. 9 No. 2")
    }
}
