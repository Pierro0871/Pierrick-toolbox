package com.pierrick.toolbox

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

suspend fun httpGet(url: String, timeout: Int = 10000): String = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeout; c.readTimeout = timeout
    c.setRequestProperty("User-Agent", "Toolbox-Android")
    try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
}

// ---------------- Météo (Open-Meteo, sans clé) ----------------

private fun wmo(code: Int): String = when (code) {
    0 -> "☀️ Ensoleillé"; 1 -> "🌤️ Peu nuageux"; 2 -> "⛅ Partiellement nuageux"; 3 -> "☁️ Couvert"
    45, 48 -> "🌫️ Brouillard"; in 51..57 -> "🌦️ Bruine"; in 61..67 -> "🌧️ Pluie"; in 71..77 -> "🌨️ Neige"
    in 80..82 -> "🌧️ Averses"; 85, 86 -> "🌨️ Averses de neige"; in 95..99 -> "⛈️ Orage"; else -> "❔"
}

@SuppressLint("MissingPermission")
@Composable
fun WeatherScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = prefs(ctx)
    var city by remember { mutableStateOf(p.getString("w_city", "") ?: "") }
    var place by remember { mutableStateOf(p.getString("w_place", null)) }
    var lat by remember { mutableStateOf(p.getFloat("w_lat", Float.NaN).toDouble()) }
    var lon by remember { mutableStateOf(p.getFloat("w_lon", Float.NaN).toDouble()) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun fetch() {
        if (lat.isNaN()) return
        loading = true; error = null
        scope.launch {
            try {
                data = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset&timezone=auto"))
            } catch (e: Exception) { error = "Impossible de charger la météo (connexion ?)" } finally { loading = false }
        }
    }
    fun setPlace(name: String, la: Double, lo: Double) {
        place = name; lat = la; lon = lo
        p.edit().putString("w_place", name).putFloat("w_lat", la.toFloat()).putFloat("w_lon", lo.toFloat()).apply(); fetch()
    }
    val locReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (loc != null) setPlace("📍 Ma position", loc.latitude, loc.longitude) else error = "Position inconnue : active la localisation ou cherche une ville."
        }
    }
    LaunchedEffect(Unit) { fetch() }

    ToolColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(city, { city = it }, Modifier.weight(1f), label = { Text("Ville") }, singleLine = true)
            Button(onClick = {
                scope.launch {
                    try {
                        val r = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" + URLEncoder.encode(city.trim(), "UTF-8")))
                        val o = r.optJSONArray("results")?.optJSONObject(0)
                        if (o == null) error = "Ville introuvable" else {
                            p.edit().putString("w_city", city).apply()
                            setPlace("${o.getString("name")}, ${o.optString("country")}", o.getDouble("latitude"), o.getDouble("longitude"))
                        }
                    } catch (e: Exception) { error = "Pas de connexion" }
                }
            }) { Text("OK") }
        }
        OutlinedButton(onClick = { locReq.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("📍 Utiliser ma position") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        data?.let { d ->
            val c = d.getJSONObject("current")
            Text(place ?: "", style = MaterialTheme.typography.titleMedium)
            Text("${c.getDouble("temperature_2m").toInt()}°", fontSize = 72.sp)
            Text(wmo(c.getInt("weather_code")), style = MaterialTheme.typography.titleLarge)
            Text("Ressenti ${c.getDouble("apparent_temperature").toInt()}° • 💧 ${c.getInt("relative_humidity_2m")} % • 💨 ${c.getDouble("wind_speed_10m").toInt()} km/h")
            val dl = d.getJSONObject("daily")
            Text("☀️ Lever ${dl.getJSONArray("sunrise").getString(0).takeLast(5)} • 🌙 Coucher ${dl.getJSONArray("sunset").getString(0).takeLast(5)}", color = MaterialTheme.colorScheme.outline)
            HorizontalDivider()
            val times = dl.getJSONArray("time")
            for (i in 0 until times.length()) {
                val day = LocalDate.parse(times.getString(i))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == 0) "Aujourd'hui" else day.format(DateTimeFormatter.ofPattern("EEE d", Locale.FRANCE)), Modifier.width(100.dp))
                    Text(wmo(dl.getJSONArray("weather_code").getInt(i)).take(2), Modifier.width(36.dp), fontSize = 20.sp)
                    Text("💧${dl.getJSONArray("precipitation_probability_max").optInt(i)}%", Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                    Text("${dl.getJSONArray("temperature_2m_min").getDouble(i).toInt()}° / ${dl.getJSONArray("temperature_2m_max").getDouble(i).toInt()}°")
                }
            }
            Text("Données : Open-Meteo", style = MaterialTheme.typography.bodySmall)
        } ?: if (lat.isNaN()) Text("Cherche une ville ou utilise ta position.") else Unit
    }
}

// ---------------- Test de débit ----------------

@Composable
fun SpeedTestScreen() {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("") }
    var ping by remember { mutableStateOf<Long?>(null) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var live by remember { mutableDoubleStateOf(0.0) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run() {
        running = true; error = null; ping = null; down = null; up = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    phase = "Ping…"
                    val pings = (1..5).map {
                        val t = System.nanoTime()
                        (URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection).apply { connectTimeout = 5000; inputStream.readBytes(); disconnect() }
                        (System.nanoTime() - t) / 1_000_000
                    }
                    ping = pings.sorted()[1]

                    phase = "Téléchargement…"
                    val c = URL("https://speed.cloudflare.com/__down?bytes=50000000").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 15000
                    val buf = ByteArray(64 * 1024); var total = 0L; val start = System.nanoTime()
                    c.inputStream.use { s ->
                        while (true) {
                            val n = s.read(buf); if (n < 0) break; total += n
                            val sec = (System.nanoTime() - start) / 1e9
                            live = total * 8 / sec / 1e6
                            if (sec > 10) break
                        }
                    }
                    c.disconnect(); down = live

                    phase = "Envoi…"
                    val payload = ByteArray(10_000_000)
                    val u = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
                    u.doOutput = true; u.requestMethod = "POST"; u.setFixedLengthStreamingMode(payload.size)
                    u.connectTimeout = 8000; u.readTimeout = 20000
                    val us = System.nanoTime(); var sent = 0
                    u.outputStream.use { o ->
                        while (sent < payload.size) {
                            val n = minOf(64 * 1024, payload.size - sent); o.write(payload, sent, n); sent += n
                            live = sent * 8 / ((System.nanoTime() - us) / 1e9) / 1e6
                        }
                    }
                    u.responseCode; u.disconnect()
                    up = sent * 8 / ((System.nanoTime() - us) / 1e9) / 1e6
                }
            } catch (e: Exception) { error = "Échec du test : ${e.message ?: "connexion"}" }
            phase = ""; running = false
        }
    }
    ToolColumn {
        Text(if (running) "%.1f".format(live) else (down?.let { "%.1f".format(it) } ?: "—"), fontSize = 72.sp)
        Text("Mb/s ${if (phase.isNotEmpty()) "• $phase" else ""}", color = MaterialTheme.colorScheme.outline)
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        ResultCard {
            Text("📶 Ping : ${ping?.let { "$it ms" } ?: "—"}")
            Text("⬇️ Descendant : ${down?.let { "%.1f Mb/s".format(it) } ?: "—"}")
            Text("⬆️ Montant : ${up?.let { "%.1f Mb/s".format(it) } ?: "—"}")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { run() }, enabled = !running) { Text("🚀 Lancer le test") }
        Text("Utilise jusqu'à ~60 Mo de données (attention en 4G/5G). Serveur : Cloudflare.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Fuseaux horaires ----------------

private val cities = linkedMapOf(
    "Paris" to "Europe/Paris", "Londres" to "Europe/London", "Lisbonne" to "Europe/Lisbon", "Madrid" to "Europe/Madrid",
    "Berlin" to "Europe/Berlin", "Rome" to "Europe/Rome", "Athènes" to "Europe/Athens", "Moscou" to "Europe/Moscow",
    "Istanbul" to "Europe/Istanbul", "Dubaï" to "Asia/Dubai", "Mumbai" to "Asia/Kolkata", "Bangkok" to "Asia/Bangkok",
    "Singapour" to "Asia/Singapore", "Hong Kong" to "Asia/Hong_Kong", "Pékin" to "Asia/Shanghai", "Tokyo" to "Asia/Tokyo",
    "Séoul" to "Asia/Seoul", "Sydney" to "Australia/Sydney", "Auckland" to "Pacific/Auckland", "Nouméa" to "Pacific/Noumea",
    "Tahiti" to "Pacific/Tahiti", "Honolulu" to "Pacific/Honolulu", "Los Angeles" to "America/Los_Angeles",
    "Denver" to "America/Denver", "Chicago" to "America/Chicago", "New York" to "America/New_York", "Montréal" to "America/Toronto",
    "Mexico" to "America/Mexico_City", "Guadeloupe / Martinique" to "America/Martinique", "Cayenne" to "America/Cayenne",
    "São Paulo" to "America/Sao_Paulo", "Buenos Aires" to "America/Argentina/Buenos_Aires", "La Réunion" to "Indian/Reunion",
    "Casablanca" to "Africa/Casablanca", "Alger" to "Africa/Algiers", "Dakar" to "Africa/Dakar", "Le Caire" to "Africa/Cairo",
    "Johannesburg" to "Africa/Johannesburg", "Nairobi" to "Africa/Nairobi",
)

@Composable
fun WorldClockScreen() {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf(prefs(ctx).getString("clocks", "New York,Tokyo,Londres")!!.split(",").filter { it in cities }) }
    fun save(l: List<String>) { chosen = l; prefs(ctx).edit().putString("clocks", l.joinToString(",")).apply() }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(1000) } }
    var adding by remember { mutableStateOf(cities.keys.first()) }
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Ici : ${now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", fontSize = 40.sp)
            Text(now.zone.id, color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("Ajouter une ville", cities.keys.toList(), adding, { it }, Modifier.weight(1f)) { adding = it }
                Button(onClick = { if (adding !in chosen) save(chosen + adding) }) { Text("+") }
            }
        }
        items(chosen) { c ->
            val t = now.withZoneSameInstant(ZoneId.of(cities[c]!!))
            val diffH = (t.offset.totalSeconds - now.offset.totalSeconds) / 3600.0
            val dayShift = t.toLocalDate().compareTo(now.toLocalDate())
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c, style = MaterialTheme.typography.titleMedium)
                        Text((if (diffH >= 0) "+" else "") + diffH.fmt(1) + " h" + when { dayShift > 0 -> " • demain"; dayShift < 0 -> " • hier"; else -> "" },
                            color = MaterialTheme.colorScheme.outline)
                    }
                    Text((if (t.hour in 7..19) "☀️ " else "🌙 ") + t.format(hm), fontSize = 30.sp)
                    TextButton(onClick = { save(chosen - c) }) { Text("✕") }
                }
            }
        }
    }
}

// ---------------- IP / Wi-Fi ----------------

@Suppress("DEPRECATION")
@Composable
fun NetInfoScreen() {
    val ctx = LocalContext.current
    var pub by remember { mutableStateOf<JSONObject?>(null) }
    var pubErr by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var locOk by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locOk = it; refresh++ }
    LaunchedEffect(refresh) {
        pubErr = null
        try { pub = JSONObject(httpGet("https://ipwho.is/")) } catch (e: Exception) { pubErr = "Hors ligne" }
    }
    val local = remember(refresh) {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress }
            }
        }.getOrDefault(emptyList())
    }
    val wifi = remember(refresh) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.takeIf { it.networkId != -1 || it.rssi > -127 }
    }
    ToolColumn {
        ResultCard {
            Text("🌐 IP publique", style = MaterialTheme.typography.titleMedium)
            pub?.let { o ->
                Text(o.optString("ip"), fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                Text("${o.optString("city")}, ${o.optString("country")}")
                o.optJSONObject("connection")?.let { Text("Opérateur : ${it.optString("isp")}") }
                Row { OutlinedButton(onClick = { copy(ctx, o.optString("ip")) }) { Text("Copier") } }
            } ?: Text(pubErr ?: "Chargement…")
        }
        ResultCard {
            Text("🏠 IP locale", style = MaterialTheme.typography.titleMedium)
            if (local.isEmpty()) Text("Aucune")
            local.forEach { (n, ip) -> Text("$ip  ($n)") }
        }
        ResultCard {
            Text("📶 Wi-Fi", style = MaterialTheme.typography.titleMedium)
            if (wifi == null || wifi.rssi <= -127) Text("Non connecté au Wi-Fi") else {
                val ssid = wifi.ssid.trim('"')
                Text("Réseau : " + if (ssid == "<unknown ssid>") "masqué (autorise la localisation)" else ssid)
                val bars = WifiManager.calculateSignalLevel(wifi.rssi, 5)
                Text("Signal : ${"▮".repeat(bars)}${"▯".repeat(4 - bars.coerceAtMost(4))}  ${wifi.rssi} dBm")
                Text("Vitesse de liaison : ${wifi.linkSpeed} Mb/s")
                Text("Bande : ${if (wifi.frequency > 5900) "6 GHz" else if (wifi.frequency > 4900) "5 GHz" else "2,4 GHz"} (${wifi.frequency} MHz)")
            }
            if (!locOk) TextButton(onClick = { req.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Afficher le nom du réseau (autorisation localisation)") }
        }
        Button(onClick = { refresh++ }) { Text("Actualiser") }
    }
}
