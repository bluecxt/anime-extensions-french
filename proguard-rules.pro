# 1. Conservation des classes vitales (Tachiyomi/Keiyoushi/Core)
-keep class eu.kanade.tachiyomi.animeextension.** { *; }
-keep class eu.kanade.tachiyomi.animesource.** { *; }
-keep class fr.bluecxt.core.** { *; }

# 2. Compatibilité avec l'Inspector (JVM Standard)
# Désactiver l'obfuscation et les optimisations de bytecode complexes
-dontobfuscate
-dontoptimize
-keepattributes *

# 3. Sécurité pour Kotlin (Éviter IllegalAccessError)
# On garde les structures Kotlin intactes pour que le chargement dynamique fonctionne
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# 4. Suppression des Logs (Nettoyage en Release)
# Note: Sans l'optimisation globale, on utilise une règle plus ciblée
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# 5. Sérialisation
-keep @kotlinx.serialization.Serializable class * { *; }
-keep class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
