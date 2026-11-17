package com.pierrick.toolbox

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sqrt

// ---------------- Mot de passe ----------------

@Composable
fun PasswordScreen() {
    val ctx = LocalContext.current
    var length by remember { mutableFloatStateOf(16f) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var noAmbiguous by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    var pool = buildString {
        if (upper) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        if (lower) append("abcdefghijklmnopqrstuvwxyz")
        if (digits) append("0123456789")
        if (symbols) append("!@#$%&*-_=+?.:;")
    }
    if (noAmbiguous) pool = pool.filter { it !in "O0Il1|" }
    val pwd = remember(pool, length, seed) {
        if (pool.isEmpty()) "" else {
            val r = SecureRandom(); String(CharArray(length.toInt()) { pool[r.nextInt(pool.length)] })
        }
    }
    val entropy = if (pool.isEmpty()) 0.0 else length * log2(pool.length.toDouble())
    val (label, color) = when {
        entropy < 40 -> "Faible" to Color(0xFFE53935)
        entropy < 60 -> "Moyen" to Color(0xFFFB8C00)
        entropy < 80 -> "Fort" to Color(0xFF7CB342)
        else -> "Très fort" to Color(0xFF2E7D32)
    }
    ToolColumn {
        Card(Modifier.fillMaxWidth()) {
            Text(pwd.ifEmpty { "Choisis au moins un type de caractères" }, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 22.sp)
        }
        LinearProgressIndicator({ (entropy / 128).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = color)
        Text("$label • ${entropy.toInt()} bits d'entropie", color = color)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { seed++; copied = false }) { Text("🔄 Générer") }
            OutlinedButton(onClick = { copy(ctx, pwd); copied = true }) { Text(if (copied) "✔ Copié" else "Copier") }
        }
        Text("Longueur : ${length.toInt()}")
        Slider(length, { length = it }, valueRange = 4f..64f)
        listOf(
            Triple("Majuscules (A-Z)", upper) { v: Boolean -> upper = v },
            Triple("Minuscules (a-z)", lower) { v: Boolean -> lower = v },
            Triple("Chiffres (0-9)", digits) { v: Boolean -> digits = v },
            Triple("Symboles (!@#…)", symbols) { v: Boolean -> symbols = v },
            Triple("Exclure caractères ambigus (O0Il1)", noAmbiguous) { v: Boolean -> noAmbiguous = v },
        ).forEach { (t, v, set) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(t, Modifier.weight(1f)); Switch(v, set)
            }
        }
    }
}

// ---------------- Sonomètre ----------------

@SuppressLint("MissingPermission")
@Composable
fun SoundMeterScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(ctx, MIC)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var db by remember { mutableFloatStateOf(0f) }
    var maxDb by remember { mutableFloatStateOf(0f) }
    var history by remember { mutableStateOf(listOf<Float>()) }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val rate = 44100
            val bufSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            val buf = ShortArray(bufSize)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                        val rms = sqrt(sum / n)
                        // Approximation : dBFS décalé pour ressembler à des dB SPL (non calibré)
                        val v = (20 * log10(rms.coerceAtLeast(1.0)) + 10).toFloat().coerceIn(0f, 120f)
                        db = db * 0.7f + v * 0.3f
                        if (db > maxDb) maxDb = db
                        history = (history + db).takeLast(100)
                    }
                    delay(100)
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }
    }

    ToolColumn {
        if (!granted) {
            Text("Le sonomètre a besoin du micro.")
            Button(onClick = { req.launch(MIC) }) { Text("Autoriser le micro") }
            return@ToolColumn
        }
        Text("${db.toInt()} dB", fontSize = 72.sp)
        Text(when {
            db < 30 -> "🤫 Très calme"; db < 50 -> "🏠 Calme"; db < 70 -> "🗣️ Conversation"
            db < 85 -> "🚗 Bruyant"; else -> "⚠️ Dangereux en exposition prolongée"
        }, style = MaterialTheme.typography.titleMedium)
        Text("Max : ${maxDb.toInt()} dB", color = MaterialTheme.colorScheme.outline)
        val lineColor = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            if (history.size > 1) {
                val dx = size.width / 99f
                for (i in 1 until history.size) {
                    drawLine(lineColor,
                        Offset((i - 1) * dx, size.height * (1 - history[i - 1] / 120f)),
                        Offset(i * dx, size.height * (1 - history[i] / 120f)), 4f)
                }
            }
        }
        OutlinedButton(onClick = { maxDb = 0f }) { Text("Réinitialiser le max") }
        Text("Valeurs approximatives : le micro du téléphone n'est pas calibré.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Infos appareil ----------------

private fun bytes(b: Long): String {
    val gb = b / 1e9
    return if (gb >= 1) "%.1f Go".format(gb) else "%.0f Mo".format(b / 1e6)
}

@Composable
fun DeviceInfoScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

    val info = remember(tick) {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
        val status = when (bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "En charge"; BatteryManager.BATTERY_STATUS_FULL -> "Pleine"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Sur batterie"; else -> "?"
        }
        val plug = when (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Secteur"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sans fil"; else -> "Non branché"
        }
        val health = when (bat?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Bonne"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Surchauffe"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Morte"; BatteryManager.BATTERY_HEALTH_COLD -> "Froide"; else -> "Inconnue"
        }
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val volt = (bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f

        val st = StatFs(Environment.getDataDirectory().path)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "Aucun"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Autre"
        }
        val dm = ctx.resources.displayMetrics
        linkedMapOf(
            "🔋 Batterie" to listOf("Niveau" to "$level %", "État" to status, "Alimentation" to plug, "Santé" to health,
                "Température" to "%.1f °C".format(temp), "Tension" to "%.2f V".format(volt)),
            "💾 Stockage & mémoire" to listOf(
                "Stockage libre" to "${bytes(st.availableBytes)} / ${bytes(st.totalBytes)}",
                "RAM libre" to "${bytes(mi.availMem)} / ${bytes(mi.totalMem)}"),
            "📱 Appareil" to listOf("Fabricant" to Build.MANUFACTURER, "Modèle" to Build.MODEL,
                "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Écran" to "${dm.widthPixels}×${dm.heightPixels} • ${dm.densityDpi} dpi",
                "Processeur" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?") + " • ${Runtime.getRuntime().availableProcessors()} cœurs"),
            "🌐 Réseau" to listOf("Connexion" to net,
                "Débit descendant" to (caps?.linkDownstreamBandwidthKbps?.let { "${it / 1000} Mb/s (estim.)" } ?: "-")),
        ) to level
    }
    ToolColumn {
        val (sections, level) = info
        if (level >= 0) LinearProgressIndicator({ level / 100f }, Modifier.fillMaxWidth().height(12.dp))
        sections.forEach { (title, rows) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.outline); Text(v)
                        }
                    }
                }
            }
        }
    }
}
