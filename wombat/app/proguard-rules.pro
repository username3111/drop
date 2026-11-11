-repackageclasses 'z'
-allowaccessmodification
-optimizationpasses 5
-renamesourcefileattribute S
-keepattributes Signature,Exceptions,*Annotation*

# Manifest-referenced components
-keep class com.sync.media.hub.Bison   { *; }
-keep class com.sync.media.hub.Deer    { *; }
-keep class com.sync.media.hub.Mink    { *; }
-keep class com.sync.media.hub.Tern    { *; }
-keep class com.sync.media.hub.Ibis    { *; }
-keep class com.sync.media.hub.Pika    { *; }
-keep class com.sync.media.hub.Lemur   { *; }

-dontwarn java.util.jar.**
-dontwarn javax.crypto.**
-dontwarn com.android.apksig.**
-keep class com.android.apksig.** { *; }

# StringFog runtime
-keep class com.github.megatronking.stringfog.** { *; }
-dontwarn com.github.megatronking.stringfog.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.jvm.internal.** { *; }
-keepclassmembers class kotlin.Metadata { *; }

# Silence common warnings
-dontwarn javax.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}

# Custom dictionaries
-packageobfuscationdictionary dictionaries/package_dictionary.txt
-classobfuscationdictionary   dictionaries/class_dictionary.txt
-obfuscationdictionary        dictionaries/obfuscation_dictionary.txt
