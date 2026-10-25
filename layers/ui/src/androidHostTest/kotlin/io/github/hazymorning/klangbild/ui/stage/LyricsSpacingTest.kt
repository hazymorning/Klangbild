package io.github.hazymorning.klangbild.ui.stage

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isGreaterThan
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.ScenePalette
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.ui.composeRule
import io.github.hazymorning.klangbild.ui.theme.Faces
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.faces
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The words keep their spaces in order in both layouts and at every size: a line's rows closest,
 * then the lines, and the most space to the status line and the song, for a line sung on one row
 * between two lines of the most rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LyricsSpacingTest {
    @get:Rule
    val compose = composeRule()

    @Test
    fun `lines stand farther apart than their rows and nearer than the status line and the song`() {
        lateinit var faces: Faces
        compose.setContent { faces = faces() }
        compose.waitForIdle()
        val styles = StageStyles(ScenePalette(Appearance.DARK, 0xFF101010.toInt(), 0xFFEEEEEE.toInt(), 0xFFBBBBBB.toInt(), 0xFF999999.toInt()), faces)
        for (flow in listOf(false, true)) {
            val room = LyricsRoom(StageGrid(ScreenSpec(960, 540, 1f), flow), styles)
            for (step in lyricsLadder) {
                val style = lyricsStyle(faces, step, cjk = false)
                val cap = style.fontSize.value * Fonts.CAP_HEIGHT
                val leading = style.lineHeight.value
                val gap = lineGap(style, step.rows, room.around())
                val most = cap + (step.rows - 1) * leading
                val name = "${if (flow) "Cover Flow" else "single cover"} at ${step.size} sp"
                assertThat(gap, name = "$name, between lines").isGreaterThan(1.5f * (leading - cap))
                assertThat(room.around() - cap / 2f - gap - most, name = "$name, to the status line and the song").isGreaterThan(1.5f * gap)
            }
        }
    }
}
