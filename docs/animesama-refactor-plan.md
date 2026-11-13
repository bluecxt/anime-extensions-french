# Anime-Sama — plan de remise en état et de refactor

> Constat établi le 2026-11-12 en interrogeant le site en production
> (`anime-sama.to`), plus lecture du JS du site (`/js/contenu/videos.js`,
> `/js/contenu/lecteur.js`).

## 1. Diagnostic

### 1.1 Ce qui a cassé sur le site

| Élément | Ancien fonctionnement | État constaté | Preuve |
| --- | --- | --- | --- |
| Liens des épisodes | `/<saison>/<lang>/episodes.js` (`var epsN = [...]`) | **supprimé** ; les repères sont dans `script[data-source="bdd"]` de la page saison | `GET .../saison1/vostfr/episodes.js` → 404 ; `data-source="bdd"` présent sur la page saison |
| Adresse d'un lecteur | URL complète dans `epsN` | repère `lien:<id>`, résolu à la lecture par l'API | `GET /api/lien.php?j=<epsJeton>&l=65268` → `{"u":"https://ansembed.net/embed-….html"}` |
| Langues d'une saison | 1 URL par langue, déduite | `div.ct-langues a[href="../<slug>"]` + `var languesDispo` | page saison, `ct-langues` ; langue absente → **404** |
| Métadonnées (titre alternatif, synopsis, genres, affiche, fond, studio, état) | lues sur la page saison | **absentes** de la page saison, présentes sur la fiche hub | `.oe-meta`, `#titreAlter`, `#synopsisText`, `.oe-genres`, `.oe-affiche`, `.oe-fond` absents en page saison |
| `newSPF("...")` (titres de films) | parsé par `extractSpfTitles` | **disparu du site** | `newSPF` introuvable sur les pages saison film |
| Pagination catalogue | `.pagi-aller input[max]` | `div.pagi` + `a.pagi-btn[href="?page=N"]` | page `/catalogue/?page=2` |
| `panneauAnime(...)` | occurrences réelles | 1 à 3 occurrences **commentées** en plus des réelles | `07-ghost` : 18 occurrences brutes, **1** réelle après nettoyage des commentaires |

### 1.2 Sémantique du nouveau script `bdd`

```html
<script data-source="bdd">
  var eps1 = ["lien:65268", …];      // un tableau par serveur ; index 0 = épisode 1
  var eps2 = […]; var eps3 = […];
  var epsMeta = {"65268":["ansembed.net"], "65329":["minochinos.com",2], …};
  var epsJeton = "1674.1794592800.d22589c309e3fb9a4b7ee272";
  var epsOrdreFinal = true;
</script>
```

* `epsMeta[id][0]` = domaine de l'hébergeur.
* `epsMeta[id][1]` = **état du lien** : absent = vivant, `1` = mort, `2` = mort
  (« autre »). Confirmé dans `lecteur.js`, fonction `infosLien`.
  Sur `07-ghost` : 75 liens dont **8 morts**.
* `lecteursInfos[domaine]` (inline script) = `{nom, pub, hd, mort?}`.
  `ansembed.net` → **Vidmoly**, `minochinos.com` → **Earnvids**,
  `embed4me.com` → **Embed4me** mais `mort: true` (liens cassés à l'échelle du site).
* `epsOrdreFinal` : `true` = les serveurs sont déjà dans l'ordre voulu par le site.
* Le jeton est **daté** (horodatage dans la chaîne) et **lié à la page** :
  un jeton périmé donne `403 {"erreur":"jeton"}`. Un id inconnu donne
  `404 {"erreur":"absent"}`, un id manquant `400 {"erreur":"lien"}`.

### 1.3 Conséquences pour l'extension actuelle

1. `fetchPlayers()` lit `episodes.js` → **ne renvoie plus rien** ; l'erreur
   remontée est « aucun épisode trouvé ».
2. `getSeasonList()` / `getEpisodeList()` cherchent les métadonnées sur la page
   saison → posters, résumés, genres et états sont vides ou faux.
3. `parseMedias()` filtre mal les panneaux commentés → saisons fantômes
   (le site commente les saisons non encore sorties).
4. La pagination du catalogue n'est plus détectée → `hasNextPage` toujours faux.
5. Une saison qui n'existe pas renvoie **200 sans script bdd** (soft 404) : il
   faut le traiter comme « pas d'épisode », pas comme une erreur réseau.

## 2. Architecture cible

Objectif : un fichier = une responsabilité, moins de 200 lignes par fichier,
noms explicites, KDoc en anglais.

```
animesama/
├── AnimeSama.kt              Source : uniquement les overrides Aniyomi + préférences
├── AnimeSamaFilters.kt       Données des filtres (inchangé)
├── AnimeSamaUrlActivity.kt   Deep links (inchangé)
├── model/
│   ├── MediaUrl.kt           JSON stocké dans SAnime.url / SEpisode.url
│   ├── LinkSet.kt            Liens d'un épisode ou d'un hébergeur (internalData)
│   └── ContentType.kt        MOVIE / SPECIAL / OAV / SEASON + préfixes
├── site/
│   ├── PageLoader.kt         GET + cache LRU + gestion 404/IOException
│   ├── CataloguePage.kt      Cartes + pagination
│   ├── HubPage.kt            Métadonnées de la fiche + liste des saisons
│   ├── SeasonPage.kt         Langues disponibles + libellé de saison
│   ├── BddScript.kt          Parsing de script[data-source=bdd]
│   └── LinkResolver.kt       /api/lien.php + renouvellement du jeton
├── naming/
│   ├── SeasonTitles.kt       "Saison 1" → titre affiché
│   └── EpisodeNaming.kt      Préfixes + titres d'épisodes
├── metadata/
│   └── Enrichment.kt         TVDB/TMDB (affiches, résumés, compteurs)
└── legacy/
    └── LegacyAnimeSama.kt    Parseur des URLs d'avant migration (à retirer 07/2027)
```

Règles :

* `AnimeSama.kt` ne parse aucun HTML : il orchestre.
* Chaque parseur reçoit un `Document` et renvoie une `data class` — testable
  sans réseau.
* Aucune regex ne s'applique au HTML brut sans passer par
  `BddScript`/`HubPage`/`SeasonPage`.
* Tout ce qui vient du site et qui doit survivre entre deux ouvertures de
  l'application est un DTO `@Serializable` dans `model/`.

## 3. Plan de correction

### P0 — remettre la lecture en état

1. **`BddScript`** : extraire `epsN`, `epsMeta`, `epsJeton`, `epsOrdreFinal`,
   `lecteursInfos`, `lecteursHS` ; exposer pour chaque épisode la liste
   `(serveur, hébergeur, id, vivant)`.
2. **`LinkResolver`** : résoudre un repère via `/api/lien.php`, avec un seul
   renouvellement de jeton sur `403`.
3. **`SeasonPage`** : lire `div.ct-langues` (slug + libellé), le libellé de
   saison (`$("#avOeuvre").html("Saison 1")`) et le titre de l'œuvre
   (`h3#titreOeuvre`).
4. **`HubPage`** : métadonnées (`.oe-meta` → année / format / studio / état,
   `#synopsisText`, `.oe-genres`, `.oe-affiche`, `.oe-fond`, `a.oe-note` pour
   TMDB) + saisons (`panneauAnime` après nettoyage des commentaires).
5. **`getEpisodeList`** : pour chaque langue de la saison, charger la page,
   lire le `bdd`, fusionner par index d'épisode, ignorer les liens morts.
6. **`getHosterList`** : une entrée par couple (langue, hébergeur) —
   l'utilisateur choisit donc un serveur précis au lieu d'un « VOSTFR »
   fourre-tout.
7. **`getVideoList`** : résoudre l'id → URL d'embed → `extractVideos`.
8. **`CataloguePage`** : cartes `a.as-carte-lien`, exclure scans/webtoons,
   pagination `a.pagi-btn`.

### P1 — robustesse

9. Détecter le soft 404 de saison (200 sans `bdd`) → liste vide + message
   explicite, pas d'exception réseau.
10. Filtrer les hébergeurs marqués `mort: true` dans `lecteursInfos`
    (aujourd'hui : `embed4me.com`) et les liens `epsMeta[id][1] ∈ {1,2}`.
11. Repli : un jeton stocké peut être périmé → rechargement de la page de la
    langue pour en obtenir un neuf, une seule fois.
12. `getSeasonList` sans requête supplémentaire : les métadonnées viennent de
    la fiche hub déjà chargée.

### P2 — lisibilité

13. Supprimer le code mort (voir §5).
14. Renommer : `spfTitles`/`newSPF` → supprimés ; `parseMedias` →
    `HubPage.seasons` ; `EpisodePlayers` → `LinkSet` ; `UrlContent` →
    `MediaUrl` ; `getLegacy*` → `legacy/`.
15. KDoc en anglais sur chaque déclaration publique et chaque fonction privée
    non triviale.

## 4. Optimisations mesurables

| Situation | Avant | Après |
| --- | --- | --- |
| Ouvrir une saison | 1 requête par langue (`episodes.js`) | 1 requête par langue (page) — identique, mais les données sont réellement disponibles |
| Ouvrir une saison (2 langues) | 2 | 2, **en parallèle**, cache LRU 30 s |
| Lire un épisode | 0 requête (URLs déjà connues) | 1 requête `lien.php` par lien **au moment de la lecture** |
| `getSeasonList` | 1 requête + N requêtes TVDB | 1 requête (hub déjà chargé) + N requêtes TVDB |
| Liens morts | proposés à l'utilisateur, extraction en échec | filtrés en amont (~10 % de lecteurs en moins sur `07-ghost`) |
| Cache des pages | `ConcurrentHashMap` + mutex maison, sans borne | `PageLoader` : 32 entrées bornées, TTL 30 s, déduplication par mutex, negative caching |

Le point clé : **ne jamais résoudre les 75 liens d'une saison** (75 requêtes
API). La résolution est paresseuse, une requête quand l'utilisateur lance une
lecture.

## 5. Code mort et dette constatés

| Fichier | Élément | Raison |
| --- | --- | --- |
| `AnimeSama.kt` | `extractSpfTitles`, `newSpfRegex` | `newSPF(...)` n'existe plus sur le site |
| `AnimeSama.kt` | bloc `// TEST` + `val langs` dans `getEpisodeList` | variable inutilisée, requête HTTP inutile |
| `AnimeSama.kt` | branche `isSpecialSeason` / OAV dans le nommage | plus de saison OAV dans les panneaux observés, à réintroduire seulement si un cas réel apparaît |
| `AnimeSama.kt` | cache maison (`documentCache`, `documentMutexes`) | remplacé par `site/PageLoader` |
| `AnimeSama.kt` | `calculateAutoS0Offset` : une requête par saison précédente | supprimé, `episodeOffset` de TVDB et le repli saison 0 suffisent |
| `dto/AnimeSamaDto.kt` | `ContentType.getPrefix(seasonNum)` | paramètre ignoré |
| `LegacyAnimeSama.kt` | 266 lignes | conservé jusqu'en 07/2027 (URLs déjà en bibliothèque), isolé dans `legacy/` |

## 6. Compatibilité

* **URLs stockées** : le JSON de `SAnime.url` reste
  `{"url":…,"titles":…,"season":…}` et gagne un champ optionnel `lang`.
  Les anciennes entrées continuent de se parser.
* **Entrées pré-JSON** : `legacy/` reste branché sur les trois overrides
  (`getAnimeDetails`, `getSeasonList`, `getEpisodeList`).
* **Jeton périmé** : géré par `LinkResolver` (un rechargement maximum).
* **Serveurs** : `supportedServers` reste piloté par le routeur du core ;
  `ansembed.net` y est déjà rattaché à `Vidmoly`.

## 7. Vérification

Matrice de tests manuels après build :

| Cas | Attendu |
| --- | --- |
| `07-ghost` (1 saison, 1 langue, 25 ép.) | 25 épisodes, 3 serveurs (Vidmoly / Embed4me / Earnvids), 8 liens morts filtrés |
| `100-meters` (film, 3 langues) | 1 « épisode » film, 3 langues proposées |
| `tis-time-for-torture-princess` (2 saisons) | 2 entrées de saison, titres « … Saison 1/2 » |
| `dragon-ball-super` (3 saisons dont « Remake 2026 ») | 3 entrées, pas de panneau fantôme |
| `20th-century-boys` (aucun panneau actif) | message clair « pas encore disponible », pas de crash |
| Saison inexistante (`saison9`) | liste vide, pas d'erreur réseau |
| Catalogue page 2 | `hasNextPage` correct |
| Recherche + filtres | même résultat que le site |

## 8. Statut d'implémentation (2026-11-12)

Le refactor est appliqué. Arborescence finale, sous
`src/fr/animesama/src/eu/kanade/tachiyomi/animeextension/fr/animesama/` :

| Fichier | Rôle | Lignes |
| --- | --- | --- |
| `AnimeSama.kt` | overrides Aniyomi, orchestration, aucun parsing HTML | ~660 |
| `site/SitePaths.kt` | conventions de chemins (langue, saison, hub) | ~100 |
| `site/PageLoader.kt` | cache borné + déduplication des requêtes | ~130 |
| `site/CataloguePage.kt` | cartes `data-*` + pagination `input[max]` | ~95 |
| `site/HubPage.kt` | métadonnées de fiche + saisons (commentaires retirés) | ~200 |
| `site/SeasonPage.kt` | langues `ct-langues` + libellé de saison | ~110 |
| `site/BddScript.kt` | `epsN`, `epsMeta`, `epsJeton`, `lecteursInfos` | ~240 |
| `site/LinkResolver.kt` | `/api/lien.php` + renouvellement de jeton | ~85 |
| `model/` | `MediaUrl`, `LinkSet`, `ContentType` | ~120 |
| `naming/` | titres de saison et d'épisodes | ~210 |
| `metadata/Enrichment.kt` | fusion HTML du site + TVDB | ~160 |
| `legacy/LegacyAnimeSama.kt` | parseur pré-JSON (retrait 07/2027) | ~330 |

Décisions prises pendant l'implémentation :

- **Liens résolus à la lecture** : `SEpisode.url` stocke `(langue, hébergeur, id, jeton)` ;
  `getVideoList` appelle l'API une fois par lien, et recharge la page de la langue uniquement
  si le jeton est refusé (`403`).
- **Un hébergeur par langue** (comme les autres extensions du dépôt) : le choix du serveur se fait
  dans la liste des vidéos, où l'extracteur étiquette déjà chaque piste avec son nom.
- **Liens morts ignorés** : `epsMeta[id][1]` valant `1` ou `2`, et les hébergeurs marqués
  `mort: true` (`embed4me.com`), ne sont plus proposés. Un épisode dont tous les liens sont
  morts disparaît.
- **Parseurs purs** : aucun parseur ne dépend d'Android ni d'Injekt, ce qui les rend exécutables
  dans un simple JVM — c'est ainsi qu'ils sont vérifiés (§9).
- **Cache local** : `PageLoader` n'utilise pas le `LruCache` du core, non commité à ce jour ; son TTL
  est un `kotlin.time.Duration` mesuré sur l'horloge monotone, donc insensible aux changements
  d'heure système.
- **Ancien format d'épisode** (`List<Player>`) accepté en lecture : ses URLs résolues pointent
  vers le mécanisme supprimé, mais elles restent jouables tant que l'hébergeur vit ; les ignorer
  casserait les bibliothèques existantes jusqu'à un rafraîchissement manuel.
- **Ancien format d'URL de fiche** (chemin nu, sans JSON) décodé lui aussi : une entrée
  `/catalogue/naruto/` ou `/catalogue/naruto/saison1` ajoutée par les premières versions passe
  par le pipeline moderne au lieu du parseur HTML historique, qui ne correspond plus au site.
  Seules les URLs ancrées (`#s-2.0|Titre`) vont encore dans `legacy/`.
- **« Early Fetch » conservée** : certains clients (Anikku) ne lisent `fetch_type` qu'au moment
  du listing, donc l'option reste disponible ; elle coûte une page par carte du catalogue, d'où
  son état désactivé par défaut. Elle s'applique au catalogue, aux dernières sorties, à la
  recherche et aux œuvres liées.
- **Deep links** : `id:<slug>` ouvre directement la fiche demandée au lieu de lancer une
  recherche qui ne trouvait rien.

## 9. Vérification effectuée

Gradle ne peut pas tourner ici (distribution 9.6.1 absente du poste, `~/.gradle` en lecture
seule dans le bac à sable), la validation a donc été faite autrement :

1. **Compilation Kotlin réelle** des 17 fichiers contre `android.jar`, les classes compilées du
   core et les dépendances du cache Gradle : **0 erreur, 0 warning**, 80 classes produites.
2. **Detekt** : 8 constats au premier passage (complexité de `enrichFromHub`, exceptions
   génériques, 4 nombres magiques, 1 `val` constantifiable), **tous corrigés**.
3. **Exécution des parseurs** sur des pages réellement téléchargées (`07-ghost`, le film
   `100 meters`, la page 2 du catalogue, le flux « dernières sorties ») : **42 vérifications,
   0 échec** — 25 épisodes, 3 serveurs, 8 liens morts filtrés, noms d'hébergeurs résolus
   (ansembed → Vidmoly, embed4me marqué mort), langues d'un film (VOSTFR/VF/VA), pagination
   page 2 → suivante, 53 → arrêt, 54 → arrêt, saisons fantômes éliminées (18 panneaux bruts → 1).

Reste à valider sur l'application (rendu Aniyomi et extracteurs, qui ne s'exécutent pas hors
appareil) : voir la matrice du §7.

### 9.1 Suite de tests `anime-extension-tester` (2026-11-12)

Deux suites passent contre l'extension compilée (`compileDevKotlin`), Gradle étant désormais
disponible dans cet environnement :

- `dev/samples/french/animesama.json` (catalogue, recherche, fiches, 12 sagas One Piece, hosters,
  vidéos) : **23/23**.
- `anime-extension-tester/samples/animesama.json` (compatibilité **vieille bibliothèque**, URLs
  brutes pré-JSON) : **4/4**.

Trois bugs réels ont été révélés par la suite et corrigés :

1. un épisode dont **tous** les liens sont morts disparaissait, ce qui créait des trous dans la
   numérotation (saga 9 : 131 épisodes annoncés par le site, 123 listés). Il est désormais
   conservé, avec ses liens vivants uniquement.
2. `preview_url` pouvait être vide quand TVDB n'a pas de capture d'épisode ; repli ajouté sur le
   fond puis les affiches de la saison.
3. `getVideoList` ne lisait pas l'`internalData` écrit par la version précédente ; le format
   hérité `[{url,lang}]` est maintenant accepté.

Côté sample, deux mises à jour étaient nécessaires pour suivre le site et l'outil :

- One Piece : le site a renommé `sagaN` en `saisonN` et redécoupe les épisodes (61, 81, 63, 130,
  53, 127, 58, 172, 131, 222, 73, 24) ; les libellés portent désormais leur plage d'épisodes.
- le contrat `preview_url` n'existe pas dans le tester (le DTO expose `thumbnailUrl`, accepté sous
  le nom `thumbnail_url`), et les blocs `expected` comparaient l'`url` de l'épisode — qui contient
  des ids **et un jeton volatil** — ainsi qu'un ordre croissant alors que la source renvoie le plus
  récent en premier. Ils ont été remplacés par des contrats (`minCount`, champs non vides).

Durées observées : 169 s pour la suite complète avec le rate-limiter du tester (600 ms par hôte),
contre 6 à 10 s pour les mêmes tests sans limitation. Le coût vient de TVDB (paginé sur 1100+
épisodes pour One Piece), pas du parsing du site.

Un test dédié couvre le cas d'un hub ouvert alors que le type de contenu n'est pas encore connu :
`AnimeSama Hub Placeholder (Early Fetch)` attend exactement un épisode, celui qui indique
d'activer Early Fetch, au lieu d'une liste vide.

### 9.2 Piège de l'outil de test

Le build produit aussi une variante `debug` (elle est compilée par `detekt`), et le classloader
du tester charge la **première** arborescence `classes` trouvée : `debug` passe avant `dev` dans
l'ordre alphabétique. Des classes obsolètes peuvent donc être exécutées silencieusement. Avant de
lancer les tests : `rm -rf src/fr/animesama/build/intermediates/built_in_kotlinc/debug` (ou
`clean` puis compiler la seule variante voulue).
