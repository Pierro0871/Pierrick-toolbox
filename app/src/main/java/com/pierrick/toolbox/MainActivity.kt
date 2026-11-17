package com.pierrick.toolbox

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

enum class Cat(val label: String) {
    PRATIQUE("🔧 Pratique"), FOCUS("🛡️ Focus & sécurité"), IMAGE("📷 Photo & caméra"), AUDIO("🔊 Audio & musique"),
    CALC("🧮 Calculs"), ARGENT("💶 Argent & travail"), ORGA("📋 Organisation"), MAISON("🏠 Maison & cuisine"),
    SANTE("💪 Santé & sport"), VOYAGE("🌍 Voyage & réseau"), PHONE("📱 Téléphone"), FUN("🎲 Fun & jeux")
}

enum class Tool(val emoji: String, val title: String, val cat: Cat) {
    // Pratique
    TORCH("🔦", "Lampe torche", Cat.PRATIQUE), QR("📷", "QR / Codes-barres", Cat.PRATIQUE), LEVEL("🧭", "Niveau & boussole", Cat.PRATIQUE),
    RULER("📏", "Règle", Cat.PRATIQUE), MAGNIFIER("🔍", "Loupe", Cat.PRATIQUE), MIRROR("🪞", "Miroir", Cat.PRATIQUE),
    BAROMETER("⛰️", "Baromètre / altimètre", Cat.PRATIQUE), CHRONO("⏱️", "Chrono & minuteur", Cat.PRATIQUE),
    MORSE("📡", "Morse & tableau", Cat.PRATIQUE), PASSWORD("🔐", "Mot de passe", Cat.PRATIQUE),
    // Focus & sécurité
    BLOCKER("🛡️", "Bloqueur de sites", Cat.FOCUS), SCREENTIME("📊", "Temps d'écran & limites", Cat.FOCUS),
    VAULT("🗝️", "Coffre mots de passe", Cat.FOCUS), LINKCHECK("🔗", "Analyse de lien", Cat.FOCUS), SPYCAM("🕵️", "Détecteur caméra cachée", Cat.FOCUS),
    // Image
    OCR("🔤", "Texte depuis photo", Cat.IMAGE), PHOTOTRANSLATE("🈯", "Traduire une photo", Cat.IMAGE), COLORPICK("🎨", "Pipette couleur", Cat.IMAGE),
    COMPRESS("🗜️", "Compresser photos", Cat.IMAGE), EXIF("🧹", "Supprimer EXIF / GPS", Cat.IMAGE), GIF("🎞️", "Créer un GIF", Cat.IMAGE),
    CAMMEASURE("📐", "Mesure par caméra", Cat.IMAGE), OBJCOUNT("🔢", "Compter sur une photo", Cat.IMAGE),
    // Audio
    SOUND("🔊", "Sonomètre", Cat.AUDIO), RECORDER("🎙️", "Dictaphone", Cat.AUDIO), DICTATION("🗣️", "Dictée vocale", Cat.AUDIO),
    TTS("📢", "Lecture à voix haute", Cat.AUDIO), FREQGEN("〰️", "Générateur de fréquence", Cat.AUDIO), SPEAKERTEST("🔈", "Test HP & éjecter l'eau", Cat.AUDIO),
    SPECTRUM("📶", "Spectre audio", Cat.AUDIO), FAKECALL("📞", "Faux appel", Cat.AUDIO), METRONOME("🥁", "Métronome", Cat.AUDIO), TUNER("🎸", "Accordeur", Cat.AUDIO),
    // Calculs
    CALC("🧮", "Calculatrice & pourboire", Cat.CALC), SCICALC("🔬", "Calculatrice scientifique", Cat.CALC), EQUATIONS("✖️", "Équations", Cat.CALC),
    FRACTIONS("➗", "Fractions", Cat.CALC), RULE3("📐", "Règle de trois", Cat.CALC), AVERAGE("🎓", "Moyenne pondérée", Cat.CALC),
    UNITS("🔁", "Unités", Cat.CALC), BASES("💻", "Bases & chiffres romains", Cat.CALC), DATES("📅", "Dates & âge", Cat.CALC),
    WORKDAYS("🗓️", "Jours ouvrés & fériés", Cat.CALC), OHM("⚡", "Loi d'Ohm & résistances", Cat.CALC), PACE("🏃", "Allure de course", Cat.CALC),
    // Argent & travail
    CURRENCY("💱", "Devises", Cat.ARGENT), CRYPTO("🪙", "Crypto-monnaies", Cat.ARGENT), LOAN("🏦", "Prêt & pourcentages", Cat.ARGENT),
    SALARY("💼", "Salaire brut ↔ net", Cat.ARGENT), SAVINGS("📈", "Épargne & intérêts", Cat.ARGENT), INFLATION("🎈", "Inflation", Cat.ARGENT),
    WORKHOURS("⏲️", "Heures travaillées", Cat.ARGENT), BUDGET("💶", "Budget", Cat.ARGENT), SUBSCRIPTIONS("🔄", "Abonnements", Cat.ARGENT),
    // Organisation
    NOTES("📝", "Notes & listes", Cat.ORGA), REMINDERS("⏰", "Rappels", Cat.ORGA), AGENDA("📆", "Agenda", Cat.ORGA), KANBAN("🗂️", "Kanban", Cat.ORGA),
    EVENTS("⏳", "Compte à rebours", Cat.ORGA), HABITS("✅", "Habitudes", Cat.ORGA), GOALS("🎯", "Objectifs", Cat.ORGA), JOURNAL("📔", "Journal & humeur", Cat.ORGA),
    LOYALTY("💳", "Cartes de fidélité", Cat.ORGA), COUNTER("🔢", "Compteur", Cat.ORGA),
    // Maison & cuisine
    SHOPPING("🛒", "Liste de courses", Cat.MAISON), RECIPES("🍲", "Recettes", Cat.MAISON), MENUS("🍽️", "Menus de la semaine", Cat.MAISON),
    COOKCONV("🥄", "Conversions cuisine", Cat.MAISON), PAINT("🖌️", "Peinture & carrelage", Cat.MAISON), INVENTORY("📦", "Inventaire maison", Cat.MAISON),
    WARRANTY("🧾", "Garanties & factures", Cat.MAISON), LOANS("🤝", "Objets prêtés", Cat.MAISON), CAR("🚗", "Entretien voiture", Cat.MAISON),
    SIZES("👕", "Tailles vêtements", Cat.MAISON),
    // Santé
    PEDOMETER("👟", "Podomètre", Cat.SANTE), BMI("⚖️", "IMC & calories", Cat.SANTE), WEIGHT("📉", "Suivi du poids", Cat.SANTE),
    HIIT("🔥", "HIIT / Tabata", Cat.SANTE), BREATH("🌬️", "Respiration", Cat.SANTE), MEDITATION("🧘", "Méditation", Cat.SANTE),
    PULSE("❤️", "Pouls (caméra)", Cat.SANTE), SLEEP("😴", "Suivi du sommeil", Cat.SANTE), REFLEX("⚡", "Test de réflexes", Cat.SANTE),
    // Voyage & réseau
    WEATHER("🌤️", "Météo", Cat.VOYAGE), GPS("📍", "GPS & position", Cat.VOYAGE), SPEEDO("🏎️", "Compteur de vitesse", Cat.VOYAGE),
    PHRASES("🗺️", "Traducteur", Cat.VOYAGE), SUNMOON("🌗", "Soleil & lune", Cat.VOYAGE), DISTANCE("📏", "Distance entre villes", Cat.VOYAGE),
    COUNTRIES("🏳️", "Infos pays", Cat.VOYAGE), FUELPRICE("⛽", "Prix des carburants", Cat.VOYAGE), FUEL("🚙", "Carburant & trajet", Cat.VOYAGE),
    PARKING("🅿️", "Où je suis garé", Cat.VOYAGE), TRAVELLIST("🧳", "Check-list voyage", Cat.VOYAGE), WORLDCLOCK("🌍", "Fuseaux horaires", Cat.VOYAGE),
    SPEEDTEST("🚀", "Test de débit", Cat.VOYAGE), NETINFO("📶", "Mon IP / Wi-Fi", Cat.VOYAGE),
    // Téléphone
    DEVICE("📱", "Infos appareil", Cat.PHONE), FILES("📁", "Fichiers", Cat.PHONE), CLEANER("🧽", "Nettoyeur", Cat.PHONE),
    APPS("🔐", "Applis & permissions", Cat.PHONE), SCREENTEST("🖥️", "Test de l'écran", Cat.PHONE), SENSORS("🛰️", "Test des capteurs", Cat.PHONE),
    BACKUP("💾", "Sauvegarde Toolbox", Cat.PHONE),
    // Fun
    RANDOM("🎲", "Aléatoire", Cat.FUN), WHEEL("🎡", "Roue de la fortune", Cat.FUN), TEAMS("👥", "Tirage d'équipes", Cat.FUN),
    CHESSCLOCK("♟️", "Pendule d'échecs", Cat.FUN), NAMEGEN("🏷️", "Générateur de pseudos", Cat.FUN), QUIZ("❓", "Quiz", Cat.FUN),
    MEMORY("🃏", "Jeu de mémoire", Cat.FUN), QUOTE("💬", "Citation du jour", Cat.FUN),
}

fun prefs(ctx: Context) = ctx.getSharedPreferences("toolbox", Context.MODE_PRIVATE)

class MainActivity : FragmentActivity() {
    private val openTool = mutableStateOf<Tool?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val ctx = LocalContext.current
            var themeMode by remember { mutableIntStateOf(prefs(ctx).getInt("theme", 0)) }
            val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                App(openTool, themeMode) {
                    themeMode = (themeMode + 1) % 3
                    prefs(ctx).edit().putInt("theme", themeMode).apply()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: android.content.Intent?) {
        i?.getStringExtra("tool")?.let { n -> Tool.entries.find { it.name == n }?.let { openTool.value = it } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(state: MutableState<Tool?>, themeMode: Int, onToggleTheme: () -> Unit) {
    var current by state
    BackHandler(enabled = current != null) { current = null }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(current?.let { "${it.emoji} ${it.title}" } ?: "🧰 Toolbox", maxLines = 1) },
            navigationIcon = {
                if (current != null) IconButton(onClick = { current = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            },
            actions = {
                if (current == null) TextButton(onClick = onToggleTheme) {
                    Text(when (themeMode) { 1 -> "☀️ Clair"; 2 -> "🌙 Sombre"; else -> "🌓 Auto" })
                }
            }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val t = current
            if (t == null) Home { current = it } else ToolContent(t)
        }
    }
}

@Composable
fun ToolContent(t: Tool) = when (t) {
    Tool.TORCH -> TorchScreen(); Tool.QR -> QrScreen(); Tool.LEVEL -> LevelCompassScreen(); Tool.RULER -> RulerScreen()
    Tool.MAGNIFIER -> CameraScreen(front = false); Tool.MIRROR -> CameraScreen(front = true); Tool.BAROMETER -> BarometerScreen()
    Tool.CHRONO -> ChronoScreen(); Tool.MORSE -> MorseScreen(); Tool.PASSWORD -> PasswordScreen()
    Tool.BLOCKER -> BlockerScreen(); Tool.SCREENTIME -> ScreenTimeScreen(); Tool.VAULT -> VaultScreen()
    Tool.LINKCHECK -> LinkCheckScreen(); Tool.SPYCAM -> SpyCamScreen()
    Tool.OCR -> OcrScreen(translate = false); Tool.PHOTOTRANSLATE -> OcrScreen(translate = true); Tool.COLORPICK -> ColorPickerScreen()
    Tool.COMPRESS -> CompressScreen(); Tool.EXIF -> ExifScreen(); Tool.GIF -> GifScreen(); Tool.CAMMEASURE -> CamMeasureScreen()
    Tool.OBJCOUNT -> ObjectCountScreen()
    Tool.SOUND -> SoundMeterScreen(); Tool.RECORDER -> RecorderScreen(); Tool.DICTATION -> DictationScreen(); Tool.TTS -> TtsScreen()
    Tool.FREQGEN -> FreqGenScreen(); Tool.SPEAKERTEST -> SpeakerTestScreen(); Tool.SPECTRUM -> SpectrumScreen(); Tool.FAKECALL -> FakeCallScreen()
    Tool.METRONOME -> MetronomeScreen(); Tool.TUNER -> TunerScreen()
    Tool.CALC -> CalcScreen(); Tool.SCICALC -> SciCalcScreen(); Tool.EQUATIONS -> EquationsScreen(); Tool.FRACTIONS -> FractionsScreen()
    Tool.RULE3 -> Rule3Screen(); Tool.AVERAGE -> AverageScreen(); Tool.UNITS -> UnitsScreen(); Tool.BASES -> BasesScreen()
    Tool.DATES -> DatesScreen(); Tool.WORKDAYS -> WorkdaysScreen(); Tool.OHM -> OhmScreen(); Tool.PACE -> PaceScreen()
    Tool.CURRENCY -> CurrencyScreen(); Tool.CRYPTO -> CryptoScreen(); Tool.LOAN -> LoanScreen(); Tool.SALARY -> SalaryScreen()
    Tool.SAVINGS -> SavingsScreen(); Tool.INFLATION -> InflationScreen(); Tool.WORKHOURS -> WorkHoursScreen(); Tool.BUDGET -> BudgetScreen()
    Tool.SUBSCRIPTIONS -> SubscriptionsScreen()
    Tool.NOTES -> NotesScreen(); Tool.REMINDERS -> RemindersScreen(); Tool.AGENDA -> AgendaScreen(); Tool.KANBAN -> KanbanScreen()
    Tool.EVENTS -> EventsScreen(); Tool.HABITS -> HabitsScreen(); Tool.GOALS -> GoalsScreen(); Tool.JOURNAL -> JournalScreen()
    Tool.LOYALTY -> LoyaltyScreen(); Tool.COUNTER -> CounterScreen()
    Tool.SHOPPING -> ShoppingScreen(); Tool.RECIPES -> RecipesScreen(); Tool.MENUS -> MenusScreen(); Tool.COOKCONV -> CookConvScreen()
    Tool.PAINT -> PaintScreen(); Tool.INVENTORY -> InventoryScreen(); Tool.WARRANTY -> WarrantyScreen(); Tool.LOANS -> LentScreen()
    Tool.CAR -> CarScreen(); Tool.SIZES -> SizesScreen()
    Tool.PEDOMETER -> PedometerScreen(); Tool.BMI -> BmiScreen(); Tool.WEIGHT -> WeightScreen(); Tool.HIIT -> HiitScreen()
    Tool.BREATH -> BreathScreen(); Tool.MEDITATION -> MeditationScreen(); Tool.PULSE -> PulseScreen(); Tool.SLEEP -> SleepScreen()
    Tool.REFLEX -> ReflexScreen()
    Tool.WEATHER -> WeatherScreen(); Tool.GPS -> GpsScreen(speedo = false); Tool.SPEEDO -> GpsScreen(speedo = true); Tool.PHRASES -> PhrasesScreen()
    Tool.SUNMOON -> SunMoonScreen(); Tool.DISTANCE -> DistanceScreen(); Tool.COUNTRIES -> CountriesScreen(); Tool.FUELPRICE -> FuelPriceScreen()
    Tool.FUEL -> FuelScreen(); Tool.PARKING -> ParkingScreen(); Tool.TRAVELLIST -> TravelListScreen(); Tool.WORLDCLOCK -> WorldClockScreen()
    Tool.SPEEDTEST -> SpeedTestScreen(); Tool.NETINFO -> NetInfoScreen()
    Tool.DEVICE -> DeviceInfoScreen(); Tool.FILES -> FilesScreen(); Tool.CLEANER -> CleanerScreen(); Tool.APPS -> AppsScreen()
    Tool.SCREENTEST -> ScreenTestScreen(); Tool.SENSORS -> SensorsScreen(); Tool.BACKUP -> BackupScreen()
    Tool.RANDOM -> RandomScreen(); Tool.WHEEL -> WheelScreen(); Tool.TEAMS -> TeamsScreen(); Tool.CHESSCLOCK -> ChessClockScreen()
    Tool.NAMEGEN -> NameGenScreen(); Tool.QUIZ -> QuizScreen(); Tool.MEMORY -> MemoryScreen(); Tool.QUOTE -> QuoteScreen()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Home(onOpen: (Tool) -> Unit) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<Cat?>(null) }
    var favs by remember { mutableStateOf(prefs(ctx).getStringSet("favs", emptySet())!!.toSet()) }
    fun toggleFav(t: Tool) {
        favs = if (t.name in favs) favs - t.name else favs + t.name
        prefs(ctx).edit().putStringSet("favs", favs).apply()
    }
    val q = query.trim().lowercase()
    val filtered = Tool.entries.filter { (q.isEmpty() || it.title.lowercase().contains(q)) && (cat == null || it.cat == cat) }
    Column {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("🔎 Rechercher parmi ${Tool.entries.size} outils…") }, singleLine = true)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(cat == null, { cat = null }, label = { Text("Tout") }) }
            items(Cat.entries) { c -> FilterChip(cat == c, { cat = if (cat == c) null else c }, label = { Text(c.label) }) }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val favList = filtered.filter { it.name in favs }
            val sections: List<Pair<String, List<Tool>>> =
                (if (favList.isNotEmpty()) listOf("⭐ Favoris" to favList) else emptyList()) +
                    if (q.isNotEmpty() || cat != null) listOf((cat?.label ?: "Résultats") to filtered.filter { it.name !in favs })
                    else Cat.entries.map { c -> c.label to filtered.filter { it.cat == c && it.name !in favs } }
            if (favs.isEmpty() && q.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Astuce : appui long sur un outil pour l'épingler ⭐", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            sections.filter { it.second.isNotEmpty() }.forEach { (title, tools) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(tools, key = { title + it.name }) { t ->
                    val fav = t.name in favs
                    ElevatedCard(
                        Modifier.fillMaxWidth().height(100.dp).combinedClickable(onClick = { onOpen(t) }, onLongClick = { toggleFav(t) }),
                        colors = if (fav) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.elevatedCardColors()
                    ) {
                        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(t.emoji, fontSize = 28.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(t.title, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, maxLines = 2)
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
