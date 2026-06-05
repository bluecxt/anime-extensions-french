# TODO - Refactorisation et Dette Technique

## Priorité Basse / Maintenance
- [ ] **Optimisation ProGuard/R8** : Affiner les règles `proguard-rules.pro` pour permettre la suppression (tree-shaking) des extracteurs centralisés dans le core qui ne sont pas utilisés par une extension spécifique.
- [ ] **Migration des modèles Video** : Remplacer l'utilisation de la propriété dépréciée `.quality` par `.videoTitle` dans toutes les extensions. 
  - *Note : Le core a été aligné sur les modèles d'aniZen, la compatibilité est assurée par des alias, mais le renommage propre est préférable à long terme.*

## Terminé
- [x] Alignement du module `core` sur les modèles `Video`, `Hoster` et `VideoType` d'aniZen.
- [x] Création du helper `addBaseUrlPreference` pour centraliser la gestion des URLs de base.
- [x] Création du helper `withDefaultHeaders` pour sécuriser les headers des vidéos.
- [x] Harmonisation des User-Agents via `DEFAULT_USER_AGENT`.
