package com.autonavi.companion.vehicle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import com.autonavi.companion.AppPrefs;

/**
 * 转向信号控制器（精确还原 FloatingWindowManager 逻辑）
 * <p>
 * Latch 机制：亮灯时记录方向，灭灯时延迟 500ms 清除后渲染（防抖，原版 950ms）。
 * Preview 机制：临时覆盖方向，到时恢复。
 * 所有 View 操作必须通过 Handler 在主线程执行。
 * <p>
 * 移植自 arcfox-turn-hud（Navi-Link 逆向还原版），集成到 AMap Max 时的改动：
 *   1. package 由 com.navi.link.vehicle 改为 com.autonavi.companion.vehicle
 *   2. 开关键由 "floating_config" 统一到 AppPrefs（amap_companion）
 *   3. 新增 refreshPreferences()：供 Service 在设置变更 / 日夜主题切换时主动刷新
 *   4. destroy() 增加空指针与线程保护
 */
public class TurnSignalController implements AdayoTurnSignalMonitor.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private TurnSignalOverlayView overlay;

    private AdayoTurnSignalMonitor.Direction latchedDirection = AdayoTurnSignalMonitor.Direction.OFF;
    private AdayoTurnSignalMonitor.Direction previewDirection;
    private AdayoTurnSignalMonitor.Direction renderedDirection = AdayoTurnSignalMonitor.Direction.OFF;

    private final Runnable clearLatched = new Runnable() {
        @Override
        public void run() {
            latchedDirection = AdayoTurnSignalMonitor.Direction.OFF;
            applyResolved();
        }
    };

    private final Runnable clearPreview = new Runnable() {
        @Override
        public void run() {
            previewDirection = null;
            applyResolved();
        }
    };

    public TurnSignalController(Context context) {
        // 保留传入的原始 Context（可能是 createDisplayContext(display) 的副屏上下文），
        // 不再强制 getApplicationContext()：TurnSignalOverlayView 用 context.getResources()
        // 计算箭头 dp 尺寸，若统一成全局 application context，副屏(HUD)窗口的 density
        // 会错误地取主屏值，导致 HUD 上箭头尺寸失真。
        // 调用方只允许传长生命周期 Context（Service / display context），无泄漏风险。
        this.context = context;
        AdayoTurnSignalMonitor.addListener(this);
    }

    /** 附加覆盖层到容器（主线程） */
    public void attachToHost(FrameLayout host) {
        if (host == null) return;
        if (overlay == null) {
            overlay = new TurnSignalOverlayView(context);
        }
        if (overlay.getParent() != null) return;
        host.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setHostVisible(true);
        applySnapshotDirect(AdayoTurnSignalMonitor.snapshot());
    }

    /** 窗口可见性变化（主线程） */
    public void onWindowVisibilityChanged(boolean visible) {
        if (overlay != null) {
            overlay.setHostVisible(visible);
        }
    }

    /** 设置项 / 日夜主题变更后重载渲染参数（主线程） */
    public void refreshPreferences() {
        if (overlay != null) {
            overlay.refreshPreferences();
        }
        applyResolved();
    }

    /** 当前实际渲染的方向，供诊断使用 */
    public AdayoTurnSignalMonitor.Direction renderedDirection() {
        return renderedDirection;
    }

    /** 预览方向 (对应原版 previewTurnSignal, 主线程) */
    public void preview(AdayoTurnSignalMonitor.Direction direction, long durationMs) {
        if (direction == null) direction = AdayoTurnSignalMonitor.Direction.HAZARD;
        long d = Math.max(1000, durationMs);
        handler.removeCallbacks(clearPreview);
        previewDirection = direction;
        applySnapshotDirect(AdayoTurnSignalMonitor.snapshot());
        handler.postDelayed(clearPreview, d);
    }

    /** 销毁（主线程） */
    public void destroy() {
        AdayoTurnSignalMonitor.removeListener(this);
        handler.removeCallbacks(clearLatched);
        handler.removeCallbacks(clearPreview);
        previewDirection = null;
        latchedDirection = AdayoTurnSignalMonitor.Direction.OFF;
        renderedDirection = AdayoTurnSignalMonitor.Direction.OFF;
        if (overlay != null) {
            try {
                if (overlay.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
                }
            } catch (Throwable ignored) {
            }
        }
        overlay = null;
    }

    // ================================================================
    // Monitor 回调（后台线程 → post 到主线程）
    // ================================================================

    @Override
    public void onTurnSignalChanged(AdayoTurnSignalMonitor.Snapshot snapshot) {
        handler.post(() -> applySnapshotDirect(snapshot));
    }

    // ================================================================
    // 内部逻辑（主线程）
    // ================================================================

    /** 对应原版 applyTurnSignalSnapshot */
    private void applySnapshotDirect(AdayoTurnSignalMonitor.Snapshot snapshot) {
        AdayoTurnSignalMonitor.Direction direction;
        if (snapshot == null) {
            direction = AdayoTurnSignalMonitor.Direction.OFF;
        } else {
            direction = snapshot.direction;
        }

        if (direction != AdayoTurnSignalMonitor.Direction.OFF) {
            // 亮灯 → 更新 latch，清除旧的 clear 回调
            latchedDirection = direction;
            handler.removeCallbacks(clearLatched);
        } else if (latchedDirection != AdayoTurnSignalMonitor.Direction.OFF) {
            // 灭灯 + latch 仍有值 → 500ms 后清除（原版 0x3b6=950ms；2026-08-03 用户实车反馈
            // 关闭延时偏长 → 改为 500ms，箭头回位更快，仍保留防抖作用）
            handler.removeCallbacks(clearLatched);
            handler.postDelayed(clearLatched, 500);
        }

        applyResolved();
    }

    private void applyResolved() {
        renderDirection(resolveDirection());
    }

    /**
     * 方向优先级：preview > latched > OFF（对应原版 resolveTurnSignalDirection）
     * <p>
     * 【与原版差异】原版在开关关闭时只放行 HAZARD 预览，LEFT/RIGHT 预览点了没反应。
     * 这里改为「预览永远生效」：enabled 开关只管真实 CAN 总线信号是否驱动 HUD，
     * 不再拦截用户主动触发的预览，避免设置页出现「点了没反应」的伪故障。
     */
    private AdayoTurnSignalMonitor.Direction resolveDirection() {
        if (previewDirection != null) return previewDirection;
        if (!AppPrefs.isTurnSignalOverlayEnabled(context)) {
            return AdayoTurnSignalMonitor.Direction.OFF;
        }
        if (latchedDirection != AdayoTurnSignalMonitor.Direction.OFF) return latchedDirection;
        return AdayoTurnSignalMonitor.Direction.OFF;
    }

    /** 渲染方向（对应原版 renderTurnSignalDirection） */
    private void renderDirection(AdayoTurnSignalMonitor.Direction direction) {
        if (overlay == null) return;
        renderedDirection = direction;
        overlay.setDirection(direction);
    }
}
