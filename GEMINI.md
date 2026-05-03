# 📜 Règles d'Harmonisation des Extensions Françaises

Ce document définit les standards obligatoires pour toutes les extensions Aniyomi du dépôt francophone bluecxt.

## 1. Formatage des Épisodes
- **Terminologie Anglaise :** Utiliser les mots-clés reconnus par AniZen entre crochets : `[S1]` (pour Season 1), `[Movie]`, `[OVA]`, `[ONA]`, `[Special]`.
- **Structure Nom :** `[S1] Episode Y - [Titre]` ou `Episode Y - [Titre]`.
- **Contenu Spécial :** Toujours préfixer par `[Movie]`, `[OVA]` ou `[Special]` (ex: `[Movie] Episode 1 - [Titre]`) pour forcer le groupement spécial d'AniZen.
- **Saison Unique :** Si l'animé ne possède qu'une seule saison, omettre le préfixe `[S1]`.
- **Pas de Redondance :** Si le titre fourni par le site contient déjà le numéro de l'épisode, ne pas répéter `Episode X` au début.

## 2. Libellés des Sources Vidéo
- **Format Standard :** `(Langue) Serveur - Qualité` ou `(Langue) Team Serveur - Qualité`.
- **Tags de Langue :** `(VOSTFR)`, `(VF)`, `(VA)`.
- **Nettoyage (Regex) :**
    - Supprimer les répétitions de noms de serveurs (ex: `Vidmoly - Vidmoly` -> `Vidmoly`).
    - Retirer les informations de poids de fichier (ex: `- 300MB`).
    - Supprimer les suffixes techniques (`:default`, `default`).
    - Inclure le nom de la team de fansub si disponible (obligatoire pour Anime-Ultime).

## 3. Tri et Priorisation
- **Langue :** Trier les vidéos en fonction de la préférence linguistique définie dans les réglages de l'extension.
- **Résolution :** À langue identique, trier par résolution décroissante (ex: `1080p` > `720p` > `480p`).
- **Parsing de Qualité :** Utiliser une Regex pour extraire le nombre de pixels (ex: `(\d+)p`) afin de garantir un tri numérique fiable.

## 4. Métadonnées (TMDB)
- **Date de Sortie :** Toujours utiliser le libellé exact `Date de sortie :` (au début de la description) pour garantir la cohérence entre les sources.
- **Studios/Créateurs :** Utiliser les champs `Artist` (pour les studios) et `Author` (pour les créateurs/mangakas) fournis par le moteur TMDB.
