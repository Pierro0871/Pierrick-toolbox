package com.pierrick.toolbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigInteger
import kotlin.math.pow

@Composable
fun NumField(value: String, label: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

@Composable
fun ResultCard(content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) }

@Composable
fun Tabs(titles: List<String>, content: @Composable (Int) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        ScrollableTabRow(tab, edgePadding = 8.dp) { titles.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
        content(tab)
    }
}

// ---------------- IMC & calories ----------------

@Composable
fun BmiScreen() {
    var weight by remember { mutableStateOf("70") }
    var height by remember { mutableStateOf("175") }
    var age by remember { mutableStateOf("30") }
    var male by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(1) }
    val acts = listOf("Sédentaire" to 1.2, "Légère (1-3/sem)" to 1.375, "Modérée (3-5/sem)" to 1.55, "Intense (6-7/sem)" to 1.725, "Très intense" to 1.9)
    val w = weight.num(); val h = height.num()?.div(100); val a = age.num()
    ToolColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(weight, "Poids (kg)", Modifier.weight(1f)) { weight = it }
            NumField(height, "Taille (cm)", Modifier.weight(1f)) { height = it }
            NumField(age, "Âge", Modifier.weight(1f)) { age = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(male, { male = true }, label = { Text("Homme") })
            FilterChip(!male, { male = false }, label = { Text("Femme") })
        }
        Dropdown("Activité physique", acts.indices.toList(), activity, { acts[it].first }) { activity = it }
        if (w != null && h != null && h > 0) {
            val bmi = w / (h * h)
            val (lbl, col) = when {
                bmi < 18.5 -> "Maigreur" to Color(0xFF42A5F5); bmi < 25 -> "Corpulence normale" to Color(0xFF43A047)
                bmi < 30 -> "Surpoids" to Color(0xFFFB8C00); else -> "Obésité" to Color(0xFFE53935)
            }
            ResultCard {
                Text("IMC : %.1f".format(bmi), style = MaterialTheme.typography.headlineMedium, color = col)
                Text(lbl, color = col)
                Text("Poids « normal » pour ta taille : %.0f – %.0f kg".format(18.5 * h * h, 24.9 * h * h))
            }
            if (a != null) {
                val bmr = 10 * w + 6.25 * h * 100 - 5 * a + if (male) 5 else -161
                val tdee = bmr * acts[activity].second
                ResultCard {
                    Text("Métabolisme de base : ${bmr.toInt()} kcal/j")
                    Text("Maintien : ${tdee.toInt()} kcal/j", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Perte (−0,5 kg/sem) : ${(tdee - 500).toInt()} kcal/j")
                    Text("Prise (+0,5 kg/sem) : ${(tdee + 500).toInt()} kcal/j")
                    Text("Eau conseillée : ≈ %.1f L/j".format(w * 0.033))
                }
            }
        }
        Text("Indications générales (formule Mifflin-St Jeor), pas un avis médical.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Prêt & pourcentages ----------------

@Composable
fun LoanScreen() = Tabs(listOf("Prêt", "Pourcentages", "TVA")) { t ->
    when (t) {
        0 -> {
            var capital by remember { mutableStateOf("150000") }
            var rate by remember { mutableStateOf("3.5") }
            var years by remember { mutableStateOf("20") }
            var insurance by remember { mutableStateOf("0.3") }
            ToolColumn {
                NumField(capital, "Montant emprunté (€)", Modifier.fillMaxWidth()) { capital = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(rate, "Taux annuel (%)", Modifier.weight(1f)) { rate = it }
                    NumField(years, "Durée (ans)", Modifier.weight(1f)) { years = it }
                    NumField(insurance, "Assurance (%)", Modifier.weight(1f)) { insurance = it }
                }
                val c = capital.num(); val r = rate.num(); val y = years.num(); val ins = insurance.num() ?: 0.0
                if (c != null && r != null && y != null && y > 0) {
                    val n = (y * 12).toInt(); val i = r / 100 / 12
                    val m = if (i == 0.0) c / n else c * i / (1 - (1 + i).pow(-n))
                    val insM = c * ins / 100 / 12
                    ResultCard {
                        Text("Mensualité : ${(m + insM).fmt(2)} €", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        Text("dont assurance : ${insM.fmt(2)} €/mois")
                        Text("Coût des intérêts : ${(m * n - c).fmt(2)} €")
                        Text("Coût total du crédit : ${(m * n - c + insM * n).fmt(2)} €")
                        Text("Total remboursé : ${((m + insM) * n).fmt(2)} €")
                    }
                }
            }
        }
        1 -> {
            var a by remember { mutableStateOf("200") }
            var b by remember { mutableStateOf("15") }
            val x = a.num(); val y = b.num()
            ToolColumn {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(a, "Valeur A", Modifier.weight(1f)) { a = it }
                    NumField(b, "Valeur B", Modifier.weight(1f)) { b = it }
                }
                if (x != null && y != null) ResultCard {
                    Text("B % de A = ${(x * y / 100).fmt(4)}")
                    Text("A + B % = ${(x * (1 + y / 100)).fmt(4)}")
                    Text("A − B % (remise) = ${(x * (1 - y / 100)).fmt(4)}", color = MaterialTheme.colorScheme.primary)
                    if (x != 0.0) Text("B représente ${(y / x * 100).fmt(2)} % de A")
                    if (x != 0.0) Text("Évolution de A vers B : ${((y - x) / x * 100).fmt(2)} %")
                }
            }
        }
        else -> {
            var amount by remember { mutableStateOf("100") }
            var vat by remember { mutableDoubleStateOf(20.0) }
            val v = amount.num()
            ToolColumn {
                NumField(amount, "Montant (€)", Modifier.fillMaxWidth()) { amount = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20.0, 10.0, 5.5, 2.1).forEach { r -> FilterChip(vat == r, { vat = r }, label = { Text("${r.fmt(1)} %") }) }
                }
                if (v != null) ResultCard {
                    Text("Si HT → TTC : ${(v * (1 + vat / 100)).fmt(2)} € (TVA ${(v * vat / 100).fmt(2)} €)")
                    Text("Si TTC → HT : ${(v / (1 + vat / 100)).fmt(2)} € (TVA ${(v - v / (1 + vat / 100)).fmt(2)} €)")
                }
            }
        }
    }
}

// ---------------- Carburant & trajet ----------------

@Composable
fun FuelScreen() = Tabs(listOf("Coût d'un trajet", "Ma consommation")) { t ->
    if (t == 0) {
        var dist by remember { mutableStateOf("100") }
        var conso by remember { mutableStateOf("6.5") }
        var price by remember { mutableStateOf("1.85") }
        var tolls by remember { mutableStateOf("0") }
        var people by remember { mutableStateOf("1") }
        var roundTrip by remember { mutableStateOf(false) }
        ToolColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(dist, "Distance (km)", Modifier.weight(1f)) { dist = it }
                NumField(conso, "L/100 km", Modifier.weight(1f)) { conso = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(price, "Prix €/L", Modifier.weight(1f)) { price = it }
                NumField(tolls, "Péages (€)", Modifier.weight(1f)) { tolls = it }
                NumField(people, "Personnes", Modifier.weight(1f)) { people = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(roundTrip, { roundTrip = it }); Text("Aller-retour") }
            val d = dist.num(); val c = conso.num(); val p = price.num()
            if (d != null && c != null && p != null) {
                val k = if (roundTrip) 2 else 1
                val liters = d * k * c / 100
                val total = liters * p + (tolls.num() ?: 0.0) * k
                val n = people.num()?.takeIf { it >= 1 } ?: 1.0
                ResultCard {
                    Text("Carburant : ${liters.fmt(2)} L")
                    Text("Coût total : ${total.fmt(2)} €", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    if (n > 1) Text("Par personne : ${(total / n).fmt(2)} €")
                    Text("Coût au km : ${(total / (d * k)).fmt(3)} €")
                }
            }
        }
    } else {
        var km by remember { mutableStateOf("600") }
        var liters by remember { mutableStateOf("40") }
        val k = km.num(); val l = liters.num()
        ToolColumn {
            Text("Fais le plein, note les km parcourus depuis le dernier plein et les litres ajoutés.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(km, "Km parcourus", Modifier.weight(1f)) { km = it }
                NumField(liters, "Litres", Modifier.weight(1f)) { liters = it }
            }
            if (k != null && l != null && k > 0) ResultCard {
                Text("${(l / k * 100).fmt(2)} L/100 km", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("${(k / l).fmt(2)} km/L")
            }
        }
    }
}

// ---------------- Bases & romains ----------------

private fun toRoman(n: Int): String {
    val v = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    var x = n; return buildString { v.forEach { (k, s) -> while (x >= k) { append(s); x -= k } } }
}

private fun fromRoman(s: String): Int? {
    val m = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
    val u = s.trim().uppercase(); if (u.isEmpty() || u.any { it !in m }) return null
    var t = 0
    for (i in u.indices) { val a = m[u[i]]!!; val b = if (i + 1 < u.length) m[u[i + 1]]!! else 0; t += if (a < b) -a else a }
    return t.takeIf { toRoman(it) == u }
}

@Composable
fun BasesScreen() {
    var value by remember { mutableStateOf<BigInteger?>(BigInteger.valueOf(42)) }
    var roman by remember { mutableStateOf("XLII") }
    val bases = listOf(10 to "Décimal", 2 to "Binaire", 16 to "Hexadécimal", 8 to "Octal")
    val texts = remember { mutableStateMapOf<Int, String>() }
    fun setFrom(b: Int?, v: BigInteger?) {
        value = v
        bases.forEach { (bb, _) -> if (bb != b) texts[bb] = v?.toString(bb)?.uppercase() ?: "" }
        if (b != null) roman = v?.toInt()?.takeIf { v < BigInteger.valueOf(4000) && it > 0 }?.let { toRoman(it) } ?: ""
    }
    LaunchedEffect(Unit) { setFrom(-1, value) }
    ToolColumn {
        bases.forEach { (b, name) ->
            OutlinedTextField(texts[b] ?: "", { s ->
                texts[b] = s
                setFrom(b, s.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigInteger(it, b) }.getOrNull() })
            }, Modifier.fillMaxWidth(), label = { Text("$name (base $b)") }, singleLine = true,
                textStyle = LocalTextStyleMono(),
                keyboardOptions = KeyboardOptions(keyboardType = if (b == 16) KeyboardType.Ascii else KeyboardType.Number))
        }
        OutlinedTextField(roman, { s -> roman = s; fromRoman(s)?.let { setFrom(null, BigInteger.valueOf(it.toLong())) } },
            Modifier.fillMaxWidth(), label = { Text("Chiffres romains (1–3999)") }, singleLine = true)
        value?.let { v -> if (v.bitLength() <= 32 && v.signum() >= 0) Text("ASCII : " + (if (v.toInt() in 32..126) "'${v.toInt().toChar()}'" else "—"), color = MaterialTheme.colorScheme.outline) }
    }
}

@Composable
private fun LocalTextStyleMono() = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
