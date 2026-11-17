package com.pierrick.toolbox

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ---------- stockage JSON générique ----------

fun loadList(ctx: Context, key: String): List<JSONObject> = runCatching {
    val a = JSONArray(prefs(ctx).getString(key, "[]")); (0 until a.length()).map { a.getJSONObject(it) }
}.getOrDefault(emptyList())

fun saveList(ctx: Context, key: String, l: List<JSONObject>) =
    prefs(ctx).edit().putString(key, JSONArray().apply { l.forEach { put(it) } }.toString()).apply()

/** État persistant : une liste d'objets JSON stockée sous une clé. */
@Composable
fun rememberStoredList(key: String): Pair<List<JSONObject>, (List<JSONObject>) -> Unit> {
    val ctx = LocalContext.current
    var l by remember { mutableStateOf(loadList(ctx, key)) }
    return l to { n -> l = n; saveList(ctx, key, n) }
}

fun JSONObject.with(k: String, v: Any?): JSONObject = JSONObject(toString()).put(k, v)

private val dFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val dtFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

// ---------------- Rappels ----------------

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            loadList(ctx, "reminders").forEach { scheduleReminder(ctx, it) }; return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("reminders", "Rappels", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val id = intent.getLongExtra("id", 0)
        nm.notify(id.toInt(), NotificationCompat.Builder(ctx, "reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("⏰ Rappel").setContentText(intent.getStringExtra("text"))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
        // rappel quotidien : on reprogramme pour demain
        val list = loadList(ctx, "reminders")
        val r = list.find { it.getLong("id") == id } ?: return
        if (r.optBoolean("daily")) {
            val next = r.with("at", r.getLong("at") + 86_400_000L)
            saveList(ctx, "reminders", list.map { if (it.getLong("id") == id) next else it })
            scheduleReminder(ctx, next)
        } else saveList(ctx, "reminders", list.map { if (it.getLong("id") == id) it.with("done", true) else it })
    }
}

private fun pi(ctx: Context, r: JSONObject) = PendingIntent.getBroadcast(
    ctx, r.getLong("id").toInt(),
    Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.getLong("id")).putExtra("text", r.getString("text")),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

fun scheduleReminder(ctx: Context, r: JSONObject) {
    if (r.optBoolean("done") || r.getLong("at") < System.currentTimeMillis()) return
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.getLong("at"), pi(ctx, r))
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.getLong("at"), pi(ctx, r))
}

fun cancelReminder(ctx: Context, r: JSONObject) =
    (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx, r))

@Composable
fun RemindersScreen() {
    val ctx = LocalContext.current
    val (list, save) = rememberStoredList("reminders")
    val notifReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33 && !hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)) notifReq.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val now = LocalDateTime.now().plusMinutes(10)
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(now.format(dFmt)) }
    var time by remember { mutableStateOf(now.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var daily by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Me rappeler de…") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, Modifier.weight(1f), label = { Text("Date jj/mm/aaaa") }, singleLine = true)
                OutlinedTextField(time, { time = it }, Modifier.weight(1f), label = { Text("Heure hh:mm") }, singleLine = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(daily, { daily = it }); Text("Tous les jours", Modifier.weight(1f))
                Button(onClick = {
                    val at = runCatching { LocalDateTime.parse("$date $time", dtFmt) }.getOrNull()
                    err = when {
                        text.isBlank() -> "Écris un texte"
                        at == null -> "Date ou heure invalide"
                        at.isBefore(LocalDateTime.now()) -> "Cette date est passée"
                        else -> {
                            val r = JSONObject().put("id", System.currentTimeMillis() % 1_000_000_000).put("text", text.trim())
                                .put("at", at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).put("daily", daily)
                            save((list + r).sortedBy { it.getLong("at") }); scheduleReminder(ctx, r); text = ""; null
                        }
                    }
                }) { Text("Ajouter") }
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (Build.VERSION.SDK_INT >= 31 && !(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms())
                TextButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }) {
                    Text("⚠️ Autoriser les alarmes exactes pour des rappels à l'heure pile")
                }
        }
        items(list, key = { it.getLong("id") }) { r ->
            val done = r.optBoolean("done")
            val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(r.getLong("at")), ZoneId.systemDefault())
            ListItem(
                headlineContent = { Text(r.getString("text"), textDecoration = if (done) TextDecoration.LineThrough else null) },
                supportingContent = { Text(at.format(dtFmt) + if (r.optBoolean("daily")) " • quotidien" else "") },
                trailingContent = { TextButton(onClick = { cancelReminder(ctx, r); save(list - r) }) { Text("✕") } }
            )
        }
    }
}

// ---------------- Comptes à rebours d'événements ----------------

@Composable
fun EventsScreen() {
    val (list, save) = rememberStoredList("events")
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var yearly by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1.3f), label = { Text("Événement") }, singleLine = true)
                OutlinedTextField(date, { date = it }, Modifier.weight(1f), label = { Text("jj/mm/aaaa") }, singleLine = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(yearly, { yearly = it }); Text("Chaque année (anniversaire…)", Modifier.weight(1f))
                Button(onClick = {
                    runCatching { LocalDate.parse(date.trim(), dFmt) }.getOrNull()?.let { d ->
                        if (name.isNotBlank()) { save(list + JSONObject().put("id", System.nanoTime()).put("name", name.trim()).put("date", d.toString()).put("yearly", yearly)); name = ""; date = "" }
                    }
                }) { Text("Ajouter") }
            }
        }
        val computed = list.map { e ->
            var d = LocalDate.parse(e.getString("date"))
            if (e.optBoolean("yearly")) { d = d.withYear(today.year); if (d.isBefore(today)) d = d.plusYears(1) }
            e to (d to ChronoUnit.DAYS.between(today, d))
        }.sortedBy { it.second.second.let { n -> if (n < 0) Long.MAX_VALUE else n } }
        items(computed, key = { it.first.getLong("id") }) { (e, p) ->
            val (d, days) = p
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.getString("name"), style = MaterialTheme.typography.titleMedium)
                        Text(d.format(dFmt), color = MaterialTheme.colorScheme.outline)
                    }
                    Text(when { days == 0L -> "🎉 Aujourd'hui"; days > 0 -> "J−$days"; else -> "il y a ${-days} j" },
                        fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { save(list - e) }) { Text("✕") }
                }
            }
        }
    }
}

// ---------------- Habitudes ----------------

@Composable
fun HabitsScreen() {
    val (list, save) = rememberStoredList("habits")
    var name by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Nouvelle habitude (💧 boire 2 L…)") }, singleLine = true)
                TextButton(onClick = { if (name.isNotBlank()) { save(list + JSONObject().put("id", System.nanoTime()).put("name", name.trim()).put("days", JSONArray())); name = "" } }) { Text("Ajouter") }
            }
        }
        items(list, key = { it.getLong("id") }) { h ->
            val done = h.getJSONArray("days").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            var streak = 0; var d = if (today.toString() in done) today else today.minusDays(1)
            while (d.toString() in done) { streak++; d = d.minusDays(1) }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("🔥 $streak j"); TextButton(onClick = { save(list - h) }) { Text("✕") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        days.forEach { day ->
                            val on = day.toString() in done
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.FRANCE), style = MaterialTheme.typography.labelSmall)
                                Box(Modifier.size(36.dp).clip(CircleShape)
                                    .background(if (on) Color(0xFF43A047) else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        val nd = if (on) done - day.toString() else done + day.toString()
                                        save(list.map { if (it.getLong("id") == h.getLong("id")) it.with("days", JSONArray(nd.toList())) else it })
                                    }, contentAlignment = Alignment.Center) { Text(if (on) "✓" else "${day.dayOfMonth}", color = if (on) Color.White else Color.Unspecified) }
                            }
                        }
                    }
                    Text("${done.size} jours au total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// ---------------- Budget ----------------

@Composable
fun BudgetScreen() {
    val (list, save) = rememberStoredList("budget")
    var month by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var label by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }
    val cats = listOf("🛒 Courses", "🏠 Logement", "🚗 Transport", "🍽️ Restos", "🎉 Loisirs", "💊 Santé", "👕 Shopping", "📱 Abonnements", "💰 Salaire", "📦 Autre")
    var cat by remember { mutableStateOf(cats[0]) }
    val key = month.toString().substring(0, 7)
    val monthItems = list.filter { it.getString("date").startsWith(key) }
    val inc = monthItems.filter { it.getDouble("amount") > 0 }.sumOf { it.getDouble("amount") }
    val exp = -monthItems.filter { it.getDouble("amount") < 0 }.sumOf { it.getDouble("amount") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.FRANCE)).replaceFirstChar { it.uppercase() },
                    Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
            }
            ResultCard {
                Text("Revenus : +${inc.fmt(2)} €", color = Color(0xFF43A047))
                Text("Dépenses : −${exp.fmt(2)} €", color = Color(0xFFE53935))
                Text("Solde : ${(inc - exp).fmt(2)} €", style = MaterialTheme.typography.headlineSmall)
            }
            monthItems.filter { it.getDouble("amount") < 0 }.groupBy { it.getString("cat") }
                .mapValues { e -> -e.value.sumOf { it.getDouble("amount") } }.entries.sortedByDescending { it.value }.forEach { (c, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator({ (v / exp).toFloat() }, Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text("${v.fmt(0)} €", style = MaterialTheme.typography.bodySmall)
                    }
                }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!income, { income = false }, label = { Text("Dépense") })
                FilterChip(income, { income = true; cat = "💰 Salaire" }, label = { Text("Revenu") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, Modifier.weight(1.4f), label = { Text("Libellé") }, singleLine = true)
                NumField(amount, "€", Modifier.weight(1f)) { amount = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("Catégorie", cats, cat, { it }, Modifier.weight(1f)) { cat = it }
                Button(onClick = {
                    amount.num()?.takeIf { it > 0 }?.let { a ->
                        val d = if (month.month == LocalDate.now().month && month.year == LocalDate.now().year) LocalDate.now() else month
                        save(list + JSONObject().put("id", System.nanoTime()).put("label", label.ifBlank { cat }).put("cat", cat)
                            .put("amount", if (income) a else -a).put("date", d.toString()))
                        label = ""; amount = ""
                    }
                }) { Text("Ajouter") }
            }
        }
        items(monthItems.sortedByDescending { it.getString("date") }, key = { it.getLong("id") }) { e ->
            val a = e.getDouble("amount")
            ListItem(
                headlineContent = { Text(e.getString("label")) },
                supportingContent = { Text("${e.getString("cat")} • ${LocalDate.parse(e.getString("date")).format(dFmt)}") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (a > 0) "+" else "") + a.fmt(2) + " €", color = if (a > 0) Color(0xFF43A047) else Color(0xFFE53935))
                        TextButton(onClick = { save(list - e) }) { Text("✕") }
                    }
                }
            )
        }
    }
}

// ---------------- Liste de courses ----------------

private val aisles = listOf("🥦 Fruits & légumes", "🥩 Viande & poisson", "🧀 Frais", "🥖 Boulangerie", "🥫 Épicerie", "🧊 Surgelés", "🥤 Boissons", "🧴 Hygiène & maison", "📦 Autre")

@Composable
fun ShoppingScreen() {
    val ctx = LocalContext.current
    val (list, save) = rememberStoredList("shopping")
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var aisle by remember { mutableStateOf(aisles[0]) }
    val history = remember(list) { (prefs(ctx).getStringSet("shop_hist", emptySet()) ?: emptySet()).toList().sorted() }
    fun add(n: String, a: String = aisle, q: String = qty) {
        if (n.isBlank()) return
        save(list + JSONObject().put("id", System.nanoTime()).put("name", n.trim()).put("qty", q.trim()).put("aisle", a).put("done", false))
        prefs(ctx).edit().putStringSet("shop_hist", history.toSet() + "${n.trim()}|$a").apply()
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1.5f), label = { Text("Article") }, singleLine = true)
                OutlinedTextField(qty, { qty = it }, Modifier.weight(0.7f), label = { Text("Qté") }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("Rayon", aisles, aisle, { it }, Modifier.weight(1f)) { aisle = it }
                Button(onClick = { add(name); name = ""; qty = "" }) { Text("Ajouter") }
            }
            val sugg = history.map { it.split("|") }.filter { s -> name.length >= 1 && s[0].contains(name, true) && list.none { it.getString("name") == s[0] && !it.getBoolean("done") } }.take(6)
            if (sugg.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sugg.forEach { s -> AssistChip(onClick = { add(s[0], s.getOrElse(1) { aisles.last() }, ""); name = "" }, label = { Text(s[0]) }) }
            }
            Row {
                Text("${list.count { !it.getBoolean("done") }} article(s) à prendre", Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                if (list.any { it.getBoolean("done") }) TextButton(onClick = { save(list.filter { !it.getBoolean("done") }) }) { Text("Vider le panier") }
                if (list.isNotEmpty()) TextButton(onClick = {
                    val txt = list.filter { !it.getBoolean("done") }.joinToString("\n") { "• " + it.getString("name") + it.getString("qty").let { q -> if (q.isNotBlank()) " ($q)" else "" } }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "🛒 Courses :\n$txt"), null))
                }) { Text("Partager") }
            }
        }
        list.sortedBy { it.getBoolean("done") }.groupBy { if (it.getBoolean("done")) "✅ Dans le panier" else it.getString("aisle") }
            .toSortedMap(compareBy { aisles.indexOf(it).let { i -> if (i < 0) 99 else i } }).forEach { (a, items) ->
                item(key = a) { Text(a, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                items(items, key = { it.getLong("id") }) { it2 ->
                    val done = it2.getBoolean("done")
                    Row(Modifier.fillMaxWidth().clickable { save(list.map { x -> if (x.getLong("id") == it2.getLong("id")) x.with("done", !done) else x }) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(done, null)
                        Text(it2.getString("name"), Modifier.weight(1f), textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = if (done) MaterialTheme.colorScheme.outline else Color.Unspecified)
                        if (it2.getString("qty").isNotBlank()) Text(it2.getString("qty"), color = MaterialTheme.colorScheme.outline)
                        TextButton(onClick = { save(list.filter { x -> x.getLong("id") != it2.getLong("id") }) }) { Text("✕") }
                    }
                }
            }
    }
}
