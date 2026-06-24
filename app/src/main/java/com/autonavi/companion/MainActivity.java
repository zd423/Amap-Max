package com.autonavi.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
// import android.content.ClipData;      [REMOVED 2026-06-23]
// import android.content.ClipboardManager; [REMOVED 2026-06-23]
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
// import android.os.Environment; [REMOVED 2026-06-23]
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
import android.widget.SeekBar;
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
    // [REMOVED 2026-06-23] REQUEST_READ_LOGS_PERMISSION / REQUEST_STORAGE_PERMISSIONS

    private TextView targetText;
    private TextView overlayScaleText;
    private TextView clusterScaleText;
    private TextView clusterDisplayText;
    private TextView coordTextX;
    private TextView coordTextY;
    private Button overlayTextModeButton;
    private Button overlayUiStyleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (redirectDesktopLaunchToTarget(getIntent())) {
            return;
        }
        View content = buildContent();
        setContentView(content);
        autoStartServiceOnAppOpen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        redirectDesktopLaunchToTarget(intent);
    }

    private void autoStartServiceOnAppOpen() {
        if (!AppPrefs.isStartServiceOnAppOpenEnabled(this)) {
            return;
        }
        targetText.postDelayed(() -> startCompanionService(false), 350L);
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF3F6FA);
        boolean wideLayout = isWideLayout();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = card(0xFF111827);
        root.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("AMap Companion");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        hero.addView(title, new LinearLayout.LayoutParams(-1, -2));

        targetText = new TextView(this);
        targetText.setTextSize(14f);
        targetText.setTextColor(0xFFD1D5DB);
        targetText.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(-1, -2);
        targetLp.setMargins(0, dp(8), 0, 0);
        hero.addView(targetText, targetLp);
        updateTargetText();

        // [REMOVED] 2026-06-13 公告区块（空白色卡片）已删除
        // addAnnouncementSection(root);


        LinearLayout contentArea = new LinearLayout(this);
        contentArea.setOrientation(wideLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1, -2);
        contentLp.setMargins(0, dp(14), 0, 0);
        root.addView(contentArea, contentLp);

        LinearLayout leftColumn = new LinearLayout(this);
        leftColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(-1, -2);
        if (wideLayout) {
            leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
            leftLp.setMargins(0, 0, dp(7), 0);
        }
        contentArea.addView(leftColumn, leftLp);

        LinearLayout rightColumn = new LinearLayout(this);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(-1, -2);
        if (wideLayout) {
            rightLp = new LinearLayout.LayoutParams(0, -2, 1f);
            rightLp.setMargins(dp(7), 0, 0, 0);
        } else {
            rightLp.setMargins(0, dp(14), 0, 0);
        }
        contentArea.addView(rightColumn, rightLp);

        LinearLayout actions = card(Color.WHITE);
        leftColumn.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        addActionButtons(actions, wideLayout);

        // [ADDED] 2026-06-23 自动启动与显示策略移到左侧
        LinearLayout behaviorCard = card(Color.WHITE);
        LinearLayout.LayoutParams behaviorLp = new LinearLayout.LayoutParams(-1, -2);
        behaviorLp.setMargins(0, dp(14), 0, 0);
        leftColumn.addView(behaviorCard, behaviorLp);
        addBehaviorControls(behaviorCard);

        LinearLayout settings = card(Color.WHITE);
        rightColumn.addView(settings, new LinearLayout.LayoutParams(-1, -2));
        addScaleControls(settings);
        addClusterMirrorControls(settings);
        addOverlayTargetControls(settings);
        addOverlayContentControls(settings);

        // [ADDED] 2026-06-23 悬浮窗样式和文字模式移到右侧
        LinearLayout styleCard = card(Color.WHITE);
        LinearLayout.LayoutParams styleLp = new LinearLayout.LayoutParams(-1, -2);
        styleLp.setMargins(0, dp(14), 0, 0);
        rightColumn.addView(styleCard, styleLp);
        addStyleAndModeControls(styleCard);
        // [REMOVED] 2026-06-11 开源信息区块已删除

        return scroll;
    }

    private void addActionButtons(LinearLayout parent, boolean wideLayout) {
        // 圆角边框卡片
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        if (wideLayout) {
            addButtonPair(card,
                    button("\u9009\u62e9\u76ee\u6807\u5e94\u7528", v -> chooseTargetApp(), 0xFF3B82F6),
                    button("\u6388\u6743\u60ac\u6d6e\u7a97", v -> requestOverlayPermission(), 0xFF60A5FA));
            addButtonPair(card,
                    button("\u542f\u52a8\u4f34\u4fa3\u670d\u52a1", v -> startCompanionService(), 0xFF10B981),
                    button("\u5173\u95ed\u4f34\u4fa3\u670d\u52a1", v -> stopCompanionService(), 0xFFF59E0B));
            card.addView(button("\u6253\u5f00\u76ee\u6807\u5e94\u7528", v -> openTargetApp(), 0xFF6366F1));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(10));
            parent.addView(card, lp);

            // [ADDED] 2026-06-23 \u526f\u5c4f\u65b9\u5411\u952e+\u5750\u6807\u5361\u7247\uff08\u5706\u89d2\u8fb9\u6846\uff09
            addClusterDirectionCard(parent);
            return;
        }
        card.addView(button("\u9009\u62e9\u76ee\u6807\u5e94\u7528", v -> chooseTargetApp(), 0xFF3B82F6));
        card.addView(button("\u6388\u6743\u60ac\u6d6e\u7a97", v -> requestOverlayPermission(), 0xFF60A5FA));
        card.addView(button("\u542f\u52a8\u4f34\u4fa3\u670d\u52a1", v -> startCompanionService(), 0xFF10B981));
        card.addView(button("\u5173\u95ed\u4f34\u4fa3\u670d\u52a1", v -> stopCompanionService(), 0xFFF59E0B));
        card.addView(button("\u6253\u5f00\u76ee\u6807\u5e94\u7528", v -> openTargetApp(), 0xFF6366F1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        parent.addView(card, lp);

        // [ADDED] 2026-06-23 副屏方向键+坐标卡片（竖屏分\u652f）
        addClusterDirectionCard(parent);
    }
    private void addAnnouncementSection(LinearLayout root) {
        LinearLayout section = card(Color.WHITE);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.setMargins(0, dp(14), 0, 0);
        root.addView(section, sectionLp);

        // [REMOVED] 2026-06-11 公告区块已删除
    }

    // [REMOVED] 2026-06-11 开源信息区块已删除

    // [MODIFIED] 2026-06-23 实时生效 + 防抖，去掉应用按钮
    private final Handler overlayScaleDebouncer = new Handler(Looper.getMainLooper());
    private static final long OVERLAY_SCALE_DEBOUNCE_MS = 150;

    private void addScaleControls(LinearLayout parent) {
        // 圆角边框卡片
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 卡片标题
        TextView title = new TextView(this);
        title.setText("悬浮窗大小");
        title.setTextSize(14f);
        title.setTextColor(0xFF111827);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 主屏大小
        LinearLayout mainBox = new LinearLayout(this);
        mainBox.setOrientation(LinearLayout.VERTICAL);
        mainBox.setPadding(0, dp(12), 0, dp(8));

        overlayScaleText = new TextView(this);
        overlayScaleText.setTextSize(13f);
        overlayScaleText.setTextColor(0xFF334155);
        mainBox.addView(overlayScaleText, new LinearLayout.LayoutParams(-1, -2));

        SeekBar mainSeekBar = new SeekBar(this);
        mainSeekBar.setMax(AppPrefs.MAX_OVERLAY_SCALE_PERCENT - AppPrefs.MIN_OVERLAY_SCALE_PERCENT);
        mainSeekBar.setProgress(AppPrefs.getOverlayScalePercent(this) - AppPrefs.MIN_OVERLAY_SCALE_PERCENT);
        mainSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int percent = AppPrefs.MIN_OVERLAY_SCALE_PERCENT + progress;
                updateOverlayScaleText(percent);
                if (fromUser) {
                    saveOverlayScalePercent(percent);
                    overlayScaleDebouncer.removeCallbacksAndMessages(null);
                    overlayScaleDebouncer.postDelayed(() -> notifyOverlayScaleChanged(), OVERLAY_SCALE_DEBOUNCE_MS);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                overlayScaleDebouncer.removeCallbacksAndMessages(null);
            }
            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int percent = AppPrefs.MIN_OVERLAY_SCALE_PERCENT + bar.getProgress();
                saveOverlayScalePercent(percent);
                updateOverlayScaleText(percent);
                overlayScaleDebouncer.removeCallbacksAndMessages(null);
                notifyOverlayScaleChanged();
            }
        });
        mainBox.addView(mainSeekBar, new LinearLayout.LayoutParams(-1, -2));
        updateOverlayScaleText(AppPrefs.getOverlayScalePercent(this));

        card.addView(mainBox, new LinearLayout.LayoutParams(-1, -2));

        // 副屏大小
        LinearLayout clusterBox = new LinearLayout(this);
        clusterBox.setOrientation(LinearLayout.VERTICAL);
        clusterBox.setPadding(0, dp(12), 0, 0);

        clusterScaleText = new TextView(this);
        clusterScaleText.setTextSize(13f);
        clusterScaleText.setTextColor(0xFF334155);
        clusterBox.addView(clusterScaleText, new LinearLayout.LayoutParams(-1, -2));

        SeekBar clusterSeekBar = new SeekBar(this);
        clusterSeekBar.setMax(AppPrefs.MAX_OVERLAY_SCALE_PERCENT - AppPrefs.MIN_OVERLAY_SCALE_PERCENT);
        clusterSeekBar.setProgress(AppPrefs.getClusterScalePercent(this) - AppPrefs.MIN_OVERLAY_SCALE_PERCENT);
        clusterSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int percent = AppPrefs.MIN_OVERLAY_SCALE_PERCENT + progress;
                updateClusterScaleText(percent);
                if (fromUser) {
                    saveClusterScalePercent(percent);
                    notifyClusterMirrorChanged();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int percent = AppPrefs.MIN_OVERLAY_SCALE_PERCENT + bar.getProgress();
                saveClusterScalePercent(percent);
                updateClusterScaleText(percent);
                notifyClusterMirrorChanged();
            }
        });
        clusterBox.addView(clusterSeekBar, new LinearLayout.LayoutParams(-1, -2));
        updateClusterScaleText(AppPrefs.getClusterScalePercent(this));

        card.addView(clusterBox, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        parent.addView(card, lp);
    }

    private void addClusterMirrorControls(LinearLayout parent) {
        // 圆角边框卡片
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("\u526f\u5c4f\u60ac\u6d6e\u7a97");
        title.setTextSize(14f);
        title.setTextColor(0xFF111827);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        clusterDisplayText = new TextView(this);
        clusterDisplayText.setTextSize(13f);
        clusterDisplayText.setTextColor(0xFF334155);
        LinearLayout.LayoutParams displayTextLp = new LinearLayout.LayoutParams(-1, -2);
        displayTextLp.setMargins(0, dp(10), 0, 0);
        card.addView(clusterDisplayText, displayTextLp);
        updateClusterDisplayText();

        card.addView(button("\u9009\u62e9\u6295\u5c4f\u5c4f\u5e55", v -> chooseClusterDisplay(), 0xFF3B82F6));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        parent.addView(card, lp);
    }

    // [ADDED] 2026-06-23 \u526f\u5c4f\u65b9\u5411\u952e+\u5750\u6807\u5361\u7247\uff08\u5706\u89d2\u8fb9\u6846\uff0c\u653e\u5728\u5de6\u4fa7\u9762\u677f\uff09
    private void addClusterDirectionCard(LinearLayout parent) {
        // \u5706\u89d2\u8fb9\u6846\u5361\u7247
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        FrameLayout card = new FrameLayout(this);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

                                        // [MODIFIED] 2026-06-24 真正内切大圆布局：4个小圆内切于大圆，圆心在圆周上
        int btnSize = dp(68);           // 小圆按钮直径
        int bigRadius = dp(80);         // 大圆半径（小圆圆心到大圆中心的距离）
        int containerHeight = dp(260);  // 容器高度
        int containerWidth = dp(260);   // 容器宽度（正方形）

        FrameLayout circleContainer = new FrameLayout(this);
        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(containerWidth, containerHeight);
        containerLp.gravity = Gravity.CENTER;
        circleContainer.setLayoutParams(containerLp);

        // 大圆中心点
        int centerX = containerWidth / 2;
        int centerY = containerHeight / 2;

        // 上（箭头向上，index=1）- 圆心在顶部
        android.widget.ImageView btnUp = directionButton(1, () -> moveClusterBy(0, -dp(2)), btnSize);
        FrameLayout.LayoutParams lpUp = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpUp.leftMargin = centerX - btnSize / 2;
        lpUp.topMargin = centerY - bigRadius - btnSize / 2;
        circleContainer.addView(btnUp, lpUp);

        // 下（箭头向下，index=3）- 圆心在底部
        android.widget.ImageView btnDown = directionButton(3, () -> moveClusterBy(0, dp(2)), btnSize);
        FrameLayout.LayoutParams lpDown = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpDown.leftMargin = centerX - btnSize / 2;
        lpDown.topMargin = centerY + bigRadius - btnSize / 2;
        circleContainer.addView(btnDown, lpDown);

        // 左（箭头向左，index=0）- 圆心在左侧
        android.widget.ImageView btnLeft = directionButton(0, () -> moveClusterBy(-dp(2), 0), btnSize);
        FrameLayout.LayoutParams lpLeft = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpLeft.leftMargin = centerX - bigRadius - btnSize / 2;
        lpLeft.topMargin = centerY - btnSize / 2;
        circleContainer.addView(btnLeft, lpLeft);

        // 右（箭头向右，index=2）- 圆心在右侧
        android.widget.ImageView btnRight = directionButton(2, () -> moveClusterBy(dp(2), 0), btnSize);
        FrameLayout.LayoutParams lpRight = new FrameLayout.LayoutParams(btnSize, btnSize);
        lpRight.leftMargin = centerX + bigRadius - btnSize / 2;
        lpRight.topMargin = centerY - btnSize / 2;
        circleContainer.addView(btnRight, lpRight);

                // 坐标显示（整个容器正中心）
        LinearLayout coordCol = new LinearLayout(this);
        coordCol.setOrientation(LinearLayout.VERTICAL);
        coordCol.setGravity(Gravity.CENTER);

        coordTextX = new TextView(this);
        coordTextX.setTextSize(18f);
        coordTextX.setTextColor(0xFF334155);
        coordTextX.setTypeface(Typeface.MONOSPACE);
        coordTextX.setGravity(Gravity.CENTER);
        coordCol.addView(coordTextX, new LinearLayout.LayoutParams(-2, -2));

        coordTextY = new TextView(this);
        coordTextY.setTextSize(18f);
        coordTextY.setTextColor(0xFF334155);
        coordTextY.setTypeface(Typeface.MONOSPACE);
        coordTextY.setGravity(Gravity.CENTER);
        coordCol.addView(coordTextY, new LinearLayout.LayoutParams(-2, -2));

        updateCoordText();

        FrameLayout.LayoutParams coordLp = new FrameLayout.LayoutParams(-2, -2);
        coordLp.gravity = Gravity.CENTER;  // 整个容器正中心
        circleContainer.addView(coordCol, coordLp);

                // 让circleContainer在card中居中
        FrameLayout.LayoutParams circleInCardLp = new FrameLayout.LayoutParams(containerWidth, containerHeight);
        circleInCardLp.gravity = Gravity.CENTER;
        card.addView(circleContainer, circleInCardLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, dp(10), 0, 0);
        parent.addView(card, cardLp);
    }

    private void addOverlayTargetControls(LinearLayout parent) {
        // 圆角边框卡片
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("\u60ac\u6d6e\u7a97\u663e\u793a\u4f4d\u7f6e");
        title.setTextSize(14f);
        title.setTextColor(0xFF111827);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.setMargins(0, dp(10), 0, 0);
        card.addView(grid, gridLp);

        if (isWideLayout()) {
            addTogglePair(grid,
                    overlayTargetToggle("\u4e3b\u5c4f\u60ac\u6d6e\u7a97", AppPrefs.KEY_MAIN_OVERLAY_ENABLED),
                    overlayTargetToggle("\u526f\u5c4f\u60ac\u6d6e\u7a97", AppPrefs.KEY_CLUSTER_MIRROR_ENABLED));
        } else {
            grid.addView(overlayTargetToggle("\u4e3b\u5c4f\u60ac\u6d6e\u7a97", AppPrefs.KEY_MAIN_OVERLAY_ENABLED));
            grid.addView(overlayTargetToggle("\u526f\u5c4f\u60ac\u6d6e\u7a97", AppPrefs.KEY_CLUSTER_MIRROR_ENABLED));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        parent.addView(card, lp);
    }

    private void addOverlayContentControls(LinearLayout parent) {
        // 圆角边框卡片
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("\u81ea\u5b9a\u4e49\u60ac\u6d6e\u7a97\u5185\u5bb9");
        title.setTextSize(14f);
        title.setTextColor(0xFF111827);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.setMargins(0, dp(10), 0, 0);
        card.addView(grid, gridLp);

        if (isWideLayout()) {
            addTogglePair(grid,
                    contentToggle("\u9876\u90e8\u72b6\u6001", AppPrefs.KEY_SHOW_MODE),
                    contentToggle("\u8def\u7ebf\u6307\u5f15", AppPrefs.KEY_SHOW_TURN));
            addTogglePair(grid,
                    contentToggle("\u7ea2\u7eff\u706f\u5012\u8ba1\u65f6", AppPrefs.KEY_SHOW_LIGHT),
                    contentToggle("\u8f66\u9053\u4fe1\u606f", AppPrefs.KEY_SHOW_LANE));
            addTogglePair(grid,
                    contentToggle("\u5269\u4f59\u91cc\u7a0b\u4e0e\u76ee\u7684\u5730", AppPrefs.KEY_SHOW_ETA),
                    contentToggle("\u9650\u901f/\u7535\u5b50\u773c/\u7ea2\u7eff\u706f\u4e2a\u6570", AppPrefs.KEY_SHOW_ALERT));
            addTogglePair(grid,
                    contentToggle("\u7ecf\u5178UI\u670d\u52a1\u533a\u4fe1\u606f", AppPrefs.KEY_SHOW_SERVICE_AREA),
                    contentToggle("\u8be6\u7ec6\u72b6\u6001", AppPrefs.KEY_SHOW_DETAIL));
        } else {
            grid.addView(contentToggle("\u9876\u90e8\u72b6\u6001", AppPrefs.KEY_SHOW_MODE));
            grid.addView(contentToggle("\u8def\u7ebf\u6307\u5f15", AppPrefs.KEY_SHOW_TURN));
            grid.addView(contentToggle("\u7ea2\u7eff\u706f\u5012\u8ba1\u65f6", AppPrefs.KEY_SHOW_LIGHT));
            grid.addView(contentToggle("\u8f66\u9053\u4fe1\u606f", AppPrefs.KEY_SHOW_LANE));
            grid.addView(contentToggle("\u5269\u4f59\u91cc\u7a0b\u4e0e\u76ee\u7684\u5730", AppPrefs.KEY_SHOW_ETA));
            grid.addView(contentToggle("\u9650\u901f/\u7535\u5b50\u773c/\u7ea2\u7eff\u706f\u4e2a\u6570", AppPrefs.KEY_SHOW_ALERT));
            grid.addView(contentToggle("\u7ecf\u5178UI\u670d\u52a1\u533a\u4fe1\u606f", AppPrefs.KEY_SHOW_SERVICE_AREA));
            grid.addView(contentToggle("\u8be6\u7ec6\u72b6\u6001", AppPrefs.KEY_SHOW_DETAIL));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        parent.addView(card, lp);
    }

    private void addStyleAndModeControls(LinearLayout parent) {
        // 圆角边框卡片
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        overlayUiStyleButton = button(overlayUiStyleButtonText(), v -> chooseOverlayUiStyle(), 0xFF3B82F6);
        LinearLayout.LayoutParams uiStyleLp = new LinearLayout.LayoutParams(-1, dp(42));
        overlayUiStyleButton.setLayoutParams(uiStyleLp);
        card.addView(overlayUiStyleButton);

        overlayTextModeButton = button(textModeButtonText(), v -> chooseTextMode(), 0xFF60A5FA);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, dp(42));
        buttonLp.setMargins(0, dp(10), 0, 0);
        overlayTextModeButton.setLayoutParams(buttonLp);
        card.addView(overlayTextModeButton);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        parent.addView(card, lp);
    }

    private void addBehaviorControls(LinearLayout parent) {
        // 圆角边框卡片
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE2E8F0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("自动启动与显示策略");
        title.setTextSize(14f);
        title.setTextColor(0xFF111827);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.setMargins(0, dp(10), 0, 0);
        card.addView(grid, gridLp);

        if (isWideLayout()) {
            addTogglePair(grid,
                    behaviorToggle("开机或亮屏自动启动服务", AppPrefs.KEY_AUTO_START_ENABLED),
                    behaviorToggle("进入软件后自动启动服务", AppPrefs.KEY_START_SERVICE_ON_APP_OPEN));
            addTogglePair(grid,
                    behaviorToggle("桌面启动时直接进入目标应用", AppPrefs.KEY_LAUNCH_TARGET_FROM_DESKTOP),
                    behaviorToggle("高德广播自动显示悬浮窗", AppPrefs.KEY_SHOW_MAIN_WHEN_TARGET_FOREGROUND));
            addTogglePair(grid,
                    behaviorToggle("高德前台隐藏中控悬浮窗", AppPrefs.KEY_HIDE_MAIN_WHEN_TARGET_FOREGROUND),
                    behaviorToggle("导航/巡航退出隐藏仪表", AppPrefs.KEY_HIDE_CLUSTER_WHEN_INACTIVE));
            addTogglePair(grid,
                    behaviorToggle("红绿灯竖向排列", AppPrefs.KEY_LIGHT_VERTICAL),
                    null);
        } else {
            grid.addView(behaviorToggle("开机或亮屏自动启动服务", AppPrefs.KEY_AUTO_START_ENABLED));
            grid.addView(behaviorToggle("进入软件后自动启动服务", AppPrefs.KEY_START_SERVICE_ON_APP_OPEN));
            grid.addView(behaviorToggle("桌面启动时直接进入目标应用", AppPrefs.KEY_LAUNCH_TARGET_FROM_DESKTOP));
            grid.addView(behaviorToggle("高德广播自动显示悬浮窗", AppPrefs.KEY_SHOW_MAIN_WHEN_TARGET_FOREGROUND));
            grid.addView(behaviorToggle("高德前台隐藏中控悬浮窗", AppPrefs.KEY_HIDE_MAIN_WHEN_TARGET_FOREGROUND));
            grid.addView(behaviorToggle("导航/巡航退出隐藏仪表", AppPrefs.KEY_HIDE_CLUSTER_WHEN_INACTIVE));
            grid.addView(behaviorToggle("红绿灯竖向排列", AppPrefs.KEY_LIGHT_VERTICAL));
        }

        addOverspeedBehaviorControls(grid);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        parent.addView(card, lp);
    }

    private void addOverspeedBehaviorControls(LinearLayout grid) {
        CheckBox mild = behaviorToggle("\u666e\u901a\u8d85\u901f\u8fb9\u6846\u63d0\u9192", AppPrefs.KEY_OVERSPEED_MILD_WARNING);
        CheckBox medium = behaviorToggle("\u8d85\u901f10%\u8fb9\u6846\u63d0\u9192", AppPrefs.KEY_OVERSPEED_MEDIUM_WARNING);
        if (isWideLayout()) {
            addTogglePair(grid, mild, medium);
        } else {
            grid.addView(mild);
            grid.addView(medium);
        }
    }



    private LinearLayout card(int color) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        if (color == Color.WHITE) {
            bg.setStroke(dp(1), 0xFFE5E7EB);
        }
        layout.setBackground(bg);
        return layout;
    }

    private Button button(String text, android.view.View.OnClickListener listener, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.setMargins(0, dp(9), 0, 0);
        b.setLayoutParams(lp);
        return b;
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

    // [MODIFIED] 2026-06-23 增大尺寸和间距，增加长按快速移动
    private android.widget.ImageView directionButton(int arrowDir, Runnable normalMove) {
        return directionButton(arrowDir, normalMove, dp(64));
    }

    private android.widget.ImageView directionButton(int arrowDir, Runnable normalMove, int btnSize) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(new ArrowDrawable(0xFFFFFFFF, arrowDir));
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

        // 单击：普通移动
        iv.setOnClickListener(v -> normalMove.run());

        // 长按：每 80ms 快速移动一次，松手停止
        final Handler fastHandler = new Handler(Looper.getMainLooper());
        final Runnable[] fastTicker = { null };
        iv.setOnLongClickListener(v -> {
            normalMove.run();
            fastTicker[0] = () -> {
                normalMove.run();
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

    private void addButtonPair(LinearLayout parent, Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(2f);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(9), 0, 0);
        row.setLayoutParams(rowLp);

        row.addView(wideButton(left, 0, dp(5)));
        if (right != null) {
            row.addView(wideButton(right, dp(5), 0));
        } else {
            android.view.View spacer = new android.view.View(this);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 0, 1f);
            spacerLp.setMargins(dp(5), 0, 0, 0);
            row.addView(spacer, spacerLp);
        }
        parent.addView(row);
    }

    private Button wideButton(Button button, int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(leftMargin, 0, rightMargin, 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void addTogglePair(LinearLayout parent, CheckBox left, CheckBox right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(2f);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(2), 0, 0);
        row.setLayoutParams(rowLp);

        row.addView(wideToggle(left, 0, dp(8)));
        if (right != null) {
            row.addView(wideToggle(right, dp(8), 0));
        } else {
            android.view.View spacer = new android.view.View(this);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 0, 1f);
            spacerLp.setMargins(dp(8), 0, 0, 0);
            row.addView(spacer, spacerLp);
        }
        parent.addView(row);
    }

    private CheckBox wideToggle(CheckBox checkBox, int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(leftMargin, 0, rightMargin, 0);
        checkBox.setLayoutParams(lp);
        return checkBox;
    }

    private boolean isWideLayout() {
        return getResources().getDisplayMetrics().widthPixels >= getResources().getDisplayMetrics().heightPixels;
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
        AlertDialog dialog = new AlertDialog.Builder(this)
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

    private void startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
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
        saveBehaviorEnabled(AppPrefs.KEY_SHOW_MAIN_WHEN_TARGET_FOREGROUND, false);
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
        Intent launch = getPackageManager().getLaunchIntentForPackage(AppPrefs.getTargetPackage(this));
        if (launch != null) {
            startActivity(launch);
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
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
            targetText.setText("\u76ee\u6807\u5e94\u7528\n" + AppPrefs.getTargetPackage(this));
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

    private void updateOverlayScaleText(int percent) {
        if (overlayScaleText != null) {
            overlayScaleText.setText("\u60ac\u6d6e\u7a97\u5927\u5c0f " + AppPrefs.clampOverlayScalePercent(percent) + "%");
        }
    }

    private CheckBox contentToggle(String text, String key) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        checkBox.setChecked(AppPrefs.isOverlayContentEnabled(this, key));
        checkBox.setTextSize(14f);
        checkBox.setTextColor(0xFF0F172A);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF2563EB));
        }
        checkBox.setPadding(0, dp(2), 0, dp(2));
        checkBox.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            saveOverlayContentEnabled(key, isChecked);
            notifyOverlayContentChanged();
        });
        return checkBox;
    }

    private CheckBox behaviorToggle(String text, String key) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        checkBox.setChecked(AppPrefs.isBehaviorEnabled(this, key));
        checkBox.setTextSize(14f);
        checkBox.setTextColor(0xFF0F172A);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF2563EB));
        }
        checkBox.setPadding(0, dp(2), 0, dp(2));
        checkBox.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            saveBehaviorEnabled(key, isChecked);
            if (AppPrefs.KEY_HIDE_MAIN_WHEN_TARGET_FOREGROUND.equals(key)
                    && isChecked && !AppPrefs.hasUsageStatsAccess(this)) {
                Toast.makeText(this, "请为 AMap Companion 开启使用情况访问权限", Toast.LENGTH_LONG).show();
                openUsageAccessSettings();
            }
            if (isChecked) {
                if (AppPrefs.KEY_START_SERVICE_ON_APP_OPEN.equals(key)) {
                    startCompanionService(false);
                } else {
                    startOverlayService();
                }
            }
            notifyDisplayPolicyChanged();
            if (!isChecked) {
                stopServiceIfNoVisuals();
            }
        });
        return checkBox;
    }

    private CheckBox overlayTargetToggle(String text, String key) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        checkBox.setChecked(AppPrefs.KEY_CLUSTER_MIRROR_ENABLED.equals(key)
                ? AppPrefs.isClusterMirrorEnabled(this)
                : AppPrefs.isMainOverlayEnabled(this));
        checkBox.setTextSize(14f);
        checkBox.setTextColor(0xFF0F172A);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF2563EB));
        }
        checkBox.setPadding(0, dp(2), 0, dp(2));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
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
            stopServiceIfNoVisuals();
        });
        return checkBox;
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

    private String textModeButtonText() {
        return AppPrefs.isAutoTextMode(this)
                ? "\u6587\u5b57\u6a21\u5f0f\uff1a\u81ea\u52a8\u6a21\u5f0f"
                : "\u6587\u5b57\u6a21\u5f0f\uff1a\u6d45\u8272\u6a21\u5f0f";
    }

    private String overlayUiStyleButtonText() {
        return "\u60ac\u6d6e\u7a97\u6837\u5f0f\uff1a" + OverlayUiStyles.displayName(AppPrefs.getOverlayUiStyle(this));
    }

    private void chooseOverlayUiStyle() {
        String currentStyle = AppPrefs.getOverlayUiStyle(this);
        int checked = OverlayUiStyles.indexOf(currentStyle);
        new AlertDialog.Builder(this)
                .setTitle("\u9009\u62e9\u60ac\u6d6e\u7a97\u6837\u5f0f")
                .setSingleChoiceItems(OverlayUiStyles.labels(), checked, (dialog, which) -> {
                    String style = OverlayUiStyles.ALL[which].id;
                    saveOverlayUiStyle(style);
                    overlayUiStyleButton.setText(overlayUiStyleButtonText());
                    notifyOverlayStyleChanged();
                    dialog.dismiss();
                })
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    private void chooseTextMode() {
        String[] labels = {
                "\u81ea\u52a8\u6a21\u5f0f\uff08\u6839\u636e\u80cc\u666f\u900f\u660e\u5ea6\u81ea\u52a8\u66f4\u6539\u6587\u5b57\u989c\u8272\uff09",
                "\u6d45\u8272\u6a21\u5f0f\uff08\u59cb\u7ec8\u4f7f\u7528\u6d45\u8272\u6587\u5b57\uff09"
        };
        int checked = AppPrefs.isAutoTextMode(this) ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("\u9009\u62e9\u6587\u5b57\u6a21\u5f0f")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    saveOverlayTextMode(which == 0 ? AppPrefs.TEXT_MODE_AUTO : AppPrefs.TEXT_MODE_LIGHT);
                    overlayTextModeButton.setText(textModeButtonText());
                    notifyOverlayStyleChanged();
                    dialog.dismiss();
                })
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    private void saveOverlayTextMode(String mode) {
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putString(AppPrefs.KEY_TEXT_MODE, AppPrefs.TEXT_MODE_AUTO.equals(mode) ? AppPrefs.TEXT_MODE_AUTO : AppPrefs.TEXT_MODE_LIGHT)
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

    // [MODIFIED] 2026-06-11 BUG4: 添加 Intent 直传保底,避免广播丢失
    // 验证: 内容变更后悬浮窗应实时更新
    // [FIXED] 2026-06-11 BUG A: 移除 startOverlayService() 冗余调用,direct Intent 已能确保服务收到
    // [OPTIMIZED] 2026-06-11 BUG D: 使用 sendDirectIntent 公用方法
    private void notifyOverlayContentChanged() {
        Intent broadcast = new Intent(AppPrefs.ACTION_OVERLAY_CONTENT_CHANGED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        sendDirectIntent(OverlayService.ACTION_REBUILD_CONTENT);
    }

    // [FIXED] 2026-06-11 BUG A: 移除 startOverlayService() 冗余调用
    // [OPTIMIZED] 2026-06-11 BUG D: 使用 sendDirectIntent 公用方法
    private void notifyOverlayStyleChanged() {
        // 方式1:广播(原始方案)
        Intent broadcast = new Intent(AppPrefs.ACTION_OVERLAY_STYLE_CHANGED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        // 方式2:Intent 直传(确保服务一定能收到)
        sendDirectIntent(OverlayService.ACTION_REBUILD_STYLE);
    }

    // [FIXED] 2026-06-11 BUG A: 移除 startOverlayService() 冗余调用,direct Intent 已能确保服务收到
    // [OPTIMIZED] 2026-06-11 BUG D: 使用 sendDirectIntent 公用方法
    private void notifyDisplayPolicyChanged() {
        Intent broadcast = new Intent(AppPrefs.ACTION_DISPLAY_POLICY_CHANGED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        sendDirectIntent(OverlayService.ACTION_REBUILD_POLICY);
    }

    private void stopServiceIfNoVisuals() {
        if (!AppPrefs.isMainOverlayEnabled(this)
                && !AppPrefs.isClusterMirrorEnabled(this)
                && !AppPrefs.isAutoStartEnabled(this)
                && !AppPrefs.isShowMainWhenTargetForegroundEnabled(this)) {
            stopService(new Intent(this, OverlayService.class));
        }
    }

    // [OPTIMIZED] 2026-06-11 BUG D: 抽取公用方法,消除反射调用重复代码
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

    private void updateClusterScaleText(int percent) {
        if (clusterScaleText != null) {
            clusterScaleText.setText("\u526f\u5c4f\u5927\u5c0f " + AppPrefs.clampOverlayScalePercent(percent) + "%");
        }
    }

    private void updateClusterDisplayText() {
        if (clusterDisplayText == null) {
            return;
        }
        int selectedId = AppPrefs.getClusterDisplayId(this);
        if (selectedId < 0) {
            clusterDisplayText.setText("\u6295\u5c4f\u5c4f\u5e55 \u00b7 \u81ea\u52a8\u9009\u62e9");
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
            clusterDisplayText.setText("\u6295\u5c4f\u5c4f\u5e55 \u00b7 " + selected.label + " (ID " + selected.displayId + ")");
        } else {
            clusterDisplayText.setText("\u6295\u5c4f\u5c4f\u5e55 \u00b7 \u5df2\u6307\u5b9a ID " + selectedId + "\uff08\u5f53\u524d\u672a\u68c0\u6d4b\u5230\uff09");
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
        if (coordTextX == null) { return; }
        SharedPreferences prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE);
        int x = prefs.getInt(AppPrefs.KEY_CLUSTER_X, 600);
        int y = prefs.getInt(AppPrefs.KEY_CLUSTER_Y, 182);
        coordTextX.setText("X: " + x);
        coordTextY.setText("Y: " + y);
    }

    private void moveClusterBy(int dx, int dy) {
        SharedPreferences prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE);
        int x = Math.max(0, prefs.getInt(AppPrefs.KEY_CLUSTER_X, 600) + dx);
        int y = Math.max(0, prefs.getInt(AppPrefs.KEY_CLUSTER_Y, 182) + dy);
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
            title.setTextColor(0xFF111827);
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

