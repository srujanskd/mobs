package dev.mobs.editor

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mobs.scene.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** A draft never changes the broadcast until Apply. The canvas uses output-normalized coordinates. */
@Composable
fun SceneEditor(
    collection: SceneCollection,
    dualCameraSupport: DualCameraSupport,
    onSave: (Scene, Boolean) -> Boolean,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(collection.active) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var browserDialog by remember { mutableStateOf(false) }
    var browserEditing by remember { mutableStateOf<SceneSource?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<Scene?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun edit(value: Scene) { draft = value; dirty = true }
    fun select(scene: Scene) {
        if (dirty) pendingSelection = scene
        else { draft = scene; selectedId = null; dirty = collection.scenes.none { it.id == scene.id } }
    }
    fun add(kind: SourceKind, media: String = "", url: String = "") {
        if (draft.sources.size >= Scene.MAX_SOURCES) { message = "A scene supports up to ${Scene.MAX_SOURCES} sources"; return }
        if (kind in Scene.CAMERA_KINDS && draft.sources.any { it.kind == kind }) { message = "This camera is already in the scene"; return }
        val otherCamera = draft.sources.any { it.visible && it.kind in Scene.CAMERA_KINDS && it.kind != kind }
        if (kind in Scene.CAMERA_KINDS && otherCamera && !dualCameraSupport.available) { message = dualCameraSupport.reason; return }
        val source = SceneSource(
            name = kind.label(), kind = kind, media = media, url = url,
            text = if (kind == SourceKind.TEXT) "Your text" else "",
            bounds = when {
                kind == SourceKind.BROWSER -> SourceBounds(0.66f, 0.12f, 0.32f, 0.76f)
                kind == SourceKind.TEXT -> SourceBounds(0.1f, 0.08f, 0.8f, 0.2f)
                draft.sources.isEmpty() -> SourceBounds()
                else -> SourceBounds(0.64f, 0.6f, 0.32f, 0.32f)
            },
        )
        edit(draft.copy(sources = draft.sources + source))
        selectedId = source.id
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try { add(SourceKind.IMAGE, importSceneImage(context, uri.toString())) }
            catch (_: Exception) { message = "Image could not be imported. Check storage and choose another image." }
        }
    }
    val selected = draft.sources.firstOrNull { it.id == selectedId }

    Dialog(onDismissRequest = { if (dirty) confirmClose = true else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = StudioBackground) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.padding(end = 12.dp)) {
                        Text("Scene studio", style = MaterialTheme.typography.titleMedium)
                        Text(if (dirty) "Unsaved draft" else "All changes saved", style = MaterialTheme.typography.labelSmall, color = StudioAccent)
                    }
                    Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        collection.scenes.forEach { scene ->
                            FilterChip(selected = draft.id == scene.id, onClick = { select(scene) }, label = { Text(scene.name) })
                        }
                        TextButton(onClick = {
                            if (collection.scenes.size >= SceneCollection.MAX_SCENES) message = "Scene limit reached"
                            else select(Scene(name = "Scene ${collection.scenes.size + 1}", sources = emptyList()))
                        }) { StudioGlyph(StudioIcon.PLUS, "New scene") }
                    }
                    OutlinedButton(onClick = { if (onSave(draft, false)) dirty = false }) { Text("Save") }
                    Button(onClick = { if (onSave(draft, true)) dirty = false }) { Text("Apply to output") }
                    StudioAction(StudioIcon.CLOSE, "Close editor", onClick = { if (dirty) confirmClose = true else onClose() })
                }
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("CANVAS", style = MaterialTheme.typography.labelSmall, color = StudioAccent)
                            Text("Drag to move · corner to resize", style = MaterialTheme.typography.labelSmall, color = Color(0xFF93A1B8))
                        }
                        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val canvasWidth = minOf(maxWidth, maxHeight * (16f / 9f))
                            Box(Modifier.width(canvasWidth).aspectRatio(16f / 9f)) {
                                SceneDraftCanvas(draft, selectedId, onSelect = { selectedId = it }, onChange = { edit(draft.update(it)) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = draft.name, onValueChange = { if (it.isNotBlank()) edit(draft.copy(name = it.take(80))) }, label = { Text("Scene name") }, singleLine = true, modifier = Modifier.weight(1f))
                            StudioAction(StudioIcon.COPY, "Duplicate scene", onClick = { select(draft.duplicate()) })
                            StudioAction(StudioIcon.DELETE, "Delete scene", enabled = draft.id != collection.activeId, onClick = { onDelete(draft.id); draft = collection.active; selectedId = null; dirty = false })
                        }
                        Text("1920 × 1080 · Changes reach your stream when you apply", style = MaterialTheme.typography.labelSmall, color = Color(0xFF93A1B8))
                    }
                    Column(modifier = Modifier.width(280.dp).clip(RoundedCornerShape(16.dp)).background(StudioPanel).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Sources", style = MaterialTheme.typography.titleSmall); Text("Top layer appears in front", style = MaterialTheme.typography.labelSmall, color = Color(0xFF93A1B8)) }
                            Box {
                                StudioAction(StudioIcon.PLUS, "Add source", onClick = { addMenu = true })
                                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                    SourceKind.entries.forEach { kind ->
                                        DropdownMenuItem(leadingIcon = { StudioGlyph(kind.icon(), null) }, text = { Text(kind.label()) }, onClick = {
                                            addMenu = false
                                            when (kind) {
                                                SourceKind.IMAGE -> imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                SourceKind.BROWSER -> { browserEditing = null; browserDialog = true }
                                                else -> add(kind)
                                            }
                                        })
                                    }
                                }
                            }
                        }
                        draft.sources.asReversed().forEach { source ->
                            Row(modifier = Modifier.fillMaxWidth().background(if (selectedId == source.id) Color(0xFF303C60) else Color(0xFF202A3C), RoundedCornerShape(12.dp)).clickable { selectedId = source.id }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                StudioGlyph(source.kind.icon(), null, StudioAccent)
                                Text(source.name, modifier = Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp)
                                StudioAction(StudioIcon.EYE, if (source.visible) "Hide ${source.name}" else "Show ${source.name}", off = !source.visible, onClick = { edit(draft.update(source.copy(visible = !source.visible))) })
                            }
                        }
                        if (draft.sources.isEmpty()) Text("Build your scene. Add a camera, chat link, image or text.", fontSize = 12.sp)
                        if (selected != null) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF303B50))
                            Text("SOURCE SETTINGS", style = MaterialTheme.typography.labelSmall, color = StudioAccent)
                            if (selected.kind in Scene.CAMERA_KINDS) CameraSourceControls(selected, draft.cameras.size == 2, onChange = { edit(draft.update(it)) })
                            if (selected.kind == SourceKind.BROWSER) {
                                Text("Live web overlay · 10 fps", style = MaterialTheme.typography.labelSmall)
                                OutlinedButton(onClick = { browserEditing = selected; browserDialog = true }) { StudioGlyph(StudioIcon.LINK, null); Spacer(Modifier.width(8.dp)); Text("Edit link") }
                            }
                            OutlinedTextField(value = selected.name, onValueChange = { if (it.isNotBlank()) edit(draft.update(selected.copy(name = it.take(80)))) }, label = { Text("Source name") }, singleLine = true)
                            if (selected.kind == SourceKind.TEXT) OutlinedTextField(value = selected.text, onValueChange = { edit(draft.update(selected.copy(text = it.take(500)))) }, label = { Text("Text") }, maxLines = 3)
                            Text("Opacity ${(selected.opacity * 100).roundToInt()}%", fontSize = 12.sp)
                            Slider(value = selected.opacity, onValueChange = { edit(draft.update(selected.copy(opacity = it))) })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StudioAction(StudioIcon.LOCK, "Lock position", active = selected.locked, onClick = { edit(draft.update(selected.copy(locked = !selected.locked))) })
                                Text("Lock position", fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                            if (selected.kind == SourceKind.COLOR || selected.kind == SourceKind.TEXT) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(0xFF17231F, 0xFF000000, 0xFF3047A0, 0xFF7B2938).forEach { color ->
                                        Box(Modifier.size(28.dp).background(Color(color)).border(1.dp, Color.White).clickable { edit(draft.update(selected.copy(color = color.toInt()))) })
                                    }
                                }
                            }
                            Row {
                                StudioAction(StudioIcon.UP, "Raise layer", onClick = {
                                    val index = draft.sources.indexOf(selected)
                                    if (index < draft.sources.lastIndex) edit(draft.copy(sources = draft.sources.toMutableList().apply { removeAt(index); add(index + 1, selected) }))
                                })
                                StudioAction(StudioIcon.DOWN, "Lower layer", onClick = {
                                    val index = draft.sources.indexOf(selected)
                                    if (index > 0) edit(draft.copy(sources = draft.sources.toMutableList().apply { removeAt(index); add(index - 1, selected) }))
                                })
                            }
                            StudioAction(StudioIcon.DELETE, "Remove source", onClick = { edit(draft.copy(sources = draft.sources.filterNot { it.id == selected.id })); selectedId = null })
                        }
                    }
                }
            }
        }
    }
    if (browserDialog) BrowserLinkDialog(browserEditing?.url.orEmpty(), onDismiss = { browserDialog = false }, onSave = { url ->
        val source = browserEditing
        if (source == null) add(SourceKind.BROWSER, url = url) else edit(draft.update(source.copy(url = url)))
        browserDialog = false
    })
    if (message != null) AlertDialog(onDismissRequest = { message = null }, text = { Text(message.orEmpty()) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    if (pendingSelection != null || confirmClose) AlertDialog(
        onDismissRequest = { pendingSelection = null; confirmClose = false },
        title = { Text("Discard unsaved changes?") },
        text = { Text("Save this draft before changing scenes to keep your edits.") },
        confirmButton = { TextButton(onClick = {
            if (confirmClose) onClose()
            else pendingSelection?.let { draft = it; selectedId = null; dirty = collection.scenes.none { scene -> scene.id == it.id } }
            pendingSelection = null; confirmClose = false
        }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { pendingSelection = null; confirmClose = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun SceneDraftCanvas(scene: Scene, selectedId: String?, onSelect: (String) -> Unit, onChange: (SceneSource) -> Unit) {
    val density = LocalDensity.current
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color.Black).border(1.dp, Color(0xFF3B4965))) {
        val stageWidth = with(density) { maxWidth.toPx() }
        val stageHeight = with(density) { maxHeight.toPx() }
        scene.sources.filter { it.visible }.forEach { source ->
            key(source.id) {
                val current by rememberUpdatedState(source)
                val size = source.bounds
                Box(modifier = Modifier.testTag("scene-source-${source.id}").offset { IntOffset((size.x * stageWidth).roundToInt(), (size.y * stageHeight).roundToInt()) }
                    .size(maxWidth * size.width, maxHeight * size.height)
                    .clip(RoundedCornerShape(1.dp))
                    .border(if (source.id == selectedId) 2.dp else 1.dp, if (source.id == selectedId) StudioAccent else Color(0xFF3B4965))
                    .clickable { onSelect(source.id) }
                    .pointerInput(source.id, source.locked, stageWidth, stageHeight) {
                        var dragBounds = current.bounds
                        detectDragGestures(onDragStart = { dragBounds = current.bounds; onSelect(source.id) }) { change, delta ->
                            change.consume()
                            if (!current.locked) {
                                dragBounds = dragBounds.move(delta.x / stageWidth, delta.y / stageHeight)
                                onChange(current.copy(bounds = dragBounds))
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                    when (source.kind) {
                        SourceKind.REAR_CAMERA, SourceKind.FRONT_CAMERA -> Box(Modifier.fillMaxSize().background(Color(0xFF202C46)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StudioGlyph(StudioIcon.CAMERA, null, StudioAccent)
                                Text(source.kind.label(), color = Color.White.copy(alpha = source.opacity), fontSize = 13.sp)
                                Text("${lensLabel(source.zoom)}${if (source.mirrored) " · Mirrored" else ""}${if (source.rotation != 0) " · ${source.rotation}°" else ""}", style = MaterialTheme.typography.labelSmall, color = StudioAccent)
                            }
                        }
                        SourceKind.BROWSER -> Box(Modifier.fillMaxSize().background(Color(0xCC242D46)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StudioGlyph(StudioIcon.LINK, null, StudioAccent)
                                Text("Chat / web overlay", fontSize = 12.sp, color = Color.White)
                                Text("Live after Apply", style = MaterialTheme.typography.labelSmall, color = StudioAccent)
                            }
                        }
                        SourceKind.TEXT -> Box(Modifier.fillMaxSize().background(Color(source.color).copy(alpha = source.opacity)), contentAlignment = Alignment.Center) { Text(source.text, color = Color.White.copy(alpha = source.opacity), fontSize = 16.sp, maxLines = 3) }
                        SourceKind.COLOR -> Box(Modifier.fillMaxSize().background(Color(source.color).copy(alpha = source.opacity)))
                        SourceKind.IMAGE -> {
                            val bitmap by produceState<ImageBitmap?>(null, source.media) {
                                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(context.filesDir, "scene-media/${source.media}").path)?.asImageBitmap() }
                            }
                            bitmap?.let { Image(it, source.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alpha = source.opacity) }
                        }
                    }
                    if (source.id == selectedId && !source.locked) Box(modifier = Modifier.align(Alignment.BottomEnd).size(26.dp).background(StudioAccent)
                        .pointerInput(source.id, stageWidth, stageHeight) {
                            var resizeBounds = current.bounds
                            detectDragGestures(onDragStart = { resizeBounds = current.bounds }) { change, delta ->
                                change.consume()
                                resizeBounds = resizeBounds.resize(delta.x / stageWidth, delta.y / stageHeight)
                                onChange(current.copy(bounds = resizeBounds))
                            }
                        }, contentAlignment = Alignment.Center) { Text("↘", color = Color.Black) }
                }
            }
        }
    }
}

private fun SourceKind.label() = when (this) {
    SourceKind.REAR_CAMERA -> "Rear camera"
    SourceKind.FRONT_CAMERA -> "Front camera"
    SourceKind.TEXT -> "Text"
    SourceKind.IMAGE -> "Image"
    SourceKind.COLOR -> "Color"
    SourceKind.BROWSER -> "Chat / web link"
}

private fun SourceKind.icon() = when (this) {
    SourceKind.REAR_CAMERA, SourceKind.FRONT_CAMERA -> StudioIcon.CAMERA
    SourceKind.TEXT -> StudioIcon.TEXT
    SourceKind.IMAGE, SourceKind.COLOR -> StudioIcon.IMAGE
    SourceKind.BROWSER -> StudioIcon.LINK
}

@Composable
private fun BrowserLinkDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by remember(current) { mutableStateOf(current) }
    val valid = isBrowserUrl(url.trim()) && url.trim().length <= 2048
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Chat / web link") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Paste a Streamlabs or StreamElements widget URL to add live chat, alerts or another web overlay to your scene.")
            OutlinedTextField(url, { url = it }, label = { Text("Widget URL") }, placeholder = { Text("https://…") },
                isError = url.isNotBlank() && !valid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false))
            Text("Use the widget link, not the dashboard address. Transparent pages keep the camera visible behind them. Links are saved on this device.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF93A1B8))
        }
    }, confirmButton = { TextButton(onClick = { onSave(url.trim()) }, enabled = valid) { Text(if (current.isEmpty()) "Add source" else "Update link") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
