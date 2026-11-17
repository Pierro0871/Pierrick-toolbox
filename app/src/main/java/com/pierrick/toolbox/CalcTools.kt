package com.pierrick.toolbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

fun Double.fmt(maxDec: Int = 6): String = NumberFormat.getInstance(Locale.FRANCE).apply {
    maximumFractionDigits = maxDec; isGroupingUsed = true
}.format(this)

fun String.num(): Double? = replace(',', '.').replace(" ", "").toDoubleOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T, name: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(
            name(selected), {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

// ---------------- Unités ----------------

private data class UnitDef(val name: String, val factor: Double)

private val unitCategories: Map<String, List<UnitDef>> = linkedMapOf(
    "Longueur" to listOf(UnitDef("mm", .001), UnitDef("cm", .01), UnitDef("m", 1.0), UnitDef("km", 1000.0),
        UnitDef("pouce", .0254), UnitDef("pied", .3048), UnitDef("yard", .9144), UnitDef("mile", 1609.344)),
    "Masse" to listOf(UnitDef("mg", 1e-6), UnitDef("g", .001), UnitDef("kg", 1.0), UnitDef("tonne", 1000.0),
        UnitDef("once", .028349523125), UnitDef("livre (lb)", .45359237)),
    "Volume" to listOf(UnitDef("ml", .001), UnitDef("cl", .01), UnitDef("l", 1.0), UnitDef("m³", 1000.0),
        UnitDef("c. à café", .005), UnitDef("c. à soupe", .015), UnitDef("tasse US", .2365882365), UnitDef("gallon US", 3.785411784)),
    "Surface" to listOf(UnitDef("cm²", 1e-4), UnitDef("m²", 1.0), UnitDef("are", 100.0), UnitDef("hectare", 1e4),
        UnitDef("km²", 1e6), UnitDef("pied²", .09290304), UnitDef("acre", 4046.8564224)),
    "Vitesse" to listOf(UnitDef("m/s", 1.0), UnitDef("km/h", 1 / 3.6), UnitDef("mph", .44704), UnitDef("nœud", .514444)),
    "Température" to listOf(UnitDef("°C", 0.0), UnitDef("°F", 0.0), UnitDef("K", 0.0)),
    "Données" to listOf(UnitDef("octet", 1.0), UnitDef("Ko", 1e3), UnitDef("Mo", 1e6), UnitDef("Go", 1e9), UnitDef("To", 1e12),
        UnitDef("Kio", 1024.0), UnitDef("Mio", 1048576.0), UnitDef("Gio", 1073741824.0)),
    "Temps" to listOf(UnitDef("s", 1.0), UnitDef("min", 60.0), UnitDef("h", 3600.0), UnitDef("jour", 86400.0),
        UnitDef("semaine", 604800.0), UnitDef("an", 31557600.0)),
    "Énergie" to listOf(UnitDef("J", 1.0), UnitDef("kJ", 1000.0), UnitDef("cal", 4.184), UnitDef("kcal", 4184.0), UnitDef("Wh", 3600.0), UnitDef("kWh", 3.6e6)),
)

private fun convertTemp(v: Double, from: String, to: String): Double {
    val c = when (from) { "°F" -> (v - 32) * 5 / 9; "K" -> v - 273.15; else -> v }
    return when (to) { "°F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
}

@Composable
fun UnitsScreen() {
    var cat by remember { mutableStateOf("Longueur") }
    val units = unitCategories.getValue(cat)
    var from by remember(cat) { mutableStateOf(units[0]) }
    var input by remember { mutableStateOf("1") }
    val v = input.num()
    ToolColumn {
        Dropdown("Catégorie", unitCategories.keys.toList(), cat, { it }) { cat = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("Valeur") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Dropdown("De", units, from, { it.name }, Modifier.weight(1f)) { from = it }
        }
        if (v != null) units.filter { it != from }.forEach { u ->
            val r = if (cat == "Température") convertTemp(v, from.name, u.name) else v * from.factor / u.factor
            ListItem(headlineContent = { Text(r.fmt(), style = MaterialTheme.typography.titleLarge) }, trailingContent = { Text(u.name) })
        }
    }
}

// ---------------- Calculatrice + pourboire ----------------

/** Évaluateur d'expressions simple : + - × ÷ ^ % ( ) √ */
class ExprParser(private val s: String) {
    private var p = 0
    fun parse(): Double { val v = expr(); if (p < s.length) error("syntaxe"); return v }
    private fun peek() = if (p < s.length) s[p] else '\u0000'
    private fun eat(c: Char): Boolean { if (peek() == c) { p++; return true }; return false }
    private fun expr(): Double { var v = term(); while (true) { v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v } } }
    private fun term(): Double { var v = factor(); while (true) { v = when { eat('×') || eat('*') -> v * factor(); eat('÷') || eat('/') -> v / factor(); else -> return v } } }
    private fun factor(): Double { val b = unary(); return if (eat('^')) Math.pow(b, factor()) else b }
    private fun unary(): Double = when { eat('-') -> -unary(); eat('+') -> unary(); eat('√') -> Math.sqrt(unary()); else -> postfix() }
    private fun postfix(): Double { var v = atom(); while (eat('%')) v /= 100; return v }
    private fun atom(): Double {
        if (eat('(')) { val v = expr(); eat(')'); return v }
        val st = p
        while (peek().isDigit() || peek() == '.' || peek() == ',') p++
        if (st == p) error("syntaxe")
        return s.substring(st, p).replace(',', '.').toDouble()
    }
}

@Composable
fun CalcScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Calculatrice") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Pourboire / Partage") })
        }
        if (tab == 0) Calculator() else TipSplit()
    }
}

@Composable
private fun Calculator() {
    var expr by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    val preview = remember(expr) { runCatching { ExprParser(expr).parse() }.getOrNull()?.takeIf { it.isFinite() } }
    val keys = listOf(
        listOf("C", "(", ")", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "-"),
        listOf("1", "2", "3", "+"), listOf("√", "0", ",", "^"), listOf("%", "⌫", "=")
    )
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        history.takeLast(3).forEach { Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.outline) }
        Spacer(Modifier.weight(1f))
        Text(expr.ifEmpty { "0" }, Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontSize = 36.sp, maxLines = 2)
        Text(preview?.fmt(10) ?: "", Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val w = if (k == "=") 2f else 1f
                    val isOp = k in listOf("÷", "×", "-", "+", "=", "^")
                    Button(
                        onClick = {
                            when (k) {
                                "C" -> expr = ""
                                "⌫" -> expr = expr.dropLast(1)
                                "=" -> preview?.let { history = history + "$expr = ${it.fmt(10)}"; expr = it.fmt(10).replace(" ", "").replace(" ", "") }
                                else -> expr += k
                            }
                        },
                        modifier = Modifier.weight(w).height(60.dp),
                        colors = if (isOp) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
                    ) { Text(k, fontSize = 22.sp) }
                }
            }
        }
    }
}

@Composable
private fun TipSplit() {
    var amount by remember { mutableStateOf("") }
    var tip by remember { mutableFloatStateOf(10f) }
    var people by remember { mutableIntStateOf(2) }
    val a = amount.num() ?: 0.0
    val t = a * tip / 100
    val total = a + t
    ToolColumn {
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Montant de l'addition (€)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Text("Pourboire : ${tip.toInt()} %")
        Slider(tip, { tip = it }, valueRange = 0f..30f, steps = 29)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Personnes : ", Modifier.weight(1f))
            FilledTonalButton(onClick = { if (people > 1) people-- }) { Text("−") }
            Text("$people", Modifier.padding(horizontal = 16.dp), fontSize = 22.sp)
            FilledTonalButton(onClick = { people++ }) { Text("+") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pourboire : ${t.fmt(2)} €")
                Text("Total : ${total.fmt(2)} €", style = MaterialTheme.typography.titleLarge)
                Text("Par personne : ${(total / people).fmt(2)} €", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------- Devises ----------------

@Composable
fun CurrencyScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rates by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var date by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("100") }
    var from by remember { mutableStateOf("EUR") }
    var to by remember { mutableStateOf("USD") }

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { URL("https://api.frankfurter.app/latest?from=EUR").readText() }
                val o = JSONObject(json); val r = o.getJSONObject("rates")
                val m = mutableMapOf("EUR" to 1.0); r.keys().forEach { m[it] = r.getDouble(it) }
                rates = m; date = o.getString("date")
                prefs(ctx).edit().putString("rates", json).apply()
            } catch (e: Exception) {
                error = "Hors ligne : taux en cache utilisés"
                prefs(ctx).getString("rates", null)?.let { json ->
                    val o = JSONObject(json); val r = o.getJSONObject("rates")
                    val m = mutableMapOf("EUR" to 1.0); r.keys().forEach { m[it] = r.getDouble(it) }
                    rates = m; date = o.getString("date")
                } ?: run { error = "Impossible de charger les taux (pas de connexion)" }
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }

    ToolColumn {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (rates.isNotEmpty()) {
            val codes = rates.keys.sorted()
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Montant") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("De", codes, from, { it }, Modifier.weight(1f)) { from = it }
                IconButton(onClick = { val t = from; from = to; to = t }) { Text("⇄", fontSize = 22.sp) }
                Dropdown("Vers", codes, to, { it }, Modifier.weight(1f)) { to = it }
            }
            val a = amount.num()
            val rf = rates[from]; val rt = rates[to]
            if (a != null && rf != null && rt != null) {
                Text("${a.fmt(2)} $from =", style = MaterialTheme.typography.titleMedium)
                Text("${(a / rf * rt).fmt(2)} $to", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("1 $from = ${(rt / rf).fmt(4)} $to", color = MaterialTheme.colorScheme.outline)
            }
            Text("Taux BCE du $date", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { load() }) { Text("Actualiser") }
    }
}

// ---------------- Dates ----------------

private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), dateFmt) }.getOrNull()

@Composable
fun DatesScreen() {
    val today = LocalDate.now()
    var d1 by remember { mutableStateOf(today.format(dateFmt)) }
    var d2 by remember { mutableStateOf(today.plusDays(30).format(dateFmt)) }
    var birth by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(today.format(dateFmt)) }
    var addDays by remember { mutableStateOf("100") }

    ToolColumn {
        Text("Écart entre deux dates", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(d1, { d1 = it }, Modifier.weight(1f), label = { Text("Début jj/mm/aaaa") }, singleLine = true)
            OutlinedTextField(d2, { d2 = it }, Modifier.weight(1f), label = { Text("Fin jj/mm/aaaa") }, singleLine = true)
        }
        val a = parseDate(d1); val b = parseDate(d2)
        if (a != null && b != null) {
            val days = ChronoUnit.DAYS.between(a, b)
            val p = Period.between(minOf(a, b), maxOf(a, b))
            Text("$days jours  •  ${days / 7} semaines et ${abs(days % 7)} j")
            Text("${p.years} an(s), ${p.months} mois, ${p.days} jour(s)", color = MaterialTheme.colorScheme.primary)
        } else Text("Format invalide", color = MaterialTheme.colorScheme.error)

        HorizontalDivider()
        Text("Âge", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(birth, { birth = it }, Modifier.fillMaxWidth(), label = { Text("Date de naissance jj/mm/aaaa") }, singleLine = true)
        parseDate(birth)?.let { bd ->
            val p = Period.between(bd, today)
            var next = bd.withYear(today.year); if (!next.isAfter(today)) next = next.plusYears(1)
            Text("${p.years} ans, ${p.months} mois, ${p.days} jours", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("Soit ${ChronoUnit.DAYS.between(bd, today).toDouble().fmt()} jours vécus")
            Text("🎂 Prochain anniversaire dans ${ChronoUnit.DAYS.between(today, next)} jours")
        }

        HorizontalDivider()
        Text("Ajouter / retirer des jours", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(base, { base = it }, Modifier.weight(1f), label = { Text("Date") }, singleLine = true)
            OutlinedTextField(addDays, { addDays = it }, Modifier.weight(1f), label = { Text("± jours") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        val bd = parseDate(base); val n = addDays.trim().toLongOrNull()
        if (bd != null && n != null) {
            val r = bd.plusDays(n)
            val dow = r.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE)
            Text("→ $dow ${r.format(dateFmt)}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun abs(x: Long) = kotlin.math.abs(x)
