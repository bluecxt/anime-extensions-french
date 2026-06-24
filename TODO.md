# TODO - Refactorisation et Dette Technique

## Priorité Haute / Performance
- [ ] **Migration vers le Non-Bloquant** : Convertir tous les appels `client.newCall(...).execute()` restants en `.awaitSuccess()` ou `.await()` dans toutes les extensions (`src/fr/`).
  - *Objectif : Libérer les threads des coroutines pour permettre un chargement véritablement parallèle des serveurs et éviter les délais de 30s.*

## Priorité Basse / Maintenance
- [ ] **Optimisation ProGuard/R8** : Affiner les règles `proguard-rules.pro` pour permettre la suppression (tree-shaking) des extracteurs centralisés dans le core qui ne sont pas utilisés par une extension spécifique.
- [ ] **Migration des modèles Video** : Remplacer l'utilisation de la propriété dépréciée `.quality` par `.videoTitle` dans toutes les extensions. 
  - *Note : Le core a été aligné sur les modèles d'aniZen, la compatibilité est assurée par des alias, mais le renommage propre est préférable à long terme.*

## a migrer vers core extractor
- [x] animesamafan
- [x] waveanime
- [x] southtv
- [x] lesporoiniens
- [x] torrentio
- [x] torrentio anime
- [x] animesama
- [x] animoflix
- [x] franime
- [x] frenchanime
- [ ] frenchmanga
- [ ] movix
- [x] voiranime
- [x] wiflix
- [x] adkami