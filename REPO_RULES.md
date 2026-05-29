# 📜 Règles d'Harmonisation pour les Extensions Françaises

Ce document définit les normes obligatoires pour toutes les extensions Aniyomi dans le dépôt francophone de bluecxt. Ces règles garantissent une expérience utilisateur cohérente et professionnelle.

## 1. Formatage des Épisodes
- **Terminologie Standard :** Utilisez des mots-clés entre crochets reconnus par AniZen pour forcer le regroupement : `[S1]` (pour la Saison 1), `[Movie]`, `[OAV]`, `[ONA]`, `[Special]`.
- **Structure du Nom :** `[S1] Épisode Y - [Titre]` ou simplement `Épisode Y - [Titre]`.
- **Contenu Spécial :** Toujours préfixer avec `[Movie]`, `[OAV]`, ou `[Special]` (ex: `[Movie] Épisode 1 - [Titre]`).
- **Première/Unique Saison :** Omettez le préfixe `[S1]` pour la première ou l'unique saison d'un anime. Commencez à utiliser les préfixes à partir de `[S2]` pour les saisons suivantes.
- **Pas de Redondance :** Si le titre fourni par le site contient déjà le numéro de l'épisode, ne répétez pas `Épisode X` au début.

## 2. Libellés des Sources Vidéo
- **Format Standard :** `(Langue) Serveur - Qualité` ou `(Langue) Team Serveur - Qualité`.
- **Tags de Langue :** `(VOSTFR)`, `(VF)`, `(VA)`.
- **Nettoyage (Regex) :**
    - Supprimez les répétitions du nom du serveur (ex: `Vidmoly - Vidmoly` -> `Vidmoly`).
    - Supprimez les informations de poids de fichier (ex: `- 300MB`).
    - Supprimez les suffixes techniques (`:default`, `default`).
    - Incluez le nom de la team de fansub si disponible (obligatoire pour Anime-Ultime).

## 3. Regroupement par Hébergeur (Super Packs)
- **Consolidation :** Les hébergeurs doivent être regroupés par langue pour éviter de longues listes répétitives sur l'écran de sélection initial.
- **Convention de Nommage :** Utilisez le tag de langue en majuscules comme nom d'hébergeur (ex: `VOSTFR`, `VF`, `VA`).
- **Liste Unifiée :** Lorsqu'un groupe de langue est sélectionné, l'extension doit agréger tous les liens de serveurs disponibles pour cette langue dans une seule liste parfaitement triée.
- **Efficacité :** L'extraction pour tous les serveurs au sein d'un groupe doit idéalement être effectuée en parallèle pour garantir une expérience utilisateur rapide et réactive.

## 4. Tri et Priorisation
- **Langue :** Triez les vidéos en fonction de la préférence de langue définie dans les paramètres de l'extension.
- **Résolution :** Pour une même langue, triez par résolution décroissante (ex: `1080p` > `720p` > `480p`).
- **Analyse de la Qualité :** Utilisez une Regex pour extraire le nombre de pixels (ex: `(\d+)p`) afin de garantir un tri numérique fiable.

## 5. Métadonnées (TMDB)
- **Date de Sortie :** Utilisez toujours le libellé français exact `Date de sortie :` (au début de la description) pour garantir la cohérence entre les sources.
- **Studios/Créateurs :** Utilisez le champ `Artist` (pour les studios) et le champ `Author` (pour les créateurs/mangakas) fournis par le moteur TMDB.
- **Débordement de Saison :** Si une saison sur le site contient plus d'épisodes que ce qui est listé sur TMDB, mappez les épisodes supplémentaires à la Saison 0 de TMDB (Spéciaux/OAV).

## 6. Mécaniques Spécifiques aux Extensions

Certaines extensions implémentent une logique avancée pour gérer des structures de site spécifiques ou des besoins de filtrage particuliers.

### Anime-Sama
- **Analyse Intelligente des Titres :** Détecte automatiquement les mots-clés comme "Saison X", "OAV", ou "FILM" dans le titre pour cibler précisément la bonne catégorie TMDB (Saison TV, Saison 0, ou Film).
- **Décalage OAV Cumulatif :** Lorsqu'un débordement de saison se produit en Saison > 1, l'extension calcule un décalage cumulatif en comptant les épisodes "manquants" (OAV) dans toutes les saisons précédentes. Cela garantit que les épisodes de débordement sont mappés au bon index séquentiel dans la Saison 0 de TMDB.

### Anime-Ultime
- **Attribution Fansub :** Inclut obligatoirement le nom de la team de fansub dans les libellés vidéo pour respecter les normes communautaires du site source.
