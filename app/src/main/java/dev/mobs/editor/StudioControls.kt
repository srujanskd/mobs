package dev.mobs.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

internal val StudioBackground = Color(0xFF0C1018)
internal val StudioPanel = Color(0xFF161D2A)
internal val StudioAccent = Color(0xFFA5B4FC)

internal enum class StudioIcon(val lines: List<List<Float>>) {
    FRAME(listOf(listOf(3f, 8f, 3f, 3f, 8f, 3f), listOf(16f, 3f, 21f, 3f, 21f, 8f), listOf(21f, 16f, 21f, 21f, 16f, 21f), listOf(8f, 21f, 3f, 21f, 3f, 16f))),
    REFRESH(listOf(listOf(20f, 9f, 17f, 4f, 10f, 3f, 4f, 7f, 3f, 13f, 6f, 19f, 13f, 21f, 19f, 18f), listOf(20f, 3f, 20f, 9f, 14f, 9f))),
    MIC(listOf(listOf(9f, 4f, 9f, 12f, 12f, 15f, 15f, 12f, 15f, 4f, 12f, 2f, 9f, 4f), listOf(5f, 10f, 5f, 13f, 8f, 18f, 16f, 18f, 19f, 13f, 19f, 10f), listOf(12f, 18f, 12f, 22f), listOf(8f, 22f, 16f, 22f))),
    FLIP(listOf(listOf(3f, 8f, 6f, 4f, 18f, 4f, 21f, 8f), listOf(17f, 8f, 21f, 8f, 21f, 3f), listOf(21f, 16f, 18f, 20f, 6f, 20f, 3f, 16f), listOf(7f, 16f, 3f, 16f, 3f, 21f))),
    CAMERA(listOf(listOf(3f, 6f, 15f, 6f, 15f, 18f, 3f, 18f, 3f, 6f), listOf(15f, 10f, 21f, 6f, 21f, 18f, 15f, 14f))),
    LAYERS(listOf(listOf(2f, 7f, 12f, 2f, 22f, 7f, 12f, 12f, 2f, 7f), listOf(2f, 12f, 12f, 17f, 22f, 12f), listOf(2f, 17f, 12f, 22f, 22f, 17f))),
    SETTINGS(listOf(listOf(3f, 6f, 21f, 6f), listOf(3f, 12f, 21f, 12f), listOf(3f, 18f, 21f, 18f), listOf(8f, 3f, 8f, 9f), listOf(16f, 9f, 16f, 15f), listOf(10f, 15f, 10f, 21f))),
    EYE(listOf(listOf(2f, 12f, 7f, 6f, 17f, 6f, 22f, 12f, 17f, 18f, 7f, 18f, 2f, 12f), listOf(10f, 10f, 14f, 10f, 14f, 14f, 10f, 14f, 10f, 10f))),
    MIRROR(listOf(listOf(12f, 2f, 12f, 22f), listOf(8f, 6f, 3f, 18f, 8f, 18f, 8f, 6f), listOf(16f, 6f, 21f, 18f, 16f, 18f, 16f, 6f))),
    PLUS(listOf(listOf(12f, 4f, 12f, 20f), listOf(4f, 12f, 20f, 12f))),
    CLOSE(listOf(listOf(5f, 5f, 19f, 19f), listOf(19f, 5f, 5f, 19f))),
    LINK(listOf(listOf(9f, 15f, 15f, 9f), listOf(8f, 11f, 5f, 11f, 2f, 14f, 2f, 18f, 6f, 22f, 10f, 22f, 13f, 19f, 13f, 16f), listOf(11f, 8f, 11f, 5f, 14f, 2f, 18f, 2f, 22f, 6f, 22f, 10f, 19f, 13f, 16f, 13f))),
    TEXT(listOf(listOf(4f, 6f, 4f, 3f, 20f, 3f, 20f, 6f), listOf(12f, 3f, 12f, 21f), listOf(8f, 21f, 16f, 21f))),
    IMAGE(listOf(listOf(3f, 3f, 21f, 3f, 21f, 21f, 3f, 21f, 3f, 3f), listOf(3f, 17f, 9f, 11f, 14f, 16f, 17f, 13f, 21f, 17f))),
    LOCK(listOf(listOf(7f, 10f, 7f, 5f, 10f, 2f, 14f, 2f, 17f, 5f, 17f, 10f), listOf(4f, 10f, 20f, 10f, 20f, 22f, 4f, 22f, 4f, 10f))),
    DELETE(listOf(listOf(3f, 5f, 21f, 5f), listOf(9f, 5f, 9f, 2f, 15f, 2f, 15f, 5f), listOf(5f, 5f, 6f, 22f, 18f, 22f, 19f, 5f), listOf(10f, 9f, 10f, 18f), listOf(14f, 9f, 14f, 18f))),
    UP(listOf(listOf(5f, 14f, 12f, 7f, 19f, 14f))),
    DOWN(listOf(listOf(5f, 10f, 12f, 17f, 19f, 10f))),
    COPY(listOf(listOf(8f, 8f, 21f, 8f, 21f, 21f, 8f, 21f, 8f, 8f), listOf(16f, 5f, 16f, 2f, 2f, 2f, 2f, 16f, 5f, 16f))),
    SHARE(listOf(listOf(12f, 16f, 12f, 2f), listOf(7f, 7f, 12f, 2f, 17f, 7f), listOf(5f, 12f, 3f, 12f, 3f, 22f, 21f, 22f, 21f, 12f, 19f, 12f))),
}

private val studioVectors = StudioIcon.entries.associateWith { icon ->
    ImageVector.Builder(icon.name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
            icon.lines.forEach { points -> moveTo(points[0], points[1]); points.drop(2).chunked(2).forEach { lineTo(it[0], it[1]) } }
        }
    }.build()
}

@Composable
internal fun StudioGlyph(icon: StudioIcon, description: String?, tint: Color = LocalContentColor.current, off: Boolean = false) {
    Box {
        Icon(studioVectors.getValue(icon), description, Modifier.size(22.dp), tint = tint)
        if (off) androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            drawLine(tint, androidx.compose.ui.geometry.Offset(1.dp.toPx(), 1.dp.toPx()), androidx.compose.ui.geometry.Offset(21.dp.toPx(), 21.dp.toPx()), 2.dp.toPx())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioAction(icon: StudioIcon, label: String, active: Boolean = false, enabled: Boolean = true, off: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).background(if (active) StudioAccent.copy(alpha = .2f) else Color.White.copy(alpha = .05f), RoundedCornerShape(14.dp))) {
            StudioGlyph(icon, label, if (active) StudioAccent else if (enabled) Color(0xFFD5DCEA) else Color.Gray, off)
        }
    }
}

internal fun lensLabel(ratio: Float): String = "${java.text.DecimalFormat("0.#").format(ratio)}×"

@Composable
internal fun CameraSourceControls(source: dev.mobs.scene.SceneSource, dual: Boolean, feedback: dev.mobs.scene.CameraZoomState? = null, live: Boolean = false, onChange: (dev.mobs.scene.SceneSource) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = androidx.compose.runtime.remember(source.kind, dual) { dev.mobs.render.sceneLensOptions(context, source.kind, dual) }
    Text(source.name, style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        StudioGlyph(StudioIcon.MIRROR, null, StudioAccent)
        Text("Mirror camera", Modifier.weight(1f).padding(start = 10.dp))
        Switch(source.mirrored, onCheckedChange = { onChange(source.copy(mirrored = it)) })
    }
    Text("Rotation correction", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 90, 180, 270).forEach { angle ->
            FilterChip(selected = source.rotation == angle, onClick = { onChange(source.copy(rotation = angle)) }, label = { Text(if (angle == 0) "Auto" else "$angle°") })
        }
    }
    Text("Use 180° if the camera is upside down. This changes preview and stream together.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF93A1B8))
    Text("Exposure and white balance adjust automatically. Color and detail depend on your phone and lighting.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF93A1B8))
    Text("Lens / zoom", style = MaterialTheme.typography.labelMedium)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { ratio ->
            FilterChip(selected = kotlin.math.abs((if (live) feedback?.actual else source.zoom)?.minus(ratio) ?: Float.MAX_VALUE) < .03f, onClick = { onChange(source.copy(zoom = ratio)) }, label = { Text(lensLabel(ratio)) })
        }
    }
    Text(if (dual) "Pinch the preview at 1× or above. Use lens buttons for ultrawide; switching it may briefly restart both cameras." else "Pinch the preview to zoom. Some zoom levels use digital crop. Mirror changes both preview and stream.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF93A1B8))
}
