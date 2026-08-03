package com.autonavi.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * iOS 18 风格开关：灰色/绿色圆角滑轨 + 白色圆钮 + 位移动画。
 * 纯 Java 自绘，不依赖 Material / AndroidX（本工程编译 classpath 仅 android.jar）。
 */
public class IosSwitch extends View {

    private static final int WIDTH_DP = 51;
    private static final int HEIGHT_DP = 31;
    private static final int KNOB_MARGIN_DP = 2;

    private boolean checked = false;
    private float knobPos = 0f; // 0..1，0=关 1=开

    private final int onColor = 0xFF34C759;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnCheckedChangeListener listener;
    private final float density;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(IosSwitch s, boolean checked);
    }

    public IosSwitch(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        knobPaint.setColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            knobPaint.setShadowLayer(dp(1.5f), 0, dp(1), 0x40000000);
        }
        setLayerType(LAYER_TYPE_SOFTWARE, null); // 让阴影生效
    }

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (WIDTH_DP * density + 0.5f);
        int h = (int) (HEIGHT_DP * density + 0.5f);
        setMeasuredDimension(w, h);
    }

    /** 关闭态滑轨色：跟随深浅色（浅 #E9E9EA / 深 #39393D） */
    private int offColor() {
        int mode = getContext().getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? 0xFF39393D : 0xFFE9E9EA;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float r = h / 2f;

        // 滑轨：在 off→on 之间做颜色插值
        trackPaint.setColor(lerpColor(offColor(), onColor, knobPos));
        canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, trackPaint);

        // 圆钮
        float knobD = h - dp(KNOB_MARGIN_DP) * 2;
        float maxX = w - knobD - dp(KNOB_MARGIN_DP) * 2;
        float cx = dp(KNOB_MARGIN_DP) + knobPos * maxX + knobD / 2f;
        float cy = h / 2f;
        canvas.drawCircle(cx, cy, knobD / 2f, knobPaint);
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public void setChecked(boolean c) {
        setChecked(c, true);
    }

    public void setChecked(boolean c, boolean animate) {
        if (checked == c && knobPos == (c ? 1f : 0f)) {
            return;
        }
        checked = c;
        if (!animate) {
            knobPos = c ? 1f : 0f;
            invalidate();
            return;
        }
        animateTo(c ? 1f : 0f);
    }

    public boolean isChecked() {
        return checked;
    }

    private void animateTo(float target) {
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(knobPos, target);
        va.setDuration(220);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(animation -> {
            knobPos = (float) animation.getAnimatedValue();
            invalidate();
        });
        va.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            toggle();
        }
        return true;
    }

    public void toggle() {
        setChecked(!checked, true);
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }
}
