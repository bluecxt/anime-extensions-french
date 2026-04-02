<div align="center">

# 🇫🇷 bluecxt french repo 🇫🇷
### Repository regroupant des modules de parsing Java/Kotlin pour l'agrégation de métadonnées de médias francophones. Optimisé pour les environnements basés sur l'architecture Anizen.

</div>

---

## 📌 Configuration du Repository

Pour intégrer ce repository à votre environnement de test, utilisez l'URL de métadonnées brute suivante :

```text
https://raw.githubusercontent.com/bluecxt/aniyomi-extensions-french-only/repo/index.min.json
```

## 🛠️ Développement Local et Validation

Ce repository inclut des outils pour valider les scrapers de métadonnées.

### 🧪 Validation des Scrapers
Chaque module dispose d'un validateur qui vérifie si le flux de données distant est toujours conforme aux schémas attendus.

**Lancer tous les tests :**
```bash
python3 audit_extensions.py
```

### 🔨 Build des Artifacts
Pour compiler un module spécifique (Android Debug APK) :
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk # Si nécessaire
./gradlew :src:fr:exemple:assembleDebug
```

## ✅ État des Media Handlers (Mars 2026)

Le repository a été optimisé pour ne conserver que les scrapers les plus performants.

| Scraper | État |
| :--- | :--- |
| **Provider-AS** | ✅ Opérationnel |
| **Provider-AS (RIP)** | ✅ Opérationnel |
| **Provider-AK** | ✅ Opérationnel |
| **Provider-AF** | ✅ Opérationnel |
| **Provider-AU** | ✅ Opérationnel |
| **Provider-FA** | ✅ Opérationnel |
| **Provider-FM** | ✅ Opérationnel |
| **Provider-FR** | ✅ Opérationnel |
| **Provider-VA** | ✅ Opérationnel |
| **Provider-ST** | ✅ Opérationnel |
| **Provider-WA** | ✅ Opérationnel |
| **Provider-UX** | ✅ Opérationnel |

---

