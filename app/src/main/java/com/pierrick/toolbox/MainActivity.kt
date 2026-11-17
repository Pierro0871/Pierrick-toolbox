package com.pierrick.toolbox

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
            }
        }
    }
}

@Composable
fun Home(onOpen: (Tool) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(Tool.entries) { t ->
            ElevatedCard(Modifier.fillMaxWidth().height(120.dp).clickable { onOpen(t) }) {
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(t.emoji, fontSize = 36.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(t.title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
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
