package com.autonavi.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;   // 【新增·转向 HUD】滑杆控件
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends Activity {
    static final String EXTRA_OPEN_SETTINGS = "open_companion_settings";
    private static final String KEY_LAST_DESKTOP_LAUNCH_AT = "last_desktop_launch_at";
    private static final long DOUBLE_DESKTOP_LAUNCH_WINDOW_MS = 30_000L;
    static final String HOMEPAGE_URL = "https://amap-companion.zuoqirun.top";
    static final String REPOSITORY_URL = "https://github.com/zuo-qirun/amap-companion";
    static final String LICENSE_URL = "https://github.com/zuo-qirun/amap-companion/blob/master/LICENSE";
    static final String CUSTOM_MAP_SKILL_URL = "https://github.com/zuo-qirun/amap-cruise-wrapper-skill";
    static final String CUSTOM_MAP_APK_URL = "https://github.com/zuo-qirun/amap-cruise-wrapper-skill/releases/download/v20260523-cruise-wrapper/amap-auto-cruise-wrapper-20260523.apk";
    static final String CUSTOM_MAP_SKILL_MIRROR_URL = "https://gh-proxy.com/https://github.com/zuo-qirun/amap-cruise-wrapper-skill/archive/refs/heads/master.zip";
    static final String CUSTOM_MAP_APK_MIRROR_URL = "https://gh.llkk.cc/https://github.com/zuo-qirun/amap-cruise-wrapper-skill/releases/download/v20260523-cruise-wrapper/amap-auto-cruise-wrapper-20260523.apk";
    private static final String TARGET_PACKAGE_PREFIX = "com.autonavi.";

    private TextView targetText;
    private TextView clusterDisplayText;
    private TextView coordTextX;
    private TextView coordTextY;
    private TextView overlayTextModeValue;
    private TextView overlayUiStyleValue;

    // ── 转向 HUD 摘要卡引用（BUG-1 修复：形状/特效/透明度变更后同步刷新摘要）──
    private TextView turnSummaryView;

    // ── 伙伴服务开关引用（P2-1/P2-2：onResume 刷新 + onDestroy 清理）──
    private IosSwitch serviceSwitch;
    private final Runnable serviceSwitchRefresh = new Runnable() {
        @Override
        public void run() {
            if (serviceSwitch != null) {
                serviceSwitch.setChecked(isServiceRunning(OverlayService.class), true);
            }
        }
    };

    // ── iPad 式左右分栏状态 ──
    private ScrollView contentScroll;          // 右侧内容滚动容器
    private LinearLayout pageContent;          // 右侧当前页内容（切换时重建）
    private final java.util.ArrayList<TextView> sidebarItems = new java.util.ArrayList<>();
    private int currentPage = 0;
    private static final String[] SIDEBAR_TITLES = {"通用设置", "悬浮窗口", "副屏设置", "极狐转向"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 一次性迁移：旧版默认 safe_left=34（1/3 固定仪表区避让）→ 新版默认 0（1300×900 全屏对称）
        AppPrefs.migrateSafeLeftIfLegacyDefault(this);
        // 车机级横屏大屏（宽≥1200 横屏，适配 1300×900 极狐 αS5）：放大 scaledDensity 使 sp 字号整体放大 1.15 倍
        // 只改 scaledDensity 不动 density → 字体变大、dp 间距/控件尺寸不变，布局不溢出
        if (isCarScreen()) {
            getResources().getDisplayMetrics().scaledDensity *= uiFontScale();
        }
        if (redirectDesktopLaunchToTarget(getIntent())) {
            return;
        }
        View content = buildContent();
        setContentView(content);
        autoStartServiceOnAppOpen();
        // 伙伴服务默认开启：已授权悬浮窗时自动拉起 OverlayService，开关初始即 true
        if (Settings.canDrawOverlays(this) && !isServiceRunning(OverlayService.class)) {
            startOverlayService();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        redirectDesktopLaunchToTarget(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 伙伴服务默认开启：已授权悬浮窗时自动拉起 OverlayService（与 onCreate 同条件，覆盖"授权悬浮窗返回"场景）
        if (Settings.canDrawOverlays(this) && !isServiceRunning(OverlayService.class)) {
            startOverlayService();
        }
        // 服务启动是异步的，延迟按真实运行状态刷新开关（授权返回后立即变绿）
        if (serviceSwitch != null) {
            serviceSwitch.postDelayed(serviceSwitchRefresh, 500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (serviceSwitch != null) {
            serviceSwitch.removeCallbacks(serviceSwitchRefresh);
        }
        super.onDestroy();
    }

    private void autoStartServiceOnAppOpen() {
        if (!AppPrefs.isStartServiceOnAppOpenEnabled(this)) {
            return;
        }
        targetText.postDelayed(() -> startCompanionService(false), 350L);
    }

    private LinearLayout buildContent() {
        // iPad 式左右分栏：整个页面不再整体滚动，改为右侧内容区独立滚动
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor()); // iOS 分组背景
        root.setPadding(dp(20), dp(18), dp(20), dp(20));

        // ── iOS 18 大标题页头（原深色 hero 卡）──
        TextView title = new TextView(this);
        title.setText("AMap Max");
        title.setTextSize(34f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(labelColor());
        title.setOnClickListener(v -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(
                    "com.adayo.app.factorymode",
                    "com.adayo.app.factorymode.MainActivity"
                ));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable t) {
                try {
                    // fallback: launch by package name
                    startActivity(getPackageManager().getLaunchIntentForPackage("com.adayo.app.factorymode"));
                } catch (Throwable t2) {
                    android.util.Log.e("MainActivity", "Failed to launch factorymode", t2);
                }
            }
        });
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        targetText = new TextView(this);
        targetText.setTextSize(13f);
        LinearLayout.LayoutParams targetTextLp = new LinearLayout.LayoutParams(-1, -2);
        targetTextLp.setMargins(0, dp(4), 0, 0);
        root.addView(targetText, targetTextLp);
        updateTargetText();

        // ── iPad 式左右分栏：左侧侧边栏 + 右侧内容区 ──
        // 【1300×900 全屏适配】中控整屏 1300×900，UI 从 x=0 全屏铺满，不做左侧避让
        LinearLayout contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.HORIZONTAL);
        contentArea.setBaselineAligned(false);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        contentLp.setMargins(0, dp(14), 0, 0);
        root.addView(contentArea, contentLp);

        // 左侧：固定宽度侧边栏（iPad 设置风格），高度撑满
        // 【菜单收窄】4 个 4 字菜单项用 160~176dp 已足够，避免过宽挤占内容区
        LinearLayout sidebar = buildSidebar();
        contentArea.addView(sidebar, new LinearLayout.LayoutParams(dp(isWideLayout() ? 176 : 160), -1));

        // 右侧：内容面板（weight=1），内部独立滚动
        LinearLayout contentPanel = new LinearLayout(this);
        contentPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(0, -1, 1f);
        panelLp.setMarginStart(dp(14));
        contentArea.addView(contentPanel, panelLp);

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        contentScroll.setClipToPadding(false);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(pageContent, new ScrollView.LayoutParams(-1, -2));
        contentPanel.addView(contentScroll, new LinearLayout.LayoutParams(-1, -1));

        // 默认选中第一页
        showPage(0);

        return root;
    }

    /** 左侧侧边栏：菜单分类 + iOS 选中高亮胶囊 */
    private LinearLayout buildSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor());
        bg.setCornerRadius(dp(16));
        sidebar.setBackground(bg);
        sidebar.setPadding(dp(6), dp(8), dp(6), dp(8));

        for (int i = 0; i < SIDEBAR_TITLES.length; i++) {
            TextView item = new TextView(this);
            item.setText(SIDEBAR_TITLES[i]);
            item.setTextSize(16f);
            item.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            item.setTextColor(labelColor());
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), dp(13), dp(16), dp(13));
            item.setClickable(true);
            item.setFocusable(true);
            final int idx = i;
            item.setOnClickListener(v -> showPage(idx));
            sidebarItems.add(item);
            sidebar.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }

        // ── 侧边栏底部：弹性留白 + Build 信息 ──
        View spacer = new View(this);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Build 版本信息
        TextView buildInfo = new TextView(this);
        buildInfo.setText("Build " + com.autonavi.companion.BuildConfig.BUILD_TIME);
        buildInfo.setTextSize(11f);
        buildInfo.setTextColor(secondaryColor());
        buildInfo.setGravity(Gravity.CENTER);
        buildInfo.setPadding(0, dp(8), 0, dp(6));
        sidebar.addView(buildInfo, new LinearLayout.LayoutParams(-1, -2));

        updateSidebarSelection();
        return sidebar;
    }

    /** 切换右侧页面并刷新侧边栏选中态 */
    private void showPage(int index) {
        currentPage = index;
        pageContent.removeAllViews();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(20));
        switch (index) {
            case 0: // 通用设置
                addActionButtons(pageContent, false);
                addBehaviorControls(pageContent);
                break;
            case 1: // 悬浮窗口
                addScaleControls(pageContent);
                addOverlayContentControls(pageContent);
                break;
            case 2: // 副屏设置
                addClusterMirrorControls(pageContent);
                break;
            case 3: // 极狐转向
                addTurnSignalEntry(pageContent);
                break;
            default:
                addActionButtons(pageContent, false);
                break;
        }
        updateSidebarSelection();
        if (contentScroll != null) {
            contentScroll.scrollTo(0, 0);
        }
    }

    /** 侧边栏选中态：蓝底白字圆角胶囊 */
    private void updateSidebarSelection() {
        for (int i = 0; i < sidebarItems.size(); i++) {
            TextView item = sidebarItems.get(i);
            boolean sel = i == currentPage;
            item.setTextColor(sel ? 0xFFFFFFFF : labelColor());
            item.setTypeface(Typeface.DEFAULT, sel ? Typeface.BOLD : Typeface.NORMAL);
            GradientDrawable d = new GradientDrawable();
            d.setColor(sel ? 0xFF007AFF : 0x00FFFFFF);
            d.setCornerRadius(dp(10));
            item.setBackground(d);
        }
    }

    private void addActionButtons(LinearLayout parent, boolean wideLayout) {
        // iOS 分组：区段标题 + 白色分组卡
        parent.addView(sectionHeader("通用设置"), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = iosCard();
        card.setPadding(dp(16), dp(4), dp(16), dp(4));

        // 选择目标应用（右侧显示当前包名）
        card.addView(listRow("选择目标应用", AppPrefs.getTargetPackage(this), this::chooseTargetApp),
                new LinearLayout.LayoutParams(-1, -2));
        card.addView(sep(), sepLp());
        // 授权悬浮窗
        card.addView(listRow("授权悬浮窗", null, this::requestOverlayPermission),
                new LinearLayout.LayoutParams(-1, -2));
        card.addView(sep(), sepLp());
        // 打开目标应用
        card.addView(listRow("打开目标应用", null, this::openTargetApp),
                new LinearLayout.LayoutParams(-1, -2));
        card.addView(sep(), sepLp());
        // 伙伴服务：iOS 开关行，运行状态即开关状态
        serviceSwitch = new IosSwitch(this);
        boolean serviceRunning = isServiceRunning(OverlayService.class);
        serviceSwitch.setChecked(serviceRunning, false);
        // 【2026-08-03 审查修复 P2-2】初始态与自启 prefs 保持一致：
        // 开关显示什么，auto_start / start_on_app_open 就写什么，避免
        // 「开关显示关但 prefs 默认 true」导致的开机静默自启（UI 与行为不一致）。
        saveBehaviorEnabled(AppPrefs.KEY_AUTO_START_ENABLED, serviceRunning);
        saveBehaviorEnabled(AppPrefs.KEY_START_SERVICE_ON_APP_OPEN, serviceRunning);
        // 服务启动是异步的，延迟按真实运行状态刷新开关（默认开启场景下变为绿色）
        serviceSwitch.postDelayed(serviceSwitchRefresh, 500);
        serviceSwitch.setOnCheckedChangeListener((s, checked) -> {
            // 【2026-08-03 用户要求】开机自启与伙伴服务开关联动：
            // 开关开 = 服务跑 + 开机自启/打开应用自启生效；开关关 = 服务停 + 全部自启关闭。
            // （此前 auto_start / start_on_app_open 两个 key 默认 true 但无独立 UI 开关，
            //   用户希望"伙伴服务开着才自启"，故跟随主开关一并写入。）
            saveBehaviorEnabled(AppPrefs.KEY_AUTO_START_ENABLED, checked);
            saveBehaviorEnabled(AppPrefs.KEY_START_SERVICE_ON_APP_OPEN, checked);
            if (checked) {
                startCompanionService();
            } else {
                stopCompanionService();
            }
            s.postDelayed(serviceSwitchRefresh, 500);
        });
        LinearLayout serviceRow = settingRow("伙伴服务开关", serviceSwitch);
        serviceRow.setClickable(true);
        serviceRow.setFocusable(true);
        serviceRow.setBackground(rowPress());
        serviceRow.setOnClickListener(v -> serviceSwitch.toggle());
        card.addView(serviceRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, lp);
    }

    /** 分组内分隔线 View（配合 sepLp 使用，iOS 左缩进分隔线） */
    private View sep() {
        View v = new View(this);
        v.setBackgroundColor(separatorColor());
        return v;
    }

    /** 分组内分隔线的 LayoutParams（左缩进对齐文字） */
    private LinearLayout.LayoutParams sepLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(dp(16), 0, 0, 0);
        return lp;
    }
    // ══════════════════════════════════════════════════════════════════════
    //  【新增·转向 HUD】设置卡片（arcfox-turn-hud 移植）
    //  说明：原版 res/layout/panel_turn_signal_overlay.xml 依赖 MaterialCardView /
    //  SwitchCompat，而本工程编译期 classpath 只有 android.jar（无 AndroidX / Material），
    //  故不使用该 XML，改为沿用本页既有的「动态 Java 构建 + 原生控件」写法。
    // ══════════════════════════════════════════════════════════════════════

    /** 特效模式名称，索引 = AppPrefs.KEY_TURN_SIGNAL_EFFECT 的值 */
    private static final String[] TURN_EFFECT_NAMES = {
            "线性衰减", "波形脉冲", "流动追光", "正弦呼吸", "粒子拖尾", "双向流光"
    };

    /** 箭头形状名称，索引 = AppPrefs.KEY_TURN_SIGNAL_SHAPE 的值 */
    // 【2026-08-03 用户新增】5 形状：V形箭头 / 流水灯带 / 实心箭头 / 传统箭头 / 静态箭头
    private static final String[] TURN_SHAPE_NAMES = {
            "V形箭头", "流水灯带", "实心箭头", "传统箭头", "静态箭头"
    };

    /** 预设箭头配色（首项为原厂默认荧光青绿）；【2026-08-03 用户加色】7 色含 iOS 亮绿 #34C759 */
    private static final int[] TURN_COLOR_PRESETS = {
            0xFF35E889, 0xFF00E5FF, 0xFFFFC400, 0xFFFF3B30, 0xFF34C759, 0xFFFFFFFF, 0xFFB388FF
    };

    private TextView turnEffectValue;
    private TextView turnShapeValue;
    private LinearLayout turnColorRow;

    /** iOS 18 风格入口卡：总开关留在主设置页，点卡片进底部弹窗做详细设置 */
    private void addTurnSignalEntry(LinearLayout parent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor());
        bg.setCornerRadius(dp(16));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(bg);

        // 顶部：标题 + iOS 开关
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(12), dp(12));

        LinearLayout textWrap = new LinearLayout(this);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("极狐转向");
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(labelColor());
        TextView sub = new TextView(this);
        sub.setText("屏幕两侧绘制流动转向箭头");
        sub.setTextSize(12f);
        sub.setTextColor(secondaryColor());
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(0, dp(2), 0, 0);
        textWrap.addView(title);
        textWrap.addView(sub, subLp);
        top.addView(textWrap, new LinearLayout.LayoutParams(0, -2, 1f));

        IosSwitch sw = new IosSwitch(this);
        sw.setChecked(AppPrefs.isTurnSignalOverlayEnabled(this));
        sw.setOnCheckedChangeListener((s, checked) -> {
            AppPrefs.setTurnSignalOverlayEnabled(this, checked);
            if (checked) {
                startOverlayService();
            }
            notifyTurnSignalChanged();
            if (!checked) {
                stopServiceIfNoVisuals();
            }
        });
        top.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        card.addView(top);

        // 分隔线
        View sep = new View(this);
        sep.setBackgroundColor(separatorColor());
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(-1, dp(1));
        sepLp.setMargins(dp(14), 0, 0, 0);
        card.addView(sep, sepLp);

        // 详情行：摘要 + 箭头，点开弹窗
        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.HORIZONTAL);
        detail.setGravity(Gravity.CENTER_VERTICAL);
        detail.setPadding(dp(14), dp(12), dp(12), dp(12));
        detail.setClickable(true);
        detail.setFocusable(true);
        TextView summary = new TextView(this);
        summary.setText(buildTurnSummary());
        summary.setTextSize(13f);
        summary.setTextColor(secondaryColor());
        turnSummaryView = summary;   // BUG-1 修复：保存引用供设置变更后刷新
        detail.addView(summary, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(22f);
        chev.setTextColor(chevronColor());
        detail.addView(chev, new LinearLayout.LayoutParams(-2, -2));
        detail.setOnClickListener(v -> TurnSignalSettingsSheet.show(this));
        card.addView(detail);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, clp);
    }

    private String buildTurnSummary() {
        return turnShapeName(AppPrefs.getTurnSignalShape(this))
                + " · " + turnEffectName(AppPrefs.getTurnSignalEffect(this))
                + " · 透明度 " + AppPrefs.getTurnSignalAlpha(this) + "%";
    }

    private void addTurnSignalControls(LinearLayout parent) {
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // ── 标题行：标题 + 总开关 ──────────────────────────────────────
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("转向灯 HUD");
        title.setTextSize(14f);
        title.setTextColor(labelColor());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(-2, -2));

        View titleSpacer = new View(this);
        titleRow.addView(titleSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        CheckBox enableToggle = new CheckBox(this);
        enableToggle.setText("启用");
        enableToggle.setTextSize(14f);
        enableToggle.setTextColor(labelColor());
        enableToggle.setChecked(AppPrefs.isTurnSignalOverlayEnabled(this));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            enableToggle.setButtonTintList(
                    android.content.res.ColorStateList.valueOf(0xFF2563EB));
        }
        enableToggle.setOnCheckedChangeListener((CompoundButton v, boolean checked) -> {
            AppPrefs.setTurnSignalOverlayEnabled(this, checked);
            if (checked) {
                // 打开时必须确保服务在跑，否则 logcat 监控线程不存在
                startOverlayService();
            }
            notifyTurnSignalChanged();
            if (!checked) {
                stopServiceIfNoVisuals();
            }
        });
        titleRow.addView(enableToggle, new LinearLayout.LayoutParams(-2, -2));
        card.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        // ── 说明副标题 ────────────────────────────────────────────────
        TextView hint = new TextView(this);
        hint.setText("读取车机 CAN 转向灯状态，在屏幕两侧绘制流动箭头");
        hint.setTextSize(11f);
        hint.setTextColor(secondaryColor());
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(0, dp(2), 0, dp(8));
        card.addView(hint, hintLp);

        // ── 箭头颜色（预设色块） ──────────────────────────────────────
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView colorLabel = new TextView(this);
        colorLabel.setText("箭头颜色");
        colorLabel.setTextSize(13f);
        colorLabel.setTextColor(labelColor());
        colorLabel.setMinWidth(dp(64));
        colorRow.addView(colorLabel, new LinearLayout.LayoutParams(-2, -2));

        turnColorRow = new LinearLayout(this);
        turnColorRow.setOrientation(LinearLayout.HORIZONTAL);
        turnColorRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int preset : TURN_COLOR_PRESETS) {
            turnColorRow.addView(buildTurnColorChip(preset));
        }
        LinearLayout.LayoutParams colorRowLp = new LinearLayout.LayoutParams(-1, -2);
        colorRowLp.setMarginStart(dp(4));
        colorRow.addView(turnColorRow, colorRowLp);

        LinearLayout.LayoutParams colorLp = new LinearLayout.LayoutParams(-1, -2);
        colorLp.setMargins(0, dp(2), 0, dp(6));
        card.addView(colorRow, colorLp);

        // ── 箭头形状（对话框单选） ────────────────────────────────────
        LinearLayout shapeRow = new LinearLayout(this);
        shapeRow.setOrientation(LinearLayout.HORIZONTAL);
        shapeRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView shapeLabel = new TextView(this);
        shapeLabel.setText("箭头形状");
        shapeLabel.setTextSize(13f);
        shapeLabel.setTextColor(labelColor());
        shapeLabel.setMinWidth(dp(64));
        shapeRow.addView(shapeLabel, new LinearLayout.LayoutParams(-2, -2));

        turnShapeValue = new TextView(this);
        turnShapeValue.setTextSize(13f);
        turnShapeValue.setTextColor(0xFF2563EB);
        turnShapeValue.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable shapeBg = new GradientDrawable();
        shapeBg.setColor(0xFFEFF6FF);
        shapeBg.setCornerRadius(dp(6));
        shapeBg.setStroke(dp(1), 0xFFBFDBFE);
        turnShapeValue.setBackground(shapeBg);
        turnShapeValue.setText(turnShapeName(AppPrefs.getTurnSignalShape(this)));
        turnShapeValue.setOnClickListener(v -> showTurnShapeDialog());
        LinearLayout.LayoutParams shapeValueLp = new LinearLayout.LayoutParams(-2, -2);
        shapeValueLp.setMarginStart(dp(4));
        shapeRow.addView(turnShapeValue, shapeValueLp);

        LinearLayout.LayoutParams shapeLp = new LinearLayout.LayoutParams(-1, -2);
        shapeLp.setMargins(0, 0, 0, dp(4));
        card.addView(shapeRow, shapeLp);

        // ── 动画特效（对话框单选） ────────────────────────────────────
        LinearLayout effectRow = new LinearLayout(this);
        effectRow.setOrientation(LinearLayout.HORIZONTAL);
        effectRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView effectLabel = new TextView(this);
        effectLabel.setText("动画特效");
        effectLabel.setTextSize(13f);
        effectLabel.setTextColor(labelColor());
        effectLabel.setMinWidth(dp(64));
        effectRow.addView(effectLabel, new LinearLayout.LayoutParams(-2, -2));

        turnEffectValue = new TextView(this);
        turnEffectValue.setTextSize(13f);
        turnEffectValue.setTextColor(0xFF2563EB);
        turnEffectValue.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable effectBg = new GradientDrawable();
        effectBg.setColor(0xFFEFF6FF);
        effectBg.setCornerRadius(dp(6));
        effectBg.setStroke(dp(1), 0xFFBFDBFE);
        turnEffectValue.setBackground(effectBg);
        refreshTurnEffectLabel();
        turnEffectValue.setOnClickListener(v -> showTurnEffectDialog());
        LinearLayout.LayoutParams effectValueLp = new LinearLayout.LayoutParams(-2, -2);
        effectValueLp.setMarginStart(dp(4));
        effectRow.addView(turnEffectValue, effectValueLp);

        LinearLayout.LayoutParams effectLp = new LinearLayout.LayoutParams(-1, -2);
        effectLp.setMargins(0, 0, 0, dp(4));
        card.addView(effectRow, effectLp);

        // ── 四条滑杆 ──────────────────────────────────────────────────
        card.addView(buildTurnSeekRow("透明度", AppPrefs.KEY_TURN_SIGNAL_ALPHA,
                AppPrefs.MIN_TURN_SIGNAL_ALPHA, AppPrefs.MAX_TURN_SIGNAL_ALPHA,
                AppPrefs.getTurnSignalAlpha(this)));
        card.addView(buildTurnSeekRow("箭头尺寸", AppPrefs.KEY_TURN_SIGNAL_SIZE,
                AppPrefs.MIN_TURN_SIGNAL_SIZE, AppPrefs.MAX_TURN_SIGNAL_SIZE,
                AppPrefs.getTurnSignalSize(this)));
        card.addView(buildTurnSeekRow("垂直位置", AppPrefs.KEY_TURN_SIGNAL_TOP,
                AppPrefs.MIN_TURN_SIGNAL_TOP, AppPrefs.MAX_TURN_SIGNAL_TOP,
                AppPrefs.getTurnSignalTop(this)));
        card.addView(buildTurnSeekRow("左右内缩", AppPrefs.KEY_TURN_SIGNAL_HORIZONTAL,
                AppPrefs.MIN_TURN_SIGNAL_HORIZONTAL, AppPrefs.MAX_TURN_SIGNAL_HORIZONTAL,
                AppPrefs.getTurnSignalHorizontal(this)));

        // ── 预览按钮 ──────────────────────────────────────────────────
        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.addView(buildTurnPreviewButton("← 左转", "left"));
        previewRow.addView(buildTurnPreviewButton("右转 →", "right"));
        previewRow.addView(buildTurnPreviewButton("双闪", "hazard"));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, -2);
        previewLp.setMargins(0, dp(8), 0, 0);
        card.addView(previewRow, previewLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, cardLp);
    }

    /** 单个预设色块；选中态加深色描边 */
    private View buildTurnColorChip(int color) {
        View chip = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        lp.setMarginEnd(dp(6));
        chip.setLayoutParams(lp);
        applyTurnColorChipState(chip, color, AppPrefs.getTurnSignalColor(this) == color);
        chip.setTag(color);
        chip.setOnClickListener(v -> {
            saveTurnSignalInt(AppPrefs.KEY_TURN_SIGNAL_COLOR, color);
            syncTurnColorChips(color);
            notifyTurnSignalChanged();
            sendTurnSignalPreview("hazard");   // 换色即预览，所见即所得
        });
        return chip;
    }

    private void applyTurnColorChipState(View chip, int color, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(dp(selected ? 3 : 1), selected ? 0xFF2563EB : 0xFFCBD5E1);
        chip.setBackground(bg);
    }

    private void syncTurnColorChips(int selectedColor) {
        if (turnColorRow == null) return;
        for (int i = 0; i < turnColorRow.getChildCount(); i++) {
            View chip = turnColorRow.getChildAt(i);
            Object tag = chip.getTag();
            if (tag instanceof Integer) {
                int c = (Integer) tag;
                applyTurnColorChipState(chip, c, c == selectedColor);
            }
        }
    }

    /**
     * 构建一行「标签 + SeekBar + 数值%」。
     * 只在 onStopTrackingTouch 时落盘 + 通知，避免拖动过程中
     * 每帧 apply() + 广播导致的主线程抖动（ANR 风险点）。
     */
    private LinearLayout buildTurnSeekRow(String label, String key,
            int min, int max, int current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(13f);
        labelView.setTextColor(labelColor());
        labelView.setMinWidth(dp(64));
        row.addView(labelView, new LinearLayout.LayoutParams(-2, -2));

        TextView valueView = new TextView(this);
        valueView.setTextSize(12f);
        valueView.setTextColor(0xFF2563EB);
        valueView.setMinWidth(dp(40));
        valueView.setGravity(Gravity.END);
        valueView.setText(current + "%");

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                valueView.setText((min + progress) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int value = min + bar.getProgress();
                saveTurnSignalInt(key, value);
                notifyTurnSignalChanged();
                sendTurnSignalPreview("hazard");
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, -2, 1f);
        seekLp.setMarginStart(dp(4));
        row.addView(seek, seekLp);
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(rowLp);
        return row;
    }

    private Button buildTurnPreviewButton(String text, String direction) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(0xFF007AFF);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFE8F1FF);
        bg.setCornerRadius(dp(10));
        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setColor(0xFFD4E5FF);
        pressedBg.setCornerRadius(dp(10));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        states.addState(new int[]{}, bg);
        b.setBackground(states);
        b.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startOverlayService();   // 复用既有权限引导弹窗
                return;
            }
            startOverlayService(this);
            sendTurnSignalPreview(direction);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMarginEnd(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshTurnEffectLabel() {
        if (turnEffectValue == null) return;
        // 【P2-2 修复】shape==4（静态箭头）时效果不生效，值文本追加提示并置灰
        boolean staticShape = AppPrefs.getTurnSignalShape(this) == 4;
        turnEffectValue.setText(turnEffectName(AppPrefs.getTurnSignalEffect(this))
                + (staticShape ? "（静态箭头无效果）" : ""));
        turnEffectValue.setTextColor(staticShape ? Color.GRAY : 0xFF2563EB);
    }

    private void showTurnEffectDialog() {
        int current = AppPrefs.getTurnSignalEffect(this);
        boolean staticShape = AppPrefs.getTurnSignalShape(this) == 4;
        iosAlertBuilder()
                .setTitle(staticShape ? "转向箭头动画特效（静态箭头无效果）" : "转向箭头动画特效")
                .setSingleChoiceItems(TURN_EFFECT_NAMES, current, (dialog, which) -> {
                    saveTurnSignalInt(AppPrefs.KEY_TURN_SIGNAL_EFFECT, which);
                    if (turnEffectValue != null) {
                        turnEffectValue.setText(turnEffectName(which)
                                + (staticShape ? "（静态箭头无效果）" : ""));
                        turnEffectValue.setTextColor(staticShape ? Color.GRAY : 0xFF2563EB);
                    }
                    notifyTurnSignalChanged();
                    sendTurnSignalPreview("hazard");
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTurnShapeDialog() {
        int current = AppPrefs.getTurnSignalShape(this);
        iosAlertBuilder()
                .setTitle("转向箭头形状")
                .setSingleChoiceItems(TURN_SHAPE_NAMES, current, (dialog, which) -> {
                    saveTurnSignalInt(AppPrefs.KEY_TURN_SIGNAL_SHAPE, which);
                    if (turnShapeValue != null) {
                        turnShapeValue.setText(turnShapeName(which));
                    }
                    refreshTurnEffectLabel();
                    notifyTurnSignalChanged();
                    sendTurnSignalPreview("hazard");
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String turnEffectName(int effect) {
        if (effect < 0 || effect >= TURN_EFFECT_NAMES.length) {
            effect = AppPrefs.DEFAULT_TURN_SIGNAL_EFFECT;
        }
        return TURN_EFFECT_NAMES[effect];
    }

    private String turnShapeName(int shape) {
        if (shape < 0 || shape >= TURN_SHAPE_NAMES.length) {
            shape = AppPrefs.DEFAULT_TURN_SIGNAL_SHAPE;
        }
        return TURN_SHAPE_NAMES[shape];
    }

    private void saveTurnSignalInt(String key, int value) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putInt(key, value)
                .apply();
    }

    /** 广播 + Intent 直传双通道，与本页其它设置项保持一致 */
    void notifyTurnSignalChanged() {
        Intent broadcast = new Intent(AppPrefs.ACTION_TURN_SIGNAL_CHANGED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        sendDirectIntent(OverlayService.ACTION_REBUILD_TURN_SIGNAL);
        // BUG-1 修复：形状/特效/透明度等变更后立即刷新摘要卡，避免显示旧值
        if (turnSummaryView != null) {
            turnSummaryView.setText(buildTurnSummary());
        }
    }

    void sendTurnSignalPreview(String direction) {
        Intent direct = new Intent(this, OverlayService.class);
        direct.setAction(OverlayService.ACTION_PREVIEW_TURN_SIGNAL);
        direct.putExtra(AppPrefs.EXTRA_TURN_SIGNAL_PREVIEW, direction);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Context.class.getMethod("startForegroundService", Intent.class)
                            .invoke(this, direct);
                } catch (Throwable ignored) {
                    startService(direct);
                }
            } else {
                startService(direct);
            }
        } catch (Throwable ignored) {
        }
    }

    private void addScaleControls(LinearLayout parent) {
        // iOS 分组：区段标题 + 白卡 + 两行（开关 + 百分比输入）
        parent.addView(sectionHeader("悬浮窗显示位置"), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = iosCard();
        card.setPadding(dp(16), dp(4), dp(16), dp(4));

        card.addView(overlayScaleRow("主屏悬浮窗", AppPrefs.KEY_MAIN_OVERLAY_ENABLED,
                AppPrefs.getOverlayScalePercent(this), p -> {
                    saveOverlayScalePercent(p);
                    notifyOverlayScaleChanged();
                }), new LinearLayout.LayoutParams(-1, -2));

        View sep = separator();
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(-1, dp(1));
        sepLp.setMargins(dp(16), 0, 0, 0);
        card.addView(sep, sepLp);

        card.addView(overlayScaleRow("副屏悬浮窗", AppPrefs.KEY_CLUSTER_MIRROR_ENABLED,
                AppPrefs.getClusterScalePercent(this), p -> {
                    saveClusterScalePercent(p);
                    notifyClusterMirrorChanged();
                }), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, lp);
    }

    private void addClusterMirrorControls(LinearLayout parent) {
        // iOS 分组：区段标题 + 白卡 + 列表行
        parent.addView(sectionHeader("副屏悬浮窗"), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = iosCard();
        card.setPadding(dp(16), dp(4), dp(16), dp(4));

        // 投屏屏幕：右侧灰色当前值，整行可点选择
        clusterDisplayText = new TextView(this);
        clusterDisplayText.setTextSize(14f);
        clusterDisplayText.setTextColor(secondaryColor());
        updateClusterDisplayText();
        LinearLayout infoRow = settingRow("投屏屏幕", clusterDisplayText);
        infoRow.setClickable(true);
        infoRow.setFocusable(true);
        infoRow.setBackground(rowPress());
        infoRow.setOnClickListener(v -> chooseClusterDisplay());
        card.addView(infoRow, new LinearLayout.LayoutParams(-1, -2));
        card.addView(sep(), sepLp());

        // 副屏位置调节
        card.addView(listRow("副屏位置调节", null, this::showDirectionPadDialog),
                new LinearLayout.LayoutParams(-1, -2));
        card.addView(sep(), sepLp());

        // 悬浮窗样式：右侧显示当前样式名
        overlayUiStyleValue = new TextView(this);
        overlayUiStyleValue.setTextSize(14f);
        overlayUiStyleValue.setTextColor(secondaryColor());
        overlayUiStyleValue.setText(OverlayUiStyles.displayName(AppPrefs.getOverlayUiStyle(this)));
        LinearLayout uiRow = settingRow("悬浮窗样式", overlayUiStyleValue);
        uiRow.setClickable(true);
        uiRow.setFocusable(true);
        uiRow.setBackground(rowPress());
        uiRow.setOnClickListener(v -> chooseOverlayUiStyle());
        card.addView(uiRow, new LinearLayout.LayoutParams(-1, -2));
        card.addView(sep(), sepLp());

        // 文字模式：右侧显示当前模式名
        overlayTextModeValue = new TextView(this);
        overlayTextModeValue.setTextSize(14f);
        overlayTextModeValue.setTextColor(secondaryColor());
        overlayTextModeValue.setText(textModeValue());
        LinearLayout modeRow = settingRow("文字模式", overlayTextModeValue);
        modeRow.setClickable(true);
        modeRow.setFocusable(true);
        modeRow.setBackground(rowPress());
        modeRow.setOnClickListener(v -> chooseTextMode());
        card.addView(modeRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, lp);
    }

    /** 文字模式显示名（不带前缀，用于行右侧值） */
    private String textModeValue() {
        String mode = AppPrefs.getOverlayTextMode(this);
        if (AppPrefs.TEXT_MODE_AUTO.equals(mode))          return "跟随系统";
        if (AppPrefs.TEXT_MODE_FORCE_NIGHT.equals(mode))   return "强制夜间";
        return "强制白天";
    }

    private void addOverlayContentControls(LinearLayout parent) {
        // iOS 分组：区段标题 + 白卡 + 开关行
        parent.addView(sectionHeader("自定义悬浮窗内容"), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = iosCard();
        card.setPadding(dp(16), dp(4), dp(16), dp(4));

        card.addView(contentToggle("红绿灯倒计时", AppPrefs.KEY_SHOW_LIGHT),
                new LinearLayout.LayoutParams(-1, -2));
        card.addView(sep(), sepLp());
        // ≤4s 呼吸动画：倒计时 ≤4 秒时呼吸闪烁提示即将变灯（移植自开源版）
        card.addView(contentToggle("≤4s 呼吸动画", AppPrefs.KEY_LIGHT_BREATHING),
                new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, lp);
    }

    private void addBehaviorControls(LinearLayout parent) {
        // 拆两组卡：「启动行为」+「显示策略」，单卡更矮，减少滚动
        behaviorGroup(parent, "启动行为",
                behaviorToggle("桌面启动直达目标应用", AppPrefs.KEY_LAUNCH_TARGET_FROM_DESKTOP),
                behaviorToggle("高德广播自动显示悬浮窗", AppPrefs.KEY_SHOW_MAIN_WHEN_TARGET_FOREGROUND));

        behaviorGroup(parent, "显示策略",
                behaviorToggle("高德前台隐藏中控悬浮窗", AppPrefs.KEY_HIDE_MAIN_WHEN_TARGET_FOREGROUND),
                behaviorToggle("导航/巡航退出隐藏仪表", AppPrefs.KEY_HIDE_CLUSTER_WHEN_INACTIVE),
                directionToggle("副屏", AppPrefs.KEY_LIGHT_VERTICAL_CLUSTER),
                directionToggle("主屏", AppPrefs.KEY_LIGHT_VERTICAL_MAIN),
                behaviorToggle("超速≤10% 黄框提醒", AppPrefs.KEY_OVERSPEED_MILD_WARNING),
                behaviorToggle("超速>10% 红框提醒", AppPrefs.KEY_OVERSPEED_MEDIUM_WARNING));
    }

    /** 一组策略卡：区段标题 + 白卡 + 若干开关行（行间自动加分隔线） */
    private void behaviorGroup(LinearLayout parent, String title, View... rows) {
        parent.addView(sectionHeader(title), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = iosCard();
        card.setPadding(dp(16), dp(4), dp(16), dp(4));
        for (int i = 0; i < rows.length; i++) {
            card.addView(rows[i], new LinearLayout.LayoutParams(-1, -2));
            if (i < rows.length - 1) {
                View sep = separator();
                LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(-1, dp(1));
                sepLp.setMargins(dp(16), 0, 0, 0);
                card.addView(sep, sepLp);
            }
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, lp);
    }



    // ══════════════════════════════════════════════════════════════════════
    //  iOS 18 组件工厂：分组标题 / 白卡 / 分隔线 / 设置行 / 系统弹窗
    // ══════════════════════════════════════════════════════════════════════

    /** 是否深色模式（跟随系统） */
    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** iOS 分组背景（浅 #F2F2F7 / 深 #000000） */
    private int bgColor() {
        return isDarkMode() ? 0xFF000000 : 0xFFF2F2F7;
    }

    /** iOS 卡片背景（浅 #FFFFFF / 深 #1C1C1E） */
    private int cardColor() {
        return isDarkMode() ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** iOS 主文字（浅 #1C1C1E / 深 #FFFFFF） */
    private int labelColor() {
        return isDarkMode() ? 0xFFFFFFFF : 0xFF1C1C1E;
    }

    /** iOS 副文字/值（浅 #8E8E93 / 深 #98989F） */
    private int secondaryColor() {
        return isDarkMode() ? 0xFF98989F : 0xFF8E8E93;
    }

    /** iOS 分组标题灰（浅 #6C6C70 / 深 #8E8E93） */
    private int sectionHeaderColor() {
        return isDarkMode() ? 0xFF8E8E93 : 0xFF6C6C70;
    }

    /** iOS 分隔线（浅 #E5E5EA / 深 #38383A） */
    private int separatorColor() {
        return isDarkMode() ? 0xFF38383A : 0xFFE5E5EA;
    }

    /** iOS 灰色箭头（浅 #C7C7CC / 深 #48484A） */
    private int chevronColor() {
        return isDarkMode() ? 0xFF48484A : 0xFFC7C7CC;
    }

    /** iOS 分组标题（灰色小字，置于白卡上方） */
    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(sectionHeaderColor());
        tv.setPadding(dp(16), 0, dp(16), dp(6));
        return tv;
    }

    /** iOS 白色分组卡（大圆角、无边框；裁剪子视图使按压高亮不溢出圆角） */
    private LinearLayout iosCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor());
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setClipToOutline(true);
        return card;
    }

    /** iOS 分隔线（左缩进 16dp，与文本对齐） */
    private View separator() {
        View v = new View(this);
        v.setBackgroundColor(separatorColor());
        return v;
    }

    /** 行按压高亮：透明→浅灰（iOS 点击反馈） */
    private StateListDrawable rowPress() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x14000000); // 8% 黑
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** iOS 设置行：左文本 + 右侧控件，行高约 46dp，可点击时带按压高亮 */
    private LinearLayout settingRow(String label, View trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15f);
        tv.setTextColor(labelColor());
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) {
            row.addView(trailing, new LinearLayout.LayoutParams(-2, -2));
        }
        return row;
    }

    /** 灰色右箭头 */
    private TextView chevron() {
        TextView v = new TextView(this);
        v.setText("›");
        v.setTextSize(20f);
        v.setTextColor(chevronColor());
        return v;
    }

    /** iOS 列表行：左标签 + 右灰色当前值 + 箭头，整行可点（跳转/选择型） */
    private LinearLayout listRow(String label, String value, Runnable onClick) {
        LinearLayout trailing = new LinearLayout(this);
        trailing.setOrientation(LinearLayout.HORIZONTAL);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        if (value != null && !value.isEmpty()) {
            TextView val = new TextView(this);
            val.setText(value);
            val.setTextSize(15f);
            val.setTextColor(secondaryColor());
            LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(-2, -2);
            valLp.setMarginEnd(dp(6));
            trailing.addView(val, valLp);
        }
        trailing.addView(chevron(), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout row = settingRow(label, trailing);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowPress());
        if (onClick != null) {
            row.setOnClickListener(v -> onClick.run());
        }
        return row;
    }

    /** iOS 行开关：左文本 + 右侧绿色开关；整行可点切换，带按压高亮 */
    private LinearLayout switchRow(String label, boolean checked, IosSwitch.OnCheckedChangeListener listener) {
        IosSwitch sw = new IosSwitch(this);
        sw.setChecked(checked, false);
        if (listener != null) {
            sw.setOnCheckedChangeListener(listener);
        }
        LinearLayout row = settingRow(label, sw);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowPress());
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    /** 系统弹窗统一 iOS 主题 */
    private AlertDialog.Builder iosAlertBuilder() {
        return new AlertDialog.Builder(this, R.style.IosAlert);
    }

    /** 系统弹窗按钮统一 iOS 蓝 */
    private void styleIosDialogButtons(AlertDialog d) {
        if (d == null) {
            return;
        }
        try {
            android.widget.Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setTextColor(0xFF007AFF);
                pos.setTextSize(16f);
            }
            android.widget.Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) {
                neg.setTextColor(0xFF007AFF);
                neg.setTextSize(16f);
            }
            android.widget.Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neu != null) {
                neu.setTextColor(0xFF007AFF);
                neu.setTextSize(16f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 绘制三角形箭头的 Drawable */
    private static class ArrowDrawable extends android.graphics.drawable.Drawable {
        private final int color;
        private final int direction; // 0=左,1=上,2=右,3=下
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ArrowDrawable(int c, int dir) { color = c; direction = dir; paint.setStyle(android.graphics.Paint.Style.FILL); }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        @Override public void draw(android.graphics.Canvas canvas) {
            float cx = getBounds().centerX(), cy = getBounds().centerY();
            float s = Math.min(getBounds().width(), getBounds().height()) * 0.28f;
            android.graphics.Path path = new android.graphics.Path();
            switch (direction) {
                case 0: // 左 - 尖角朝左，底边在右
                    path.moveTo(cx + s * 0.3f, cy - s); path.lineTo(cx - s, cy); path.lineTo(cx + s * 0.3f, cy + s); break;
                case 1: // 上 - 尖角朝上，底边在下
                    path.moveTo(cx - s, cy + s * 0.3f); path.lineTo(cx, cy - s); path.lineTo(cx + s, cy + s * 0.3f); break;
                case 2: // 右 - 尖角朝右，底边在左
                    path.moveTo(cx - s * 0.3f, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx - s * 0.3f, cy + s); break;
                case 3: // 下 - 尖角朝下，底边在上
                    path.moveTo(cx - s, cy - s * 0.3f); path.lineTo(cx, cy + s); path.lineTo(cx + s, cy - s * 0.3f); break;
            }
            path.close();
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }
    }

    private void showDirectionPadDialog() {
        int btnSize = dp(68);
        int bigRadius = dp(80);
        int containerSize = dp(260);

        FrameLayout circleContainer = new FrameLayout(this);
        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(containerSize, containerSize);
        containerLp.gravity = Gravity.CENTER;
        circleContainer.setLayoutParams(containerLp);

        int centerX = containerSize / 2;
        int centerY = containerSize / 2;

        // 上
        android.widget.ImageView btnUp = directionButton(1, () -> moveClusterBy(0, -dp(2)), () -> moveClusterBy(0, -dp(10)), btnSize);
        FrameLayout.LayoutParams lpUp = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpUp.leftMargin = centerX - btnSize / 2;
        lpUp.topMargin = centerY - bigRadius - btnSize / 2;
        circleContainer.addView(btnUp, lpUp);

        // 下
        android.widget.ImageView btnDown = directionButton(3, () -> moveClusterBy(0, dp(2)), () -> moveClusterBy(0, dp(10)), btnSize);
        FrameLayout.LayoutParams lpDown = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpDown.leftMargin = centerX - btnSize / 2;
        lpDown.topMargin = centerY + bigRadius - btnSize / 2;
        circleContainer.addView(btnDown, lpDown);

        // 左
        android.widget.ImageView btnLeft = directionButton(0, () -> moveClusterBy(-dp(2), 0), () -> moveClusterBy(-dp(10), 0), btnSize);
        FrameLayout.LayoutParams lpLeft = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpLeft.leftMargin = centerX - bigRadius - btnSize / 2;
        lpLeft.topMargin = centerY - btnSize / 2;
        circleContainer.addView(btnLeft, lpLeft);

        // 右
        android.widget.ImageView btnRight = directionButton(2, () -> moveClusterBy(dp(2), 0), () -> moveClusterBy(dp(10), 0), btnSize);
        FrameLayout.LayoutParams lpRight = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpRight.leftMargin = centerX + bigRadius - btnSize / 2;
        lpRight.topMargin = centerY - btnSize / 2;
        circleContainer.addView(btnRight, lpRight);

        // 坐标显示（容器正中心）
        LinearLayout coordCol = new LinearLayout(this);
        coordCol.setOrientation(LinearLayout.VERTICAL);
        coordCol.setGravity(Gravity.CENTER);

        coordTextX = new TextView(this);
        coordTextX.setTextSize(18f);
        coordTextX.setTextColor(labelColor());
        coordTextX.setTypeface(Typeface.MONOSPACE);
        coordTextX.setGravity(Gravity.CENTER);
        coordCol.addView(coordTextX, new LinearLayout.LayoutParams(-2, -2));

        coordTextY = new TextView(this);
        coordTextY.setTextSize(18f);
        coordTextY.setTextColor(labelColor());
        coordTextY.setTypeface(Typeface.MONOSPACE);
        coordTextY.setGravity(Gravity.CENTER);
        coordCol.addView(coordTextY, new LinearLayout.LayoutParams(-2, -2));

        updateCoordText();

        FrameLayout.LayoutParams coordLp = new FrameLayout.LayoutParams(-2, -2);
        coordLp.gravity = Gravity.CENTER;
        circleContainer.addView(coordCol, coordLp);

        // 整体居中
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.addView(circleContainer);

        AlertDialog dialog = iosAlertBuilder()
                .setTitle("副屏位置调节")
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();
        dialog.show();
        styleIosDialogButtons(dialog);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private android.widget.ImageView directionButton(int arrowDir, Runnable clickMove, Runnable longMove, int btnSize) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        // 统一使用向上的箭头 PNG，通过旋转得到四向（0=左 270°, 1=上 0°, 2=右 90°, 3=下 180°）
        iv.setImageResource(R.drawable.light_green_arrow_straight);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        float rot = 0f;
        if (arrowDir == 0) rot = -90f;   // 左
        else if (arrowDir == 2) rot = 90f; // 右
        else if (arrowDir == 3) rot = 180f; // 下
        iv.setRotation(rot);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = btnSize / 5;
        iv.setPadding(pad, pad, pad, pad);
        iv.setClickable(true);
        iv.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF10B981);
        bg.setShape(GradientDrawable.OVAL);
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xFF059669);
        pressed.setShape(GradientDrawable.OVAL);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, bg);
        iv.setBackground(states);

        // 单击：小步移动 (2dp)
        iv.setOnClickListener(v -> clickMove.run());

        // 长按：每 80ms 快速移动一次 (10dp)，松手停止
        final Handler fastHandler = new Handler(Looper.getMainLooper());
        final Runnable[] fastTicker = { null };
        iv.setOnLongClickListener(v -> {
            longMove.run();
            fastTicker[0] = () -> {
                longMove.run();
                fastHandler.postDelayed(fastTicker[0], 80);
            };
            fastHandler.postDelayed(fastTicker[0], 100);
            return true;
        });
        iv.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                fastHandler.removeCallbacksAndMessages(null);
            }
            return false;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnSize, btnSize);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        iv.setLayoutParams(lp);
        return iv;
    }

    private boolean isWideLayout() {
        return getResources().getDisplayMetrics().widthPixels >= getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * 车机级横屏大屏（宽 ≥ 1200 且横屏）：触发字号放大（全屏 1300×900 适配，无左侧避让）。
     * 【分辨率适配 1300×900】极狐阿尔法 S5 车机实际分辨率为 1300×900（横屏整屏），
     * 原阈值 1920（按 1920×1080 假设备）导致 1300×900 不触发 → 字号不放大。改为 ≥1200：
     *   1300×900 车机  → true（目标机型）
     *   1600×900 模拟器 → true（贴近车机行为，测试更真实）
     *   1920×1080      → true（兼容大屏）
     *   手机竖屏        → false
     */
    private boolean isCarScreen() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels && dm.widthPixels >= 1200;
    }

    /** 车机屏字号放大系数：仅放大 sp 字号，dp 间距不变，保持"不小不大"的协调 */
    private float uiFontScale() {
        return isCarScreen() ? 1.15f : 1.0f;
    }

    private void chooseTargetApp() {
        ArrayList<AppChoice> allChoices = new ArrayList<>();
        ArrayList<AppChoice> choices = new ArrayList<>();
        LinearLayout dialogContent = new LinearLayout(this);
        dialogContent.setOrientation(LinearLayout.VERTICAL);
        dialogContent.setPadding(dp(8), 0, dp(8), 0);
        TextView hint = new TextView(this);
        hint.setText("\u6b63\u5728\u52a0\u8f7d\u5df2\u5b89\u88c5\u5e94\u7528\u2026");
        hint.setTextSize(13);
        hint.setTextColor(0xFF4B5563);
        hint.setPadding(dp(16), dp(6), dp(16), dp(10));
        dialogContent.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        ListView listView = new ListView(this);
        listView.setDivider(null);
        TargetAppAdapter adapter = new TargetAppAdapter(choices);
        listView.setAdapter(adapter);
        dialogContent.addView(listView, new LinearLayout.LayoutParams(-1, Math.min(dp(520), getResources().getDisplayMetrics().heightPixels / 2)));
        AlertDialog dialog = iosAlertBuilder()
                .setTitle("\u9009\u62e9\u76ee\u6807\u5e94\u7528")
                .setNegativeButton("\u663e\u793a\u6240\u6709\u5e94\u7528", null)
                .setView(dialogContent)
                .create();
        listView.setOnItemClickListener((parent, view, which, id) -> {
            if (which < 0 || which >= choices.size()) {
                return;
            }
            saveTargetPackage(choices.get(which).packageName);
            updateTargetText();
            startOverlayService();
            dialog.dismiss();
        });
        dialog.show();
        styleIosDialogButtons(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            choices.clear();
            choices.addAll(allChoices);
            if (choices.isEmpty()) {
                choices.add(new AppChoice(AppPrefs.DEFAULT_TARGET_PACKAGE, AppPrefs.DEFAULT_TARGET_PACKAGE, false, false, false, true));
            }
            hint.setText("\u5df2\u663e\u793a\u6240\u6709\u53ef\u89c1\u5e94\u7528\u5305\u3002");
            adapter.notifyDataSetChanged();
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        new Thread(() -> {
            ArrayList<AppChoice> loadedChoices = loadTargetAppChoices();
            ArrayList<AppChoice> filteredChoices = new ArrayList<>();
            for (AppChoice choice : loadedChoices) {
                if (choice.mapNamed || choice.amapPackage) {
                    filteredChoices.add(choice);
                }
            }
            boolean fallbackToAll = filteredChoices.isEmpty();
            ArrayList<AppChoice> visibleChoices = fallbackToAll ? loadedChoices : filteredChoices;
            if (visibleChoices.isEmpty()) {
                visibleChoices.add(new AppChoice(AppPrefs.DEFAULT_TARGET_PACKAGE,
                        AppPrefs.DEFAULT_TARGET_PACKAGE, false, false, false, true));
            }
            final ArrayList<AppChoice> result = visibleChoices;
            runOnUiThread(() -> {
                if (isFinishing() || !dialog.isShowing()) {
                    return;
                }
                allChoices.clear();
                allChoices.addAll(loadedChoices);
                choices.clear();
                choices.addAll(result);
                hint.setText(fallbackToAll
                        ? "\u672a\u627e\u5230 com.autonavi.* \u6216\u540d\u79f0\u5305\u542b\u201c\u5730\u56fe\u201d\u7684\u5e94\u7528\uff0c\u5df2\u663e\u793a\u6240\u6709\u53ef\u89c1\u5e94\u7528\u5305\u3002"
                        : "\u4f18\u5148\u663e\u793a com.autonavi.* \u5305\u540d\u6216\u540d\u79f0\u5305\u542b\u201c\u5730\u56fe\u201d\u7684\u5e94\u7528\u3002");
                adapter.notifyDataSetChanged();
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            });
        }, "target-app-loader").start();
    }

    private ArrayList<AppChoice> loadTargetAppChoices() {
        PackageManager pm = getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PackageManager.MATCH_ALL : 0;
        HashSet<String> launcherPackages = new HashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, flags);
        HashSet<String> seen = new HashSet<>();
        ArrayList<AppChoice> choices = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            launcherPackages.add(pkg);
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            ApplicationInfo appInfo = info.activityInfo.applicationInfo;
            String label = String.valueOf(appInfo.loadLabel(pm));
            choices.add(new AppChoice(label, pkg, isSystemApp(appInfo), true,
                    isMapNamedApp(label), isAmapPackage(pkg)));
        }
        for (ApplicationInfo appInfo : pm.getInstalledApplications(flags)) {
            String pkg = appInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            String label = String.valueOf(appInfo.loadLabel(pm));
            choices.add(new AppChoice(label, pkg, isSystemApp(appInfo),
                    launcherPackages.contains(pkg), isMapNamedApp(label), isAmapPackage(pkg)));
        }
        sortAppChoices(choices);
        return choices;
    }

    private boolean isSystemApp(ApplicationInfo appInfo) {
        return (appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private void sortAppChoices(ArrayList<AppChoice> choices) {
        Collections.sort(choices, Comparator
                .comparing((AppChoice a) -> !a.amapPackage)
                .thenComparing(a -> !a.mapNamed)
                .thenComparing(a -> a.system)
                .thenComparing(a -> a.label.toLowerCase(java.util.Locale.CHINA))
                .thenComparing(a -> a.packageName));
    }

    private boolean isAmapPackage(String packageName) {
        return packageName != null && packageName.startsWith(TARGET_PACKAGE_PREFIX);
    }

    private boolean isMapNamedApp(String label) {
        return label != null && label.contains("\u5730\u56fe");
    }

    void startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            iosAlertBuilder()
                    .setTitle("\u60ac\u6d6e\u7a97\u6743\u9650")
                    .setMessage("\u4f34\u4fa3\u670d\u52a1\u9700\u8981\u60ac\u6d6e\u7a97\u6743\u9650\uff0c\u8bf7\u5728\u63a5\u4e0b\u6765\u7684\u754c\u9762\u4e2d\u5141\u8bb8\u201c\u663e\u793a\u5728\u5176\u4ed6\u5e94\u7528\u7684\u4e0a\u5c42\u201d\u3002")
                    .setPositiveButton("\u53bb\u8bbe\u7f6e", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Throwable ignored) {}
                    })
                    .setNegativeButton("\u53d6\u6d88", null)
                    .show();
            return;
        }
        startOverlayService(this);
    }

    static void startOverlayService(Context context) {
        Intent intent = new Intent(context, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(context, intent);
            } catch (Throwable ignored) {
                context.startService(intent);
            }
        } else {
            context.startService(intent);
        }
    }

    private void startCompanionService() {
        startCompanionService(true);
    }

    private void startCompanionService(boolean showToast) {
        if (!AppPrefs.isMainOverlayEnabled(this)
                && !AppPrefs.isClusterMirrorEnabled(this)
                && !AppPrefs.isShowMainWhenTargetForegroundEnabled(this)) {
            if (showToast) {
                Toast.makeText(this, "\u8bf7\u5148\u52fe\u9009\u4e3b\u5c4f\u60ac\u6d6e\u7a97\u3001\u526f\u5c4f\u60ac\u6d6e\u7a97\u6216\u9ad8\u5fb7\u5e7f\u64ad\u81ea\u52a8\u663e\u793a", Toast.LENGTH_LONG).show();
            }
            return;
        }
        startOverlayService();
        notifyMainOverlayChanged();
        notifyClusterMirrorChanged();
        if (showToast) {
            Toast.makeText(this, "\u5df2\u6309\u9009\u9879\u542f\u52a8\u4f34\u4fa3\u670d\u52a1", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopCompanionService() {
        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(OverlayService.ACTION_STOP_SERVICE);
        try {
            startService(stopIntent);
        } catch (Throwable ignored) {
            stopService(new Intent(this, OverlayService.class));
        }
        Toast.makeText(this, "\u5df2\u5173\u95ed\u4f34\u4fa3\u670d\u52a1", Toast.LENGTH_SHORT).show();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void openTargetApp() {
        String pkg = AppPrefs.getTargetPackage(this);
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            startActivity(launch);
        } else {
            Toast.makeText(this, "\u65e0\u6cd5\u542f\u52a8\uff1a" + pkg + "\uff08\u672a\u5b89\u88c5\u6216\u65e0\u542f\u52a8\u9875\uff09", Toast.LENGTH_LONG).show();
        }
    }

    private boolean redirectDesktopLaunchToTarget(Intent sourceIntent) {
        if (sourceIntent != null && sourceIntent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            clearPendingDesktopLaunch();
            return false;
        }
        if (!AppPrefs.isLaunchTargetFromDesktopEnabled(this)) {
            clearPendingDesktopLaunch();
            return false;
        }
        if (sourceIntent == null
                || !Intent.ACTION_MAIN.equals(sourceIntent.getAction())
                || !sourceIntent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            return false;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(AppPrefs.getTargetPackage(this));
        if (launch == null) {
            clearPendingDesktopLaunch();
            return false;
        }
        long now = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE);
        long lastLaunchAt = prefs.getLong(KEY_LAST_DESKTOP_LAUNCH_AT, 0L);
        if (lastLaunchAt > 0L
                && now >= lastLaunchAt
                && now - lastLaunchAt <= DOUBLE_DESKTOP_LAUNCH_WINDOW_MS) {
            clearPendingDesktopLaunch();
            return false;
        }
        prefs.edit().putLong(KEY_LAST_DESKTOP_LAUNCH_AT, now).commit();
        if (AppPrefs.isMainOverlayEnabled(this)
                || AppPrefs.isClusterMirrorEnabled(this)
                || AppPrefs.isShowMainWhenTargetForegroundEnabled(this)) {
            startOverlayService(this);
        }
        startActivity(launch);
        finish();
        return true;
    }

    private void clearPendingDesktopLaunch() {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_DESKTOP_LAUNCH_AT)
                .commit();
    }

    private void chooseClusterDisplay() {
        ArrayList<DisplayChoice> choices = getClusterDisplayChoices();
        String[] labels = new String[choices.size() + 1];
        labels[0] = "\u81ea\u52a8\u9009\u62e9\n\u4f18\u5148\u4f7f\u7528\u7cfb\u7edf\u8ba4\u5b9a\u7684\u526f\u5c4f";
        for (int i = 0; i < choices.size(); i++) {
            DisplayChoice choice = choices.get(i);
            labels[i + 1] = choice.label + "\nID " + choice.displayId;
        }
        int currentId = AppPrefs.getClusterDisplayId(this);
        int checked = 0;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).displayId == currentId) {
                checked = i + 1;
                break;
            }
        }
        iosAlertBuilder()
                .setTitle("\u9009\u62e9\u6295\u5c4f\u5c4f\u5e55")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    saveClusterDisplayId(which == 0 ? -1 : choices.get(which - 1).displayId);
                    updateClusterDisplayText();
                    startOverlayService();
                    notifyClusterMirrorChanged();
                    dialog.dismiss();
                })
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "\u65e0\u6cd5\u6253\u5f00\u94fe\u63a5", Toast.LENGTH_SHORT).show();
        }
    }

















    private void chooseDownloadSource(String title, String githubUrl, String mirrorUrl) {
        String[] labels = {
                "\u955c\u50cf\u7ad9\uff08\u4e0b\u8f7d ZIP\uff0c\u5feb\uff09\n" + mirrorUrl,
                "GitHub \u539f\u7ad9\uff08\u53ef\u80fd\u8f83\u6162\uff09\n" + githubUrl
        };
        iosAlertBuilder()
                .setTitle(title)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        openUrl(mirrorUrl);
                    } else {
                        openUrl(githubUrl);
                    }
                })
                .show();
    }

    private void updateTargetText() {
        if (targetText != null) {
            String label = "\u9501\u5b9a\u5e94\u7528 ";
            String pkg = AppPrefs.getTargetPackage(this);
            android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(label + pkg);
            ssb.setSpan(new android.text.style.ForegroundColorSpan(secondaryColor()), 0, label.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFF007AFF), label.length(), label.length() + pkg.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            targetText.setText(ssb);
        }
    }

    private void saveTargetPackage(String packageName) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putString(AppPrefs.KEY_TARGET_PACKAGE, packageName)
                .apply();
    }

    private void saveOverlayScalePercent(int percent) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putInt(AppPrefs.KEY_OVERLAY_SCALE_PERCENT, AppPrefs.clampOverlayScalePercent(percent))
                .apply();
    }

    private LinearLayout contentToggle(String text, String key) {
        IosSwitch sw = new IosSwitch(this);
        sw.setChecked(AppPrefs.isOverlayContentEnabled(this, key), false);
        sw.setOnCheckedChangeListener((s, isChecked) -> {
            saveOverlayContentEnabled(key, isChecked);
            notifyOverlayContentChanged();
        });
        LinearLayout row = settingRow(text, sw);
        row.setClickable(true);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private LinearLayout directionToggle(String prefix, String key) {
        IosSwitch sw = new IosSwitch(this);
        sw.setChecked(AppPrefs.isBehaviorEnabled(this, key), false);
        TextView label = new TextView(this);
        label.setTextSize(15f);
        label.setTextColor(labelColor());
        label.setText(getDirectionToggleText(prefix, key, sw.isChecked()));
        sw.setOnCheckedChangeListener((s, isChecked) -> {
            saveBehaviorEnabled(key, isChecked);
            label.setText(getDirectionToggleText(prefix, key, isChecked));
            notifyDisplayPolicyChanged();
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setClickable(true);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private String getDirectionToggleText(String prefix, String key, boolean isVertical) {
        return prefix + "-红绿灯" + (isVertical ? "竖向模式中" : "横向模式中");
    }

    private LinearLayout behaviorToggle(String text, String key) {
        IosSwitch sw = new IosSwitch(this);
        sw.setChecked(AppPrefs.isBehaviorEnabled(this, key), false);
        sw.setOnCheckedChangeListener((s, isChecked) -> {
            saveBehaviorEnabled(key, isChecked);
            if (AppPrefs.KEY_HIDE_MAIN_WHEN_TARGET_FOREGROUND.equals(key)
                    && isChecked && !AppPrefs.hasUsageStatsAccess(this)) {
                Toast.makeText(this, "请为 AMap Companion 开启使用情况访问权限", Toast.LENGTH_LONG).show();
                openUsageAccessSettings();
            }
            if (isChecked) {
                if (AppPrefs.KEY_START_SERVICE_ON_APP_OPEN.equals(key)
                        || AppPrefs.KEY_SHOW_MAIN_WHEN_TARGET_FOREGROUND.equals(key)) {
                    startCompanionService(false);
                }
            }
            notifyDisplayPolicyChanged();
            if (!isChecked) {
                stopServiceIfNoVisuals();
            }
        });
        LinearLayout row = settingRow(text, sw);
        row.setClickable(true);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    /** iOS 行：文本 + 右侧（开关 + 百分比输入框 + % 后缀） */
    private LinearLayout overlayScaleRow(String text, String key, int percent,
                                         java.util.function.Consumer<Integer> onCommit) {
        IosSwitch sw = new IosSwitch(this);
        sw.setChecked(AppPrefs.KEY_CLUSTER_MIRROR_ENABLED.equals(key)
                ? AppPrefs.isClusterMirrorEnabled(this)
                : AppPrefs.isMainOverlayEnabled(this), false);
        sw.setOnCheckedChangeListener((s, isChecked) -> {
            if (AppPrefs.KEY_CLUSTER_MIRROR_ENABLED.equals(key)) {
                saveClusterMirrorEnabled(isChecked);
                if (isChecked) {
                    startOverlayService();
                }
                notifyClusterMirrorChanged();
            } else {
                saveMainOverlayEnabled(isChecked);
                if (isChecked) {
                    startOverlayService();
                }
                notifyMainOverlayChanged();
            }
            notifyDisplayPolicyChanged();
            if (!isChecked) {
                stopServiceIfNoVisuals();
            }
        });

        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(percent));
        input.setSelection(input.getText().length());
        input.setTextSize(14f);
        input.setTextColor(labelColor());
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        input.setMinWidth(dp(56));
        input.setMaxWidth(dp(72));
        input.setBackgroundResource(R.drawable.ios_edit_bg);
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                try {
                    int p = Integer.parseInt(input.getText().toString().trim());
                    p = AppPrefs.clampOverlayScalePercent(p);
                    input.setText(String.valueOf(p));
                    onCommit.accept(p);
                } catch (NumberFormatException ignored) {
                    input.setText(String.valueOf(percent));
                }
            }
        });

        TextView suffix = new TextView(this);
        suffix.setText("%");
        suffix.setTextSize(14f);
        suffix.setTextColor(secondaryColor());

        LinearLayout trailing = new LinearLayout(this);
        trailing.setOrientation(LinearLayout.HORIZONTAL);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        trailing.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-2, -2);
        inputLp.setMargins(dp(8), 0, dp(4), 0);
        trailing.addView(input, inputLp);
        trailing.addView(suffix);

        return settingRow(text, trailing);
    }

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开使用情况访问设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveOverlayContentEnabled(String key, boolean enabled) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(key, enabled)
                .apply();
    }

    private void saveBehaviorEnabled(String key, boolean enabled) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(key, enabled)
                .apply();
    }

    private void chooseOverlayUiStyle() {
        String currentStyle = AppPrefs.getOverlayUiStyle(this);
        int checked = OverlayUiStyles.indexOf(currentStyle);
        iosAlertBuilder()
                .setTitle("\u9009\u62e9\u60ac\u6d6e\u7a97\u6837\u5f0f")
                .setSingleChoiceItems(OverlayUiStyles.labels(), checked, (dialog, which) -> {
                    String style = OverlayUiStyles.ALL[which].id;
                    saveOverlayUiStyle(style);
                    if (overlayUiStyleValue != null) {
                        overlayUiStyleValue.setText(OverlayUiStyles.displayName(style));
                    }
                    notifyOverlayStyleChanged();
                    dialog.dismiss();
                })
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    private void chooseTextMode() {
        String[] labels = {
                "\u8ddf\u968f\u7cfb\u7edf\uff08\u7cfb\u7edf\u591c\u95f4\u6a21\u5f0f\u5373\u591c\u95f4\uff0c\u5426\u5219\u767d\u5929\uff09",
                "\u5f3a\u5236\u767d\u5929",
                "\u5f3a\u5236\u591c\u95f4"
        };
        String current = AppPrefs.getOverlayTextMode(this);
        int checked;
        if (AppPrefs.TEXT_MODE_AUTO.equals(current))          checked = 0;
        else if (AppPrefs.TEXT_MODE_FORCE_NIGHT.equals(current)) checked = 2;
        else                                                   checked = 1;
        iosAlertBuilder()
                .setTitle("\u9009\u62e9\u6587\u5b57\u6a21\u5f0f")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    String mode;
                    if (which == 0)      mode = AppPrefs.TEXT_MODE_AUTO;
                    else if (which == 2) mode = AppPrefs.TEXT_MODE_FORCE_NIGHT;
                    else                 mode = AppPrefs.TEXT_MODE_LIGHT;
                    saveOverlayTextMode(mode);
                    if (overlayTextModeValue != null) {
                        overlayTextModeValue.setText(textModeValue());
                    }
                    notifyOverlayStyleChanged();
                    dialog.dismiss();
                })
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    private void saveOverlayTextMode(String mode) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putString(AppPrefs.KEY_TEXT_MODE, mode)
                .apply();
    }

    private void saveOverlayUiStyle(String style) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putString(AppPrefs.KEY_OVERLAY_UI_STYLE, OverlayUiStyles.normalize(style))
                .apply();
    }







    private void notifyOverlayScaleChanged() {
        startOverlayService();
        Intent intent = new Intent(AppPrefs.ACTION_OVERLAY_SCALE_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void saveMainOverlayEnabled(boolean enabled) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(AppPrefs.KEY_MAIN_OVERLAY_ENABLED, enabled)
                .apply();
    }

    private void saveClusterMirrorEnabled(boolean enabled) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(AppPrefs.KEY_CLUSTER_MIRROR_ENABLED, enabled)
                .apply();
    }

    private void notifyMainOverlayChanged() {
        Intent intent = new Intent(AppPrefs.ACTION_MAIN_OVERLAY_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void notifyClusterMirrorChanged() {
        Intent intent = new Intent(AppPrefs.ACTION_CLUSTER_MIRROR_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void notifyClusterPositionChanged() {
        Intent intent = new Intent(AppPrefs.ACTION_CLUSTER_POSITION_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    // 验证: 内容变更后悬浮窗应实时更新
    private void notifyOverlayContentChanged() {
        Intent broadcast = new Intent(AppPrefs.ACTION_OVERLAY_CONTENT_CHANGED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        sendDirectIntent(OverlayService.ACTION_REBUILD_CONTENT);
    }

    private void notifyOverlayStyleChanged() {
        // 方式1:广播(原始方案)
        Intent broadcast = new Intent(AppPrefs.ACTION_OVERLAY_STYLE_CHANGED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        // 方式2:Intent 直传(确保服务一定能收到)
        sendDirectIntent(OverlayService.ACTION_REBUILD_STYLE);
    }

    private void notifyDisplayPolicyChanged() {
        Intent broadcast = new Intent(AppPrefs.ACTION_DISPLAY_POLICY_CHANGED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        sendDirectIntent(OverlayService.ACTION_REBUILD_POLICY);
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (android.app.ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(info.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void stopServiceIfNoVisuals() {
        if (!AppPrefs.isMainOverlayEnabled(this)
                && !AppPrefs.isClusterMirrorEnabled(this)
                && !AppPrefs.isAutoStartEnabled(this)
                // 【2026-08-03 审查修复 P2-1】转向 HUD 可独立于主悬浮窗工作，
                // 只要极狐转向开着服务就必须活着，否则 logcat 监控线程会被杀（与 OverlayService 一致）
                && !AppPrefs.isTurnSignalOverlayEnabled(this)
                && !AppPrefs.isShowMainWhenTargetForegroundEnabled(this)) {
            stopService(new Intent(this, OverlayService.class));
        }
    }

    private void sendDirectIntent(String action) {
        Intent direct = new Intent(this, OverlayService.class);
        direct.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { Context.class.getMethod("startForegroundService", Intent.class).invoke(this, direct); } catch (Throwable ignored) { startService(direct); }
        } else {
            startService(direct);
        }
    }

    private void saveClusterScalePercent(int percent) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putInt(AppPrefs.KEY_CLUSTER_SCALE_PERCENT, AppPrefs.clampOverlayScalePercent(percent))
                .apply();
    }

    private void updateClusterDisplayText() {
        if (clusterDisplayText == null) {
            return;
        }
        int selectedId = AppPrefs.getClusterDisplayId(this);
        if (selectedId < 0) {
            clusterDisplayText.setText("自动选择");
            return;
        }
        DisplayChoice selected = null;
        ArrayList<DisplayChoice> choices = getClusterDisplayChoices();
        for (DisplayChoice choice : choices) {
            if (choice.displayId == selectedId) {
                selected = choice;
                break;
            }
        }
        if (selected != null) {
            clusterDisplayText.setText(selected.label + " (ID " + selected.displayId + ")");
        } else {
            clusterDisplayText.setText("已指定 ID " + selectedId + "（当前未检测到）");
        }
    }

    private void saveClusterDisplayId(int displayId) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putInt(AppPrefs.KEY_CLUSTER_DISPLAY_ID, displayId)
                .apply();
    }

    private ArrayList<DisplayChoice> getClusterDisplayChoices() {
        ArrayList<DisplayChoice> choices = new ArrayList<>();
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) {
            return choices;
        }
        Display[] displays = manager.getDisplays();
        for (Display display : displays) {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                continue;
            }
            String name = display.getName();
            if (TextUtils.isEmpty(name)) {
                name = "\u526f\u5c4f";
            }
            choices.add(new DisplayChoice(display.getDisplayId(), name));
        }
        Collections.sort(choices, Comparator.comparingInt(choice -> choice.displayId));
        return choices;
    }

    private void updateCoordText() {
        SharedPreferences prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE);
        int x = prefs.getInt(AppPrefs.KEY_CLUSTER_X, 600);
        int y = prefs.getInt(AppPrefs.KEY_CLUSTER_Y, 180);
        String xs = "X: " + x;
        String ys = "Y: " + y;
        if (coordTextX != null) { coordTextX.setText(xs); }
        if (coordTextY != null) { coordTextY.setText(ys); }
    }

    private void moveClusterBy(int dx, int dy) {
        SharedPreferences prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE);
        int x = Math.max(0, prefs.getInt(AppPrefs.KEY_CLUSTER_X, 600) + dx);
        int y = Math.max(0, prefs.getInt(AppPrefs.KEY_CLUSTER_Y, 180) + dy);
        boolean saved = prefs.edit()
                .putInt(AppPrefs.KEY_CLUSTER_X, x)
                .putInt(AppPrefs.KEY_CLUSTER_Y, y)
                .commit();
        startOverlayService();
        if (saved) {
            notifyClusterPositionChanged();
        } else {
            notifyClusterMirrorChanged();
        }
        updateCoordText();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class TargetAppAdapter extends BaseAdapter {
        private final ArrayList<AppChoice> choices;
        private final HashMap<String, Drawable> iconCache = new HashMap<>();

        TargetAppAdapter(ArrayList<AppChoice> choices) {
            this.choices = choices;
        }

        @Override
        public int getCount() {
            return choices.size();
        }

        @Override
        public Object getItem(int position) {
            return choices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppChoice choice = choices.get(position);
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(18), dp(12), dp(18), dp(12));

            ImageView icon = new ImageView(MainActivity.this);
            icon.setImageDrawable(loadAppIcon(choice.packageName));
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(46), dp(46));
            iconLp.setMargins(0, 0, dp(14), 0);
            root.addView(icon, iconLp);

            LinearLayout content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            root.addView(content, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView title = new TextView(MainActivity.this);
            title.setText(choice.label);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(labelColor());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView packageView = new TextView(MainActivity.this);
            packageView.setText(choice.packageName);
            packageView.setTextSize(12);
            packageView.setTextColor(0xFF6B7280);
            packageView.setSingleLine(true);
            packageView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(-1, -2);
            pkgLp.setMargins(0, dp(4), 0, 0);
            content.addView(packageView, pkgLp);

            LinearLayout tags = new LinearLayout(MainActivity.this);
            tags.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tagsLp = new LinearLayout.LayoutParams(-1, -2);
            tagsLp.setMargins(0, dp(8), 0, 0);
            content.addView(tags, tagsLp);

            tags.addView(appTag(choice.amapPackage ? "\u9ad8\u5fb7\u5305\u540d" : (choice.mapNamed ? "\u5730\u56fe\u5339\u914d" : "\u5168\u90e8\u5217\u8868"),
                    choice.amapPackage ? 0xFFEFF6FF : (choice.mapNamed ? 0xFFECFDF5 : 0xFFF3F4F6),
                    choice.amapPackage ? 0xFF1D4ED8 : (choice.mapNamed ? 0xFF047857 : 0xFF4B5563)));
            tags.addView(appTag(choice.system ? "\u7cfb\u7edf\u5e94\u7528" : "\u7528\u6237\u5e94\u7528",
                    choice.system ? 0xFFFFF7ED : 0xFFEFF6FF,
                    choice.system ? 0xFFC2410C : 0xFF1D4ED8));
            tags.addView(appTag(choice.launchable ? "\u53ef\u6253\u5f00" : "\u65e0\u684c\u9762\u56fe\u6807",
                    choice.launchable ? 0xFFF0FDFA : 0xFFFEF2F2,
                    choice.launchable ? 0xFF0F766E : 0xFFB91C1C));
            return root;
        }

        private Drawable loadAppIcon(String packageName) {
            Drawable cached = iconCache.get(packageName);
            if (cached != null) {
                return cached;
            }
            try {
                Drawable icon = getPackageManager().getApplicationIcon(packageName);
                iconCache.put(packageName, icon);
                return icon;
            } catch (Exception ignored) {
                return getResources().getDrawable(android.R.drawable.sym_def_app_icon);
            }
        }

        private TextView appTag(String text, int backgroundColor, int textColor) {
            TextView tag = new TextView(MainActivity.this);
            tag.setText(text);
            tag.setTextSize(11);
            tag.setTextColor(textColor);
            tag.setTypeface(Typeface.DEFAULT_BOLD);
            tag.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable background = new GradientDrawable();
            background.setColor(backgroundColor);
            background.setCornerRadius(dp(999));
            tag.setBackground(background);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(6), 0);
            tag.setLayoutParams(lp);
            return tag;
        }
    }

    private static final class AppChoice {
        final String label;
        final String packageName;
        final boolean system;
        final boolean launchable;
        final boolean mapNamed;
        final boolean amapPackage;

        AppChoice(String label, String packageName, boolean system, boolean launchable, boolean mapNamed, boolean amapPackage) {
            this.label = label;
            this.packageName = packageName;
            this.system = system;
            this.launchable = launchable;
            this.mapNamed = mapNamed;
            this.amapPackage = amapPackage;
        }
    }

    private static final class DisplayChoice {
        final int displayId;
        final String label;

        DisplayChoice(int displayId, String label) {
            this.displayId = displayId;
            this.label = label;
        }
    }
}

