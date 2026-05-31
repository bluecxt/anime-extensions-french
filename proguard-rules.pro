-keep class ** { *; }
-dontobfuscate
-dontoptimize

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}