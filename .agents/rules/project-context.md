---
trigger: always_on
---

# 🚀 Contexte Global du Projet (anime-extensions-french)

## 📌 1. Structure du Dépôt & Git Worktrees
* **Gestion en Git Worktrees :** Ce dépôt utilise une configuration bare Git avec des worktrees séparés (ex: `dev`, `main`, branches de features). Assurez-vous d'opérer dans le contexte du worktree actif approprié.
* **Architecture Multi-Module Gradle / Android :**
  - `core/` : Module central partagé (logiques communes, utilitaires JSON/Coroutines, clients TMDB/TVDB, extracteurs migrés).
  - `src/fr/<extension>/` : Extensions individuelles (Aniyomi / AniZen) en Kotlin (ex: `animesama`, `adkami`, `voiranime`, etc.).
  - `lib/` : **Dossier de référence temporaire**. Les extracteurs historiques s'y trouvent pour consultation et référence pendant leur migration progressive vers le module `core/`.
  - `docs/` : Documentation du projet.

---

## 📜 2. Règles Obligatoires du Répertoire (`docs/REPO_RULES.md`)
**Consultez et appliquez rigoureusement les normes définies dans `docs/REPO_RULES.md`** :

1. **Formatage des Épisodes :**
   - Mots-clés entre crochets pour le regroupement AniZen : `[S1]`, `[S2]`, `[Movie]`, `[OAV]`, `[ONA]`, `[Special]`.
   - Pas de `[S1]` pour la première/unique saison.
   - Structure : `[S2] Épisode Y - [Titre]` ou `Épisode Y - [Titre]`. Ne pas répéter le préfixe si déjà présent.
2. **Libellés des Vidéos & Super Packs :**
   - Format : `(Langue) Serveur - Qualité` avec tags `(VOSTFR)`, `(VF)`, `(VA)`.
   - Regroupement des hébergeurs par langue en Super Packs (`VOSTFR`, `VF`, `VA`).
3. **Tri & Priorisation :**
   - Tri par langue préférée puis par résolution décroissante via Regex (`(\d+)p`).
4. **Métadonnées TMDB :**
   - Format date : `Date de sortie :` au début de la description.
   - `Artist` = Studios, `Author` = Mangakas / Auteurs.
   - Mappage des épisodes de débordement de saison vers la Saison 0 TMDB.
5. **Bouton WebView :**
   - Si `anime.url` stocke des métadonnées sérialisées, toujours surcharger `getAnimeUrl(anime: SAnime): String` pour renvoyer une URL HTTP/HTTPS directe et valide.
6. **Utilitaires Communs & Performance :**
   - **Priorité aux utilitaires partagés :** Toujours maximiser l'utilisation des fonctions utilitaires disponibles dans `core/src/main/kotlin/keiyoushi/utils` et `core/src/main/kotlin/fr/bluecxt/core/utils` plutôt que de réimplémenter du code ad hoc.
   - JSON : `myObject.toJsonString()`, `string.parseAs<T>()`, `response.parseAs<T>()`.
   - Concurrence : Utiliser `parallelMap` / `parallelMapNotNull` (`keiyoushi.utils.Coroutines`).
   - Requêtes multiples indépendantes : Obligation d'utiliser `coroutineScope { async { ... } }` pour exécuter les appels réseau en parallèle.
7. **Validation & Compilation Obligatoire :**
   - **Toujours tester chaque modification avec `assembleDebug`** (sur le module concerné, ex: `./gradlew :src:fr:<extension>:assembleDebug`, ou globalement) avant de conclure une tâche pour garantir l'absence d'erreurs de compilation, de syntaxe ou de packaging APK.

