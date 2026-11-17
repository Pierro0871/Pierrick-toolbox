package com.pierrick.toolbox

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun humanSize(b: Long): String = when { b >= 1L shl 30 -> "%.2f Go".format(b / 1073741824.0); b >= 1L shl 20 -> "%.1f Mo".format(b / 1048576.0); b >= 1024 -> "${b / 1024} Ko"; else -> "$b o" }

private fun iconFor(f: DocumentFile): String {
    if (f.isDirectory) return "📁"
    val t = f.type ?: ""; val n = f.name?.lowercase() ?: ""
    return when { t.startsWith("image") -> "🖼️"; t.startsWith("video") -> "🎬"; t.startsWith("audio") -> "🎵"; t.contains("pdf") -> "📕"
        n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") -> "🗜️"; n.endsWith(".apk") -> "📦"; t.startsWith("text") -> "📄"; else -> "📄" }
}

@Composable
fun rememberTreeAccess(key: String): Pair<Uri?, () -> Unit> {
    val ctx = LocalContext.current
    var root by remember { mutableStateOf(prefs(ctx).getString(key, null)?.let(Uri::parse)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            root = u; prefs(ctx).edit().putString(key, u.toString()).apply()
        }
    }
    return root to { pick.launch(null) }
}

// ---------------- Fichiers ----------------

@Composable
fun FilesScreen() {
    val ctx = LocalContext.current
    val (root, pickRoot) = rememberTreeAccess("files_root")
    val stack = remember { mutableStateListOf<DocumentFile>() }
    var refresh by remember { mutableIntStateOf(0) }
    var sortBy by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<DocumentFile?>(null) }
    LaunchedEffect(root) { stack.clear(); root?.let { DocumentFile.fromTreeUri(ctx, it) }?.let { stack.add(it) } }
    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    if (root == null || stack.isEmpty()) {
        ToolColumn {
            Text("Choisis un dossier à explorer (ex : « Download » ou la racine du stockage interne).")
            Button(onClick = pickRoot) { Text("📂 Choisir un dossier") }
        }
        return
    }
    val cur = stack.last()
    val files = remember(cur, refresh, sortBy) {
        (cur.listFiles().toList()).sortedWith(compareBy<DocumentFile> { !it.isDirectory }.then(when (sortBy) {
            1 -> compareByDescending { it.length() }; 2 -> compareByDescending { it.lastModified() }; else -> compareBy { it.name?.lowercase() }
        }))
    }
    confirmDelete?.let { f ->
        AlertDialog({ confirmDelete = null }, title = { Text("Supprimer ?") }, text = { Text(f.name ?: "") },
            confirmButton = { TextButton(onClick = { f.delete(); confirmDelete = null; refresh++ }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stack.joinToString(" / ") { it.name ?: "…" }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = pickRoot) { Text("Changer") }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Nom", "Taille", "Date").forEachIndexed { i, l -> FilterChip(sortBy == i, { sortBy = i }, label = { Text(l) }) }
            Text("${files.size} éléments", Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.outline)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (stack.size > 1) item { ListItem(headlineContent = { Text("⬆️ ..") }, modifier = Modifier.clickable { stack.removeAt(stack.lastIndex) }) }
            items(files, key = { it.uri.toString() }) { f ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    leadingContent = { Text(iconFor(f), fontSize = 24.sp) },
                    headlineContent = { Text(f.name ?: "?", maxLines = 1) },
                    supportingContent = { Text((if (f.isDirectory) "Dossier" else humanSize(f.length())) + " • " + Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))) },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { menu = true }) { Text("⋮") }
                            DropdownMenu(menu, { menu = false }) {
                                if (!f.isDirectory) DropdownMenuItem({ Text("Partager") }, {
                                    menu = false; ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(f.type ?: "*/*").putExtra(Intent.EXTRA_STREAM, f.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                                })
                                DropdownMenuItem({ Text("Supprimer", color = MaterialTheme.colorScheme.error) }, { menu = false; confirmDelete = f })
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        if (f.isDirectory) stack.add(f) else runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    })
            }
        }
    }
}

// ---------------- Nettoyeur ----------------

@Composable
fun CleanerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (root, pickRoot) = rememberTreeAccess("cleaner_root")
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableIntStateOf(0) }
    var big by remember { mutableStateOf(listOf<DocumentFile>()) }
    var dups by remember { mutableStateOf(listOf<List<DocumentFile>>()) }
    var empties by remember { mutableStateOf(listOf<DocumentFile>()) }
    var minMb by remember { mutableIntStateOf(50) }
    val selected = remember { mutableStateListOf<Uri>() }
    var tab by remember { mutableIntStateOf(0) }
    var freed by remember { mutableLongStateOf(0L) }
    fun scan() {
        val r = root?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return
        scanning = true; scanned = 0; selected.clear()
        scope.launch {
            val all = mutableListOf<DocumentFile>(); val emptyDirs = mutableListOf<DocumentFile>()
            withContext(Dispatchers.IO) {
                fun walk(d: DocumentFile, depth: Int) {
                    if (depth > 12) return
                    val ch = d.listFiles(); if (ch.isEmpty() && depth > 0) emptyDirs += d
                    ch.forEach { if (it.isDirectory) walk(it, depth + 1) else { all += it; scanned++ } }
                }
                walk(r, 0)
            }
            big = all.filter { it.length() >= minMb * 1048576L }.sortedByDescending { it.length() }
            empties = emptyDirs
            // doublons : même taille puis même empreinte (premiers 64 Ko + taille)
            dups = withContext(Dispatchers.IO) {
                all.filter { it.length() > 10240 }.groupBy { it.length() }.values.filter { it.size > 1 }.flatMap { same ->
                    same.groupBy { f -> runCatching {
                        ctx.contentResolver.openInputStream(f.uri)?.use { s -> val b = ByteArray(65536); val n = s.read(b); MessageDigest.getInstance("MD5").digest(b.copyOf(maxOf(n, 0))).joinToString("") { "%02x".format(it) } }
                    }.getOrNull() }.filterKeys { it != null }.values.filter { it.size > 1 }
                }.sortedByDescending { it.first().length() * (it.size - 1) }
            }
            scanning = false
        }
    }
    ToolColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = pickRoot) { Text(if (root == null) "📂 Choisir un dossier" else "Changer de dossier") }
            if (root != null) Button(onClick = { scan() }, enabled = !scanning) { Text("🔍 Analyser") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Gros fichiers ≥ "); listOf(10, 50, 100, 500).forEach { m -> FilterChip(minMb == m, { minMb = m }, label = { Text("$m Mo") }, modifier = Modifier.padding(horizontal = 2.dp)) } }
        if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("$scanned fichiers analysés…") }
        if (freed > 0) Text("✅ ${humanSize(freed)} libérés", color = Color(0xFF43A047))
        if (!scanning && (big.isNotEmpty() || dups.isNotEmpty() || empties.isNotEmpty())) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Gros (${big.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Doublons (${dups.size})") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Vides (${empties.size})") })
            }
            @Composable fun Row1(f: DocumentFile, sub: String = "") = Row(Modifier.fillMaxWidth().clickable { if (f.uri in selected) selected.remove(f.uri) else selected.add(f.uri) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.uri in selected, null)
                Column(Modifier.weight(1f)) { Text(f.name ?: "?", maxLines = 1); if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                Text(humanSize(f.length()))
            }
            when (tab) {
                0 -> big.forEach { Row1(it) }
                1 -> {
                    TextButton(onClick = { selected.clear(); dups.forEach { g -> g.drop(1).forEach { selected.add(it.uri) } } }) { Text("Sélectionner toutes les copies (garder 1 original)") }
                    dups.forEach { g -> Text("${g.size} copies de ${humanSize(g.first().length())}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)); g.forEach { Row1(it, it.parentFile?.name ?: "") } }
                }
                else -> { TextButton(onClick = { selected.clear(); empties.forEach { selected.add(it.uri) } }) { Text("Tout sélectionner") }; empties.forEach { Row1(it) } }
            }
            if (selected.isNotEmpty()) Button(onClick = {
                val all = big + dups.flatten() + empties
                all.filter { it.uri in selected }.distinctBy { it.uri }.forEach { f -> val s = f.length(); if (f.delete()) freed += s }
                big = big.filter { it.uri !in selected }; dups = dups.map { g -> g.filter { it.uri !in selected } }.filter { it.size > 1 }; empties = empties.filter { it.uri !in selected }
                selected.clear()
            }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("🗑️ Supprimer ${selected.size} élément(s)")
            }
        }
        Text("Rien n'est supprimé sans ta confirmation. Vérifie bien avant !", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Applis & permissions ----------------

private val sensitivePerms = linkedMapOf(
    "android.permission.CAMERA" to "📷 Caméra", "android.permission.RECORD_AUDIO" to "🎤 Micro", "android.permission.ACCESS_FINE_LOCATION" to "📍 Position précise",
    "android.permission.ACCESS_COARSE_LOCATION" to "📍 Position approx.", "android.permission.ACCESS_BACKGROUND_LOCATION" to "📍 Position en arrière-plan",
    "android.permission.READ_CONTACTS" to "👥 Contacts", "android.permission.READ_SMS" to "💬 SMS", "android.permission.READ_CALL_LOG" to "📞 Journal d'appels",
    "android.permission.READ_PHONE_STATE" to "📱 Infos téléphone", "android.permission.READ_CALENDAR" to "📅 Agenda", "android.permission.BODY_SENSORS" to "❤️ Capteurs corporels",
    "android.permission.READ_MEDIA_IMAGES" to "🖼️ Photos", "android.permission.READ_EXTERNAL_STORAGE" to "💾 Stockage", "android.permission.ACTIVITY_RECOGNITION" to "🏃 Activité physique")

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var showSystem by remember { mutableStateOf(false) }
    var filterPerm by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    val apps = remember {
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).map { pi ->
            val granted = pi.requestedPermissions?.filterIndexed { i, p -> (pi.requestedPermissionsFlags?.get(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 && p in sensitivePerms } ?: emptyList()
            Triple(pi, pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName, granted)
        }.sortedBy { it.second.lowercase() }
    }
    val shown = apps.filter { (pi, name, perms) ->
        (showSystem || (pi.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0) && (filterPerm == null || filterPerm in perms) && name.contains(q, true)
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("🔎 Appli") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showSystem, { showSystem = it }); Text("Applis système"); Spacer(Modifier.weight(1f)); Text("${shown.size} applis", color = MaterialTheme.colorScheme.outline) }
            Dropdown("Filtrer par permission accordée", listOf<String?>(null) + sensitivePerms.keys, filterPerm, { it?.let { k -> sensitivePerms[k] } ?: "Toutes" }) { filterPerm = it }
        }
        items(shown, key = { it.first.packageName }) { (pi, name, perms) ->
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text(if (perms.isEmpty()) "Aucune permission sensible" else perms.joinToString(" ") { sensitivePerms[it]!!.take(2) } + "  " + "v${pi.versionName ?: "?"}") },
                modifier = Modifier.clickable { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${pi.packageName}"))) })
        }
    }
}

// ---------------- Test de l'écran ----------------

@Composable
fun ScreenTestScreen() {
    var mode by remember { mutableIntStateOf(-1) }
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black, Color.Gray, Color.Cyan, Color.Magenta, Color.Yellow)
    if (mode in colors.indices) {
        androidx.compose.ui.window.Dialog({ mode = -1 }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(colors[mode]).clickable { mode = if (mode + 1 < colors.size) mode + 1 else -1 })
        }
    }
    if (mode == 100) {
        val paths = remember { mutableStateListOf<List<Offset>>() }
        val cur = remember { mutableStateListOf<Offset>() }
        androidx.compose.ui.window.Dialog({ mode = -1 }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(onDragStart = { cur.clear(); cur.add(it) }, onDragEnd = { paths.add(cur.toList()); cur.clear() }) { c, _ -> cur.add(c.position) }
                }) {
                    (paths + listOf(cur.toList())).forEach { pts ->
                        if (pts.size > 1) drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }, Color(0xFF1E88E5), style = Stroke(10f))
                    }
                }
                Text("Dessine partout : une ligne qui se coupe = zone tactile morte. Retour pour quitter.", Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.Gray)
            }
        }
    }
    val dm = LocalContext.current.resources.displayMetrics
    ToolColumn {
        Button(onClick = { mode = 0 }, Modifier.fillMaxWidth()) { Text("🎨 Pixels morts : couleurs plein écran") }
        Text("Touche l'écran pour passer à la couleur suivante. Cherche un point qui ne change pas de couleur.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { mode = 100 }, Modifier.fillMaxWidth()) { Text("✍️ Test tactile") }
        ResultCard {
            Text("Résolution : ${dm.widthPixels} × ${dm.heightPixels}")
            Text("Densité : ${dm.densityDpi} dpi (×${dm.density})")
            Text("Diagonale ≈ ${"%.1f".format(Math.sqrt(Math.pow(dm.widthPixels / dm.xdpi.toDouble(), 2.0) + Math.pow(dm.heightPixels / dm.ydpi.toDouble(), 2.0)))}\"")
            val rr = (LocalContext.current.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0)?.refreshRate
            Text("Fréquence : ${rr?.let { "%.0f Hz".format(it) } ?: "?"}")
        }
    }
}

// ---------------- Capteurs ----------------

@Composable
fun SensorsScreen() {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensors = remember { sm.getSensorList(Sensor.TYPE_ALL).distinctBy { it.type } }
    val values = remember { mutableStateMapOf<Int, FloatArray>() }
    DisposableEffect(Unit) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values[e.sensor.type] = e.values.clone() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensors.forEach { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(l) }
    }
    val names = mapOf(Sensor.TYPE_ACCELEROMETER to "Accéléromètre (m/s²)", Sensor.TYPE_GYROSCOPE to "Gyroscope (rad/s)", Sensor.TYPE_MAGNETIC_FIELD to "Magnétomètre (µT)",
        Sensor.TYPE_LIGHT to "Luminosité (lux)", Sensor.TYPE_PROXIMITY to "Proximité (cm)", Sensor.TYPE_PRESSURE to "Baromètre (hPa)", Sensor.TYPE_GRAVITY to "Gravité",
        Sensor.TYPE_LINEAR_ACCELERATION to "Accélération linéaire", Sensor.TYPE_ROTATION_VECTOR to "Vecteur de rotation", Sensor.TYPE_STEP_COUNTER to "Compteur de pas",
        Sensor.TYPE_AMBIENT_TEMPERATURE to "Température (°C)", Sensor.TYPE_RELATIVE_HUMIDITY to "Humidité (%)", Sensor.TYPE_HEART_RATE to "Fréquence cardiaque")
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("${sensors.size} capteurs détectés", style = MaterialTheme.typography.titleMedium) }
        items(sensors, key = { it.type }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(names[s.type] ?: s.stringType.substringAfterLast('.'), style = MaterialTheme.typography.titleSmall)
                    Text("${s.name} • ${s.vendor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(values[s.type]?.take(3)?.joinToString("   ") { "%.2f".format(it) } ?: "— (bouge le téléphone)", fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ---------------- Sauvegarde ----------------

@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        u ?: return@rememberLauncherForActivityResult
        val o = JSONObject()
        prefs(ctx).all.forEach { (k, v) ->
            if (k.startsWith("vault")) return@forEach // le coffre reste chiffré sur l'appareil
            o.put(k, JSONObject().put("t", when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "s"; else -> "str" })
                .put("v", if (v is Set<*>) JSONArray(v.toList()) else v))
        }
        runCatching { ctx.contentResolver.openOutputStream(u)!!.use { it.write(o.toString(2).toByteArray()) } }
            .onSuccess { msg = "✅ ${o.length()} réglages/données exportés" }.onFailure { msg = "Erreur d'export" }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u ?: return@rememberLauncherForActivityResult
        runCatching {
            val o = JSONObject(ctx.contentResolver.openInputStream(u)!!.bufferedReader().readText())
            val e = prefs(ctx).edit()
            o.keys().forEach { k ->
                val x = o.getJSONObject(k)
                when (x.getString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v")); "i" -> e.putInt(k, x.getInt("v")); "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat()); "s" -> e.putStringSet(k, x.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    else -> e.putString(k, x.getString("v"))
                }
            }
            e.apply(); o.length()
        }.onSuccess { msg = "✅ $it éléments restaurés. Relance l'appli pour tout voir." }.onFailure { msg = "Fichier invalide" }
    }
    ToolColumn {
        Text("Sauvegarde toutes tes données (notes, listes, budget, habitudes, compteurs, réglages…) dans un fichier, par exemple sur Google Drive.")
        Button(onClick = { export.launch("toolbox-sauvegarde-${java.time.LocalDate.now()}.json") }, Modifier.fillMaxWidth()) { Text("💾 Exporter une sauvegarde") }
        OutlinedButton(onClick = { import.launch(arrayOf("application/json", "*/*")) }, Modifier.fillMaxWidth()) { Text("📥 Restaurer une sauvegarde") }
        msg?.let { Text(it) }
        Text("Le coffre à mots de passe n'est jamais exporté (il est chiffré avec une clé propre à ce téléphone). Les enregistrements audio et photos ne sont pas inclus.", style = MaterialTheme.typography.bodySmall)
    }
}
