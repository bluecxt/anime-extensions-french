# ============================================================================
# ProGuard/R8 Rules - Release Only
# ============================================================================

# --- Suppression des logs de debug en release ---
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
