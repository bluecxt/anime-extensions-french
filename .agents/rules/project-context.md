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

## 📜 2. Règles Obligatoires du Répertoire (`CONTRIBUTING.md`)
**Consultez et appliquez rigoureusement les normes définies dans `CONTRIBUTING.md`** :

1. **Formatage des Épisodes & Films :**
   - Épisodes standards : Pas de tag `[SX]` artificiel. Format `Épisode X - [Titre]` ou `Épisode X`.
   - Films uniques : `Film` ou nom du film direct sans crochets.
   - Films multiples dans un même SAnime : Préfixer obligatoirement par `[Film] Nom du film` (ex: `[Film] Mugen Train`).
   - OAV / Spéciaux : Préfixer `[OAV]`, `[Special]` uniquement lorsqu'ils sont mélangés à une série.
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
7. **Intégrité de la Base de Données AniZen & Tachiyomi :**
   - Immutabilité absolue des `anime.url` et `episode.url`. Ne jamais modifier le schéma d'URL d'une extension sans implémenter un fallback transparent pour les entrées déjà en base SQLite des utilisateurs.
8. **Validation, Style & Compilation Obligatoires :**
   - **Toujours valider les modifications** avec la compilation (`./gradlew :src:fr:<extension>:assembleDebug`), le formatage du code (`./gradlew spotlessCheck`) et l'analyse statique (`./gradlew detekt`) avant de conclure une tâche pour garantir l'absence de régression.
9. **Contraintes Discord & Alertes (STRICT) :**
   - **Interdiction formelle** d'envoyer des messages ou d'interagir sur Discord en dehors des salons `#error` et `#admin`. Zéro message intempestif dans les salons publics ou communautaires.


