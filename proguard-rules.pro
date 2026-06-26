# ============================================================================
# ProGuard/R8 Rules - Project Root
# Applied to ALL extension builds via common.gradle.
#
# AVANT : `-keep class ** { *; }` gardait TOUT → aucun tree-shaking.
# MAINTENANT : On laisse R8 faire son travail d'optimisation.
# Les règles spécifiques au core sont dans core/consumer-rules.pro
# et sont automatiquement propagées via consumerProguardFiles.
# ============================================================================

# --- Pas d'obfuscation (les extensions chargent les classes par nom) ---
-dontobfuscate

# --- Suppression des logs de debug en release ---
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# ===========================================================================
# EXTENSION ENTRY POINTS
# L'app hôte (AniZen/Aniyomi) charge les extensions par nom de classe
# via le Manifest (extClass). On doit garder toutes les classes racines
# des extensions.
# ===========================================================================

# Garde les classes d'extension déclarées dans le Manifest
-keep class eu.kanade.tachiyomi.animeextension.** extends eu.kanade.tachiyomi.animesource.online.AnimeHttpSource { *; }
-keep class eu.kanade.tachiyomi.animeextension.** extends fr.bluecxt.core.Source { *; }

# Garde aussi les AnimeSources (interface)
-keep class * implements eu.kanade.tachiyomi.animesource.AnimeSource { *; }
-keep class * implements eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource { *; }

# ===========================================================================
# KOTLINX.SERIALIZATION - Support global
# ===========================================================================

-keepattributes Signature,*Annotation*,InnerClasses
-keepattributes EnclosingMethod

# Les classes @Serializable dans les extensions elles-mêmes
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keep class **$$serializer { *; }

# Kotlin Metadata (nécessaire pour la sérialisation)
-keep class kotlin.Metadata { *; }

# ===========================================================================
# KOTLIN COROUTINES
# ===========================================================================

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ===========================================================================
# OKHTTP / JSOUP (utilisés dans les extensions)
# ===========================================================================

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**

# ===========================================================================
# INJEKT (dependency injection utilisée dans les extensions)
# ===========================================================================

-keep class uy.kohesive.injekt.** { *; }

# Injekt lit le type générique de ses objets FullTypeReference au runtime
# via javaClass.getGenericSuperclass(). R8 doit donc garder ces sous-classes
# anonymes avec leur attribut Signature.
-keep class * extends uy.kohesive.injekt.api.FullTypeReference { *; }

# ===========================================================================
# LIB EXTRACTORS (lib-multisrc et extracteurs individuels dans /lib/)
# Même stratégie : les libs sont référencées via l'arbre d'appels.
# On protège seulement les classes @Serializable internes.
# ===========================================================================

-keepclassmembers @kotlinx.serialization.Serializable class eu.kanade.tachiyomi.lib.** {
    *** Companion;
}
-keep class eu.kanade.tachiyomi.lib.**$$serializer { *; }
