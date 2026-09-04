# ============================================
# Mirage Xposed Module - ProGuard Rules
# ============================================

# ----- 核心模块类 -----
-keep class wx.mirage.** { *; }

# ----- DexKit 库 -----
-keep class org.luckypray.dexkit.** { *; }

# ----- Gson 序列化 -----
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# Gson TypeToken 保护
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ----- Xposed Bridge 类 -----
-keep class de.robv.android.xposed.** { *; }
-keep class android.** { *; }

# ----- 保留注解 -----
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ----- 通用优化规则 -----
-keepclassmembers class * {
    native <methods>;
}

-keepclassmembers class * {
    public <init>(android.content.Context);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# ----- wxmask baseutils (jar, reflection) -----
-keep class com.lu.magic.** { *; }
-dontwarn com.lu.magic.**
-keep class com.lu.lposed.** { *; }
-dontwarn com.lu.lposed.**
