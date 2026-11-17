package com.pierrick.toolbox

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tool(val emoji: String, val title: String) {
    TORCH("🔦", "Lampe torche"),
    QR("📷", "QR / Codes-barres"),
    LEVEL("🧭", "Niveau & boussole"),
    RULER("📏", "Règle"),
    UNITS("🔁", "Unités"),
    CALC("🧮", "Calculatrice & pourboire"),
    CURRENCY("💱", "Devises"),
    DATES("📅", "Dates & âge"),
    CHRONO("⏱️", "Chrono & minuteur"),
    NOTES("📝", "Notes & listes"),
    COUNTER("🔢", "Compteur"),
    RANDOM("🎲", "Aléatoire"),
    PASSWORD("🔐", "Mot de passe"),
    SOUND("🔊", "Sonomètre"),
    DEVICE("📱", "Infos appareil"),
    MAGNIFIER("🔍", "Loupe"),
    MIRROR("🪞", "Miroir"),
    PEDOMETER("👟", "Podomètre"),
    BAROMETER("⛰️", "Baromètre / altimètre"),
    BMI("⚖️", "IMC & calories"),
    LOAN("🏦", "Prêt & pourcentages"),
    FUEL("⛽", "Carburant & trajet"),
    BASES("💻", "Bases & chiffres romains"),
    REMINDERS("⏰", "Rappels"),
    EVENTS("⏳", "Compte à rebours"),
    HABITS("✅", "Habitudes"),
    BUDGET("💶", "Budget"),
    SHOPPING("🛒", "Liste de courses"),
    WEATHER("🌤️", "Météo"),
    SPEEDTEST("🚀", "Test de débit"),
    WORLDCLOCK("🌍", "Fuseaux horaires"),
    NETINFO("📶", "Mon IP / Wi-Fi"),
    METRONOME("🥁", "Métronome"),
    TUNER("🎸", "Accordeur"),
    MORSE("📡", "Morse & tableau"),
}

fun prefs(ctx: Context) = ctx.getSharedPreferences("toolbox", Context.MODE_PRIVATE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            // 0 = système, 1 = clair, 2 = sombre
            var themeMode by remember { mutableIntStateOf(prefs(ctx).getInt("theme", 0)) }
            val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                App(themeMode) {
                    themeMode = (themeMode + 1) % 3
                    prefs(ctx).edit().putInt("theme", themeMode).apply()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(themeMode: Int, onToggleTheme: () -> Unit) {
    var current by rememberSaveable { mutableStateOf<Tool?>(null) }
    BackHandler(enabled = current != null) { current = null }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(current?.let { "${it.emoji} ${it.title}" } ?: "🧰 Toolbox") },
            navigationIcon = {
                if (current != null) IconButton(onClick = { current = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                }
            },
            actions = {
                if (current == null) TextButton(onClick = onToggleTheme) {
                    Text(when (themeMode) { 1 -> "☀️ Clair"; 2 -> "🌙 Sombre"; else -> "🌓 Auto" })
                }
            }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (current) {
                null -> Home { current = it }
                Tool.TORCH -> TorchScreen()
                Tool.QR -> QrScreen()
                Tool.LEVEL -> LevelCompassScreen()
                Tool.RULER -> RulerScreen()
                Tool.UNITS -> UnitsScreen()
                Tool.CALC -> CalcScreen()
                Tool.CURRENCY -> CurrencyScreen()
                Tool.DATES -> DatesScreen()
                Tool.CHRONO -> ChronoScreen()
                Tool.NOTES -> NotesScreen()
                Tool.COUNTER -> CounterScreen()
                Tool.RANDOM -> RandomScreen()
                Tool.PASSWORD -> PasswordScreen()
                Tool.SOUND -> SoundMeterScreen()
                Tool.DEVICE -> DeviceInfoScreen()
                Tool.MAGNIFIER -> CameraScreen(front = false)
                Tool.MIRROR -> CameraScreen(front = true)
                Tool.PEDOMETER -> PedometerScreen()
                Tool.BAROMETER -> BarometerScreen()
                Tool.BMI -> BmiScreen()
                Tool.LOAN -> LoanScreen()
                Tool.FUEL -> FuelScreen()
                Tool.BASES -> BasesScreen()
                Tool.REMINDERS -> RemindersScreen()
                Tool.EVENTS -> EventsScreen()
                Tool.HABITS -> HabitsScreen()
                Tool.BUDGET -> BudgetScreen()
                Tool.SHOPPING -> ShoppingScreen()
                Tool.WEATHER -> WeatherScreen()
                Tool.SPEEDTEST -> SpeedTestScreen()
                Tool.WORLDCLOCK -> WorldClockScreen()
                Tool.NETINFO -> NetInfoScreen()
                Tool.METRONOME -> MetronomeScreen()
                Tool.TUNER -> TunerScreen()
                Tool.MORSE -> MorseScreen()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Home(onOpen: (Tool) -> Unit) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var favs by remember { mutableStateOf(prefs(ctx).getStringSet("favs", emptySet())!!.toSet()) }
    fun toggleFav(t: Tool) {
        favs = if (t.name in favs) favs - t.name else favs + t.name
        prefs(ctx).edit().putStringSet("favs", favs).apply()
    }
    val q = query.trim().lowercase()
    val list = Tool.entries.filter { q.isEmpty() || it.title.lowercase().contains(q) }
        .sortedBy { if (it.name in favs) 0 else 1 }
    Column {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("🔎 Rechercher un outil…") }, singleLine = true)
        if (q.isEmpty() && favs.isEmpty()) Text("Astuce : appui long sur un outil pour l'épingler ⭐",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.name }) { t ->
                val fav = t.name in favs
                ElevatedCard(
                    Modifier.fillMaxWidth().height(110.dp).combinedClickable(onClick = { onOpen(t) }, onLongClick = { toggleFav(t) }),
                    colors = if (fav) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.elevatedCardColors()
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (fav) Text("⭐", Modifier.align(Alignment.TopEnd).padding(6.dp), fontSize = 12.sp)
                        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(t.emoji, fontSize = 32.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(t.title, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** Petit helper de mise en page commun. */
@Composable
fun ToolColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}
