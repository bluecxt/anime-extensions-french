<div align="center">

# 🇫🇷 bluecxt french repo 🇫🇷
### Repository regroupant des modules de parsing Java/Kotlin pour l'agrégation de métadonnées de médias francophones. Optimisé pour les environnements basés sur l'architecture [AniZen](https://github.com/salmanbappi/AniZen).

<br>

<a href="https://intradeus.github.io/http-protocol-redirector/?r=anizen://add-repo?url=https://bluecxt.github.io/anime-extensions-french/stable/index.min.json">
  <img src="https://img.shields.io/badge/AniZen-Ajouter-FFFFFF?labelColor=00209F&style=for-the-badge&logo=android&logoColor=white" alt="Ajouter le Repo à AniZen"><img src="https://img.shields.io/badge/%20-Repo-E70013?labelColor=FFFFFF&style=for-the-badge" alt="Ajouter le Repo à AniZen">
</a>

</div>

---

## 📌 Configuration du Repository

Pour intégrer ce repository à votre application (comme AniZen), ajoutez l'URL correspondant à la version souhaitée :

> [!WARNING]
> Les URLs du repository ont récemment changé (migration vers GitHub Pages). Pensez à mettre à jour l'adresse dans votre application pour continuer à recevoir les dernières mises à jour des extensions.


*   **Stable (Production) :**
    ```text
    https://bluecxt.github.io/anime-extensions-french/stable/index.min.json
    ```
*   **Alpha :**
    ```text
    https://bluecxt.github.io/anime-extensions-french/alpha/index.min.json
    ```

## ⚠️ Notes Importantes (DNS)

Si vous rencontrez des problèmes de chargement des lecteurs, il est fortement recommandé d'utiliser les DNS de Cloudflare :
*   **Sur Android :** Suivez ce guide [Configuration 1.1.1.1](https://developers.cloudflare.com/1.1.1.1/setup/android/)
*   **Dans AniZen :** Allez dans `Paramètres` > `Avancé` > `DNS over HTTPS` et activez-le.

## 🛠️ Développement Local et Validation

Ce repository inclut des outils pour valider les extensions.

### 🔨 Build des Artifacts
Pour compiler un module spécifique (Android Debug APK) :
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk # Si nécessaire
./gradlew :src:fr:exemple:assembleDebug
```

## ✅ État des Extensions (Mai 2026)

Le repository a été optimisé pour ne conserver que les extensions les plus performantes.

| Extension | État | Description | Langues        | +18     |
| :--- | :--- | :--- |:---------------|:--------|
| **Anime-Sama** | ✅ | Plus grosse source pour les sorties d'animes récents. | VOSTFR, VF, VA | Non     |
| **Anime-Sama (FAN)** | ✅ | Version alternative rapide, utile en secours à Anime-Sama. | VOSTFR, VF     | Non     |
| **ADKami** | ✅ | Catalogue massif incluant une large section Hentai. | VOSTFR, VOSTA, RAW | **Oui** |
| **AnimoFlix** | ✅ | Source variée proposant également des cartoons. | VOSTFR, VF     | Non     |
| **Anime-Ultime** | ✅ | Haute qualité de Fantrad sur des titres peu diffusés. | VOSTFR, RAW    | **Oui** |
| **FrAnime** | ✅ | Plus grosse source de films et d'animes. | VOSTFR, VF     | **Oui** |
| **French-Manga** | ✅ | Très complet avec flux haute qualité. | VOSTFR, VF     | Non     |
| **VoirAnime.homes** | ✅ | Miroir de French-Manga. | VOSTFR, VF     | Non     |
| **French Anime** | ✅ | Source de secours (backup). | VOSTFR, VF     | Non     |
| **Les Poroïniens** | ✅ | Scrapers très précis et contenu haute qualité. | VOSTFR         | **Oui** |
| **VoirAnime** | ✅ | Uniquement VOSTFR avec une haute qualité d'image. | VOSTFR         | Non     |
| **SouthTV** | ✅ | L'intégrale de South Park : épisodes VF et tous les films. | VF             | Non     |
| **WaveAnime** | ✅ | Flux moderne optimisé au format DASH (Haute résolution). | VOSTFR, VF     | Non     |
| **AnimesUltra** | ✅ | Alternative de secours de haute qualité. | VOSTFR         | Non     |
| **Dessin Anime** | ✅ | Dessins animés et séries jeunesse. | VF | Non |
| **Movix** | ✅ | Films et séries via l'API TMDB. | VOSTFR, VF | Non |
| **Movix Anime** | ✅ | Animes via l'API TMDB. | VOSTFR, VF | Non |
| **Wiflix** | ✅ | Grand catalogue de films et séries. | VOSTFR, VF | Non |
| **Torrentio** | ✅ | Moteur de recherche Torrent / Debrid (Cinemeta/JustWatch). | Multi (All)    | Non     |
| **Torrentio Anime** | ✅ | Version optimisée Anime pour Torrentio (Anilist). | Multi (All)    | Non     |

---

## 🔍 Télémétrie & Rapports d'erreurs

Certaines extensions intègrent un mécanisme de rapport d'erreur automatique (`ErrorWebhook`) actif dans les builds officiels signés par le mainteneur du repo.

**Ce qui est transmis en cas d'erreur de parsing :**
- Le domaine de la source (`baseUrl`) et l'URL de la page ayant échoué
- Le message d'erreur et le nom de la méthode concernée
- La version de l'extension

**Ce qui n'est pas transmis :**
- Aucune donnée personnelle (compte, identifiants, historique de navigation)
- Aucune information sur l'appareil ou l'utilisateur

**Conditions d'envoi :**
- La clé webhook (`WEBHOOK_URL`) n'est **pas incluse** dans les builds compilés depuis les sources publiques — elle est injectée uniquement lors des builds CI officiels via une variable d'environnement secrète. Tout build réalisé sans cette clé ne transmet rien.
- Un même événement ne peut être envoyé qu'une fois toutes les 24h (déduplication par hash).
- Les envois sont réalisés de manière asynchrone et n'affectent pas le fonctionnement de l'extension.

Cette fonctionnalité a pour seul but de détecter rapidement les cassures de parsing (changement de structure HTML d'un site source).

---

## 📊 Statistiques d'usage anonymes

Les extensions envoient une fois par jour un ping anonyme afin de connaître les sources les plus utilisées et d'orienter les priorités de maintenance.

**Ce qui est transmis :**
- Le nom et la version de l'extension utilisée
- Un identifiant anonyme à usage unique (ANDROID_ID haché en SHA-256, tronqué à 16 caractères — non réversible, non lié à un compte)

**Ce qui n'est pas transmis :**
- Aucune donnée de navigation (titres regardés, URLs, historique)
- Aucune information personnelle ou d'appareil identifiable

Un même appareil ne peut envoyer qu'un ping par extension par jour. Ces données sont utilisées uniquement à des fins statistiques internes (popularité des sources) et ne sont ni revendues ni partagées.

---

## 💬 Support

Besoin d'aide ou envie de discuter du projet ? Rejoigne le discord !

[![Discord](https://img.shields.io/badge/Discord-%237289DA.svg?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/Bx2PhgndEC)
