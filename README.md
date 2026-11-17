# 🧰 Pierrick Toolbox

Application Android (Kotlin + Jetpack Compose, Material 3) regroupant 36 outils (dont un 🛡️ bloqueur de sites par VPN local) du quotidien, avec recherche et favoris (appui long pour épingler ⭐) :

| Pratique | Calculs | Organisation | Divers |
|---|---|---|---|
| 🔦 Lampe torche (fixe, strobo, SOS) | 🔁 Convertisseur d'unités (9 catégories) | ⏱️ Chrono / minuteur / Pomodoro | 🔐 Générateur de mots de passe |
| 📷 Scanner & générateur QR / codes-barres | 🧮 Calculatrice + pourboire / partage | 📝 Notes & listes de courses | 🔊 Sonomètre |
| 🧭 Niveau à bulle + boussole | 💱 Devises (taux BCE, cache hors ligne) | 🔢 Compteurs multiples | 📱 Infos batterie / stockage / réseau |
| 📏 Règle à l'écran (cm / pouces, calibrable) | 📅 Dates, âge, ajout de jours | 🎲 Pile/face, dés, nombre, choix | 🌓 Thème auto / clair / sombre (Material You) |

### v2 — 20 nouveautés
| Capteurs | Calculs | Organisation | Réseau | Musique & texte |
|---|---|---|---|---|
| 🔍 Loupe (zoom, lumière, figer) | ⚖️ IMC & calories | ⏰ Rappels avec notification | 🌤️ Météo 7 jours (Open-Meteo) | 🥁 Métronome + tap tempo |
| 🪞 Miroir | 🏦 Prêt, %, TVA | ⏳ Comptes à rebours | 🚀 Test de débit | 🎸 Accordeur |
| 👟 Podomètre | ⛽ Carburant & trajet | ✅ Suivi d'habitudes | 🌍 Fuseaux horaires | 📡 Morse (son/flash) + tableau défilant |
| ⛰️ Baromètre / altimètre | 💻 Bases & chiffres romains | 💶 Budget mensuel • 🛒 Courses par rayon | 📶 Mon IP / Wi-Fi | 🔎 Recherche & ⭐ favoris |

## Installer
- **GitHub Actions** : chaque push construit l'APK → onglet *Actions* → dernier run → artefact `toolbox-apk`.
- **Local** : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

Android 8.0+ requis. Autoriser « installation depuis des sources inconnues » pour installer l'APK.
