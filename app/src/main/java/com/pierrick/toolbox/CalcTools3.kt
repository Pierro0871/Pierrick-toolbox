package com.pierrick.toolbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigInteger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.*

// ---------------- Calculatrice scientifique ----------------

class SciParser(private val s: String, private val deg: Boolean) {
    private var p = 0
    fun parse(): Double { val v = expr(); if (p < s.length) error("syntaxe"); return v }
    private fun peek() = if (p < s.length) s[p] else '\u0000'
    private fun eat(c: Char): Boolean { while (peek() == ' ') p++; if (peek() == c) { p++; return true }; return false }
    private fun expr(): Double { var v = term(); while (true) v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v } }
    private fun term(): Double { var v = factor(); while (true) v = when { eat('×') || eat('*') -> v * factor(); eat('÷') || eat('/') -> v / factor(); else -> return v } }
    private fun factor(): Double { val b = unary(); return if (eat('^')) b.pow(factor()) else b }
    private fun unary(): Double = when { eat('-') -> -unary(); eat('+') -> unary(); else -> postfix() }
    private fun postfix(): Double {
        var v = atom()
        while (true) { v = when { eat('!') -> fact(v); eat('%') -> v / 100; eat('²') -> v * v; else -> return v } }
    }
    private fun fact(x: Double): Double { if (x < 0 || x != floor(x) || x > 170) error("!"); var r = 1.0; for (i in 2..x.toInt()) r *= i; return r }
    private fun ang(x: Double) = if (deg) Math.toRadians(x) else x
    private fun inv(x: Double) = if (deg) Math.toDegrees(x) else x
    private fun atom(): Double {
        if (eat('(')) { val v = expr(); eat(')'); return v }
        if (eat('√')) return sqrt(postfix())
        if (eat('π')) return PI
        val st = p
        if (peek().isLetter()) {
            while (peek().isLetter() || peek() == '⁻' || peek() == '¹') p++
            val name = s.substring(st, p)
            if (name == "e") return E
            if (name == "Ans") return 0.0
            val a = if (eat('(')) { val v = expr(); eat(')'); v } else postfix()
            return when (name) {
                "sin" -> sin(ang(a)); "cos" -> cos(ang(a)); "tan" -> tan(ang(a))
                "asin", "sin⁻¹" -> inv(asin(a)); "acos", "cos⁻¹" -> inv(acos(a)); "atan", "tan⁻¹" -> inv(atan(a))
                "ln" -> ln(a); "log" -> log10(a); "exp" -> exp(a); "abs" -> abs(a); "sqrt" -> sqrt(a)
                else -> error("fonction")
            }
        }
        while (peek().isDigit() || peek() == '.' || peek() == ',') p++
        if (st == p) error("syntaxe")
        var v = s.substring(st, p).replace(',', '.').toDouble()
        if (peek() == 'E') { p++; val es = p; if (peek() == '-') p++; while (peek().isDigit()) p++; v *= 10.0.pow(s.substring(es, p).toDouble()) }
        return v
    }
}

@Composable
fun SciCalcScreen() {
    var expr by remember { mutableStateOf("") }
    var deg by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(listOf<String>()) }
    val res = remember(expr, deg) { runCatching { SciParser(expr, deg).parse() }.getOrNull()?.takeIf { it.isFinite() } }
    val keys = listOf(
        listOf("sin(", "cos(", "tan(", "π", "e"), listOf("sin⁻¹(", "cos⁻¹(", "tan⁻¹(", "ln(", "log("),
        listOf("(", ")", "^", "√", "!"), listOf("7", "8", "9", "÷", "C"), listOf("4", "5", "6", "×", "⌫"),
        listOf("1", "2", "3", "-", "²"), listOf("0", ",", "E", "+", "=")
    )
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(deg, { deg = true }, label = { Text("DEG") }); Spacer(Modifier.width(6.dp)); FilterChip(!deg, { deg = false }, label = { Text("RAD") })
        }
        history.takeLast(3).forEach { Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.outline) }
        Spacer(Modifier.weight(1f))
        Text(expr.ifEmpty { "0" }, Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontSize = 30.sp, maxLines = 3)
        Text(res?.let { if (abs(it) > 1e12 || (abs(it) < 1e-6 && it != 0.0)) "%.8e".format(it) else it.fmt(10) } ?: "", Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Button(onClick = {
                        when (k) {
                            "C" -> expr = ""; "⌫" -> expr = expr.dropLast(1)
                            "=" -> res?.let { history = history + "$expr = ${it.fmt(10)}"; expr = it.toString().removeSuffix(".0") }
                            else -> expr += k
                        }
                    }, Modifier.weight(1f).height(52.dp), contentPadding = PaddingValues(2.dp),
                        colors = if (k.first().isDigit() || k == ",") ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()
                    ) { Text(k.removeSuffix("("), fontSize = 16.sp, maxLines = 1) }
                }
            }
        }
    }
}

// ---------------- Équations ----------------

@Composable
fun EquationsScreen() = Tabs(listOf("ax + b = 0", "ax² + bx + c = 0", "Système 2×2", "Système 3×3")) { t ->
    ToolColumn {
        when (t) {
            0 -> {
                var a by remember { mutableStateOf("2") }; var b by remember { mutableStateOf("-6") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(a, "a", Modifier.weight(1f)) { a = it }; NumField(b, "b", Modifier.weight(1f)) { b = it } }
                val x = a.num(); val y = b.num()
                if (x != null && y != null) ResultCard {
                    Text(when { x != 0.0 -> "x = ${(-y / x).fmt(8)}"; y == 0.0 -> "Infinité de solutions"; else -> "Aucune solution" }, style = MaterialTheme.typography.headlineSmall)
                }
            }
            1 -> {
                var a by remember { mutableStateOf("1") }; var b by remember { mutableStateOf("-3") }; var c by remember { mutableStateOf("2") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(a, "a", Modifier.weight(1f)) { a = it }; NumField(b, "b", Modifier.weight(1f)) { b = it }; NumField(c, "c", Modifier.weight(1f)) { c = it }
                }
                val x = a.num(); val y = b.num(); val z = c.num()
                if (x != null && y != null && z != null && x != 0.0) {
                    val delta = y * y - 4 * x * z
                    ResultCard {
                        Text("Δ = b² − 4ac = ${delta.fmt(6)}")
                        when {
                            delta > 0 -> { Text("x₁ = ${((-y - sqrt(delta)) / (2 * x)).fmt(8)}", style = MaterialTheme.typography.titleLarge); Text("x₂ = ${((-y + sqrt(delta)) / (2 * x)).fmt(8)}", style = MaterialTheme.typography.titleLarge) }
                            delta == 0.0 -> Text("x = ${(-y / (2 * x)).fmt(8)} (racine double)", style = MaterialTheme.typography.titleLarge)
                            else -> {
                                val re = -y / (2 * x); val im = sqrt(-delta) / (2 * x)
                                Text("Pas de solution réelle. Solutions complexes :")
                                Text("x = ${re.fmt(6)} ± ${abs(im).fmt(6)} i", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Text("Sommet : (${(-y / (2 * x)).fmt(4)} ; ${(-delta / (4 * x)).fmt(4)})", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            else -> {
                val n = if (t == 2) 2 else 3
                val m = remember(n) { mutableStateListOf(*Array(n * (n + 1)) { "" }) }
                val vars = listOf("x", "y", "z")
                for (r in 0 until n) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (c in 0..n) {
                        NumField(m[r * (n + 1) + c], if (c < n) vars[c] else "=", Modifier.weight(1f)) { m[r * (n + 1) + c] = it }
                    }
                }
                val a = m.map { it.num() }
                if (a.all { it != null }) {
                    val v = a.map { it!! }
                    fun e(r: Int, c: Int) = v[r * (n + 1) + c]
                    fun det(mat: List<List<Double>>): Double = if (mat.size == 2) mat[0][0] * mat[1][1] - mat[0][1] * mat[1][0]
                        else mat[0][0] * (mat[1][1] * mat[2][2] - mat[1][2] * mat[2][1]) - mat[0][1] * (mat[1][0] * mat[2][2] - mat[1][2] * mat[2][0]) + mat[0][2] * (mat[1][0] * mat[2][1] - mat[1][1] * mat[2][0])
                    val base = List(n) { r -> List(n) { c -> e(r, c) } }
                    val dd = det(base)
                    ResultCard {
                        if (abs(dd) < 1e-12) Text("Déterminant nul : pas de solution unique")
                        else for (k in 0 until n) {
                            val mk = List(n) { r -> List(n) { c -> if (c == k) e(r, n) else e(r, c) } }
                            Text("${vars[k]} = ${(det(mk) / dd).fmt(8)}", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                } else Text("Remplis tous les coefficients", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ---------------- Fractions ----------------

data class Frac(val n: BigInteger, val d: BigInteger) {
    fun norm(): Frac { if (d.signum() == 0) error("/0"); val g = n.gcd(d).let { if (it.signum() == 0) BigInteger.ONE else it }; val s = if (d.signum() < 0) -1 else 1
        return Frac(n / g * BigInteger.valueOf(s.toLong()), d / g * BigInteger.valueOf(s.toLong())) }
    operator fun plus(o: Frac) = Frac(n * o.d + o.n * d, d * o.d).norm()
    operator fun minus(o: Frac) = Frac(n * o.d - o.n * d, d * o.d).norm()
    operator fun times(o: Frac) = Frac(n * o.n, d * o.d).norm()
    operator fun div(o: Frac) = Frac(n * o.d, d * o.n).norm()
    override fun toString() = if (d == BigInteger.ONE) "$n" else "$n/$d"
    fun mixed(): String { if (n.abs() < d) return toString(); val q = n / d; val r = (n % d).abs(); return if (r.signum() == 0) "$q" else "$q ${r}/$d" }
}

fun parseFrac(s: String): Frac? = runCatching {
    val t = s.trim().replace(',', '.')
    if (t.contains(' ') && t.contains('/')) { val (w, f) = t.split(Regex("\\s+"), 2); val p = parseFrac(f)!!; val wb = BigInteger(w)
        return Frac(wb * p.d + (if (wb.signum() < 0) -p.n else p.n), p.d).norm() }
    if (t.contains('/')) { val (a, b) = t.split('/'); Frac(BigInteger(a.trim()), BigInteger(b.trim())).norm() }
    else if (t.contains('.')) { val dec = t.substringAfter('.').length; Frac(BigInteger(t.replace(".", "")), BigInteger.TEN.pow(dec)).norm() }
    else Frac(BigInteger(t), BigInteger.ONE)
}.getOrNull()

@Composable
fun FractionsScreen() {
    var a by remember { mutableStateOf("3/4") }; var b by remember { mutableStateOf("2/3") }; var op by remember { mutableStateOf("+") }
    ToolColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(a, { a = it }, Modifier.weight(1f), label = { Text("Fraction A") }, singleLine = true)
            OutlinedTextField(b, { b = it }, Modifier.weight(1f), label = { Text("Fraction B") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("+", "−", "×", "÷").forEach { o -> FilterChip(op == o, { op = o }, label = { Text(o, fontSize = 18.sp) }) } }
        Text("Formats acceptés : 3/4, 1 1/2, 0,75, 5", style = MaterialTheme.typography.bodySmall)
        val x = parseFrac(a); val y = parseFrac(b)
        if (x != null && y != null) runCatching { when (op) { "+" -> x + y; "−" -> x - y; "×" -> x * y; else -> x / y } }.getOrNull()?.let { r ->
            ResultCard {
                Text("$x $op $y =", color = MaterialTheme.colorScheme.outline)
                Text(r.toString(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                if (r.n.abs() > r.d && r.d != BigInteger.ONE) Text("Nombre mixte : ${r.mixed()}")
                Text("Décimal : ${(r.n.toDouble() / r.d.toDouble()).fmt(10)}")
            }
        } ?: Text("Fraction invalide ou division par zéro", color = MaterialTheme.colorScheme.error)
        x?.let { Text("A simplifiée : $it", color = MaterialTheme.colorScheme.outline) }
    }
}

// ---------------- Règle de trois ----------------

@Composable
fun Rule3Screen() {
    var a by remember { mutableStateOf("3") }; var b by remember { mutableStateOf("4,50") }; var c by remember { mutableStateOf("7") }
    var inverse by remember { mutableStateOf(false) }
    ToolColumn {
        Text("Si A correspond à B, alors C correspond à… ?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(a, "A", Modifier.weight(1f)) { a = it }; NumField(b, "B", Modifier.weight(1f)) { b = it } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField(c, "C", Modifier.weight(1f)) { c = it }; Text("→  X", Modifier.weight(1f), fontSize = 22.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(inverse, { inverse = it }); Text("  Proportionnalité inverse (ex : ouvriers / durée)") }
        val x = a.num(); val y = b.num(); val z = c.num()
        if (x != null && y != null && z != null && x != 0.0 && z != 0.0) ResultCard {
            val r = if (inverse) x * y / z else y * z / x
            Text("X = ${r.fmt(6)}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(if (inverse) "Calcul : A × B ÷ C" else "Calcul : B × C ÷ A", color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---------------- Moyenne pondérée ----------------

@Composable
fun AverageScreen() {
    val (list, save) = rememberStoredList("grades")
    var grade by remember { mutableStateOf("") }; var coef by remember { mutableStateOf("1") }; var max by remember { mutableStateOf("20") }; var subj by remember { mutableStateOf("") }
    val sc = max.num()?.takeIf { it > 0 } ?: 20.0
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            val tc = list.sumOf { it.getDouble("c") }
            val avg = if (tc > 0) list.sumOf { it.getDouble("g") / it.getDouble("m") * sc * it.getDouble("c") } / tc else null
            ResultCard {
                Text(avg?.let { "Moyenne : ${it.fmt(2)} / ${sc.fmt(0)}" } ?: "Ajoute des notes", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("${list.size} notes • total coefficients ${tc.fmt(2)}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(subj, { subj = it }, Modifier.weight(1.4f), label = { Text("Matière") }, singleLine = true)
                NumField(grade, "Note", Modifier.weight(1f)) { grade = it }
                NumField(max, "Sur", Modifier.weight(0.8f)) { max = it }
                NumField(coef, "Coef", Modifier.weight(0.8f)) { coef = it }
            }
            Row {
                Button(onClick = {
                    val g = grade.num(); val c = coef.num() ?: 1.0
                    if (g != null) { save(list + JSONObject().put("id", System.nanoTime()).put("s", subj).put("g", g).put("c", c).put("m", sc)); grade = "" }
                }) { Text("Ajouter") }
                Spacer(Modifier.weight(1f)); if (list.isNotEmpty()) TextButton(onClick = { save(emptyList()) }) { Text("Tout effacer") }
            }
            list.groupBy { it.optString("s").ifBlank { "Sans matière" } }.takeIf { it.size > 1 }?.forEach { (s, l) ->
                val c = l.sumOf { it.getDouble("c") }
                Text("$s : ${(l.sumOf { it.getDouble("g") / it.getDouble("m") * sc * it.getDouble("c") } / c).fmt(2)}", color = MaterialTheme.colorScheme.outline)
            }
        }
        items(list.reversed(), key = { it.getLong("id") }) { g ->
            ListItem(headlineContent = { Text("${g.getDouble("g").fmt(2)} / ${g.getDouble("m").fmt(0)}") },
                supportingContent = { Text("${g.optString("s")} • coef ${g.getDouble("c").fmt(2)}") },
                trailingContent = { TextButton(onClick = { save(list - g) }) { Text("✕") } })
        }
    }
}

// ---------------- Jours ouvrés & fériés ----------------

fun easter(y: Int): LocalDate {
    val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(y, month, day)
}

fun frenchHolidays(y: Int, alsace: Boolean = false): Map<LocalDate, String> {
    val e = easter(y)
    val m = linkedMapOf(
        LocalDate.of(y, 1, 1) to "Jour de l'an", e.plusDays(1) to "Lundi de Pâques", LocalDate.of(y, 5, 1) to "Fête du Travail",
        LocalDate.of(y, 5, 8) to "Victoire 1945", e.plusDays(39) to "Ascension", e.plusDays(50) to "Lundi de Pentecôte",
        LocalDate.of(y, 7, 14) to "Fête nationale", LocalDate.of(y, 8, 15) to "Assomption", LocalDate.of(y, 11, 1) to "Toussaint",
        LocalDate.of(y, 11, 11) to "Armistice", LocalDate.of(y, 12, 25) to "Noël")
    if (alsace) { m[e.minusDays(2)] = "Vendredi saint"; m[LocalDate.of(y, 12, 26)] = "Saint-Étienne" }
    return m.toSortedMap()
}

@Composable
fun WorkdaysScreen() {
    var from by remember { mutableStateOf(LocalDate.now().toString()) }
    var to by remember { mutableStateOf(LocalDate.now().plusMonths(1).toString()) }
    var alsace by remember { mutableStateOf(false) }
    var saturday by remember { mutableStateOf(false) }
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    ToolColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateButton("Du", from, Modifier.weight(1f)) { from = it }; DateButton("Au", to, Modifier.weight(1f)) { to = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(alsace, { alsace = it }); Text("Alsace-Moselle"); Checkbox(saturday, { saturday = it }); Text("Samedi travaillé") }
        val a = LocalDate.parse(from); val b = LocalDate.parse(to)
        if (!b.isBefore(a)) {
            val hol = (a.year..b.year).flatMap { frenchHolidays(it, alsace).keys }.toSet()
            var work = 0; var holWeek = 0; var dd = a
            while (!dd.isAfter(b)) {
                val we = dd.dayOfWeek == DayOfWeek.SUNDAY || (!saturday && dd.dayOfWeek == DayOfWeek.SATURDAY)
                if (!we) { if (dd in hol) holWeek++ else work++ }
                dd = dd.plusDays(1)
            }
            ResultCard {
                Text("$work jours ouvrés", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("${ChronoUnit.DAYS.between(a, b) + 1} jours calendaires • $holWeek férié(s) en semaine")
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { year-- }) { Text("◀") }; Text("Jours fériés $year", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium); TextButton(onClick = { year++ }) { Text("▶") }
        }
        frenchHolidays(year, alsace).forEach { (dt, n) ->
            Row { Text(dt.format(java.time.format.DateTimeFormatter.ofPattern("EEE dd/MM", java.util.Locale.FRANCE)), Modifier.width(110.dp)); Text(n, Modifier.weight(1f))
                if (dt.dayOfWeek.value >= 6) Text("week-end", color = MaterialTheme.colorScheme.outline) }
        }
    }
}

// ---------------- Loi d'Ohm & résistances ----------------

private val bandColors = listOf("Noir" to Color.Black, "Marron" to Color(0xFF795548), "Rouge" to Color.Red, "Orange" to Color(0xFFFF9800),
    "Jaune" to Color.Yellow, "Vert" to Color(0xFF4CAF50), "Bleu" to Color.Blue, "Violet" to Color(0xFF9C27B0), "Gris" to Color.Gray, "Blanc" to Color.White)
private val tolerances = listOf("Marron ±1 %" to 1.0, "Rouge ±2 %" to 2.0, "Or ±5 %" to 5.0, "Argent ±10 %" to 10.0)

private fun ohmFmt(r: Double) = when { r >= 1e6 -> "${(r / 1e6).fmt(2)} MΩ"; r >= 1e3 -> "${(r / 1e3).fmt(2)} kΩ"; else -> "${r.fmt(2)} Ω" }

@Composable
fun OhmScreen() = Tabs(listOf("Loi d'Ohm", "Code couleur")) { t ->
    ToolColumn {
        if (t == 0) {
            var u by remember { mutableStateOf("") }; var i by remember { mutableStateOf("") }; var r by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
            Text("Remplis 2 valeurs, les autres sont calculées.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(u, "Tension U (V)", Modifier.weight(1f)) { u = it }; NumField(i, "Courant I (A)", Modifier.weight(1f)) { i = it } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(r, "Résistance R (Ω)", Modifier.weight(1f)) { r = it }; NumField(p, "Puissance P (W)", Modifier.weight(1f)) { p = it } }
            val U = u.num(); val I = i.num(); val R = r.num(); val P = p.num()
            val res: Map<String, Double>? = when {
                U != null && I != null -> mapOf("U" to U, "I" to I, "R" to U / I, "P" to U * I)
                U != null && R != null -> mapOf("U" to U, "I" to U / R, "R" to R, "P" to U * U / R)
                U != null && P != null -> mapOf("U" to U, "I" to P / U, "R" to U * U / P, "P" to P)
                I != null && R != null -> mapOf("U" to I * R, "I" to I, "R" to R, "P" to I * I * R)
                I != null && P != null -> mapOf("U" to P / I, "I" to I, "R" to P / (I * I), "P" to P)
                R != null && P != null -> mapOf("U" to sqrt(P * R), "I" to sqrt(P / R), "R" to R, "P" to P)
                else -> null
            }
            res?.let { m -> ResultCard {
                Text("U = ${m["U"]!!.fmt(4)} V"); Text("I = ${m["I"]!!.fmt(4)} A (${(m["I"]!! * 1000).fmt(1)} mA)")
                Text("R = ${ohmFmt(m["R"]!!)}"); Text("P = ${m["P"]!!.fmt(4)} W")
            } }
            Text("U = R × I • P = U × I", style = MaterialTheme.typography.bodySmall)
        } else {
            val sel = remember { mutableStateListOf(1, 0, 2) }
            var tol by remember { mutableIntStateOf(2) }
            Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFFE6C99A)), horizontalArrangement = Arrangement.SpaceEvenly) {
                sel.forEach { Box(Modifier.width(18.dp).fillMaxHeight().background(bandColors[it].second)) }
                Box(Modifier.width(18.dp).fillMaxHeight().background(listOf(Color(0xFF795548), Color.Red, Color(0xFFD4AF37), Color(0xFFC0C0C0))[tol]))
            }
            listOf("1re bande", "2e bande", "Multiplicateur").forEachIndexed { b, lbl ->
                Dropdown(lbl, bandColors.indices.toList(), sel[b], { "${bandColors[it].first} (${if (b == 2) "×10^$it" else "$it"})" }) { sel[b] = it }
            }
            Dropdown("Tolérance", tolerances.indices.toList(), tol, { tolerances[it].first }) { tol = it }
            val value = (sel[0] * 10 + sel[1]) * 10.0.pow(sel[2])
            ResultCard {
                Text(ohmFmt(value), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("±${tolerances[tol].second.fmt(0)} % → ${ohmFmt(value * (1 - tolerances[tol].second / 100))} à ${ohmFmt(value * (1 + tolerances[tol].second / 100))}")
            }
        }
    }
}

// ---------------- Allure de course ----------------

private fun parseDur(s: String): Double? { // h:mm:ss ou mm:ss ou minutes
    val p = s.trim().split(":").map { it.toDoubleOrNull() ?: return null }
    return when (p.size) { 1 -> p[0] * 60; 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> null }
}
private fun fmtDur(sec: Double): String { val s = sec.roundToInt(); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
fun PaceScreen() {
    var dist by remember { mutableStateOf("10") }
    var time by remember { mutableStateOf("50:00") }
    ToolColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("5" to "5 km", "10" to "10 km", "21.0975" to "Semi", "42.195" to "Marathon").forEach { (v, l) -> AssistChip(onClick = { dist = v }, label = { Text(l) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(dist, "Distance (km)", Modifier.weight(1f)) { dist = it }
            OutlinedTextField(time, { time = it }, Modifier.weight(1f), label = { Text("Temps (h:mm:ss)") }, singleLine = true)
        }
        val d = dist.num(); val t = parseDur(time)
        if (d != null && t != null && d > 0 && t > 0) {
            val pace = t / d
            ResultCard {
                Text("Allure : ${fmtDur(pace)} /km", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("Vitesse : ${(d / (t / 3600)).fmt(2)} km/h")
                Text("Allure : ${fmtDur(pace * 1.609344)} /mile")
            }
            Text("Projections (formule de Riegel)", style = MaterialTheme.typography.titleMedium)
            listOf(5.0 to "5 km", 10.0 to "10 km", 21.0975 to "Semi-marathon", 42.195 to "Marathon").forEach { (dd, l) ->
                Row { Text(l, Modifier.weight(1f)); Text(fmtDur(t * (dd / d).pow(1.06))) }
            }
        }
    }
}

// ---------------- Crypto ----------------

@Composable
fun CryptoScreen() {
    val scope = rememberCoroutineScope()
    val coins = listOf("bitcoin" to "BTC", "ethereum" to "ETH", "solana" to "SOL", "binancecoin" to "BNB", "ripple" to "XRP", "cardano" to "ADA",
        "dogecoin" to "DOGE", "tether" to "USDT", "polkadot" to "DOT", "litecoin" to "LTC", "chainlink" to "LINK", "avalanche-2" to "AVAX")
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("1") }
    var coin by remember { mutableStateOf(coins[0]) }
    fun load() = scope.launch {
        err = null
        try { data = JSONObject(httpGet("https://api.coingecko.com/api/v3/simple/price?ids=${coins.joinToString(",") { it.first }}&vs_currencies=eur,usd&include_24hr_change=true")) }
        catch (e: Exception) { err = "Impossible de charger les cours" }
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumField(amount, "Quantité", Modifier.weight(1f)) { amount = it }
                Dropdown("Crypto", coins, coin, { it.second }, Modifier.weight(1f)) { coin = it }
            }
            data?.optJSONObject(coin.first)?.let { o -> amount.num()?.let { a ->
                ResultCard { Text("${(a * o.getDouble("eur")).fmt(2)} €", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${(a * o.getDouble("usd")).fmt(2)} $") }
            } }
            Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { load() }) { Text("Actualiser") } }
        }
        items(coins) { (id, sym) ->
            data?.optJSONObject(id)?.let { o ->
                val ch = o.optDouble("eur_24h_change", 0.0)
                ListItem(headlineContent = { Text(sym) }, supportingContent = { Text(id.replaceFirstChar { it.uppercase() }) },
                    trailingContent = { Column(horizontalAlignment = Alignment.End) {
                        Text("${o.getDouble("eur").fmt(if (o.getDouble("eur") < 1) 4 else 2)} €")
                        Text("${if (ch >= 0) "▲" else "▼"} ${abs(ch).fmt(2)} %", color = if (ch >= 0) Color(0xFF43A047) else Color(0xFFE53935))
                    } })
            }
        }
        item { Text("Données CoinGecko • pas un conseil en investissement", style = MaterialTheme.typography.bodySmall) }
    }
}

// ---------------- Salaire brut ↔ net ----------------

@Composable
fun SalaryScreen() {
    val statuts = listOf("Non-cadre (≈ 22 %)" to 0.22, "Cadre (≈ 25 %)" to 0.25, "Fonction publique (≈ 15 %)" to 0.15, "Apprenti / alternant (≈ 0 %)" to 0.0, "Indépendant / portage (≈ 45 %)" to 0.45)
    var st by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf("2500") }
    var isGross by remember { mutableStateOf(true) }
    var period by remember { mutableIntStateOf(0) }
    var tax by remember { mutableStateOf("0") }
    var months by remember { mutableStateOf("12") }
    var hours by remember { mutableStateOf("35") }
    ToolColumn {
        Dropdown("Statut", statuts.indices.toList(), st, { statuts[it].first }) { st = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(isGross, { isGross = true }, label = { Text("Je connais le brut") }); FilterChip(!isGross, { isGross = false }, label = { Text("le net") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Mensuel", "Annuel", "Horaire").forEachIndexed { i, l -> FilterChip(period == i, { period = i }, label = { Text(l) }) } }
        NumField(value, "Montant (€)", Modifier.fillMaxWidth()) { value = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(tax, "Taux prél. source (%)", Modifier.weight(1f)) { tax = it }
            NumField(months, "Mois/an", Modifier.weight(0.7f)) { months = it }
            NumField(hours, "h/semaine", Modifier.weight(0.7f)) { hours = it }
        }
        val v = value.num(); val rate = statuts[st].second; val m = months.num() ?: 12.0; val h = (hours.num() ?: 35.0) * 52 / 12
        if (v != null) {
            val monthly = when (period) { 1 -> v / m; 2 -> v * h; else -> v }
            val gross = if (isGross) monthly else monthly / (1 - rate)
            val net = gross * (1 - rate)
            val after = net * (1 - (tax.num() ?: 0.0) / 100)
            ResultCard {
                Text("Brut : ${gross.fmt(2)} €/mois • ${(gross * m).fmt(0)} €/an", style = MaterialTheme.typography.titleMedium)
                Text("Net avant impôt : ${net.fmt(2)} €/mois", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("Net après impôt : ${after.fmt(2)} €/mois")
                Text("Net annuel : ${(net * m).fmt(0)} € • horaire net ${(net / h).fmt(2)} €")
            }
        }
        Text("Estimation avec taux moyens de cotisations, le bulletin réel peut varier.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Épargne ----------------

@Composable
fun SavingsScreen() {
    var initial by remember { mutableStateOf("1000") }; var monthly by remember { mutableStateOf("100") }
    var rate by remember { mutableStateOf("3") }; var years by remember { mutableStateOf("10") }
    var target by remember { mutableStateOf("") }
    ToolColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(initial, "Capital de départ (€)", Modifier.weight(1f)) { initial = it }; NumField(monthly, "Versement / mois (€)", Modifier.weight(1f)) { monthly = it } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(rate, "Taux annuel (%)", Modifier.weight(1f)) { rate = it }; NumField(years, "Durée (ans)", Modifier.weight(1f)) { years = it } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Livret A" to "2.4", "LDDS" to "2.4", "LEP" to "3.5", "Fonds euros" to "2.5", "Actions" to "7").forEach { (n, r) -> AssistChip(onClick = { rate = r }, label = { Text(n) }) } }
        val c0 = initial.num() ?: 0.0; val pm = monthly.num() ?: 0.0; val r = (rate.num() ?: 0.0) / 100 / 12; val n = ((years.num() ?: 0.0) * 12).toInt()
        val pts = mutableListOf<Float>(); var bal = c0
        for (i in 1..n) { bal = bal * (1 + r) + pm; if (i % 12 == 0) pts += bal.toFloat() }
        val invested = c0 + pm * n
        ResultCard {
            Text("Capital final : ${bal.fmt(0)} €", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("Versé : ${invested.fmt(0)} € • Intérêts : ${(bal - invested).fmt(0)} €")
        }
        if (pts.size >= 2) LineChart(listOf(c0.toFloat()) + pts)
        HorizontalDivider()
        NumField(target, "Objectif à atteindre (€)", Modifier.fillMaxWidth()) { target = it }
        target.num()?.let { tg ->
            var b = c0; var mo = 0
            while (b < tg && mo < 1200) { b = b * (1 + r) + pm; mo++ }
            Text(if (mo >= 1200) "Objectif hors d'atteinte en 100 ans" else "Atteint en ${mo / 12} an(s) et ${mo % 12} mois", style = MaterialTheme.typography.titleMedium)
        }
        Text("Intérêts composés mensuellement, hors fiscalité.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Inflation ----------------

// Inflation annuelle France (INSEE, IPC moyenne annuelle, %)
private val frInflation = mapOf(1990 to 3.4, 1991 to 3.2, 1992 to 2.4, 1993 to 2.1, 1994 to 1.7, 1995 to 1.8, 1996 to 2.0, 1997 to 1.2, 1998 to 0.7, 1999 to 0.5,
    2000 to 1.7, 2001 to 1.6, 2002 to 1.9, 2003 to 2.1, 2004 to 2.1, 2005 to 1.7, 2006 to 1.7, 2007 to 1.5, 2008 to 2.8, 2009 to 0.1, 2010 to 1.5,
    2011 to 2.1, 2012 to 2.0, 2013 to 0.9, 2014 to 0.5, 2015 to 0.0, 2016 to 0.2, 2017 to 1.0, 2018 to 1.8, 2019 to 1.1, 2020 to 0.5, 2021 to 1.6,
    2022 to 5.2, 2023 to 4.9, 2024 to 2.0, 2025 to 1.0)

@Composable
fun InflationScreen() = Tabs(listOf("Historique France", "Projection")) { t ->
    ToolColumn {
        if (t == 0) {
            var amount by remember { mutableStateOf("100") }
            var from by remember { mutableIntStateOf(2000) }; var to by remember { mutableIntStateOf(2025) }
            val years = frInflation.keys.sorted()
            NumField(amount, "Somme (€)", Modifier.fillMaxWidth()) { amount = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("En", years, from, { "$it" }, Modifier.weight(1f)) { from = it }; Dropdown("Vaut en", years, to, { "$it" }, Modifier.weight(1f)) { to = it }
            }
            val a = amount.num()
            if (a != null) {
                var f = 1.0
                if (to > from) for (y in from + 1..to) f *= 1 + frInflation[y]!! / 100 else for (y in to + 1..from) f /= 1 + frInflation[y]!! / 100
                ResultCard {
                    Text("${a.fmt(2)} € en $from ≈ ${(a * f).fmt(2)} € en $to", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Hausse des prix cumulée : ${((f - 1) * 100).fmt(1)} %")
                    Text("Perte de pouvoir d'achat d'une somme non placée : ${((1 - 1 / f) * 100).fmt(1)} %")
                }
            }
            Text("Source : INSEE (indice des prix, moyennes annuelles, valeurs arrondies, 2025 estimé).", style = MaterialTheme.typography.bodySmall)
        } else {
            var amount by remember { mutableStateOf("1000") }; var rate by remember { mutableStateOf("2") }; var years by remember { mutableStateOf("10") }
            NumField(amount, "Somme (€)", Modifier.fillMaxWidth()) { amount = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(rate, "Inflation (%/an)", Modifier.weight(1f)) { rate = it }; NumField(years, "Années", Modifier.weight(1f)) { years = it } }
            val a = amount.num(); val r = rate.num(); val y = years.num()
            if (a != null && r != null && y != null) ResultCard {
                val f = (1 + r / 100).pow(y)
                Text("Dans ${y.fmt(0)} ans, il faudra ${(a * f).fmt(2)} € pour acheter ce qui coûte ${a.fmt(2)} € aujourd'hui")
                Text("${a.fmt(2)} € gardés sous le matelas vaudront ${(a / f).fmt(2)} € d'aujourd'hui", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------- Heures travaillées ----------------

@Composable
fun WorkHoursScreen() = Tabs(listOf("Feuille de temps", "Conversion")) { t ->
    if (t == 0) {
        var weekly by remember { mutableStateOf("35") }
        RecordsScreen("workhours",
            listOf(Field("date", "Jour", FType.DATE), Field("start", "Début", FType.TIME), Field("end", "Fin", FType.TIME), Field("pause", "Pause (min)", FType.NUM), Field("note", "Note")),
            title = { o -> workMin(o)?.let { "${it / 60} h ${"%02d".format(it % 60)}" } ?: "?" },
            subtitle = { "${isoToFr(it.optString("date"))} • ${it.optString("start")} → ${it.optString("end")}" },
            sort = { l -> l.sortedByDescending { it.optString("date") } }, addLabel = "+ Journée",
            header = { l ->
                val now = LocalDate.now(); val monday = now.minusDays(now.dayOfWeek.value - 1L)
                val week = l.filter { (runCatching { LocalDate.parse(it.optString("date")) }.getOrNull() ?: LocalDate.MIN) >= monday }.sumOf { workMin(it) ?: 0 }
                val month = l.filter { it.optString("date").startsWith(now.toString().take(7)) }.sumOf { workMin(it) ?: 0 }
                val target = ((weekly.num() ?: 35.0) * 60).toInt()
                NumField(weekly, "Heures contrat / semaine", Modifier.fillMaxWidth()) { weekly = it }
                ResultCard {
                    Text("Cette semaine : ${week / 60} h ${"%02d".format(week % 60)}", style = MaterialTheme.typography.titleLarge)
                    val extra = week - target
                    Text(if (extra > 0) "➕ ${extra / 60} h ${"%02d".format(extra % 60)} supplémentaires" else "Reste ${-extra / 60} h ${"%02d".format(-extra % 60)}",
                        color = if (extra > 0) Color(0xFFFB8C00) else MaterialTheme.colorScheme.outline)
                    Text("Ce mois : ${month / 60} h ${"%02d".format(month % 60)} (${(month / 60.0).fmt(2)} h décimales)")
                }
            })
    } else ToolColumn {
        var hm by remember { mutableStateOf("7:45") }; var dec by remember { mutableStateOf("7.75") }
        OutlinedTextField(hm, { hm = it }, Modifier.fillMaxWidth(), label = { Text("Heures:minutes") }, singleLine = true)
        hm.split(":").let { p -> val h = p.getOrNull(0)?.toDoubleOrNull(); val m = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            if (h != null) Text("= ${(h + m / 60).fmt(4)} h décimales", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
        NumField(dec, "Heures décimales", Modifier.fillMaxWidth()) { dec = it }
        dec.num()?.let { Text("= ${it.toInt()} h ${"%02d".format(((it % 1) * 60).roundToInt())} min", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
    }
}

private fun workMin(o: JSONObject): Int? {
    val s = runCatching { LocalTime.parse(o.optString("start")) }.getOrNull() ?: return null
    val e = runCatching { LocalTime.parse(o.optString("end")) }.getOrNull() ?: return null
    var m = ChronoUnit.MINUTES.between(s, e).toInt(); if (m < 0) m += 1440
    return m - (o.optString("pause").num()?.toInt() ?: 0)
}

// ---------------- Conversions cuisine ----------------

private val ingredients = linkedMapOf( // grammes pour 1 tasse US (240 ml)
    "Eau / lait" to 240.0, "Farine" to 125.0, "Sucre en poudre" to 200.0, "Sucre glace" to 120.0, "Cassonade" to 220.0, "Beurre" to 227.0,
    "Riz" to 185.0, "Flocons d'avoine" to 90.0, "Cacao" to 85.0, "Miel" to 340.0, "Huile" to 218.0, "Sel" to 290.0, "Amandes en poudre" to 96.0,
    "Pépites de chocolat" to 170.0, "Crème" to 240.0, "Yaourt" to 245.0, "Fromage râpé" to 100.0)
private val cookUnits = linkedMapOf("g" to 0.0, "kg" to 0.0, "tasse US" to 240.0, "ml" to 1.0, "cl" to 10.0, "dl" to 100.0, "l" to 1000.0,
    "c. à soupe" to 15.0, "c. à café" to 5.0, "verre (20 cl)" to 200.0, "once (oz)" to 0.0, "livre (lb)" to 0.0, "fl oz US" to 29.57)

@Composable
fun CookConvScreen() {
    var ing by remember { mutableStateOf("Farine") }; var unit by remember { mutableStateOf("tasse US") }; var v by remember { mutableStateOf("1") }
    var tempC by remember { mutableStateOf("180") }
    ToolColumn {
        Dropdown("Ingrédient", ingredients.keys.toList(), ing, { it }) { ing = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(v, "Quantité", Modifier.weight(1f)) { v = it }; Dropdown("Unité", cookUnits.keys.toList(), unit, { it }, Modifier.weight(1f)) { unit = it } }
        val a = v.num()
        if (a != null) {
            val gPerMl = ingredients[ing]!! / 240.0
            val grams = when (unit) { "g" -> a; "kg" -> a * 1000; "once (oz)" -> a * 28.3495; "livre (lb)" -> a * 453.592; else -> a * cookUnits[unit]!! * gPerMl }
            val ml = grams / gPerMl
            ResultCard {
                Text("${grams.fmt(1)} g", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("${ml.fmt(1)} ml • ${(ml / 240).fmt(2)} tasse US")
                Text("${(ml / 15).fmt(1)} c. à soupe • ${(ml / 5).fmt(1)} c. à café")
                Text("${(grams / 28.3495).fmt(2)} oz")
            }
        }
        HorizontalDivider()
        Text("Four", style = MaterialTheme.typography.titleMedium)
        NumField(tempC, "°C", Modifier.fillMaxWidth()) { tempC = it }
        tempC.num()?.let { c -> Text("= ${(c * 9 / 5 + 32).roundToInt()} °F • thermostat ${(c / 30).fmt(1)}", style = MaterialTheme.typography.titleMedium) }
        Text("1 œuf moyen ≈ 50 g • 1 sachet de levure = 11 g • 1 noix de beurre ≈ 10 g", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Peinture & carrelage ----------------

@Composable
fun PaintScreen() = Tabs(listOf("Peinture", "Carrelage / parquet", "Papier peint")) { t ->
    ToolColumn {
        when (t) {
            0 -> {
                var l by remember { mutableStateOf("4") }; var w by remember { mutableStateOf("3.5") }; var h by remember { mutableStateOf("2.5") }
                var openings by remember { mutableStateOf("3.5") }; var coats by remember { mutableStateOf("2") }; var cov by remember { mutableStateOf("10") }
                var ceiling by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField(l, "Longueur (m)", Modifier.weight(1f)) { l = it }; NumField(w, "Largeur (m)", Modifier.weight(1f)) { w = it }; NumField(h, "Hauteur (m)", Modifier.weight(1f)) { h = it } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField(openings, "Portes+fenêtres (m²)", Modifier.weight(1f)) { openings = it }; NumField(coats, "Couches", Modifier.weight(0.6f)) { coats = it }; NumField(cov, "m²/L", Modifier.weight(0.6f)) { cov = it } }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ceiling, { ceiling = it }); Text("Inclure le plafond") }
                val L = l.num(); val W = w.num(); val H = h.num()
                if (L != null && W != null && H != null) {
                    val area = (2 * (L + W) * H - (openings.num() ?: 0.0) + if (ceiling) L * W else 0.0).coerceAtLeast(0.0)
                    val liters = area * (coats.num() ?: 2.0) / (cov.num() ?: 10.0)
                    ResultCard {
                        Text("Surface à peindre : ${area.fmt(1)} m²")
                        Text("Peinture : ${liters.fmt(1)} L", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        Text("Pots conseillés : ${ceil(liters / 2.5).toInt()} × 2,5 L ou ${ceil(liters / 10).toInt()} × 10 L")
                        Text("Porte standard ≈ 1,6 m² • fenêtre ≈ 1,5 m²", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            1 -> {
                var l by remember { mutableStateOf("4") }; var w by remember { mutableStateOf("3") }
                var tl by remember { mutableStateOf("60") }; var tw by remember { mutableStateOf("60") }; var waste by remember { mutableStateOf("10") }; var box by remember { mutableStateOf("1.44") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField(l, "Pièce long. (m)", Modifier.weight(1f)) { l = it }; NumField(w, "Pièce larg. (m)", Modifier.weight(1f)) { w = it } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField(tl, "Carreau (cm)", Modifier.weight(1f)) { tl = it }; NumField(tw, "× (cm)", Modifier.weight(1f)) { tw = it } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField(waste, "Chutes (%)", Modifier.weight(1f)) { waste = it }; NumField(box, "m² par carton", Modifier.weight(1f)) { box = it } }
                val area = (l.num() ?: 0.0) * (w.num() ?: 0.0) * (1 + (waste.num() ?: 0.0) / 100)
                val tile = (tl.num() ?: 1.0) * (tw.num() ?: 1.0) / 10000
                ResultCard {
                    Text("Surface (avec chutes) : ${area.fmt(2)} m²")
                    Text("${ceil(area / tile).toInt()} carreaux", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    box.num()?.takeIf { it > 0 }?.let { Text("${ceil(area / it).toInt()} cartons") }
                    Text("Plinthes : ${(2 * ((l.num() ?: 0.0) + (w.num() ?: 0.0))).fmt(1)} m linéaires")
                }
            }
            else -> {
                var per by remember { mutableStateOf("14") }; var h by remember { mutableStateOf("2.5") }
                var rollL by remember { mutableStateOf("10.05") }; var rollW by remember { mutableStateOf("0.53") }; var raccord by remember { mutableStateOf("0") }
                NumField(per, "Périmètre des murs à tapisser (m)", Modifier.fillMaxWidth()) { per = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField(h, "Hauteur (m)", Modifier.weight(1f)) { h = it }; NumField(raccord, "Raccord (cm)", Modifier.weight(1f)) { raccord = it } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField(rollL, "Long. rouleau (m)", Modifier.weight(1f)) { rollL = it }; NumField(rollW, "Larg. rouleau (m)", Modifier.weight(1f)) { rollW = it } }
                val stripH = (h.num() ?: 2.5) + 0.1 + (raccord.num() ?: 0.0) / 100
                val stripsPerRoll = floor((rollL.num() ?: 10.05) / stripH).coerceAtLeast(1.0)
                val strips = ceil((per.num() ?: 0.0) / (rollW.num() ?: 0.53))
                ResultCard {
                    Text("$strips lés de ${stripH.fmt(2)} m • ${stripsPerRoll.toInt()} lés par rouleau")
                    Text("${ceil(strips / stripsPerRoll).toInt()} rouleaux", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ---------------- Tailles de vêtements ----------------

@Composable
fun SizesScreen() = Tabs(listOf("Chaussures", "Hauts femme", "Hauts homme", "Jeans")) { t ->
    val (head, rows) = when (t) {
        0 -> listOf("EU", "UK", "US H", "US F", "cm") to listOf(
            listOf("36", "3,5", "4,5", "6", "22,5"), listOf("37", "4", "5", "6,5", "23"), listOf("38", "5", "6", "7,5", "24"), listOf("39", "6", "7", "8,5", "24,5"),
            listOf("40", "6,5", "7,5", "9", "25"), listOf("41", "7", "8", "9,5", "26"), listOf("42", "8", "9", "10,5", "26,5"), listOf("43", "9", "10", "11,5", "27,5"),
            listOf("44", "9,5", "10,5", "12", "28"), listOf("45", "10,5", "11,5", "13", "29"), listOf("46", "11", "12", "13,5", "29,5"), listOf("47", "12", "13", "14,5", "30,5"))
        1 -> listOf("FR", "Intl", "UK", "US", "IT", "DE") to listOf(
            listOf("34", "XS", "6", "2", "38", "32"), listOf("36", "S", "8", "4", "40", "34"), listOf("38", "M", "10", "6", "42", "36"), listOf("40", "L", "12", "8", "44", "38"),
            listOf("42", "XL", "14", "10", "46", "40"), listOf("44", "XXL", "16", "12", "48", "42"), listOf("46", "3XL", "18", "14", "50", "44"))
        2 -> listOf("FR", "Intl", "Tour poitrine (cm)", "Chemise (col)") to listOf(
            listOf("44", "XS", "86-90", "37"), listOf("46", "S", "90-94", "38"), listOf("48", "M", "94-98", "39-40"), listOf("50", "L", "98-102", "41"),
            listOf("52", "XL", "102-106", "42-43"), listOf("54", "XXL", "106-110", "44"), listOf("56", "3XL", "110-114", "45-46"))
        else -> listOf("FR", "US (W)", "Tour de taille (cm)") to listOf(
            listOf("36", "26", "66"), listOf("38", "28", "71"), listOf("40", "30", "76"), listOf("42", "32", "81"), listOf("44", "34", "86"),
            listOf("46", "36", "91"), listOf("48", "38", "97"), listOf("50", "40", "102"))
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp)) { head.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall) } } }
        items(rows) { r -> Row(Modifier.padding(8.dp)) { r.forEach { Text(it, Modifier.weight(1f)) } }; HorizontalDivider() }
        item { Text("Correspondances indicatives : chaque marque taille différemment.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    }
}
