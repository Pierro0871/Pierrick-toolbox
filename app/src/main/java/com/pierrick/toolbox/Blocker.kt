package com.pierrick.toolbox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
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
        if (intent?.action == STOP) { stopVpn(); stopSelf(); return START_NOT_STICKY }
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
        var blocked = BlockList.all(this); var lastLoad = System.currentTimeMillis()
        try {
            while (running) {
                val n = input.read(buf); if (n <= 0) continue
                if (System.currentTimeMillis() - lastLoad > 3000) { blocked = BlockList.all(this); lastLoad = System.currentTimeMillis() }
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
    var on by remember { mutableStateOf(BlockerVpnService.running) }
    var custom by remember { mutableStateOf(BlockList.custom(ctx)) }
    var presets by remember { mutableStateOf(BlockList.enabledPresets(ctx)) }
    var input by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(BlockerVpnService.blockedCount) }
    var recent by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        while (true) {
            on = BlockerVpnService.running; count = BlockerVpnService.blockedCount
            recent = synchronized(BlockerVpnService.lastBlocked) { BlockerVpnService.lastBlocked.toList() }
            delay(1000)
        }
    }
    fun start() { ctx.startService(Intent(ctx, BlockerVpnService::class.java)); on = true }
    val vpnReq = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == Activity.RESULT_OK) start() }
    fun saveCustom(s: Set<String>) { custom = s; prefs(ctx).edit().putStringSet("bl_custom", s).apply() }
    fun savePresets(s: Set<String>) { presets = s; prefs(ctx).edit().putStringSet("bl_presets", s).apply() }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (on) "🛡️ Blocage actif" else "Blocage désactivé", style = MaterialTheme.typography.titleLarge)
                        Text("${BlockList.all(ctx).size} domaines • $count requêtes bloquées", color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(on, { want ->
                        if (want) { val i = VpnService.prepare(ctx); if (i != null) vpnReq.launch(i) else start() }
                        else { ctx.startService(Intent(ctx, BlockerVpnService::class.java).setAction(BlockerVpnService.STOP)); on = false }
                    })
                }
            }
            Text("Fonctionne via un VPN local : aucune donnée ne quitte ton téléphone. Si un site passe quand même, désactive « DNS privé » dans les réglages Android et « DNS sécurisé » dans Chrome.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Text("Catégories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        items(BlockList.presets.keys.toList()) { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p)
                    Text(BlockList.presets[p]!!.take(4).joinToString(", ") + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Switch(p in presets, { savePresets(if (it) presets + p else presets - p) })
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
            ListItem(headlineContent = { Text(d) }, trailingContent = { TextButton(onClick = { saveCustom(custom - d) }) { Text("✕") } })
        }
        if (recent.isNotEmpty()) {
            item { Text("Derniers blocages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(recent.take(15)) { Text("⛔ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline) }
        }
    }
}
