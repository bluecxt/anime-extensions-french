# 📊 Matrice des Extensions & Fournisseurs de Données

Ce document récapitule l'ensemble des modules d'extensions du dépôt **anime-extensions-french**, leurs protocoles et formats sources exacts, ainsi que leurs fournisseurs d'enrichissement de métadonnées.

---

## 1. Vue d'Ensemble des Sources Francophones

| Extension | Domaine par Défaut | Type de Contenu | Format / Protocole Source | Fournisseur Principal | Enrichissement Métadonnées | Épisodes (Images / Résumés) | Spécificités Techniques |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: | :--- |
| **Anime-Sama** | `anime-sama.to` | Animes | **Hybride HTML + JS Scripts** (`episodes.js`) | Site | **TVDB v4** + TMDB | TVDB | Scraping des variables JS `epsArray`, fallback Legacy URL automatique, détection saisons/OAV |
| **Anime-Sama (FAN)** | `animesama.co` | Animes | **Scraping HTML Jsoup** | Site | **TMDB** | TMDB | Source miroir de secours, sélecteurs DOM directs |
| **ADKami** | `hentai.adkami.com` | Hentai / Adulte | **Scraping HTML Jsoup** | Site | **Self** | Non | Catalogue adulte +18 exhaustif, navigation DOM |
| **AnimoFlix** | `animoflix.to` | Animes & Cartoons | **API AJAX JSON** (`/catalogue/?ajax=1`) | Site | **TMDB** | TMDB | Support des cartoons et séries d'animation, JSON pour le catalogue |
| **Anime-Ultime** | `v5.anime-ultime.net` | Animes (Fansub) | **API AJAX JSON (POST)** + HTML Détails | Site | **Self** | TMDB | `/SeriesResults.html` et `/VideoPlayer.html` en JSON sérialisé (`decodeFromStringFixed`), attribution fansub |
| **AnimesUltra** | `ww.animesultra.org` | Animes | **Scraping HTML Jsoup** | Site | **Self** | TMDB | Alternative de secours, extraction multi-versions (VF/VOSTFR) |
| **Dessin Anime** | `dessinanime.cc` | Dessins animés | **API REST JSON** (`/api/catalogue`, `/api/search`) | Site | **Self** | Non | API JSON Next.js pour le catalogue/recherche, HTML pour les pages médias |
| **FrAnime** | `franime.fr` | Animes & Films | **API REST JSON Pure** (`api.franime.fr/api/anime`) | Site | **TMDB** | TMDB | API REST structurée, zéro parsing HTML, haute fidélité |
| **French Anime** | `french-anime.com` | Animes | **Scraping HTML Jsoup** | Site | **TMDB** | TMDB | Parsing HTML DLE Engine standard |
| **French-Manga** | `w16.french-manga.net` | Animes | **Scraping HTML + AJAX Search** | Site | **TMDB** | TMDB | Moteur DLE, recherche AJAX (`/engine/ajax/search.php`), déduplication des versions |
| **French Stream** | `french-stream.one` | Films & Séries | **Hybride AJAX JSON + JS Static + HTML** | Site | **TMDB** | TMDB | Catalogue HTML, films via API JSON (`film_api.php`), séries via JS (`static/series/{id}.js`) |
| **Les Poroïniens** | `lesporoiniens.org` | Animes & DDL | **Fichiers JSON Statiques** (`/data/series/*.json`) | Site | **Self** | Non | Fansubbing haute fidélité, lecture directe des JSON de métadonnées |
| **Movix** | `movix.fun` | Films & Séries | **API REST TMDB Directe (JSON)** | **TMDB Direct** | **TMDB** | TMDB | Catalogue et recherche alimentés directement par l'API TMDB officielle |
| **Movix Anime** | `movix.fun` | Animes | **API REST TMDB Directe (JSON)** | **TMDB Direct** | **TMDB** | TMDB | Découverte TMDB ciblée animation japonaise |
| **PapaDuStream** | `papadustreami.online` | Séries streaming | **HTML + AJAX (POST/DLE)** (`getxfield`) | Site | **TVDB** | TVDB + Site | Décodage token AJAX `xfPageToken` / `getxfield` en POST |
| **SouthTV** | `southtv.fr` | South Park | **JSON Statique / TMDB Direct** | **TMDB Direct** | **TMDB** | TMDB | Intégrale South Park VF et films, métadonnées 100% TMDB |
| **VoirAnime** | `voiranime.io` | Animes | **Scraping HTML + AJAX Search JSON** | Site | **Self** | TMDB | Uniquement VOSTFR haute qualité, recherche AJAX WP (`admin-ajax.php`) |
| **VoirDrama** | `voirdrama.to` | Dramas asiatiques | **Scraping HTML Jsoup (Madara CMS)** | Site | **TMDB** | TMDB | Framework Madara, dramas asiatiques (VF/VOSTFR) |
| **WaveAnime** | `waveanime.fr` | Animes | **Scraping HTML Jsoup** | Site | **TMDB** | TMDB | Extraction DOM, flux optimisés au format DASH / MP4 |
| **Wiflix** | `flemmix.men` | Films & Séries | **Scraping HTML + AJAX Search** | Site | **TMDB** | TMDB | Recherche AJAX DLE (`user_hash`), catalogue films & séries |
| **Torrentio** | `torrentio.strem.fun` | Multi (Torrents) | **APIs JSON GraphQL + REST Stremio** | Stremio / JustWatch | **JustWatch** | TMDB | GraphQL JustWatch pour le catalogue, API REST Cinemeta/Torrentio pour les flux |
| **Torrentio Anime** | `torrentio.strem.fun` | Animes (Torrents) | **API GraphQL AniList + REST Torrentio** | **AniList GraphQL** | **AniList** | Non | GraphQL AniList pour le catalogue/détails, Torrentio pour les flux magnets/debrid |

---

## 2. Définition des Rôles des Fournisseurs

- **Self** : Les informations sont extraites directement du balisage HTML ou des APIs internes/fichiers JSON du site source.
- **TVDB v4** : Utilisé en priorité pour les animes japonais (titres officiels traduits en français, numérotation des saisons fidèle et résumés d'épisodes individuels).
- **TMDB** : Utilisé pour les films, séries occidentales et dramas asiatiques (posters haute résolution, statuts et dates de diffusion).
- **AniList** : Utilisé pour les métadonnées spécifiques aux animes dans Torrentio Anime via GraphQL.
