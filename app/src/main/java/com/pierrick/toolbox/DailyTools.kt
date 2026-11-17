package com.pierrick.toolbox

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

fun vibrate(ctx: Context, ms: Long = 40) {
    val v = if (Build.VERSION.SDK_INT >= 31)
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}

private fun fmtMs(ms: Long): String {
    val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val cs = ms / 10 % 100
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d.%02d".format(m, s, cs)
}

// ---------------- Chrono / Minuteur / Pomodoro ----------------

@Composable
fun ChronoScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Chrono") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Minuteur") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Pomodoro") })
        }
        when (tab) { 0 -> Stopwatch(); 1 -> Countdown(); else -> Pomodoro() }
    }
}

@Composable
private fun Stopwatch() {
    var running by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var laps by remember { mutableStateOf(listOf<Long>()) }
    LaunchedEffect(running) {
        val start = System.currentTimeMillis() - elapsed
        while (running) { elapsed = System.currentTimeMillis() - start; delay(16) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(fmtMs(elapsed), fontSize = 60.sp, modifier = Modifier.padding(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { running = !running }) { Text(if (running) "Pause" else "Démarrer") }
            OutlinedButton(onClick = { if (running) laps = laps + elapsed else { elapsed = 0; laps = emptyList() } }) {
                Text(if (running) "Tour" else "Réinitialiser")
            }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            items(laps.indices.reversed().toList()) { i ->
                val split = laps[i] - (if (i > 0) laps[i - 1] else 0)
                ListItem(headlineContent = { Text("Tour ${i + 1}") },
                    supportingContent = { Text("+${fmtMs(split)}") }, trailingContent = { Text(fmtMs(laps[i])) })
            }
        }
    }
}

@Composable
private fun TimerCore(totalMs: Long, running: Boolean, onFinish: () -> Unit, onTick: (Long) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(running, totalMs) {
        if (!running) return@LaunchedEffect
        val end = System.currentTimeMillis() + totalMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) {
                onTick(0)
                runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500) }
                vibrate(ctx, 800)
                onFinish(); break
            }
            onTick(left); delay(50)
        }
    }
}

@Composable
private fun Countdown() {
    var min by remember { mutableStateOf("5") }
    var sec by remember { mutableStateOf("0") }
    var left by remember { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var startFrom by remember { mutableLongStateOf(0L) }
    TimerCore(startFrom, running, { running = false }) { left = it }
    ToolColumn {
        if (!running && left == 0L) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(min, { min = it }, Modifier.weight(1f), label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(sec, { sec = it }, Modifier.weight(1f), label = { Text("Secondes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5, 10, 15).forEach { m -> AssistChip(onClick = { min = "$m"; sec = "0" }, label = { Text("$m min") }) }
            }
        } else Text(fmtMs(left), fontSize = 64.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (running) { running = false } else {
                    startFrom = if (left > 0) left else ((min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)) * 1000
                    if (startFrom > 0) running = true
                }
            }) { Text(if (running) "Pause" else if (left > 0) "Reprendre" else "Démarrer") }
            OutlinedButton(onClick = { running = false; left = 0 }) { Text("Réinitialiser") }
        }
    }
}

@Composable
private fun Pomodoro() {
    var work by remember { mutableStateOf(true) }
    var cycle by remember { mutableIntStateOf(1) }
    var running by remember { mutableStateOf(false) }
    var left by remember { mutableLongStateOf(25 * 60_000L) }
    var startFrom by remember { mutableLongStateOf(left) }
    TimerCore(startFrom, running, {
        running = false
        if (work) { work = false; left = if (cycle % 4 == 0) 15 * 60_000L else 5 * 60_000L }
        else { work = true; cycle++; left = 25 * 60_000L }
    }) { if (running) left = it }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (work) "🍅 Travail — cycle $cycle" else "☕ Pause", style = MaterialTheme.typography.headlineSmall)
        Text(fmtMs(left).substringBefore('.'), fontSize = 72.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (running) running = false else { startFrom = left; running = true } }) { Text(if (running) "Pause" else "Démarrer") }
            OutlinedButton(onClick = { running = false; work = true; cycle = 1; left = 25 * 60_000L }) { Text("Réinitialiser") }
        }
        Text("25 min travail • 5 min pause • 15 min toutes les 4 sessions", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Notes & listes ----------------

private data class Note(val id: Long, val title: String, val body: String, val checklist: Boolean, val items: List<Pair<String, Boolean>>)

private fun loadNotes(ctx: Context): List<Note> = runCatching {
    val a = JSONArray(prefs(ctx).getString("notes", "[]"))
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i); val it = o.optJSONArray("items") ?: JSONArray()
        Note(o.getLong("id"), o.getString("title"), o.optString("body"), o.optBoolean("check"),
            (0 until it.length()).map { j -> it.getJSONObject(j).let { x -> x.getString("t") to x.getBoolean("d") } })
    }
}.getOrDefault(emptyList())

private fun saveNotes(ctx: Context, notes: List<Note>) {
    val a = JSONArray()
    notes.forEach { n ->
        a.put(JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("check", n.checklist)
            .put("items", JSONArray().apply { n.items.forEach { (t, d) -> put(JSONObject().put("t", t).put("d", d)) } }))
    }
    prefs(ctx).edit().putString("notes", a.toString()).apply()
}

@Composable
fun NotesScreen() {
    val ctx = LocalContext.current
    var notes by remember { mutableStateOf(loadNotes(ctx)) }
    var editing by remember { mutableStateOf<Long?>(null) }
    fun update(n: List<Note>) { notes = n; saveNotes(ctx, n) }

    val cur = notes.find { it.id == editing }
    if (cur != null) {
        androidx.activity.compose.BackHandler { editing = null }
        NoteEditor(cur, onChange = { n -> update(notes.map { if (it.id == n.id) n else it }) },
            onDelete = { update(notes.filter { it.id != cur.id }); editing = null }, onClose = { editing = null })
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { val n = Note(System.currentTimeMillis(), "", "", false, emptyList()); update(listOf(n) + notes); editing = n.id }) { Text("+ Note") }
            Button(onClick = { val n = Note(System.currentTimeMillis(), "Liste", "", true, emptyList()); update(listOf(n) + notes); editing = n.id }) { Text("+ Liste / Courses") }
        }
        if (notes.isEmpty()) Text("Aucune note pour l'instant.", color = MaterialTheme.colorScheme.outline)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id }) { n ->
                Card(Modifier.fillMaxWidth().clickable { editing = n.id }) {
                    Column(Modifier.padding(12.dp)) {
                        Text((if (n.checklist) "☑️ " else "📝 ") + n.title.ifBlank { "Sans titre" }, style = MaterialTheme.typography.titleMedium)
                        val sub = if (n.checklist) "${n.items.count { it.second }}/${n.items.size} cochés" else n.body.take(80)
                        if (sub.isNotBlank()) Text(sub, color = MaterialTheme.colorScheme.outline, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteEditor(n: Note, onChange: (Note) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var newItem by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text("← Notes") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
        }
        OutlinedTextField(n.title, { onChange(n.copy(title = it)) }, Modifier.fillMaxWidth(), label = { Text("Titre") }, singleLine = true)
        if (!n.checklist) {
            OutlinedTextField(n.body, { onChange(n.copy(body = it)) }, Modifier.fillMaxWidth().weight(1f), label = { Text("Note") })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newItem, { newItem = it }, Modifier.weight(1f), label = { Text("Nouvel élément") }, singleLine = true)
                TextButton(onClick = { if (newItem.isNotBlank()) { onChange(n.copy(items = n.items + (newItem.trim() to false))); newItem = "" } }) { Text("Ajouter") }
            }
            if (n.items.any { it.second }) TextButton(onClick = { onChange(n.copy(items = n.items.filter { !it.second })) }) { Text("Retirer les éléments cochés") }
            LazyColumn {
                items(n.items.indices.toList()) { i ->
                    val (t, d) = n.items[i]
                    Row(Modifier.fillMaxWidth().clickable {
                        onChange(n.copy(items = n.items.mapIndexed { j, p -> if (j == i) p.first to !p.second else p }))
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d, null)
                        Text(t, Modifier.weight(1f), textDecoration = if (d) TextDecoration.LineThrough else null)
                        TextButton(onClick = { onChange(n.copy(items = n.items.filterIndexed { j, _ -> j != i })) }) { Text("✕") }
                    }
                }
            }
        }
    }
}

// ---------------- Compteur ----------------

@Composable
fun CounterScreen() {
    val ctx = LocalContext.current
    var counters by remember {
        mutableStateOf(runCatching {
            val o = JSONObject(prefs(ctx).getString("counters", "{}")!!)
            o.keys().asSequence().map { it to o.getInt(it) }.toList()
        }.getOrDefault(emptyList()).ifEmpty { listOf("Compteur" to 0) })
    }
    fun save(c: List<Pair<String, Int>>) {
        counters = c
        prefs(ctx).edit().putString("counters", JSONObject().apply { c.forEach { put(it.first, it.second) } }.toString()).apply()
    }
    var newName by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(counters.indices.toList()) { i ->
            val (name, v) = counters[i]
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { save(counters.mapIndexed { j, p -> if (j == i) p.first to 0 else p }) }) { Text("RAZ") }
                        if (counters.size > 1) TextButton(onClick = { save(counters.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledTonalButton(onClick = { save(counters.mapIndexed { j, p -> if (j == i) p.first to p.second - 1 else p }); vibrate(ctx) },
                            Modifier.size(72.dp)) { Text("−", fontSize = 28.sp) }
                        Text("$v", fontSize = 56.sp, modifier = Modifier.widthIn(min = 100.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Button(onClick = { save(counters.mapIndexed { j, p -> if (j == i) p.first to p.second + 1 else p }); vibrate(ctx) },
                            Modifier.size(72.dp)) { Text("+", fontSize = 28.sp) }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("Nom du nouveau compteur") }, singleLine = true)
                TextButton(onClick = {
                    val nm = newName.trim().ifBlank { "Compteur ${counters.size + 1}" }
                    if (counters.none { it.first == nm }) save(counters + (nm to 0)); newName = ""
                }) { Text("Ajouter") }
            }
        }
    }
}

// ---------------- Aléatoire ----------------

@Composable
fun RandomScreen() {
    val ctx = LocalContext.current
    var coin by remember { mutableStateOf<String?>(null) }
    var dice by remember { mutableIntStateOf(1) }
    var faces by remember { mutableIntStateOf(6) }
    var diceRes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var min by remember { mutableStateOf("1") }
    var max by remember { mutableStateOf("100") }
    var num by remember { mutableStateOf<Long?>(null) }
    var choices by remember { mutableStateOf("Pizza\nSushi\nBurger") }
    var pick by remember { mutableStateOf<String?>(null) }
    ToolColumn {
        Text("Pile ou face", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { coin = if (Random.nextBoolean()) "🪙 Pile" else "👑 Face"; vibrate(ctx) }) { Text("Lancer") }
            coin?.let { Text(it, fontSize = 28.sp) }
        }
        HorizontalDivider()
        Text("Dés", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(4, 6, 8, 10, 12, 20, 100).forEach { f -> FilterChip(faces == f, { faces = f }, label = { Text("d$f") }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nombre de dés : $dice", Modifier.weight(1f))
            TextButton(onClick = { if (dice > 1) dice-- }) { Text("−") }
            TextButton(onClick = { if (dice < 10) dice++ }) { Text("+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { diceRes = List(dice) { Random.nextInt(1, faces + 1) }; vibrate(ctx) }) { Text("🎲 Lancer") }
            if (diceRes.isNotEmpty()) Text(diceRes.joinToString(" + ") + if (diceRes.size > 1) " = ${diceRes.sum()}" else "", fontSize = 22.sp)
        }
        HorizontalDivider()
        Text("Nombre aléatoire", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(min, { min = it }, Modifier.weight(1f), label = { Text("Min") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(max, { max = it }, Modifier.weight(1f), label = { Text("Max") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = {
                val a = min.toLongOrNull(); val b = max.toLongOrNull()
                if (a != null && b != null && a <= b) num = Random.nextLong(a, b + 1)
            }) { Text("Go") }
        }
        num?.let { Text("$it", fontSize = 40.sp, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("Choisir pour moi (un choix par ligne)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(choices, { choices = it }, Modifier.fillMaxWidth(), minLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { pick = choices.lines().map { it.trim() }.filter { it.isNotEmpty() }.randomOrNull(); vibrate(ctx) }) { Text("🎯 Choisir") }
            pick?.let { Text(it, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
