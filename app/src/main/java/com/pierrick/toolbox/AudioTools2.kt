package com.pierrick.toolbox

import android.annotation.SuppressLint
import android.content.Intent
import android.media.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

private const val RATE = 44100

// ---------------- Dictaphone ----------------

@Composable
fun RecorderScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(ctx, MIC)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val dir = remember { File(ctx.filesDir, "recordings").apply { mkdirs() } }
    var files by remember { mutableStateOf(dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }
    fun refresh() { files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
    LaunchedEffect(recorder) {
        elapsed = 0
        while (recorder != null) { delay(100); elapsed++; level = ((recorder?.maxAmplitude ?: 0) / 32767f).coerceIn(0f, 1f) }
    }
    DisposableEffect(Unit) { onDispose { runCatching { recorder?.stop() }; recorder?.release(); player?.release() } }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%02d:%02d".format(elapsed / 600, elapsed / 10 % 60), fontSize = 48.sp)
                LinearProgressIndicator({ level }, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Color(0xFFE53935))
                Button(onClick = {
                    if (!granted) { req.launch(MIC); return@Button }
                    if (recorder == null) {
                        val f = File(dir, "Enregistrement " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".m4a")
                        recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                            setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(128000); setAudioSamplingRate(44100)
                            setOutputFile(f.absolutePath); prepare(); start()
                        }
                    } else { runCatching { recorder?.stop() }; recorder?.release(); recorder = null; refresh() }
                }, Modifier.size(96.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) {
                    Text(if (recorder == null) "●" else "■", fontSize = 36.sp, color = Color.White)
                }
                Text(if (recorder == null) "Appuie pour enregistrer" else "Enregistrement…", color = MaterialTheme.colorScheme.outline)
            }
        }
        items(files, key = { it.name }) { f ->
            ListItem(
                headlineContent = { Text(f.nameWithoutExtension) },
                supportingContent = { Text("${f.length() / 1024} Ko") },
                trailingContent = {
                    Row {
                        TextButton(onClick = {
                            player?.release(); player = null
                            if (playing == f.name) { playing = null } else {
                                player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start(); setOnCompletionListener { playing = null } }; playing = f.name
                            }
                        }) { Text(if (playing == f.name) "⏹" else "▶") }
                        TextButton(onClick = {
                            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        }) { Text("↗") }
                        TextButton(onClick = { f.delete(); refresh() }) { Text("✕") }
                    }
                })
        }
    }
}

// ---------------- Dictée ----------------

@Composable
fun DictationScreen() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(prefs(ctx).getString("dictation", "")!!) }
    var lang by remember { mutableStateOf("fr-FR") }
    fun save(t: String) { text = t; prefs(ctx).edit().putString("dictation", t).apply() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { s ->
            save(if (text.isBlank()) s.replaceFirstChar { it.uppercase() } else "$text ${s}")
        }
    }
    ToolColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("fr-FR" to "🇫🇷", "en-US" to "🇬🇧", "es-ES" to "🇪🇸", "de-DE" to "🇩🇪", "it-IT" to "🇮🇹").forEach { (c, f) -> FilterChip(lang == c, { lang = c }, label = { Text(f) }) }
        }
        Button(onClick = {
            runCatching {
                launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_PROMPT, "Parle maintenant…"))
            }
        }, Modifier.fillMaxWidth().height(64.dp)) { Text("🎤 Dicter", fontSize = 20.sp) }
        OutlinedTextField(text, { save(it) }, Modifier.fillMaxWidth(), minLines = 8, label = { Text("Texte") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copy(ctx, text) }) { Text("Copier") }
            OutlinedButton(onClick = { shareText(ctx, text) }) { Text("Partager") }
            TextButton(onClick = { save("") }) { Text("Effacer") }
        }
        Text("${text.split(Regex("\\s+")).count { it.isNotBlank() }} mots", color = MaterialTheme.colorScheme.outline)
    }
}

// ---------------- Lecture à voix haute ----------------

@Composable
fun TtsScreen() {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        tts[0] = TextToSpeech(ctx) { ready = it == TextToSpeech.SUCCESS }
        onDispose { tts[0]?.stop(); tts[0]?.shutdown() }
    }
    var text by remember { mutableStateOf("Bonjour ! Je suis ta boîte à outils.") }
    var rate by remember { mutableFloatStateOf(1f) }; var pitch by remember { mutableFloatStateOf(1f) }
    var lang by remember { mutableStateOf(Locale.FRANCE) }
    ToolColumn {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 6, label = { Text("Texte à lire") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Locale.FRANCE to "🇫🇷", Locale.UK to "🇬🇧", Locale("es", "ES") to "🇪🇸", Locale.GERMANY to "🇩🇪", Locale.ITALY to "🇮🇹").forEach { (l, f) -> FilterChip(lang == l, { lang = l }, label = { Text(f) }) }
        }
        Text("Vitesse ×%.1f".format(rate)); Slider(rate, { rate = it }, valueRange = 0.5f..2f)
        Text("Tonalité ×%.1f".format(pitch)); Slider(pitch, { pitch = it }, valueRange = 0.5f..2f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { tts[0]?.run { language = lang; setSpeechRate(rate); setPitch(pitch); speak(text, TextToSpeech.QUEUE_FLUSH, null, "t") } }, enabled = ready) { Text("🔊 Lire") }
            OutlinedButton(onClick = { tts[0]?.stop() }) { Text("⏹ Stop") }
        }
        if (!ready) Text("Moteur de synthèse vocale en cours de chargement…", color = MaterialTheme.colorScheme.outline)
    }
}

// ---------------- Générateur de fréquence ----------------

@Composable
fun FreqGenScreen() {
    var freq by remember { mutableFloatStateOf(440f) }
    var wave by remember { mutableIntStateOf(0) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    var playing by remember { mutableStateOf(false) }
    val f by rememberUpdatedState(freq); val w by rememberUpdatedState(wave); val v by rememberUpdatedState(vol)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val track = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2).build()
            track.play(); var phase = 0.0; val buf = ShortArray(1024)
            try {
                while (isActive) {
                    for (i in buf.indices) {
                        val s = when (w) { 0 -> sin(2 * PI * phase); 1 -> if (phase < 0.5) 1.0 else -1.0; 2 -> 2 * phase - 1; else -> 1 - 4 * abs(phase - 0.5) }
                        buf[i] = (s * v * 0.8 * Short.MAX_VALUE).toInt().toShort()
                        phase += f / RATE; if (phase >= 1) phase -= 1
                    }
                    track.write(buf, 0, buf.size)
                }
            } finally { track.stop(); track.release() }
        }
    }
    ToolColumn {
        Text("${freq.roundToInt()} Hz", fontSize = 56.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        // échelle logarithmique 20 Hz – 20 kHz
        Slider(ln(freq / 20f) / ln(1000f), { freq = 20f * 1000f.pow(it) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-10, -1).forEach { d -> FilledTonalButton(onClick = { freq = (freq + d).coerceIn(20f, 20000f) }) { Text("$d") } }
            listOf(1, 10).forEach { d -> FilledTonalButton(onClick = { freq = (freq + d).coerceIn(20f, 20000f) }) { Text("+$d") } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(50, 100, 440, 1000, 10000, 15000).forEach { h -> AssistChip(onClick = { freq = h.toFloat() }, label = { Text(if (h >= 1000) "${h / 1000}k" else "$h") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Sinus", "Carré", "Scie", "Triangle").forEachIndexed { i, l -> FilterChip(wave == i, { wave = i }, label = { Text(l) }) } }
        Text("Volume"); Slider(vol, { vol = it })
        Button(onClick = { playing = !playing }, Modifier.fillMaxWidth().height(56.dp)) { Text(if (playing) "⏹ Stop" else "▶ Jouer", fontSize = 18.sp) }
        Text("⚠️ Baisse le volume avant de commencer, surtout avec des écouteurs.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Test HP & éjecter l'eau ----------------

@Composable
fun SpeakerTestScreen() {
    var job by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(job) {
        val j = job ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val track = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2).build()
            track.play()
            val totalSec = if (j == "eau") 30.0 else 2.0
            val buf = ShortArray(2048); var n = 0L
            try {
                while (isActive && n < RATE * totalSec) {
                    for (i in 0 until buf.size / 2) {
                        val t = (n + i).toDouble() / RATE
                        val s = when (j) {
                            "eau" -> sin(2 * PI * 165 * t) // 165 Hz : fréquence classique pour expulser l'eau
                            "sweep" -> sin(2 * PI * (20 * (1000.0.pow(t / 2) - 1) / (ln(1000.0) / 2)))
                            else -> sin(2 * PI * 440 * t) * (0.5 + 0.5 * sin(2 * PI * 4 * t))
                        }
                        val sv = (s * 0.9 * Short.MAX_VALUE).toInt().toShort()
                        buf[2 * i] = if (j == "droite") 0 else sv; buf[2 * i + 1] = if (j == "gauche") 0 else sv
                    }
                    track.write(buf, 0, buf.size); n += buf.size / 2
                    progress = (n / (RATE * totalSec)).toFloat()
                }
            } finally { track.stop(); track.release(); job = null; progress = 0f }
        }
    }
    ToolColumn {
        Text("Test des haut-parleurs", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { job = "gauche" }, Modifier.weight(1f), enabled = job == null) { Text("◀ Gauche") }
            Button(onClick = { job = "stereo" }, Modifier.weight(1f), enabled = job == null) { Text("Les deux") }
            Button(onClick = { job = "droite" }, Modifier.weight(1f), enabled = job == null) { Text("Droite ▶") }
        }
        OutlinedButton(onClick = { job = "sweep" }, Modifier.fillMaxWidth(), enabled = job == null) { Text("〰️ Balayage 20 Hz → 20 kHz") }
        Text("Avec des écouteurs, vérifie que le son sort du bon côté.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("💧 Éjecter l'eau du haut-parleur", style = MaterialTheme.typography.titleMedium)
        Text("Mets le volume au MAXIMUM, haut-parleur vers le bas. Un son grave (165 Hz) fait vibrer la membrane pendant 30 s pour chasser l'eau.")
        Button(onClick = { job = "eau" }, Modifier.fillMaxWidth().height(56.dp), enabled = job == null, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))) { Text("💧 Lancer l'éjection", fontSize = 18.sp) }
        if (job != null) { LinearProgressIndicator({ progress }, Modifier.fillMaxWidth()); OutlinedButton(onClick = { job = null }) { Text("⏹ Stop") } }
    }
}

// ---------------- Spectre audio (FFT) ----------------

private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size; var j = 0
    for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
        if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
    var len = 2
    while (len <= n) {
        val ang = -2 * PI / len; val wr = cos(ang); val wi = sin(ang)
        var i = 0
        while (i < n) {
            var cr = 1.0; var ci = 0.0
            for (k in 0 until len / 2) {
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci; val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                re[i + k] = ur + vr; im[i + k] = ui + vi; re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
            }
            i += len
        }
        len = len shl 1
    }
}

@SuppressLint("MissingPermission")
@Composable
fun SpectrumScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(ctx, MIC)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(MIC) }
    var bars by remember { mutableStateOf(FloatArray(64)) }
    var peak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val n = 4096
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(n * 2, AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            val buf = ShortArray(n)
            try {
                rec.startRecording()
                while (isActive) {
                    val r = rec.read(buf, 0, n); if (r < n) continue
                    val re = DoubleArray(n) { buf[it] / 32768.0 * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }; val im = DoubleArray(n)
                    fft(re, im)
                    val mag = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
                    // 64 bandes logarithmiques de 40 Hz à 16 kHz
                    val out = FloatArray(64) { b ->
                        val f1 = 40 * 400.0.pow(b / 64.0); val f2 = 40 * 400.0.pow((b + 1) / 64.0)
                        val i1 = (f1 * n / RATE).toInt().coerceIn(1, n / 2 - 1); val i2 = (f2 * n / RATE).toInt().coerceIn(i1 + 1, n / 2)
                        val m = (i1 until i2).maxOf { mag[it] }
                        ((20 * log10(m + 1e-9) + 40) / 70).toFloat().coerceIn(0f, 1f)
                    }
                    bars = FloatArray(64) { maxOf(out[it], bars[it] * 0.85f) }
                    val pi = (1 until n / 2).maxBy { mag[it] }
                    if (mag[pi] > 2) peak = (pi.toFloat() * RATE / n)
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }
    }
    val c1 = MaterialTheme.colorScheme.primary; val c2 = MaterialTheme.colorScheme.tertiary
    ToolColumn {
        if (!granted) { Button(onClick = { req.launch(MIC) }) { Text("Autoriser le micro") }; return@ToolColumn }
        Text("Fréquence dominante : ${peak.roundToInt()} Hz", style = MaterialTheme.typography.titleLarge)
        Canvas(Modifier.fillMaxWidth().height(300.dp)) {
            val w = size.width / bars.size
            bars.forEachIndexed { i, v ->
                drawRect(androidx.compose.ui.graphics.lerp(c1, c2, i / 64f), Offset(i * w + 1, size.height * (1 - v)), androidx.compose.ui.geometry.Size(w - 2, size.height * v))
            }
        }
        Row { Text("40 Hz", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text("1 kHz", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text("16 kHz", style = MaterialTheme.typography.bodySmall) }
    }
}

// ---------------- Faux appel ----------------

@Composable
fun FakeCallScreen() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(prefs(ctx).getString("fake_name", "Maman")!!) }
    var delaySec by remember { mutableIntStateOf(10) }
    var countdown by remember { mutableIntStateOf(-1) }
    var ringing by remember { mutableStateOf(false) }
    var inCall by remember { mutableIntStateOf(-1) }
    LaunchedEffect(countdown) { if (countdown > 0) { delay(1000); countdown-- } else if (countdown == 0) { countdown = -1; ringing = true } }
    LaunchedEffect(ringing) {
        if (!ringing) return@LaunchedEffect
        val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        r?.play()
        try { while (ringing) { vibrate(ctx, 600); delay(1500) } } finally { r?.stop() }
    }
    LaunchedEffect(inCall) { if (inCall >= 0) { delay(1000); inCall++ } }
    if (ringing || inCall >= 0) {
        androidx.compose.ui.window.Dialog({}, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
            Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(60.dp))
                Box(Modifier.size(110.dp).clip(CircleShape).background(Color(0xFF455A64)), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), fontSize = 48.sp, color = Color.White) }
                Spacer(Modifier.height(20.dp))
                Text(name, fontSize = 34.sp, color = Color.White)
                Text(if (ringing) "Appel entrant…" else "%02d:%02d".format(inCall / 60, inCall % 60), color = Color.LightGray, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { ringing = false; inCall = -1 }, Modifier.size(76.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) { Text("📞", fontSize = 28.sp) }
                    if (ringing) Button(onClick = { ringing = false; inCall = 0 }, Modifier.size(76.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))) { Text("📞", fontSize = 28.sp) }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    ToolColumn {
        OutlinedTextField(name, { name = it; prefs(ctx).edit().putString("fake_name", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Nom de l'appelant") }, singleLine = true)
        Text("Sonner dans :")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5, 10, 30, 60, 300).forEach { s -> FilterChip(delaySec == s, { delaySec = s }, label = { Text(if (s >= 60) "${s / 60} min" else "$s s") }) } }
        Button(onClick = { countdown = delaySec }, Modifier.fillMaxWidth().height(56.dp), enabled = countdown < 0) {
            Text(if (countdown >= 0) "📞 Appel dans $countdown s…" else "📞 Programmer le faux appel", fontSize = 17.sp)
        }
        if (countdown >= 0) TextButton(onClick = { countdown = -1 }) { Text("Annuler") }
        Text("Garde l'appli ouverte (écran éteint possible si tu ne la quittes pas). Pratique pour s'éclipser poliment 😉", style = MaterialTheme.typography.bodySmall)
    }
}
