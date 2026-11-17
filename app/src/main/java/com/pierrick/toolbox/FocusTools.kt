package com.pierrick.toolbox

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ---------------- Temps d'écran ----------------

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
}

/** Temps au premier plan par appli entre deux instants (via les événements, plus précis que les stats agrégées). */
fun usageBetween(ctx: Context, start: Long, end: Long): Map<String, Long> {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val ev = usm.queryEvents(start, end); val e = UsageEvents.Event()
    val open = HashMap<String, Long>(); val total = HashMap<String, Long>()
    while (ev.hasNextEvent()) {
        ev.getNextEvent(e)
        when (e.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> open[e.packageName] = e.timeStamp
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> open.remove(e.packageName)?.let { total[e.packageName] = (total[e.packageName] ?: 0) + (e.timeStamp - it) }
        }
    }
    open.forEach { (p, t) -> total[p] = (total[p] ?: 0) + (end - t) }
    return total.filterKeys { it != ctx.packageName || true }
}

fun startOfDay(d: LocalDate = LocalDate.now()) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun fmtMin(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min" }

class LimitReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!hasUsageAccess(ctx)) return
        val limits = runCatching { JSONObject(prefs(ctx).getString("limits", "{}")!!) }.getOrDefault(JSONObject())
        if (limits.length() == 0) return
        val today = LocalDate.now().toString()
        val usage = usageBetween(ctx, startOfDay(), System.currentTimeMillis())
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("limits", "Limites de temps", NotificationManager.IMPORTANCE_HIGH))
        limits.keys().forEach { pkg ->
            val lim = limits.getInt(pkg) * 60_000L; val used = usage[pkg] ?: 0
            val key = "limit_notified_${pkg}"
            if (used >= lim && prefs(ctx).getString(key, "") != today) {
                val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                nm.notify(pkg.hashCode(), NotificationCompat.Builder(ctx, "limits").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("⏳ Limite atteinte : $label").setContentText("Tu as utilisé $label ${fmtMin(used)} aujourd'hui (limite ${fmtMin(lim)}). Pause ?")
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
                prefs(ctx).edit().putString(key, today).apply()
            }
        }
    }
}

fun scheduleLimitChecks(ctx: Context) {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(ctx, 9191, Intent(ctx, LimitReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, 10 * 60_000L, pi)
}

@Composable
fun ScreenTimeScreen() {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(hasUsageAccess(ctx)) }
    var days by remember { mutableIntStateOf(0) } // 0 aujourd'hui, 1 hier, 7 = 7 jours
    var limits by remember { mutableStateOf(runCatching { JSONObject(prefs(ctx).getString("limits", "{}")!!) }.getOrDefault(JSONObject())) }
    var editing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (!access) { delay(1500); access = hasUsageAccess(ctx) } }
    if (!access) {
        ToolColumn {
            Text("Pour afficher ton temps d'écran, autorise « Accès aux données d'utilisation » pour Toolbox.")
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Ouvrir les réglages") }
        }
        return
    }
    val usage = remember(days) {
        val (s, e) = when (days) { 0 -> startOfDay() to System.currentTimeMillis(); 1 -> startOfDay(LocalDate.now().minusDays(1)) to startOfDay(); else -> startOfDay(LocalDate.now().minusDays(6)) to System.currentTimeMillis() }
        usageBetween(ctx, s, e).filter { it.value > 30_000 }.entries.sortedByDescending { it.value }
    }
    val pm = ctx.packageManager
    fun label(p: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    val total = usage.sumOf { it.value }
    editing?.let { p ->
        var v by remember(p) { mutableStateOf(limits.optInt(p, 0).takeIf { it > 0 }?.toString() ?: "30") }
        AlertDialog({ editing = null }, title = { Text("Limite pour ${label(p)}") },
            text = { Column { NumField(v, "Minutes par jour", Modifier.fillMaxWidth()) { v = it }; Text("Tu recevras une notification quand la limite est atteinte.", style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = {
                limits = JSONObject(limits.toString()).apply { v.toIntOrNull()?.takeIf { it > 0 }?.let { put(p, it) } ?: remove(p) }
                prefs(ctx).edit().putString("limits", limits.toString()).apply(); scheduleLimitChecks(ctx); editing = null
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { limits = JSONObject(limits.toString()).apply { remove(p) }; prefs(ctx).edit().putString("limits", limits.toString()).apply(); editing = null }) { Text("Retirer la limite") } })
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Aujourd'hui", 1 to "Hier", 7 to "7 jours").forEach { (d, l) -> FilterChip(days == d, { days = d }, label = { Text(l) }) } }
            Text(fmtMin(total), fontSize = 48.sp)
            Text(if (days == 7) "soit ${fmtMin(total / 7)} par jour en moyenne" else "d'écran", color = MaterialTheme.colorScheme.outline)
            Text("Touche une appli pour lui fixer une limite quotidienne ⏳", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        }
        items(usage, key = { it.key }) { (p, ms) ->
            val lim = limits.optInt(p, 0)
            val today = if (days == 0) ms else 0
            Column(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 6.dp)) {
                Row { Text(label(p), Modifier.weight(1f)); Text(fmtMin(ms)) }
                LinearProgressIndicator({ (ms.toFloat() / usage.first().value).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = if (lim > 0 && today >= lim * 60_000L) Color(0xFFE53935) else MaterialTheme.colorScheme.primary)
                if (lim > 0) Text("⏳ Limite : $lim min/jour", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

// ---------------- Coffre à mots de passe ----------------

private object VaultCrypto {
    private const val ALIAS = "toolbox_vault_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }
    fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Demande l'empreinte / le code de l'appareil ; à défaut, un PIN propre à l'appli. */
fun authenticate(ctx: Context, title: String, onOk: () -> Unit, onFail: (String) -> Unit) {
    val act = ctx as? FragmentActivity ?: return onFail("Indisponible")
    val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(ctx).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) return onFail("NONE")
    BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onOk()
        override fun onAuthenticationError(code: Int, msg: CharSequence) = onFail(msg.toString())
    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth).build())
}

@Composable
fun VaultScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = prefs(ctx)
    var unlocked by remember { mutableStateOf(false) }
    var needPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(listOf<JSONObject>()) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var reveal by remember { mutableStateOf<Long?>(null) }
    var q by remember { mutableStateOf("") }
    fun load() { entries = p.getString("vault_data", null)?.let { runCatching { JSONArray(VaultCrypto.decrypt(it)) }.getOrNull() }?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList() }
    fun save(l: List<JSONObject>) { entries = l; p.edit().putString("vault_data", VaultCrypto.encrypt(JSONArray(l).toString())).apply() }
    fun unlock() {
        authenticate(ctx, "Ouvrir le coffre", { unlocked = true; load() }) { m -> if (m == "NONE") needPin = true else err = m }
    }
    LaunchedEffect(Unit) { unlock() }
    if (!unlocked) {
        ToolColumn {
            Text("🗝️", fontSize = 72.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("Coffre verrouillé", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (needPin) {
                val hasPin = p.getString("vault_pin", null) != null
                Text(if (hasPin) "Entre ton code du coffre" else "Aucun verrouillage d'écran sur ce téléphone : choisis un code pour le coffre (4 chiffres min.)")
                OutlinedTextField(pin, { pin = it }, Modifier.fillMaxWidth(), label = { Text("Code") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                Button(onClick = {
                    if (!hasPin && pin.length >= 4) { p.edit().putString("vault_pin", sha256("vault:$pin")).apply(); unlocked = true; load() }
                    else if (hasPin && sha256("vault:$pin") == p.getString("vault_pin", "")) { unlocked = true; load() } else err = "Code incorrect"
                    pin = ""
                }) { Text("Déverrouiller") }
            } else Button(onClick = { err = null; unlock() }, Modifier.fillMaxWidth()) { Text("🔓 Déverrouiller (empreinte / code)") }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Chiffré en AES-256 avec une clé stockée dans la puce sécurisée du téléphone. Rien ne quitte l'appareil.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    editing?.let { e ->
        RecordEditor(e, listOf(Field("site", "Site / appli"), Field("user", "Identifiant / e-mail"), Field("pass", "Mot de passe"), Field("url", "Adresse web"), Field("note", "Note", FType.MULTI)),
            onCancel = { editing = null }, onDelete = if (entries.any { it.getLong("id") == e.getLong("id") }) { { save(entries.filter { it.getLong("id") != e.getLong("id") }); editing = null } } else null
        ) { n -> save(if (entries.any { it.getLong("id") == n.getLong("id") }) entries.map { if (it.getLong("id") == n.getLong("id")) n else it } else entries + n); editing = null }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val r = SecureRandom(); val pool = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*-_?"
                    editing = JSONObject().put("id", System.currentTimeMillis()).put("pass", String(CharArray(18) { pool[r.nextInt(pool.length)] }))
                }) { Text("+ Ajouter") }
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("🔎") }, singleLine = true)
                TextButton(onClick = { unlocked = false; entries = emptyList() }) { Text("🔒") }
            }
            val reused = entries.groupBy { it.optString("pass") }.filter { it.key.isNotBlank() && it.value.size > 1 }
            if (reused.isNotEmpty()) Text("⚠️ ${reused.values.sumOf { it.size }} comptes partagent le même mot de passe", color = Color(0xFFFB8C00))
            val weak = entries.count { it.optString("pass").length < 10 }
            if (weak > 0) Text("⚠️ $weak mot(s) de passe court(s) (< 10 caractères)", color = Color(0xFFFB8C00))
        }
        items(entries.filter { it.toString().contains(q, true) }.sortedBy { it.optString("site").lowercase() }, key = { it.getLong("id") }) { e ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { editing = e }) {
                            Text(e.optString("site").ifBlank { "Sans nom" }, style = MaterialTheme.typography.titleMedium)
                            Text(e.optString("user"), color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton(onClick = { copy(ctx, e.optString("user")) }) { Text("👤") }
                        TextButton(onClick = {
                            copy(ctx, e.optString("pass"))
                            scope.launch { delay(30_000); copy(ctx, "") } // efface le presse-papiers après 30 s
                        }) { Text("🔑") }
                        TextButton(onClick = { reveal = if (reveal == e.getLong("id")) null else e.getLong("id") }) { Text("👁") }
                    }
                    if (reveal == e.getLong("id")) Text(e.optString("pass"), fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                }
            }
        }
        item { Text("🔑 copie le mot de passe (effacé du presse-papiers après 30 s).", style = MaterialTheme.typography.bodySmall) }
    }
}

// ---------------- Analyse de lien ----------------

private val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rebrand.ly", "shorturl.at", "tiny.cc", "rb.gy", "s.id", "lnkd.in")
private val riskyTlds = setOf("zip", "mov", "top", "xyz", "tk", "ml", "ga", "cf", "gq", "click", "country", "kim", "work", "loan", "rest", "cam", "icu", "buzz")
private val brands = mapOf("paypal" to "paypal.com", "amazon" to "amazon.fr", "apple" to "apple.com", "netflix" to "netflix.com", "ameli" to "ameli.fr",
    "impots" to "impots.gouv.fr", "laposte" to "laposte.fr", "colissimo" to "laposte.fr", "chronopost" to "chronopost.fr", "credit-agricole" to "credit-agricole.fr",
    "creditagricole" to "credit-agricole.fr", "societegenerale" to "societegenerale.fr", "bnpparibas" to "bnpparibas", "labanquepostale" to "labanquepostale.fr",
    "microsoft" to "microsoft.com", "google" to "google.com", "facebook" to "facebook.com", "instagram" to "instagram.com", "antai" to "antai.gouv.fr",
    "caf" to "caf.fr", "orange" to "orange.fr", "free" to "free.fr", "sfr" to "sfr.fr", "bouygues" to "bouyguestelecom.fr", "dhl" to "dhl.com", "ups" to "ups.com", "edf" to "edf.fr")

@Composable
fun LinkCheckScreen() {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var chain by remember { mutableStateOf(listOf<Pair<Int, String>>()) }
    var warnings by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    fun analyze() {
        var u = url.trim(); if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        busy = true; chain = emptyList(); warnings = emptyList()
        scope.launch {
            val ch = mutableListOf<Pair<Int, String>>()
            withContext(Dispatchers.IO) {
                var cur = u
                repeat(10) {
                    val code = runCatching {
                        val c = URL(cur).openConnection() as HttpURLConnection
                        c.instanceFollowRedirects = false; c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Toolbox-LinkCheck")
                        val rc = c.responseCode; val loc = c.getHeaderField("Location"); c.disconnect()
                        ch += rc to cur
                        if (rc in 300..399 && loc != null) { cur = URL(URL(cur), loc).toString(); rc } else -1
                    }.getOrElse { ch += 0 to cur; -1 }
                    if (code == -1) return@withContext
                }
            }
            chain = ch
            val w = mutableListOf<String>()
            val all = (listOf(u) + ch.map { it.second }).distinct()
            all.forEach { link ->
                val host = runCatching { URI(link).host?.lowercase() }.getOrNull() ?: return@forEach
                val tld = host.substringAfterLast('.')
                if (link.startsWith("http://")) w += "🔓 $host : connexion non chiffrée (http)"
                if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) w += "🔢 $host : adresse IP brute au lieu d'un nom de domaine"
                if (host.contains("xn--")) w += "🎭 $host : caractères internationaux (affiché « ${IDN.toUnicode(host)} ») — risque d'usurpation"
                if (host.split('.').size > 4) w += "🪜 $host : beaucoup de sous-domaines"
                if (tld in riskyTlds) w += "⚠️ $host : extension .$tld souvent utilisée pour des arnaques"
                if (host in shorteners || host.removePrefix("www.") in shorteners) w += "🔗 $host : raccourcisseur de lien (la vraie destination est cachée)"
                if (link.contains("@") && link.indexOf('@') < link.indexOf(host) + host.length + 5) w += "📧 « @ » dans l'adresse : technique de tromperie"
                brands.forEach { (k, real) -> if (host.replace("-", "").contains(k.replace("-", "")) && !host.endsWith(real) && !host.endsWith(real.substringBefore('.') + ".com")) w += "🚩 $host imite peut-être « $real »" }
                if (link.length > 150) w += "📏 Lien très long"
            }
            if (ch.lastOrNull()?.first == 0) w += "❌ Le site ne répond pas"
            warnings = w.distinct(); busy = false
        }
    }
    ToolColumn {
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Colle le lien suspect (SMS, mail…)") }, minLines = 2)
        Button(onClick = { analyze() }, Modifier.fillMaxWidth(), enabled = !busy) { Text("🔍 Analyser sans ouvrir") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (chain.isNotEmpty()) {
            val final = chain.last().second
            val host = runCatching { URI(final).host }.getOrNull() ?: "?"
            Card(colors = CardDefaults.cardColors(containerColor = if (warnings.isEmpty()) Color(0xFF43A047).copy(.2f) else Color(0xFFE53935).copy(.15f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (warnings.isEmpty()) "✅ Aucun signal d'alerte évident" else "⚠️ ${warnings.size} signal(aux) d'alerte", style = MaterialTheme.typography.titleLarge)
                    Text("Destination finale : $host", style = MaterialTheme.typography.titleMedium)
                }
            }
            warnings.forEach { Text(it) }
            Text("Chemin des redirections", style = MaterialTheme.typography.titleSmall)
            chain.forEachIndexed { i, (code, l) -> Text("${i + 1}. [${if (code == 0) "erreur" else code}] $l", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
        Text("Conseils : ta banque, les impôts ou Ameli ne demandent jamais tes codes par SMS. En cas de doute, va directement sur le site officiel. Signale les arnaques au 33700.", style = MaterialTheme.typography.bodySmall)
    }
}
