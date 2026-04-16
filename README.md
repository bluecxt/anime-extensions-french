<div align="center">

# 🇫🇷 bluecxt french repo 🇫🇷
### Repository regroupant des modules de parsing Java/Kotlin pour l'agrégation de métadonnées de médias francophones. Optimisé pour les environnements basés sur l'architecture Anizen.

</div>

---

## 📌 Configuration du Repository

Pour intégrer ce repository à votre environnement de test, utilisez l'URL de métadonnées brute suivante :

```text
https://raw.githubusercontent.com/bluecxt/aniyomi-extensions-french-only/repo/index.min.json
```

## 🛠️ Développement Local et Validation

Ce repository inclut des outils pour valider les scrapers de métadonnées.

### 🧪 Validation des Scrapers
Chaque module dispose d'un validateur qui vérifie si le flux de données distant est toujours conforme aux schémas attendus.

**Lancer tous les tests :**
```bash
python3 audit_extensions.py
```

### 🔨 Build des Artifacts
Pour compiler un module spécifique (Android Debug APK) :
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk # Si nécessaire
./gradlew :src:fr:exemple:assembleDebug
```

## ✅ État des Media Handlers (Avril 2026)

Le repository a été optimisé pour ne conserver que les scrapers les plus performants.

| Extension | État | Description | Langues | +18 |
| :--- | :--- | :--- | :--- | :--- |
| **Anime-Sama** | ✅ | Plus grosse source pour les sorties d'animes récents. | VOSTFR, VF, VA | Non |
| **Anime-Sama (FAN)** | ✅ | Version alternative rapide, utile en secours à Anime-Sama. | VOSTFR, VF | Non |
| **ADKami** | ✅ | Catalogue massif incluant une large section Hentai. | VOSTFR | **Oui** |
| **AnimoFlix** | ✅ | Seule source proposant également des cartoons. | VOSTFR, VF | Non |
| **Anime-Ultime** | ✅ | Haute qualité de Fantrad sur des titres peu diffusés. | VOSTFR | Non |
| **FrAnime** | ✅ | Plus grosse source de films et d'animes. | VOSTFR, VF | **Oui** |
| **French-Manga** | ✅ | Très complet avec flux haute qualité. | VOSTFR, VF | Non |
| **VoirAnime.homes** | ✅ | Miroir de French-Manga. | VOSTFR, VF | Non |
| **French Anime** | ✅ | Source de secours (backup). | VOSTFR, VF | Non |
| **Les Poroïniens** | ✅ | Scrapers très précis et contenu haute qualité. | VOSTFR | **Oui** |
| **VoirAnime** | ✅ | Uniquement VOSTFR avec une haute qualité d'image. | VOSTFR | Non |
| **SouthTV** | ✅ | L'intégrale de South Park : épisodes VF et tous les films. | VF | Non |
| **WaveAnime** | ✅ | Flux moderne optimisé au format DASH (Haute résolution). | VOSTFR, VF | Non |
| **AnimesUltra** | ✅ | Alternative de secours de haute qualité. | VOSTFR | Non |

---
