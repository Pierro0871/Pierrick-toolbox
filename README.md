# 🧰 Pierrick Toolbox

Application Android (Kotlin + Jetpack Compose, Material 3) regroupant 15 outils du quotidien :

| Pratique | Calculs | Organisation | Divers |
|---|---|---|---|
| 🔦 Lampe torche (fixe, strobo, SOS) | 🔁 Convertisseur d'unités (9 catégories) | ⏱️ Chrono / minuteur / Pomodoro | 🔐 Générateur de mots de passe |
| 📷 Scanner & générateur QR / codes-barres | 🧮 Calculatrice + pourboire / partage | 📝 Notes & listes de courses | 🔊 Sonomètre |
| 🧭 Niveau à bulle + boussole | 💱 Devises (taux BCE, cache hors ligne) | 🔢 Compteurs multiples | 📱 Infos batterie / stockage / réseau |
| 📏 Règle à l'écran (cm / pouces, calibrable) | 📅 Dates, âge, ajout de jours | 🎲 Pile/face, dés, nombre, choix | 🌓 Thème auto / clair / sombre (Material You) |

## Installer
- **GitHub Actions** : chaque push construit l'APK → onglet *Actions* → dernier run → artefact `toolbox-apk`.
- **Local** : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

Android 8.0+ requis. Autoriser « installation depuis des sources inconnues » pour installer l'APK.
