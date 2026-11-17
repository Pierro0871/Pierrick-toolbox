package com.pierrick.toolbox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Bloqueur de sites : VPN local (rien ne sort du téléphone) qui intercepte uniquement
 * les requêtes DNS. Les domaines bloqués reçoivent « domaine inexistant », le reste
 * est transmis au DNS 1.1.1.1.
 */
object BlockList {
    val presets = linkedMapOf(
        "📱 Réseaux sociaux" to listOf("facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com", "tiktok.com", "tiktokcdn.com",
            "tiktokv.com", "twitter.com", "x.com", "twimg.com", "snapchat.com", "reddit.com", "redd.it", "linkedin.com", "pinterest.com", "threads.net"),
        "🎬 Vidéo & streaming" to listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "netflix.com", "nflxvideo.net",
            "twitch.tv", "primevideo.com", "disneyplus.com", "dailymotion.com"),
        "🎮 Jeux" to listOf("roblox.com", "epicgames.com", "steampowered.com", "steamcommunity.com", "supercell.com", "king.com", "miniclip.com"),
        "🛍️ Shopping" to listOf("amazon.fr", "amazon.com", "aliexpress.com", "temu.com", "shein.com", "vinted.fr", "leboncoin.fr", "cdiscount.com"),
        "📰 Actu" to listOf("lemonde.fr", "lefigaro.fr", "bfmtv.com", "20minutes.fr", "francetvinfo.fr", "ouest-france.fr"),
        "🎰 Paris & jeux d'argent" to listOf("betclic.fr", "winamax.fr", "unibet.fr", "pmu.fr", "parionssport.fdj.fr", "zebet.fr", "pokerstars.fr"),
        "🚫 Pubs & traqueurs" to listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "app-measurement.com", "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "scorecardresearch.com", "amazon-adsystem.com", "ads.yahoo.com", "adcolony.com", "applovin.com", "unityads.unity3d.com"),
    )

    fun custom(ctx: Context) = prefs(ctx).getStringSet("bl_custom", emptySet())!!.toSet()
    fun enabledPresets(ctx: Context) = prefs(ctx).getStringSet("bl_presets", emptySet())!!.toSet()
    fun all(ctx: Context): Set<String> = custom(ctx) + enabledPresets(ctx).flatMap { presets[it].orEmpty() }

    fun normalize(s: String) = s.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').substringBefore(':')

    fun focusUntil(ctx: Context) = prefs(ctx).getLong("bl_focus_until", 0L)
    fun focusActive(ctx: Context) = focusUntil(ctx) > System.currentTimeMillis()
    fun schedules(ctx: Context): List<org.json.JSONObject> = loadList(ctx, "bl_sched")
    fun scheduleOn(ctx: Context) = prefs(ctx).getBoolean("bl_sched_on", false)

    /** Le blocage s'applique-t-il en ce moment ? (mode focus > planning > toujours) */
    fun activeNow(ctx: Context): Boolean {
        if (focusActive(ctx)) return true
        if (!scheduleOn(ctx)) return true
        val now = java.time.LocalDateTime.now(); val t = now.toLocalTime().toString().take(5); val dow = now.dayOfWeek.value
        return schedules(ctx).any { s ->
            val days = s.optString("days"); val a = s.optString("start"); val b = s.optString("end")
            dow.toString() in days && (if (a <= b) t >= a && t < b else t >= a || t < b)
        }
    }

    fun isBlocked(domain: String, set: Set<String>): Boolean {
        var d = domain.lowercase().trimEnd('.')
        while (true) {
            if (d in set) return true
            val i = d.indexOf('.'); if (i < 0) return false
            d = d.substring(i + 1)
        }
    }
}

class BlockerVpnService : VpnService() {
    companion object {
        @Volatile var running = false
        @Volatile var blockedCount = 0
        @Volatile var lastBlocked = ArrayDeque<String>()
        const val STOP = "stop"
    }
    private var tun: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { if (!BlockList.focusActive(this)) { stopVpn(); stopSelf() }; return START_NOT_STICKY }
        if (running) return START_STICKY
        tun = Builder().setSession("Toolbox – bloqueur")
            .addAddress("10.111.222.1", 32)
            .addDnsServer("10.111.222.2")
            .addRoute("10.111.222.2", 32)
            .setBlocking(true)
            .establish() ?: return START_NOT_STICKY
        running = true
        prefs(this).edit().putBoolean("bl_on", true).apply()
        thread = Thread { loop() }.also { it.start() }
        return START_STICKY
    }

    private fun loop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        var blocked = if (BlockList.activeNow(this)) BlockList.all(this) else emptySet(); var lastLoad = System.currentTimeMillis()
        try {
            while (running) {
                val n = input.read(buf); if (n <= 0) continue
                if (System.currentTimeMillis() - lastLoad > 3000) { blocked = if (BlockList.activeNow(this)) BlockList.all(this) else emptySet(); lastLoad = System.currentTimeMillis() }
                val pkt = buf.copyOf(n)
                if ((pkt[0].toInt() shr 4) != 4 || pkt[9].toInt() != 17) continue // IPv4 + UDP seulement
                val ihl = (pkt[0].toInt() and 0x0F) * 4
                val dns = pkt.copyOfRange(ihl + 8, n)
                if (dns.size < 12) continue
                val name = qname(dns)
                if (name != null && BlockList.isBlocked(name, blocked)) {
                    blockedCount++
                    synchronized(lastBlocked) { lastBlocked.addFirst(name); while (lastBlocked.size > 30) lastBlocked.removeLast() }
                    val resp = dns.copyOf()
                    resp[2] = (0x80 or (dns[2].toInt() and 0x01)).toByte() // QR=1, RD conservé
                    resp[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
                    synchronized(output) { output.write(reply(pkt, ihl, resp)) }
                } else pool.execute {
                    try {
                        DatagramSocket().use { s ->
                            protect(s); s.soTimeout = 5000
                            s.send(DatagramPacket(dns, dns.size, InetAddress.getByName("1.1.1.1"), 53))
                            val rb = ByteArray(4096); val rp = DatagramPacket(rb, rb.size); s.receive(rp)
                            val r = reply(pkt, ihl, rb.copyOf(rp.length))
                            synchronized(output) { output.write(r) }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun qname(d: ByteArray): String? = runCatching {
        var i = 12; val sb = StringBuilder()
        while (true) {
            val len = d[i].toInt() and 0xFF; if (len == 0) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, i + 1, len, Charsets.US_ASCII)); i += len + 1
        }
        sb.toString()
    }.getOrNull()

    /** Construit le paquet IPv4/UDP de réponse en inversant source et destination. */
    private fun reply(q: ByteArray, ihl: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val r = ByteArray(total)
        r[0] = 0x45; r[2] = (total shr 8).toByte(); r[3] = total.toByte()
        r[6] = 0x40; r[8] = 64; r[9] = 17
        System.arraycopy(q, 16, r, 12, 4); System.arraycopy(q, 12, r, 16, 4) // src <-> dst
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        sum = sum.inv() and 0xFFFF; r[10] = (sum shr 8).toByte(); r[11] = sum.toByte()
        r[20] = q[ihl + 2]; r[21] = q[ihl + 3]; r[22] = q[ihl]; r[23] = q[ihl + 1] // ports inversés
        val ul = 8 + payload.size; r[24] = (ul shr 8).toByte(); r[25] = ul.toByte() // checksum UDP = 0 (facultatif en IPv4)
        System.arraycopy(payload, 0, r, 28, payload.size)
        return r
    }

    private fun stopVpn() {
        running = false
        prefs(this).edit().putBoolean("bl_on", false).apply()
        runCatching { tun?.close() }; tun = null
        thread?.interrupt()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); pool.shutdownNow(); super.onDestroy() }
}

@Composable
fun BlockerScreen() {
    val ctx = LocalContext.current
    val p = prefs(ctx)
    var on by remember { mutableStateOf(BlockerVpnService.running) }
    var custom by remember { mutableStateOf(BlockList.custom(ctx)) }
    var presets by remember { mutableStateOf(BlockList.enabledPresets(ctx)) }
    var input by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(BlockerVpnService.blockedCount) }
    var recent by remember { mutableStateOf(listOf<String>()) }
    var focusLeft by remember { mutableLongStateOf(0L) }
    var activeNow by remember { mutableStateOf(true) }
    var schedOn by remember { mutableStateOf(BlockList.scheduleOn(ctx)) }
    val (sched, saveSched) = rememberStoredList("bl_sched")
    var hasPin by remember { mutableStateOf(p.getString("bl_pin", null) != null) }
    var pinPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinEntry by remember { mutableStateOf("") }
    var pinErr by remember { mutableStateOf(false) }
    var newPin by remember { mutableStateOf("") }
    var focusMin by remember { mutableIntStateOf(60) }
    LaunchedEffect(Unit) {
        while (true) {
            on = BlockerVpnService.running; count = BlockerVpnService.blockedCount
            recent = synchronized(BlockerVpnService.lastBlocked) { BlockerVpnService.lastBlocked.toList() }
            focusLeft = (BlockList.focusUntil(ctx) - System.currentTimeMillis()).coerceAtLeast(0)
            activeNow = BlockList.activeNow(ctx)
            delay(1000)
        }
    }
    val locked = focusLeft > 0
    /** Exécute une action « qui affaiblit » le blocage : interdite en mode focus, protégée par PIN sinon. */
    fun guarded(action: () -> Unit) { if (locked) return; if (hasPin) { pinEntry = ""; pinErr = false; pinPrompt = action } else action() }
    fun start() { ctx.startService(Intent(ctx, BlockerVpnService::class.java)); on = true }
    var afterVpn by remember { mutableStateOf<(() -> Unit)?>(null) }
    val vpnReq = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == Activity.RESULT_OK) { start(); afterVpn?.invoke(); afterVpn = null } }
    fun ensureVpn(then: () -> Unit = {}) { val i = VpnService.prepare(ctx); if (i != null) { afterVpn = then; vpnReq.launch(i) } else { start(); then() } }
    fun saveCustom(s: Set<String>) { custom = s; p.edit().putStringSet("bl_custom", s).apply() }
    fun savePresets(s: Set<String>) { presets = s; p.edit().putStringSet("bl_presets", s).apply() }

    pinPrompt?.let { act ->
        AlertDialog({ pinPrompt = null }, title = { Text("🔒 Code du bloqueur") },
            text = { Column {
                OutlinedTextField(pinEntry, { pinEntry = it }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                if (pinErr) Text("Code incorrect", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = { if (sha256("bl:$pinEntry") == p.getString("bl_pin", "")) { pinPrompt = null; act() } else pinErr = true }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pinPrompt = null }) { Text("Annuler") } })
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when { !on -> "Blocage désactivé"; activeNow -> "🛡️ Blocage actif"; else -> "⏸️ Hors planning" }, style = MaterialTheme.typography.titleLarge)
                        Text("${BlockList.all(ctx).size} domaines • $count requêtes bloquées", color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(on, { want -> if (want) ensureVpn() else guarded { ctx.startService(Intent(ctx, BlockerVpnService::class.java).setAction(BlockerVpnService.STOP)); on = false } }, enabled = !locked)
                }
            }
            // ---- Mode focus
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (locked) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🎯 Mode focus", style = MaterialTheme.typography.titleMedium)
                    if (locked) {
                        Text("%d:%02d:%02d".format(focusLeft / 3_600_000, focusLeft / 60_000 % 60, focusLeft / 1000 % 60), fontSize = 40.sp)
                        Text("Blocage verrouillé jusqu'à la fin : impossible de le couper ou de retirer des sites. Courage ! 💪", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Bloque la liste sans pouvoir l'arrêter avant la fin.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(25, 60, 120, 240, 480).forEach { m -> FilterChip(focusMin == m, { focusMin = m }, label = { Text(if (m < 60) "$m min" else "${m / 60} h") }) } }
                        Button(onClick = { ensureVpn { p.edit().putLong("bl_focus_until", System.currentTimeMillis() + focusMin * 60_000L).apply() } }) { Text("🚀 Lancer le focus") }
                    }
                }
            }
            // ---- Planning
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🗓️ Planning de blocage", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(schedOn, { v -> val apply = { schedOn = v; p.edit().putBoolean("bl_sched_on", v).apply() }; if (v) apply() else apply() }, enabled = !locked)
                    }
                    Text(if (schedOn) "Le blocage ne s'applique que pendant ces plages (le bloqueur doit être activé)." else "Désactivé : le blocage s'applique tout le temps.", style = MaterialTheme.typography.bodySmall)
                    val dayNames = listOf("L", "M", "M", "J", "V", "S", "D")
                    sched.forEach { sl ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row { (1..7).forEach { d ->
                                    val sel = d.toString() in sl.optString("days")
                                    Text(dayNames[d - 1], Modifier.padding(2.dp).clickable(enabled = !locked) {
                                        val nd = if (sel) sl.optString("days").replace(d.toString(), "") else sl.optString("days") + d
                                        saveSched(sched.map { if (it === sl) it.with("days", nd) else it })
                                    }.padding(4.dp), color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.Bold else null)
                                } }
                                Row {
                                    TimeButton("De", sl.optString("start")) { v -> saveSched(sched.map { if (it === sl) it.with("start", v) else it }) }
                                    Spacer(Modifier.width(6.dp))
                                    TimeButton("à", sl.optString("end")) { v -> saveSched(sched.map { if (it === sl) it.with("end", v) else it }) }
                                }
                            }
                            TextButton(onClick = { guarded { saveSched(sched - sl) } }, enabled = !locked) { Text("✕") }
                        }
                    }
                    TextButton(onClick = { saveSched(sched + org.json.JSONObject().put("id", System.nanoTime()).put("days", "12345").put("start", "09:00").put("end", "18:00")) }) { Text("+ Ajouter une plage") }
                }
            }
            // ---- PIN
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔒 Protection par code", style = MaterialTheme.typography.titleMedium)
                    if (hasPin) {
                        Text("Un code est demandé pour désactiver le blocage, retirer un site ou une catégorie.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { guarded { p.edit().remove("bl_pin").apply(); hasPin = false } }, enabled = !locked) { Text("Supprimer le code") }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newPin, { newPin = it }, Modifier.weight(1f), label = { Text("Nouveau code (4+ chiffres)") }, singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                        TextButton(onClick = { if (newPin.length >= 4) { p.edit().putString("bl_pin", sha256("bl:$newPin")).apply(); hasPin = true; newPin = "" } }) { Text("Définir") }
                    }
                }
            }
            Text("Fonctionne via un VPN local : aucune donnée ne quitte ton téléphone. Si un site passe quand même, désactive « DNS privé » dans les réglages Android et « DNS sécurisé » dans Chrome. Astuce : active « VPN permanent » pour Toolbox dans Réglages > Réseau > VPN.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Text("Catégories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        items(BlockList.presets.keys.toList()) { pr ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pr)
                    Text(BlockList.presets[pr]!!.take(4).joinToString(", ") + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Switch(pr in presets, { if (it) savePresets(presets + pr) else guarded { savePresets(presets - pr) } }, enabled = !(locked && pr in presets))
            }
        }
        item {
            Text("Mes sites", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("ex : youtube.com") }, singleLine = true)
                TextButton(onClick = { val d = BlockList.normalize(input); if (d.contains('.')) saveCustom(custom + d); input = "" }) { Text("Bloquer") }
            }
            Text("Les sous-domaines sont aussi bloqués (m.site.com, www.site.com…).", style = MaterialTheme.typography.bodySmall)
        }
        items(custom.sorted()) { d ->
            ListItem(headlineContent = { Text(d) }, trailingContent = { if (!locked) TextButton(onClick = { guarded { saveCustom(custom - d) } }) { Text("✕") } })
        }
        if (recent.isNotEmpty()) {
            item { Text("Derniers blocages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(recent.take(15)) { Text("⛔ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline) }
        }
    }
}
