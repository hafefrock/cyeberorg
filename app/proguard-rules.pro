# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Strip non-error Logcat calls in release/fast builds so sensitive keys, prompts,
# and terminal output never leak to system logs.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Preserve annotations and generic signatures needed by Retrofit and Moshi
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Moshi codegen + Kotlin reflection adapter compatibility
-keep class kotlin.Metadata { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>(...);
}
-keep class **JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
    public <init>(com.squareup.moshi.Moshi, java.lang.reflect.Type[]);
}
-keep class * extends com.squareup.moshi.JsonAdapter {
    public <init>(...);
}
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.Json <methods>;
}

# Keep AI/data serialization models and enums intact when minifying
-keep class com.example.ai.Gemini* { *; }
-keep class com.example.data.CommandSnippet { *; }
-keep class com.example.data.SnippetExportEnvelope { *; }
-keep class com.example.data.PromptHistoryItem { *; }
-keep class com.example.data.FallbackModelEntry { *; }
-keep class com.example.data.ApiKeyMetadata { *; }
-keep enum com.example.** { *; }

# Termux terminal emulator: JNI (libtermux.so) looks up classes/fields/methods by name,
# and TerminalSession/TerminalEmulator are accessed reflectively (FileDescriptor wrapping).
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# Retrofit & OkHttp
-keep interface com.example.ai.GeminiRetrofitApi { *; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_snuff.IgnoreJRERequirement
-dontwarn com.google.errorprone.annotations.**
