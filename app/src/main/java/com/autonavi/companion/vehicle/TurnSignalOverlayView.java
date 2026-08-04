package com.autonavi.companion.vehicle;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import com.autonavi.companion.AppPrefs;

/**
 * 转向信号箭头覆盖层 View（精确还原版）
 * <p>
 * 在悬浮窗边缘渲染流动箭头动画。支持 5 种效果模式（0-4），
 * 通过 Canvas STROKE 双次绘制实现 ShadowLayer 发光效果。
 * <p>
 * 移植自 arcfox-turn-hud（Navi-Link 逆向还原版），集成到 AMap Max 时的改动：
 *   1. package 由 com.navi.link.vehicle 改为 com.autonavi.companion.vehicle
 *   2. SharedPreferences 由 "floating_config" 统一到 AppPrefs.PREFS（amap_companion），
 *      与主程序设置页共用同一份配置
 *   3. 【设计决策·转向不做夜间调光】转向箭头是安全警示信号（模拟真车转向灯），
 *      必须任何场景都保持用户所选颜色/亮度的高对比可见性；
 *      夜间模式仅作用于信息性内容（红绿灯/文本/背景），转向不受影响。
 *      红绿灯调光走 OverlayService.nightDim()（×0.70），与本 View 无关
 *   4. setLayerType 由 onDraw 移到构造函数（每帧调用是无谓开销）
 *   5. density 缓存为字段，避免每帧穿透 Resources 查询
 *   6. 新增 onDetachedFromWindow 停止动画，修复 View 被移除后 Handler 动画泄漏
 */
public final class TurnSignalOverlayView extends View {

    private final Paint paint = new Paint(1 /*ANTI_ALIAS_FLAG*/);
    private final Path path = new Path();

    private AdayoTurnSignalMonitor.Direction direction = AdayoTurnSignalMonitor.Direction.OFF;
    private boolean hostVisible = true;

    private int color = 0xFF35E889;
    private int renderColor = 0xFF35E889;       // 实际绘制色（=用户选择色；转向不做夜间调光）
    private int effect;                         // 0-5
    private int shape;                          // 0=V形箭头 1=流水灯带(奥迪) 2=实心圆头 3=传统箭头(用户新增)
    private float alphaFactor = 0.62f;          // 0.15 ~ 1.0
    private float sizeFactor = 1.0f;            // 0.6 ~ 1.8
    private float topFactor = 0.50f;            // 0.08 ~ 0.92
    private float horizontalInsetFactor;        // 0.0 ~ 0.42
    private float safeLeftFactor;               // 0.0 ~ 0.45 左侧内缩（默认 0 → 左右对称）
    private float density = 1f;

    // 动画改为 Handler 驱动 ~30fps（转向灯无需 60fps）：软件渲染层每帧成本减半，节流降 CPU/内存
    private static final long FRAME_INTERVAL_MS = 33L;
    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private boolean animating = false;
    private long animStartTime;
    private float phase;
    private final Runnable animTick = new Runnable() {
        @Override
        public void run() {
            if (!animating) return;
            // 与原 ValueAnimator 时长一致：effect=3 呼吸 900ms，其余 1150ms，线性循环
            float dur = (effect == 3) ? 900f : 1150f;
            long elapsed = SystemClock.elapsedRealtime() - animStartTime;
            phase = (elapsed % (long) dur) / dur;
            invalidate();
            animHandler.postDelayed(animTick, FRAME_INTERVAL_MS);
        }
    };

    public TurnSignalOverlayView(Context context) {
        this(context, null);
    }

    public TurnSignalOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(false);
        setFocusable(false);
        setBackgroundColor(0);
        density = context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // 软件渲染层：ShadowLayer 发光效果在硬件加速下不生效，必须在此声明一次
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        refreshPreferences();
    }

    // ================================================================
    // 公共 API
    // ================================================================

    /** 设置方向（含动画启停 + 可见性） */
    public void setDirection(AdayoTurnSignalMonitor.Direction value) {
        direction = (value != null) ? value : AdayoTurnSignalMonitor.Direction.OFF;
        applyVisibility();
        boolean visible = hostVisible && isVisibleForSide();
        // 静态箭头（shape==4）不启动动画循环，实车省电
        if (visible && effect > 0 && shape != 4) startAnimation();
        else stopAnimation();
        invalidate();
    }

    /** 当前渲染方向 */
    public AdayoTurnSignalMonitor.Direction getDirection() {
        return direction;
    }

    /** 宿主窗口可见性变化 */
    public void setHostVisible(boolean visible) {
        hostVisible = visible;
        applyVisibility();
        boolean shouldAnimate = hostVisible && isVisibleForSide();
        // 静态箭头（shape==4）不启动动画循环，实车省电
        if (shouldAnimate && effect > 0 && shape != 4) startAnimation();
        else stopAnimation();
        invalidate();
    }

    /** 从 SharedPreferences 重载所有配置 */
    public void refreshPreferences() {
        SharedPreferences sp = getContext().getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE);
        color = sp.getInt(AppPrefs.KEY_TURN_SIGNAL_COLOR, AppPrefs.DEFAULT_TURN_SIGNAL_COLOR);
        effect = Math.min(AppPrefs.MAX_TURN_SIGNAL_EFFECT, Math.max(0,
                sp.getInt(AppPrefs.KEY_TURN_SIGNAL_EFFECT, AppPrefs.DEFAULT_TURN_SIGNAL_EFFECT)));
        shape = Math.min(AppPrefs.MAX_TURN_SIGNAL_SHAPE, Math.max(0,
                sp.getInt(AppPrefs.KEY_TURN_SIGNAL_SHAPE, AppPrefs.DEFAULT_TURN_SIGNAL_SHAPE)));
        alphaFactor = Math.max(0.15f, Math.min(1.0f,
                sp.getInt(AppPrefs.KEY_TURN_SIGNAL_ALPHA, AppPrefs.DEFAULT_TURN_SIGNAL_ALPHA) / 100f));
        sizeFactor = Math.max(0.6f, Math.min(1.8f,
                sp.getInt(AppPrefs.KEY_TURN_SIGNAL_SIZE, AppPrefs.DEFAULT_TURN_SIGNAL_SIZE) / 100f));
        topFactor = Math.max(0.08f, Math.min(0.92f,
                sp.getInt(AppPrefs.KEY_TURN_SIGNAL_TOP, AppPrefs.DEFAULT_TURN_SIGNAL_TOP) / 100f));
        horizontalInsetFactor = Math.max(0f, Math.min(0.42f,
                sp.getInt(AppPrefs.KEY_TURN_SIGNAL_HORIZONTAL, AppPrefs.DEFAULT_TURN_SIGNAL_HORIZONTAL) / 100f));
        safeLeftFactor = Math.max(0f, Math.min(0.45f,
                sp.getInt(AppPrefs.KEY_SAFE_LEFT, AppPrefs.DEFAULT_SAFE_LEFT) / 100f));
        renderColor = color;   // 转向为安全警示信号，不做夜间调光，始终用用户所选颜色
        density = getResources().getDisplayMetrics().density;
        // 效果模式可能从静态切到动态，或反之，需要同步动画状态
        // 静态箭头（shape==4）不启动动画循环，实车省电
        boolean shouldAnimate = hostVisible && isVisibleForSide() && effect > 0 && shape != 4;
        if (shouldAnimate) startAnimation();
        else stopAnimation();
        invalidate();
    }

    // ================================================================
    // 内部逻辑
    // ================================================================

    private boolean isVisibleForSide() {
        return direction != AdayoTurnSignalMonitor.Direction.OFF;
    }

    private void applyVisibility() {
        setVisibility((hostVisible && isVisibleForSide()) ? VISIBLE : GONE);
    }

    // ---- 动画 ----

    private void startAnimation() {
        if (animating) return;
        animating = true;
        animStartTime = SystemClock.elapsedRealtime();
        animHandler.post(animTick);
    }

    private void stopAnimation() {
        animating = false;
        animHandler.removeCallbacks(animTick);
        phase = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        // 修复：View 从窗口移除后 Handler 动画仍在跑，会持有 View 引用导致泄漏
        stopAnimation();
        super.onDetachedFromWindow();
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isVisibleForSide()) return;

        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;

        float halfWidth = 11f * density * sizeFactor;        // chevron 半宽
        float halfHeight = 19f * density * sizeFactor;       // chevron 半高
        float spacing = 18f * density * sizeFactor;          // 箭头间距
        float edgeInset = Math.max(18f * density, horizontalInsetFactor * width);
        float centerY = Math.max(
                halfHeight + 4f * density,
                Math.min(height - halfHeight - 4f * density, topFactor * height));
        int baseAlpha = Math.round(Color.alpha(renderColor) * alphaFactor);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        // 左侧 = LEFT 方向或 HAZARD
        // 【车机适配】左基准 = max(边缘内缩, 左侧安全区避让)，防止画进左侧固定仪表区（时速/挡位，约 1/3 屏宽）
        if (direction == AdayoTurnSignalMonitor.Direction.LEFT
                || direction == AdayoTurnSignalMonitor.Direction.HAZARD) {
            float leftBase = Math.max(edgeInset, safeLeftFactor * width);
            if (shape == 1) {
                drawLightBar(canvas, false, leftBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else if (shape == 2) {
                drawFilledArrow(canvas, false, leftBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else if (shape == 3) {
                drawClassicArrow(canvas, false, leftBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else if (shape == 4) {
                drawStaticArrow(canvas, false, leftBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else {
                drawSide(canvas, false, leftBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            }
        }
        // 右侧 = RIGHT 方向或 HAZARD
        if (direction == AdayoTurnSignalMonitor.Direction.RIGHT
                || direction == AdayoTurnSignalMonitor.Direction.HAZARD) {
            float rightBase = width - edgeInset;
            if (shape == 1) {
                drawLightBar(canvas, true, rightBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else if (shape == 2) {
                drawFilledArrow(canvas, true, rightBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else if (shape == 3) {
                drawClassicArrow(canvas, true, rightBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else if (shape == 4) {
                drawStaticArrow(canvas, true, rightBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            } else {
                drawSide(canvas, true, rightBase, centerY, spacing,
                        halfWidth, halfHeight, density, baseAlpha);
            }
        }
        paint.clearShadowLayer();
    }

    /**
     * 绘制单侧箭头（3 个 chevron）
     * @param rightMirrored true=尖朝右, false=尖朝左
     * @param firstCenter 最近一个箭头中心的 x 坐标（靠边缘）
     */
    private void drawSide(Canvas canvas, boolean rightMirrored,
            float firstCenter, float centerY, float spacing,
            float halfWidth, float halfHeight, float density, int baseAlpha) {
        for (int i = 0; i < 3; i++) {
            float centerX = rightMirrored
                    ? firstCenter - i * spacing
                    : firstCenter + i * spacing;

            float intensity = effectIntensity(i);
            int alpha = Math.max(18, Math.round(baseAlpha * intensity));

            // --- 外层发光 (shadowRadius=10) ---
            int edgeColor = Color.argb(alpha,
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor));
            paint.setStrokeWidth(7f * density * sizeFactor);
            paint.setColor(Color.argb(
                    Math.round(alpha * 0.22f),
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            paint.setShadowLayer(10f * density * sizeFactor, 0, 0, edgeColor);
            buildChevron(centerX, centerY, halfWidth, halfHeight, rightMirrored);
            canvas.drawPath(path, paint);

            // --- 内层描边 (shadowRadius=5) ---
            paint.setShadowLayer(5f * density * sizeFactor, 0, 0, edgeColor);
            paint.setStrokeWidth(3.2f * density * sizeFactor);
            paint.setColor(edgeColor);
            canvas.drawPath(path, paint);

            // --- effect=2 的白色高亮闪烁 ---
            if (effect == 2) {
                int flashIndex = Math.min(2, (int) (phase * 3f));
                if (i == flashIndex) {
                    paint.clearShadowLayer();
                    paint.setStrokeWidth(1.35f * density * sizeFactor);
                    paint.setColor(Color.argb(
                            Math.min(255, alpha + 70), 255, 255, 255));
                    canvas.drawPath(path, paint);
                }
            }
        }
        // --- effect=4: 粒子拖尾 ---
        if (effect == 4) {
            drawParticleTrail(canvas, firstCenter, centerY, spacing,
                    density, baseAlpha, rightMirrored);
        }
    }

    /**
     * 奥迪灯厂「流水灯带」：一条由多颗 LED 灯珠组成的横向灯带，
     * 按 phase 从边缘向中心逐颗点亮，形成流水推进的奢侈观感。
     * 灯珠用圆角矩形，配合 glow 发光；未点亮段保持 12% 微光。
     */
    private void drawLightBar(Canvas canvas, boolean rightMirrored,
            float firstCenter, float centerY, float spacing,
            float halfWidth, float halfHeight, float density, int baseAlpha) {
        final int LED_COUNT = 8;
        float ledW = Math.max(5f, 7f * density * sizeFactor);   // 灯珠宽
        float ledH = Math.max(10f, 24f * density * sizeFactor); // 灯珠高（竖向 LED 更接近灯厂尾灯）
        float gap = Math.max(2f, 3f * density * sizeFactor);    // 灯珠间距
        float pitch = ledW + gap;

        // 从边缘向中心推进：progress ∈ [0, LED_COUNT+1)
        float progress = phase * (LED_COUNT + 1f);

        for (int i = 0; i < LED_COUNT; i++) {
            float centerX = rightMirrored
                    ? firstCenter - (i + 0.5f) * pitch
                    : firstCenter + (i + 0.5f) * pitch;

            // 每颗灯的亮度：已点亮段全亮，当前段渐变，前方 12% 微光
            float brightness;
            if (i < progress) {
                brightness = 1f;
            } else if (i < progress + 1f) {
                brightness = Math.max(0.12f, progress - i);
            } else {
                brightness = 0.12f;
            }
            int alpha = Math.max(10, Math.round(baseAlpha * brightness));

            // 外层 glow
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(alpha * 0.30f),
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            paint.setShadowLayer(12f * density * sizeFactor, 0, 0,
                    Color.argb(alpha, Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            canvas.drawRoundRect(centerX - ledW / 2f, centerY - ledH / 2f,
                    centerX + ledW / 2f, centerY + ledH / 2f,
                    ledW / 2f, ledW / 2f, paint);

            // 核心灯珠
            paint.setShadowLayer(0f, 0, 0, 0);
            paint.setColor(Color.argb(alpha,
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            canvas.drawRoundRect(centerX - ledW / 2f, centerY - ledH / 2f,
                    centerX + ledW / 2f, centerY + ledH / 2f,
                    ledW / 2f, ledW / 2f, paint);

            // 灯珠中心高光（让灯珠更"亮"）
            if (brightness > 0.5f) {
                paint.setColor(Color.argb(Math.round(alpha * 0.55f), 255, 255, 255));
                canvas.drawRoundRect(centerX - ledW / 4f, centerY - ledH / 3f,
                        centerX + ledW / 4f, centerY + ledH / 3f,
                        ledW / 4f, ledW / 4f, paint);
            }
        }
        paint.setShadowLayer(0f, 0, 0, 0);
        paint.setStyle(Paint.Style.STROKE);
    }

    /**
     * 实心圆头箭头：粗线圆头 chevron（类似 iOS 返回箭头观感），
     * 外层 glow + 内层实色，可选 effect=2 高亮闪烁。
     */
    private void drawFilledArrow(Canvas canvas, boolean rightMirrored,
            float firstCenter, float centerY, float spacing,
            float halfWidth, float halfHeight, float density, int baseAlpha) {
        float strokeW = Math.max(6f, 10f * density * sizeFactor);
        for (int i = 0; i < 3; i++) {
            float centerX = rightMirrored
                    ? firstCenter - i * spacing
                    : firstCenter + i * spacing;
            float intensity = effectIntensity(i);
            int alpha = Math.max(18, Math.round(baseAlpha * intensity));

            // 外层 glow
            int edgeColor = Color.argb(alpha,
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(strokeW * 1.6f);
            paint.setColor(Color.argb(Math.round(alpha * 0.20f),
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            paint.setShadowLayer(12f * density * sizeFactor, 0, 0, edgeColor);
            buildChevron(centerX, centerY, halfWidth, halfHeight, rightMirrored);
            canvas.drawPath(path, paint);

            // 核心实色粗线
            paint.setStrokeWidth(strokeW);
            paint.setShadowLayer(4f * density * sizeFactor, 0, 0, edgeColor);
            paint.setColor(edgeColor);
            canvas.drawPath(path, paint);

            // effect=2 高亮闪烁
            if (effect == 2) {
                int flashIndex = Math.min(2, (int) (phase * 3f));
                if (i == flashIndex) {
                    paint.clearShadowLayer();
                    paint.setStrokeWidth(strokeW * 0.5f);
                    paint.setColor(Color.argb(Math.min(255, alpha + 70), 255, 255, 255));
                    canvas.drawPath(path, paint);
                }
            }
        }
        paint.setShadowLayer(0f, 0, 0, 0);
    }

    /** 构建 chevron (∧ / ∨) Path：只 3 个点 */
    private void buildChevron(float centerX, float centerY,
            float halfWidth, float halfHeight, boolean rightMirrored) {
        path.reset();
        float tipX = rightMirrored ? centerX + halfWidth : centerX - halfWidth;
        float tailX = rightMirrored ? centerX - halfWidth : centerX + halfWidth;
        path.moveTo(tailX, centerY - halfHeight);
        path.lineTo(tipX, centerY);
        path.lineTo(tailX, centerY + halfHeight);
    }

    /**
     * 【2026-08-03 用户新增】传统箭头：经典实心转向指示灯图形（三角头 + 燕尾凹槽 + 杆一体成型），
     * FILL 填充 + 柔和 glow 发光，画 2 个（传统箭头单体更大更醒目，2 个足够辨识）。
     * 【2026-08-04 用户选择 B】从「三角头+短杆两段拼接」改为「一体成型」：三角头尾部带燕尾凹槽，
     * 杆从凹槽内长出，整条轮廓为单一连续 Path，无拼接缝隙（经典交通指示牌箭头造型）。
     */
    private void drawClassicArrow(Canvas canvas, boolean rightMirrored,
            float firstCenter, float centerY, float spacing,
            float halfWidth, float halfHeight, float density, int baseAlpha) {
        float headW = 26f * density * sizeFactor;   // 头长
        float headH = 36f * density * sizeFactor;   // 头高
        float barL = 16f * density * sizeFactor;    // 杆长
        float barW = 13f * density * sizeFactor;    // 杆宽
        for (int i = 0; i < 2; i++) {
            float centerX = rightMirrored
                    ? firstCenter - i * spacing
                    : firstCenter + i * spacing;
            float intensity = effectIntensity(i);
            int alpha = Math.max(18, Math.round(baseAlpha * intensity));
            int edgeColor = Color.argb(alpha,
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor));

            buildClassicArrowPath(centerX, centerY, headW, headH, barL, barW, rightMirrored);

            // 外层柔和 glow（灯珠光晕质感：大半径低透明度）
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(alpha * 0.18f),
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            paint.setShadowLayer(14f * density * sizeFactor, 0, 0, edgeColor);
            canvas.drawPath(path, paint);

            // 核心实色（带轻光晕，边缘不锐利）
            paint.setShadowLayer(6f * density * sizeFactor, 0, 0, edgeColor);
            paint.setColor(edgeColor);
            canvas.drawPath(path, paint);

            // effect=2 高亮闪烁（2 个箭头取 0/1 交替）
            if (effect == 2) {
                int flashIndex = Math.min(1, (int) (phase * 2f));
                if (i == flashIndex) {
                    paint.clearShadowLayer();
                    paint.setColor(Color.argb(Math.min(255, alpha + 70), 255, 255, 255));
                    canvas.drawPath(path, paint);
                }
            }
        }
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
    }

    /**
     * 【2026-08-04 用户新增·实车】静态箭头：复用一体成型燕尾箭头造型（B 版），
     * 但完全无动态 —— 恒定亮度（不随 phase/intensity 变化）、无白闪、无呼吸/流动。
     * 实车场景：按方向点亮后静止显示，干净利落；同时上层动画循环被 shape!=4 条件停掉，不白耗 CPU。
     * 【2026-08-04 用户确认】静态箭头每侧仅 1 个（循环 2→1），更贴近真车转向灯直觉。
     */
    private void drawStaticArrow(Canvas canvas, boolean rightMirrored,
            float firstCenter, float centerY, float spacing,
            float halfWidth, float halfHeight, float density, int baseAlpha) {
        float headW = 26f * density * sizeFactor;   // 与 B 传统箭头同尺寸
        float headH = 36f * density * sizeFactor;
        float barL = 16f * density * sizeFactor;
        float barW = 13f * density * sizeFactor;
        for (int i = 0; i < 1; i++) {
            float centerX = rightMirrored
                    ? firstCenter - i * spacing
                    : firstCenter + i * spacing;
            // 静态：所有箭头同一恒定亮度，不乘 effectIntensity，不参与相位动画
            int alpha = Math.max(18, baseAlpha);
            int edgeColor = Color.argb(alpha,
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor));

            buildClassicArrowPath(centerX, centerY, headW, headH, barL, barW, rightMirrored);

            // 外层柔和 glow（恒定）
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(alpha * 0.18f),
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            paint.setShadowLayer(14f * density * sizeFactor, 0, 0, edgeColor);
            canvas.drawPath(path, paint);

            // 核心实色（恒定）
            paint.setShadowLayer(6f * density * sizeFactor, 0, 0, edgeColor);
            paint.setColor(edgeColor);
            canvas.drawPath(path, paint);
        }
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
    }

    /**
     * 构建一体成型传统箭头 Path（经典交通指示牌样式）：
     * 三角头尾部带燕尾凹槽，杆从凹槽顶点向外延伸，整条轮廓为单一连续 Path，无拼接缝。
     * 尖朝右 = rightMirrored true；尖朝左 = rightMirrored false。
     */
    private void buildClassicArrowPath(float centerX, float centerY,
            float headW, float headH, float barL, float barW, boolean rightMirrored) {
        path.reset();
        float sign = rightMirrored ? 1f : -1f;
        float tipX = centerX + sign * headW;         // 尖端
        float headHalf = headH / 2f;
        float barHalf = barW / 2f;
        float notchX = centerX + sign * barW * 0.7f; // 燕尾顶点（= 杆右端面中心）
        float barEndX = centerX - sign * barL;       // 杆尾端
        // 尖端 → 上耳 → 燕尾上斜边 → 杆上边 → 杆端面 → 杆下边 → 燕尾下斜边 → 下耳 → 闭合
        path.moveTo(tipX, centerY);
        path.lineTo(centerX, centerY - headHalf);
        path.lineTo(notchX, centerY - barHalf);
        path.lineTo(barEndX, centerY - barHalf);
        path.lineTo(barEndX, centerY + barHalf);
        path.lineTo(notchX, centerY + barHalf);
        path.lineTo(centerX, centerY + headHalf);
        path.close();
    }

    /** effect=4 的粒子拖尾（5 个渐变圆） */
    private void drawParticleTrail(Canvas canvas, float firstCenter, float centerY,
            float spacing, float density, int baseAlpha, boolean rightMirrored) {
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {
            float travel = (phase + i * 0.14f) % 1f;
            float distance = 3.4f * spacing * travel;
            float x = rightMirrored ? firstCenter - distance : firstCenter + distance;
            float radius = ((1f - travel) * 2.2f + 1.4f) * density * sizeFactor;
            paint.setColor(Color.argb(
                    Math.round(baseAlpha * (1f - travel)),
                    Color.red(renderColor), Color.green(renderColor), Color.blue(renderColor)));
            canvas.drawCircle(x, centerY, radius, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
    }

    /**
     * 计算第 i 个箭头的强度因子（根据 effect 模式）
     * effect: 0=线性衰减, 1=波形脉冲, 2=恒定阶梯, 3=正弦呼吸, 4=尾部衰减, 5=双向流光
     */
    private float effectIntensity(int index) {
        switch (effect) {
            case 0: // 线性衰减: 0.72→0.82→0.92
                return 0.72f + index * 0.1f;

            case 1: { // 波形脉冲: 基于 phase 的三角波
                float local = (phase + index * 0.22f) % 1f;
                return 0.18f + (1f - Math.abs(2f * local - 1f)) * 0.82f;
            }
            case 2: // 恒定阶梯
                return 0.38f + index * 0.32f;

            case 3: { // 正弦呼吸
                float pulse = (float) (0.5 + Math.sin(phase * Math.PI * 2) * 0.5);
                return 0.3f + pulse * 0.7f;
            }
            case 5: { // 双向流光：光带沿 index 正反向交错推进
                float forward = (phase + index * 0.16f) % 1f;
                float backward = 1f - ((phase * 0.7f + (2 - index) * 0.16f) % 1f);
                float wave = Math.min(forward, backward);
                return 0.15f + (1f - Math.abs(2f * wave - 1f)) * 0.85f;
            }
            default: // effect=4: 尾部衰减（从近到远: 0.72→0.90→1.0）
                float local = (phase + index * 0.18f) % 1f;
                return 0.28f + (1f - local) * 0.72f;
        }
    }
}
