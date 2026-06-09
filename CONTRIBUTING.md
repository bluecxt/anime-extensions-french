# Contribution

Ce guide contient des instructions et des conseils sur la façon de créer une nouvelle extension Aniyomi. Veuillez le **lire attentivement** si vous êtes un nouveau contributeur ou si vous n'avez pas d'expérience avec les langages et les connaissances requis.

Ce guide n'est pas définitif et est mis à jour au fil du temps. Si vous y trouvez un problème, n'hésitez pas à le signaler via un [Ticket Meta](https://github.com/bluecxt/anime-extensions-french/issues/new?assignees=&labels=Meta+request&template=06_request_meta.yml) ou à le corriger directement en soumettant une Pull Request.

## Table des matières

1. [Prérequis](#prérequis)
   1. [Outils](#outils)
   2. [Clonage du dépôt](#clonage-du-dépôt)
2. [Obtenir de l'aide](#obtenir-de-laide)
3. [Écrire une extension](#écrire-une-extension)
   1. [Configuration d'un nouveau module Gradle](#configuration-dun-nouveau-module-gradle)
   2. [Dépendances de base](#dépendances-de-base)
   3. [Classe principale de l'extension](#classe-principale-de-lextension)
   4. [Flux d'appel de l'extension](#flux-dappel-de-lextension)
   5. [Notes diverses](#notes-diverses)
   6. [Fonctionnalités avancées de l'extension](#fonctionnalités-avancées-de-lextension)
4. [Thèmes multi-sources (Multisrc)](#thèmes-multi-sources-multisrc)
   1. [Structure des répertoires](#structure-des-répertoires)
   2. [Flux de travail de développement](#flux-de-travail-de-développement)
   3. [Génération d'overrides](#génération-doverrides)
   4. [Notes additionnelles](#notes-additionnelles-1)
5. [Exécution](#exécution)
6. [Débogage](#débogage)
   1. [Débogueur Android](#débogueur-android)
   2. [Logs](#logs)
   3. [Inspection des appels réseau](#inspection-des-appels-réseau)
   4. [Utilisation d'outils d'inspection réseau externes](#utilisation-doutils-dinspection-réseau-externes)
7. [Compilation (Build)](#compilation-build)
8. [Soumettre les modifications](#soumettre-les-modifications)
   1. [Liste de vérification (Checklist) de la Pull Request](#liste-de-vérification-checklist-de-la-pull-request)

## Prérequis

Avant de commencer, veuillez noter que la capacité à utiliser les technologies suivantes est **requise** et que les contributeurs existants ne vous les enseigneront pas activement.

- Développement [Android de base](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)
- Web scraping (Extraction de données web)
    - [HTML](https://developer.mozilla.org/fr/docs/Web/HTML)
    - [Sélecteurs CSS](https://developer.mozilla.org/fr/docs/Web/CSS/CSS_Selectors)
    - [OkHttp](https://square.github.io/okhttp/)
    - [JSoup](https://jsoup.org/)

### Outils

- [Android Studio](https://developer.android.com/studio)
- Émulateur ou téléphone avec les options de développeur activées et une version récente d'AniZen installée
- [Générateur d'icônes](https://as280093.github.io/AndroidAssetStudio/icons-launcher.html)
- [Tester JSoup](https://try.jsoup.org/)

### Clonage du dépôt

Certaines étapes alternatives peuvent être suivies pour ignorer les sources non liées, ce qui rendra l'extraction, la navigation et la compilation plus rapides. Cela réduira également l'utilisation du disque et le trafic réseau.

**Ces étapes ne sont nécessaires que lorsque le dépôt est énorme et contient beaucoup de sources. Si le dépôt est petit, effectuez simplement un clonage complet normal.**

<details><summary>Étapes</summary>

1. Effectuez un clonage partiel.
    ```bash
    git clone --filter=blob:none --sparse https://github.com/bluecxt/anime-extensions-french.git
    cd anime-extensions-french/
    ```
2. Configurez le "sparse checkout" (extraction parcimonieuse).

    Il existe deux modes de correspondance de motifs. Le mode par défaut est le mode "cone" (🔺).
    Le mode cone permet une correspondance de motifs considérablement plus rapide pour les gros monorepos et la fonction d'index parcimonieux pour rendre les commandes Git plus réactives.
    Dans ce mode, vous ne pouvez filtrer que par chemin de fichier, ce qui est moins flexible et peut nécessiter plus de travail lorsque la structure du projet change.

    Vous pouvez ignorer ce bloc de code pour utiliser le mode hérité si vous voulez des filtres plus simples. Ce ne sera pas beaucoup plus lent car le dépôt n'a pas tant de fichiers que ça.

    Pour activer le mode cone avec l'index parcimonieux, suivez ces étapes :

    ```bash
    git sparse-checkout set --cone --sparse-index
    # ajouter les dossiers du projet
    git sparse-checkout add buildSrc core gradle lib lib-multisrc utils
    # ajouter une seule source
    git sparse-checkout add src/<lang>/<source>
    ```

    Pour supprimer une source, ouvrez `.git/info/sparse-checkout` et supprimez les lignes exactes que vous avez tapées lors de l'ajout. Ne touchez pas aux autres lignes générées automatiquement à moins de comprendre parfaitement le fonctionnement du mode cone, sous peine de le casser.

    Pour utiliser le mode hérité (non-cone), suivez ces étapes :

    ```bash
    # activer le sparse checkout
    git sparse-checkout set --no-cone
    # éditer le filtre sparse checkout
    vim .git/info/sparse-checkout
    # alternativement, si vous avez VS Code installé
    code .git/info/sparse-checkout
    ```
    Voici un exemple :
    ```bash
    /*
    !/src/*
    !/multisrc-lib/*
    # autoriser une seule source
    /src/<lang>/<source>
    # autoriser un thème multisrc
    /lib-multisrc/<source>
    # ou tapez le nom de la source directement
    <source>
    ```

    Explication : les règles sont comme un `gitignore`. Nous excluons d'abord toutes les sources tout en conservant les dossiers du projet, puis nous rajoutons manuellement les sources nécessaires.

3. Configurez les remotes.
    ```bash
    # ajouter l'upstream (dépôt principal)
    git remote add upstream https://github.com/bluecxt/anime-extensions-french.git
    # optionnellement, désactiver le push vers l'upstream
    git remote set-url --push upstream no_pushing
    # optionnellement, ne récupérer que la branche main (ignorer toutes les autres)
    git config remote.upstream.fetch "+refs/heads/main:refs/remotes/upstream/main"
    # mettre à jour les remotes
    git remote update
    # suivre la branche main de l'upstream au lieu du fork
    git branch main -u upstream/main
    ```
4. Configurations utiles (optionnel).
    ```bash
    # nettoyer les branches distantes obsolètes lors du fetch
    git config remote.origin.prune true
    # faire uniquement des fast-forward lors du pull de la branche main
    git config pull.ff only
    # Ajouter un alias pour synchroniser la branche main sans récupérer de blobs inutiles.
    # Si vous lancez `git pull` pour faire un fast-forward dans un clone sans blobs comme celui-ci,
    # tous les blobs (fichiers) des nouveaux commits sont quand même récupérés quelles que soient
    # les règles sparse, ce qui accumule des fichiers inutiles.
    # Utilisez `git sync-master` pour éviter cela. Attention si vous avez des modifications
    # sur la branche main, ce qui n'est pas une bonne pratique.
    git config alias.sync-master '!git switch main && git fetch upstream && git reset --keep FETCH_HEAD'
    ```
5. Plus tard, si vous modifiez le filtre sparse checkout, lancez `git sparse-checkout reapply`.

En savoir plus sur le [modèle d'objet de Git](https://github.blog/2020-12-17-commits-are-snapshots-not-diffs/), le [clonage partiel](https://github.blog/2020-12-21-get-up-to-speed-with-partial-clone-and-shallow-clone/), le [sparse checkout](https://github.blog/2020-01-17-bring-your-monorepo-down-to-size-with-sparse-checkout/), l'[index parcimonieux](https://github.blog/2021-11-10-make-your-monorepo-feel-small-with-gits-sparse-index/), et les [refspecs négatifs](https://github.blog/2020-10-19-git-2-29-released/#user-content-negative-refspecs).
</details>

## Obtenir de l'aide

- Rejoignez [le serveur Discord](https://discord.gg/Bx2PhgndEC) pour obtenir de l'aide en ligne et poser des questions lors du développement de votre extension.
- Certaines fonctionnalités et astuces ne sont pas explorées dans ce document. Référez-vous au code des extensions existantes pour des exemples.

## Écrire une extension

La façon la plus rapide de commencer est de copier la structure de dossier d'une extension existante et de la renommer selon vos besoins. Nous recommandons également de lire le code de quelques extensions existantes avant de commencer.

> [!IMPORTANT]
> Avant de commencer, assurez-vous de lire les [[REPO_RULES|règles d'harmonisation du dépôt]] pour connaître les normes de formatage des épisodes et des sources vidéo.

### Configuration d'un nouveau module Gradle

Chaque extension doit résider dans `src/<lang>/<nomdesource>`. Utilisez `all` comme `<lang>` si votre source cible prend en charge plusieurs langues.

Le `<lang>` utilisé dans le dossier à l'intérieur de `src` doit être la partie principale de la langue. Par exemple, si vous créez une source `pt-BR`, utilisez `pt` uniquement pour le dossier. Dans la classe de la source, utilisez la chaîne de caractères complète de la locale à la place.

### Chargement d'un sous-ensemble de modules Gradle

Par défaut, toutes les extensions individuelles et multisrc générées sont chargées pour le développement local. Cela peut être peu pratique si vous ne travaillez que sur une seule extension à la fois.

Pour ajuster quels modules sont chargés, modifiez le fichier `settings.gradle.kts` selon vos besoins.

#### Structure des fichiers d'une extension

La structure d'extension la plus simple ressemble à ceci :

```console
$ tree src/<lang>/<nomdesource>/
src/<lang>/<nomdesource>/
├── AndroidManifest.xml (optionnel)
├── build.gradle
├── res
│   ├── mipmap-hdpi
│   │   └── ic_launcher.png
│   ├── mipmap-mdpi
│   │   └── ic_launcher.png
│   ├── mipmap-xhdpi
│   │   └── ic_launcher.png
│   ├── mipmap-xxhdpi
│   │   └── ic_launcher.png
│   └── mipmap-xxxhdpi
│       └── ic_launcher.png
└── src
    └── eu
        └── kanade
            └── tachiyomi
                └── animeextension
                    └── <lang>
                        └── <nomdesource>
                            └── <NomDeSource>.kt

13 répertoires, 9 fichiers
```

`<lang>` doit être un code de langue conforme à la norme ISO 639-1 (deux lettres ou `all`). `<nomdesource>` doit être adapté du nom du site, et ne peut contenir que des lettres ASCII minuscules et des chiffres. Le code de votre extension doit être placé dans le package `eu.kanade.tachiyomi.animeextension.<lang>.<nomdesource>`.

#### AndroidManifest.xml (optionnel)
Vous n'avez besoin de créer ce fichier que si vous souhaitez ajouter le "deep linking" à votre extension.
Voir [Filtre d'intention d'URL](#filtre-dintention-durl) pour plus d'informations.

#### build.gradle
Assurez-vous que le fichier `build.gradle` de votre nouvelle extension suit la structure suivante :

```groovy
ext {
    extName = '<Nom de ma source>'
    extClass = '.<NomDeSource>'
    extVersionCode = 1
    isNsfw = true
}

apply from: "$rootDir/common.gradle"
```

| Champ            | Description                                                                                                                                                                                           |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `extName`        | Le nom de l'extension. Doit être romanisé si le nom du site n'est pas en anglais.                                                                                                                     |
| `extClass`       | Pointe vers la classe qui implémente `AnimeSource`. Vous pouvez utiliser un chemin relatif commençant par un point (le nom du package est le chemin de base). Utilisé pour trouver l'extension.       |
| `extVersionCode` | Le code de version de l'extension. Il doit s'agir d'un entier positif incrémenté à chaque changement du code.                                                                                         |
| `isNsfw`         | (Optionnel, par défaut `false`) Flag indiquant qu'une source contient du contenu NSFW (+18).                                                                                                          |

Le nom de la version de l'extension est généré automatiquement en concaténant `14` et `extVersionCode`. Avec l'exemple utilisé ci-dessus, la version serait `14.1`.

### Dépendances de base

#### API de l'extension

Les extensions s'appuient sur [extensions-lib](https://github.com/aniyomiorg/extensions-lib), qui fournit des interfaces et des stubs de l'[application](https://github.com/aniyomiorg/aniyomi) pour la compilation. Les implémentations réelles peuvent être trouvées [ici](https://github.com/aniyomiorg/aniyomi/tree/main/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/animesource). Consulter l'implémentation réelle aidera à comprendre le flux d'appel des extensions.

#### Dépendances supplémentaires

Si vous avez besoin de fonctionnalités supplémentaires, vous pouvez ajouter d'autres dépendances à votre fichier `build.gradle`. De nombreuses [dépendances](https://github.com/aniyomiorg/aniyomi/blob/main/app/build.gradle.kts) de l'application sont exposées par défaut aux extensions.

> [!NOTE]
> Plusieurs dépendances sont déjà exposées à toutes les extensions via le catalogue de versions de Gradle. Pour voir lesquelles sont disponibles, consultez le fichier `gradle/libs.versions.toml`.

Notez que nous utilisons `compileOnly` au lieu de `implementation` si l'application le contient déjà. Vous pouvez utiliser `implementation` pour une nouvelle dépendance si vous préférez ne pas dépendre de ce que l'application principale possède, au détriment de la taille de l'APK.

> [!IMPORTANT]
> L'utilisation de `compileOnly` vous limite aux versions qui doivent être compatibles avec celles utilisées dans [la dernière version stable de l'application](https://github.com/aniyomiorg/aniyomi/releases/latest).

### Classe principale de l'extension

La classe qui est référencée et définie par `extClass` dans `build.gradle`. Cette classe doit soit implémenter `AnimeSourceFactory`, soit hériter d'une des implémentations de `AnimeSource` : `AnimeHttpSource` ou `ParsedAnimeHttpSource`.

| Classe                  | Description                                                                                                                               |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `AnimeSourceFactory`    | Utilisé pour exposer plusieurs `AnimeSource`. À utiliser dans le cas d'une source qui supporte plusieurs langues ou miroirs du même site. |
| `AnimeHttpSource`       | Pour une source en ligne, où les requêtes sont effectuées via HTTP.                                                                      |
| `ParsedAnimeHttpSource` | Obsolète, utilisez `AnimeHttpSource` à la place.                                                                                          |

#### Variables clés de la classe principale

| Champ     | Description                                                                                                                                                                 |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `name`    | Nom affiché dans l'onglet "Sources" de l'application.                                                                                                                       |
| `baseUrl` | URL de base de la source sans barre oblique (slash) à la fin.                                                                                                               |
| `lang`    | Un code de langue conforme à l'ISO 639-1 (deux lettres en minuscules, mais peut aussi inclure la variante pays/dialecte en utilisant un tiret).                             |
| `id`      | Identifiant de votre source, défini automatiquement dans `AnimeHttpSource`. Ne doit être surchargé manuellement que si vous devez copier un ID existant généré auparavant. |

### Flux d'appel de l'extension

#### Animes populaires

Alias le point d'entrée "Parcourir" dans l'application (invoqué en appuyant sur le nom de la source).

- L'application appelle `fetchPopularAnime` qui doit renvoyer une `AnimesPage` contenant le premier lot d'entrées `SAnime`.
    - Cette méthode prend en charge la pagination. Lorsque l'utilisateur fait défiler la liste, l'application l'appelle à nouveau avec des valeurs de `page` croissantes (commençant par `page=1`). Cela continue tant que `AnimesPage.hasNextPage` est `true`.
- Pour afficher la liste correctement, l'application a besoin de `url`, `title` et `thumbnail_url`. Vous **devez** les définir ici.

#### Dernières mises à jour (Latest Anime)

- Activé si `supportsLatest` est `true` pour une source.
- Similaire aux animes populaires, mais doit récupérer les entrées les plus récentes.

#### Recherche d'anime

- Lorsque l'utilisateur effectue une recherche, `fetchSearchAnime` est appelé.
    - Si la fonctionnalité de recherche n'est pas disponible, renvoyez `Observable.just(AnimesPage(emptyList(), false))`.
- `getFilterList` sera appelé pour obtenir tous les filtres disponibles.

##### Filtres

Le flux de recherche prend en charge des filtres qui peuvent être ajoutés à une `AnimeFilterList`. Vous pouvez consulter les types de filtres disponibles [ici](https://github.com/aniyomiorg/aniyomi/blob/main/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/animesource/model/AnimeFilter.kt).

#### Détails de l'anime

- Lorsque l'utilisateur appuie sur un anime, `getAnimeDetails` et `getEpisodeList` sont appelés.
- `SAnime.status` est une valeur "enum". Référez-vous aux [valeurs dans l'objet compagnon de `SAnime`](https://github.com/aniyomiorg/extensions-lib/blob/main/library/src/main/java/eu/kanade/tachiyomi/animesource/model/SAnime.kt#L24).
- La liste des épisodes doit être triée par ordre décroissant (le plus récent en premier).

#### Épisode

- `SEpisode.date_upload` est le temps Unix Epoch **exprimé en millisecondes**.
- Si vous ne le remplissez pas (laissé à zéro), l'application utilisera la date actuelle, mais il est recommandé de le remplir si possible.

#### Vidéos de l'épisode

- Lorsqu'un utilisateur ouvre un épisode, `getVideoList` est appelé et renvoie une liste de `Video`.
- Les attributs `Video.url` et `Video.videoUrl` **doivent être définis comme des URL absolues**.

### Notes diverses

- Si vous n'utilisez pas certaines méthodes héritées, surchargez-les et lancez une exception : `throw UnsupportedOperationException()`
- Essayez autant que possible de rester fidèle au flux de travail général de `AnimeHttpSource`.
- En implémentant `ConfigurableAnimeSource`, vous pouvez ajouter des paramètres à votre source via `SharedPreferences`.

### Fonctionnalités avancées de l'extension

#### Filtre d'intention d'URL (URL intent filter)

Les extensions peuvent définir des filtres d'intention en les déclarant dans un fichier `AndroidManifest.xml` personnalisé. Cela permet à l'application de s'ouvrir lorsqu'un lien du site est cliqué.

#### Stratégie de mise à jour

Pour les titres finis qui ne recevront plus de nouveaux épisodes, vous pouvez utiliser `UpdateStrategy.ONLY_FETCH_ONCE` pour économiser des requêtes lors des mises à jour globales de la bibliothèque.

#### Renommer des sources existantes

Si une source change de nom sur son site web, vous devez explicitement définir l'`id` sur l'ancienne valeur pour que les utilisateurs ne perdent pas leur suivi et n'aient pas à migrer manuellement.

## Thèmes multi-sources (Multisrc)

Le répertoire `lib-multisrc` abrite du code source utile lorsque plusieurs sites utilisent le même moteur (souvent un CMS) et sont assez similaires pour justifier une réutilisation de code par héritage/composition.

Cette section doit être réécrite. Venez sur le salon `#programming` de notre serveur Discord pour obtenir de l'aide.

<details>
<summary>Informations obsolètes</summary>

(Cette section contient des détails techniques sur la génération automatique des extensions à partir d'un thème commun).
</details>

## Exécution

Pour le développement local, utilisez la configuration de lancement suivante dans Android Studio pour lancer l'application directement dans le panneau de navigation.

Copiez ce qui suit dans `Launch Flags` pour le build Debug d'AniZen :

```
-W -S -n app.anizen.dev/eu.kanade.tachiyomi.ui.main.MainActivity -a eu.kanade.tachiyomi.SHOW_CATALOGUES
```

## Débogage

### Débogueur Android

Utilisez `Attach Debugger to Android Process` dans Android Studio pour attacher le débogueur à l'application `app.anizen.dev`.

### Logs

Vous pouvez utiliser `Logcat` dans Android Studio pour voir les logs imprimés par votre extension.

### Inspection des appels réseau

Activez le "Verbose logging" dans les paramètres avancés de l'application pour voir tous les appels OkHttp dans le Logcat. Vous pouvez filtrer par le tag `OkHttpClient`.

## Compilation (Build)

Les APK peuvent être créés via `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
En ligne de commande :
```bash
./gradlew src:<lang>:<source>:assembleDebug
```

## Soumettre les modifications

Une fois confiant, soumettez une Pull Request. Suivez le flux de travail standard de GitHub (Fork & Pull Request). **Ne commitez pas directement sur `main`**, créez toujours une nouvelle branche.

> [!IMPORTANT]
> N'oubliez pas de générer l'icône de l'extension. Elle doit être un carré avec des coins arrondis. Supprimez le fichier `web_hi_res_512.png` généré.

Veuillez tester vos modifications en compilant via Android Studio avant de soumettre. Les PR non testées ne seront pas fusionnées.
