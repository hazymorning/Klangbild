package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A complete, static stage for one model, for stills. The app's stage ([StageTheater])
 * composes the same layers with its transitions and pause dimming.
 */
@Composable
fun Stage(model: StageModel, modifier: Modifier = Modifier) {
    val queue = rememberQueuePlan(model)
    Box(modifier.fillMaxSize()) {
        BaseWall(model.scene.palette.wall.asColor())
        WallLayer(model.scene)
        ArtworkLayer(model.scene)
        TextLayer(model, queuePlan = queue)
        QueueRowsLayer(queue, null, RowsMotion.Still)
        StatusLayer(model)
    }
}
