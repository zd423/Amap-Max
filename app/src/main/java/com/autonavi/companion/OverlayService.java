package com.autonavi.companion;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// ── 极狐转向 HUD（arcfox-turn-hud 移植）────────────────────────────────────
import com.autonavi.companion.vehicle.AdayoTurnSignalMonitor;
import com.autonavi.companion.vehicle.TurnSignalController;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class OverlayService extends Service {
    private static final String TAG = "AmapCompanion";
    public static final String ACTION_STOP_SERVICE = "com.autonavi.companion.STOP_OVERLAY_SERVICE";
    public static final String ACTION_REBUILD_STYLE = "com.autonavi.companion.REBUILD_STYLE";
    public static final String ACTION_REBUILD_CONTENT = "com.autonavi.companion.REBUILD_CONTENT";
    public static final String ACTION_REBUILD_POLICY = "com.autonavi.companion.REBUILD_POLICY";
    // 允许第三方工具/自动化按 action 直接拉起服务（不弹 App 页面）
    public static final String ACTION_START_SERVICE = "com.autonavi.companion.START_OVERLAY_SERVICE";
    // 【新增·转向 HUD】设置页 Intent 直传：重建/刷新转向 HUD、触发预览
    public static final String ACTION_REBUILD_TURN_SIGNAL = "com.autonavi.companion.REBUILD_TURN_SIGNAL";
    public static final String ACTION_PREVIEW_TURN_SIGNAL = "com.autonavi.companion.PREVIEW_TURN_SIGNAL";
    private static final String CHANNEL_ID = "amap_companion";
    private static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV";
    private static final long LIGHT_TTL_MS = 4500L;
    private static final long LIGHT_TICK_MS = 1000L;
    private static final long DISPLAY_POLICY_POLL_MS = 1500L;
    // 导航活跃 TTL：导航模式广播频繁，12s 足够；
    // 巡航模式广播频率低（无路况更新时可能暂停），单独放宽到 45s，避免副屏误隐藏闪烁
    private static final long NAVIGATION_ACTIVE_TTL_MS = 12000L;
    private static final long CRUISE_ACTIVE_TTL_MS = 45000L;
    private static final long TARGET_BROADCAST_ACTIVE_TTL_MS = 15000L;
    private static final long PANEL_WIDTH_SHRINK_DELAY_MS = 2500L;
    // 超速边框：
    //   超速 <=10% 黄框、10%~20% 红框：稳态显示 OVERSPEED_ON_MS 后隐藏，等 OVERSPEED_OFF_MS 仍超速则往复
    //   超速 >20% 红框：常亮不歇，持续循环直到降到 20% 以下
    private static final long OVERSPEED_ON_MS = 5000L;
    private static final long OVERSPEED_OFF_MS = 20000L;
    private static final int OVERSPEED_NONE = 0;
    private static final int OVERSPEED_MILD = 1;      // 超速 <=10% : 黄框（往复）
    private static final int OVERSPEED_MEDIUM = 2;    // 超速 10%~20% : 红框（往复）
    private static final int OVERSPEED_CRITICAL = 3;  // 超速 >20%  : 红框（常亮不歇）
    private static final long ALERT_TTL_MS = 5000L;
    // 【新增·转向 HUD】预览时长；比 Latch(950ms) 长足够多，肉眼可完整看清一轮动画
    private static final long TURN_PREVIEW_MS = 2600L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout panel;
    // 【转向只在 HUD】转向箭头仅渲染到副屏（HUD）display，中控屏不创建任何转向窗口。
    // 复用红绿灯副屏的 findClusterDisplay() / createDisplayContext() 机制：
    // 用户已在「副屏设置」里选定 HUD display（红绿灯能上 HUD 即证明选择有效）。
    // 无副屏（如模拟器单屏）时 attachClusterTurnSignalWindow() 返回 false 静默跳过，转向不渲染。
    private Context turnClusterContext;
    private WindowManager turnClusterWindowManager;
    private WindowManager.LayoutParams turnClusterParams;
    private FrameLayout turnClusterHost;
    private TurnSignalController turnClusterController;
    private Display turnClusterDisplay;
    // Cached light pill LinearLayouts per slot (0=left, 1=straight, 2=right)
    private LinearLayout[] cachedLightPills = new LinearLayout[3];
    private LinearLayout[] cachedClusterLightPills = new LinearLayout[3];
    private boolean cachedPillsVertical;
    private boolean cachedClusterPillsVertical;
    private Context clusterContext;
    private WindowManager clusterWindowManager;
    private WindowManager.LayoutParams clusterParams;
    private LinearLayout clusterPanel;
    private LinearLayout clusterLightRow;
    private Display clusterDisplay;
    private boolean clusterMirrorEnabled;
    private int clusterMirrorRetryCount;
    private final HashMap<Integer, TrafficLightParser.LightState> trafficLights = new HashMap<>();
    private boolean inCruiseMode;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean dragging;
    private float clusterDownRawX;
    private float clusterDownRawY;
    private int clusterDownX;
    private int clusterDownY;
    private boolean clusterDragging;
    private int currentLimitSpeed = -1;
    private int currentVehicleSpeed = -1;
    private int navigationTurnDir = -1;
    private int currentTurnIcon = 0;
    private Runnable overspeedBlinks;        // 显示阶段（稳态 5s）的周期回调
    private Runnable overspeedOffRunnable;   // 隐藏等待阶段（20s）的回调
    private boolean overspeedOffPhase;       // true = 正处于 20s 隐藏等待期
    private int overspeedColor;
    private int overspeedLevel;
    private GradientDrawable panelBackground;
    private GradientDrawable clusterPanelBackground;
    private Runnable mainPanelWidthUnlock;
    private Runnable clusterPanelWidthUnlock;
    private int mainPanelBaseMinWidth = -1;
    private int clusterPanelBaseMinWidth = -1;
    private int mainPanelBaseMinHeight = -1;
    private int clusterPanelBaseMinHeight = -1;
    private int mainPanelHeldMinWidth;
    private int clusterPanelHeldMinWidth;
    private int mainPanelHeldMinHeight;
    private int clusterPanelHeldMinHeight;
    private float overlayScale = 2f;
    private float clusterScale = 2f;
    private Runnable pendingClusterMirrorRebuild;
    private float activeDensity = -1f;
    private boolean onCreateDelayed;
    private boolean targetAppForeground;
    private boolean targetBroadcastActive;
    private boolean navigationOrCruiseActive;
    private long lastNavigationSignalAt;
    private long lastCruiseSignalAt;
    private long lastTargetBroadcastAt;

    // Light row references - shared between cruise/nav modes
    private LinearLayout lightRow;

    private final View.OnLayoutChangeListener clusterBoundsListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateClusterPosition();

    private final Runnable lanePoll = new Runnable() {
        @Override
        public void run() {
            if (shouldRequestAmapData()) {
                requestTrafficLightInfo();
            }
            mainHandler.postDelayed(this, 6000L);
        }
    };

    private final Runnable trafficLightTicker = new Runnable() {
        @Override
        public void run() {
            renderTrafficLights();
        }
    };

    private final Runnable displayPolicyPoll = new Runnable() {
        @Override
        public void run() {
            refreshDisplayPolicies();
            mainHandler.postDelayed(this, DISPLAY_POLICY_POLL_MS);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBroadcast(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // 一次性迁移：旧版默认 safe_left=34（1/3 固定仪表区避让）→ 新版默认 0（1300×900 全屏对称）
        AppPrefs.migrateSafeLeftIfLegacyDefault(this);
        startForeground(1, buildNotification());
        registerAmapReceivers();
        stopSelfIfNoVisuals();
        if (shouldRequestAmapData()) {
            requestTrafficLightInfo();
        }
        mainHandler.postDelayed(lanePoll, 6000L);
        mainHandler.post(displayPolicyPoll);
        onCreateDelayed = true;
        mainHandler.postDelayed(() -> {
            onCreateDelayed = false;
            ensureOverlay();
            ensureClusterMirror();
            ensureTurnSignalOverlay();   // 【新增·转向 HUD】与主/仪表窗同批延迟创建
            stopSelfIfNoVisuals();
        }, 800);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand action=" + (intent != null ? intent.getAction() : "null"));
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            shutdownWindowsImmediately();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REBUILD_STYLE.equals(intent.getAction())) {
            rebuildOverlaysForStyleChange();
            return START_STICKY;
        }
        if (intent != null && ACTION_REBUILD_CONTENT.equals(intent.getAction())) {
            rebuildOverlay();
            applyContentVisibilityPrefs();
            return START_STICKY;
        }
        if (intent != null && ACTION_REBUILD_POLICY.equals(intent.getAction())) {
            applyContentVisibilityPrefs();
            syncMainOverlayAttachment();
            // P2-4：红绿灯竖向/横向切换、红绿灯可见性等显示策略变更需立即重建渲染，
            // 不能依赖 1s ticker 或下一条广播才生效
            renderTrafficLights();
            return START_STICKY;
        }
        // 【新增·转向 HUD】设置项变更：重建/刷新独立悬浮窗
        if (intent != null && ACTION_REBUILD_TURN_SIGNAL.equals(intent.getAction())) {
            ensureTurnSignalOverlay();
            stopSelfIfNoVisuals();
            return START_STICKY;
        }
        // 【新增·转向 HUD】设置页预览：left / right / hazard
        if (intent != null && ACTION_PREVIEW_TURN_SIGNAL.equals(intent.getAction())) {
            previewTurnSignal(intent.getStringExtra(AppPrefs.EXTRA_TURN_SIGNAL_PREVIEW));
            return START_STICKY;
        }
        // 【新增·转向 HUD】模拟器/调试注入（startService 路径，绕过广播限制）
        if (intent != null && AppPrefs.ACTION_TURN_SIGNAL_INJECT.equals(intent.getAction())) {
            injectTurnSignal(intent);
            return START_STICKY;
        }
        if (intent != null && ACTION_START_SERVICE.equals(intent.getAction())) {
            // 外部按 action 拉起服务：走与默认启动一致的初始化路径，不依赖 Activity
            if (!onCreateDelayed) {
                ensureOverlay();
                ensureClusterMirror();
                ensureTurnSignalOverlay();
            }
            if (shouldRequestAmapData()) {
                requestTrafficLightInfo();
            }
            return START_STICKY;
        }
        if (!onCreateDelayed) {
            ensureOverlay();
            ensureClusterMirror();
            ensureTurnSignalOverlay();
            stopSelfIfNoVisuals();
        }
        if (shouldRequestAmapData()) {
            requestTrafficLightInfo();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdownWindowsImmediately();
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void shutdownWindowsImmediately() {
        onCreateDelayed = false;
        mainHandler.removeCallbacksAndMessages(null);
        pendingClusterMirrorRebuild = null;
        mainPanelWidthUnlock = null;
        clusterPanelWidthUnlock = null;
        overspeedBlinks = null;
        overspeedOffRunnable = null;
        overspeedOffPhase = false;
        dismissClusterMirror();
        // 【新增·转向 HUD】服务停机：先摘监听再停线程，避免回调打到已销毁的 View
        dismissTurnSignalOverlay();
        try {
            AdayoTurnSignalMonitor.stop();
        } catch (Throwable ignored) {
        }
        if (windowManager != null && panel != null && panel.getParent() != null) {
            try {
                panel.setVisibility(View.GONE);
                windowManager.removeViewImmediate(panel);
            } catch (Throwable ignored) {
            }
        }
        panel = null;
        params = null;
        windowManager = null;
        panelBackground = null;
        clusterPanelBackground = null;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerAmapReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SEND);
        filter.addAction(ACTION_RECV);
        filter.addAction("AUTO_GUIDE_INFO_FOR_INTERNAL_WIDGET");
        filter.addAction("AUTO_STATUS_FOR_INTERNAL_WIDGET");
        filter.addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_ROAD_NAME_INFO");
        filter.addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_SILENCE_ROADNAME_INFO");
        filter.addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_GPS_INFO");
        filter.addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_CAR_DIRECTION");
        filter.addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_CAMERA_INFO");
        filter.addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_TRAFFIC_LIGHT_INFO");
        filter.addAction("com.autonavi.amapauto.AUTO_WIDGET_UPDATE_CRUISE_TRAFFIC_LIGHT_INFO");
        filter.addAction(AppPrefs.ACTION_MAIN_OVERLAY_CHANGED);
        filter.addAction(AppPrefs.ACTION_OVERLAY_SCALE_CHANGED);
        filter.addAction(AppPrefs.ACTION_CLUSTER_MIRROR_CHANGED);
        filter.addAction(AppPrefs.ACTION_CLUSTER_POSITION_CHANGED);
        filter.addAction(AppPrefs.ACTION_OVERLAY_CONTENT_CHANGED);
        filter.addAction(AppPrefs.ACTION_OVERLAY_STYLE_CHANGED);
        filter.addAction(AppPrefs.ACTION_DISPLAY_POLICY_CHANGED);
        // 【新增·转向 HUD】配置变更 / 预览 / 调试注入广播
        filter.addAction(AppPrefs.ACTION_TURN_SIGNAL_CHANGED);
        filter.addAction(AppPrefs.ACTION_TURN_SIGNAL_PREVIEW);
        filter.addAction(AppPrefs.ACTION_TURN_SIGNAL_INJECT);
        filter.addAction(android.content.Intent.ACTION_CONFIGURATION_CHANGED);
        try {
            registerReceiver(receiver, filter);
        } catch (Throwable t) {
            Log.e(TAG, "register receiver failed", t);
        }
    }

    private void ensureOverlay() {
        if (panel != null) {
            syncMainOverlayAttachment();
            return;
        }

        overlayScale = AppPrefs.getOverlayScale(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        panel = buildPanel(this, overlayScale, false);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = getSavedOverlayX();
        params.y = getSavedOverlayY();

        Point screenSize = new Point();
        windowManager.getDefaultDisplay().getRealSize(screenSize);
        if (screenSize.x > 0) {
            int marginX = Math.max(1, screenSize.x / 10);
            params.x = Math.max(0, Math.min(params.x, screenSize.x - marginX));
        }
        if (screenSize.y > 0) {
            params.y = Math.max(0, Math.min(params.y, screenSize.y - 100));
        }

        panel.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = params.x;
                    downY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawX() - downRawX) > dp(4)
                            || Math.abs(event.getRawY() - downRawY) > dp(4)) {
                        dragging = true;
                    }
                    params.x = downX + Math.round(event.getRawX() - downRawX);
                    params.y = downY + Math.round(event.getRawY() - downRawY);
                    updateOverlayPosition();
                    return true;
                case MotionEvent.ACTION_UP:
                    saveOverlayPosition();
                    if (!dragging) {
                        openMainActivity();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        });

        syncMainOverlayAttachment();
        applyContentVisibilityPrefs();
        updateClusterPosition();
    }

    private void syncMainOverlayAttachment() {
        if (windowManager == null || panel == null || params == null) {
            return;
        }
        boolean enabled = (AppPrefs.isMainOverlayEnabled(this)
                || shouldShowMainOverlayForTargetBroadcast())
                && !shouldHideMainOverlayForTargetForeground();
        boolean attached = panel.getParent() != null;
        if (enabled && !attached) {
            try {
                windowManager.addView(panel, params);
            } catch (Throwable t) {
                Log.e(TAG, "overlay add failed", t);
            }
            return;
        }
        if (!enabled && attached) {
            try {
                windowManager.removeView(panel);
            } catch (Throwable t) {
                Log.e(TAG, "overlay remove failed", t);
            }
        }
    }

    private void ensureClusterMirror() {
        clusterMirrorEnabled = AppPrefs.isClusterMirrorEnabled(this);
        if (!clusterMirrorEnabled) {
            clusterMirrorRetryCount = 0;
            dismissClusterMirror();
            return;
        }
        if (shouldHideClusterMirrorForInactiveNavigation()) {
            clusterMirrorRetryCount = 0;
            dismissClusterMirror();
            return;
        }
        Display display = findClusterDisplay();
        if (display == null) {
            dismissClusterMirror();
            if (clusterMirrorRetryCount < 5) {
                clusterMirrorRetryCount++;
                mainHandler.postDelayed(() -> {
                    if (AppPrefs.isClusterMirrorEnabled(this)) {
                        ensureClusterMirror();
                    }
                }, 2500L);
            }
            return;
        }
        float requestedClusterScale = AppPrefs.getClusterScale(this);
        float nextClusterScale = requestedClusterScale;
        boolean scaleChanged = Math.abs(nextClusterScale - clusterScale) > 0.001f;
        clusterMirrorRetryCount = 0;
        if (clusterPanel != null && clusterDisplay != null
                && clusterDisplay.getDisplayId() == display.getDisplayId()
                && !scaleChanged) {
            updateClusterPosition();
            return;
        }
        dismissClusterMirror();
        clusterScale = nextClusterScale;
        clusterDisplay = display;
        try {
            clusterContext = createDisplayContext(display);
        } catch (Throwable t) {
            clusterContext = this;
        }
        if (clusterContext == null) {
            clusterContext = this;
        }
        clusterWindowManager = (WindowManager) clusterContext.getSystemService(WINDOW_SERVICE);
        if (clusterWindowManager == null) {
            return;
        }
        clusterPanel = buildPanel(clusterContext, clusterScale, true);
        clusterPanel.addOnLayoutChangeListener(clusterBoundsListener);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        clusterParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        clusterParams.gravity = Gravity.TOP | Gravity.LEFT;
        clusterParams.x = getSavedClusterX();
        clusterParams.y = getSavedClusterY();

        clusterPanel.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    clusterDownRawX = event.getRawX();
                    clusterDownRawY = event.getRawY();
                    clusterDownX = clusterParams.x;
                    clusterDownY = clusterParams.y;
                    clusterDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawX() - clusterDownRawX) > dp(4)
                            || Math.abs(event.getRawY() - clusterDownRawY) > dp(4)) {
                        clusterDragging = true;
                    }
                    clusterParams.x = clusterDownX + Math.round(event.getRawX() - clusterDownRawX);
                    clusterParams.y = clusterDownY + Math.round(event.getRawY() - clusterDownRawY);
                    updateClusterPosition();
                    return true;
                case MotionEvent.ACTION_UP:
                    saveClusterPosition();
                    return true;
                default:
                    return true;
            }
        });

        try {
            clusterWindowManager.addView(clusterPanel, clusterParams);
            clusterPanel.post(this::updateClusterPosition);
            syncClusterFromMain();
            applyContentVisibilityPrefs();
            mainHandler.postDelayed(() -> {
                if (clusterParams != null && clusterPanel != null && clusterPanel.getParent() != null) {
                    clusterParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    try {
                        clusterWindowManager.updateViewLayout(clusterPanel, clusterParams);
                    } catch (Throwable ignored) {
                    }
                }
            }, 4000);
        } catch (Throwable t) {
            dismissClusterMirror();
        }
    }

    private void dismissClusterMirror() {
        if (pendingClusterMirrorRebuild != null) {
            mainHandler.removeCallbacks(pendingClusterMirrorRebuild);
            pendingClusterMirrorRebuild = null;
        }
        clusterDisplay = null;
        clusterContext = null;
        if (clusterWindowManager != null && clusterPanel != null && clusterPanel.getParent() != null) {
            try {
                clusterPanel.removeOnLayoutChangeListener(clusterBoundsListener);
                clusterWindowManager.removeViewImmediate(clusterPanel);
            } catch (Throwable ignored) {
            }
        }
        clusterPanel = null;
        clusterLightRow = null;
        clusterParams = null;
        clusterWindowManager = null;
        clusterPanelBackground = null;
        clusterMirrorRetryCount = 0;
    }

    private boolean canUseOverlayWindowType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return android.provider.Settings.canDrawOverlays(this);
    }

    private void rebuildClusterMirrorForStyleChange() {
        dismissClusterMirror();
        if (pendingClusterMirrorRebuild != null) {
            mainHandler.removeCallbacks(pendingClusterMirrorRebuild);
        }
        pendingClusterMirrorRebuild = () -> {
            pendingClusterMirrorRebuild = null;
            if (!AppPrefs.isClusterMirrorEnabled(this)) return;
            ensureClusterMirror();
            syncClusterFromMain();
            applyContentVisibilityPrefs();
            updateClusterPosition();
        };
        mainHandler.postDelayed(pendingClusterMirrorRebuild, 120L);
    }

    private void rebuildOverlaysForStyleChange() {
        if (pendingClusterMirrorRebuild != null) {
            mainHandler.removeCallbacks(pendingClusterMirrorRebuild);
            pendingClusterMirrorRebuild = null;
        }
        boolean rebuildCluster = AppPrefs.isClusterMirrorEnabled(this);
        boolean shouldAttach = AppPrefs.isMainOverlayEnabled(this)
                || shouldShowMainOverlayForTargetBroadcast();
        if (shouldAttach && shouldHideMainOverlayForTargetForeground()) {
            shouldAttach = false;
        }
        boolean wasAttached = panel != null && panel.getParent() != null;
        dismissClusterMirror();
        rebuildOverlay();
        // 【新增·转向 HUD】日夜文本模式属于「样式」分类，切换后需重算箭头调光色
        refreshTurnSignalOverlay();
        if (shouldAttach && windowManager != null && panel != null && panel.getParent() == null) {
            try {
                windowManager.addView(panel, params);
            } catch (Throwable t) {
                Log.e(TAG, "style change: attach failed", t);
            }
        }
        applyContentVisibilityPrefs();
        if (!rebuildCluster) return;
        pendingClusterMirrorRebuild = () -> {
            pendingClusterMirrorRebuild = null;
            if (!AppPrefs.isClusterMirrorEnabled(this)) return;
            ensureClusterMirror();
            syncClusterFromMain();
            applyContentVisibilityPrefs();
            updateClusterPosition();
        };
        mainHandler.postDelayed(pendingClusterMirrorRebuild, 160L);
    }

    private Display findClusterDisplay() {
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) return null;
        int preferredDisplayId = AppPrefs.getClusterDisplayId(this);
        if (preferredDisplayId >= 0) {
            Display[] displays = manager.getDisplays();
            for (Display display : displays) {
                if (display != null && display.getDisplayId() == preferredDisplayId) return display;
            }
            Log.w(TAG, "cluster display id=" + preferredDisplayId + " not found, count=" + displays.length);
            return null;
        }
        Display[] presentationDisplays = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display display : presentationDisplays) {
            if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) return display;
        }
        Display[] displays = manager.getDisplays();
        for (Display display : displays) {
            if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) return display;
        }
        return null;
    }

    private LinearLayout buildPanel(Context context, float scale, boolean cluster) {
        String styleId = OverlayUiStyles.normalize(AppPrefs.getOverlayUiStyle(this));
        int layoutRes;
        switch (styleId) {
            case OverlayUiStyles.CLASSIC:
                layoutRes = R.layout.panel_classic;
                break;
            case OverlayUiStyles.DASHBOARD:
                layoutRes = R.layout.panel_dashboard;
                break;
            case OverlayUiStyles.DYNAMIC_ISLAND:
                layoutRes = R.layout.panel_dynamic_island;
                break;
            case OverlayUiStyles.CARD:
            default:
                layoutRes = R.layout.panel_card;
                break;
        }

        LinearLayout card = (LinearLayout) LayoutInflater.from(context).inflate(layoutRes, null);

        int padH = scaledDp(5, scale);
        int padTop = scaledDp(4, scale);
        int padBottom = scaledDp(2, scale);
        card.setPadding(padH, padTop, padH, padBottom);
        GradientDrawable bg = new GradientDrawable();
        // 【1840 修复】夜间模式给悬浮窗深色底（可见变暗），白天保持透明悬浮不变
        int opacity = isNightMode() ? 85 : 0;
        bg.setColor(AppPrefs.withAlpha(nightPaletteBg(0), opacity));
        bg.setCornerRadius(scaledDp(12, scale));
        card.setBackground(bg);

        // Light row from XML (all layouts use same id: light_row)
        LinearLayout lightRowXml = (LinearLayout) card.findViewById(R.id.light_row);

        if (cluster) {
            clusterPanel = card;
            clusterLightRow = lightRowXml;
        } else {
            panel = card;
            lightRow = lightRowXml;
        }

        return card;
    }

    private void updateClusterPosition() {
        if (clusterParams != null && clusterWindowManager != null
                && clusterPanel != null && clusterPanel.getParent() != null) {
            try {
                clusterWindowManager.updateViewLayout(clusterPanel, clusterParams);
            } catch (Throwable t) {
                Log.e(TAG, "cluster position update failed", t);
            }
        }
    }

    private void syncClusterFromMain() {
        renderTrafficLights();
        applyContentVisibilityPrefs();
        updateClusterPosition();
    }
    private void showAnyPanel() {
        refreshPanelVisibility();
    }

    private void refreshPanelVisibility() {
        if (panel != null) {
            applyOverlayTextOutlines(panel);
            panel.setVisibility(hasVisibleChildren(panel) ? View.VISIBLE : View.GONE);
            schedulePanelSizeStabilizer(panel, false);
        }
        if (clusterPanel != null) {
            applyOverlayTextOutlines(clusterPanel);
            clusterPanel.setVisibility(hasVisibleChildren(clusterPanel) ? View.VISIBLE : View.GONE);
            schedulePanelSizeStabilizer(clusterPanel, true);
        }
    }

    private void applyOverlayTextOutlines(View view) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int color = text.getCurrentTextColor();
            int red = Color.red(color);
            int green = Color.green(color);
            int blue = Color.blue(color);
            int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
            int outline = luminance >= 150 ? 0xE6000000 : 0xE6FFFFFF;
            float density = text.getResources().getDisplayMetrics().density;
            float radius = Math.max(1.2f * density,
                    Math.min(2.4f * density, text.getTextSize() * 0.055f));
            text.setShadowLayer(radius, 0f, 0f, outline);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyOverlayTextOutlines(group.getChildAt(i));
            }
        }
    }

    private void schedulePanelSizeStabilizer(LinearLayout target, boolean cluster) {
        if (target == null) return;
        target.post(() -> stabilizePanelSize(target, cluster));
    }

    private void stabilizePanelSize(LinearLayout target, boolean cluster) {
        if (target == null) return;
        int baseMin = cluster ? clusterPanelBaseMinWidth : mainPanelBaseMinWidth;
        if (baseMin < 0) {
            baseMin = Math.max(0, target.getMinimumWidth());
            if (cluster) {
                clusterPanelBaseMinWidth = baseMin;
            } else {
                mainPanelBaseMinWidth = baseMin;
            }
        }
        int baseMinHeight = cluster ? clusterPanelBaseMinHeight : mainPanelBaseMinHeight;
        if (baseMinHeight < 0) {
            baseMinHeight = Math.max(0, target.getMinimumHeight());
            if (cluster) {
                clusterPanelBaseMinHeight = baseMinHeight;
            } else {
                mainPanelBaseMinHeight = baseMinHeight;
            }
        }
        int width = target.getWidth();
        int height = target.getHeight();
        if (width <= 0 || height <= 0) {
            int wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            target.measure(wSpec, hSpec);
            width = target.getMeasuredWidth();
            height = target.getMeasuredHeight();
        }
        int held = cluster ? clusterPanelHeldMinWidth : mainPanelHeldMinWidth;
        int heldHeight = cluster ? clusterPanelHeldMinHeight : mainPanelHeldMinHeight;
        int nextMin = Math.max(baseMin, Math.max(held, width));
        int nextMinHeight = Math.max(baseMinHeight, Math.max(heldHeight, height));
        boolean expanded = nextMin > held || nextMinHeight > heldHeight;
        if (cluster) {
            clusterPanelHeldMinWidth = nextMin;
            clusterPanelHeldMinHeight = nextMinHeight;
        } else {
            mainPanelHeldMinWidth = nextMin;
            mainPanelHeldMinHeight = nextMinHeight;
        }
        if (target.getMinimumWidth() != nextMin) {
            target.setMinimumWidth(nextMin);
            target.requestLayout();
        }
        if (target.getMinimumHeight() != nextMinHeight) {
            target.setMinimumHeight(nextMinHeight);
            target.requestLayout();
        }
        if (expanded) {
            if (cluster) {
                if (clusterPanel != null && clusterPanel.getParent() != null) {
                    clusterWindowManager.updateViewLayout(clusterPanel, clusterParams);
                }
            } else {
                if (panel != null && panel.getParent() != null) {
                    windowManager.updateViewLayout(panel, params);
                }
            }
        }
        Runnable oldUnlock = cluster ? clusterPanelWidthUnlock : mainPanelWidthUnlock;
        if (!expanded && oldUnlock != null) return;
        if (oldUnlock != null) mainHandler.removeCallbacks(oldUnlock);
        Runnable unlock = () -> unlockPanelWidth(target, cluster);
        if (cluster) {
            clusterPanelWidthUnlock = unlock;
        } else {
            mainPanelWidthUnlock = unlock;
        }
        mainHandler.postDelayed(unlock, PANEL_WIDTH_SHRINK_DELAY_MS);
    }

    private void unlockPanelWidth(LinearLayout target, boolean cluster) {
        if (target == null || target != (cluster ? clusterPanel : panel)) return;
        int baseMin = Math.max(0, cluster ? clusterPanelBaseMinWidth : mainPanelBaseMinWidth);
        int baseMinHeight = Math.max(0, cluster ? clusterPanelBaseMinHeight : mainPanelBaseMinHeight);
        if (cluster) {
            clusterPanelHeldMinWidth = 0;
            clusterPanelHeldMinHeight = 0;
            clusterPanelWidthUnlock = null;
        } else {
            mainPanelHeldMinWidth = 0;
            mainPanelHeldMinHeight = 0;
            mainPanelWidthUnlock = null;
        }
        boolean changed = false;
        if (target.getMinimumWidth() != baseMin) {
            target.setMinimumWidth(baseMin);
            changed = true;
        }
        if (target.getMinimumHeight() != baseMinHeight) {
            target.setMinimumHeight(baseMinHeight);
            changed = true;
        }
        if (changed) target.requestLayout();
        if (cluster) {
            updateClusterPosition();
        } else {
            updateOverlayPosition();
        }
    }

    private void resetMainPanelWidthStabilizer() {
        if (mainPanelWidthUnlock != null) {
            mainHandler.removeCallbacks(mainPanelWidthUnlock);
            mainPanelWidthUnlock = null;
        }
        mainPanelBaseMinWidth = -1;
        mainPanelBaseMinHeight = -1;
        mainPanelHeldMinWidth = 0;
        mainPanelHeldMinHeight = 0;
    }

    private void resetClusterPanelWidthStabilizer() {
        if (clusterPanelWidthUnlock != null) {
            mainHandler.removeCallbacks(clusterPanelWidthUnlock);
            clusterPanelWidthUnlock = null;
        }
        clusterPanelBaseMinWidth = -1;
        clusterPanelBaseMinHeight = -1;
        clusterPanelHeldMinWidth = 0;
        clusterPanelHeldMinHeight = 0;
    }

    private boolean hasVisibleChildren(LinearLayout layout) {
        if (layout == null) return false;
        for (int i = 0; i < layout.getChildCount(); i++) {
            if (layout.getChildAt(i).getVisibility() == View.VISIBLE) return true;
        }
        return false;
    }

    private void updateOverlayPosition() {
        if (params != null) {
            Point screenSize = new Point();
            windowManager.getDefaultDisplay().getRealSize(screenSize);
            int panelWidth = panel != null ? panel.getWidth() : 0;
            int panelHeight = panel != null ? panel.getHeight() : 0;
            if (panelWidth <= 0 || panelHeight <= 0) {
                if (panel != null) {
                    int wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                    int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                    panel.measure(wSpec, hSpec);
                    panelWidth = Math.max(panelWidth, panel.getMeasuredWidth());
                    panelHeight = Math.max(panelHeight, panel.getMeasuredHeight());
                }
            }
            if (screenSize.x > 0 && panelWidth > 0) {
                params.x = Math.max(0, Math.min(params.x, screenSize.x - Math.min(panelWidth, screenSize.x / 10)));
            }
            if (screenSize.y > 0 && panelHeight > 0) {
                params.y = Math.max(0, Math.min(params.y, screenSize.y - panelHeight));
            }
        }
        try {
            if (windowManager != null && panel != null && panel.getParent() != null) {
                windowManager.updateViewLayout(panel, params);
            }
        } catch (Throwable t) {
            Log.e(TAG, "drag update failed", t);
        }
        updateClusterPosition();
    }

    private void rebuildOverlay() {
        int oldX = params != null ? params.x : rawDp(24);
        int oldY = params != null ? params.y : rawDp(220);
        if (windowManager != null && panel != null && panel.getParent() != null) {
            try {
                windowManager.removeView(panel);
            } catch (Throwable t) {
                Log.e(TAG, "overlay remove for rebuild failed", t);
            }
        }
        panel = null;
        panelBackground = null;
        resetMainPanelWidthStabilizer();
        ensureOverlay();
        if (params != null) {
            params.x = oldX;
            params.y = oldY;
            updateOverlayPosition();
        }
        requestTrafficLightInfo();
    }

    private void stopSelfIfNoVisuals() {
        if (!AppPrefs.isMainOverlayEnabled(this)
                && !AppPrefs.isClusterMirrorEnabled(this)
                && !AppPrefs.isAutoStartEnabled(this)
                // 【新增·转向 HUD】转向 HUD 可独立于主悬浮窗工作，
                // 只要它开着服务就必须活着，否则 logcat 监控线程会被杀
                && !AppPrefs.isTurnSignalOverlayEnabled(this)
                && !AppPrefs.isShowMainWhenTargetForegroundEnabled(this)) {
            stopSelf();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  【新增·转向 HUD】独立全屏悬浮窗生命周期（arcfox-turn-hud 移植）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 按开关状态创建 / 刷新 / 回收转向 HUD 窗口。
     * 幂等：重复调用不会重复 addView，可安全地在任意广播里调用。
     */
    private void ensureTurnSignalOverlay() {
        if (!AppPrefs.isTurnSignalOverlayEnabled(this)) {
            // 关闭 → 停监控线程并回收窗口，零 CPU / 零窗口占用
            AdayoTurnSignalMonitor.stop();
            dismissTurnSignalOverlay();
            return;
        }
        if (!AdayoTurnSignalMonitor.isRunning()) {
            AdayoTurnSignalMonitor.start(getApplicationContext());
        }
        // 【转向只在 HUD】中控屏不渲染转向，仅挂载副屏（HUD）窗口；无副屏（如模拟器）自动跳过
        if (attachClusterTurnSignalWindow()) {
            refreshTurnSignalOverlay();
        }
    }

    /** 设置项或日夜主题变更后重载渲染参数（不重建窗口）。
     *  转向只在 HUD：仅副屏（HUD）控制器需要刷新。 */
    private void refreshTurnSignalOverlay() {
        if (turnClusterController != null) {
            try {
                turnClusterController.refreshPreferences();
            } catch (Throwable t) {
                Log.e(TAG, "cluster turn signal refresh failed", t);
            }
        }
        // 尺寸/位置设置变更后窗口内容高度与 topFactor 可能变化 → 重新对准 y
        if (turnClusterHost != null) {
            // 尺寸类设置：内容高度变化 → 等新 layout 完成后按新高度定位
            repositionClusterTurnSignalWindowWhenReady();
            // 纯 topFactor 变更：内容高度不变 → 立即按新百分比定位
            positionClusterTurnSignalWindow();
        }
    }

    /**
     * 【转向只在 HUD】在副屏（HUD）display 上挂载转向 HUD 窗口（中控屏不再渲染转向）。
     * 复用红绿灯副屏的 findClusterDisplay() / createDisplayContext() 机制：
     * 用户已在「副屏设置」里选定 HUD display（红绿灯能上 HUD 即证明选择有效）。
     * 无副屏（如模拟器单屏）时返回 false，转向不渲染。
     * 幂等：重复调用不会重复 addView。
     */
    private boolean attachClusterTurnSignalWindow() {
        // 已挂载但目标 display 变化（用户在副屏设置里切换了 HUD）→ 重建到新屏
        if (turnClusterHost != null && turnClusterHost.getParent() != null) {
            Display current = findClusterDisplay();
            if (current != null && turnClusterDisplay != null
                    && current.getDisplayId() == turnClusterDisplay.getDisplayId()) {
                return true;
            }
            dismissClusterTurnSignalWindow();
        }
        Display display = findClusterDisplay();
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return false;
        }
        turnClusterDisplay = display;
        try {
            if (turnClusterWindowManager == null) {
                if (turnClusterContext == null) {
                    try {
                        turnClusterContext = createDisplayContext(display);
                    } catch (Throwable t) {
                        turnClusterContext = this;
                    }
                }
                if (turnClusterContext == null) {
                    turnClusterContext = this;
                }
                turnClusterWindowManager = (WindowManager) turnClusterContext.getSystemService(WINDOW_SERVICE);
            }
            if (turnClusterWindowManager == null) return false;
            if (turnClusterHost == null) {
                turnClusterHost = new FrameLayout(turnClusterContext);
                turnClusterHost.setClickable(false);
                turnClusterHost.setFocusable(false);
                turnClusterHost.setBackgroundColor(Color.TRANSPARENT);
            }
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            turnClusterParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            turnClusterParams.gravity = Gravity.TOP | Gravity.START;
            turnClusterParams.x = 0;
            turnClusterParams.y = 0;
            turnClusterWindowManager.addView(turnClusterHost, turnClusterParams);

            if (turnClusterController == null) {
                // 用 display context 创建控制器：TurnSignalOverlayView 的 dp 尺寸按副屏 density 计算
                turnClusterController = new TurnSignalController(turnClusterContext);
            }
            turnClusterController.attachToHost(turnClusterHost);
            turnClusterController.onWindowVisibilityChanged(true);
            // 窗口高度贴合箭头内容后，等首次 layout 完成拿到内容高度，把窗口 y 对准 topFactor
            repositionClusterTurnSignalWindowWhenReady();
            Log.d(TAG, "attachClusterTurnSignalWindow OK display=" + display.getDisplayId()
                    + " flags=" + display.getFlags() + " name=" + display.getName());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "cluster turn signal overlay attach failed", t);
            dismissClusterTurnSignalWindow();
            return false;
        }
    }

    /** 窗口高度贴合箭头内容后，把窗口 y 对准 topFactor（箭头中心 = topFactor × 屏高）。
     *  调用前需等 layout 完成（有内容高度可读）。 */
    private void positionClusterTurnSignalWindow() {
        if (turnClusterWindowManager == null || turnClusterHost == null
                || turnClusterHost.getParent() == null || turnClusterParams == null
                || turnClusterDisplay == null) {
            return;
        }
        int contentHeight = turnClusterHost.getHeight();
        if (contentHeight <= 0) return;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        try {
            turnClusterDisplay.getRealMetrics(dm);
        } catch (Throwable ignored) {
            return;
        }
        if (dm.heightPixels <= 0) return;
        int topPct = AppPrefs.getTurnSignalTop(this); // 已 clamp 到 MIN..MAX（8..92）
        int y = Math.round(topPct / 100f * dm.heightPixels - contentHeight / 2f);
        y = Math.max(0, Math.min(dm.heightPixels - contentHeight, y));
        turnClusterParams.y = y;
        try {
            turnClusterWindowManager.updateViewLayout(turnClusterHost, turnClusterParams);
        } catch (Throwable t) {
            Log.e(TAG, "cluster turn signal position update failed", t);
        }
    }

    /** 等窗口完成一次 layout（内容高度可读）后重新对准 y。
     *  用 layout listener 而非 post()：post 可能在首次 layout 前执行（高度仍为 0）而静默跳过；
     *  listener 保证 measure/layout 完成后才读高度。 */
    private void repositionClusterTurnSignalWindowWhenReady() {
        if (turnClusterHost == null) return;
        turnClusterHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (bottom - top <= 0) return; // 尚无真实高度，等下一次 layout
                v.removeOnLayoutChangeListener(this); // 一次性：只对准一次
                positionClusterTurnSignalWindow();
            }
        });
    }

    /** 回收副屏（HUD）转向窗口与控制器（监控线程由调用方决定是否一并停止） */
    private void dismissClusterTurnSignalWindow() {
        if (turnClusterController != null) {
            try {
                turnClusterController.destroy();
            } catch (Throwable ignored) {
            }
            turnClusterController = null;
        }
        if (turnClusterWindowManager != null && turnClusterHost != null
                && turnClusterHost.getParent() != null) {
            try {
                turnClusterWindowManager.removeViewImmediate(turnClusterHost);
            } catch (Throwable ignored) {
            }
        }
        turnClusterHost = null;
        turnClusterParams = null;
        turnClusterWindowManager = null;
        turnClusterContext = null;
        turnClusterDisplay = null;
    }

    /** 回收转向窗口与控制器（监控线程由调用方决定是否一并停止）。
     *  转向只在 HUD：中控无转向窗口，此处仅回收副屏（HUD）窗口。 */
    private void dismissTurnSignalOverlay() {
        dismissClusterTurnSignalWindow();
    }

    /**
     * 设置页预览。开关关闭时也允许预览：临时拉起窗口，
     * 预览结束后若仍为关闭状态则自动回收，不留常驻窗口。
     */
    private void previewTurnSignal(String raw) {
        Log.d(TAG, "previewTurnSignal raw=" + raw);
        AdayoTurnSignalMonitor.Direction direction = parsePreviewDirection(raw);
        // 【转向只在 HUD】预览仅走副屏（HUD）通道；无副屏（如模拟器）则跳过。
        // 预览结束由 turnPreviewCleanup（关闭态）或 ensureTurnSignalOverlay 重建（开启态）统一回收
        if (!attachClusterTurnSignalWindow()) return;
        refreshTurnSignalOverlay();
        if (turnClusterController == null) return;
        turnClusterController.preview(direction, TURN_PREVIEW_MS);
        if (!AppPrefs.isTurnSignalOverlayEnabled(this)) {
            mainHandler.removeCallbacks(turnPreviewCleanup);
            mainHandler.postDelayed(turnPreviewCleanup, TURN_PREVIEW_MS + 500L);
        }
    }

    private final Runnable turnPreviewCleanup = new Runnable() {
        @Override
        public void run() {
            if (!AppPrefs.isTurnSignalOverlayEnabled(OverlayService.this)) {
                dismissTurnSignalOverlay();
                stopSelfIfNoVisuals();
            }
        }
    };

    private AdayoTurnSignalMonitor.Direction parsePreviewDirection(String raw) {
        if (raw == null) return AdayoTurnSignalMonitor.Direction.HAZARD;
        String v = raw.trim().toLowerCase(java.util.Locale.US);
        if ("left".equals(v)) return AdayoTurnSignalMonitor.Direction.LEFT;
        if ("right".equals(v)) return AdayoTurnSignalMonitor.Direction.RIGHT;
        return AdayoTurnSignalMonitor.Direction.HAZARD;
    }

    /**
     * 模拟器/调试注入：解析 {"direction":"RIGHT","left":0,"right":1} 并写入 monitor。
     * 用于在模拟器上绕过 logcat uid 隔离，完整自测 Latch / 渲染链路。
     */
    private void injectTurnSignal(Intent intent) {
        if (intent == null) return;
        String raw = intent.getStringExtra(AppPrefs.EXTRA_TURN_SIGNAL_INJECT);
        Log.d(TAG, "injectTurnSignal raw=" + raw);
        AdayoTurnSignalMonitor.Direction direction = AdayoTurnSignalMonitor.Direction.OFF;
        int left = 0, right = 0;
        try {
            if (raw != null && !raw.isEmpty()) {
                JSONObject json = new JSONObject(raw);
                String dir = json.optString("direction", "OFF").trim().toUpperCase(java.util.Locale.US);
                direction = parseInjectDirection(dir);
                left = json.optInt("left", 0);
                right = json.optInt("right", 0);
            } else {
                direction = parseInjectDirection(intent.getStringExtra("direction"));
                left = intent.getIntExtra("left", 0);
                right = intent.getIntExtra("right", 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "turn signal inject parse failed: " + t.getMessage());
            return;
        }
        Log.d(TAG, "injectTurnSignal direction=" + direction + " left=" + left + " right=" + right);
        AdayoTurnSignalMonitor.inject(direction, left, right);
    }

    private AdayoTurnSignalMonitor.Direction parseInjectDirection(String raw) {
        if (raw == null) return AdayoTurnSignalMonitor.Direction.OFF;
        String v = raw.trim().toUpperCase(java.util.Locale.US);
        if ("LEFT".equals(v))   return AdayoTurnSignalMonitor.Direction.LEFT;
        if ("RIGHT".equals(v))  return AdayoTurnSignalMonitor.Direction.RIGHT;
        if ("HAZARD".equals(v)) return AdayoTurnSignalMonitor.Direction.HAZARD;
        return AdayoTurnSignalMonitor.Direction.OFF;
    }

    private void openMainActivity() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "open main activity failed", t);
        }
    }

    private void handleBroadcast(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (AppPrefs.ACTION_OVERLAY_SCALE_CHANGED.equals(action)) {
            rebuildOverlay();
            return;
        }
        if (AppPrefs.ACTION_MAIN_OVERLAY_CHANGED.equals(action)) {
            ensureOverlay();
            stopSelfIfNoVisuals();
            return;
        }
        if (AppPrefs.ACTION_CLUSTER_MIRROR_CHANGED.equals(action)) {
            clusterScale = -1f;
            ensureClusterMirror();
            // 【新增·转向 HUD 副屏】副屏 display 可能已切换：转向窗口跟随重建到新屏
            ensureTurnSignalOverlay();
            stopSelfIfNoVisuals();
            return;
        }
        if (AppPrefs.ACTION_CLUSTER_POSITION_CHANGED.equals(action)) {
            applySavedClusterPosition();
            return;
        }
        if (AppPrefs.ACTION_OVERLAY_STYLE_CHANGED.equals(action)) {
            rebuildOverlaysForStyleChange();
            return;
        }
        if (AppPrefs.ACTION_OVERLAY_CONTENT_CHANGED.equals(action)) {
            applyContentVisibilityPrefs();
            // ≤4s 呼吸动画开关切换需立即重建渲染，不能等 1s ticker 或下一条广播
            renderTrafficLights();
            return;
        }
        if (AppPrefs.ACTION_DISPLAY_POLICY_CHANGED.equals(action)) {
            refreshDisplayPolicies();
            updateOverspeedWarning();
            stopSelfIfNoVisuals();
            return;
        }
        // 【新增·转向 HUD】开关/颜色/特效/透明度/尺寸/位置变更
        if (AppPrefs.ACTION_TURN_SIGNAL_CHANGED.equals(action)) {
            ensureTurnSignalOverlay();
            stopSelfIfNoVisuals();
            return;
        }
        // 【新增·转向 HUD】预览
        if (AppPrefs.ACTION_TURN_SIGNAL_PREVIEW.equals(action)) {
            previewTurnSignal(intent.getStringExtra(AppPrefs.EXTRA_TURN_SIGNAL_PREVIEW));
            return;
        }
        // 【新增·转向 HUD】模拟器/调试注入：绕过 logcat，直接把状态写入 monitor
        if (AppPrefs.ACTION_TURN_SIGNAL_INJECT.equals(action)) {
            injectTurnSignal(intent);
            return;
        }
        if (android.content.Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
            rebuildOverlay();
            if (AppPrefs.isClusterMirrorEnabled(this)) {
                dismissClusterMirror();
                ensureClusterMirror();
            }
            // 【新增·转向 HUD】日夜主题 / 分辨率变化后重算调光色与画布尺寸
            refreshTurnSignalOverlay();
            return;
        }
        boolean targetBroadcastChanged = updateTargetBroadcastActivity(action);
        if (targetBroadcastChanged) {
            ensureOverlay();
            syncMainOverlayAttachment();
            ensureClusterMirror();
        }
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        ensureOverlay();
        boolean displayPolicyChanged = targetBroadcastChanged || updateNavigationActivityFromExtras(extras);
        updateModeFromExtras(extras);
        updateTurnFromExtras(extras);
        updateAlertDetails(extras);

        int keyType = intValue(extras, "KEY_TYPE", -1);
        boolean trafficLightAction = action != null
                && action.toLowerCase(java.util.Locale.US).contains("traffic_light");
        if (keyType == AmapConstants.KEY_TYPE_TRAFFIC_LIGHT
                || trafficLightAction
                || extras.containsKey("trafficLightStatus")
                || extras.containsKey("TRAFFIC_LIGHT_STATUS")
                || extras.containsKey("traffic_light_status")
                || extras.containsKey("redLightCountDownSeconds")
                || extras.containsKey("RED_LIGHT_COUNT_DOWN_SECONDS")
                || extras.containsKey("red_light_count_down_seconds")
                || extras.containsKey("greenLightLastSecond")
                || extras.containsKey("GREEN_LIGHT_LAST_SECOND")
                || extras.containsKey("green_light_last_second")
                || extras.containsKey("leftRedLightCountDownSeconds")
                || extras.containsKey("straightRedLightCountDownSeconds")
                || extras.containsKey("rightRedLightCountDownSeconds")
                || extras.containsKey("trafficLights")
                || extras.containsKey("trafficLightInfo")
                || extras.containsKey("cameraLightInfo")
                || extras.containsKey("cameraLightInfos")
                || extras.containsKey("cameraLightInfoWrapper")
                || extras.containsKey("cameraLights")
                || extras.containsKey("lightInfos")
                || extras.containsKey("dir")) {
            updateTrafficLights(extras);
        }

        int speed = intValue(extras, "CUR_SPEED", intValue(extras, "SPEED", -1));
        if (speed >= 0) {
            currentVehicleSpeed = speed;
        }
        updateOverspeedWarning();
        if (displayPolicyChanged) {
            syncMainOverlayAttachment();
            ensureClusterMirror();
        }
    }

    private void applyContentVisibilityPrefs() {
        syncTrafficLightVisibility();
        refreshPanelVisibility();
        updateClusterPosition();
    }

    private void applyPanelStyle() {
        if (panel != null) panel.setBackground(createMainPanelBackground());
        if (clusterPanel != null) clusterPanel.setBackground(createClusterPanelBackground());
    }

    private GradientDrawable createMainPanelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        // 【1840 修复】夜间模式深色底可见，白天保持透明悬浮
        int opacity = isNightMode() ? 85 : 0;
        bg.setColor(AppPrefs.withAlpha(nightPaletteBg(0), opacity));
        return bg;
    }

    private GradientDrawable createClusterPanelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(clusterDp(14));
        // 【1840 修复】夜间模式深色底可见，白天保持透明悬浮
        int opacity = isNightMode() ? 85 : 0;
        bg.setColor(AppPrefs.withAlpha(nightPaletteBg(0), opacity));
        return bg;
    }

    /** Night mode: true = night palette, false = day palette. */
    private boolean isNightMode() {
        return AppPrefs.isNightMode(this);
    }

    /** Reduce traffic light and text brightness in night mode (~35% darker). */
    private int nightDim(int color) {
        if (!isNightMode()) return color;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r * 0.70f);
        g = (int) (g * 0.70f);
        b = (int) (b * 0.70f);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** White text, dimmed in night mode. */
    private int nightText() {
        return nightDim(0xFFFFFFFF);
    }

    private int nightPaletteBg(int baseOpacity) {
        return isNightMode()
                ? AmapConstants.PALETTE_BG[1]   // dark night bg
                : AmapConstants.PALETTE_BG[0];  // day bg
    }

    private int nightPaletteStroke(int baseWhiteAlpha) {
        return isNightMode()
                ? AmapConstants.PALETTE_STROKE[1]   // more transparent in night
                : AmapConstants.PALETTE_STROKE[0];  // normal stroke
    }

    private int primaryTextColor() {
        return isNightMode()
                ? AmapConstants.PALETTE_PRIMARY_TEXT[1]   // white in night
                : AppPrefs.usesDarkTextPalette(this) ? 0xFF0F172A : AmapConstants.PALETTE_PRIMARY_TEXT[0];
    }

    private void syncTrafficLightVisibility() {
        boolean visible = AppPrefs.isLightVisible(this)
                && lightRow != null
                && hasVisibleChild(lightRow);
        setPairedVisibility(lightRow, clusterLightRow, visible, true);
    }

    private static boolean hasVisibleChild(ViewGroup vg) {
        if (vg == null) return false;
        for (int i = 0; i < vg.getChildCount(); i++) {
            if (vg.getChildAt(i).getVisibility() == View.VISIBLE) return true;
        }
        return false;
    }

    private void setPairedVisibility(View main, View cluster, boolean visible) {
        setPairedVisibility(main, cluster, visible, false);
    }

    private void setPairedVisibility(View main, View cluster, boolean visible, boolean holdSpace) {
        int state;
        if (visible) {
            state = View.VISIBLE;
        } else if (holdSpace) {
            state = View.INVISIBLE;
        } else {
            state = View.GONE;
        }
        if (main != null) main.setVisibility(state);
        if (cluster != null) cluster.setVisibility(state);
    }

    // ======================= Traffic Light Rendering =======================

    private void updateTrafficLights(Bundle extras) {
        if (lightRow == null) return;
        TrafficLightParser.Result result = TrafficLightParser.parse(
                extras, inCruiseMode, navigationTurnDir, currentTurnIcon, trafficLights);
        inCruiseMode = result.setInCruiseMode;
        if (result.changed) {
            replaceTrafficLights(result.lights);
        }
        renderTrafficLights();
    }

    private void replaceTrafficLights(HashMap<Integer, TrafficLightParser.LightState> nextLights) {
        trafficLights.clear();
        trafficLights.putAll(nextLights);
    }

    private void renderTrafficLights() {
        if (lightRow == null) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, TrafficLightParser.LightState>> iterator = trafficLights.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, TrafficLightParser.LightState> entry = iterator.next();
            TrafficLightParser.LightState state = entry.getValue();
            if (now - state.updatedAt > state.ttlMs || TrafficLightParser.currentLightSeconds(state, now) <= 0) {
                iterator.remove();
            }
        }
        if (trafficLights.isEmpty()) {
            mainHandler.removeCallbacks(trafficLightTicker);
            if (inCruiseMode && lightRow.getChildCount() == 3) {
                for (int si = 0; si < 3; si++) {
                    if (cachedLightPills[si] != null) {
                        cachedLightPills[si].setVisibility(View.INVISIBLE);
                    }
                }
                if (clusterLightRow != null && clusterLightRow.getChildCount() == 3) {
                    for (int si = 0; si < 3; si++) {
                        if (cachedClusterLightPills[si] != null) {
                            cachedClusterLightPills[si].setVisibility(View.INVISIBLE);
                        }
                    }
                }
            } else {
                lightRow.removeAllViews();
                if (clusterLightRow != null) clusterLightRow.removeAllViews();
                cachedLightPills = new LinearLayout[3];
                cachedClusterLightPills = new LinearLayout[3];
            }
            lightRow.setVisibility(View.GONE);
            if (clusterLightRow != null) clusterLightRow.setVisibility(View.GONE);
            return;
        }

        ArrayList<Integer> keys = new ArrayList<>(trafficLights.keySet());
        Collections.sort(keys, (a, b) -> TrafficLightParser.directionPriority(a, inCruiseMode) - TrafficLightParser.directionPriority(b, inCruiseMode));
        if (!inCruiseMode && keys.size() > 1) {
            Integer preferred = preferredNavigationLightKey(keys);
            keys.clear();
            if (preferred != null) keys.add(preferred);
        }
        if (!inCruiseMode) {
            lightRow.removeAllViews();
            if (clusterLightRow != null) clusterLightRow.removeAllViews();
            cachedLightPills = new LinearLayout[3];
            cachedClusterLightPills = new LinearLayout[3];
        } else if (lightRow.getChildCount() != 3
                || (clusterLightRow != null && (clusterLightRow.getChildCount() != 3
                    || AppPrefs.isLightVerticalCluster(this) != cachedClusterPillsVertical))
                || AppPrefs.isLightVerticalMain(this) != cachedPillsVertical) {
            lightRow.removeAllViews();
            if (clusterLightRow != null) clusterLightRow.removeAllViews();
            cachedLightPills = new LinearLayout[3];
            cachedClusterLightPills = new LinearLayout[3];
            cachedPillsVertical = AppPrefs.isLightVerticalMain(this);
            cachedClusterPillsVertical = AppPrefs.isLightVerticalCluster(this);
            for (int si = 0; si < 3; si++) {
                int margin = scaledDp(0.75f, overlayScale);
                LinearLayout pill = new LinearLayout(this);
                pill.setOrientation(cachedPillsVertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER);
                pill.setVisibility(View.GONE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(margin, 0, margin, 0);
                lightRow.addView(pill, lp);
                cachedLightPills[si] = pill;

                TrafficLightParser.LightState dummy = new TrafficLightParser.LightState();
                dummy.color = 0x00000000;
                dummy.dir = -1;
                buildPillContent(pill, dummy, false, overlayScale, 0, cachedPillsVertical);

                if (clusterLightRow != null && clusterContext != null) {
                    LinearLayout clPill = new LinearLayout(clusterContext);
                    clPill.setOrientation(cachedClusterPillsVertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                    clPill.setGravity(Gravity.CENTER);
                    clPill.setVisibility(View.GONE);
                    LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(-2, -2);
                    clLp.setMargins(margin, 0, margin, 0);
                    clusterLightRow.addView(clPill, clLp);
                    cachedClusterLightPills[si] = clPill;
                    buildPillContent(clPill, dummy, false, clusterScale, 0, cachedClusterPillsVertical);
                }
            }
        }
        boolean mainVertical = AppPrefs.isLightVerticalMain(this);
        boolean clusterVertical = AppPrefs.isLightVerticalCluster(this);
        lightRow.setOrientation(LinearLayout.HORIZONTAL);
        if (clusterLightRow != null) clusterLightRow.setOrientation(LinearLayout.HORIZONTAL);
        boolean showMainDirectionLabel = AppPrefs.isLightDirectionVisible(this);
        boolean showClusterDirectionLabel = AppPrefs.isLightDirectionVisible(this);
        // ≤4s 呼吸动画：任一可见灯倒计时 ≤4s 时整体呼吸（alpha 0.25~1.0 正弦），tick 提速到 120ms
        boolean breathingOn = AppPrefs.isLightBreathingEnabled(this);
        boolean anyBreathing = false;
        if (breathingOn) {
            for (Map.Entry<Integer, TrafficLightParser.LightState> e : trafficLights.entrySet()) {
                if (TrafficLightParser.currentLightSeconds(e.getValue(), now) <= 4) {
                    anyBreathing = true;
                    break;
                }
            }
        }
        float breathAlpha = 1f;
        if (anyBreathing) {
            double phase = (System.currentTimeMillis() % 1000L) / 1000.0;
            breathAlpha = 0.25f + 0.75f * (float) Math.abs(Math.sin(phase * Math.PI));
        }
        if (inCruiseMode) {
            int[][] slotDirGroups = {
                    {AmapConstants.DIR_LEFT, AmapConstants.DIR_UTURN,
                            AmapConstants.DIR_DIAGONAL_LEFT_1, AmapConstants.DIR_DIAGONAL_LEFT_2},
                    {AmapConstants.DIR_STRAIGHT},
                    {AmapConstants.DIR_RIGHT, AmapConstants.DIR_RIGHT_ALT,
                            AmapConstants.DIR_DIAGONAL_RIGHT_1, AmapConstants.DIR_DIAGONAL_RIGHT_2}
            };
            TrafficLightParser.LightState[] slotStates = new TrafficLightParser.LightState[3];
            for (int si = 0; si < 3; si++) {
                for (int d : slotDirGroups[si]) {
                    TrafficLightParser.LightState s = trafficLights.get(d);
                    if (s != null && TrafficLightParser.currentLightSeconds(s, now) > 0) {
                        slotStates[si] = s;
                        break;
                    }
                }
            }
            for (int si = 0; si < 3; si++) {
                if (slotStates[si] != null) {
                    int sec = TrafficLightParser.currentLightSeconds(slotStates[si], now);
                    updateLightPillInPlace(cachedLightPills[si], slotStates[si],
                            showMainDirectionLabel, overlayScale, sec);
                    // 呼吸动画：≤4s 呼吸，否则恢复完全不透明（缓存槽复用需显式复位）
                    cachedLightPills[si].setAlpha(anyBreathing && sec <= 4 ? breathAlpha : 1f);
                    if (clusterLightRow != null && cachedClusterLightPills[si] != null) {
                        updateLightPillInPlace(cachedClusterLightPills[si], slotStates[si],
                                showClusterDirectionLabel, clusterScale, sec);
                        cachedClusterLightPills[si].setAlpha(anyBreathing && sec <= 4 ? breathAlpha : 1f);
                    }
                } else {
                    if (cachedLightPills[si] != null) cachedLightPills[si].setVisibility(View.GONE);
                    if (clusterLightRow != null && cachedClusterLightPills[si] != null) {
                        cachedClusterLightPills[si].setVisibility(View.GONE);
                    }
                }
            }
        } else {
            // Navigation mode: build fresh pills from remaining keys
            for (Integer key : keys) {
                TrafficLightParser.LightState state = trafficLights.get(key);
                if (state == null) continue;
                int sec = TrafficLightParser.currentLightSeconds(state, now);
                View mainPill = lightPill(this, state, showMainDirectionLabel, overlayScale, sec, mainVertical);
                if (anyBreathing && sec <= 4) mainPill.setAlpha(breathAlpha);
                lightRow.addView(mainPill);
                if (clusterLightRow != null && clusterContext != null) {
                    View clusterPill = lightPill(clusterContext, state, showClusterDirectionLabel, clusterScale, sec, clusterVertical);
                    if (anyBreathing && sec <= 4) clusterPill.setAlpha(breathAlpha);
                    clusterLightRow.addView(clusterPill);
                }
            }
        }
        lightRow.setVisibility(View.VISIBLE);
        if (clusterLightRow != null) clusterLightRow.setVisibility(View.VISIBLE);
        if (AppPrefs.isLightVisible(this) && hasVisibleChild(lightRow)) {
            showAnyPanel();
        }
        mainHandler.removeCallbacks(trafficLightTicker);
        if (!trafficLights.isEmpty()) {
            // 呼吸动画进行时 tick 提速到 120ms 保证流畅，平时维持 1s 省电
            mainHandler.postDelayed(trafficLightTicker, anyBreathing ? 120L : LIGHT_TICK_MS);
        }
    }

    private Integer preferredNavigationLightKey(ArrayList<Integer> keys) {
        if (navigationTurnDir >= 0 && trafficLights.containsKey(navigationTurnDir)) {
            return navigationTurnDir;
        }
        Integer best = null;
        for (Integer key : keys) {
            TrafficLightParser.LightState state = trafficLights.get(key);
            if (state == null) continue;
            if (best == null) {
                best = key;
                continue;
            }
            TrafficLightParser.LightState old = trafficLights.get(best);
            if (old == null || TrafficLightParser.currentLightSeconds(state, System.currentTimeMillis())
                    < TrafficLightParser.currentLightSeconds(old, System.currentTimeMillis())) {
                best = key;
            }
        }
        return best;
    }

    private void buildPillContent(LinearLayout pill, TrafficLightParser.LightState state,
                                  boolean showDirectionLabel, float scale, int seconds,
                                  boolean vertical) {
        float oldDensity = activeDensity;
        activeDensity = pill.getContext().getResources().getDisplayMetrics().density;
        try {
            int circleSize = scaledDp(24, scale);
            FrameLayout circle = new FrameLayout(pill.getContext());
            GradientDrawable circleBg = new GradientDrawable();
            circleBg.setShape(GradientDrawable.OVAL);
            circleBg.setColor(nightDim(state.color));
            circle.setBackground(circleBg);
            circle.setLayoutParams(new FrameLayout.LayoutParams(circleSize, circleSize));
            circle.setMinimumWidth(circleSize);
            circle.setMinimumHeight(circleSize);

            boolean showArrowBadge = showDirectionLabel && state.dir >= 0;
            if (showArrowBadge) {
                View arrow = diyArrowBadge(pill.getContext(), state, scale);
                FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(-1, -1);
                arrowLp.gravity = Gravity.CENTER;
                circle.addView(arrow, arrowLp);
            }

            int textFixedW = scaledDp(30, scale);
            TextView timeText = new TextView(pill.getContext());
            timeText.setText(String.valueOf(seconds));
            timeText.setTextColor(nightText());
            timeText.setTypeface(Typeface.DEFAULT_BOLD);
            timeText.setGravity(Gravity.CENTER);
            timeText.setTextSize(scaledSp(18f, scale));
            timeText.setMinWidth(textFixedW);
            timeText.setIncludeFontPadding(false);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(textFixedW, -2);
            if (vertical) {
                textLp.topMargin = scaledDp(1, scale);
            } else {
                textLp.leftMargin = scaledDp(1, scale);
                timeText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                textLp.gravity = Gravity.CENTER_VERTICAL;
            }

            pill.addView(circle);
            pill.addView(timeText, textLp);
        } finally {
            activeDensity = oldDensity;
        }
    }

    private void updateLightPillInPlace(LinearLayout pill, TrafficLightParser.LightState state,
                                        boolean showDirectionLabel, float scale, int seconds) {
        FrameLayout circle = (FrameLayout) pill.getChildAt(0);
        TextView timeText = (TextView) pill.getChildAt(1);

        GradientDrawable bg = (GradientDrawable) circle.getBackground();
        bg.setColor(nightDim(state.color));

        circle.removeAllViews();
        boolean showArrowBadge = showDirectionLabel && state.dir >= 0;
        if (showArrowBadge) {
            View arrow = diyArrowBadge(pill.getContext(), state, scale);
            FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(-1, -1);
            arrowLp.gravity = Gravity.CENTER;
            circle.addView(arrow, arrowLp);
        }

        timeText.setText(String.valueOf(seconds));
        pill.setVisibility(View.VISIBLE);
    }

    private View lightPill(Context context, TrafficLightParser.LightState state, boolean showDirectionLabel,
                           float scale, int seconds, boolean vertical) {
        float oldDensity = activeDensity;
        activeDensity = context.getResources().getDisplayMetrics().density;
        try {
            LinearLayout view = new LinearLayout(context);
            view.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER);

            int circleSize = scaledDp(24, scale);
            FrameLayout circle = new FrameLayout(context);
            GradientDrawable circleBg = new GradientDrawable();
            circleBg.setShape(GradientDrawable.OVAL);
            circleBg.setColor(nightDim(state.color));
            circle.setBackground(circleBg);
            circle.setMinimumWidth(circleSize);
            circle.setMinimumHeight(circleSize);

            boolean showArrowBadge = showDirectionLabel && state.dir >= 0;
            if (showArrowBadge) {
                View arrow = diyArrowBadge(context, state, scale);
                FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(-1, -1);
                arrowLp.gravity = Gravity.CENTER;
                circle.addView(arrow, arrowLp);
            }

            circle.setLayoutParams(new FrameLayout.LayoutParams(circleSize, circleSize));

            int textFixedW = scaledDp(30, scale);
            TextView timeText = new TextView(context);
            timeText.setText(String.valueOf(seconds));
            timeText.setTextColor(nightText());
            timeText.setTypeface(Typeface.DEFAULT_BOLD);
            timeText.setGravity(Gravity.CENTER);
            timeText.setIncludeFontPadding(false);
            timeText.setMinWidth(textFixedW);
            timeText.setTextSize(scaledSp(18f, scale));
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(textFixedW, -2);
            if (vertical) {
                view.addView(circle);
                textLp.topMargin = scaledDp(1, scale);
                view.addView(timeText, textLp);
            } else {
                view.addView(circle);
                textLp.leftMargin = scaledDp(1, scale);
                timeText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                textLp.gravity = Gravity.CENTER_VERTICAL;
                view.addView(timeText, textLp);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(scaledDp(2, scale), scaledDp(1, scale), scaledDp(2, scale), scaledDp(1, scale));
            view.setLayoutParams(lp);
            return view;
        } finally {
            activeDensity = oldDensity;
        }
    }

    private int lightArrowResourceId(TrafficLightParser.LightState state) {
        String prefix;
        if (state.color == AmapConstants.COLOR_RED) {
            prefix = "light_red_arrow_";
        } else if (state.color == AmapConstants.COLOR_YELLOW) {
            prefix = "light_yellow_arrow_";
        } else {
            prefix = "light_green_arrow_";
        }
        String suffix;
        int dir = state.dir;
        if (dir == 0) {
            suffix = "uturn";
        } else if (dir == 1 || dir == 5 || dir == 6) {
            suffix = "left";
        } else if (dir == 2 || dir == 3 || dir == 7 || dir == 8) {
            suffix = "right";
        } else {
            suffix = "straight";
        }
        int id = getResources().getIdentifier(prefix + suffix, "drawable", getPackageName());
        if (id == 0) {
            id = getResources().getIdentifier("light_green_arrow_straight", "drawable", getPackageName());
        }
        return id;
    }

    private View diyArrowBadge(Context context, TrafficLightParser.LightState state, float scale) {
        ImageView image = new ImageView(context);
        int arrowRes = lightArrowResourceId(state);
        image.setImageResource(arrowRes);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        if (isNightMode()) {
            image.setImageAlpha((int)(255 * 0.70f));
        }

        FrameLayout wrapper = new FrameLayout(context);
        wrapper.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        return wrapper;
    }



    // ======================= Mode / Turn / Alert Handling =======================

    private void updateModeFromExtras(Bundle extras) {
        // Simplified: only manage cruise/nav state for traffic lights
        int keyType = intValue(extras, "KEY_TYPE", -1);
        int state = intValue(extras, "EXTRA_STATE", -1);
        if (keyType != 10001 && keyType != 10019 && keyType != 60021) return;
        if (keyType == 10019 && state != 8 && state != 9 && state != 24 && state != 25) return;
        int type = intValue(extras, "TYPE", -1);
        int speed = intValue(extras, "CUR_SPEED", intValue(extras, "SPEED", -1));
        String road = valueString(extras, "CUR_ROAD_NAME", "NEXT_ROAD_NAME", "ROAD_NAME", "roadName");
        boolean hasRoute = hasAny(extras, "ROUTE_REMAIN_DIS_AUTO", "ROUTE_REMAIN_TIME_AUTO",
                "ROUTE_REMAIN_DIS", "ROUTE_REMAIN_TIME", "ETA_TEXT");

        if (keyType == 10019 && state == 24) {
            inCruiseMode = true;
        } else if (keyType == 10019 && state == 25) {
            inCruiseMode = false;
            navigationTurnDir = -1;
            trafficLights.clear();
        } else if (keyType == 10019 && state == 8) {
            inCruiseMode = false;
        } else if (keyType == 10019 && state == 9) {
            inCruiseMode = false;
            navigationTurnDir = -1;
            trafficLights.clear();
            currentLimitSpeed = -1;
        } else if (type == 2 || (!hasRoute && (speed >= 0 || !TextUtils.isEmpty(road)))) {
            inCruiseMode = true;
        } else if (keyType == 10001 || hasRoute) {
            inCruiseMode = false;
        }
        if (speed >= 0) {
            currentVehicleSpeed = speed;
        }
    }

    private void updateTurnFromExtras(Bundle extras) {
        // Minimal: extract turn direction for traffic light navigation direction
        int keyType = intValue(extras, "KEY_TYPE", -1);
        if (keyType != 10001) return;
        if (inCruiseMode) {
            navigationTurnDir = -1;
            currentTurnIcon = 0;
            return;
        }
        int icon = intValue(extras, "NEW_ICON", intValue(extras, "ICON", 0));
        if (icon <= 0) {
            navigationTurnDir = -1;
            currentTurnIcon = 0;
            return;
        }
        navigationTurnDir = TrafficLightParser.turnIconToTrafficDir(icon);
        currentTurnIcon = icon;
    }

    private void updateAlertDetails(Bundle extras) {
        // Minimal: extract speed limit for overspeed warning
        int limitedSpeed = intValue(extras, "LIMITED_SPEED", -1);
        int cameraSpeed = intValue(extras, "CAMERA_SPEED", -1);
        int displaySpeed = limitedSpeed > 0 ? limitedSpeed : cameraSpeed;
        if (displaySpeed > 0) {
            currentLimitSpeed = displaySpeed;
        }
    }

    // ======================= Overspeed Warning =======================

    private void updateOverspeedWarning() {
        int v = currentVehicleSpeed;
        int l = currentLimitSpeed;
        if (l <= 0 || v < 0 || v <= l) {
            stopOverspeedBlink();
            return;
        }
        float ratio = (float) v / l;
        boolean mildOn = AppPrefs.isOverspeedMildWarningEnabled(this);
        boolean mediumOn = AppPrefs.isOverspeedMediumWarningEnabled(this);
        if (!mildOn && !mediumOn) {
            stopOverspeedBlink();
            return;
        }
        // 三档：超速 <=10% 黄框（往复）；10%~20% 红框（往复）；>20% 红框（常亮不歇）
        int level;
        if (ratio <= 1.10f) {
            level = mildOn ? OVERSPEED_MILD : OVERSPEED_NONE;
        } else if (ratio <= 1.20f) {
            level = mediumOn ? OVERSPEED_MEDIUM : OVERSPEED_NONE;
        } else {
            level = mediumOn ? OVERSPEED_CRITICAL : OVERSPEED_NONE;
        }
        if (level == OVERSPEED_NONE) {
            stopOverspeedBlink();
            return;
        }
        // 正处于 20s 隐藏等待期：P2-5 若档位升级（黄框→红框/常亮）立即打断等待期重新显示，
        // 避免严重超速时红框缺失最长 20s；同级或降级则维持等待，避免无意义重启
        if (overspeedOffPhase) {
            if (level > overspeedLevel) {
                startOverspeedShow(level, overspeedColorForLevel(level));
            }
            return;
        }
        int color = overspeedColorForLevel(level);
        if (overspeedBlinks != null && overspeedLevel == level && overspeedColor == color) {
            return;
        }
        startOverspeedShow(level, color);
    }

    private int overspeedColorForLevel(int level) {
        if (level == OVERSPEED_MILD) return 0xFFFFEB3B;     // 黄（超速 <=10%）
        if (level == OVERSPEED_MEDIUM) return 0xFFFF0000;   // 红（超速 10%~20%） 往复
        if (level == OVERSPEED_CRITICAL) return 0xFFFF0000; // 红（超速 >20%） 常亮
        return 0;
    }

    // 稳态显示 OVERSPEED_ON_MS，然后隐藏并进入 OVERSPEED_OFF_MS 等待期；
    // 等待期结束后再次评估：若仍超速则重新显示，往复。
    private void startOverspeedShow(int level, int color) {
        stopOverspeedBlink();
        overspeedLevel = level;
        overspeedColor = color;
        applyOverspeedBorder(color);   // 稳态边框，不闪烁

        if (level == OVERSPEED_CRITICAL) {
            // 常亮红框（超速 >20%）：不歇，持续循环，直到降到 20% 以下或开关关闭才交回往复/停止
            overspeedBlinks = new Runnable() {
                @Override
                public void run() {
                    int v = currentVehicleSpeed;
                    int l = currentLimitSpeed;
                    boolean mediumOn = AppPrefs.isOverspeedMediumWarningEnabled(OverlayService.this);
                    float ratio = (l > 0 && v >= 0) ? ((float) v / l) : 0f;
                    if (!mediumOn || l <= 0 || v <= l || ratio <= 1.20f) {
                        // 退出常亮：开关关 / 回到限速内 / 降到 20% 以下 -> 交回正常逻辑
                        overspeedBlinks = null;
                        updateOverspeedWarning();
                        return;
                    }
                    applyOverspeedBorder(color);   // 保持红框常亮
                    mainHandler.postDelayed(this, 1000L);
                }
            };
            mainHandler.postDelayed(overspeedBlinks, 1000L);
            return;
        }

        // 往复档（黄框 / 红框 10%~20%）：稳态 5s -> 隐藏 -> 等 20s -> 再评估
        final long onStartedAt = System.currentTimeMillis();
        overspeedBlinks = new Runnable() {
            @Override
            public void run() {
                long onElapsed = System.currentTimeMillis() - onStartedAt;
                if (onElapsed < OVERSPEED_ON_MS) {
                    mainHandler.postDelayed(this, 200L);
                    return;
                }
                // 显示阶段结束 -> 隐藏边框，进入 20s 等待期
                applyOverspeedBorder(0);
                overspeedBlinks = null;
                overspeedOffPhase = true;
                overspeedOffRunnable = new Runnable() {
                    @Override
                    public void run() {
                        overspeedOffPhase = false;
                        overspeedOffRunnable = null;
                        updateOverspeedWarning();
                    }
                };
                mainHandler.postDelayed(overspeedOffRunnable, OVERSPEED_OFF_MS);
            }
        };
        mainHandler.postDelayed(overspeedBlinks, 200L);
    }

    private void stopOverspeedBlink() {
        if (overspeedBlinks != null) {
            mainHandler.removeCallbacks(overspeedBlinks);
            overspeedBlinks = null;
        }
        if (overspeedOffRunnable != null) {
            mainHandler.removeCallbacks(overspeedOffRunnable);
            overspeedOffRunnable = null;
        }
        overspeedOffPhase = false;
        overspeedLevel = OVERSPEED_NONE;
        applyOverspeedBorder(0);
    }

    private void applyOverspeedBorder(int color) {
        if (panelBackground != null) {
            if (color != 0) {
                panelBackground.setStroke(dp(1), color);
            } else {
                panelBackground.setStroke(0, 0);
            }
        } else if (panel != null && panel.getBackground() instanceof GradientDrawable) {
            panelBackground = (GradientDrawable) panel.getBackground();
            if (color != 0) {
                panelBackground.setStroke(dp(1), color);
            } else {
                panelBackground.setStroke(0, 0);
            }
        }
        if (clusterPanelBackground != null) {
            if (color != 0) {
                clusterPanelBackground.setStroke(clusterDp(1), color);
            } else {
                clusterPanelBackground.setStroke(0, 0);
            }
        } else if (clusterPanel != null && clusterPanel.getBackground() instanceof GradientDrawable) {
            clusterPanelBackground = (GradientDrawable) clusterPanel.getBackground();
            if (color != 0) {
                clusterPanelBackground.setStroke(clusterDp(1), color);
            } else {
                clusterPanelBackground.setStroke(0, 0);
            }
        }
    }

    // ======================= Display Policy =======================

    private void refreshDisplayPolicies() {
        boolean wasForeground = targetAppForeground;
        boolean wasBroadcast = targetBroadcastActive;
        targetAppForeground = isTargetAppForeground();
        boolean oldNavActive = navigationOrCruiseActive;
        long now = System.currentTimeMillis();
        // 巡航信号 TTL 更宽（45s），导航信号 TTL 较窄（12s），两者任一活跃即视为活跃
        boolean cruiseActive = (now - lastCruiseSignalAt) < CRUISE_ACTIVE_TTL_MS;
        boolean navActive = (now - lastNavigationSignalAt) < NAVIGATION_ACTIVE_TTL_MS;
        navigationOrCruiseActive = cruiseActive || navActive;
        targetBroadcastActive = (now - lastTargetBroadcastAt) < TARGET_BROADCAST_ACTIVE_TTL_MS;
        boolean navJustBecameInactive = oldNavActive && !navigationOrCruiseActive;
        boolean navJustBecameActive = !oldNavActive && navigationOrCruiseActive;
        if (navJustBecameInactive || navJustBecameActive) {
            Log.d(TAG, "navigation/cruise state changed: active=" + navigationOrCruiseActive);
        }
        if (navJustBecameInactive) {
            // P2-6：导航/巡航退出后清空限速与车速缓存（LIMITED_SPEED 广播已停），
            // 避免基于过期限速的超速边框误报残留
            currentLimitSpeed = -1;
            currentVehicleSpeed = -1;
            updateOverspeedWarning();
        }
        if (wasForeground != targetAppForeground || wasBroadcast != targetBroadcastActive) {
            Log.d(TAG, "display policy changed: targetFg=" + targetAppForeground + " broadcast=" + targetBroadcastActive);
        }
    }

    private boolean shouldShowMainOverlayForTargetBroadcast() {
        if (!AppPrefs.isShowMainWhenTargetForegroundEnabled(this)) return false;
        return targetBroadcastActive || navigationOrCruiseActive;
    }

    private boolean shouldHideMainOverlayForTargetForeground() {
        return AppPrefs.isHideMainWhenTargetForegroundEnabled(this) && targetAppForeground;
    }

    private boolean shouldHideClusterMirrorForInactiveNavigation() {
        if (!AppPrefs.isClusterMirrorEnabled(this)) return true;
        // 选项"导航/巡航退出隐藏仪表"：勾选=导航退出即隐藏；不勾=副屏常驻
        if (AppPrefs.isHideClusterWhenInactiveEnabled(this)) return !navigationOrCruiseActive;
        return false;
    }

    private boolean updateTargetBroadcastActivity(String action) {
        if (isAmapAction(action)) {
            lastTargetBroadcastAt = System.currentTimeMillis();
            if (!targetBroadcastActive && navigationOrCruiseActive) {
                targetBroadcastActive = true;
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean updateNavigationActivityFromExtras(Bundle extras) {
        int keyType = intValue(extras, "KEY_TYPE", -1);
        int state = intValue(extras, "EXTRA_STATE", -1);
        boolean wasActive = navigationOrCruiseActive;
        if (isNavigationActivitySignal(extras, keyType, state)) {
            long now = System.currentTimeMillis();
            // 巡航型信号（红绿灯倒计时 / 巡航状态）单独记录时间戳，用更宽的巡航 TTL
            if (isCruiseTypeSignal(extras, keyType)) {
                lastCruiseSignalAt = now;
            } else {
                lastNavigationSignalAt = now;
            }
            navigationOrCruiseActive = true;
        }
        boolean changed = wasActive != navigationOrCruiseActive;
        if (changed) {
            Log.d(TAG, "nav activity: wasActive=" + wasActive + " nowActive=" + navigationOrCruiseActive);
        }
        return changed;
    }

    /** 巡航型信号：红绿灯倒计时 / 巡航状态类广播（频率低，需更长 TTL） */
    private boolean isCruiseTypeSignal(Bundle extras, int keyType) {
        if (keyType == AmapConstants.KEY_TYPE_TRAFFIC_LIGHT) return true;
        return extras != null && (extras.containsKey("trafficLightStatus")
                || extras.containsKey("redLightCountDownSeconds")
                || extras.containsKey("greenLightLastSecond")
                || extras.containsKey("traffic_light_status"));
    }

    private boolean isNavigationActivitySignal(Bundle extras, int keyType, int state) {
        if (keyType == 10019) {
            return state == 5 || state == 6 || state == 8 || state == 10 || state == 11 || state == 24;
        }
        if (keyType == 10001 || keyType == 60021 || keyType == 13012) {
            return true;
        }
        if (keyType == AmapConstants.KEY_TYPE_TRAFFIC_LIGHT && TrafficLightParser.hasCountdownPayload(extras)) {
            return true;
        }
        return hasAny(extras,
                "ROUTE_REMAIN_DIS_AUTO", "ROUTE_REMAIN_TIME_AUTO",
                "ROUTE_REMAIN_DIS", "ROUTE_REMAIN_TIME",
                "SEG_REMAIN_DIS", "NEXT_SEG_REMAIN_DIS",
                "trafficLightStatus", "redLightCountDownSeconds", "greenLightLastSecond");
    }

    private boolean isTargetAppForeground() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        String targetPackage = AppPrefs.getTargetPackage(this);
        try {
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()
                    && tasks.get(0).topActivity != null
                    && targetPackage.equals(tasks.get(0).topActivity.getPackageName())) {
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "read running task failed", t);
        }
        if (!AppPrefs.hasUsageStatsAccess(this)) return false;
        try {
            UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usageStatsManager == null) return false;
            long now = System.currentTimeMillis();
            UsageEvents events = usageStatsManager.queryEvents(now - 10000L, now);
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                String latestForegroundPackage = null;
                long latestForegroundAt = 0L;
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    int type = event.getEventType();
                    if ((type == UsageEvents.Event.MOVE_TO_FOREGROUND
                            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED))
                            && event.getTimeStamp() >= latestForegroundAt) {
                        latestForegroundAt = event.getTimeStamp();
                        latestForegroundPackage = event.getPackageName();
                    }
                }
                if (!TextUtils.isEmpty(latestForegroundPackage)) {
                    return targetPackage.equals(latestForegroundPackage);
                }
            }
            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 10000L, now);
            UsageStats latest = null;
            for (UsageStats stat : stats) {
                if (stat == null || TextUtils.isEmpty(stat.getPackageName())) continue;
                if (latest == null || stat.getLastTimeUsed() > latest.getLastTimeUsed()) {
                    latest = stat;
                }
            }
            return latest != null && targetPackage.equals(latest.getPackageName());
        } catch (Throwable t) {
            Log.d(TAG, "read usage stats failed", t);
        }
        return false;
    }

    private boolean isAmapAction(String action) {
        return ACTION_SEND.equals(action)
                || ACTION_RECV.equals(action)
                || "AUTO_GUIDE_INFO_FOR_INTERNAL_WIDGET".equals(action)
                || "AUTO_STATUS_FOR_INTERNAL_WIDGET".equals(action)
                || (action != null && action.startsWith("com.autonavi.amapauto."));
    }

    private boolean shouldRequestAmapData() {
        return AppPrefs.isMainOverlayEnabled(this)
                || AppPrefs.isClusterMirrorEnabled(this)
                || targetBroadcastActive
                || navigationOrCruiseActive;
    }

    private void requestTrafficLightInfo() {
        try {
            Intent intent = new Intent(ACTION_RECV);
            intent.setPackage(AppPrefs.getTargetPackage(this));
            intent.putExtra("KEY_TYPE", AmapConstants.KEY_TYPE_TRAFFIC_LIGHT);
            sendBroadcast(intent);
        } catch (Throwable t) {
            Log.e(TAG, "request traffic light info failed", t);
        }
    }

    // ======================= Position Persistence =======================

    private int getSavedOverlayX() {
        return getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .getInt(AppPrefs.KEY_OVERLAY_X, rawDp(24));
    }

    private int getSavedOverlayY() {
        return getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .getInt(AppPrefs.KEY_OVERLAY_Y, rawDp(220));
    }

    private int getSavedClusterX() {
        return AppPrefs.getClusterX(this, 610);
    }

    private int getSavedClusterY() {
        return AppPrefs.getClusterY(this, 180);
    }

    private void saveOverlayPosition() {
        if (params == null) return;
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putInt(AppPrefs.KEY_OVERLAY_X, params.x)
                .putInt(AppPrefs.KEY_OVERLAY_Y, params.y)
                .apply();
    }

    private void saveClusterPosition() {
        if (clusterParams == null) return;
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .edit()
                .putInt(AppPrefs.KEY_CLUSTER_X, clusterParams.x)
                .putInt(AppPrefs.KEY_CLUSTER_Y, clusterParams.y)
                .apply();
    }

    private void applySavedClusterPosition() {
        if (clusterParams == null) return;
        clusterParams.x = getSavedClusterX();
        clusterParams.y = getSavedClusterY();
        updateClusterPosition();
    }

    // ======================= Utility Methods =======================

    private boolean hasAny(Bundle extras, String... keys) {
        for (String key : keys) {
            if (extras.containsKey(key)) return true;
        }
        return false;
    }

    private Object safeExtra(Bundle extras, String key) {
        try {
            return extras.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private int intValue(Bundle extras, String key, int fallback) {
        Object value = safeExtra(extras, key);
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String valueString(Bundle extras, String... keys) {
        for (String key : keys) {
            Object value = safeExtra(extras, key);
            if (value == null) continue;
            String s = String.valueOf(value);
            if (!TextUtils.isEmpty(s) && !"0".equals(s) && !"null".equals(s)) {
                return s;
            }
        }
        return null;
    }



    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) ensureNotificationChannel(nm);
            builder = createNotificationBuilderWithChannel();
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("AMap Companion")
                .setContentText("\u76d1\u542c\u9ad8\u5fb7\u5bfc\u822a/\u5de1\u822a\u5e7f\u64ad")
                .setOngoing(true)
                .build();
    }

    private void ensureNotificationChannel(NotificationManager notificationManager) {
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "AMap Companion",
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        } catch (Throwable ignored) {
        }
    }

    private Notification.Builder createNotificationBuilderWithChannel() {
        try {
            return new Notification.Builder(this, CHANNEL_ID);
        } catch (Throwable ignored) {
            return new Notification.Builder(this);
        }
    }

    private int dp(int value) { return dp((float) value); }

    private int dp(float value) { return scaledDp(value, overlayScale); }

    private int rawDp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float sp(float value) { return scaledSp(value, overlayScale); }

    private int clusterDp(float value) { return scaledDp(value, clusterScale); }

    private int scaledDp(float value, float scale) {
        float density = activeDensity > 0f ? activeDensity : getResources().getDisplayMetrics().density;
        return (int) (value * scale * density + 0.5f);
    }

    private float scaledSp(float value, float scale) {
        return value * scale;
    }
}