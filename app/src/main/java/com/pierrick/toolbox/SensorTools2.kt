package com.pierrick.toolbox

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate

// ---------------- Loupe & miroir (CameraX) ----------------

@Composable
fun CameraScreen(front: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.CAMERA)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        ToolColumn {
            Text("L'appareil photo est nécessaire.")
            Button(onClick = { req.launch(Manifest.permission.CAMERA) }) { Text("Autoriser la caméra") }
        }
        return
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(if (front) 0f else 0.5f) }
    var torch by remember { mutableStateOf(false) }
    var frozenBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(front) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            camera = runCatching {
                provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }.getOrNull()
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    LaunchedEffect(camera, zoom) { camera?.cameraControl?.setLinearZoom(zoom) }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        frozenBmp?.let { bmp ->
            androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        if (camera == null) Text("Caméra indisponible", Modifier.align(Alignment.Center), color = Color.White)
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.Black.copy(alpha = .55f)) {
            Column(Modifier.padding(12.dp)) {
                Text("Zoom", color = Color.White)
                Slider(zoom, { zoom = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!front) FilledTonalButton(onClick = { torch = !torch }) { Text(if (torch) "🔦 Éteindre" else "🔦 Lumière") }
                    FilledTonalButton(onClick = { frozenBmp = if (frozenBmp != null) null else previewView.bitmap?.asImageBitmap() }) { Text(if (frozenBmp != null) "▶ Reprendre" else "⏸ Figer") }
                }
            }
        }
    }
}


// ---------------- Podomètre ----------------

@Composable
fun PedometerScreen() {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null
    var granted by remember { mutableStateOf(perm == null || hasPerm(ctx, perm)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted && perm != null) req.launch(perm) }
    val p = prefs(ctx)
    var goal by remember { mutableStateOf(p.getInt("step_goal", 8000).toString()) }
    val v = if (granted) rememberSensor(Sensor.TYPE_STEP_COUNTER) else null
    val total = v?.get(0)?.toLong()
    val today = LocalDate.now().toString()
    val steps = remember(total) {
        if (total == null) null else {
            if (p.getString("step_day", "") != today || total < p.getLong("step_base", 0)) {
                p.edit().putString("step_day", today).putLong("step_base", total).apply()
            }
            total - p.getLong("step_base", total)
        }
    }
    val g = goal.toIntOrNull()?.coerceAtLeast(1) ?: 8000
    ToolColumn {
        when {
            !granted -> { Text("Autorise l'accès à l'activité physique."); Button(onClick = { perm?.let { req.launch(it) } }) { Text("Autoriser") } }
            (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null ->
                Text("Pas de capteur de pas sur cet appareil.", color = MaterialTheme.colorScheme.error)
            steps == null -> Text("En attente du capteur… fais quelques pas.")
            else -> {
                Text("$steps pas", fontSize = 64.sp)
                Text("aujourd'hui (comptés depuis la 1re ouverture du jour)", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator({ (steps.toFloat() / g).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(14.dp))
                Text("${(steps * 100 / g)} % de l'objectif • ≈ ${"%.2f".format(steps * 0.75 / 1000)} km • ≈ ${(steps * 0.04).toInt()} kcal")
            }
        }
        OutlinedTextField(goal, { goal = it; it.toIntOrNull()?.let { n -> p.edit().putInt("step_goal", n).apply() } },
            label = { Text("Objectif quotidien") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        Text("Astuce : le compteur repart à zéro chaque jour à la première ouverture de l'outil.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Baromètre / altimètre ----------------

@Composable
fun BarometerScreen() {
    val ctx = LocalContext.current
    val v = rememberSensor(Sensor.TYPE_PRESSURE)
    var seaLevel by remember { mutableStateOf("1013.25") }
    val has = remember { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PRESSURE) != null }
    ToolColumn {
        if (!has) { Text("Ce téléphone n'a pas de baromètre 😕", color = MaterialTheme.colorScheme.error); return@ToolColumn }
        val hpa = v?.get(0)
        if (hpa == null) { Text("Lecture du capteur…"); return@ToolColumn }
        val ref = seaLevel.num()?.toFloat() ?: SensorManager.PRESSURE_STANDARD_ATMOSPHERE
        val alt = SensorManager.getAltitude(ref, hpa)
        Text("%.1f hPa".format(hpa), fontSize = 52.sp)
        Text(when { hpa < 1000 -> "🌧️ Basse pression (temps instable)"; hpa > 1020 -> "☀️ Haute pression (beau temps)"; else -> "⛅ Pression normale" })
        HorizontalDivider()
        Text("Altitude estimée", style = MaterialTheme.typography.titleMedium)
        Text("%.0f m".format(alt), fontSize = 44.sp, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(seaLevel, { seaLevel = it }, label = { Text("Pression au niveau de la mer (hPa)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Text("Pour plus de précision, entre la pression « QNH » locale donnée par la météo.", style = MaterialTheme.typography.bodySmall)
    }
}
