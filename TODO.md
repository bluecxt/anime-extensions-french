# TODO - Refactorisation et Dette Technique

## Priorité Basse / Maintenance
- [ ] **Optimisation ProGuard/R8** : Affiner les règles `proguard-rules.pro` pour permettre la suppression (tree-shaking) des extracteurs centralisés dans le core qui ne sont pas utilisés par une extension spécifique.
- [ ] **Migration des modèles Video** : Remplacer l'utilisation de la propriété dépréciée `.quality` par `.videoTitle` dans toutes les extensions. 
  - *Note : Le core a été aligné sur les modèles d'aniZen, la compatibilité est assurée par des alias, mais le renommage propre est préférable à long terme.*

## a migrer vers core extractor
- [x] animesamafan
- [x] waveanime
- [x] southtv
- [x] lesporoiniens
- [ ] torrentio
- [ ] torrentio anime
- [ ] adkami
- [x] animesama
- [ ] animoflix
- [ ] franime
- [ ] frenchanime
- [ ] frenchmanga
- [ ] movix
- [ ] voiranime
- [ ] wiflix