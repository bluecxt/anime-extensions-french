# Suivi des Métadonnées des Extensions

Ce fichier répertorie la source d'information utilisée par chaque extension pour remplir les détails des animes.
TODO : ajouter les informations manquantes avec l'API Anilist "https://anilist.co/graphiql"

## Common Structure
- **Title** : Nom de l'œuvre.
- **Author** : Généralement le studio ou le créateur original.
- **Artist** : Illustrateur ou directeur d'animation (si applicable).
- **Description** : Synopsis ou résumé de l'anime.
- **Genre** : Catégories/Tags associés à l'anime.
- **Status** : Statut actuel (Ongoing, Completed, On Hiatus, etc.).
- **Release Date** : Année ou date de sortie.
- **Thumbnail** : URL de l'image de couverture de l'anime.
- **Episode Thumbnail** : Image pour les épisodes individuels (par défaut : `TMDB`).
- **Episode Summary** : Description pour les épisodes individuels (par défaut : `TMDB`).
- **Provider** : La source des données (par défaut : `Self` pour le site source lui-même).

---

## ADKami
- [x] **Title**: [Provider: Self]
- [x] **Author**: [Provider: Self]
- [x] **Artist**: [Provider: Self]
- [x] **Description**: [Provider: Self]
- [x] **Genre**: [Provider: Self]
- [ ] **Status**: [Provider: Self]
- [x] **Release Date**: [Provider: Self]
- [x] **Thumbnail**: [Provider: Self]
- [ ] **Episode Thumbnail**: [Provider: None]
- [ ] **Episode Summary**: [Provider: None]

## Anime-Sama
- [x] **Title**: [Provider: Self]
- [x] **Author**: [Provider: TMDB]
- [x] **Artist**: [Provider: TMDB]
- [x] **Description**: [Provider: TMDB/Self]
- [x] **Genre**: [Provider: TMDB/Self]
- [x] **Status**: [Provider: TMDB/Self]
- [x] **Release Date**: [Provider: TMDB]
- [x] **Thumbnail**: [Provider: TMDB/Self]
- [x] **Episode Thumbnail**: [Provider: TMDB]
- [x] **Episode Summary**: [Provider: TMDB]

## AnimeSamaFan
- [x] **Title**: [Provider: Self]
- [x] **Author**: [Provider: TMDB]
- [x] **Artist**: [Provider: TMDB]
- [x] **Description**: [Provider: TMDB]
- [x] **Genre**: [Provider: Self/TMDB]
- [x] **Status**: [Provider: TMDB]
- [x] **Release Date**: [Provider: TMDB]
- [x] **Thumbnail**: [Provider: TMDB]
- [x] **Episode Thumbnail**: [Provider: TMDB]
- [x] **Episode Summary**: [Provider: TMDB]

## AnimesUltra
- [x] **Title**: [Provider: Self]
- [ ] **Author**: [Provider: Self]
- [ ] **Artist**: [Provider: Self]
- [ ] **Description**: [Provider: Self]
- [ ] **Genre**: [Provider: Self]
- [x] **Status**: [Provider: Self]
- [ ] **Release Date**: [Provider: Self]
- [x] **Thumbnail**: [Provider: Self]
- [ ] **Episode Thumbnail**: [Provider: TMDB]
- [ ] **Episode Summary**: [Provider: TMDB]

## Anime-Ultime
- [x] **Title**: [Provider: Self]
- [ ] **Author**: [Provider: Self]
- [ ] **Artist**: [Provider: Self]
- [x] **Description**: [Provider: Self]
- [x] **Genre**: [Provider: Self]
- [ ] **Status**: [Provider: Self]
- [x] **Release Date**: [Provider: Self]
- [x] **Thumbnail**: [Provider: Self]
- [ ] **Episode Thumbnail**: [Provider: TMDB]
- [ ] **Episode Summary**: [Provider: TMDB]

## AnimoFlix
- [x] **Title**: [Provider: Self]
- [x] **Author**: [Provider: TMDB]
- [x] **Artist**: [Provider: TMDB]
- [x] **Description**: [Provider: TMDB]
- [x] **Genre**: [Provider: Self]
- [x] **Status**: [Provider: TMDB]
- [x] **Release Date**: [Provider: TMDB]
- [x] **Thumbnail**: [Provider: TMDB]
- [x] **Episode Thumbnail**: [Provider: TMDB]
- [x] **Episode Summary**: [Provider: TMDB]

## FrAnime
- [x] **Title**: [Provider: Self]
- [x] **Author**: [Provider: TMDB]
- [x] **Artist**: [Provider: TMDB]
- [x] **Description**: [Provider: TMDB/Self]
- [x] **Genre**: [Provider: Self]
- [x] **Status**: [Provider: Self]
- [x] **Release Date**: [Provider: TMDB]
- [x] **Thumbnail**: [Provider: TMDB]
- [x] **Episode Thumbnail**: [Provider: TMDB]
- [x] **Episode Summary**: [Provider: TMDB]

## French Anime
- [x] **Title**: [Provider: Self]
- [ ] **Author**: [Provider: Self]
- [ ] **Artist**: [Provider: Self]
- [x] **Description**: [Provider: Self]
- [x] **Genre**: [Provider: Self]
- [ ] **Status**: [Provider: Self]
- [x] **Release Date**: [Provider: Self]
- [x] **Thumbnail**: [Provider: Self]
- [ ] **Episode Thumbnail**: [Provider: TMDB]
- [ ] **Episode Summary**: [Provider: TMDB]

## French-Manga
- [x] **Title**: [Provider: Self]
- [ ] **Author**: [Provider: Self]
- [ ] **Artist**: [Provider: Self]
- [x] **Description**: [Provider: Self]
- [x] **Genre**: [Provider: Self]
- [x] **Status**: [Provider: Self]
- [x] **Release Date**: [Provider: Self]
- [x] **Thumbnail**: [Provider: Self]
- [ ] **Episode Thumbnail**: [Provider: TMDB]
- [ ] **Episode Summary**: [Provider: TMDB]

## Les Poroïniens
- [x] **Title**: [Provider: Self]
- [ ] **Author**: [Provider: Self]
- [ ] **Artist**: [Provider: Self]
- [x] **Description**: [Provider: Self]
- [x] **Genre**: [Provider: Self]
- [ ] **Status**: [Provider: Self]
- [ ] **Release Date**: [Provider: Self]
- [x] **Thumbnail**: [Provider: Self]
- [ ] **Episode Thumbnail**: [Provider: TMDB]
- [ ] **Episode Summary**: [Provider: TMDB]

## Movix
- [x] **Title**: [Provider: Self]
- [x] **Author**: [Provider: TMDB]
- [x] **Artist**: [Provider: TMDB]
- [x] **Description**: [Provider: TMDB]
- [x] **Genre**: [Provider: Self]
- [x] **Status**: [Provider: None]
- [x] **Release Date**: [Provider: TMDB]
- [x] **Thumbnail**: [Provider: TMDB/Self]
- [x] **Episode Thumbnail**: [Provider: TMDB]
- [x] **Episode Summary**: [Provider: TMDB]

## SouthTV
- [x] **Title**: [Provider: Self]
- [x] **Author**: [Provider: TMDB]
- [x] **Artist**: [Provider: TMDB]
- [x] **Description**: [Provider: TMDB]
- [x] **Genre**: [Provider: TMDB]
- [x] **Status**: [Provider: TMDB]
- [x] **Release Date**: [Provider: TMDB]
- [x] **Thumbnail**: [Provider: TMDB]
- [x] **Episode Thumbnail**: [Provider: TMDB]
- [x] **Episode Summary**: [Provider: TMDB]

## VoirAnime
- [x] **Title**: [Provider: Self]
- [ ] **Author**: [Provider: Self]
- [ ] **Artist**: [Provider: Self]
- [x] **Description**: [Provider: Self]
- [x] **Genre**: [Provider: Self]
- [ ] **Status**: [Provider: Self]
- [x] **Release Date**: [Provider: Self]
- [x] **Thumbnail**: [Provider: Self]
- [ ] **Episode Thumbnail**: [Provider: TMDB]
- [ ] **Episode Summary**: [Provider: TMDB]

## Torrentio
- [x] **Title**: [Provider: JustWatch]
- [x] **Author**: [Provider: JustWatch]
- [x] **Artist**: [Provider: JustWatch]
- [x] **Description**: [Provider: JustWatch]
- [x] **Genre**: [Provider: JustWatch]
- [ ] **Status**: [Provider: None]
- [ ] **Release Date**: [Provider: JustWatch]
- [x] **Thumbnail**: [Provider: JustWatch]
- [x] **Episode Thumbnail**: [Provider: TMDB]
- [x] **Episode Summary**: [Provider: TMDB]

## Torrentio Anime
- [x] **Title**: [Provider: Anilist]
- [x] **Author**: [Provider: Anilist]
- [ ] **Artist**: [Provider: None]
- [x] **Description**: [Provider: Anilist]
- [x] **Genre**: [Provider: Anilist]
- [x] **Status**: [Provider: Anilist]
- [x] **Release Date**: [Provider: Anilist]
- [x] **Thumbnail**: [Provider: Anilist]
- [ ] **Episode Thumbnail**: [Provider: None]
- [ ] **Episode Summary**: [Provider: None]
