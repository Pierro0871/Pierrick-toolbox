package com.pierrick.toolbox

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val FR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
fun isoToFr(s: String): String = runCatching { LocalDate.parse(s).format(FR_DATE) }.getOrDefault(s)

fun pickDate(ctx: Context, initial: LocalDate = LocalDate.now(), onPick: (LocalDate) -> Unit) =
    DatePickerDialog(ctx, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()

fun pickTime(ctx: Context, initial: LocalTime = LocalTime.now(), onPick: (LocalTime) -> Unit) =
    TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()

@Composable
fun DateButton(label: String, iso: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        pickDate(ctx, runCatching { LocalDate.parse(iso) }.getOrDefault(LocalDate.now())) { onPick(it.toString()) }
    }, modifier) { Text("$label : " + if (iso.isBlank()) "—" else isoToFr(iso)) }
}

@Composable
fun TimeButton(label: String, hhmm: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        pickTime(ctx, runCatching { LocalTime.parse(hhmm) }.getOrDefault(LocalTime.now())) { onPick(it.toString().take(5)) }
    }, modifier) { Text("$label : ${hhmm.ifBlank { "—" }}") }
}

fun shareText(ctx: Context, text: String) =
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 1600): android.graphics.Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
}.getOrNull()

// ---------------- Listes d'enregistrements génériques ----------------

enum class FType { TEXT, MULTI, NUM, DATE, TIME, IMAGE, CHOICE }
data class Field(val key: String, val label: String, val type: FType = FType.TEXT, val choices: List<String> = emptyList())

/**
 * Écran générique « liste d'éléments » : ajout / modification / suppression,
 * stockage JSON. Utilisé par inventaire, garanties, prêts, voiture, etc.
 */
@Composable
fun RecordsScreen(
    key: String,
    fields: List<Field>,
    title: (JSONObject) -> String,
    subtitle: (JSONObject) -> String = { "" },
    trailing: (JSONObject) -> String = { "" },
    sort: (List<JSONObject>) -> List<JSONObject> = { it.reversed() },
    addLabel: String = "+ Ajouter",
    header: @Composable ColumnScope.(List<JSONObject>) -> Unit = {},
    onSaved: (JSONObject) -> Unit = {},
) {
    val (list, save) = rememberStoredList(key)
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    editing?.let { e ->
        RecordEditor(e, fields, onCancel = { editing = null }, onDelete = if (e.has("id") && list.any { it.getLong("id") == e.getLong("id") }) {
            { save(list.filter { it.getLong("id") != e.getLong("id") }); editing = null }
        } else null) { n ->
            save(if (list.any { it.getLong("id") == n.getLong("id") }) list.map { if (it.getLong("id") == n.getLong("id")) n else it } else list + n)
            onSaved(n); editing = null
        }
        return
    }
    val shown = sort(list).filter { query.isBlank() || it.toString().contains(query, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                header(list)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = JSONObject().put("id", System.currentTimeMillis()) }) { Text(addLabel) }
                    if (list.size > 5) OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("🔎 Filtrer") }, singleLine = true)
                }
                if (list.isEmpty()) Text("Rien pour l'instant.", color = MaterialTheme.colorScheme.outline)
            }
        }
        items(shown, key = { it.getLong("id") }) { r ->
            ElevatedCard(Modifier.fillMaxWidth().clickable { editing = r }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    fields.firstOrNull { it.type == FType.IMAGE }?.let { f ->
                        r.optString(f.key).takeIf { it.isNotBlank() }?.let { u -> Thumb(Uri.parse(u), Modifier.size(52.dp).padding(end = 10.dp)) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title(r), style = MaterialTheme.typography.titleMedium)
                        subtitle(r).takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.outline, maxLines = 3) }
                    }
                    trailing(r).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
fun Thumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp = remember(uri) { loadBitmap(ctx, uri, 300)?.asImageBitmap() }
    bmp?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
}

@Composable
fun RecordEditor(initial: JSONObject, fields: List<Field>, onCancel: () -> Unit, onDelete: (() -> Unit)?, onSave: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, initial.optString(it.key)) } } }
    var imgField by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = imgField
        if (uri != null && k != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            values[k] = uri.toString()
        }
    }
    androidx.activity.compose.BackHandler { onCancel() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.forEach { f ->
            val v = values[f.key] ?: ""
            when (f.type) {
                FType.TEXT -> OutlinedTextField(v, { values[f.key] = it }, Modifier.fillMaxWidth(), label = { Text(f.label) }, singleLine = true)
                FType.MULTI -> OutlinedTextField(v, { values[f.key] = it }, Modifier.fillMaxWidth(), label = { Text(f.label) }, minLines = 3)
                FType.NUM -> OutlinedTextField(v, { values[f.key] = it }, Modifier.fillMaxWidth(), label = { Text(f.label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                FType.DATE -> DateButton(f.label, v, Modifier.fillMaxWidth()) { values[f.key] = it }
                FType.TIME -> TimeButton(f.label, v, Modifier.fillMaxWidth()) { values[f.key] = it }
                FType.CHOICE -> Dropdown(f.label, f.choices, v.ifBlank { f.choices.first() }, { it }) { values[f.key] = it }
                FType.IMAGE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.isNotBlank()) Thumb(Uri.parse(v), Modifier.size(80.dp))
                    OutlinedButton(onClick = { imgField = f.key; picker.launch(arrayOf("image/*", "application/pdf")) }) { Text(if (v.isBlank()) "📎 ${f.label}" else "Changer") }
                    if (v.isNotBlank()) TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }) { Text("Ouvrir") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val o = JSONObject(initial.toString())
                fields.forEach { f -> o.put(f.key, if (f.type == FType.CHOICE && values[f.key].isNullOrBlank()) f.choices.first() else values[f.key] ?: "") }
                onSave(o)
            }) { Text("Enregistrer") }
            OutlinedButton(onClick = onCancel) { Text("Annuler") }
            Spacer(Modifier.weight(1f))
            onDelete?.let { TextButton(onClick = it) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } }
        }
    }
}

// ---------------- Graphique simple ----------------

@Composable
fun LineChart(values: List<Float>, modifier: Modifier = Modifier.fillMaxWidth().height(180.dp)) {
    val c = MaterialTheme.colorScheme.primary
    val g = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val mn = values.min(); val mx = values.max(); val range = (mx - mn).takeIf { it > 0 } ?: 1f
        for (i in 0..4) { val y = size.height * i / 4; drawLine(g, Offset(0f, y), Offset(size.width, y), 1f) }
        val dx = size.width / (values.size - 1)
        for (i in 1 until values.size) {
            drawLine(c, Offset((i - 1) * dx, size.height * (1 - (values[i - 1] - mn) / range)), Offset(i * dx, size.height * (1 - (values[i] - mn) / range)), 5f)
        }
        values.forEachIndexed { i, v -> drawCircle(c, 6f, Offset(i * dx, size.height * (1 - (v - mn) / range))) }
    }
}

@Composable
fun SimpleChecklist(key: String, defaults: List<String> = emptyList(), placeholder: String = "Nouvel élément") {
    val ctx = LocalContext.current
    val (list, save) = rememberStoredList(key)
    LaunchedEffect(Unit) {
        if (list.isEmpty() && defaults.isNotEmpty() && !prefs(ctx).getBoolean("${key}_init", false)) {
            save(defaults.mapIndexed { i, s -> JSONObject().put("id", System.nanoTime() + i).put("t", s).put("d", false) })
            prefs(ctx).edit().putBoolean("${key}_init", true).apply()
        }
    }
    var input by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text(placeholder) }, singleLine = true)
                TextButton(onClick = { if (input.isNotBlank()) { save(list + JSONObject().put("id", System.nanoTime()).put("t", input.trim()).put("d", false)); input = "" } }) { Text("Ajouter") }
            }
            Row {
                Text("${list.count { it.getBoolean("d") }}/${list.size}", Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { save(list.map { it.with("d", false) }) }) { Text("Tout décocher") }
            }
        }
        items(list, key = { it.getLong("id") }) { r ->
            Row(Modifier.fillMaxWidth().clickable { save(list.map { if (it.getLong("id") == r.getLong("id")) it.with("d", !r.getBoolean("d")) else it }) },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(r.getBoolean("d"), null)
                Text(r.getString("t"), Modifier.weight(1f))
                TextButton(onClick = { save(list.filter { it.getLong("id") != r.getLong("id") }) }) { Text("✕") }
            }
        }
    }
}
