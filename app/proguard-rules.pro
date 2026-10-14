# ---------- 通用 ----------
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

# 保留行号便于崩溃定位（去掉可再减几 KB）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,AnnotationDefault

# ---------- kotlinx.serialization ----------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    public static ** serializer(...);
}
-keep,includedescriptorclasses class com.dshwatch.**$$serializer { *; }
-keepclassmembers class com.dshwatch.** {
    *** Companion;
}
-keepclasseswithmembers class com.dshwatch.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclasseswithmembers class okhttp3.internal.platform.** { *; }

# ---------- androidx.security.crypto（反射加载 Tink 实现）----------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**

# ---------- 关闭日志（release 移除 android.util.Log）----------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
