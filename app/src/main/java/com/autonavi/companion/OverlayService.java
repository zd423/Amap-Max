package com.autonavi.companion;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String CHANNEL_ID = "amap_companion";
    private static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV";
    private static final long LIGHT_TTL_MS = 4500L;
    private static final long LIGHT_TICK_MS = 1000L;
    private static final long DISPLAY_POLICY_POLL_MS = 1500L;
    private static final long NAVIGATION_ACTIVE_TTL_MS = 12000L;
    private static final long TARGET_BROADCAST_ACTIVE_TTL_MS = 15000L;
    private static final long PANEL_WIDTH_SHRINK_DELAY_MS = 2500L;
    private static final long OVERSPEED_BLINK_MS = 5000L;
    private static final long OVERSPEED_MILD_REST_MS = 20000L;
    private static final long OVERSPEED_MEDIUM_REST_MS = 10000L;
    private static final int OVERSPEED_NONE = 0;
    private static final int OVERSPEED_MILD = 1;
    private static final int OVERSPEED_MEDIUM = 2;
    private static final int OVERSPEED_HIGH = 3;
    private static final long ALERT_TTL_MS = 5000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout panel;
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
    private Runnable overspeedBlinks;
    private boolean overspeedBlinkOn;
    private boolean overspeedBlinkPhase;
    private int overspeedColor;
    private int overspeedLevel;
    private long overspeedPhaseStartedAt;
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
            stopSelfIfNoVisuals();
        }, 800);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
            return START_STICKY;
        }
        if (!onCreateDelayed) {
            ensureOverlay();
            ensureClusterMirror();
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
        dismissClusterMirror();
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
        activateClusterBridge();
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
        try {
            Method method = android.provider.Settings.class
                    .getMethod("canDrawOverlays", Context.class);
            Object result = method.invoke(null, this);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
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
        int opacity = 0;
        bg.setColor(withAlpha(nightPaletteBg(0), opacity));
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

        applyTextPalette();
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
                && !AppPrefs.isShowMainWhenTargetForegroundEnabled(this)) {
            stopSelf();
        }
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
            return;
        }
        if (AppPrefs.ACTION_DISPLAY_POLICY_CHANGED.equals(action)) {
            refreshDisplayPolicies();
            updateOverspeedWarning();
            stopSelfIfNoVisuals();
            return;
        }
        if (android.content.Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
            rebuildOverlay();
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
        syncModeVisibility();
        syncTrafficLightVisibility();
        refreshPanelVisibility();
        updateClusterPosition();
    }

    private void applyPanelStyle() {
        if (panel != null) panel.setBackground(createMainPanelBackground());
        if (clusterPanel != null) clusterPanel.setBackground(createClusterPanelBackground());
        applyTextPalette();
    }

    private GradientDrawable createMainPanelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        int opacity = 0;
        bg.setColor(withAlpha(nightPaletteBg(0), opacity));
        return bg;
    }

    private GradientDrawable createClusterPanelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(clusterDp(14));
        int opacity = 0;
        bg.setColor(withAlpha(nightPaletteBg(0), opacity));
        return bg;
    }

    private void applyTextPalette() {
        // Text palette removed in simplification
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
        r = (int) (r * 0.65f);
        g = (int) (g * 0.65f);
        b = (int) (b * 0.65f);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** White text, dimmed in night mode. */
    private int nightText() {
        return nightDim(0xFFFFFFFF);
    }

    private int nightPaletteBg(int baseOpacity) {
        return isNightMode()
                ? AmapConstants.PALETTE_BG[1][0]   // dark night bg
                : AmapConstants.PALETTE_BG[0][0];  // day bg
    }

    private int nightPaletteStroke(int baseWhiteAlpha) {
        return isNightMode()
                ? AmapConstants.PALETTE_STROKE[1][0]   // more transparent in night
                : AmapConstants.PALETTE_STROKE[0][0];  // normal stroke
    }

    private int primaryTextColor() {
        return isNightMode()
                ? AmapConstants.PALETTE_PRIMARY_TEXT[1]   // white in night
                : AppPrefs.usesDarkTextPalette(this) ? 0xFF0F172A : AmapConstants.PALETTE_PRIMARY_TEXT[0];
    }

    private int withAlpha(int color, int alphaPercent) {
        int alpha = Math.max(0, Math.min(255, Math.round(alphaPercent * 255f / 100f)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private void syncModeVisibility() {
        // Mode visibility removed in simplification
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

    private boolean preferLightState(TrafficLightParser.LightState candidate, TrafficLightParser.LightState old) {
        return TrafficLightParser.preferLightState(candidate, old);
    }

    private void replaceTrafficLights(HashMap<Integer, TrafficLightParser.LightState> nextLights) {
        trafficLights.clear();
        trafficLights.putAll(nextLights);
    }

    private void mergeTrafficLights(HashMap<Integer, TrafficLightParser.LightState> nextLights) {
        for (Map.Entry<Integer, TrafficLightParser.LightState> entry : nextLights.entrySet()) {
            TrafficLightParser.LightState old = trafficLights.get(entry.getKey());
            TrafficLightParser.LightState state = entry.getValue();
            if (old == null || old.status != state.status || preferLightState(state, old)) {
                trafficLights.put(entry.getKey(), state);
            }
        }
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
                    if (clusterLightRow != null && cachedClusterLightPills[si] != null) {
                        updateLightPillInPlace(cachedClusterLightPills[si], slotStates[si],
                                showClusterDirectionLabel, clusterScale, sec);
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
                lightRow.addView(mainPill);
                if (clusterLightRow != null && clusterContext != null) {
                    View clusterPill = lightPill(clusterContext, state, showClusterDirectionLabel, clusterScale, sec, clusterVertical);
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
            mainHandler.postDelayed(trafficLightTicker, LIGHT_TICK_MS);
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

    private View lightPill(TrafficLightParser.LightState state, boolean showDirectionLabel) {
        return lightPill(this, state, showDirectionLabel);
    }

    private View lightPill(Context context, TrafficLightParser.LightState state, boolean showDirectionLabel) {
        return lightPill(context, state, showDirectionLabel, overlayScale);
    }

    private View lightPill(Context context, TrafficLightParser.LightState state, boolean showDirectionLabel, float scale) {
        return lightPill(context, state, showDirectionLabel, scale,
                TrafficLightParser.currentLightSeconds(state, System.currentTimeMillis()));
    }

    private View lightPill(Context context, TrafficLightParser.LightState state, boolean showDirectionLabel,
                           float scale, int seconds) {
        return lightPill(context, state, showDirectionLabel, scale, seconds, false);
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
            image.setImageAlpha((int)(255 * 0.65f));
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
        int level;
        if (ratio >= 1.20f) {
            level = OVERSPEED_HIGH;
        } else if (ratio >= 1.10f) {
            level = mediumOn ? OVERSPEED_MEDIUM : OVERSPEED_NONE;
        } else {
            level = mildOn ? OVERSPEED_MILD : OVERSPEED_NONE;
        }
        if (level == OVERSPEED_NONE) {
            stopOverspeedBlink();
            return;
        }
        int color = overspeedColorForLevel(level);
        if (overspeedBlinks != null && overspeedLevel == level && overspeedColor == color) {
            return;
        }
        startOverspeedBlink(level, color);
    }

    private int overspeedColorForLevel(int level) {
        if (level == OVERSPEED_HIGH) return 0xFFFF0000;
        if (level == OVERSPEED_MEDIUM) return 0xFFFFEB3B;
        return 0xFFFFA500;
    }

    private void startOverspeedBlink(int level, int color) {
        stopOverspeedBlink();
        overspeedLevel = level;
        overspeedColor = color;
        overspeedBlinkOn = false;
        overspeedBlinkPhase = false;
        overspeedPhaseStartedAt = System.currentTimeMillis();
        applyOverspeedBorder(color);
        overspeedBlinks = new Runnable() {
            private int blinkCount = 0;
            private long startedAt = System.currentTimeMillis();

            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long elapsed = now - startedAt;
                if (elapsed >= OVERSPEED_BLINK_MS) {
                    stopOverspeedBlink();
                    return;
                }
                // Rest between blink groups
                long phaseElapsed = now - overspeedPhaseStartedAt;
                int restMs;
                if (level == OVERSPEED_MILD) {
                    restMs = (int) OVERSPEED_MILD_REST_MS;
                } else if (level == OVERSPEED_MEDIUM) {
                    restMs = (int) OVERSPEED_MEDIUM_REST_MS;
                } else {
                    restMs = 0;
                }
                if (phaseElapsed >= OVERSPEED_BLINK_MS + restMs) {
                    overspeedPhaseStartedAt = now;
                    blinkCount = 0;
                    phaseElapsed = 0;
                }
                if (phaseElapsed < OVERSPEED_BLINK_MS) {
                    // Blink phase
                    overspeedBlinkPhase = !overspeedBlinkPhase;
                    applyOverspeedBorder(overspeedBlinkPhase ? color : 0);
                }
                mainHandler.postDelayed(this, 120L);
            }
        };
        mainHandler.postDelayed(overspeedBlinks, 120L);
    }

    private void stopOverspeedBlink() {
        if (overspeedBlinks != null) {
            mainHandler.removeCallbacks(overspeedBlinks);
            overspeedBlinks = null;
        }
        overspeedBlinkOn = false;
        overspeedLevel = OVERSPEED_NONE;
        overspeedBlinkPhase = false;
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
        navigationOrCruiseActive = (System.currentTimeMillis() - lastNavigationSignalAt) < NAVIGATION_ACTIVE_TTL_MS;
        targetBroadcastActive = (System.currentTimeMillis() - lastTargetBroadcastAt) < TARGET_BROADCAST_ACTIVE_TTL_MS;
        boolean navJustBecameInactive = oldNavActive && !navigationOrCruiseActive;
        boolean navJustBecameActive = !oldNavActive && navigationOrCruiseActive;
        if (navJustBecameInactive || navJustBecameActive) {
            Log.d(TAG, "navigation/cruise state changed: active=" + navigationOrCruiseActive);
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
        
        return !navigationOrCruiseActive;
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
            lastNavigationSignalAt = System.currentTimeMillis();
            navigationOrCruiseActive = true;
        }
        boolean changed = wasActive != navigationOrCruiseActive;
        if (changed) {
            Log.d(TAG, "nav activity: wasActive=" + wasActive + " nowActive=" + navigationOrCruiseActive);
        }
        return changed;
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
        return AppPrefs.getClusterX(this, 600);
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

    private void activateClusterBridge() {
        // Stub 闂?was used to connect to cluster display service if needed
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

    private boolean booleanValue(Bundle extras, String key, boolean fallback) {
        Object value = safeExtra(extras, key);
        if (value == null) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        String s = String.valueOf(value);
        if ("1".equals(s) || "true".equalsIgnoreCase(s) || "\u662f".equals(s)) return true;
        if ("0".equals(s) || "false".equalsIgnoreCase(s) || "\u5426".equals(s)) return false;
        return fallback;
    }

    private double doubleValue(Bundle extras, String key, double fallback) {
        Object value = safeExtra(extras, key);
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
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
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
            Object channel = ctor.newInstance(CHANNEL_ID, "AMap Companion", 2);
            notificationManager.getClass()
                    .getMethod("createNotificationChannel", channelClass)
                    .invoke(notificationManager, channel);
        } catch (Throwable ignored) {
        }
    }

    private Notification.Builder createNotificationBuilderWithChannel() {
        try {
            Constructor<Notification.Builder> ctor =
                    Notification.Builder.class.getConstructor(Context.class, String.class);
            return ctor.newInstance(this, CHANNEL_ID);
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

    private float clusterSp(float value) { return scaledSp(value, clusterScale); }

    private int scaledDp(float value, float scale) {
        float density = activeDensity > 0f ? activeDensity : getResources().getDisplayMetrics().density;
        return (int) (value * scale * density + 0.5f);
    }

    private float scaledSp(float value, float scale) {
        return value * scale;
    }
}