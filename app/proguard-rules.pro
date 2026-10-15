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

# ---------- Rhino（本地 JS 执行）----------
# Rhino 大量使用反射与运行时字节码生成（ClassFileWriter），混淆后会直接崩，
# 且 debug 包不开 R8 测不出来 —— 必须在 release 上验证。
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
# core 包里有引用了桌面 JDK 类型的可选分支，Android 上不存在，忽略即可
-dontwarn java.beans.**
-dontwarn java.awt.**
-dontwarn javax.script.**
-dontwarn org.mozilla.classfile.**

# ---------- Chaquopy（仅 full 包含 Python 时存在）----------
# 引擎通过反射调用（Class.forName / getMethod），类名是字符串字面量，
# R8 看不到引用就会整包剥掉 —— 保留整个包。
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ---------- 关闭日志（release 移除 android.util.Log）----------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
