package com.autonavi.companion;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

/**
 * Centralized access to all SharedPreferences keys, broadcast action constants,
 * UI style constants, and preference accessor methods.
 *
 * Extracted from MainActivity to decouple OverlayService and other components
 * from the Activity class.
 */
public final class AppPrefs {

    private AppPrefs() {}

    // ── SharedPreferences name ───────────────────────────────────────────
    public static final String PREFS = "amap_companion";

    // ── Preference keys ──────────────────────────────────────────────────
    public static final String KEY_TARGET_PACKAGE               = "target_package";
    public static final String KEY_OVERLAY_SCALE_PERCENT        = "overlay_scale_percent";
    public static final String KEY_MAIN_OVERLAY_ENABLED         = "main_overlay_enabled";
    public static final String KEY_CLUSTER_MIRROR_ENABLED       = "cluster_mirror_enabled";
    public static final String KEY_OVERLAY_X                    = "overlay_x";
    public static final String KEY_OVERLAY_Y                    = "overlay_y";
    public static final String KEY_CLUSTER_X                    = "cluster_x";
    public static final String KEY_CLUSTER_Y                    = "cluster_y";
    public static final String KEY_CLUSTER_SCALE_PERCENT        = "cluster_scale_percent";
    public static final String KEY_CLUSTER_DISPLAY_ID           = "cluster_display_id";
    public static final String KEY_SHOW_LIGHT                 = "show_light";
    public static final String KEY_LIGHT_VERTICAL_CLUSTER          = "light_vertical_cluster";
    public static final String KEY_LIGHT_VERTICAL_MAIN             = "light_vertical_main";
    public static final String KEY_SHOW_LIGHT_DIRECTION         = "show_light_direction";
    public static final String KEY_LIGHT_BREATHING              = "light_breathing";
    public static final String KEY_TRANSPARENT_BACKGROUND       = "transparent_background";
    public static final String KEY_BACKGROUND_OPACITY_PERCENT   = "background_opacity_percent";
    public static final String KEY_TEXT_MODE                    = "text_mode";
    public static final String TEXT_MODE_FORCE_NIGHT              = "force_night";
    public static final String KEY_OVERLAY_UI_STYLE             = "overlay_ui_style";
    public static final String KEY_AUTO_START_ENABLED           = "auto_start_enabled";
    public static final String KEY_START_SERVICE_ON_APP_OPEN    = "start_service_on_app_open";
    public static final String KEY_LAUNCH_TARGET_FROM_DESKTOP   = "launch_target_from_desktop";
    public static final String KEY_SHOW_MAIN_WHEN_TARGET_FOREGROUND  = "show_main_when_target_foreground";
    public static final String KEY_HIDE_MAIN_WHEN_TARGET_FOREGROUND  = "hide_main_when_target_foreground";
    public static final String KEY_HIDE_CLUSTER_WHEN_INACTIVE   = "hide_cluster_when_inactive";
    public static final String KEY_EDOG_ALERT_BACKGROUND_OPACITY = "edog_alert_background_opacity";
    public static final String KEY_OVERSPEED_MILD_WARNING       = "overspeed_mild_warning";
    public static final String KEY_OVERSPEED_MEDIUM_WARNING     = "overspeed_medium_warning";

    // ── 极狐转向 HUD（arcfox-turn-hud 移植）─────────────────────────────
    // 沿用原版 key 名，便于从 Navi-Link 迁移配置；存储位置统一到本 PREFS
    public static final String KEY_TURN_SIGNAL_ENABLED          = "turn_signal_overlay_enabled";
    public static final String KEY_TURN_SIGNAL_COLOR            = "turn_signal_overlay_color";
    public static final String KEY_TURN_SIGNAL_EFFECT           = "turn_signal_overlay_effect";
    public static final String KEY_TURN_SIGNAL_ALPHA            = "turn_signal_overlay_alpha";
    public static final String KEY_TURN_SIGNAL_SIZE             = "turn_signal_overlay_size";
    public static final String KEY_TURN_SIGNAL_TOP              = "turn_signal_overlay_top";
    public static final String KEY_TURN_SIGNAL_HORIZONTAL       = "turn_signal_overlay_horizontal";
    // 箭头形状：0=V形箭头(经典 chevron) 1=流水灯带(奥迪灯厂) 2=实心圆头箭头
    public static final String KEY_TURN_SIGNAL_SHAPE            = "turn_signal_overlay_shape";
    // 转向箭头左侧内缩（% 屏宽）：默认 0 → 左右对称布局（中控整屏 1300×900，无左侧固定区概念）
    // 保留 0~45% 可调作为高级选项（若个别车机确有左侧遮挡可手动调大）
    public static final String KEY_SAFE_LEFT                    = "safe_left_area";

    // 转向 HUD 默认值：与 arcfox-turn-hud/INTEGRATION.md 声明的原厂默认保持一致
    // 【2026-08-03 用户明确要求】透明度默认 100、尺寸 100、垂直位置 40%、左右内缩 38%、左侧内缩 30%
    public static final int  DEFAULT_TURN_SIGNAL_COLOR          = 0xFF35E889; // 荧光青绿
    public static final int  DEFAULT_TURN_SIGNAL_EFFECT         = 2;          // 0..5，2 = 流动追光
    public static final int  DEFAULT_TURN_SIGNAL_SHAPE          = 0;          // 0..4，0 = V形箭头（3 = 传统箭头，4 = 静态箭头）
    public static final int  DEFAULT_TURN_SIGNAL_ALPHA          = 100;        // 0..100 %（用户指定默认 100）
    public static final int  DEFAULT_TURN_SIGNAL_SIZE           = 100;        // 40..160 %（用户指定默认 100）
    public static final int  DEFAULT_TURN_SIGNAL_TOP            = 40;         // 8..92 % 垂直中心（用户指定默认 40）
    public static final int  DEFAULT_TURN_SIGNAL_HORIZONTAL     = 38;         // 0..42 % 左右内缩（用户指定默认 38）
    // 极狐转向总开关默认值：默认开启（true = 1）。
    // 【2026-08-03 用户明确要求】「极狐转向设置默认为 1」→ 装车即用，无需进设置手动开。
    // 行为代价（可接受）：默认开启时转向窗口常驻全屏悬浮窗 + 一条 logcat 读取线程，
    //      使 OverlayService.stopSelfIfNoVisuals() 不会因「无可见悬浮窗」自动停服（服务常驻后台）。
    public static final boolean DEFAULT_TURN_SIGNAL_ENABLED     = true;

    public static final int  MIN_TURN_SIGNAL_EFFECT             = 0;
    public static final int  MAX_TURN_SIGNAL_EFFECT             = 5;
    public static final int  MIN_TURN_SIGNAL_SHAPE              = 0;
    public static final int  MAX_TURN_SIGNAL_SHAPE              = 4;          // 0..4，3 = 传统箭头，4 = 静态箭头（用户新增）
    /** 静态箭头形状编号（无动态常亮，实车用） */
    public static final int  TURN_SHAPE_STATIC                  = 4;
    public static final int  MIN_TURN_SIGNAL_ALPHA              = 10;
    public static final int  MAX_TURN_SIGNAL_ALPHA              = 100;
    public static final int  MIN_TURN_SIGNAL_SIZE               = 40;
    public static final int  MAX_TURN_SIGNAL_SIZE               = 160;
    public static final int  MIN_TURN_SIGNAL_TOP                = 8;
    public static final int  MAX_TURN_SIGNAL_TOP                = 92;
    public static final int  MIN_TURN_SIGNAL_HORIZONTAL         = 0;
    public static final int  MAX_TURN_SIGNAL_HORIZONTAL         = 42;
    // 左侧内缩：默认 30%（用户指定默认 30，适配左驾 HUD 投影遮挡），0~45% 可调（高级选项）
    public static final int  DEFAULT_SAFE_LEFT                  = 30;
    public static final int  MIN_SAFE_LEFT                      = 0;
    public static final int  MAX_SAFE_LEFT                      = 45;

    // ── Broadcast actions ────────────────────────────────────────────────
    public static final String ACTION_MAIN_OVERLAY_CHANGED      = "com.autonavi.companion.MAIN_OVERLAY_CHANGED";
    public static final String ACTION_OVERLAY_SCALE_CHANGED     = "com.autonavi.companion.OVERLAY_SCALE_CHANGED";
    public static final String ACTION_CLUSTER_MIRROR_CHANGED    = "com.autonavi.companion.CLUSTER_MIRROR_CHANGED";
    public static final String ACTION_CLUSTER_POSITION_CHANGED  = "com.autonavi.companion.CLUSTER_POSITION_CHANGED";
    public static final String ACTION_OVERLAY_CONTENT_CHANGED   = "com.autonavi.companion.OVERLAY_CONTENT_CHANGED";
    public static final String ACTION_OVERLAY_STYLE_CHANGED     = "com.autonavi.companion.OVERLAY_STYLE_CHANGED";
    public static final String ACTION_DISPLAY_POLICY_CHANGED    = "com.autonavi.companion.DISPLAY_POLICY_CHANGED";
    // 转向 HUD 配置变更（开关 / 颜色 / 特效 / 透明度 / 尺寸 / 位置）
    public static final String ACTION_TURN_SIGNAL_CHANGED       = "com.autonavi.companion.TURN_SIGNAL_CHANGED";
    // 转向 HUD 手动预览（EXTRA_TURN_SIGNAL_PREVIEW: "left" / "right" / "hazard"）
    public static final String ACTION_TURN_SIGNAL_PREVIEW       = "com.autonavi.companion.TURN_SIGNAL_PREVIEW";
    public static final String EXTRA_TURN_SIGNAL_PREVIEW        = "turn_signal_preview_direction";
    // 转向 HUD 模拟器/调试注入（把状态直接写入 monitor，模拟 CAN 信号）
    public static final String ACTION_TURN_SIGNAL_INJECT        = "com.autonavi.companion.TURN_SIGNAL_INJECT";
    public static final String EXTRA_TURN_SIGNAL_INJECT         = "turn_signal_inject_state";

    // ── UI style values ──────────────────────────────────────────────────
    public static final String DEFAULT_TARGET_PACKAGE           = "com.autonavi.amapauto";
    public static final String TEXT_MODE_LIGHT                  = "light";
    public static final String TEXT_MODE_AUTO                   = "auto";
    public static final String OVERLAY_UI_CARD                  = OverlayUiStyles.CARD;

    // ── Scale / opacity bounds ───────────────────────────────────────────
    public static final int MIN_BACKGROUND_OPACITY_PERCENT      = 0;
    public static final int MAX_BACKGROUND_OPACITY_PERCENT      = 90;
    public static final int DEFAULT_BACKGROUND_OPACITY_PERCENT  = 0;
    public static final int MIN_OVERLAY_SCALE_PERCENT           = 150;
    public static final int MAX_OVERLAY_SCALE_PERCENT           = 180;
    public static final int DEFAULT_OVERLAY_SCALE_PERCENT       = 170;

    // ═══════════════════════════════════════════════════════════════════════
    //  Static preference accessors
    // ═══════════════════════════════════════════════════════════════════════

    public static int getOverlayScalePercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return clampOverlayScalePercent(prefs.getInt(KEY_OVERLAY_SCALE_PERCENT, DEFAULT_OVERLAY_SCALE_PERCENT));
    }

    public static float getOverlayScale(Context context) {
        return getOverlayScalePercent(context) / 100f;
    }

    public static boolean isMainOverlayEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MAIN_OVERLAY_ENABLED, false);
    }

    public static boolean isClusterMirrorEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLUSTER_MIRROR_ENABLED, true);
    }

    public static boolean isAutoStartEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_AUTO_START_ENABLED);
    }

    public static boolean isStartServiceOnAppOpenEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_START_SERVICE_ON_APP_OPEN);
    }

    public static boolean isLaunchTargetFromDesktopEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_LAUNCH_TARGET_FROM_DESKTOP);
    }

    public static boolean isHideMainWhenTargetForegroundEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_HIDE_MAIN_WHEN_TARGET_FOREGROUND);
    }

    public static boolean isShowMainWhenTargetForegroundEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_SHOW_MAIN_WHEN_TARGET_FOREGROUND);
    }

    public static boolean isHideClusterWhenInactiveEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_HIDE_CLUSTER_WHEN_INACTIVE);
    }

    public static boolean isOverspeedMildWarningEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_OVERSPEED_MILD_WARNING);
    }

    public static boolean isOverspeedMediumWarningEnabled(Context context) {
        return isBehaviorEnabled(context, KEY_OVERSPEED_MEDIUM_WARNING);
    }

    public static boolean hasUsageStatsAccess(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return true;
        }
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getClusterScalePercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return clampOverlayScalePercent(prefs.getInt(KEY_CLUSTER_SCALE_PERCENT, DEFAULT_OVERLAY_SCALE_PERCENT));
    }

    public static float getClusterScale(Context context) {
        return getClusterScalePercent(context) / 100f;
    }

    public static int getEdogAlertBackgroundOpacity(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_EDOG_ALERT_BACKGROUND_OPACITY, 50);
    }

    public static int getClusterDisplayId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CLUSTER_DISPLAY_ID, -1);
    }

    public static int getClusterX(Context context, int defaultValue) {
        return Math.max(0, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CLUSTER_X, defaultValue));
    }

    public static int getClusterY(Context context, int defaultValue) {
        return Math.max(0, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CLUSTER_Y, defaultValue));
    }

    public static boolean isLightVisible(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_LIGHT, true);
    }

    public static boolean isLightVerticalMain(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LIGHT_VERTICAL_MAIN, true);
    }

    public static boolean isLightVerticalCluster(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LIGHT_VERTICAL_CLUSTER, true);
    }

    public static boolean isLightDirectionVisible(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_LIGHT_DIRECTION, true);
    }

    /** ≤4s 呼吸动画：倒计时 ≤4 秒时 alpha 0.25→1.0 正弦呼吸，tick 提速到 120ms，提示即将变灯 */
    public static boolean isLightBreathingEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LIGHT_BREATHING, true);
    }

    public static boolean isTransparentBackground(Context context) {
        return getBackgroundOpacityPercent(context) <= MIN_BACKGROUND_OPACITY_PERCENT;
    }

    public static boolean isAutoTextMode(Context context) {
        return TEXT_MODE_AUTO.equals(getOverlayTextMode(context));
    }

    public static boolean isForceNightMode(Context context) {
        return TEXT_MODE_FORCE_NIGHT.equals(getOverlayTextMode(context));
    }

    /** Returns true unless force day; force night → true; follow system → system night flag. */
    public static boolean isNightMode(Context context) {
        String mode = getOverlayTextMode(context);
        // Force day → always daytime palette
        if (TEXT_MODE_LIGHT.equals(mode)) return false;
        // Force night → always night palette
        if (TEXT_MODE_FORCE_NIGHT.equals(mode)) return true;
        // Follow system
        return isSystemNightMode(context);
    }

    /**
     * Checks Android system UI mode (Configuration.UI_MODE_NIGHT_YES).
     * 【夜间判断锚定主屏】始终用 applicationContext 的资源配置（跟随系统主显示配置）。
     * 车机副屏（HUD）display context 的 uiMode 与主屏可能不一致（HUD 无法识别白天/夜间），
     * 若用副屏 context 判断会导致「主屏红绿灯调暗、HUD 转向未调暗」的错位 —— 一律锚定主屏。
     */
    public static boolean isSystemNightMode(Context context) {
        Context base = context != null ? context.getApplicationContext() : null;
        if (base == null) base = context;
        if (base == null) return false;
        int mode = base.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  极狐转向 HUD 访问器（arcfox-turn-hud 移植）
    //  所有读取一律做 clamp，避免外部/脏数据写入导致绘制异常或 ANR
    // ═══════════════════════════════════════════════════════════════════════

    public static boolean isTurnSignalOverlayEnabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TURN_SIGNAL_ENABLED, DEFAULT_TURN_SIGNAL_ENABLED);
    }

    public static void setTurnSignalOverlayEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TURN_SIGNAL_ENABLED, enabled).apply();
    }

    public static int getTurnSignalColor(Context context) {
        if (context == null) return DEFAULT_TURN_SIGNAL_COLOR;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TURN_SIGNAL_COLOR, DEFAULT_TURN_SIGNAL_COLOR);
    }

    public static int getTurnSignalEffect(Context context) {
        if (context == null) return DEFAULT_TURN_SIGNAL_EFFECT;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TURN_SIGNAL_EFFECT, DEFAULT_TURN_SIGNAL_EFFECT);
        return clamp(v, MIN_TURN_SIGNAL_EFFECT, MAX_TURN_SIGNAL_EFFECT);
    }

    public static int getTurnSignalShape(Context context) {
        if (context == null) return DEFAULT_TURN_SIGNAL_SHAPE;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TURN_SIGNAL_SHAPE, DEFAULT_TURN_SIGNAL_SHAPE);
        return clamp(v, MIN_TURN_SIGNAL_SHAPE, MAX_TURN_SIGNAL_SHAPE);
    }

    public static int getTurnSignalAlpha(Context context) {
        if (context == null) return DEFAULT_TURN_SIGNAL_ALPHA;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TURN_SIGNAL_ALPHA, DEFAULT_TURN_SIGNAL_ALPHA);
        return clamp(v, MIN_TURN_SIGNAL_ALPHA, MAX_TURN_SIGNAL_ALPHA);
    }

    public static int getTurnSignalSize(Context context) {
        if (context == null) return DEFAULT_TURN_SIGNAL_SIZE;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TURN_SIGNAL_SIZE, DEFAULT_TURN_SIGNAL_SIZE);
        return clamp(v, MIN_TURN_SIGNAL_SIZE, MAX_TURN_SIGNAL_SIZE);
    }

    public static int getTurnSignalTop(Context context) {
        if (context == null) return DEFAULT_TURN_SIGNAL_TOP;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TURN_SIGNAL_TOP, DEFAULT_TURN_SIGNAL_TOP);
        return clamp(v, MIN_TURN_SIGNAL_TOP, MAX_TURN_SIGNAL_TOP);
    }

    public static int getTurnSignalHorizontal(Context context) {
        if (context == null) return DEFAULT_TURN_SIGNAL_HORIZONTAL;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TURN_SIGNAL_HORIZONTAL, DEFAULT_TURN_SIGNAL_HORIZONTAL);
        return clamp(v, MIN_TURN_SIGNAL_HORIZONTAL, MAX_TURN_SIGNAL_HORIZONTAL);
    }

    /** 转向箭头左侧内缩（% 屏宽），默认 30（DEFAULT_SAFE_LEFT）；值为 0 时为左右对称布局 */
    public static int getSafeLeft(Context context) {
        if (context == null) return DEFAULT_SAFE_LEFT;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SAFE_LEFT, DEFAULT_SAFE_LEFT);
        return clamp(v, MIN_SAFE_LEFT, MAX_SAFE_LEFT);
    }

    /** 一次性迁移：旧版默认 safe_left=34（1/3 固定仪表区避让设计），新版取消该设计、默认 0。
     *  仅当存储值恰为旧默认 34 时清除（视为从未手动调整过）；手动调过的非 34 值保留。 */
    public static void migrateSafeLeftIfLegacyDefault(Context context) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.contains(KEY_SAFE_LEFT) && sp.getInt(KEY_SAFE_LEFT, DEFAULT_SAFE_LEFT) == 34) {
            sp.edit().remove(KEY_SAFE_LEFT).apply();
        }
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static boolean isCardUiEnabled(Context context) {
        return OVERLAY_UI_CARD.equals(getOverlayUiStyle(context));
    }

    public static boolean usesDarkTextPalette(Context context) {
        return getBackgroundOpacityPercent(context) <= 55 && !isForceNightMode(context);
    }

    public static int getBackgroundOpacityPercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(KEY_BACKGROUND_OPACITY_PERCENT)) {
            return clampBackgroundOpacityPercent(
                    prefs.getInt(KEY_BACKGROUND_OPACITY_PERCENT, DEFAULT_BACKGROUND_OPACITY_PERCENT));
        }
        return prefs.getBoolean(KEY_TRANSPARENT_BACKGROUND, false)
                ? MIN_BACKGROUND_OPACITY_PERCENT
                : DEFAULT_BACKGROUND_OPACITY_PERCENT;
    }

    public static String getOverlayTextMode(Context context) {
        String mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TEXT_MODE, TEXT_MODE_AUTO);
        // Legacy migration: old "light" value means "force_day"
        if (TEXT_MODE_LIGHT.equals(mode)) return TEXT_MODE_LIGHT;
        if (TEXT_MODE_AUTO.equals(mode)) return TEXT_MODE_AUTO;
        if (TEXT_MODE_FORCE_NIGHT.equals(mode)) return TEXT_MODE_FORCE_NIGHT;
        return TEXT_MODE_AUTO;
    }

    public static String getOverlayUiStyle(Context context) {
        String style = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OVERLAY_UI_STYLE, OverlayUiStyles.CARD);
        return OverlayUiStyles.normalize(style);
    }

    public static boolean isOverlayContentEnabled(Context context, String key) {
        boolean defaultValue;
        if (KEY_SHOW_LIGHT.equals(key)) {
            defaultValue = true;  // 红绿灯倒计时默认开启
        } else {
            defaultValue = KEY_SHOW_LIGHT_DIRECTION.equals(key)
                    || KEY_LIGHT_BREATHING.equals(key); // ≤4s 呼吸动画默认开启，其他内容默认关闭
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, defaultValue);
    }

    static boolean isBehaviorEnabled(Context context, String key) {
        boolean defaultValue = KEY_AUTO_START_ENABLED.equals(key)
                || KEY_START_SERVICE_ON_APP_OPEN.equals(key)
                || KEY_LIGHT_VERTICAL_CLUSTER.equals(key)
                || KEY_LIGHT_VERTICAL_MAIN.equals(key)
                || KEY_OVERSPEED_MILD_WARNING.equals(key)
                || KEY_OVERSPEED_MEDIUM_WARNING.equals(key)
                || KEY_HIDE_CLUSTER_WHEN_INACTIVE.equals(key);
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, defaultValue);
    }

    static int clampOverlayScalePercent(int percent) {
        return Math.max(MIN_OVERLAY_SCALE_PERCENT, Math.min(MAX_OVERLAY_SCALE_PERCENT, percent));
    }

    static int clampBackgroundOpacityPercent(int percent) {
        return Math.max(MIN_BACKGROUND_OPACITY_PERCENT, Math.min(MAX_BACKGROUND_OPACITY_PERCENT, percent));
    }

    public static int strokeOpacityForBackground(int opacityPercent) {
        return opacityPercent <= 0 ? 0 : Math.max(8, Math.round(opacityPercent * 0.18f));
    }

    public static int withAlpha(int color, int alphaPercent) {
        int pct = Math.max(0, Math.min(100, alphaPercent));
        int alpha = Math.round(pct * 255f / 100f);
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static String getTargetPackage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE);
        return value == null || value.length() == 0 ? DEFAULT_TARGET_PACKAGE : value;
    }
}
