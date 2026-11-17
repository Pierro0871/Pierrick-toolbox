# 🧰 Pierrick Toolbox

Application Android (Kotlin + Jetpack Compose, Material 3) regroupant **117 outils** classés en 12 catégories (dont un 🛡️ bloqueur de sites par VPN local avec mode focus, planning et code PIN) du quotidien, avec recherche et favoris (appui long pour épingler ⭐) :

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
- **Local** : `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

Android 8.0+ requis. Autoriser « installation depuis des sources inconnues » pour installer l'APK.

### v3 — 80 nouveautés
- **Focus & sécurité** : mode focus minuté, planning de blocage, code PIN du bloqueur, temps d'écran et limites par appli, coffre à mots de passe chiffré (empreinte), analyse de liens suspects, détecteur de caméra cachée
- **Photo** : texte depuis photo (OCR), traduction de photo, pipette couleur, compression, suppression EXIF/GPS, création de GIF, mesure par caméra, comptage sur photo
- **Audio** : dictaphone, dictée vocale, lecture à voix haute, générateur de fréquence, test HP + éjection d'eau, spectre, faux appel
- **Calculs & argent** : scientifique, équations, fractions, règle de trois, moyenne, jours ouvrés/fériés, Ohm & résistances, allure de course, crypto, salaire brut/net, épargne, inflation, heures travaillées, abonnements
- **Organisation & maison** : agenda, kanban, objectifs, journal, cartes de fidélité, recettes, menus, conversions cuisine, peinture/carrelage, inventaire, garanties, objets prêtés, entretien voiture, tailles
- **Santé** : poids, HIIT, respiration, méditation, pouls par caméra, sommeil, réflexes
- **Voyage** : GPS, compteur de vitesse, traducteur, soleil & lune, distances, infos pays, prix des carburants, parking, check-list valise
- **Téléphone** : fichiers, nettoyeur (gros fichiers, doublons), applis & permissions, test écran, capteurs, sauvegarde/restauration
- **Fun** : roue de la fortune, équipes, pendule d'échecs, pseudos, quiz, mémoire, citation du jour
- **Android** : widget écran d'accueil + tuiles réglages rapides (lampe, bloqueur)
