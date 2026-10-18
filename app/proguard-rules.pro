# Sephile ProGuard/R8 rules
# Keep JNI entry points so the native bridge is not stripped/renamed.
-keep class com.sephile.app.root.NativeBridge {
    native <methods>;
}
-keepclasseswithmembernames class com.sephile.app.root.NativeBridge {
    native <methods>;
}

# Keep model classes used reflectively
-keep class com.sephile.app.model.** { *; }

# Keep entry points
-keep class com.sephile.app.SephileApp { *; }
-keep class com.sephile.app.ui.MainActivity { *; }

# R8 may remove unused native libs; keep them
-keepattributes *Annotation*, Signature
-keepattributes SourceFile, LineNumberTable
