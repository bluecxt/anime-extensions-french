# 📜 Guide de Contribution & Normes Techniques

Bienvenue sur le dépôt **anime-extensions-french** ! Ce document définit l'architecture, les conventions de code et les règles obligatoires pour le développement et la maintenance des modules d'extensions (compatibles AniZen et Tachiyomi).

---

## 1. 🗄️ Intégrité de la Base de Données (CRITIQUE AniZen)

Dans AniZen et Tachiyomi, les chaînes `anime.url` et `episode.url` constituent les **clés primaires absolues** en base SQLite pour lier les favoris, les catégories et l'historique de visionnage.

- **Immutabilité stricte :** Il est strictement **INTERDIT** de modifier la structure d'URL d'un anime ou d'un épisode existant sans prévoir de mécanisme de migration ou de fallback rétro-compatible.
- **Rétro-compatibilité & Fallback :**
  - Si une extension adopte un format d'URL structuré (JSON ou slug) alors qu'elle utilisait auparavant des URLs brutes, elle **DOIT** implémenter un fallback transparent (ex. `LegacyAnimeSama`) pour continuer à parser les anciens animes déjà présents dans la bibliothèque des utilisateurs.
- **Bouton WebView (`getAnimeUrl`) :**
  - Si `anime.url` contient des données sérialisées ou un identifiant interne (ex: `{"ids":["123"]}`), vous **DEVEZ** obligatoirement surcharger `getAnimeUrl(anime: SAnime): String` afin qu'il renvoie une URL HTTP/HTTPS directe et valide vers la page web du site.

---

## 2. 🎬 Formatage des Épisodes & Films

AniZen gère nativement la hiérarchie et les saisons. Le formatage des titres d'épisodes doit respecter ces règles :

- **Épisodes de séries standards :**
  - Pas de préfixe `[SX]` artificiel. Utiliser `Épisode X - [Titre]` ou `Épisode X`.
  - Pas de redondance si le titre source contient déjà le numéro d'épisode.
- **Films :**
  - **Cas standard (Fiche SAnime dédiée à un film)** : Nommer simplement `Film` ou le nom propre du film sans crochets forcé.
  - **Cas particulier (Plusieurs films regroupés sous un même SAnime)** : Préfixer explicitement avec `[Film] Nom du film` (ex: `[Film] Mugen Train`) pour permettre à l'utilisateur de les distinguer clairement dans la liste des épisodes.
- **Contenus Spéciaux & OAV :**
  - Préfixer avec `[OAV]`, `[Special]` ou `[ONA]` uniquement lorsqu'ils sont mélangés dans la liste d'épisodes d'une série.

---

## 3. 🌐 Libellés des Vidéos & Super-Packs

- **Conventions de nommage des vidéos :**
  - Format : `(LANGUE) Serveur - Qualité` ou `(LANGUE) Team Serveur - Qualité`.
  - Tags de langue autorisés : `(VOSTFR)`, `(VF)`, `(VA)`, `(VO)`.
  - Exemple : `(VF) Voe - 1080p` ou `(VOSTFR) Filemoon - 720p`.
- **Super-Packs par Langue :**
  - Les hébergeurs doivent être regroupés par langue (`VF`, `VOSTFR`) dans le sélecteur principal.
  - L'extraction de tous les serveurs au sein d'un groupe doit s'effectuer en parallèle pour un chargement instantané.
- **Tri déterministe :**
  - Priorité à la langue configurée dans les paramètres de l'extension.
  - Ensuite, tri par résolution décroissante (1080p > 720p > 480p) via extraction numérique (`(\d+)p`).

---

## 4. ⚡ Performance & Gestion des Ressources

- **Requêtes Réseau Parallèles (`coroutineScope + async`) :**
  Lorsqu'une méthode exécute au moins deux requêtes réseau indépendantes (ex: télécharger la page HTML ET interroger l'API TMDB/TVDB), vous **DEVEZ** les exécuter en parallèle :
  ```kotlin
  coroutineScope {
      val documentDeferred = async { client.get(pageUrl, headers).useAsJsoup() }
      val tvdbMetadataDeferred = async { fetchTvdbMetadata(cleanTitle, seasonNum) }

      val document = documentDeferred.await()
      val tvdbMetadata = tvdbMetadataDeferred.await()
  }
  ```
- **Fermeture stricte des flux :**
  - Toujours consommer les réponses OkHttp avec `.useAsJsoup()`, `.parseAs<T>()` ou `.use { ... }`.
  - Ne jamais laisser de `Response` ou `ResponseBody` ouvert.
- **Regex & Mémoire :**
  - Toutes les `Regex` doivent être précompilées au niveau top-level (en dehors des classes ou en `companion object`). Zéro instanciation de `Regex(...)` dans les boucles de scraping.
  - Interdiction d'utiliser des `MutableMap` globales non bornées. Préférer `ConcurrentHashMap` ou un cache LRU avec éviction.

---

## 5. 🛰️ Télémétrie & Observabilité Hub

Le module `core` intègre un système d'alerte automatique connecté à un webhook de monitoring.
- **Fail-Silent absolu :** Aucun échec de monitoring ou coupure réseau ne doit jamais faire crasher l'extension ou bloquer l'utilisateur.
- **`ErrorInterceptor` :** Intercepte automatiquement les codes HTTP non-2xx (hors 404, 3xx, 502-504) et notifie le hub.
- **`isDebug` Guard :** En mode debug local (`isDebug == true`), les webhooks distants sont silencés pour ne pas polluer les canaux de production.

---

## 6. 🛠️ Compilation & Validation Locale

Avant de soumettre une modification ou une Pull Request :

```bash
# Compiler une extension spécifique en Debug
./gradlew :src:<lang>:<nom-extension>:assembleDebug

# Vérifier le formatage du code
./gradlew :src:<lang>:<nom-extension>:spotlessCheck

# Vérifier l'analyse statique et la qualité du code (Detekt)
./gradlew :src:<lang>:<nom-extension>:detekt
```

Toute modification doit compiler sans avertissement bloquant (`BUILD SUCCESSFUL`) et passer avec succès les vérifications `spotlessCheck` et `detekt` sans warning.
