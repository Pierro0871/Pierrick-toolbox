package com.pierrick.toolbox

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun d(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

// ---------------- Agenda ----------------

@Composable
fun AgendaScreen() {
    val ctx = LocalContext.current
    val (list, save) = rememberStoredList("agenda")
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    val byDay = list.groupBy { it.getString("date") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                Text(month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)).replaceFirstChar { it.uppercase() },
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
            }
            Row { listOf("L", "M", "M", "J", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline) } }
            val first = month.atDay(1); val offset = first.dayOfWeek.value - 1
            val cells = offset + month.lengthOfMonth()
            for (w in 0 until (cells + 6) / 7) Row {
                for (dw in 0 until 7) {
                    val dayN = w * 7 + dw - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (dayN in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayN)
                            val sel = date == selected; val today = date == LocalDate.now()
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                .background(when { sel -> MaterialTheme.colorScheme.primary; today -> MaterialTheme.colorScheme.primaryContainer; else -> Color.Transparent })
                                .clickable { selected = date }, contentAlignment = Alignment.Center) {
                                Text("$dayN", color = if (sel) MaterialTheme.colorScheme.onPrimary else Color.Unspecified)
                                if (byDay.containsKey(date.toString())) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).clip(RoundedCornerShape(50))
                                    .background(if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(selected.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.weight(1f), label = { Text("Événement") }, singleLine = true)
                OutlinedButton(onClick = { pickTime(ctx, LocalTime.of(9, 0)) { time = it.toString().take(5) } }) { Text(time.ifBlank { "🕒" }) }
                Button(onClick = {
                    if (title.isNotBlank()) {
                        save(list + JSONObject().put("id", System.currentTimeMillis()).put("title", title.trim()).put("date", selected.toString()).put("time", time))
                        title = ""; time = ""
                    }
                }) { Text("+") }
            }
        }
        items((byDay[selected.toString()] ?: emptyList()).sortedBy { it.optString("time") }, key = { it.getLong("id") }) { e ->
            ListItem(headlineContent = { Text(e.getString("title")) },
                leadingContent = { Text(e.optString("time").ifBlank { "Journée" }) },
                trailingContent = { TextButton(onClick = { save(list - e) }) { Text("✕") } })
        }
        val upcoming = list.filter { (d(it.getString("date")) ?: LocalDate.MIN) > selected }.sortedBy { it.getString("date") + it.optString("time") }.take(5)
        if (upcoming.isNotEmpty()) {
            item { Text("À venir", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
            items(upcoming, key = { "u" + it.getLong("id") }) { e ->
                Text("• ${isoToFr(e.getString("date"))} ${e.optString("time")} — ${e.getString("title")}", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ---------------- Kanban ----------------

@Composable
fun KanbanScreen() {
    val (list, save) = rememberStoredList("kanban")
    val cols = listOf("📥 À faire", "⚙️ En cours", "✅ Fait")
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("Nouvelle tâche") }, singleLine = true)
            TextButton(onClick = { if (input.isNotBlank()) { save(list + JSONObject().put("id", System.nanoTime()).put("t", input.trim()).put("c", 0)); input = "" } }) { Text("Ajouter") }
        }
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cols.forEachIndexed { ci, cn ->
                Card(Modifier.width(240.dp).fillMaxHeight()) {
                    LazyColumn(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            Row {
                                Text("$cn (${list.count { it.getInt("c") == ci }})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                if (ci == 2 && list.any { it.getInt("c") == 2 }) TextButton(onClick = { save(list.filter { it.getInt("c") != 2 }) }) { Text("Vider") }
                            }
                        }
                        items(list.filter { it.getInt("c") == ci }, key = { it.getLong("id") }) { t ->
                            ElevatedCard {
                                Column(Modifier.padding(8.dp)) {
                                    Text(t.getString("t"))
                                    Row {
                                        if (ci > 0) TextButton(onClick = { save(list.map { if (it === t) it.with("c", ci - 1) else it }) }) { Text("◀") }
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { save(list - t) }) { Text("✕") }
                                        if (ci < 2) TextButton(onClick = { save(list.map { if (it === t) it.with("c", ci + 1) else it }) }) { Text("▶") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Abonnements ----------------

private val periods = listOf("Mensuel", "Annuel", "Hebdomadaire", "Trimestriel")
private fun monthly(o: JSONObject): Double {
    val p = o.optString("price").num() ?: 0.0
    return when (o.optString("period")) { "Annuel" -> p / 12; "Hebdomadaire" -> p * 52 / 12; "Trimestriel" -> p / 3; else -> p }
}
private fun nextRenewal(o: JSONObject): LocalDate? {
    var n = d(o.optString("date")) ?: return null
    val today = LocalDate.now()
    while (n.isBefore(today)) n = when (o.optString("period")) { "Annuel" -> n.plusYears(1); "Hebdomadaire" -> n.plusWeeks(1); "Trimestriel" -> n.plusMonths(3); else -> n.plusMonths(1) }
    return n
}

@Composable
fun SubscriptionsScreen() {
    val ctx = LocalContext.current
    RecordsScreen("subs",
        listOf(Field("name", "Service (Netflix, salle de sport…)"), Field("price", "Prix (€)", FType.NUM),
            Field("period", "Fréquence", FType.CHOICE, periods), Field("date", "Prochain prélèvement", FType.DATE), Field("note", "Note")),
        title = { it.optString("name") },
        subtitle = { o -> nextRenewal(o)?.let { "Prochain : ${it.format(FR_DATE)} (dans ${ChronoUnit.DAYS.between(LocalDate.now(), it)} j)" } ?: "" },
        trailing = { "${monthly(it).fmt(2)} €/mois" },
        sort = { l -> l.sortedBy { nextRenewal(it) ?: LocalDate.MAX } },
        header = { list ->
            val m = list.sumOf { monthly(it) }
            ResultCard {
                Text("Total : ${m.fmt(2)} €/mois", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("soit ${(m * 12).fmt(2)} €/an")
            }
            Text("Un rappel est programmé 2 jours avant chaque prélèvement.", style = MaterialTheme.typography.bodySmall)
        },
        onSaved = { o ->
            nextRenewal(o)?.minusDays(2)?.let { day ->
                val at = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val r = JSONObject().put("id", o.getLong("id") % 1_000_000_000 + 7).put("text", "💳 ${o.optString("name")} : prélèvement de ${o.optString("price")} € dans 2 jours").put("at", at).put("daily", false)
                saveList(ctx, "reminders", loadList(ctx, "reminders").filter { it.getLong("id") != r.getLong("id") } + r)
                scheduleReminder(ctx, r)
            }
        })
}

// ---------------- Objectifs ----------------

@Composable
fun GoalsScreen() {
    val (list, save) = rememberStoredList("goals")
    var name by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Long?>(null) }
    var step by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Nouvel objectif") }, singleLine = true)
                TextButton(onClick = { if (name.isNotBlank()) { save(list + JSONObject().put("id", System.nanoTime()).put("name", name.trim()).put("steps", JSONArray())); name = "" } }) { Text("Ajouter") }
            }
        }
        items(list, key = { it.getLong("id") }) { g ->
            val steps = g.getJSONArray("steps").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val done = steps.count { it.getBoolean("d") }
            fun upd(s: List<JSONObject>) = save(list.map { if (it.getLong("id") == g.getLong("id")) it.with("steps", JSONArray(s)) else it })
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.clickable { open = if (open == g.getLong("id")) null else g.getLong("id") }, verticalAlignment = Alignment.CenterVertically) {
                        Text((if (steps.isNotEmpty() && done == steps.size) "🏆 " else "🎯 ") + g.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("$done/${steps.size}")
                    }
                    LinearProgressIndicator({ if (steps.isEmpty()) 0f else done.toFloat() / steps.size }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    if (open == g.getLong("id")) {
                        steps.forEachIndexed { i, s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(s.getBoolean("d"), { upd(steps.mapIndexed { j, x -> if (j == i) x.with("d", it) else x }) })
                                Text(s.getString("t"), Modifier.weight(1f), textDecoration = if (s.getBoolean("d")) TextDecoration.LineThrough else null)
                                TextButton(onClick = { upd(steps.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(step, { step = it }, Modifier.weight(1f), label = { Text("Nouvelle étape") }, singleLine = true)
                            TextButton(onClick = { if (step.isNotBlank()) { upd(steps + JSONObject().put("t", step.trim()).put("d", false)); step = "" } }) { Text("+") }
                        }
                        TextButton(onClick = { save(list - g) }) { Text("Supprimer l'objectif", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

// ---------------- Journal ----------------

private val moods = listOf("😄", "🙂", "😐", "😕", "😢", "😡", "😴")

@Composable
fun JournalScreen() = RecordsScreen("journal",
    listOf(Field("date", "Date", FType.DATE), Field("mood", "Humeur", FType.CHOICE, moods), Field("text", "Ma journée", FType.MULTI)),
    title = { "${it.optString("mood")}  ${isoToFr(it.optString("date"))}" },
    subtitle = { it.optString("text").take(140) },
    sort = { l -> l.sortedByDescending { it.optString("date") } },
    addLabel = "+ Écrire aujourd'hui",
    header = { list ->
        val last = list.sortedByDescending { it.optString("date") }.take(30)
        if (last.isNotEmpty()) {
            Text("Humeurs des 30 dernières entrées", style = MaterialTheme.typography.titleSmall)
            Text(last.reversed().joinToString("") { it.optString("mood") }, fontSize = 20.sp)
            val counts = last.groupingBy { it.optString("mood") }.eachCount().entries.sortedByDescending { it.value }
            Text(counts.joinToString("  ") { "${it.key}×${it.value}" }, color = MaterialTheme.colorScheme.outline)
        }
    })

// ---------------- Cartes de fidélité ----------------

fun barcodeBitmap(content: String, format: BarcodeFormat, w: Int = 900, h: Int = 300): android.graphics.Bitmap? = runCatching {
    val isSquare = format == BarcodeFormat.QR_CODE || format == BarcodeFormat.AZTEC || format == BarcodeFormat.DATA_MATRIX
    val ww = if (isSquare) h * 2 else w; val hh = if (isSquare) h * 2 else h
    val m = MultiFormatWriter().encode(content, format, ww, hh)
    val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
}.getOrNull()

@Composable
fun LoyaltyScreen() {
    val ctx = LocalContext.current
    val (list, save) = rememberStoredList("loyalty")
    var shown by remember { mutableStateOf<JSONObject?>(null) }
    var pendingName by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResultScan { code, fmt ->
        save(list + JSONObject().put("id", System.currentTimeMillis()).put("name", pendingName.ifBlank { "Carte" }).put("code", code).put("fmt", fmt)); pendingName = ""
    }
    shown?.let { c ->
        androidx.activity.compose.BackHandler { shown = null }
        val fmt = runCatching { BarcodeFormat.valueOf(c.getString("fmt")) }.getOrDefault(BarcodeFormat.CODE_128)
        val bmp = remember(c) { barcodeBitmap(c.getString("code"), fmt) }
        Column(Modifier.fillMaxSize().background(Color.White).clickable { shown = null }.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(c.getString("name"), color = Color.Black, fontSize = 28.sp)
            Spacer(Modifier.height(24.dp))
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth()) } ?: Text("Code non affichable", color = Color.Black)
            Spacer(Modifier.height(16.dp))
            Text(c.getString("code"), color = Color.Black, fontSize = 20.sp)
            Text("Monte la luminosité si la caisse ne lit pas le code", color = Color.Gray, fontSize = 12.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pendingName, { pendingName = it }, Modifier.weight(1f), label = { Text("Nom du magasin") }, singleLine = true)
                Button(onClick = { scan.launch(ScanOptions().setPrompt("Scanne la carte").setOrientationLocked(false)) }) { Text("📷 Scanner") }
            }
        }
        items(list, key = { it.getLong("id") }) { c ->
            ElevatedCard(Modifier.fillMaxWidth().clickable { shown = c }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("💳 " + c.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { save(list - c) }) { Text("✕") }
                }
            }
        }
    }
}

@Composable
fun rememberLauncherForActivityResultScan(onResult: (String, String) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        if (r.contents != null) onResult(r.contents, r.formatName ?: "CODE_128")
    }

// ---------------- Recettes ----------------

private val qtyRegex = Regex("""^\s*(\d+(?:[.,]\d+)?)(.*)$""")

@Composable
fun RecipesScreen() {
    val (list, _) = rememberStoredList("recipes")
    var viewing by remember { mutableStateOf<Long?>(null) }
    val r = list.find { it.getLong("id") == viewing }
    if (r != null) {
        androidx.activity.compose.BackHandler { viewing = null }
        val base = r.optString("portions").num()?.takeIf { it > 0 } ?: 4.0
        var portions by remember { mutableDoubleStateOf(base) }
        ToolColumn {
            OutlinedButton(onClick = { viewing = null }) { Text("← Recettes") }
            Text(r.optString("name"), style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Portions : ", Modifier.weight(1f))
                FilledTonalButton(onClick = { if (portions > 1) portions-- }) { Text("−") }
                Text(portions.fmt(0), Modifier.padding(horizontal = 16.dp), fontSize = 22.sp)
                FilledTonalButton(onClick = { portions++ }) { Text("+") }
            }
            Text("Ingrédients", style = MaterialTheme.typography.titleMedium)
            r.optString("ingredients").lines().filter { it.isNotBlank() }.forEach { line ->
                val m = qtyRegex.find(line)
                Text("• " + if (m != null) (m.groupValues[1].num()!! * portions / base).fmt(1) + m.groupValues[2] else line)
            }
            Text("Préparation", style = MaterialTheme.typography.titleMedium)
            Text(r.optString("steps"))
            if (r.optString("time").isNotBlank()) Text("⏱️ ${r.optString("time")}", color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    Column {
        RecordsScreen("recipes",
            listOf(Field("name", "Nom de la recette"), Field("portions", "Nombre de portions", FType.NUM), Field("time", "Temps (ex : 45 min)"),
                Field("ingredients", "Ingrédients (un par ligne, quantité en premier : « 200 g farine »)", FType.MULTI),
                Field("steps", "Préparation", FType.MULTI), Field("photo", "Photo", FType.IMAGE)),
            title = { it.optString("name") }, subtitle = { "${it.optString("portions")} pers. • ${it.optString("time")}" },
            sort = { l -> l.sortedBy { it.optString("name") } },
            header = { l -> if (l.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ouvrir : ", Modifier.padding(top = 8.dp))
                l.sortedBy { it.optString("name") }.forEach { x -> AssistChip(onClick = { viewing = x.getLong("id") }, label = { Text(x.optString("name")) }) }
            } })
    }
}

// ---------------- Menus de la semaine ----------------

@Composable
fun MenusScreen() {
    val ctx = LocalContext.current
    val p = prefs(ctx)
    var data by remember { mutableStateOf(runCatching { JSONObject(p.getString("menus", "{}")!!) }.getOrDefault(JSONObject())) }
    fun set(k: String, v: String) { data = data.with(k, v); p.edit().putString("menus", data.toString()).apply() }
    ToolColumn {
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val txt = DayOfWeek.entries.joinToString("\n") { dw ->
                    "${dw.getDisplayName(TextStyle.FULL, Locale.FRANCE)} : midi ${data.optString("${dw}_m", "-")} / soir ${data.optString("${dw}_s", "-")}"
                }
                shareText(ctx, "🍽️ Menus de la semaine\n$txt")
            }) { Text("Partager") }
            TextButton(onClick = { data = JSONObject(); p.edit().putString("menus", "{}").apply() }) { Text("Tout effacer") }
        }
        DayOfWeek.entries.forEach { dw ->
            val today = LocalDate.now().dayOfWeek == dw
            Card(colors = if (today) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(dw.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(data.optString("${dw}_m"), { set("${dw}_m", it) }, Modifier.fillMaxWidth(), label = { Text("☀️ Midi") }, singleLine = true)
                    OutlinedTextField(data.optString("${dw}_s"), { set("${dw}_s", it) }, Modifier.fillMaxWidth(), label = { Text("🌙 Soir") }, singleLine = true)
                }
            }
        }
    }
}

// ---------------- Inventaire, garanties, prêts, voiture ----------------

@Composable
fun InventoryScreen() = RecordsScreen("inventory",
    listOf(Field("name", "Objet"), Field("room", "Pièce", FType.CHOICE, listOf("Salon", "Cuisine", "Chambre", "Salle de bain", "Bureau", "Garage", "Cave / grenier", "Jardin", "Autre")),
        Field("qty", "Quantité", FType.NUM), Field("value", "Valeur (€)", FType.NUM), Field("date", "Date d'achat", FType.DATE),
        Field("serial", "N° de série"), Field("photo", "Photo", FType.IMAGE), Field("note", "Note", FType.MULTI)),
    title = { it.optString("name") }, subtitle = { "${it.optString("room")}${it.optString("qty").let { q -> if (q.isNotBlank()) " • ×$q" else "" }}" },
    trailing = { o -> o.optString("value").num()?.let { "${it.fmt(0)} €" } ?: "" },
    sort = { l -> l.sortedBy { it.optString("room") + it.optString("name") } },
    header = { l ->
        val tot = l.sumOf { (it.optString("value").num() ?: 0.0) * (it.optString("qty").num() ?: 1.0) }
        Text("${l.size} objets • valeur totale ${tot.fmt(0)} € (utile pour l'assurance)", color = MaterialTheme.colorScheme.outline)
    })

@Composable
fun WarrantyScreen() = RecordsScreen("warranty",
    listOf(Field("name", "Produit"), Field("shop", "Magasin"), Field("date", "Date d'achat", FType.DATE),
        Field("years", "Durée de garantie (ans)", FType.NUM), Field("price", "Prix (€)", FType.NUM), Field("doc", "Facture (photo/PDF)", FType.IMAGE)),
    title = { it.optString("name") },
    subtitle = { o ->
        val end = d(o.optString("date"))?.plusMonths(((o.optString("years").num() ?: 2.0) * 12).toLong())
        if (end == null) o.optString("shop") else (if (end.isBefore(LocalDate.now())) "❌ Expirée le " else "✅ Garantie jusqu'au ") + end.format(FR_DATE)
    },
    trailing = { o -> o.optString("price").num()?.let { "${it.fmt(0)} €" } ?: "" },
    header = { Text("Garantie légale en France : 2 ans minimum sur les produits neufs.", style = MaterialTheme.typography.bodySmall) })

@Composable
fun LentScreen() = RecordsScreen("lent",
    listOf(Field("what", "Objet / somme"), Field("dir", "Sens", FType.CHOICE, listOf("J'ai prêté à", "J'ai emprunté à")), Field("who", "Personne"),
        Field("date", "Date", FType.DATE), Field("back", "Rendu ?", FType.CHOICE, listOf("Non", "Oui")), Field("note", "Note")),
    title = { "${if (it.optString("back") == "Oui") "✅" else "⏳"} ${it.optString("what")}" },
    subtitle = { "${it.optString("dir")} ${it.optString("who")} • ${isoToFr(it.optString("date"))}" },
    sort = { l -> l.sortedBy { (if (it.optString("back") == "Oui") "1" else "0") + it.optString("date") } })

@Composable
fun CarScreen() = RecordsScreen("car",
    listOf(Field("type", "Intervention", FType.CHOICE, listOf("Vidange", "Pneus", "Freins", "Contrôle technique", "Révision", "Courroie", "Batterie", "Lavage", "Plein", "Réparation", "Autre")),
        Field("date", "Date", FType.DATE), Field("km", "Kilométrage", FType.NUM), Field("cost", "Coût (€)", FType.NUM),
        Field("next", "Prochaine échéance", FType.DATE), Field("note", "Note", FType.MULTI)),
    title = { it.optString("type") },
    subtitle = { o -> "${isoToFr(o.optString("date"))} • ${o.optString("km")} km" + (d(o.optString("next"))?.let { " • ⏭ ${it.format(FR_DATE)}" } ?: "") },
    trailing = { o -> o.optString("cost").num()?.let { "${it.fmt(0)} €" } ?: "" },
    sort = { l -> l.sortedByDescending { it.optString("date") } },
    header = { l ->
        val total = l.sumOf { it.optString("cost").num() ?: 0.0 }
        val year = l.filter { it.optString("date").startsWith(LocalDate.now().year.toString()) }.sumOf { it.optString("cost").num() ?: 0.0 }
        Text("Dépensé cette année : ${year.fmt(0)} € • total : ${total.fmt(0)} €", color = MaterialTheme.colorScheme.outline)
        l.mapNotNull { o -> d(o.optString("next"))?.let { it to o.optString("type") } }.filter { !it.first.isBefore(LocalDate.now()) }.minByOrNull { it.first }?.let { (dt, t) ->
            Text("⏭ Prochaine échéance : $t le ${dt.format(FR_DATE)}", color = MaterialTheme.colorScheme.primary)
        }
    })

// ---------------- Check-list voyage ----------------

@Composable
fun TravelListScreen() = SimpleChecklist("travel", listOf(
    "Carte d'identité / passeport", "Billets / réservations", "Carte bancaire + un peu de liquide", "Permis de conduire", "Carte Vitale / CEAM",
    "Chargeur de téléphone", "Batterie externe", "Adaptateur de prise", "Écouteurs", "Médicaments", "Trousse de toilette", "Brosse à dents",
    "Crème solaire", "Lunettes de soleil", "Sous-vêtements", "Chaussettes", "T-shirts", "Pantalons / shorts", "Pull / veste", "Pyjama",
    "Maillot de bain", "Chaussures", "Clés de la maison", "Fermer l'eau / le gaz", "Sortir les poubelles"), "Ajouter à la valise")

// ---------------- Poids & sommeil ----------------

@Composable
fun WeightScreen() = RecordsScreen("weight",
    listOf(Field("date", "Date", FType.DATE), Field("kg", "Poids (kg)", FType.NUM), Field("note", "Note")),
    title = { "${it.optString("kg")} kg" }, subtitle = { isoToFr(it.optString("date")) },
    sort = { l -> l.sortedByDescending { it.optString("date") } }, addLabel = "+ Pesée",
    header = { l ->
        val pts = l.filter { it.optString("kg").num() != null }.sortedBy { it.optString("date") }
        if (pts.size >= 2) {
            val first = pts.first().optString("kg").num()!!; val last = pts.last().optString("kg").num()!!
            Text("Actuel : ${last.fmt(1)} kg • évolution ${(if (last >= first) "+" else "") + (last - first).fmt(1)} kg", style = MaterialTheme.typography.titleMedium)
            LineChart(pts.takeLast(60).map { it.optString("kg").num()!!.toFloat() })
            Text("Min ${pts.minOf { it.optString("kg").num()!! }.fmt(1)} • Max ${pts.maxOf { it.optString("kg").num()!! }.fmt(1)}", color = MaterialTheme.colorScheme.outline)
        }
    })

private fun sleepHours(o: JSONObject): Double? {
    val b = runCatching { LocalTime.parse(o.optString("bed")) }.getOrNull() ?: return null
    val w = runCatching { LocalTime.parse(o.optString("wake")) }.getOrNull() ?: return null
    var m = ChronoUnit.MINUTES.between(b, w); if (m < 0) m += 1440
    return m / 60.0
}

@Composable
fun SleepScreen() = RecordsScreen("sleep",
    listOf(Field("date", "Nuit du", FType.DATE), Field("bed", "Coucher", FType.TIME), Field("wake", "Lever", FType.TIME),
        Field("q", "Qualité", FType.CHOICE, listOf("😴 Excellente", "🙂 Bonne", "😐 Moyenne", "😫 Mauvaise")), Field("note", "Note")),
    title = { o -> sleepHours(o)?.let { "${it.toInt()} h ${((it % 1) * 60).toInt()} min" } ?: "?" },
    subtitle = { "${isoToFr(it.optString("date"))} • ${it.optString("bed")} → ${it.optString("wake")} • ${it.optString("q")}" },
    sort = { l -> l.sortedByDescending { it.optString("date") } }, addLabel = "+ Nuit",
    header = { l ->
        val h = l.sortedBy { it.optString("date") }.mapNotNull { sleepHours(it) }
        if (h.isNotEmpty()) {
            val avg = h.takeLast(7).average()
            Text("Moyenne 7 dernières nuits : ${avg.toInt()} h ${((avg % 1) * 60).toInt()}", style = MaterialTheme.typography.titleMedium,
                color = if (avg >= 7) Color(0xFF43A047) else Color(0xFFFB8C00))
            if (h.size >= 2) LineChart(h.takeLast(30).map { it.toFloat() })
            Text("Recommandé pour un adulte : 7 à 9 h", style = MaterialTheme.typography.bodySmall)
        }
    })
