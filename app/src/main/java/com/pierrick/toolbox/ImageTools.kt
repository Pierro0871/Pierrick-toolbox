package com.pierrick.toolbox

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.*

// ---------------- Helpers caméra ----------------

@Composable
fun rememberCameraPermission(): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.CAMERA)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(Manifest.permission.CAMERA) }
    return granted to { req.launch(Manifest.permission.CAMERA) }
}

/** Aperçu caméra arrière simple ; renvoie la PreviewView pour lire des images. */
@Composable
fun CameraPreview(modifier: Modifier, torch: Boolean = false, analyzer: ImageAnalysis.Analyzer? = null): PreviewView {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var cam by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(Unit) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val cases = mutableListOf<UseCase>(preview)
            analyzer?.let { a -> cases += ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { it.setAnalyzer(ContextCompat.getMainExecutor(ctx), a) } }
            p.unbindAll()
            cam = runCatching { p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *cases.toTypedArray()) }.getOrNull()
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { f.get().unbindAll() } }
    }
    LaunchedEffect(cam, torch) { cam?.cameraControl?.enableTorch(torch) }
    AndroidView({ view }, modifier)
    return view
}

fun tempImageUri(ctx: Context): Uri {
    val f = File(ctx.cacheDir, "shared").apply { mkdirs() }.let { File(it, "photo_${System.currentTimeMillis()}.jpg") }
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
}

fun saveToGallery(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri? = runCatching {
    val cv = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Toolbox")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
    ctx.contentResolver.openOutputStream(uri)!!.use(write)
    uri
}.getOrNull()

/** Choisir une image ou prendre une photo. */
@Composable
fun ImageSource(onImage: (Uri) -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(onImage) }
    val shot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pending?.let(onImage) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { val u = tempImageUri(ctx); pending = u; shot.launch(u) }) { Text("📷 Prendre une photo") }
        OutlinedButton(onClick = { pick.launch("image/*") }) { Text("🖼️ Galerie") }
    }
}

// ---------------- Traduction (ML Kit, hors ligne après téléchargement) ----------------

val translateLangs = listOf(TranslateLanguage.FRENCH to "Français", TranslateLanguage.ENGLISH to "Anglais", TranslateLanguage.SPANISH to "Espagnol",
    TranslateLanguage.GERMAN to "Allemand", TranslateLanguage.ITALIAN to "Italien", TranslateLanguage.PORTUGUESE to "Portugais", TranslateLanguage.DUTCH to "Néerlandais",
    TranslateLanguage.ARABIC to "Arabe", TranslateLanguage.CHINESE to "Chinois", TranslateLanguage.JAPANESE to "Japonais", TranslateLanguage.KOREAN to "Coréen",
    TranslateLanguage.RUSSIAN to "Russe", TranslateLanguage.TURKISH to "Turc", TranslateLanguage.POLISH to "Polonais", TranslateLanguage.GREEK to "Grec",
    TranslateLanguage.THAI to "Thaï", TranslateLanguage.VIETNAMESE to "Vietnamien", TranslateLanguage.CROATIAN to "Croate", TranslateLanguage.SWEDISH to "Suédois")

fun translate(text: String, target: String, source: String? = null, onDone: (String?, String?) -> Unit) {
    fun go(src: String) {
        if (src == target) { onDone(text, null); return }
        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build())
        tr.downloadModelIfNeeded().addOnSuccessListener {
            tr.translate(text).addOnSuccessListener { onDone(it, null); tr.close() }.addOnFailureListener { onDone(null, "Erreur de traduction"); tr.close() }
        }.addOnFailureListener { onDone(null, "Téléchargement du dictionnaire impossible (connexion requise la 1re fois)") }
    }
    if (source != null) go(source) else LanguageIdentification.getClient().identifyLanguage(text)
        .addOnSuccessListener { code -> val src = TranslateLanguage.fromLanguageTag(code) ?: TranslateLanguage.ENGLISH; go(src) }
        .addOnFailureListener { go(TranslateLanguage.ENGLISH) }
}

// ---------------- OCR / traduction photo ----------------

@Composable
fun OcrScreen(translate: Boolean) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf(TranslateLanguage.FRENCH) }
    fun runTranslate() {
        if (text.isBlank()) return
        busy = true; translated = null
        translate(text, target) { r, e -> translated = r; err = e; busy = false }
    }
    ToolColumn {
        ImageSource { uri ->
            val b = loadBitmap(ctx, uri, 2000) ?: return@ImageSource
            bmp = b; busy = true; text = ""; translated = null; err = null
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(b, 0))
                .addOnSuccessListener { r -> text = r.text; busy = false; if (text.isBlank()) err = "Aucun texte détecté" else if (translate) runTranslate() }
                .addOnFailureListener { err = "Erreur de reconnaissance"; busy = false }
        }
        if (translate) Dropdown("Traduire vers", translateLangs, translateLangs.first { it.first == target }, { it.second }) { target = it.first; runTranslate() }
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (translate && translated != null) {
            Text("Traduction", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) { Text(translated!!, Modifier.padding(12.dp), fontSize = 18.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { copy(ctx, translated!!) }) { Text("Copier") }; OutlinedButton(onClick = { shareText(ctx, translated!!) }) { Text("Partager") } }
        }
        if (text.isNotBlank()) {
            Text(if (translate) "Texte original" else "Texte reconnu (modifiable)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { copy(ctx, text) }) { Text("Copier") }; OutlinedButton(onClick = { shareText(ctx, text) }) { Text("Partager") } }
        }
        Text("Reconnaissance sur l'appareil (alphabet latin). Traduction hors ligne après téléchargement de la langue (~30 Mo).", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Pipette couleur ----------------

private val namedColors = listOf("Noir" to 0x000000, "Blanc" to 0xFFFFFF, "Gris" to 0x808080, "Rouge" to 0xE53935, "Bordeaux" to 0x800020, "Rose" to 0xF48FB1,
    "Orange" to 0xFB8C00, "Jaune" to 0xFDD835, "Beige" to 0xF5F5DC, "Marron" to 0x795548, "Vert" to 0x43A047, "Vert olive" to 0x808000, "Turquoise" to 0x40E0D0,
    "Cyan" to 0x00BCD4, "Bleu ciel" to 0x87CEEB, "Bleu" to 0x1E88E5, "Bleu marine" to 0x000080, "Violet" to 0x8E24AA, "Lavande" to 0xB39DDB, "Doré" to 0xD4AF37)
private fun nearestName(c: Int): String = namedColors.minBy { (_, v) ->
    val dr = (c shr 16 and 255) - (v shr 16 and 255); val dg = (c shr 8 and 255) - (v shr 8 and 255); val db = (c and 255) - (v and 255); dr * dr * 3 + dg * dg * 4 + db * db * 2 }.first

@Composable
fun ColorPickerScreen() {
    val ctx = LocalContext.current
    val (granted, ask) = rememberCameraPermission()
    var color by remember { mutableIntStateOf(0) }
    var frozen by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(prefs(ctx).getString("colors", "")!!.split(",").filter { it.isNotBlank() }) }
    if (!granted) { ToolColumn { Button(onClick = ask) { Text("Autoriser la caméra") } }; return }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val view = CameraPreview(Modifier.fillMaxSize())
            LaunchedEffect(frozen) {
                while (!frozen) {
                    delay(250)
                    view.bitmap?.let { b ->
                        var r = 0; var g = 0; var bl = 0; var n = 0
                        for (dx in -4..4) for (dy in -4..4) { val p = b.getPixel((b.width / 2 + dx).coerceIn(0, b.width - 1), (b.height / 2 + dy).coerceIn(0, b.height - 1)); r += p shr 16 and 255; g += p shr 8 and 255; bl += p and 255; n++ }
                        color = (r / n shl 16) or (g / n shl 8) or (bl / n)
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White, 28f, center, style = Stroke(4f)); drawCircle(Color.Black, 32f, center, style = Stroke(2f)) }
        }
        val hex = "#%06X".format(color)
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color or 0xFF000000.toInt(), it) }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(color or 0xFF000000.toInt())))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(hex, fontSize = 26.sp, fontFamily = FontFamily.Monospace)
                Text("RGB ${color shr 16 and 255}, ${color shr 8 and 255}, ${color and 255}")
                Text("HSV ${hsv[0].roundToInt()}°, ${(hsv[1] * 100).roundToInt()} %, ${(hsv[2] * 100).roundToInt()} % • ≈ ${nearestName(color)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { frozen = !frozen }) { Text(if (frozen) "▶ Reprendre" else "⏸ Figer") }
            OutlinedButton(onClick = { copy(ctx, hex) }) { Text("Copier") }
            OutlinedButton(onClick = { saved = (listOf(hex) + saved).distinct().take(12); prefs(ctx).edit().putString("colors", saved.joinToString(",")).apply() }) { Text("💾") }
        }
        if (saved.isNotEmpty()) Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            saved.take(8).forEach { h -> Box(Modifier.size(32.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(h))).pointerInput(h) { detectTapGestures { copy(ctx, h) } }) }
        }
    }
}

// ---------------- Compression ----------------

@Composable
fun CompressScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uris by remember { mutableStateOf(listOf<Uri>()) }
    var quality by remember { mutableFloatStateOf(75f) }
    var maxSide by remember { mutableIntStateOf(1920) }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris = it; report = null }
    ToolColumn {
        Button(onClick = { pick.launch("image/*") }) { Text("🖼️ Choisir des photos") }
        if (uris.isNotEmpty()) Text("${uris.size} photo(s) sélectionnée(s)")
        Text("Qualité JPEG : ${quality.roundToInt()} %"); Slider(quality, { quality = it }, valueRange = 30f..95f)
        Text("Taille maximale")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(800, 1280, 1920, 2560, 0).forEach { s -> FilterChip(maxSide == s, { maxSide = s }, label = { Text(if (s == 0) "Originale" else "${s}px") }) } }
        Button(onClick = {
            busy = true
            scope.launch {
                var before = 0L; var after = 0L; var ok = 0
                withContext(Dispatchers.IO) {
                    uris.forEach { u ->
                        before += ctx.contentResolver.openInputStream(u)?.use { it.available().toLong() } ?: 0
                        val b = loadBitmap(ctx, u, if (maxSide == 0) 8000 else maxSide * 2) ?: return@forEach
                        val scaled = if (maxSide > 0 && max(b.width, b.height) > maxSide) {
                            val k = maxSide.toFloat() / max(b.width, b.height); Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
                        } else b
                        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality.roundToInt(), it) }.toByteArray()
                        if (saveToGallery(ctx, "compresse_${System.currentTimeMillis()}.jpg", "image/jpeg") { it.write(bytes) } != null) { after += bytes.size; ok++ }
                    }
                }
                report = "$ok photo(s) enregistrée(s) dans Images/Toolbox\n${before / 1024} Ko → ${after / 1024} Ko" + if (before > 0) " (−${100 - after * 100 / before} %)" else ""
                busy = false
            }
        }, enabled = uris.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text("🗜️ Compresser") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        report?.let { ResultCard { Text(it) } }
        Text("Les originaux ne sont pas modifiés. Les copies n'ont plus de données EXIF (position GPS, appareil…).", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- EXIF ----------------

@Composable
fun ExifScreen() {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var tags by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var gps by remember { mutableStateOf<DoubleArray?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u ?: return@rememberLauncherForActivityResult
        uri = u; msg = null
        ctx.contentResolver.openInputStream(u)?.use { s ->
            val e = android.media.ExifInterface(s)
            gps = FloatArray(2).let { a -> if (e.getLatLong(a)) doubleArrayOf(a[0].toDouble(), a[1].toDouble()) else null }
            tags = listOf("Appareil" to "TAG_MAKE", "Modèle" to "TAG_MODEL", "Date" to "TAG_DATETIME", "Logiciel" to "TAG_SOFTWARE",
                "Largeur" to "TAG_IMAGE_WIDTH", "Hauteur" to "TAG_IMAGE_LENGTH", "Ouverture" to "TAG_F_NUMBER", "Exposition" to "TAG_EXPOSURE_TIME",
                "ISO" to "TAG_ISO_SPEED_RATINGS", "Focale" to "TAG_FOCAL_LENGTH", "Flash" to "TAG_FLASH", "Altitude GPS" to "TAG_GPS_ALTITUDE", "Auteur" to "TAG_ARTIST")
                .mapNotNull { (l, f) -> val tag = android.media.ExifInterface::class.java.getField(f).get(null) as String; e.getAttribute(tag)?.let { l to it } }
        }
    }
    ToolColumn {
        Button(onClick = { pick.launch("image/*") }) { Text("🖼️ Choisir une photo") }
        uri?.let { u ->
            Thumb(u, Modifier.fillMaxWidth().height(200.dp))
            gps?.let { g ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⚠️ Cette photo contient ta position GPS !", style = MaterialTheme.typography.titleMedium)
                        Text("%.5f, %.5f".format(g[0], g[1]))
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${g[0]},${g[1]}?q=${g[0]},${g[1]}"))) }) { Text("Voir sur la carte") }
                    }
                }
            } ?: Text("✅ Pas de position GPS dans cette photo", color = Color(0xFF43A047))
            if (tags.isEmpty()) Text("Aucune métadonnée EXIF") else ResultCard { tags.forEach { (k, v) -> Row { Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.outline); Text(v) } } }
            Button(onClick = {
                val b = loadBitmap(ctx, u, 8000)
                val out = b?.let { bm -> saveToGallery(ctx, "propre_${System.currentTimeMillis()}.jpg", "image/jpeg") { bm.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                msg = if (out != null) "✅ Copie sans métadonnées enregistrée dans Images/Toolbox" else "Erreur"
                out?.let { o -> ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, o).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager la photo propre")) }
            }, Modifier.fillMaxWidth()) { Text("🧹 Créer une copie propre et la partager") }
            msg?.let { Text(it) }
        }
    }
}

// ---------------- GIF ----------------

/** Encodeur GIF minimal : palette fixe 6×7×6 + LZW. */
class GifEncoder(private val out: OutputStream, private val w: Int, private val h: Int, private val delayCs: Int) {
    private val palette = IntArray(256) { i -> if (i < 252) { val r = i / 42; val g = (i / 6) % 7; val b = i % 6; (r * 51 shl 16) or ((g * 255 / 6) shl 8) or (b * 51) } else 0 }
    init {
        out.write("GIF89a".toByteArray()); short(w); short(h); out.write(0xF7); out.write(0); out.write(0)
        palette.forEach { out.write(it shr 16 and 255); out.write(it shr 8 and 255); out.write(it and 255) }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0))
    }
    private fun short(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
    fun addFrame(src: Bitmap) {
        val b = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        addIndexedFrame(ByteArray(w * h) { i -> val p = px[i]; val r = ((p shr 16 and 255) * 6 / 256); val g = ((p shr 8 and 255) * 7 / 256); val bl = ((p and 255) * 6 / 256); (r * 42 + g * 6 + bl).toByte() })
    }
    internal fun addIndexedFrame(idx: ByteArray) {
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0)); short(delayCs); out.write(0); out.write(0)
        out.write(0x2C); short(0); short(0); short(w); short(h); out.write(0)
        lzw(idx)
    }
    private fun lzw(data: ByteArray) {
        val minCode = 8; out.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val block = ByteArrayOutputStream(); var acc = 0; var bits = 0
        var codeSize = minCode + 1
        fun emit(code: Int) {
            acc = acc or (code shl bits); bits += codeSize
            while (bits >= 8) { block.write(acc and 255); acc = acc ushr 8; bits -= 8 }
        }
        val dict = HashMap<Int, Int>(); var next = eoi + 1
        emit(clear)
        var prefix = data[0].toInt() and 255
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 255; val key = (prefix shl 8) or c
            val found = dict[key]
            if (found != null) prefix = found else {
                emit(prefix)
                if (next < 4096) { dict[key] = next++; if (next > (1 shl codeSize) && codeSize < 12) codeSize++ }
                else { emit(clear); dict.clear(); next = eoi + 1; codeSize = minCode + 1 }
                prefix = c
            }
        }
        emit(prefix); emit(eoi)
        if (bits > 0) block.write(acc and 255)
        val bytes = block.toByteArray(); var o = 0
        while (o < bytes.size) { val n = min(255, bytes.size - o); out.write(n); out.write(bytes, o, n); o += n }
        out.write(0)
    }
    fun finish() { out.write(0x3B); out.flush() }
}

@Composable
fun GifScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uris by remember { mutableStateOf(listOf<Uri>()) }
    var delayMs by remember { mutableFloatStateOf(500f) }
    var size by remember { mutableIntStateOf(480) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris = it; result = null }
    ToolColumn {
        Button(onClick = { pick.launch("image/*") }) { Text("🖼️ Choisir les images (dans l'ordre)") }
        if (uris.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { uris.take(8).forEach { Thumb(it, Modifier.size(40.dp)) }; if (uris.size > 8) Text("+${uris.size - 8}") }
        Text("Délai entre images : ${delayMs.roundToInt()} ms"); Slider(delayMs, { delayMs = it }, valueRange = 50f..2000f)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(240, 360, 480, 640).forEach { s -> FilterChip(size == s, { size = s }, label = { Text("${s}px") }) } }
        Button(onClick = {
            busy = true
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    val first = loadBitmap(ctx, uris[0], size * 2) ?: return@withContext null
                    val k = size.toFloat() / max(first.width, first.height)
                    val w = (first.width * k).toInt().coerceAtLeast(1); val h = (first.height * k).toInt().coerceAtLeast(1)
                    saveToGallery(ctx, "anim_${System.currentTimeMillis()}.gif", "image/gif") { os ->
                        val enc = GifEncoder(os, w, h, (delayMs / 10).roundToInt())
                        uris.forEach { u -> loadBitmap(ctx, u, size * 2)?.let { enc.addFrame(it) } }
                        enc.finish()
                    }
                }
                busy = false
            }
        }, enabled = uris.size >= 2 && !busy, modifier = Modifier.fillMaxWidth()) { Text("🎞️ Créer le GIF") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { r ->
            Text("✅ GIF enregistré dans Images/Toolbox", color = Color(0xFF43A047))
            Button(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/gif").putExtra(Intent.EXTRA_STREAM, r).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) }) { Text("Partager") }
        }
    }
}

// ---------------- Mesure par caméra ----------------

@Composable
fun CamMeasureScreen() {
    val (granted, ask) = rememberCameraPermission()
    var phoneH by remember { mutableStateOf("1.5") }
    var dist by remember { mutableStateOf<Double?>(null) }
    var height by remember { mutableStateOf<Double?>(null) }
    val g = rememberSensor(Sensor.TYPE_ACCELEROMETER)
    // angle entre l'axe de la caméra et la verticale vers le bas (0° = pointe au sol)
    val tilt = g?.let { Math.toDegrees(atan2(sqrt((it[0] * it[0] + it[1] * it[1]).toDouble()), -it[2].toDouble())).let { a -> 180 - a } } ?: 0.0
    if (!granted) { ToolColumn { Button(onClick = ask) { Text("Autoriser la caméra") } }; return }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CameraPreview(Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                drawLine(Color.Red, Offset(center.x - 60, center.y), Offset(center.x + 60, center.y), 4f)
                drawLine(Color.Red, Offset(center.x, center.y - 60), Offset(center.x, center.y + 60), 4f)
            }
            Text("Inclinaison : %.1f°".format(tilt), Modifier.align(Alignment.TopCenter).background(Color.Black.copy(.5f)).padding(6.dp), color = Color.White)
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1) Tiens le téléphone à hauteur des yeux, vise le PIED de l'objet avec la croix. 2) Puis vise le SOMMET.", style = MaterialTheme.typography.bodySmall)
            NumField(phoneH, "Hauteur du téléphone (m)", Modifier.fillMaxWidth()) { phoneH = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val h = phoneH.num() ?: 1.5; dist = if (tilt in 1.0..89.0) h * tan(Math.toRadians(tilt)) else null; height = null }) { Text("① Viser le pied") }
                Button(onClick = { dist?.let { d -> height = (phoneH.num() ?: 1.5) + d * tan(Math.toRadians(tilt - 90)) } }, enabled = dist != null) { Text("② Viser le sommet") }
            }
            Text("Distance : ${dist?.let { "%.2f m".format(it) } ?: "—"}   •   Hauteur : ${height?.let { "%.2f m".format(it) } ?: "—"}", style = MaterialTheme.typography.titleLarge)
            Text("Estimation trigonométrique, précise à ±10 % environ sur sol plat.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- Comptage sur photo ----------------

@Composable
fun ObjectCountScreen() {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    val marks = remember { mutableStateListOf<Offset>() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageSource { u -> bmp = loadBitmap(ctx, u, 2000); marks.clear() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compte : ${marks.size}", fontSize = 28.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) }) { Text("↶ Annuler") }
            TextButton(onClick = { marks.clear() }) { Text("Effacer") }
        }
        Text("Touche chaque objet pour le marquer, touche un repère pour l'enlever.", style = MaterialTheme.typography.bodySmall)
        bmp?.let { b ->
            Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { boxSize = it }.pointerInput(b) {
                detectTapGestures { p ->
                    val hit = marks.indexOfFirst { (it - p).getDistance() < 40f }
                    if (hit >= 0) marks.removeAt(hit) else marks.add(p)
                }
            }) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(Modifier.fillMaxSize()) {
                    marks.forEachIndexed { i, m ->
                        drawCircle(Color(0xFFE53935), 22f, m); drawCircle(Color.White, 22f, m, style = Stroke(3f))
                        drawContext.canvas.nativeCanvas.drawText("${i + 1}", m.x, m.y + 8f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 22f; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true })
                    }
                }
            }
        }
    }
}

// ---------------- Pouls ----------------

@Composable
fun PulseScreen() {
    val (granted, ask) = rememberCameraPermission()
    var measuring by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<Pair<Long, Double>>() }
    var bpm by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var fingerOk by remember { mutableStateOf(false) }
    val duration = 20_000L
    val analyzer = remember {
        ImageAnalysis.Analyzer { img ->
            if (measuring) {
                val y = img.planes[0]; val buf = y.buffer; val w = img.width; val h = img.height; val rs = y.rowStride
                var sum = 0L; var n = 0
                for (row in h / 4 until 3 * h / 4 step 4) for (col in w / 4 until 3 * w / 4 step 4) { sum += buf.get(row * rs + col).toInt() and 255; n++ }
                val v = sum.toDouble() / n
                // avec le doigt sur l'objectif + flash, l'image est rouge sombre/uniforme
                fingerOk = v in 20.0..200.0
                samples.add(System.currentTimeMillis() to v)
            }
            img.close()
        }
    }
    LaunchedEffect(measuring) {
        if (!measuring) return@LaunchedEffect
        samples.clear(); bpm = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < duration) { delay(200); progress = (System.currentTimeMillis() - start).toFloat() / duration }
        measuring = false
        val s = samples.toList().dropWhile { it.first - start < 3000 } // on ignore les 3 premières secondes
        if (s.size > 30) {
            val win = 10
            val det = s.indices.map { i -> val a = max(0, i - win); val b = min(s.size - 1, i + win); s[i].second - (a..b).map { s[it].second }.average() }
            val sm = det.indices.map { i -> (max(0, i - 2)..min(det.size - 1, i + 2)).map { det[it] }.average() }
            val beats = mutableListOf<Long>()
            for (i in 1 until sm.size) if (sm[i - 1] < 0 && sm[i] >= 0 && (beats.isEmpty() || s[i].first - beats.last() > 333)) beats += s[i].first
            if (beats.size >= 4) {
                val intervals = beats.zipWithNext { a, b -> b - a }.sorted()
                val med = intervals[intervals.size / 2]
                bpm = (60000.0 / med).roundToInt().takeIf { it in 40..200 }
            }
        }
    }
    if (!granted) { ToolColumn { Button(onClick = ask) { Text("Autoriser la caméra") } }; return }
    val wave = samples.takeLast(100).map { it.second.toFloat() }
    ToolColumn {
        Box(Modifier.size(1.dp)) { CameraPreview(Modifier.size(1.dp), torch = measuring, analyzer = analyzer) }
        Text("❤️", fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(bpm?.let { "$it BPM" } ?: if (measuring) "Mesure…" else "—", fontSize = 48.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (measuring) {
            LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
            if (!fingerOk) Text("Pose bien le doigt sur l'objectif ET le flash", color = MaterialTheme.colorScheme.error)
            if (wave.size > 2) LineChart(wave, Modifier.fillMaxWidth().height(100.dp))
        }
        if (!measuring && samples.isNotEmpty() && bpm == null) Text("Mesure ratée : garde le doigt immobile et appuie légèrement.", color = MaterialTheme.colorScheme.error)
        Button(onClick = { measuring = !measuring }, Modifier.fillMaxWidth().height(56.dp)) { Text(if (measuring) "⏹ Arrêter" else "▶ Mesurer (20 s)", fontSize = 18.sp) }
        Text("Couvre complètement l'objectif arrière et le flash avec le bout de l'index, sans appuyer fort, et reste immobile. Indication seulement, pas un appareil médical.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Détecteur caméra cachée ----------------

@Composable
fun SpyCamScreen() {
    val ctx = LocalContext.current
    val m = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val mag = m?.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) } ?: 0f
    var base by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(m != null) { if (m != null && base == null) { delay(800); base = mag } }
    val diff = base?.let { mag - it } ?: 0f
    val alert = diff > 40
    LaunchedEffect(alert) { while (alert) { vibrate(ctx, 80); delay(400) } }
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(tab) { Tab(tab == 0, { tab = 0 }, text = { Text("Magnétique") }); Tab(tab == 1, { tab = 1 }, text = { Text("Reflets (lentille)") }) }
        if (tab == 0) ToolColumn {
            if (m == null) { Text("Pas de magnétomètre"); return@ToolColumn }
            Text("${mag.roundToInt()} µT", fontSize = 56.sp)
            LinearProgressIndicator({ (diff / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(16.dp), color = if (alert) Color(0xFFE53935) else Color(0xFF43A047))
            Text(if (alert) "⚠️ Champ magnétique anormal : appareil électronique à proximité ?" else "Normal", color = if (alert) Color(0xFFE53935) else Color.Unspecified, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { base = mag }) { Text("Recalibrer ici") }
            Text("Passe lentement le téléphone près des détecteurs de fumée, prises, réveils, cadres… Un pic peut signaler un appareil électronique (mais aussi un simple objet métallique).", style = MaterialTheme.typography.bodySmall)
        } else {
            val (granted, ask) = rememberCameraPermission()
            if (!granted) { ToolColumn { Button(onClick = ask) { Text("Autoriser la caméra") } } } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    CameraPreview(Modifier.fillMaxSize(), torch = true)
                    Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.25f)))
                }
                Text("Éteins la lumière de la pièce et balaye lentement. Les objectifs de caméra renvoient un petit point brillant bleuté/violet avec le flash. Les LED infrarouges de vision nocturne apparaissent comme des points violets.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
