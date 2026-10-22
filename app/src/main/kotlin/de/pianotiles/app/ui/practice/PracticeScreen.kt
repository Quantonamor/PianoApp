package de.pianotiles.app.ui.practice

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.pianotiles.app.PianoTilesApp
import de.pianotiles.app.i18n.tr
import de.pianotiles.app.ui.theme.PtColors
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Pitch
import de.pianotiles.core.practice.HandSelection
import de.pianotiles.core.practice.NoteState
import de.pianotiles.core.practice.PracticeMode
import kotlinx.coroutines.delay

@Composable
fun PracticeScreen(songId: String, onBack: () -> Unit, onEdit: (String) -> Unit, listen: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: PracticeViewModel = viewModel(key = "practice-$songId") { PracticeViewModel(app, songId, listen) }
    val settings by (app as PianoTilesApp).settings.settings.collectAsState()
    val song = vm.song
    val engine = vm.engine
    val range = remember(song) { KeyLayout.rangeFor(song.lowestPitch, song.highestPitch) }

    // keep the screen on while practising
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // frame loop
    LaunchedEffect(vm) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) vm.tick((now - last) / 1e9)
                last = now
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setMic(true) else vm.message = tr("Ohne Mikrofon-Erlaubnis kannst du die Tasten auf dem Bildschirm benutzen")
    }
    fun requestMic(on: Boolean) {
        if (!on) { vm.setMic(false); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.setMic(true)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Switch the microphone on automatically for "Warten" (the user can turn it off again).
    LaunchedEffect(vm.mode) { if (vm.mode == PracticeMode.WAIT && !vm.micActive) requestMic(true) }
    DisposableEffect(Unit) { onDispose { vm.setMic(false) } }

    var showSpeed by remember { mutableStateOf(false) }
    val sheetFile = remember(song.id, song.sheetMeasures) { (app as PianoTilesApp).songs.sheetPages(song.id).takeIf { it.isNotEmpty() && song.sheetMeasures.isNotEmpty() } }
    var showSheet by remember { mutableStateOf(false) }
    var showLoop by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                vm, onBack = onBack, onEdit = { vm.pause(); onEdit(songId) }, onMic = { requestMic(!vm.micActive) }, onSpeed = { showSpeed = true },
                sheetAvailable = sheetFile != null, sheetShown = showSheet, onSheet = { showSheet = !showSheet },
            )

            Row(Modifier.fillMaxWidth().weight(1f)) {
            if (showSheet && sheetFile != null) {
                SheetPanel(
                    pages = sheetFile, measures = song.sheetMeasures, currentMeasure = vm.engine.currentMeasure.also { vm.frame },
                    loopMeasures = if (vm.loop.enabled) vm.loopStartMeasure..vm.loopEndMeasure else null,
                    onSeekMeasure = { vm.seekBeat(song.measureStart(it)) },
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                )
            }
            BoxWithConstraints(Modifier.weight(0.58f).fillMaxHeight()) {
                val keyboardHeight = (maxHeight * 0.30f).coerceIn(70.dp, 170.dp)
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        FallingNotes(
                            engine = engine, range = range, frame = vm.frame,
                            lookaheadSeconds = settings.lookaheadSeconds, naming = settings.noteNaming,
                            showNames = settings.showNamesOnTiles, showMeasureNumbers = settings.showMeasureNumbers,
                            showFingers = settings.showFingers,
                            onScrub = vm::scrubBy, onTap = vm::togglePlay, onDoubleTap = vm::loopCurrentMeasure,
                        )
                        Overlays(vm)
                    }
                    val expected = HashMap<Int, Hand>()
                    val sounding = HashMap<Int, Hand>()
                    vm.frame.also { }
                    for (p in engine.expectedPitches) expected[p] = song.notes.firstOrNull { it.pitch == p && it.start >= engine.position - 0.6 }?.hand ?: Hand.RIGHT
                    for (i in engine.soundingNotes()) {
                        val n = song.notes[i]
                        if (engine.noteStates[i] == NoteState.AUTO || engine.noteStates[i] == NoteState.HIT || vm.mode != PracticeMode.WAIT) sounding[n.pitch] = n.hand
                    }
                    Box(Modifier.fillMaxWidth().height(keyboardHeight)) {
                        Keyboard(
                            range = range, expected = expected, sounding = sounding, heard = vm.heardPitches,
                            pressed = vm.tappedPitches, wrong = vm.wrongPitch, naming = settings.noteNaming,
                            showNames = settings.showNamesOnKeys, onKeyDown = vm::keyDown, onKeyUp = vm::keyUp,
                        )
                        if (settings.showHands) {
                            HandsOverlay(vm.handMotion, engine.position, vm.frame, range, showNumbers = settings.showFingers)
                        }
                    }
                }
            }
            }
            BottomBar(vm, onLoopOptions = { showLoop = true })
        }

        if (showSpeed) SpeedDialog(vm, onDismiss = { showSpeed = false })
        if (showLoop) LoopPanel(vm, onDismiss = { showLoop = false })
        vm.finished?.let { FinishedDialog(vm, it, onBack) }
    }
}

@Composable
private fun TopBar(
    vm: PracticeViewModel, onBack: () -> Unit, onEdit: () -> Unit, onMic: () -> Unit, onSpeed: () -> Unit,
    sheetAvailable: Boolean, sheetShown: Boolean, onSheet: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) }
            Column(Modifier.weight(1f)) {
                Text(vm.song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                vm.frame.also { }
                val m = vm.engine.currentMeasure + 1
                Text(
                    tr("Takt %s / %s · %s BPM", m, vm.song.measureCount, (vm.song.bpmAt(vm.engine.position) * vm.speed).toInt()),
                    style = MaterialTheme.typography.labelSmall, color = PtColors.TextDim,
                )
            }
            Segmented(
                listOf(
                    Triple(PracticeMode.WAIT, Icons.Filled.HourglassTop, tr("Warten")),
                    Triple(PracticeMode.FLOW, Icons.Filled.PlayArrow, tr("Fließend")),
                    Triple(PracticeMode.LISTEN, Icons.Filled.Hearing, tr("Anhören")),
                ),
                selected = vm.mode, onSelect = vm::changeMode,
            )
            Spacer(Modifier.width(8.dp))
            HandSelector(vm.hands, vm::changeHands)
            Spacer(Modifier.width(8.dp))
            Chip(text = "${(vm.speed * 100).toInt()} %", icon = Icons.Filled.Tune, active = vm.speed != 1f, onClick = onSpeed)
            ToggleIcon(Icons.Filled.Timer, tr("Metronom"), vm.metronome, vm::toggleMetronome)
            MicButton(vm.micActive, vm.micLevel, onMic)
            ToggleIcon(Icons.Filled.PanTool, tr("Hände"), vm.settings.showHands, vm::toggleHands)
            if (sheetAvailable) ToggleIcon(Icons.Filled.MenuBook, tr("Notenblatt"), sheetShown, onSheet)
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, tr("Noten bearbeiten")) }
        }
    }
}

@Composable
private fun <T> Segmented(items: List<Triple<T, ImageVector, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(PtColors.SurfaceHigh)) {
        for ((value, icon, label) in items) {
            val sel = value == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (sel) MaterialTheme.colorScheme.onPrimary else PtColors.TextDim)
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 12.sp, color = if (sel) MaterialTheme.colorScheme.onPrimary else PtColors.TextDim, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HandSelector(hands: HandSelection, onSelect: (HandSelection) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(PtColors.SurfaceHigh)) {
        for ((value, label, color) in listOf(
            Triple(HandSelection.LEFT, "L", PtColors.LeftHand),
            Triple(HandSelection.BOTH, "L+R", Color.White),
            Triple(HandSelection.RIGHT, "R", PtColors.RightHand),
        )) {
            val sel = value == hands
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) color.copy(alpha = 0.9f) else Color.Transparent)
                    .clickable { onSelect(value) }.padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (sel) Color(0xFF0E1220) else color) }
        }
    }
}

@Composable
fun Chip(text: String, icon: ImageVector?, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (active) PtColors.Loop.copy(alpha = 0.3f) else PtColors.SurfaceHigh)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc, tint = if (active) MaterialTheme.colorScheme.primary else PtColors.TextDim) }
}

@Composable
private fun MicButton(active: Boolean, level: Float, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.BottomCenter) {
        IconButton(onClick = onClick) {
            Icon(if (active) Icons.Filled.Mic else Icons.Filled.MicOff, tr("Mikrofon"), tint = if (active) PtColors.Hit else PtColors.TextDim)
        }
        if (active) LinearProgressIndicator(progress = { level }, modifier = Modifier.width(28.dp).height(3.dp).padding(bottom = 2.dp), color = PtColors.Hit)
    }
}

@Composable
private fun BottomBar(vm: PracticeViewModel, onLoopOptions: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::restart) { Icon(Icons.Filled.Replay, tr("Von vorn")) }
            IconButton(onClick = { vm.seekMeasureDelta(-1) }) { Icon(Icons.Filled.FastRewind, tr("Takt zurück")) }
            FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(48.dp), shape = CircleShape) {
                Icon(if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (vm.isPlaying) "Pause" else "Start", Modifier.size(30.dp))
            }
            IconButton(onClick = { vm.seekMeasureDelta(1) }) { Icon(Icons.Filled.FastForward, tr("Takt vor")) }
            Spacer(Modifier.width(6.dp))
            vm.frame.also { }
            Timeline(
                song = vm.song, position = vm.engine.position, loop = vm.loop,
                mistakes = vm.engine.mistakesPerMeasure + vm.song.stats.mistakesPerMeasure.filterKeys { it !in vm.engine.mistakesPerMeasure },
                onSeekMeasure = { vm.seekBeat(vm.song.measureStart(it)) },
                onSelectLoop = { a, b -> vm.setLoopMeasures(a, b) },
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 10.dp),
            )
            Spacer(Modifier.width(6.dp))
            val loopText = if (vm.loop.endBeat > vm.loop.startBeat) {
                if (vm.loopStartMeasure == vm.loopEndMeasure) tr("T. %s", vm.loopStartMeasure + 1) else tr("T. %s–%s", vm.loopStartMeasure + 1, vm.loopEndMeasure + 1)
            } else tr("Schleife")
            IconButton(onClick = vm::toggleLoop) {
                Icon(if (vm.loop.enabled) Icons.Filled.RepeatOne else Icons.Filled.Repeat, tr("Schleife an/aus"), tint = if (vm.loop.enabled) PtColors.Loop else PtColors.TextDim)
            }
            Chip(text = loopText, icon = null, active = vm.loop.enabled, onClick = onLoopOptions)
        }
    }
}

@Composable
private fun Overlays(vm: PracticeViewModel) {
    vm.frame.also { }
    Box(Modifier.fillMaxSize()) {
        // big play hint when paused
        if (!vm.isPlaying && vm.finished == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Color(0x553E4A7A)).clickable { vm.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, "Start", Modifier.size(44.dp)) }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Tippen = Start/Pause · Wischen = Spulen · Doppeltipp = Takt wiederholen"),
                    style = MaterialTheme.typography.labelSmall, color = PtColors.TextDim,
                )
            }
        }
        // waiting hint
        if (vm.engine.isWaiting) {
            val names = vm.engine.expectedPitches.sorted().joinToString("  ") { Pitch.fullName(it, vm.settings.noteNaming) }
            Text(
                tr("Spiele: %s", names),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xAA0E1220)).padding(horizontal = 12.dp, vertical = 4.dp),
                color = PtColors.Hit, fontWeight = FontWeight.Bold,
            )
        }
        // pass result toast
        val pass = vm.lastPass
        var showPass by remember { mutableStateOf(false) }
        LaunchedEffect(pass) { if (pass != null) { showPass = true; delay(2500); showPass = false } }
        AnimatedVisibility(showPass && pass != null, Modifier.align(Alignment.TopCenter).padding(top = 12.dp), enter = fadeIn(), exit = fadeOut()) {
            pass?.let {
                Toast(buildString {
                    append(tr("Durchgang %s: %s %%", it.pass, it.accuracy))
                    it.newSpeed?.let { s -> append(tr(" · schneller: %s %%", (s * 100).toInt())) }
                })
            }
        }
        // generic messages
        val msg = vm.message
        LaunchedEffect(msg) { if (msg != null) { delay(2500); vm.message = null } }
        AnimatedVisibility(msg != null, Modifier.align(Alignment.TopCenter).padding(top = 56.dp), enter = fadeIn(), exit = fadeOut()) {
            msg?.let { Toast(it) }
        }
        // accuracy badge while scoring
        if (vm.mode != PracticeMode.LISTEN && (vm.micActive || vm.tappedPitches.isNotEmpty() || vm.mode == PracticeMode.WAIT)) {
            Text(
                "${vm.engine.accuracy()} %",
                Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x88171C30)).padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun Toast(text: String) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(16.dp)).background(PtColors.SurfaceHigh).padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color.White, style = MaterialTheme.typography.bodyMedium,
    )
}
