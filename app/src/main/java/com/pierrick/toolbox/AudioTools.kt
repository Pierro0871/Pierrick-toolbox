package com.pierrick.toolbox

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SR = 44100

private fun tone(freq: Double, ms: Int, vol: Double = 0.6): ShortArray {
    val n = SR * ms / 1000
    return ShortArray(n) { i ->
        val env = exp(-i.toDouble() / (n / 4.0))
        (sin(2 * PI * freq * i / SR) * env * vol * Short.MAX_VALUE).toInt().toShort()
    }
}

private fun newTrack(): AudioTrack = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
    .setTransferMode(AudioTrack.MODE_STREAM)
    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
    .build()

// ---------------- Métronome ----------------

@Composable
fun MetronomeScreen() {
    var bpm by remember { mutableFloatStateOf(100f) }
    var beats by remember { mutableIntStateOf(4) }
    var running by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(-1) }
    var taps by remember { mutableStateOf(listOf<Long>()) }

    LaunchedEffect(running) {
        if (!running) { current = -1; return@LaunchedEffect }
        withContext(Dispatchers.Default) {
            val track = newTrack(); track.play()
            val hi = tone(1500.0, 40); val lo = tone(900.0, 40)
            var i = 0
            try {
                while (isActive) {
                    val samplesPerBeat = (SR * 60.0 / bpm).toInt()
                    val click = if (i % beats == 0) hi else lo
                    val buf = ShortArray(samplesPerBeat); click.copyInto(buf, 0, 0, minOf(click.size, samplesPerBeat))
                    current = i % beats
                    track.write(buf, 0, buf.size) // bloquant → cadence naturelle
                    i++
                }
            } finally { track.stop(); track.release() }
        }
    }
    ToolColumn {
        Text("${bpm.roundToInt()} BPM", fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(when (bpm.roundToInt()) { in 0..59 -> "Largo"; in 60..75 -> "Adagio"; in 76..107 -> "Andante"; in 108..119 -> "Moderato"; in 120..167 -> "Allegro"; else -> "Presto" },
            Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.outline)
        Slider(bpm, { bpm = it.roundToInt().toFloat() }, valueRange = 30f..240f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
            listOf(-5, -1).forEach { d -> FilledTonalButton(onClick = { bpm = (bpm + d).coerceIn(30f, 240f) }) { Text("$d") } }
            listOf(1, 5).forEach { d -> FilledTonalButton(onClick = { bpm = (bpm + d).coerceIn(30f, 240f) }) { Text("+$d") } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(beats) { b ->
                Box(Modifier.padding(6.dp).size(if (b == 0) 34.dp else 26.dp).clip(CircleShape)
                    .background(if (b == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Temps par mesure : ", Modifier.weight(1f))
            listOf(2, 3, 4, 6).forEach { n -> FilterChip(beats == n, { beats = n }, label = { Text("$n") }, modifier = Modifier.padding(horizontal = 2.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { running = !running }, Modifier.weight(1f).height(56.dp)) { Text(if (running) "⏹ Stop" else "▶ Démarrer", fontSize = 18.sp) }
            OutlinedButton(onClick = {
                val now = System.currentTimeMillis()
                taps = (taps.filter { now - it < 3000 } + now).takeLast(6)
                if (taps.size >= 2) bpm = (60000.0 / ((taps.last() - taps.first()) / (taps.size - 1).toDouble())).toFloat().coerceIn(30f, 240f).roundToInt().toFloat()
            }, Modifier.weight(1f).height(56.dp)) { Text("👆 Tap tempo") }
        }
    }
}

// ---------------- Accordeur ----------------

private val noteNames = listOf("Do", "Do#", "Ré", "Ré#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")

/** Détection de hauteur par autocorrélation normalisée. */
private fun detectPitch(b: ShortArray, n: Int): Double? {
    var rms = 0.0; for (i in 0 until n) rms += b[i] * b[i].toDouble(); rms = kotlin.math.sqrt(rms / n)
    if (rms < 300) return null
    val minLag = SR / 1200; val maxLag = SR / 60
    var best = -1; var bestVal = 0.0
    val corr = DoubleArray(maxLag + 2)
    for (lag in minLag..maxLag) {
        var s = 0.0; var e1 = 0.0; var e2 = 0.0
        for (i in 0 until n - lag) { s += b[i] * b[i + lag].toDouble(); e1 += b[i] * b[i].toDouble(); e2 += b[i + lag] * b[i + lag].toDouble() }
        corr[lag] = s / kotlin.math.sqrt(e1 * e2 + 1e-9)
    }
    // premier pic au-dessus de 90 % du max (évite les erreurs d'octave)
    val mx = (minLag..maxLag).maxOf { corr[it] }
    if (mx < 0.5) return null
    for (lag in minLag + 1 until maxLag) if (corr[lag] > 0.9 * mx && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) { best = lag; bestVal = corr[lag]; break }
    if (best < 0) return null
    val y0 = corr[best - 1]; val y2 = corr[best + 1]
    val shift = (y0 - y2) / (2 * (y0 - 2 * bestVal + y2)).let { if (it.isNaN()) 0.0 else it }
    return SR / (best + shift)
}

@SuppressLint("MissingPermission")
@Composable
fun TunerScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(ctx, MIC)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(MIC) }
    var freq by remember { mutableStateOf<Double?>(null) }
    var a4 by remember { mutableFloatStateOf(440f) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val size = 4096
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(size * 2, AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            val buf = ShortArray(size)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, size)
                    if (n > 0) detectPitch(buf, n)?.let { f -> freq = freq?.let { if (abs(it - f) / f < 0.03) it * 0.6 + f * 0.4 else f } ?: f }
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }
    }
    ToolColumn {
        if (!granted) { Text("L'accordeur a besoin du micro."); Button(onClick = { req.launch(MIC) }) { Text("Autoriser") }; return@ToolColumn }
        val f = freq
        if (f == null) Text("Joue une note…", fontSize = 28.sp) else {
            val semis = 12 * ln(f / a4) / ln(2.0)
            val nearest = semis.roundToInt()
            val cents = ((semis - nearest) * 100).roundToInt()
            val idx = ((nearest + 9) % 12 + 12) % 12
            val octave = 4 + Math.floorDiv(nearest + 9, 12)
            val ok = abs(cents) <= 5
            val col = if (ok) Color(0xFF43A047) else if (abs(cents) < 20) Color(0xFFFB8C00) else Color(0xFFE53935)
            Text("${noteNames[idx]}$octave", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = col, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("%.1f Hz • %+d cents".format(f, cents), Modifier.align(Alignment.CenterHorizontally))
            BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                Box(Modifier.align(Alignment.Center).width(4.dp).fillMaxHeight().background(Color(0xFF43A047)))
                val x = (maxWidth / 2 - 10.dp) * (cents.coerceIn(-50, 50) / 50f)
                Box(Modifier.align(Alignment.Center).offset(x = x).size(20.dp).clip(CircleShape).background(col))
            }
            Text(if (ok) "✅ Juste !" else if (cents < 0) "⬆️ Trop bas : resserre" else "⬇️ Trop haut : desserre", Modifier.align(Alignment.CenterHorizontally))
        }
        HorizontalDivider()
        Text("Guitare (accordage standard) : Mi2 La2 Ré3 Sol3 Si3 Mi4", style = MaterialTheme.typography.bodySmall)
        Text("Ukulélé : Sol4 Do4 Mi4 La4 • Basse : Mi1 La1 Ré2 Sol2", style = MaterialTheme.typography.bodySmall)
        Text("Diapason La4 = ${a4.roundToInt()} Hz")
        Slider(a4, { a4 = it.roundToInt().toFloat() }, valueRange = 430f..450f)
    }
}

// ---------------- Morse & tableau ----------------

private val morse = mapOf(
    'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..",
    'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
    'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
    '8' to "---..", '9' to "----.", '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '\'' to ".----.",
    '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-", ':' to "---...", '=' to "-...-", '+' to ".-.-.", '-' to "-....-", '@' to ".--.-."
)
private val reverseMorse = morse.entries.associate { (k, v) -> v to k }

private fun stripAccents(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
private fun toMorse(s: String) = stripAccents(s).uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    .joinToString(" / ") { w -> w.mapNotNull { morse[it] }.joinToString(" ") }
private fun fromMorse(s: String) = s.trim().split(Regex("\\s*/\\s*|\\s{3,}")).joinToString(" ") { w ->
    w.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { reverseMorse[it.replace('_', '-').replace('·', '.')] ?: '?' }.joinToString("")
}

@Composable
fun MorseScreen() = Tabs(listOf("Morse", "Tableau défilant")) { t -> if (t == 0) MorseView() else BoardView() }

@Composable
private fun MorseView() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("SOS") }
    var code by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<String?>(null) } // "son" / "flash"
    var wpm by remember { mutableFloatStateOf(12f) }
    val encoded = toMorse(text)

    LaunchedEffect(playing) {
        val mode = playing ?: return@LaunchedEffect
        val unit = (1200 / wpm).toLong()
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cam = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        val track = if (mode == "son") newTrack().also { it.play() } else null
        val beep = { ms: Long -> ShortArray((SR * ms / 1000).toInt()) { i -> (sin(2 * PI * 700 * i / SR) * 0.5 * Short.MAX_VALUE).toInt().toShort() } }
        try {
            withContext(Dispatchers.Default) {
                for (ch in encoded) {
                    when (ch) {
                        '.', '-' -> {
                            val d = if (ch == '.') unit else unit * 3
                            if (track != null) track.write(beep(d), 0, (SR * d / 1000).toInt()) else { cam?.let { runCatching { cm.setTorchMode(it, true) } }; delay(d); cam?.let { runCatching { cm.setTorchMode(it, false) } } }
                            if (track != null) track.write(ShortArray((SR * unit / 1000).toInt()), 0, (SR * unit / 1000).toInt()) else delay(unit)
                        }
                        ' ' -> if (track != null) track.write(ShortArray((SR * unit * 2 / 1000).toInt()), 0, (SR * unit * 2 / 1000).toInt()) else delay(unit * 2)
                        '/' -> if (track != null) track.write(ShortArray((SR * unit * 2 / 1000).toInt()), 0, (SR * unit * 2 / 1000).toInt()) else delay(unit * 2)
                    }
                }
            }
        } finally {
            track?.let { delay(200); it.stop(); it.release() }
            cam?.let { runCatching { cm.setTorchMode(it, false) } }
            playing = null
        }
    }
    ToolColumn {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Texte → Morse") })
        Card(Modifier.fillMaxWidth()) { Text(encoded.ifEmpty { "…" }, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 20.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { playing = if (playing == null) "son" else null }, enabled = encoded.isNotEmpty()) { Text(if (playing == "son") "⏹ Stop" else "🔊 Son") }
            Button(onClick = { playing = if (playing == null) "flash" else null }, enabled = encoded.isNotEmpty()) { Text(if (playing == "flash") "⏹ Stop" else "🔦 Flash") }
            OutlinedButton(onClick = { copy(ctx, encoded) }) { Text("Copier") }
        }
        Text("Vitesse : ${wpm.roundToInt()} mots/min"); Slider(wpm, { wpm = it }, valueRange = 5f..30f)
        HorizontalDivider()
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Morse → Texte (. - espaces, / entre mots)") }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
        if (code.isNotBlank()) Text(fromMorse(code), fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardView() {
    var text by remember { mutableStateOf("Bonjour ! 👋") }
    var size by remember { mutableFloatStateOf(120f) }
    var speed by remember { mutableFloatStateOf(150f) }
    val palettes = listOf(Color.White to Color.Black, Color.Yellow to Color.Black, Color.Black to Color.White, Color(0xFF00E676) to Color.Black, Color.Red to Color.White, Color.Cyan to Color(0xFF1A237E))
    var pal by remember { mutableIntStateOf(1) }
    var full by remember { mutableStateOf(false) }
    ToolColumn {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Message") })
        Text("Taille : ${size.roundToInt()}"); Slider(size, { size = it }, valueRange = 40f..300f)
        Text("Vitesse"); Slider(speed, { speed = it }, valueRange = 30f..400f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            palettes.forEachIndexed { i, (fg, bg) ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(bg).clickable { pal = i }, contentAlignment = Alignment.Center) {
                    Text(if (i == pal) "✓" else "A", color = fg, fontWeight = FontWeight.Bold)
                }
            }
        }
        Button(onClick = { full = true }, Modifier.fillMaxWidth()) { Text("📺 Afficher en plein écran") }
        Text("Astuce : tourne le téléphone à l'horizontale. Touche l'écran pour quitter.", style = MaterialTheme.typography.bodySmall)
    }
    if (full) Dialog({ full = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        val (fg, bg) = palettes[pal]
        Box(Modifier.fillMaxSize().background(bg).clickable { full = false }, contentAlignment = Alignment.Center) {
            Text(text, color = fg, fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 0, velocity = speed.dp))
        }
    }
}
