# État de l'avancement - Extension Anime-Sama

## 🚀 Ce qui a été fait
- **Extracteurs mis à jour :**
    - **VidMoly :** Passage au domaine `.biz` et gestion des nouvelles protections JS.
    - **VidHide :** Intégration de l'AutoUnpacker de Keiyoushi pour déchiffrer les scripts obfusqués.
    - **VoE, Sibnet, Sendvid, VK, Dood, StreamTape :** Mise à jour vers les dernières versions de la logique d'extraction.
    - **Embed4me :** Intégration complète du nouvel extracteur avec support pour `seekstreaming`.
- **Core & Utilitaires :**
    - Importation de `keiyoushi.utils` (Coroutines, Network, UrlUtils) pour une meilleure stabilité.
    - Mise à jour de l'objet `Video` pour être compatible avec les nouveaux standards.
- **Correction des Hubs :**
    - **Filtre anti-boucle :** Les Hubs ne se listent plus eux-mêmes comme des saisons (exclusion des liens racines comme `/vostfr/`).
    - **Logs de Debug :** Ajout de logs `AnimeSamaDebug` pour tracer l'extraction du nom, des saisons et des liens vidéos.

## 🛠️ En cours / À faire
- **Logique de Nommage des Saisons :**
    - [ ] Finaliser la règle stricte : Saison 1 = "Nom de l'Anime", Saison X = "Nom de l'Anime X".
    - [ ] S'assurer que les versions "Kai" et "Director's Cut" gardent toujours le nom complet sans être tronquées.
- **Fix "Part 10 / 20" :**
    - [ ] Vérifier que l'assignation du numéro de saison `0.0` (Specials) règle définitivement le problème des labels générés par l'app.
- **Validation Finale :**
    - [ ] Tester sur plusieurs séries (Re:Zero, One Piece, etc.) via les logs pour confirmer que tout est propre.

## 📝 Notes techniques
Les modifications sont compilables via `./gradlew assembleDebug`.
Le tag de log à surveiller est : `AnimeSamaDebug`.
