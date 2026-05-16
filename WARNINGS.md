# Rapport des Avertissements (Compilation & Lint)

Ce document répertorie tous les avertissements actuels détectés lors de la compilation et du build du projet.

## 1. Avertissements de Compilation (Kotlin)

### lib:gdriveplayerextractor
- **Fichier** : `GdrivePlayerExtractor.kt:24:21`
- **Type** : `Inaccessible Nullable annotation`
- **Message** : Type annotation class 'Nullable' of the inferred type is inaccessible. Check the module classpath for missing or conflicting dependencies. This will become an error in language version 2.4.

### lib:googledriveextractor
- **Fichier** : `GoogleDriveExtractor.kt:39:19`
- **Type** : `Inaccessible Nullable annotation`
- **Message** : Type annotation class 'Nullable' of the inferred type is inaccessible.

### lib:streamsilkextractor
- **Fichier** : `JsHunter.kt:37:25`, `JsHunter.kt:38:25`
- **Type** : `Nullable receiver`
- **Message** : Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'.

### lib:vidhideextractor
- **Fichier** : `JsUnpacker.kt:31:31`, `JsUnpacker.kt:57:43` (Mismatch), `JsUnpacker.kt:33:31`, `36:30`, `40:29`, `44:29`, `64:57` (Nullable)
- **Type** : `Type mismatch / Nullable receiver`
- **Message** : Java type mismatch: inferred type is 'String?', but 'CharSequence' (or 'String') was expected. / Only safe (?.) or non-null asserted (!!.) calls are allowed.

### src:fr:adkami
- **Fichier** : `ADKami.kt:521:64`
- **Type** : `Inaccessible Nullable annotation`
- **Message** : Type annotation class 'Nullable' of the inferred type is inaccessible.

### src:fr:frenchmanga
- **Fichier** : `FrenchMangaExtractor.kt:62:66` (Unnecessary !!), `65:57` (Nullable)
- **Type** : `Cleanup / Nullable receiver`
- **Message** : Unnecessary non-null assertion (!!) on a non-null receiver / Only safe (?.) or non-null asserted (!!.) calls are allowed.

---

## 2. Rapports Lint (Extraits .txt)

### src:fr:waveanime
```text
WaveAnime.kt:56: Warning: Consider using apply() instead [ApplySharedPref]
WaveAnime.kt:69: Warning: Consider using apply() instead [ApplySharedPref]
```

### src:fr:voiranime
```text
VoirAnime.kt:272: Warning: Consider using apply() instead [ApplySharedPref]
tempAndroidManifest.xml:9: Warning: This intent filter... is missing the autoVerify attribute [AppLinkWarning]
tempAndroidManifest.xml:15, 19: Warning: Consider splitting data tag into multiple tags [IntentFilterUniqueDataAttributes]
res/mipmap-*/ic_launcher.png: Warning: Launcher icons should not fill every pixel... [IconLauncherShape]
```

### src:fr:southtv
```text
tempAndroidManifest.xml:11: Warning: This intent filter... is missing the autoVerify attribute [AppLinkWarning]
```

### src:fr:franime
```text
FrAnime.kt:97, 113: Warning: Consider using apply() instead [ApplySharedPref]
tempAndroidManifest.xml:12: Warning: This intent filter... is missing the autoVerify attribute [AppLinkWarning]
res/mipmap-*/ic_launcher.png: Warning: Launcher icons should not fill every pixel... [IconLauncherShape]
```

### src:fr:animoflix
```text
AnimoFlix.kt:83, 96, 109: Warning: Consider using apply() instead [ApplySharedPref]
```

### src:fr:animesamafan
```text
tempAndroidManifest.xml:9: Warning: This intent filter... is missing the autoVerify attribute [AppLinkWarning]
tempAndroidManifest.xml:15, 19, 23, 27, 32, 36, 40, 44: Warning: Consider splitting data tag... [IntentFilterUniqueDataAttributes]
res/mipmap-*/ic_launcher.png: Warning: Launcher icons should not fill every pixel... [IconLauncherShape]
```

### src:fr:adkami
```text
tempAndroidManifest.xml:12: Warning: This intent filter... is missing the autoVerify attribute [AppLinkWarning]
tempAndroidManifest.xml:18, 22, 26: Warning: Consider splitting data tag... [IntentFilterUniqueDataAttributes]
```

### src:fr:animesama
```text
tempAndroidManifest.xml:9: Warning: This intent filter... is missing the autoVerify attribute [AppLinkWarning]
tempAndroidManifest.xml:15, 19: Warning: Consider splitting data tag... [IntentFilterUniqueDataAttributes]
```
