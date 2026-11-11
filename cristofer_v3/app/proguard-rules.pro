-keep class **.JnkBuildTag { *; }
-keep class com.yourcompany.core.jni.NativeBridge { *; }
-keep class com.yourcompany.internal.SystemActionCallback { *; }
-keep interface com.yourcompany.internal.TaskResultListener { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-renamesourcefileattribute SourceFile
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

-keepclassmembers class * extends android.content.Context {
   public void *(android.view.View);
   public void *(android.view.MenuItem);
}

# OkHttp/okio: own AAR consumer rules handle what's needed
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.openjsse.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.apache.harmony.**
-dontwarn com.android.org.conscrypt.**

-dontwarn kotlin.**

# Coroutines: volatile fields needed for correct state machine behavior
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# WorkManager: workers are instantiated by class name via Class.forName
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-classobfuscationdictionary build/obf/dict_classes.txt
-obfuscationdictionary      build/obf/dict_methods.txt
-packageobfuscationdictionary build/obf/dict_packages.txt

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
