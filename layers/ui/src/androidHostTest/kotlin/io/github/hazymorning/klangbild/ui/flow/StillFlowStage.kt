package io.github.hazymorning.klangbild.ui.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.ui.stage.BaseWall
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StatusLayer
import io.github.hazymorning.klangbild.ui.stage.TextLayer
import io.github.hazymorning.klangbild.ui.stage.WallLayer
import io.github.hazymorning.klangbild.ui.stage.asColor

/**
 * A complete, static cover flow stage for stills: the wall, the cards at rest (the current one
 * in the middle at position 0) and the text column, standing in the middle with the cards
 * there are. The app's stage ([FlowTheater]) composes the same layers with its transitions.
 */
@Composable
fun FlowStage(model: StageModel, cards: List<CardMotion>, modifier: Modifier = Modifier) {
    val scene = model.scene
    val geometry = remember(scene.layout) { FlowGeometry(scene.layout) }
    val balance = remember(cards, geometry) { geometry.balance(left = cards.count { it.to < 0f }, right = cards.count { it.to > 0f }) }
    Box(modifier.fillMaxSize()) {
        BaseWall(scene.palette.wall.asColor())
        WallLayer(scene, shift = { balance.cards }, column = { balance.column })
        FlowStrip(cards, geometry, scene.appearance, scene.palette.wall.asColor(), edges = !scene.coverField, shift = { balance.cards })
        TextLayer(model, shift = { balance.column })
        StatusLayer(model, shift = { balance.column })
    }
}
