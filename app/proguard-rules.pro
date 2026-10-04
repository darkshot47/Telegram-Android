# ---------------------------------------------------------------------------
# TDLib native bindings.
#
# The Java API is called from native code and serialises objects through JNI
# using the generated class/field layout, therefore nothing under
# org.drinkless.tdlib may be renamed or removed.
# (The TDLib artifact already embeds these rules; they are repeated here as a
# safety net in case minification is enabled for a custom build variant.)
# ---------------------------------------------------------------------------
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# Keep annotations used by the generated API.
-dontwarn androidx.annotation.**

# FileProvider is referenced from the manifest.
-keep class androidx.core.content.FileProvider { *; }
