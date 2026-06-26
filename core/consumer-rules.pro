# ============================================================================
# Consumer ProGuard/R8 Rules for core module (fr.bluecxt.core)
# These rules are automatically applied to every extension that depends on :core.
#
# Stratégie : On protège UNIQUEMENT ce qui doit l'être (API publique, modèles,
# sérialisation, réflexion). Tout le reste (extracteurs, utilitaires internes)
# est éligible au tree-shaking par R8, ce qui réduit la taille des APK.
# ============================================================================

# ===========================================================================
# 1. MODÈLES / API PUBLIQUE - Utilisés par l'app hôte (AniZen/Aniyomi)
#    via réflexion ou sérialisation. Doivent être conservés intégralement.
# ===========================================================================

# --- Interfaces et implémentations des modèles de données ---
# L'app hôte instancie SAnimeImpl/SEpisodeImpl via SAnime.create()/SEpisode.create()
# et accède aux propriétés par réflexion (ex: setFetch_type, setSeason_number).
-keep class eu.kanade.tachiyomi.animesource.** { *; }

# --- Classes de sérialisation kotlinx.serialization ---
# Video, SerializableVideo, Hoster, SerializableHoster, Track, TimeStamp,
# ChapterType, VideoType, FetchType utilisent @Serializable.
# Le plugin kotlinx-serialization génère des classes $$serializer compagnons.
-keepclassmembers class eu.kanade.tachiyomi.animesource.model.** {
    *** Companion;
}
-keep class eu.kanade.tachiyomi.animesource.model.**$$serializer { *; }

# ===========================================================================
# 2. CLASSES CORE (fr.bluecxt.core) - API publique utilisée par les extensions
#    On garde les classes/interfaces publiques mais PAS les classes internes
#    (extracteurs) afin de permettre le tree-shaking.
# ===========================================================================

# --- Classe de base Source (héritée par chaque extension) ---
-keep class fr.bluecxt.core.Source { *; }

# --- Interface CommonPreferences (implémentée par les extensions) ---
-keep class fr.bluecxt.core.CommonPreferences { *; }
-keep class fr.bluecxt.core.CommonPreferences$Companion { *; }

# --- Modèles du core ---
-keep class fr.bluecxt.core.model.ExtractedSource { *; }
-keep class fr.bluecxt.core.model.VideoServer { *; }

# --- Exceptions custom (nommées par classe dans les catch/throw) ---
-keep class fr.bluecxt.core.ContentUnavailableException { *; }
-keep class fr.bluecxt.core.RateLimitException { *; }
-keep class fr.bluecxt.core.ExtractionException { *; }
-keep class fr.bluecxt.core.SelectorException { *; }

# --- TMDB (utilisé directement par les extensions via import) ---
-keep class fr.bluecxt.core.TmdbMetadata { *; }

# --- Constantes et fonctions utilitaires publiques ---
# On garde les top-level functions/properties car elles sont appelées par les extensions.
# Le fichier Const.kt, Helper.kt, Servers.kt exportent des fonctions/constantes.
-keep class fr.bluecxt.core.ConstKt { *; }
-keep class fr.bluecxt.core.HelperKt { *; }
-keep class fr.bluecxt.core.TmdbKt { *; }

# --- Servers.kt : factory function getVideoServer() ---
# IMPORTANT : Cette fonction contient un `when` qui référence les extracteurs.
# R8 analysera les branches du `when` effectivement atteintes à travers le call graph.
# Les extracteurs NON utilisés par une extension seront éliminés par tree-shaking.
-keep class fr.bluecxt.core.ServersKt { *; }

# --- Liste DEFAULT_SERVER (utilisée par CommonPreferences.supportedServers) ---
# Déjà couverte par ServersKt ci-dessus.

# --- Network (CloudflareInterceptor utilisé dans Source.client) ---
-keep class fr.bluecxt.core.network.CloudflareInterceptor { *; }

# ===========================================================================
# 3. EXTRACTEURS - PAS DE -keep !
#    Les extracteurs (fr.bluecxt.core.extractors.*) ne sont PAS protégés ici.
#    Ils sont référencés uniquement via getVideoServer() dans Servers.kt.
#    R8 analysera les branches du `when` effectivement exécutées dans le
#    call graph de chaque extension et éliminera les extracteurs non utilisés.
#
#    Cependant, les classes @Serializable internes aux extracteurs doivent
#    être protégées pour la désérialisation JSON.
# ===========================================================================

# Protège les data classes @Serializable imbriquées dans les extracteurs
# (ex: FilemoonExtractor$JWPlayerData, VidaraExtractor$..., MinochinosExtractor$...)
-keepclassmembers class fr.bluecxt.core.extractors.**$* {
    *** Companion;
}
-keep class fr.bluecxt.core.extractors.**$*$$serializer { *; }
-keepnames class fr.bluecxt.core.extractors.**$* {
    <init>(...);
}

# ===========================================================================
# 4. UTILITAIRES KEIYOUSHI - Fonctions d'extension utilisées par les extensions
# ===========================================================================

-keep class keiyoushi.utils.** { *; }
-keep class keiyoushi.core.BuildConfig { *; }

# ===========================================================================
# 5. UTILITAIRES CORE (PlaylistUtils, VideoUtils, Unpacker)
#    Gardés car potentiellement utilisés par les extracteurs actifs.
# ===========================================================================

-keep class fr.bluecxt.core.utils.PlaylistUtils { *; }
-keep class fr.bluecxt.core.utils.VideoUtils { *; }
-keep class fr.bluecxt.core.utils.unpacker.** { *; }

# ===========================================================================
# 6. KOTLINX.SERIALIZATION - Règles génériques
# ===========================================================================

# Garde les signatures génériques (obligatoire pour Injekt TypeReference et kotlinx.serialization)
-keepattributes Signature,*Annotation*,InnerClasses

# Injekt construit ses clés via object : FullTypeReference<T>() et lit
# javaClass.getGenericSuperclass() au runtime. En mode R8 optimisé, garder
# l'attribut Signature ne suffit pas si ces sous-classes anonymes ne sont
# pas elles-mêmes protégées.
-keep class * extends uy.kohesive.injekt.api.FullTypeReference { *; }

# Sérializers générés par le plugin
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keep class **$$serializer { *; }

# ===========================================================================
# 7. RÉFLEXION - Utilisée dans Source.kt
# ===========================================================================

# Source.coreSetFetchType() et coreSetSeasonNumber() utilisent la réflexion
# pour appeler setFetch_type et setSeason_number sur les objets SAnime.
# Déjà couvert par la règle eu.kanade.tachiyomi.animesource.** ci-dessus.

# Source.logUsage() utilise this.javaClass.package?.name
# Couvert car Source est déjà -keep.
