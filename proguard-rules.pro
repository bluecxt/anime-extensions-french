# 1. Conservation des classes vitales (Tachiyomi/Keiyoushi)
# On garde les classes principales pour que l'app puisse charger l'extension
-keep class eu.kanade.tachiyomi.animeextension.** { *; }
-keep class eu.kanade.tachiyomi.animesource.** { *; }
-keep class fr.bluecxt.core.** { *; }

# Désactiver l'obfuscation (renommage) pour éviter les VerifyError dans l'inspector
-dontobfuscate

# 2. Sérialisation (Kotlinx Serialization)
# Indispensable pour que le JSON fonctionne toujours après optimisation
-keep @kotlinx.serialization.Serializable class * { *; }
-keep class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# 3. Optimisations de performance
# On reste conservateur pour éviter de casser la compatibilité avec l'inspector
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# 4. Suppression des Logs (Nettoyage total en Release)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# 5. Optimisations spécifiques à Kotlin
# Supprime les vérifications de nullité redondantes générées par Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}
