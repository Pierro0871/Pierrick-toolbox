package com.pierrick.toolbox

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------- Lampe torche ----------------

@Composable
fun TorchScreen() {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val camId = remember {
        cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }
    var mode by remember { mutableStateOf("off") } // off, on, strobe, sos
    var strobeHz by remember { mutableFloatStateOf(5f) }
    fun set(on: Boolean) { camId?.let { runCatching { cm.setTorchMode(it, on) } } }

    LaunchedEffect(mode, strobeHz) {
        when (mode) {
            "off" -> set(false)
            "on" -> set(true)
            "strobe" -> while (isActive) {
                val half = (500 / strobeHz).toLong()
                set(true); delay(half); set(false); delay(half)
            }
            "sos" -> {
                val u = 200L
                val pattern = listOf(1, 1, 1, 3, 3, 3, 1, 1, 1) // ... --- ...
                while (isActive) {
                    pattern.forEachIndexed { i, len ->
                        set(true); delay(u * len); set(false)
                        delay(if (i == 2 || i == 5) u * 3 else u)
                    }
                    delay(u * 7)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { set(false) } }

    ToolColumn {
        if (camId == null) Text("Aucun flash détecté sur cet appareil.", color = MaterialTheme.colorScheme.error)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            FilledIconToggle(mode != "off") { mode = if (mode == "off") "on" else "off" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("on" to "Fixe", "strobe" to "Strobo", "sos" to "SOS").forEach { (m, l) ->
                FilterChip(selected = mode == m, onClick = { mode = if (mode == m) "off" else m }, label = { Text(l) })
            }
        }
        if (mode == "strobe") {
            Text("Fréquence : ${strobeHz.roundToInt()} Hz")
            Slider(strobeHz, { strobeHz = it }, valueRange = 1f..15f)
        }
        Text("Astuce : l'écran blanc ci-dessous peut aussi servir de lumière douce.")
        var screenLight by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { screenLight = !screenLight }) { Text(if (screenLight) "Masquer écran blanc" else "Écran blanc") }
        if (screenLight) Box(Modifier.fillMaxWidth().height(300.dp).then(Modifier.padding(0.dp))) {
            Canvas(Modifier.fillMaxSize()) { drawRect(Color.White) }
        }
    }
}

@Composable
private fun FilledIconToggle(on: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(180.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) Color(0xFFFFC107) else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) { Text(if (on) "ON" else "OFF", fontSize = 40.sp, fontWeight = FontWeight.Bold) }
}

// ---------------- QR ----------------

@Composable
fun QrScreen() {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> if (r.contents != null) result = r.contents }
    var text by remember { mutableStateOf("") }
    val bmp = remember(text) { if (text.isBlank()) null else qrBitmap(text) }

    ToolColumn {
        Text("Scanner", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {
            launcher.launch(ScanOptions().setPrompt("Vise le code").setBeepEnabled(true).setOrientationLocked(false))
        }) { Text("📷 Scanner un code") }
        result?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copy(ctx, r) }) { Text("Copier") }
                        if (r.startsWith("http://") || r.startsWith("https://"))
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r)))
                            }) { Text("Ouvrir") }
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r), null))
                        }) { Text("Partager") }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("Générer un QR code", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Texte, lien, Wi-Fi…") })
        bmp?.let {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(it.asImageBitmap(), null, Modifier.size(260.dp))
            }
        }
    }
}

fun qrBitmap(text: String, size: Int = 512): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

fun copy(ctx: Context, s: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("toolbox", s))
}

// ---------------- Niveau + boussole ----------------

@Composable
fun rememberSensor(type: Int): FloatArray? {
    val ctx = LocalContext.current
    var values by remember { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(type)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values = e.values.clone() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return values
}

@Composable
fun LevelCompassScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Niveau") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Boussole") })
        }
        if (tab == 0) LevelView() else CompassView()
    }
}

@Composable
private fun LevelView() {
    val g = rememberSensor(Sensor.TYPE_ACCELEROMETER)
    val x = g?.get(0) ?: 0f; val y = g?.get(1) ?: 0f; val z = g?.get(2) ?: 9.81f
    val pitch = Math.toDegrees(atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))).toFloat()
    val roll = Math.toDegrees(atan2(-x.toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
    val ok = abs(pitch) < 1f && abs(roll) < 1f
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    ToolColumn {
        if (g == null) Text("Accéléromètre indisponible")
        Text("X : %.1f°   Y : %.1f°".format(roll, pitch), style = MaterialTheme.typography.headlineSmall)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val r = size.minDimension / 2
            val c = center
            drawCircle(outline, r, c, style = Stroke(4f))
            drawCircle(outline, r * 0.15f, c, style = Stroke(2f))
            drawLine(outline, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
            drawLine(outline, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
            val bx = (roll / 45f).coerceIn(-1f, 1f) * r * 0.85f
            val by = (pitch / 45f).coerceIn(-1f, 1f) * r * 0.85f
            drawCircle(if (ok) Color(0xFF4CAF50) else primary, r * 0.12f, Offset(c.x - bx, c.y + by))
        }
        Text(if (ok) "✅ De niveau" else "Pose le téléphone à plat ou sur la tranche", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CompassView() {
    val rv = rememberSensor(Sensor.TYPE_ROTATION_VECTOR)
    val azimuth = rv?.let {
        val m = FloatArray(9); SensorManager.getRotationMatrixFromVector(m, it)
        val o = FloatArray(3); SensorManager.getOrientation(m, o)
        ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
    } ?: 0f
    val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    val label = dirs[((azimuth + 22.5f) / 45f).toInt() % 8]
    val onSurf = MaterialTheme.colorScheme.onSurface
    ToolColumn {
        if (rv == null) Text("Capteur d'orientation indisponible")
        Text("${azimuth.roundToInt()}° $label", style = MaterialTheme.typography.displaySmall)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val r = size.minDimension / 2 * 0.9f
            rotate(-azimuth) {
                drawCircle(onSurf, r, style = Stroke(4f))
                for (i in 0 until 72) {
                    rotate(i * 5f) {
                        val len = if (i % 18 == 0) 40f else if (i % 2 == 0) 20f else 10f
                        drawLine(onSurf, Offset(center.x, center.y - r), Offset(center.x, center.y - r + len), 3f)
                    }
                }
                // aiguille nord
                drawLine(Color.Red, center, Offset(center.x, center.y - r * 0.8f), 12f)
                drawLine(onSurf.copy(alpha = .5f), center, Offset(center.x, center.y + r * 0.8f), 12f)
            }
            drawLine(Color(0xFFFFC107), Offset(center.x, 0f), Offset(center.x, 40f), 8f)
        }
        Text("Calibre en faisant un 8 avec le téléphone si la valeur dérive.")
    }
}

// ---------------- Règle ----------------

@Composable
fun RulerScreen() {
    val dm = LocalContext.current.resources.displayMetrics
    val density = LocalDensity.current
    var inches by remember { mutableStateOf(false) }
    var calib by remember { mutableFloatStateOf(1f) }
    val pxPerMm = dm.ydpi / 25.4f * calib
    val color = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxHeight().width(120.dp)) {
            if (!inches) {
                var mm = 0
                while (mm * pxPerMm < size.height) {
                    val y = mm * pxPerMm
                    val len = when { mm % 10 == 0 -> 90f; mm % 5 == 0 -> 60f; else -> 35f }
                    drawLine(color, Offset(0f, y), Offset(len, y), 2f)
                    if (mm % 10 == 0) drawContext.canvas.nativeCanvas.drawText(
                        "${mm / 10}", len + 8f, y + 14f,
                        android.graphics.Paint().apply { textSize = with(density) { 14.sp.toPx() }; this.color = android.graphics.Color.GRAY }
                    )
                    mm++
                }
            } else {
                val pxPerSixteenth = pxPerMm * 25.4f / 16f
                var i = 0
                while (i * pxPerSixteenth < size.height) {
                    val y = i * pxPerSixteenth
                    val len = when { i % 16 == 0 -> 90f; i % 8 == 0 -> 65f; i % 4 == 0 -> 50f; else -> 30f }
                    drawLine(color, Offset(0f, y), Offset(len, y), 2f)
                    if (i % 16 == 0) drawContext.canvas.nativeCanvas.drawText(
                        "${i / 16}", len + 8f, y + 14f,
                        android.graphics.Paint().apply { textSize = with(density) { 14.sp.toPx() }; this.color = android.graphics.Color.GRAY }
                    )
                    i++
                }
            }
            drawLine(accent, Offset(0f, 0f), Offset(0f, size.height), 6f)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bord gauche de l'écran = 0")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("cm"); Switch(inches, { inches = it }, Modifier.padding(horizontal = 8.dp)); Text("pouces")
            }
            Text("Calibrage : ×%.2f".format(calib))
            Slider(calib, { calib = it }, valueRange = 0.8f..1.2f)
            Text("Compare avec une vraie règle ou une carte bancaire (8,56 cm) pour ajuster.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
val MIC = Manifest.permission.RECORD_AUDIO
