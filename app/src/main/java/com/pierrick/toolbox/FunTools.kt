package com.pierrick.toolbox

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val wheelColors = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA, 0xFFFB8C00, 0xFF00ACC1, 0xFFD81B60, 0xFF6D4C41, 0xFF3949AB).map { Color(it) }

// ---------------- Roue de la fortune ----------------

@Composable
fun WheelScreen() {
    val ctx = LocalContext.current
    val p = prefs(ctx)
    var text by remember { mutableStateOf(p.getString("wheel", "Pizza\nSushi\nBurger\nTacos\nSalade\nKebab")!!) }
    val items = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val rot = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    ToolColumn {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize(0.95f).rotate(rot.value)) {
                if (items.isEmpty()) return@Canvas
                val sweep = 360f / items.size
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = with(density) { 14.sp.toPx() }; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.RIGHT }
                items.forEachIndexed { i, s ->
                    drawArc(wheelColors[i % wheelColors.size], i * sweep - 90f, sweep, true)
                    rotate(i * sweep + sweep / 2 - 90f) {
                        drawContext.canvas.nativeCanvas.drawText(s.take(14), center.x + size.width / 2 - 16f, center.y + 10f, paint)
                    }
                }
                drawCircle(Color.White, size.minDimension * 0.06f)
            }
            Canvas(Modifier.fillMaxSize()) {
                val path = Path().apply { moveTo(center.x - 22f, 0f); lineTo(center.x + 22f, 0f); lineTo(center.x, 50f); close() }
                drawPath(path, Color.Black)
            }
        }
        result?.let { Text("🎉 $it", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.primary) }
        Button(onClick = {
            if (items.size < 2 || rot.isRunning) return@Button
            result = null
            scope.launch {
                val target = rot.value + 360f * Random.nextInt(5, 9) + Random.nextFloat() * 360f
                rot.animateTo(target, tween(4500, easing = FastOutSlowInEasing))
                val angle = ((360f - rot.value % 360f) % 360f)
                result = items[(angle / (360f / items.size)).toInt() % items.size]
                vibrate(ctx, 120)
            }
        }, Modifier.fillMaxWidth().height(56.dp)) { Text("🎡 Tourner !", fontSize = 18.sp) }
        OutlinedTextField(text, { text = it; p.edit().putString("wheel", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Choix (un par ligne)") }, minLines = 4)
    }
}

// ---------------- Équipes ----------------

@Composable
fun TeamsScreen() {
    val ctx = LocalContext.current
    var names by remember { mutableStateOf(prefs(ctx).getString("teams", "")!!) }
    var n by remember { mutableIntStateOf(2) }
    var teams by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    ToolColumn {
        OutlinedTextField(names, { names = it; prefs(ctx).edit().putString("teams", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Participants (un par ligne)") }, minLines = 5)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nombre d'équipes : $n", Modifier.weight(1f))
            TextButton(onClick = { if (n > 2) n-- }) { Text("−") }; TextButton(onClick = { n++ }) { Text("+") }
        }
        Button(onClick = {
            val l = names.lines().map { it.trim() }.filter { it.isNotEmpty() }.shuffled()
            teams = List(n) { i -> l.filterIndexed { j, _ -> j % n == i } }; vibrate(ctx)
        }, Modifier.fillMaxWidth()) { Text("👥 Former les équipes") }
        teams.forEachIndexed { i, t ->
            Card(colors = CardDefaults.cardColors(containerColor = wheelColors[i % wheelColors.size].copy(alpha = .2f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Équipe ${i + 1} (${t.size})", style = MaterialTheme.typography.titleMedium)
                    Text(t.joinToString(" • "))
                }
            }
        }
        if (teams.isNotEmpty()) OutlinedButton(onClick = { shareText(ctx, teams.mapIndexed { i, t -> "Équipe ${i + 1} : ${t.joinToString(", ")}" }.joinToString("\n")) }) { Text("Partager") }
    }
}

// ---------------- Pendule d'échecs ----------------

@Composable
fun ChessClockScreen() {
    val ctx = LocalContext.current
    var minutes by remember { mutableIntStateOf(5) }
    var inc by remember { mutableIntStateOf(0) }
    var times by remember { mutableStateOf(listOf(300_000L, 300_000L)) }
    var active by remember { mutableIntStateOf(-1) }
    var paused by remember { mutableStateOf(false) }
    var moves by remember { mutableStateOf(listOf(0, 0)) }
    LaunchedEffect(active, paused) {
        if (active < 0 || paused) return@LaunchedEffect
        var last = System.currentTimeMillis()
        while (true) {
            delay(50); val now = System.currentTimeMillis()
            val t = times.toMutableList(); t[active] = (t[active] - (now - last)).coerceAtLeast(0); last = now; times = t
            if (t[active] == 0L) { vibrate(ctx, 1000); active = -2; break }
        }
    }
    fun reset() { times = listOf(minutes * 60_000L, minutes * 60_000L); active = -1; paused = false; moves = listOf(0, 0) }
    fun press(p: Int) {
        if (active == -2 || paused) return
        if (active == -1 || active == p) {
            if (active == p) { val t = times.toMutableList(); t[p] += inc * 1000L; times = t; moves = moves.toMutableList().also { it[p]++ } }
            active = 1 - p; vibrate(ctx, 20)
        }
    }
    @Composable fun Side(p: Int, mod: Modifier) {
        val t = times[p]; val on = active == p
        Box(mod.fillMaxWidth().background(when { t == 0L -> Color(0xFFE53935); on -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceVariant })
            .clickable { press(p) }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%d:%02d".format(t / 60000, t / 1000 % 60) + if (t < 20000) ".${t / 100 % 10}" else "", fontSize = 72.sp,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Coups : ${moves[p]}", color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Side(0, Modifier.weight(1f).rotate(180f))
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1 to 0, 3 to 2, 5 to 0, 10 to 0, 15 to 10, 30 to 0).forEach { (m, i) ->
                FilterChip(minutes == m && inc == i, { minutes = m; inc = i; times = listOf(m * 60_000L, m * 60_000L); active = -1; moves = listOf(0, 0) },
                    label = { Text(if (i > 0) "$m+$i" else "$m'", fontSize = 12.sp) })
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { paused = !paused }) { Text(if (paused) "▶" else "⏸") }
            TextButton(onClick = { reset() }) { Text("↺") }
        }
        Side(1, Modifier.weight(1f))
    }
}

// ---------------- Générateur de pseudos ----------------

private val adjs = listOf("Rapide", "Sombre", "Brave", "Mystique", "Cosmique", "Sauvage", "Rusé", "Silencieux", "Électrique", "Gelé", "Doré", "Fou", "Légendaire", "Turbo", "Néon", "Royal", "Furtif", "Pixel", "Atomique", "Lunaire")
private val nouns = listOf("Loup", "Phénix", "Dragon", "Ninja", "Tigre", "Renard", "Faucon", "Panda", "Kraken", "Viking", "Samouraï", "Lynx", "Corbeau", "Golem", "Pirate", "Yéti", "Spectre", "Titan", "Cobra", "Hibou")
private val enAdj = listOf("Shadow", "Cyber", "Night", "Iron", "Frost", "Blaze", "Storm", "Neo", "Hyper", "Silent", "Dark", "Lucky", "Toxic", "Crimson", "Void")
private val enNoun = listOf("Wolf", "Hunter", "Rider", "Ghost", "Fox", "Knight", "Viper", "Raven", "Sniper", "Ninja", "Bear", "Hawk", "Byte", "Storm", "Blade")
private val firstM = listOf("Lucas", "Hugo", "Gabriel", "Louis", "Arthur", "Jules", "Adam", "Léo", "Raphaël", "Noah", "Nathan", "Ethan", "Paul", "Tom", "Sacha", "Gabin", "Maël", "Liam", "Aaron", "Victor")
private val firstF = listOf("Emma", "Jade", "Louise", "Alice", "Chloé", "Lina", "Rose", "Léa", "Mila", "Anna", "Inès", "Ambre", "Julia", "Mia", "Léna", "Zoé", "Agathe", "Lou", "Iris", "Nina")
private val ideas = listOf("Faire un pique-nique", "Soirée jeux de société", "Cuisiner un plat d'un pays inconnu", "Randonnée", "Visiter un musée", "Marathon de films",
    "Apprendre 10 mots dans une nouvelle langue", "Faire un puzzle", "Écrire une lettre à un ami", "Ranger un placard", "Balade à vélo", "Faire du karaoké",
    "Tester un nouveau sport", "Faire des crêpes", "Créer une playlist", "Observer les étoiles", "Aller au marché", "Faire un escape game", "Bricoler un truc", "Méditer 10 minutes")

@Composable
fun NameGenScreen() {
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(listOf<String>()) }
    fun gen() {
        results = List(8) {
            when (mode) {
                0 -> adjs.random() + nouns.random() + if (Random.nextBoolean()) Random.nextInt(10, 99) else ""
                1 -> enAdj.random() + enNoun.random() + if (Random.nextBoolean()) "_" + Random.nextInt(1, 999) else ""
                2 -> firstM.random(); 3 -> firstF.random(); else -> ideas.random()
            }
        }.distinct()
    }
    LaunchedEffect(mode) { gen() }
    ToolColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Pseudo FR", "Gamer", "Prénom ♂", "Prénom ♀", "Idée d'activité").forEachIndexed { i, l -> FilterChip(mode == i, { mode = i }, label = { Text(l, fontSize = 12.sp) }) }
        }
        Button(onClick = { gen(); vibrate(ctx) }, Modifier.fillMaxWidth()) { Text("🔄 Générer") }
        results.forEach { r ->
            ElevatedCard(Modifier.fillMaxWidth().clickable { copy(ctx, r) }) { Text(r, Modifier.padding(14.dp), fontSize = 20.sp) }
        }
        Text("Touche un résultat pour le copier", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Quiz ----------------

private data class Q(val q: String, val answers: List<String>, val ok: Int)
private val quiz = listOf(
    Q("Quelle est la capitale de l'Australie ?", listOf("Sydney", "Canberra", "Melbourne", "Perth"), 1),
    Q("Combien de côtés a un hexagone ?", listOf("5", "6", "7", "8"), 1),
    Q("Qui a peint la Joconde ?", listOf("Michel-Ange", "Raphaël", "Léonard de Vinci", "Botticelli"), 2),
    Q("Quel est le plus grand océan ?", listOf("Atlantique", "Indien", "Arctique", "Pacifique"), 3),
    Q("En quelle année a eu lieu la prise de la Bastille ?", listOf("1789", "1792", "1776", "1804"), 0),
    Q("Quel est le symbole chimique de l'or ?", listOf("Ag", "Au", "Or", "Go"), 1),
    Q("Combien de joueurs dans une équipe de rugby à XV sur le terrain ?", listOf("11", "13", "15", "16"), 2),
    Q("Quelle planète est surnommée la planète rouge ?", listOf("Vénus", "Jupiter", "Mars", "Mercure"), 2),
    Q("Quel est le plus long fleuve de France ?", listOf("La Seine", "Le Rhône", "La Garonne", "La Loire"), 3),
    Q("Qui a écrit « Les Misérables » ?", listOf("Émile Zola", "Victor Hugo", "Balzac", "Flaubert"), 1),
    Q("Combien d'os dans le corps humain adulte ?", listOf("186", "206", "226", "256"), 1),
    Q("Quelle est la monnaie du Japon ?", listOf("Yuan", "Won", "Yen", "Roupie"), 2),
    Q("Quel est le plus haut sommet d'Europe occidentale ?", listOf("Mont Blanc", "Cervin", "Mont Rose", "Grand Combin"), 0),
    Q("Quel gaz les plantes absorbent-elles ?", listOf("Oxygène", "Azote", "CO₂", "Hélium"), 2),
    Q("Combien de minutes dans une journée ?", listOf("1 240", "1 440", "1 640", "2 400"), 1),
    Q("Quel pays a la forme d'une botte ?", listOf("Grèce", "Espagne", "Portugal", "Italie"), 3),
    Q("Qui a découvert la pénicilline ?", listOf("Pasteur", "Fleming", "Curie", "Koch"), 1),
    Q("Quel est l'animal le plus rapide sur terre ?", listOf("Lion", "Guépard", "Antilope", "Lévrier"), 1),
    Q("Combien de pays dans l'Union européenne (2024) ?", listOf("25", "27", "28", "30"), 1),
    Q("Quelle est la capitale du Canada ?", listOf("Toronto", "Montréal", "Ottawa", "Vancouver"), 2),
    Q("Quel instrument a 88 touches ?", listOf("Orgue", "Piano", "Accordéon", "Clavecin"), 1),
    Q("Quel est le plus petit pays du monde ?", listOf("Monaco", "Vatican", "Saint-Marin", "Liechtenstein"), 1),
    Q("Qui était le premier homme sur la Lune ?", listOf("Buzz Aldrin", "Youri Gagarine", "Neil Armstrong", "John Glenn"), 2),
    Q("Combien font 12 × 12 ?", listOf("124", "144", "148", "132"), 1),
    Q("Quelle langue compte le plus de locuteurs natifs ?", listOf("Anglais", "Espagnol", "Mandarin", "Hindi"), 2),
    Q("En quelle année l'euro est-il entré en circulation ?", listOf("1999", "2000", "2002", "2004"), 2),
    Q("Quel organe produit l'insuline ?", listOf("Foie", "Pancréas", "Rein", "Rate"), 1),
    Q("Quel est le désert le plus grand (chaud) ?", listOf("Gobi", "Kalahari", "Sahara", "Atacama"), 2),
    Q("Qui a composé « La Flûte enchantée » ?", listOf("Beethoven", "Bach", "Mozart", "Verdi"), 2),
    Q("Combien de cœurs a une pieuvre ?", listOf("1", "2", "3", "4"), 2),
    Q("Quel est le plus grand mammifère ?", listOf("Éléphant", "Baleine bleue", "Girafe", "Orque"), 1),
    Q("Quelle ville est surnommée la Ville Lumière ?", listOf("Lyon", "New York", "Paris", "Rome"), 2),
    Q("Quel est le point d'ébullition de l'eau au niveau de la mer ?", listOf("90 °C", "100 °C", "110 °C", "120 °C"), 1),
    Q("Quelle est la capitale du Brésil ?", listOf("Rio de Janeiro", "São Paulo", "Brasília", "Salvador"), 2),
    Q("Combien de joueurs dans une équipe de football sur le terrain ?", listOf("9", "10", "11", "12"), 2),
    Q("Quel scientifique a formulé la relativité ?", listOf("Newton", "Einstein", "Bohr", "Hawking"), 1),
    Q("Quel est l'élément le plus abondant dans l'univers ?", listOf("Oxygène", "Carbone", "Hydrogène", "Hélium"), 2),
    Q("Quelle mer borde Marseille ?", listOf("Mer du Nord", "Manche", "Méditerranée", "Atlantique"), 2),
    Q("Combien de continents compte-t-on traditionnellement ?", listOf("5", "6", "7", "8"), 2),
    Q("Quel roi a fait construire Versailles ?", listOf("Louis XIII", "Louis XIV", "Louis XV", "Henri IV"), 1),
)

@Composable
fun QuizScreen() {
    val ctx = LocalContext.current
    var order by remember { mutableStateOf(quiz.indices.shuffled().take(10)) }
    var i by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val best = prefs(ctx).getInt("quiz_best", 0)
    ToolColumn {
        if (i >= order.size) {
            if (score > best) prefs(ctx).edit().putInt("quiz_best", score).apply()
            Text("🏁 Score : $score / ${order.size}", fontSize = 36.sp)
            Text(when { score >= 9 -> "🏆 Génie !"; score >= 7 -> "👏 Très bien"; score >= 5 -> "🙂 Pas mal"; else -> "📚 Il faut réviser !" }, fontSize = 22.sp)
            Text("Meilleur score : ${maxOf(best, score)}", color = MaterialTheme.colorScheme.outline)
            Button(onClick = { order = quiz.indices.shuffled().take(10); i = 0; score = 0; picked = null }) { Text("Rejouer") }
            return@ToolColumn
        }
        val q = quiz[order[i]]
        Text("Question ${i + 1}/${order.size} • Score $score", color = MaterialTheme.colorScheme.outline)
        LinearProgressIndicator({ i.toFloat() / order.size }, Modifier.fillMaxWidth())
        Text(q.q, style = MaterialTheme.typography.headlineSmall)
        q.answers.forEachIndexed { k, a ->
            val col = when { picked == null -> ButtonDefaults.filledTonalButtonColors(); k == q.ok -> ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                k == picked -> ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)); else -> ButtonDefaults.filledTonalButtonColors() }
            Button(onClick = { if (picked == null) { picked = k; if (k == q.ok) score++ else vibrate(ctx, 150) } }, Modifier.fillMaxWidth().height(54.dp), colors = col) { Text(a, fontSize = 17.sp) }
        }
        if (picked != null) Button(onClick = { i++; picked = null }, Modifier.align(Alignment.End)) { Text("Suivant →") }
    }
}

// ---------------- Mémoire ----------------

@Composable
fun MemoryScreen() {
    val ctx = LocalContext.current
    val emojis = listOf("🐶", "🐱", "🦊", "🐼", "🐸", "🦁", "🐵", "🐙", "🦄", "🐝", "🐢", "🦋")
    var pairs by remember { mutableIntStateOf(8) }
    var cards by remember { mutableStateOf(emptyList<String>()) }
    var found by remember { mutableStateOf(setOf<Int>()) }
    var open by remember { mutableStateOf(listOf<Int>()) }
    var tries by remember { mutableIntStateOf(0) }
    var start by remember { mutableLongStateOf(0L) }
    fun newGame() { cards = (emojis.shuffled().take(pairs) * 2).shuffled(); found = emptySet(); open = emptyList(); tries = 0; start = System.currentTimeMillis() }
    LaunchedEffect(pairs) { newGame() }
    LaunchedEffect(open) {
        if (open.size == 2) {
            tries++
            if (cards[open[0]] == cards[open[1]]) { found = found + open; open = emptyList(); vibrate(ctx, 30) }
            else { delay(800); open = emptyList() }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Essais : $tries", Modifier.weight(1f))
            listOf(6, 8, 10, 12).forEach { n -> FilterChip(pairs == n, { pairs = n }, label = { Text("${n * 2}") }, modifier = Modifier.padding(horizontal = 2.dp)) }
        }
        if (cards.isNotEmpty() && found.size == cards.size) {
            Text("🎉 Gagné en $tries essais et ${(System.currentTimeMillis() - start) / 1000} s !", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            Button(onClick = { newGame() }) { Text("Rejouer") }
        }
        LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(cards) { idx, e ->
                val visible = idx in found || idx in open
                Box(Modifier.aspectRatio(0.8f).clip(RoundedCornerShape(12.dp))
                    .background(if (visible) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !visible && open.size < 2) { open = open + idx }, contentAlignment = Alignment.Center) {
                    Text(if (visible) e else "❔", fontSize = 32.sp)
                }
            }
        }
    }
}

private operator fun <T> List<T>.times(n: Int): List<T> = (1..n).flatMap { this }

// ---------------- Citation du jour ----------------

private val quotes = listOf(
    "La vie, c'est comme une bicyclette, il faut avancer pour ne pas perdre l'équilibre. — Albert Einstein",
    "Le succès, c'est d'aller d'échec en échec sans perdre son enthousiasme. — Winston Churchill",
    "Ils ne savaient pas que c'était impossible, alors ils l'ont fait. — Mark Twain",
    "Celui qui déplace une montagne commence par déplacer de petites pierres. — Confucius",
    "On ne voit bien qu'avec le cœur. L'essentiel est invisible pour les yeux. — Antoine de Saint-Exupéry",
    "Le meilleur moment pour planter un arbre était il y a 20 ans. Le deuxième meilleur moment est maintenant. — Proverbe chinois",
    "Fais de ta vie un rêve, et d'un rêve, une réalité. — Antoine de Saint-Exupéry",
    "Il n'y a qu'une façon d'échouer, c'est d'abandonner avant d'avoir réussi. — Georges Clemenceau",
    "La simplicité est la sophistication suprême. — Léonard de Vinci",
    "Le bonheur n'est pas quelque chose de prêt à l'emploi. Il vient de vos propres actions. — Dalaï-Lama",
    "Choisissez un travail que vous aimez et vous n'aurez pas à travailler un seul jour de votre vie. — Confucius",
    "L'imagination est plus importante que le savoir. — Albert Einstein",
    "Un voyage de mille lieues commence toujours par un premier pas. — Lao Tseu",
    "La persévérance, c'est ce qui rend l'impossible possible. — Lionel Jospin",
    "Ce n'est pas parce que les choses sont difficiles que nous n'osons pas, c'est parce que nous n'osons pas qu'elles sont difficiles. — Sénèque",
    "Agis comme s'il était impossible d'échouer. — Dorothea Brande",
    "Rien n'est permanent, sauf le changement. — Héraclite",
    "Le pessimiste voit la difficulté dans chaque opportunité, l'optimiste voit l'opportunité dans chaque difficulté. — Winston Churchill",
    "Vis comme si tu devais mourir demain. Apprends comme si tu devais vivre toujours. — Gandhi",
    "Soyez le changement que vous voulez voir dans le monde. — Gandhi",
    "La seule limite à notre épanouissement de demain sera nos doutes d'aujourd'hui. — Franklin D. Roosevelt",
    "Il faut toujours viser la lune, car même en cas d'échec, on atterrit dans les étoiles. — Oscar Wilde",
    "Le courage n'est pas l'absence de peur, mais la capacité de la vaincre. — Nelson Mandela",
    "Hâte-toi de bien vivre et songe que chaque jour est à lui seul une vie. — Sénèque",
    "Ce qui ne me tue pas me rend plus fort. — Friedrich Nietzsche",
    "Le temps est un grand maître, dit-on. Le malheur est qu'il tue ses élèves. — Hector Berlioz",
    "Tout ce que vous pouvez faire, ou rêvez de faire, commencez-le. — Goethe",
    "L'échec est le fondement de la réussite. — Lao Tseu",
    "N'attendez pas d'être heureux pour sourire. Souriez plutôt afin d'être heureux. — Edward L. Kramer",
    "La connaissance s'acquiert par l'expérience, tout le reste n'est que de l'information. — Albert Einstein",
)
private val facts = listOf(
    "Les pieuvres ont trois cœurs et le sang bleu.", "Le miel ne se périme jamais : on en a trouvé de comestible dans des tombes égyptiennes.",
    "Une journée sur Vénus dure plus longtemps qu'une année sur Vénus.", "Les bananes sont légèrement radioactives (potassium 40).",
    "La tour Eiffel grandit d'environ 15 cm en été à cause de la dilatation.", "Les loutres de mer se tiennent la main en dormant pour ne pas dériver.",
    "Il y a plus d'arbres sur Terre que d'étoiles dans la Voie lactée.", "Le cœur d'une crevette se trouve dans sa tête.",
    "Un éclair est environ 5 fois plus chaud que la surface du Soleil.", "Les flamants roses sont roses grâce à leur alimentation.",
    "La France est le pays avec le plus de fuseaux horaires (12, avec l'outre-mer).", "Un escargot peut dormir jusqu'à 3 ans.",
    "Le cerveau humain consomme environ 20 % de l'énergie du corps.", "Les vaches ont des meilleures amies et stressent quand on les sépare.",
    "Le mot « robot » vient du tchèque « robota », travail forcé.", "Les koalas dorment jusqu'à 22 heures par jour.",
)

@Composable
fun QuoteScreen() {
    val ctx = LocalContext.current
    val day = LocalDate.now().toEpochDay().toInt()
    var qi by remember { mutableIntStateOf(day % quotes.size) }
    var fi by remember { mutableIntStateOf(day % facts.size) }
    ToolColumn {
        Text("💬 Citation du jour", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        val (txt, author) = quotes[qi].split(" — ").let { it[0] to it.getOrElse(1) { "" } }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("« $txt »", fontSize = 22.sp, lineHeight = 30.sp)
                Text("— $author", Modifier.align(Alignment.End).padding(top = 12.dp), color = MaterialTheme.colorScheme.outline)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { qi = Random.nextInt(quotes.size) }) { Text("🔄 Une autre") }
            OutlinedButton(onClick = { shareText(ctx, quotes[qi]) }) { Text("Partager") }
        }
        HorizontalDivider()
        Text("🤓 Le saviez-vous ?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) { Text(facts[fi], Modifier.padding(16.dp), fontSize = 18.sp) }
        OutlinedButton(onClick = { fi = Random.nextInt(facts.size) }) { Text("🔄 Une autre anecdote") }
    }
}

// ---------------- Réflexes ----------------

@Composable
fun ReflexScreen() {
    val ctx = LocalContext.current
    var state by remember { mutableIntStateOf(0) } // 0 prêt, 1 attente, 2 go, 3 résultat, 4 trop tôt
    var goAt by remember { mutableLongStateOf(0L) }
    var result by remember { mutableLongStateOf(0L) }
    var history by remember { mutableStateOf(listOf<Long>()) }
    val best = prefs(ctx).getLong("reflex_best", 0L)
    LaunchedEffect(state) {
        if (state == 1) { delay(Random.nextLong(1500, 5000)); if (state == 1) { goAt = System.nanoTime(); state = 2 } }
    }
    Box(Modifier.fillMaxSize().background(when (state) { 1 -> Color(0xFFC62828); 2 -> Color(0xFF2E7D32); 4 -> Color(0xFFFB8C00); else -> MaterialTheme.colorScheme.primaryContainer })
        .clickable {
            when (state) {
                0, 3, 4 -> state = 1
                1 -> state = 4
                2 -> {
                    result = (System.nanoTime() - goAt) / 1_000_000; history = (history + result).takeLast(5); state = 3
                    if (best == 0L || result < best) prefs(ctx).edit().putLong("reflex_best", result).apply()
                }
            }
        }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            when (state) {
                0 -> { Text("⚡", fontSize = 80.sp); Text("Touche l'écran pour commencer.\nQuand il devient VERT, touche le plus vite possible !", textAlign = TextAlign.Center, fontSize = 20.sp) }
                1 -> Text("Attends le vert…", fontSize = 32.sp, color = Color.White)
                2 -> Text("TOUCHE !", fontSize = 56.sp, color = Color.White, fontWeight = FontWeight.Bold)
                3 -> {
                    Text("$result ms", fontSize = 64.sp, fontWeight = FontWeight.Bold)
                    Text(when { result < 200 -> "🚀 Incroyable !"; result < 250 -> "⚡ Excellent"; result < 300 -> "👍 Bien (moyenne humaine ≈ 250 ms)"; else -> "🐢 Peut mieux faire" }, fontSize = 20.sp)
                    if (history.size > 1) Text("Moyenne des ${history.size} derniers : ${history.average().toInt()} ms")
                    Text("Record : ${minOf(best.takeIf { it > 0 } ?: result, result)} ms", color = MaterialTheme.colorScheme.outline)
                    Text("Touche pour rejouer", Modifier.padding(top = 16.dp))
                }
                4 -> { Text("Trop tôt ! 😅", fontSize = 40.sp, color = Color.White); Text("Touche pour réessayer", color = Color.White) }
            }
        }
    }
}

// ---------------- HIIT ----------------

@Composable
fun HiitScreen() {
    val ctx = LocalContext.current
    var work by remember { mutableIntStateOf(20) }; var rest by remember { mutableIntStateOf(10) }; var rounds by remember { mutableIntStateOf(8) }; var prep by remember { mutableIntStateOf(10) }
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }; var left by remember { mutableIntStateOf(0) }; var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        suspend fun phaseRun(name: String, sec: Int) {
            phase = name
            for (s in sec downTo 1) { left = s; if (s <= 3) tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120); delay(1000) }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400); vibrate(ctx, 300)
        }
        try {
            phaseRun("Préparation", prep)
            for (r in 1..rounds) { round = r; phaseRun("💪 EFFORT", work); if (r < rounds) phaseRun("😮‍💨 Repos", rest) }
            phase = "🏁 Terminé !"; left = 0
        } finally { tg.release(); running = false }
    }
    ToolColumn {
        if (running || phase == "🏁 Terminé !") {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(when { phase.contains("EFFORT") -> Color(0xFFE53935); phase.contains("Repos") -> Color(0xFF43A047); else -> MaterialTheme.colorScheme.secondaryContainer }).padding(32.dp),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(phase, fontSize = 30.sp, color = Color.White)
                    if (left > 0) Text("$left", fontSize = 110.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Round $round / $rounds", color = Color.White)
                }
            }
            Button(onClick = { running = false; phase = "" }, Modifier.fillMaxWidth()) { Text(if (running) "⏹ Arrêter" else "OK") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { work = 20; rest = 10; rounds = 8 }, label = { Text("Tabata 20/10×8") })
                AssistChip(onClick = { work = 40; rest = 20; rounds = 10 }, label = { Text("40/20×10") })
                AssistChip(onClick = { work = 30; rest = 30; rounds = 12 }, label = { Text("30/30×12") })
            }
            @Composable fun Stepper(label: String, v: Int, step: Int, set: (Int) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f)); FilledTonalButton(onClick = { set((v - step).coerceAtLeast(step)) }) { Text("−") }
                Text("$v", Modifier.width(60.dp), textAlign = TextAlign.Center, fontSize = 22.sp); FilledTonalButton(onClick = { set(v + step) }) { Text("+") }
            }
            Stepper("Effort (s)", work, 5) { work = it }; Stepper("Repos (s)", rest, 5) { rest = it }; Stepper("Rounds", rounds, 1) { rounds = it }; Stepper("Préparation (s)", prep, 5) { prep = it }
            Text("Durée totale : ${(prep + work * rounds + rest * (rounds - 1)) / 60} min ${(prep + work * rounds + rest * (rounds - 1)) % 60} s", color = MaterialTheme.colorScheme.outline)
            Button(onClick = { running = true }, Modifier.fillMaxWidth().height(56.dp)) { Text("▶ Démarrer", fontSize = 18.sp) }
        }
    }
}

// ---------------- Respiration ----------------

@Composable
fun BreathScreen() {
    val ctx = LocalContext.current
    val patterns = listOf("Cohérence cardiaque 5-5" to listOf(5, 0, 5, 0), "Carré 4-4-4-4" to listOf(4, 4, 4, 4), "Relaxation 4-7-8" to listOf(4, 7, 8, 0), "Énergie 6-0-2-0" to listOf(6, 0, 2, 0))
    var pi by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var cycles by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(5) }
    val scale = remember { Animatable(0.4f) }
    LaunchedEffect(running) {
        if (!running) { scale.snapTo(0.4f); label = ""; return@LaunchedEffect }
        val p = patterns[pi].second; val end = System.currentTimeMillis() + duration * 60_000L; cycles = 0
        while (running && System.currentTimeMillis() < end) {
            label = "Inspire"; vibrate(ctx, 30); scale.animateTo(1f, tween(p[0] * 1000, easing = LinearEasing))
            if (p[1] > 0) { label = "Retiens"; delay(p[1] * 1000L) }
            label = "Expire"; vibrate(ctx, 30); scale.animateTo(0.4f, tween(p[2] * 1000, easing = LinearEasing))
            if (p[3] > 0) { label = "Retiens"; delay(p[3] * 1000L) }
            cycles++
        }
        running = false
    }
    ToolColumn {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize(0.9f).scale(scale.value).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .7f)))
            Text(label.ifEmpty { "🌬️" }, fontSize = 32.sp, color = Color.White)
        }
        if (running) Text("Cycles : $cycles", Modifier.align(Alignment.CenterHorizontally))
        Dropdown("Exercice", patterns.indices.toList(), pi, { patterns[it].first }) { pi = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 3, 5, 10).forEach { m -> FilterChip(duration == m, { duration = m }, label = { Text("$m min") }) } }
        Button(onClick = { running = !running }, Modifier.fillMaxWidth().height(56.dp)) { Text(if (running) "⏹ Arrêter" else "▶ Commencer", fontSize = 18.sp) }
        Text("La cohérence cardiaque (6 respirations/min pendant 5 min, 3 fois par jour) aide à réduire le stress.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Méditation ----------------

@Composable
fun MeditationScreen() {
    val ctx = LocalContext.current
    var minutes by remember { mutableIntStateOf(10) }
    var interval by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    val total = prefs(ctx).getInt("medit_total", 0)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        fun bell() { tg.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 900) }
        try {
            bell(); left = minutes * 60
            while (left > 0 && running) {
                delay(1000); left--
                if (interval > 0 && left > 0 && (minutes * 60 - left) % (interval * 60) == 0) bell()
            }
            if (left == 0) { bell(); delay(1200); bell(); prefs(ctx).edit().putInt("medit_total", total + minutes).apply() }
        } finally { tg.release(); running = false }
    }
    val pulse by rememberInfiniteTransition(label = "m").animateFloat(0.85f, 1f, infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "p")
    ToolColumn {
        Box(Modifier.fillMaxWidth().aspectRatio(1.2f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize(0.8f).scale(if (running) pulse else 1f).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer))
            Text(if (running) "%d:%02d".format(left / 60, left % 60) else "🧘", fontSize = 56.sp)
        }
        if (!running) {
            Text("Durée", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(3, 5, 10, 15, 20, 30).forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text("$m") }) } }
            Text("Cloche intermédiaire", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Aucune", 1 to "1 min", 5 to "5 min").forEach { (v, l) -> FilterChip(interval == v, { interval = v }, label = { Text(l) }) } }
        }
        Button(onClick = { running = !running }, Modifier.fillMaxWidth().height(56.dp)) { Text(if (running) "⏹ Arrêter" else "▶ Commencer", fontSize = 18.sp) }
        Text("Temps total médité : ${total / 60} h ${total % 60} min", color = MaterialTheme.colorScheme.outline)
    }
}
