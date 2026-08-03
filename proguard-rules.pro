# ============================================================
# AMap Max Companion — ProGuard / R8 rules
# 手写构建链（aapt2 + javac + R8），发布版启用混淆 + 资源收缩
# ============================================================

# ---- Android 组件（manifest 按类名实例化，必须 keep，防混淆）----
-keep class com.autonavi.companion.MainActivity { *; }
-keep class com.autonavi.companion.StartServiceActivity { *; }
-keep class com.autonavi.companion.OverlayService { *; }
-keep class com.autonavi.companion.BootReceiver { *; }

# 保险：所有组件子类保持原名
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# ---- R 类（资源 ID 常量；R8 会 shrink 未用字段，显式 keep 更稳）----
-keep class com.autonavi.companion.R { *; }
-keepclassmembers class com.autonavi.companion.R$* { public static final int *; }

# ---- BuildConfig（BUILD_TIME 被 UI 展示引用）----
-keep class com.autonavi.companion.BuildConfig { *; }

# ---- 通用属性（注解/泛型/内部类元数据）----
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 发布版裁剪日志（减小 dex + 消除字符串），不影响控制流 ----
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    public static int w(...);
    public static int e(...);
}
