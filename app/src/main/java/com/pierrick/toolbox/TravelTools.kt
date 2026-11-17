package com.pierrick.toolbox

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

// ---------------- Localisation ----------------

@Composable
fun rememberLocationPermission(): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.any { v -> v } }
    return granted to { req.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

@SuppressLint("MissingPermission")
fun lastLocation(ctx: Context): Location? = runCatching {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
}.getOrNull()

@SuppressLint("MissingPermission")
@Composable
fun rememberLiveLocation(enabled: Boolean): Location? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf(if (enabled) lastLocation(ctx) else null) }
    DisposableEffect(enabled) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = LocationListener { loc = it }
        if (enabled) runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, l)
        }
        onDispose { lm.removeUpdates(l) }
    }
    return loc
}

fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val r = 6371.0; val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
    val a = sin(dLa / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val y = sin(Math.toRadians(lo2 - lo1)) * cos(Math.toRadians(la2))
    val x = cos(Math.toRadians(la1)) * sin(Math.toRadians(la2)) - sin(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * cos(Math.toRadians(lo2 - lo1))
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

private fun dms(v: Double, pos: String, neg: String): String {
    val a = abs(v); val d = a.toInt(); val m = ((a - d) * 60).toInt(); val s = ((a - d) * 60 - m) * 60
    return "%d°%02d'%04.1f\" %s".format(d, m, s, if (v >= 0) pos else neg)
}

// ---------------- GPS & compteur de vitesse ----------------

@Composable
fun GpsScreen(speedo: Boolean) {
    val ctx = LocalContext.current
    val (granted, ask) = rememberLocationPermission()
    LaunchedEffect(Unit) { if (!granted) ask() }
    val loc = rememberLiveLocation(granted)
    var maxSpeed by remember { mutableFloatStateOf(0f) }
    var dist by remember { mutableDoubleStateOf(0.0) }
    var last by remember { mutableStateOf<Location?>(null) }
    var start by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(loc) {
        loc ?: return@LaunchedEffect
        val kmh = loc.speed * 3.6f
        if (loc.hasSpeed() && kmh > maxSpeed) maxSpeed = kmh
        last?.let { if (loc.accuracy < 30 && kmh > 2) dist += it.distanceTo(loc) / 1000.0 }
        last = loc
    }
    ToolColumn {
        if (!granted) { Button(onClick = ask) { Text("Autoriser la localisation") }; return@ToolColumn }
        if (loc == null) { Text("Recherche du signal GPS… (va à l'extérieur)"); LinearProgressIndicator(Modifier.fillMaxWidth()); return@ToolColumn }
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        if (speedo) {
            val c = MaterialTheme.colorScheme.primary; val bg = MaterialTheme.colorScheme.surfaceVariant
            Box(Modifier.fillMaxWidth().aspectRatio(1.4f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension * 0.45f; val tl = Offset(center.x - r, center.y - r * 0.6f)
                    drawArc(bg, 150f, 240f, false, tl, androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = androidx.compose.ui.graphics.drawscope.Stroke(30f))
                    drawArc(c, 150f, 240f * (kmh / 200f).coerceIn(0f, 1f), false, tl, androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = androidx.compose.ui.graphics.drawscope.Stroke(30f))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${kmh.roundToInt()}", fontSize = 84.sp, fontWeight = FontWeight.Bold)
                    Text("km/h", color = MaterialTheme.colorScheme.outline)
                }
            }
            ResultCard {
                Text("Vitesse max : ${maxSpeed.roundToInt()} km/h")
                Text("Distance : ${dist.fmt(2)} km")
                val h = (System.currentTimeMillis() - start) / 3_600_000.0
                Text("Moyenne : ${if (h > 0) (dist / h).fmt(1) else "0"} km/h")
                Text("Précision : ±${loc.accuracy.roundToInt()} m", color = MaterialTheme.colorScheme.outline)
            }
            OutlinedButton(onClick = { maxSpeed = 0f; dist = 0.0; start = System.currentTimeMillis() }) { Text("Réinitialiser") }
            Text("Garde l'écran allumé. La vitesse GPS peut différer légèrement du compteur de la voiture.", style = MaterialTheme.typography.bodySmall)
        } else {
            ResultCard {
                Text("%.6f, %.6f".format(loc.latitude, loc.longitude), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(dms(loc.latitude, "N", "S") + "   " + dms(loc.longitude, "E", "O"))
                Text("Altitude : ${if (loc.hasAltitude()) "${loc.altitude.roundToInt()} m" else "—"}")
                Text("Précision : ±${loc.accuracy.roundToInt()} m • vitesse ${kmh.roundToInt()} km/h")
                if (loc.hasBearing()) Text("Cap : ${loc.bearing.roundToInt()}°")
                Text("Source : ${loc.provider} • ${Instant.ofEpochMilli(loc.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", color = MaterialTheme.colorScheme.outline)
            }
            val link = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, loc.latitude, loc.longitude)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareText(ctx, "📍 Je suis ici : $link") }) { Text("📤 Partager ma position") }
                OutlinedButton(onClick = { copy(ctx, "%.6f, %.6f".format(Locale.US, loc.latitude, loc.longitude)) }) { Text("Copier") }
            }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}"))) }) { Text("🗺️ Ouvrir dans Maps") }
            Text("En cas d'urgence, donne ces coordonnées au 112.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- Traducteur ----------------

private val phrases = listOf("Bonjour", "Merci beaucoup", "S'il vous plaît", "Excusez-moi", "Je ne comprends pas", "Parlez-vous français ?",
    "Combien ça coûte ?", "Où sont les toilettes ?", "L'addition, s'il vous plaît", "Je voudrais réserver une table pour deux", "Je suis allergique à…",
    "Où est la gare ?", "Pouvez-vous m'aider ?", "J'ai besoin d'un médecin", "Appelez la police !", "Je suis perdu", "Une bière, s'il vous plaît",
    "Avez-vous une chambre libre ?", "À quelle heure ça ferme ?", "Pouvez-vous parler plus lentement ?", "Où puis-je prendre un taxi ?", "C'est délicieux !")

@Composable
fun PhrasesScreen() {
    val ctx = LocalContext.current
    var src by remember { mutableStateOf("fr") }
    var target by remember { mutableStateOf("en") }
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) { tts[0] = TextToSpeech(ctx) {}; onDispose { tts[0]?.shutdown() } }
    fun run(t: String) {
        if (t.isBlank()) return
        busy = true; err = null
        translate(t, target, src) { r, e -> result = r ?: ""; err = e; busy = false }
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("De", translateLangs, translateLangs.first { it.first == src }, { it.second }, Modifier.weight(1f)) { src = it.first }
                IconButton(onClick = { val t = src; src = target; target = t; text = result.also { result = text } }) { Text("⇄", fontSize = 22.sp) }
                Dropdown("Vers", translateLangs, translateLangs.first { it.first == target }, { it.second }, Modifier.weight(1f)) { target = it.first; run(text) }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Texte") }, minLines = 2)
            Button(onClick = { run(text) }, Modifier.fillMaxWidth()) { Text("🈯 Traduire") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (result.isNotBlank()) ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(result, fontSize = 24.sp)
                    Row {
                        TextButton(onClick = { tts[0]?.run { language = Locale.forLanguageTag(target); speak(result, TextToSpeech.QUEUE_FLUSH, null, "p") } }) { Text("🔊 Écouter") }
                        TextButton(onClick = { copy(ctx, result) }) { Text("Copier") }
                    }
                }
            }
            Text("Phrases utiles (touche pour traduire)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
        items(phrases) { p -> Text("• $p", Modifier.fillMaxWidth().clickable { src = "fr"; text = p; run(p) }.padding(vertical = 6.dp)) }
        item { Text("Fonctionne hors ligne une fois la langue téléchargée.", style = MaterialTheme.typography.bodySmall) }
    }
}

// ---------------- Soleil & lune ----------------

private fun jdToTime(j: Double, zone: ZoneId): LocalTime? = if (j.isNaN()) null else Instant.ofEpochMilli(((j - 2440587.5) * 86400000).toLong()).atZone(zone).toLocalTime()

/** Lever / transit / coucher (algorithme de l'équation du lever du soleil). altitude = −0.833 standard, −6 crépuscule civil. */
fun sunTimes(date: LocalDate, lat: Double, lon: Double, alt: Double = -0.833): Triple<Double, Double, Double> {
    val n = date.toEpochDay() + 2440588 - 2451545.0 + 0.0008
    val js = n - lon / 360
    val m = (357.5291 + 0.98560028 * js) % 360
    val mr = Math.toRadians(m)
    val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
    val l = Math.toRadians((m + c + 180 + 102.9372) % 360)
    val jt = 2451545.0 + js + 0.0053 * sin(mr) - 0.0069 * sin(2 * l)
    val dec = asin(sin(l) * sin(Math.toRadians(23.4397)))
    val cosW = (sin(Math.toRadians(alt)) - sin(Math.toRadians(lat)) * sin(dec)) / (cos(Math.toRadians(lat)) * cos(dec))
    if (cosW < -1 || cosW > 1) return Triple(Double.NaN, jt, Double.NaN)
    val w = Math.toDegrees(acos(cosW))
    return Triple(jt - w / 360, jt, jt + w / 360)
}

@Composable
fun SunMoonScreen() {
    val ctx = LocalContext.current
    val (granted, ask) = rememberLocationPermission()
    var lat by remember { mutableDoubleStateOf(48.8566) }; var lon by remember { mutableDoubleStateOf(2.3522) }; var place by remember { mutableStateOf("Paris (par défaut)") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(granted) { if (granted) lastLocation(ctx)?.let { lat = it.latitude; lon = it.longitude; place = "📍 Ma position" } }
    val zone = ZoneId.systemDefault(); val hm = DateTimeFormatter.ofPattern("HH:mm")
    ToolColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(place, Modifier.weight(1f)); if (!granted) TextButton(onClick = ask) { Text("Ma position") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { date = date.minusDays(1) }) { Text("◀") }
            Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { date = date.plusDays(1) }) { Text("▶") }
        }
        val (rise, noon, set) = sunTimes(date, lat, lon)
        val (dawn, _, dusk) = sunTimes(date, lat, lon, -6.0)
        val (gStart, _, gEnd) = sunTimes(date, lat, lon, 6.0)
        ResultCard {
            Text("☀️ Soleil", style = MaterialTheme.typography.titleLarge)
            Text("Aube civile : ${jdToTime(dawn, zone)?.format(hm) ?: "—"}")
            Text("Lever : ${jdToTime(rise, zone)?.format(hm) ?: "—"}", style = MaterialTheme.typography.titleMedium)
            Text("Midi solaire : ${jdToTime(noon, zone)?.format(hm)}")
            Text("Coucher : ${jdToTime(set, zone)?.format(hm) ?: "—"}", style = MaterialTheme.typography.titleMedium)
            Text("Crépuscule civil : ${jdToTime(dusk, zone)?.format(hm) ?: "—"}")
            if (!rise.isNaN()) { val len = (set - rise) * 24; Text("Durée du jour : ${len.toInt()} h ${((len % 1) * 60).toInt()} min") }
            Text("📸 Heure dorée : ${jdToTime(rise, zone)?.format(hm) ?: "—"}–${jdToTime(gStart, zone)?.format(hm) ?: "—"} et ${jdToTime(gEnd, zone)?.format(hm) ?: "—"}–${jdToTime(set, zone)?.format(hm) ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
        val jd = date.toEpochDay() + 2440588.0
        val age = ((jd - 2451550.26) % 29.530588853 + 29.530588853) % 29.530588853
        val illum = (1 - cos(2 * PI * age / 29.530588853)) / 2
        val (emoji, name) = when {
            age < 1.84 -> "🌑" to "Nouvelle lune"; age < 5.53 -> "🌒" to "Premier croissant"; age < 9.23 -> "🌓" to "Premier quartier"
            age < 12.91 -> "🌔" to "Gibbeuse croissante"; age < 16.61 -> "🌕" to "Pleine lune"; age < 20.30 -> "🌖" to "Gibbeuse décroissante"
            age < 23.99 -> "🌗" to "Dernier quartier"; age < 27.68 -> "🌘" to "Dernier croissant"; else -> "🌑" to "Nouvelle lune"
        }
        ResultCard {
            Text("🌙 Lune", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 64.sp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text("Éclairée à ${(illum * 100).roundToInt()} % • âge ${age.fmt(1)} j")
                    val toFull = ((14.765 - age) % 29.53 + 29.53) % 29.53; val toNew = 29.53 - age
                    Text("Pleine lune dans ${toFull.roundToInt()} j • nouvelle lune dans ${toNew.roundToInt()} j", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------------- Distance ----------------

@Composable
fun DistanceScreen() {
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf("Paris") }; var b by remember { mutableStateOf("Marseille") }
    var res by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<List<String>>(emptyList()) }
    suspend fun geo(q: String): JSONObject? = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" + URLEncoder.encode(q.trim(), "UTF-8"))).optJSONArray("results")?.optJSONObject(0)
    ToolColumn {
        OutlinedTextField(a, { a = it }, Modifier.fillMaxWidth(), label = { Text("Ville de départ") }, singleLine = true)
        OutlinedTextField(b, { b = it }, Modifier.fillMaxWidth(), label = { Text("Ville d'arrivée") }, singleLine = true)
        Button(onClick = {
            scope.launch {
                try {
                    val x = geo(a); val y = geo(b)
                    if (x == null || y == null) { res = "Ville introuvable"; return@launch }
                    val d = haversine(x.getDouble("latitude"), x.getDouble("longitude"), y.getDouble("latitude"), y.getDouble("longitude"))
                    val br = bearing(x.getDouble("latitude"), x.getDouble("longitude"), y.getDouble("latitude"), y.getDouble("longitude"))
                    val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
                    res = "${d.fmt(1)} km"
                    info = listOf("${x.getString("name")} (${x.optString("country")}) → ${y.getString("name")} (${y.optString("country")})",
                        "${(d / 1.609344).fmt(1)} miles • ${(d / 1.852).fmt(1)} milles nautiques", "Direction : ${br.roundToInt()}° (${dirs[((br + 22.5) / 45).toInt() % 8]})",
                        "✈️ ≈ ${fmtH(d / 800 + 0.5)} en avion • 🚗 ≈ ${fmtH(d * 1.25 / 90)} en voiture • 🚴 ≈ ${fmtH(d * 1.25 / 18)} à vélo",
                        "Différence d'altitude : ${(y.optDouble("elevation", 0.0) - x.optDouble("elevation", 0.0)).roundToInt()} m")
                } catch (e: Exception) { res = "Pas de connexion" }
            }
        }, Modifier.fillMaxWidth()) { Text("📏 Calculer") }
        res?.let { Text(it, fontSize = 44.sp, color = MaterialTheme.colorScheme.primary) }
        info.forEach { Text(it) }
        Text("Distance à vol d'oiseau. Les durées sont des estimations grossières.", style = MaterialTheme.typography.bodySmall)
    }
}
private fun fmtH(h: Double) = if (h < 1) "${(h * 60).roundToInt()} min" else "${h.toInt()} h ${"%02d".format(((h % 1) * 60).roundToInt())}"

// ---------------- Infos pays ----------------

private data class Country(val flag: String, val name: String, val code: String, val plug: String, val volt: String, val drive: String, val money: String, val sos: String, val tip: String)
private val countries = listOf(
    Country("🇫🇷", "France", "+33", "C / E", "230 V", "droite", "Euro (EUR)", "112 / 15 / 17 / 18", "Service compris, pourboire facultatif"),
    Country("🇧🇪", "Belgique", "+32", "C / E", "230 V", "droite", "Euro", "112", "Facultatif"), Country("🇨🇭", "Suisse", "+41", "C / J", "230 V", "droite", "Franc suisse (CHF)", "112 / 117 / 144", "Arrondir"),
    Country("🇪🇸", "Espagne", "+34", "C / F", "230 V", "droite", "Euro", "112", "5–10 %"), Country("🇮🇹", "Italie", "+39", "C / F / L", "230 V", "droite", "Euro", "112", "Coperto souvent inclus"),
    Country("🇵🇹", "Portugal", "+351", "C / F", "230 V", "droite", "Euro", "112", "5–10 %"), Country("🇩🇪", "Allemagne", "+49", "C / F", "230 V", "droite", "Euro", "112 / 110", "5–10 %"),
    Country("🇬🇧", "Royaume-Uni", "+44", "G", "230 V", "GAUCHE", "Livre (GBP)", "999 / 112", "10–12,5 %"), Country("🇮🇪", "Irlande", "+353", "G", "230 V", "GAUCHE", "Euro", "112 / 999", "10 %"),
    Country("🇳🇱", "Pays-Bas", "+31", "C / F", "230 V", "droite", "Euro", "112", "Arrondir"), Country("🇬🇷", "Grèce", "+30", "C / F", "230 V", "droite", "Euro", "112", "5–10 %"),
    Country("🇭🇷", "Croatie", "+385", "C / F", "230 V", "droite", "Euro", "112", "10 %"), Country("🇹🇷", "Turquie", "+90", "C / F", "230 V", "droite", "Livre turque (TRY)", "112", "5–10 %"),
    Country("🇲🇦", "Maroc", "+212", "C / E", "220 V", "droite", "Dirham (MAD)", "19 / 15", "10 %"), Country("🇹🇳", "Tunisie", "+216", "C / E", "230 V", "droite", "Dinar (TND)", "197 / 190", "10 %"),
    Country("🇩🇿", "Algérie", "+213", "C / F", "230 V", "droite", "Dinar (DZD)", "17 / 14", "Facultatif"), Country("🇸🇳", "Sénégal", "+221", "C / D / E / K", "230 V", "droite", "Franc CFA (XOF)", "17 / 18", "Facultatif"),
    Country("🇺🇸", "États-Unis", "+1", "A / B", "120 V", "droite", "Dollar (USD)", "911", "15–20 % obligatoire"), Country("🇨🇦", "Canada", "+1", "A / B", "120 V", "droite", "Dollar canadien (CAD)", "911", "15–18 %"),
    Country("🇲🇽", "Mexique", "+52", "A / B", "127 V", "droite", "Peso (MXN)", "911", "10–15 %"), Country("🇧🇷", "Brésil", "+55", "C / N", "127/220 V", "droite", "Real (BRL)", "190 / 192", "10 % inclus"),
    Country("🇦🇷", "Argentine", "+54", "C / I", "220 V", "droite", "Peso (ARS)", "911", "10 %"), Country("🇯🇵", "Japon", "+81", "A / B", "100 V", "GAUCHE", "Yen (JPY)", "110 / 119", "Pas de pourboire !"),
    Country("🇨🇳", "Chine", "+86", "A / C / I", "220 V", "droite", "Yuan (CNY)", "110 / 120", "Pas d'usage"), Country("🇰🇷", "Corée du Sud", "+82", "C / F", "220 V", "droite", "Won (KRW)", "112 / 119", "Pas d'usage"),
    Country("🇹🇭", "Thaïlande", "+66", "A / B / C / O", "220 V", "GAUCHE", "Baht (THB)", "191 / 1155", "Arrondir"), Country("🇻🇳", "Vietnam", "+84", "A / C", "220 V", "droite", "Dong (VND)", "113 / 115", "Facultatif"),
    Country("🇮🇳", "Inde", "+91", "C / D / M", "230 V", "GAUCHE", "Roupie (INR)", "112", "10 %"), Country("🇮🇩", "Indonésie", "+62", "C / F", "230 V", "GAUCHE", "Roupie (IDR)", "112", "5–10 %"),
    Country("🇦🇪", "Émirats", "+971", "G", "230 V", "droite", "Dirham (AED)", "999 / 998", "10–15 %"), Country("🇦🇺", "Australie", "+61", "I", "230 V", "GAUCHE", "Dollar australien (AUD)", "000", "Facultatif"),
    Country("🇳🇿", "Nouvelle-Zélande", "+64", "I", "230 V", "GAUCHE", "Dollar NZ (NZD)", "111", "Facultatif"), Country("🇿🇦", "Afrique du Sud", "+27", "C / M / N", "230 V", "GAUCHE", "Rand (ZAR)", "10111 / 112", "10–15 %"),
    Country("🇪🇬", "Égypte", "+20", "C / F", "220 V", "droite", "Livre égyptienne (EGP)", "122 / 123", "10 % (bakchich)"), Country("🇷🇪", "La Réunion", "+262", "C / E", "230 V", "droite", "Euro", "112 / 15", "Facultatif"),
    Country("🇬🇵", "Antilles fr.", "+590/596", "C / E", "230 V", "droite", "Euro", "112 / 15", "Facultatif"), Country("🇵🇫", "Polynésie fr.", "+689", "A / B / E", "220 V", "droite", "Franc Pacifique (XPF)", "15 / 17", "Pas d'usage"),
)

@Composable
fun CountriesScreen() {
    var q by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("🔎 Pays") }, singleLine = true)
            Text("Prises françaises : type C/E. Si le pays utilise A/B/G/I, prends un adaptateur !", style = MaterialTheme.typography.bodySmall) }
        items(countries.filter { it.name.contains(q, true) }) { c ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${c.flag} ${c.name}", style = MaterialTheme.typography.titleMedium)
                    Text("📞 ${c.code}   🔌 ${c.plug} (${c.volt})   🚗 ${c.drive}")
                    Text("💰 ${c.money}   🚨 ${c.sos}")
                    Text("💁 Pourboire : ${c.tip}", color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// ---------------- Prix des carburants ----------------

@Composable
fun FuelPriceScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (granted, ask) = rememberLocationPermission()
    val fuels = listOf("gazole" to "Gazole", "e10" to "SP95-E10", "sp95" to "SP95", "sp98" to "SP98", "e85" to "E85", "gplc" to "GPL")
    var fuel by remember { mutableStateOf(prefs(ctx).getString("fuel_type", "gazole")!!) }
    var radius by remember { mutableIntStateOf(10) }
    var cp by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    fun load(where: String) {
        busy = true; err = null
        scope.launch {
            try {
                val url = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records?limit=40" +
                    "&order_by=${fuel}_prix&where=" + URLEncoder.encode("$where and ${fuel}_prix is not null", "UTF-8")
                val a = JSONObject(httpGet(url, 15000)).getJSONArray("results")
                list = (0 until a.length()).map { a.getJSONObject(it) }
                if (list.isEmpty()) err = "Aucune station trouvée"
            } catch (e: Exception) { err = "Impossible de charger les prix" } finally { busy = false }
        }
    }
    fun nearMe() {
        val l = lastLocation(ctx) ?: run { err = "Position inconnue, active la localisation"; return }
        here = l.latitude to l.longitude
        load("within_distance(geom, geom'POINT(${l.longitude} ${l.latitude})', ${radius}km)")
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { fuels.forEach { (k, l) -> FilterChip(fuel == k, { fuel = k; prefs(ctx).edit().putString("fuel_type", k).apply() }, label = { Text(l, fontSize = 11.sp) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Rayon :"); listOf(5, 10, 20, 30).forEach { r -> FilterChip(radius == r, { radius = r }, label = { Text("$r km") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (granted) nearMe() else ask() }) { Text("📍 Autour de moi") }
                OutlinedTextField(cp, { cp = it }, Modifier.weight(1f), label = { Text("Code postal") }, singleLine = true)
                TextButton(onClick = { here = null; load("cp='${cp.trim()}'") }) { Text("OK") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(list) { s ->
            val price = s.optDouble("${fuel}_prix")
            val g = s.optJSONObject("geom")
            val d = if (g != null && here != null) haversine(here!!.first, here!!.second, g.optDouble("lat"), g.optDouble("lon")) else null
            ElevatedCard(Modifier.fillMaxWidth().clickable {
                g?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${it.optDouble("lat")},${it.optDouble("lon")}(${Uri.encode(s.optString("adresse"))})"))) }
            }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.optString("adresse").lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                        Text("${s.optString("cp")} ${s.optString("ville")}" + (d?.let { " • ${it.fmt(1)} km" } ?: ""), color = MaterialTheme.colorScheme.outline)
                        s.optString("${fuel}_maj").takeIf { it.length >= 10 }?.let { Text("MAJ ${isoToFr(it.take(10))}", style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("${price.fmt(3)} €", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (s === list.first()) Color(0xFF43A047) else MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Text("Données officielles prix-carburants.gouv.fr. Touche une station pour l'itinéraire.", style = MaterialTheme.typography.bodySmall) }
    }
}

// ---------------- Où je suis garé ----------------

@Composable
fun ParkingScreen() {
    val ctx = LocalContext.current
    val p = prefs(ctx)
    val (granted, ask) = rememberLocationPermission()
    val loc = rememberLiveLocation(granted)
    var saved by remember { mutableStateOf(p.getString("parking", null)?.let { JSONObject(it) }) }
    var note by remember { mutableStateOf(saved?.optString("note") ?: "") }
    var photo by remember { mutableStateOf<String?>(saved?.optString("photo")?.takeIf { it.isNotBlank() }) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val shot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) { photo = pendingUri.toString(); saved = saved?.with("photo", photo); saved?.let { p.edit().putString("parking", it.toString()).apply() } } }
    var meterMin by remember { mutableIntStateOf(0) }
    ToolColumn {
        if (!granted) { Button(onClick = ask) { Text("Autoriser la localisation") }; return@ToolColumn }
        val s = saved
        if (s == null) {
            Text("🅿️", fontSize = 72.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (niveau -2, place B14…)") }, singleLine = true)
            Text("Rappel horodateur :")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0, 30, 60, 90, 120).forEach { m -> FilterChip(meterMin == m, { meterMin = m }, label = { Text(if (m == 0) "Aucun" else if (m < 60) "$m min" else "${m / 60}h${if (m % 60 > 0) "30" else ""}") }) } }
            Button(onClick = {
                val l = loc ?: lastLocation(ctx) ?: return@Button
                val o = JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("note", note).put("time", System.currentTimeMillis()).put("acc", l.accuracy.toDouble())
                if (meterMin > 0) {
                    val at = System.currentTimeMillis() + (meterMin - 10) * 60_000L
                    o.put("meter", System.currentTimeMillis() + meterMin * 60_000L)
                    val r = JSONObject().put("id", 424242L).put("text", "🅿️ Ton stationnement expire dans 10 minutes !").put("at", at).put("daily", false)
                    saveList(ctx, "reminders", loadList(ctx, "reminders").filter { it.getLong("id") != 424242L } + r); scheduleReminder(ctx, r)
                }
                saved = o; p.edit().putString("parking", o.toString()).apply()
            }, Modifier.fillMaxWidth().height(60.dp), enabled = loc != null || lastLocation(ctx) != null) { Text("📍 Enregistrer ma place ici", fontSize = 18.sp) }
            if (loc == null) Text("Recherche de la position…", color = MaterialTheme.colorScheme.outline)
        } else {
            val la = s.getDouble("lat"); val lo = s.getDouble("lon")
            val ago = (System.currentTimeMillis() - s.getLong("time")) / 60000
            ResultCard {
                Text("🚗 Garé il y a ${if (ago >= 60) "${ago / 60} h ${ago % 60} min" else "$ago min"}", style = MaterialTheme.typography.titleLarge)
                if (s.optString("note").isNotBlank()) Text("📝 ${s.optString("note")}")
                loc?.let { l ->
                    val d = haversine(l.latitude, l.longitude, la, lo) * 1000
                    Text("Distance : ${if (d > 1000) "${(d / 1000).fmt(2)} km" else "${d.roundToInt()} m"} • direction ${bearing(l.latitude, l.longitude, la, lo).roundToInt()}°", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (s.has("meter")) { val left = (s.getLong("meter") - System.currentTimeMillis()) / 60000; Text(if (left > 0) "⏱️ Horodateur : reste $left min" else "⚠️ Horodateur expiré !", color = if (left > 10) Color.Unspecified else Color(0xFFE53935)) }
            }
            photo?.let { Thumb(Uri.parse(it), Modifier.fillMaxWidth().height(200.dp)) }
            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$la,$lo&mode=w")).let { i -> if (i.resolveActivity(ctx.packageManager) != null) i else Intent(Intent.ACTION_VIEW, Uri.parse("geo:$la,$lo?q=$la,$lo(Ma voiture)")) }) },
                Modifier.fillMaxWidth()) { Text("🧭 Me guider à pied jusqu'à la voiture") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { val u = tempImageUri(ctx); pendingUri = u; shot.launch(u) }) { Text("📷 Photo du repère") }
                OutlinedButton(onClick = { shareText(ctx, "🚗 Ma voiture est garée ici : https://maps.google.com/?q=$la,$lo") }) { Text("Partager") }
            }
            TextButton(onClick = { saved = null; photo = null; note = ""; p.edit().remove("parking").apply() }) { Text("✅ J'ai récupéré la voiture", color = MaterialTheme.colorScheme.error) }
        }
    }
}
