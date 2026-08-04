package com.autonavi.companion;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * 转向灯 HUD 的 iOS 18 风格设置底部弹窗。
 * 结构：抓取条 + 标题栏(完成) + 分组卡片[外观 / 布局 / 预览]。
 * 主设置页只保留总开关，详细设置从这里进入（符合车机一键关闭的安全诉求）。
 */
public final class TurnSignalSettingsSheet {

    // 【2026-08-03 用户加色】7 色：荧光青绿 / 青色 / 琥珀黄 / iOS红 / 【新增 iOS 亮绿 #34C759】/ 白 / 淡紫
    private static final int[] TURN_COLOR_PRESETS = {
            0xFF35E889, 0xFF00E5FF, 0xFFFFC400, 0xFFFF3B30, 0xFF34C759, 0xFFFFFFFF, 0xFFB388FF
    };
    private static final String[] TURN_EFFECT_NAMES = {
            "线性衰减", "波形脉冲", "流动追光", "正弦呼吸", "粒子拖尾", "双向流光"
    };
    // 【2026-08-03 用户加形状】5 形状：V形箭头 / 流水灯带 / 实心箭头 / 传统箭头 / 【新增 静态箭头(实车)】
    private static final String[] TURN_SHAPE_NAMES = {
            "V形箭头", "流水灯带", "实心箭头", "传统箭头", "静态箭头"
    };

    private static final int IOS_BLUE = 0xFF007AFF;
    private static final int IOS_GREEN = 0xFF34C759;
    /** 预览展示时长，与 OverlayService.TURN_PREVIEW_MS 保持一致 */
    private static final long TURN_PREVIEW_MS = 2600L;

    private final MainActivity activity;
    private Dialog dialog;
    private LinearLayout colorRow;
    private TextView effectValue;
    private TextView shapeValue;
    // 预览防抖：连续步进/切档时合并为一次预览（拖完才发广播，避免刷屏）
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private final Runnable previewRunnable = new Runnable() {
        @Override
        public void run() {
            preview("hazard");
        }
    };
    // 【2026-08-03 审查修复 P2-3】长按连发 Handler 集中登记，dismiss() 统一清理，
    // 防止「长按不放 + 点完成关闭」时 repeater 收不到 ACTION_CANCEL 无限自续发（泄漏）
    private final List<Handler> repeatHandlers = new ArrayList<>();

    private boolean isDarkMode() {
        int mode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 弹窗整体背景（浅 #F2F2F7 / 深 #000000） */
    private int bg() {
        return isDarkMode() ? 0xFF000000 : 0xFFF2F2F7;
    }

    /** 卡片背景（浅 #FFFFFF / 深 #1C1C1E） */
    private int card() {
        return isDarkMode() ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** 主文字（浅 #1C1C1E / 深 #FFFFFF） */
    private int label() {
        return isDarkMode() ? 0xFFFFFFFF : 0xFF1C1C1E;
    }

    /** 分隔线（浅 #E5E5EA / 深 #38383A） */
    private int separatorColor() {
        return isDarkMode() ? 0xFF38383A : 0xFFE5E5EA;
    }

    /** 箭头（浅 #C7C7CC / 深 #48484A） */
    private int chevronColor() {
        return isDarkMode() ? 0xFF48484A : 0xFFC7C7CC;
    }

    private TurnSignalSettingsSheet(MainActivity a) {
        this.activity = a;
    }

    public static void show(MainActivity activity) {
        new TurnSignalSettingsSheet(activity).build();
    }

    private int dp(float v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void build() {
        dialog = new Dialog(activity, R.style.IosDialog);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(bg());
        float r = dp(20);
        rootBg.setCornerRadius(r);
        root.setBackground(rootBg);
        root.setPadding(0, dp(4), 0, dp(8));

        // 标题栏
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(2), dp(16), dp(6));
        TextView title = new TextView(activity);
        title.setText("极狐转向");
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(label());
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        TextView done = new TextView(activity);
        done.setText("完成");
        done.setTextSize(17f);
        done.setTextColor(IOS_BLUE);
        done.setPadding(dp(8), dp(4), 0, dp(4));
        done.setOnClickListener(v -> dismiss());
        header.addView(done, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        root.addView(header);

        // 滚动内容
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(6));

        // —— 外观 ——
        LinearLayout appearance = groupedCard();
        // 箭头颜色：标签与 6 个色块同一行并列（左：文字，右：色块横排）
        LinearLayout colorRow = new LinearLayout(activity);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setPadding(dp(16), dp(4), dp(16), dp(6));
        TextView colorLabel = new TextView(activity);
        colorLabel.setText("箭头颜色");
        colorLabel.setTextSize(16f);
        colorLabel.setTextColor(label());
        colorLabel.setMinWidth(dp(84));
        colorRow.addView(colorLabel, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        this.colorRow = new LinearLayout(activity);
        this.colorRow.setOrientation(LinearLayout.HORIZONTAL);
        this.colorRow.setGravity(Gravity.CENTER_VERTICAL);
        int curColor = AppPrefs.getTurnSignalColor(activity);
        for (int c : TURN_COLOR_PRESETS) {
            this.colorRow.addView(buildColorChip(c, c == curColor));
        }
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        chipLp.setMarginStart(dp(4));
        colorRow.addView(this.colorRow, chipLp);
        appearance.addView(colorRow);
        appearance.addView(separator());
        LinearLayout shapeRow = rowLabel("箭头形状");
        shapeValue = new TextView(activity);
        shapeValue.setText(shapeName(AppPrefs.getTurnSignalShape(activity)));
        shapeValue.setTextSize(15f);
        shapeValue.setTextColor(IOS_BLUE);
        shapeRow.addView(shapeValue, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        shapeRow.addView(chevron());
        shapeRow.setClickable(true);
        shapeRow.setOnClickListener(v -> showShapeSheet());
        appearance.addView(shapeRow);
        appearance.addView(separator());
        LinearLayout effectRow = rowLabel("动画特效");
        effectValue = new TextView(activity);
        refreshEffectLabel();
        effectValue.setTextSize(15f);
        effectRow.addView(effectValue, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        effectRow.addView(chevron());
        effectRow.setClickable(true);
        effectRow.setOnClickListener(v -> showEffectSheet());
        appearance.addView(effectRow);
        content.addView(section(appearance));

        // —— 布局 ——
        // 【2026-08-03 用户指定默认值】透明度 100 / 尺寸 100 / 垂直 40% / 左右内缩 38% / 左侧内缩 30%；
        // 快速档位同步调整：新默认值必须是档位之一（或最高档），避免点档位按钮把默认值回退。
        LinearLayout layout = groupedCard();
        layout.addView(adjustRow("透明度", AppPrefs.KEY_TURN_SIGNAL_ALPHA,
                AppPrefs.MIN_TURN_SIGNAL_ALPHA, AppPrefs.MAX_TURN_SIGNAL_ALPHA,
                AppPrefs.getTurnSignalAlpha(activity), new int[]{30, 62, 100}, 5));
        layout.addView(separator());
        layout.addView(adjustRow("箭头尺寸", AppPrefs.KEY_TURN_SIGNAL_SIZE,
                AppPrefs.MIN_TURN_SIGNAL_SIZE, AppPrefs.MAX_TURN_SIGNAL_SIZE,
                AppPrefs.getTurnSignalSize(activity), new int[]{60, 100, 140}, 10));
        layout.addView(separator());
        layout.addView(adjustRow("垂直位置", AppPrefs.KEY_TURN_SIGNAL_TOP,
                AppPrefs.MIN_TURN_SIGNAL_TOP, AppPrefs.MAX_TURN_SIGNAL_TOP,
                AppPrefs.getTurnSignalTop(activity), new int[]{30, 40, 50}, 5));
        layout.addView(separator());
        layout.addView(adjustRow("左右内缩", AppPrefs.KEY_TURN_SIGNAL_HORIZONTAL,
                AppPrefs.MIN_TURN_SIGNAL_HORIZONTAL, AppPrefs.MAX_TURN_SIGNAL_HORIZONTAL,
                AppPrefs.getTurnSignalHorizontal(activity), new int[]{20, 30, 38}, 2));
        layout.addView(separator());
        // 左侧内缩（高级选项）：默认 30%（用户指定，适配左驾 HUD 投影遮挡）；档位 0/15/30，30 为最高档
        layout.addView(adjustRow("左侧内缩", AppPrefs.KEY_SAFE_LEFT,
                AppPrefs.MIN_SAFE_LEFT, AppPrefs.MAX_SAFE_LEFT,
                AppPrefs.getSafeLeft(activity), new int[]{0, 15, 30}, 1));
        content.addView(section(layout));

        // —— 预览：左转 / 右转 / 双闪 并排三列（省竖向空间），点击后高亮反馈 ——
        LinearLayout preview = groupedCard();
        LinearLayout previewRow = new LinearLayout(activity);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setPadding(dp(16), dp(8), dp(16), dp(8));
        previewRow.addView(previewButton("左转", "left", true, 0));
        previewRow.addView(previewButton("右转", "right", false, 1));
        previewRow.addView(previewButton("双闪", "hazard", false, 2));
        preview.addView(previewRow);
        content.addView(section(preview));

        scroll.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            // 【2026-08-03 用户再调】宽度 45% 屏宽 + 高度 85% 屏高（宽度 75%→65%→50%→47.5%→45% 逐步收窄）；
            // 1300×900 → 585×765px。
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int wPx = (int) (dm.widthPixels * 0.45);
            int maxH = (int) (dm.heightPixels * 0.85);
            w.setLayout(wPx, maxH);
            w.setGravity(Gravity.CENTER);
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void dismiss() {
        previewHandler.removeCallbacks(previewRunnable);
        previewStateHandler.removeCallbacks(previewStateClear);
        // 【2026-08-03 审查修复 P2-3】清理全部长按连发 Handler，防非触摸路径退出时自续发泄漏
        for (Handler h : repeatHandlers) {
            h.removeCallbacksAndMessages(null);
        }
        repeatHandlers.clear();
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    // ── 样式工具 ──────────────────────────────────────────────
    private LinearLayout groupedCard() {
        LinearLayout ll = new LinearLayout(activity);
        ll.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card());
        bg.setCornerRadius(dp(12));
        ll.setBackground(bg);
        ll.setPadding(0, dp(4), 0, dp(4));
        return ll;
    }

    private LinearLayout section(LinearLayout card) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(card, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        wrap.setLayoutParams(lp);
        return wrap;
    }

    private LinearLayout rowLabel(String text) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(5), dp(16), dp(5));
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(16f);
        t.setTextColor(label());
        r.addView(t, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        return r;
    }

    private View separator() {
        View v = new View(activity);
        GradientDrawable s = new GradientDrawable();
        s.setColor(separatorColor());
        v.setBackground(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, 1);
        lp.setMargins(dp(16), 0, 0, 0);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView chevron() {
        TextView c = new TextView(activity);
        c.setText("›");
        c.setTextSize(24f);
        c.setTextColor(chevronColor());
        c.setPadding(0, 0, dp(2), 0);
        return c;
    }

    // ── 颜色 ──────────────────────────────────────────────────
    private View buildColorChip(int color, boolean selected) {
        View chip = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.setMarginEnd(dp(12));
        chip.setLayoutParams(lp);
        applyChip(chip, color, selected);
        chip.setTag(color);
        chip.setOnClickListener(v -> {
            int c = (Integer) v.getTag();
            saveInt(AppPrefs.KEY_TURN_SIGNAL_COLOR, c);
            for (int i = 0; i < colorRow.getChildCount(); i++) {
                View ch = colorRow.getChildAt(i);
                applyChip(ch, (Integer) ch.getTag(), ch == v);
            }
            activity.notifyTurnSignalChanged();
            preview("hazard");
        });
        return chip;
    }

    private void applyChip(View chip, int color, boolean sel) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(sel ? 3 : 1), sel ? IOS_BLUE : 0xFFC7C7CC);
        chip.setBackground(d);
    }

    // ── 调节行（预设档胶囊 + 步进按钮，替代原生 SeekBar）─────────
    // 一行 = 标签 | 3 档预设(小/中/大) | [−] 数值 [+]
    // 步进按钮支持长按连发；每次变更立即存盘 + 防抖预览，车机大触控目标友好
    private LinearLayout adjustRow(String label, String key, final int min, final int max,
                                   int cur, final int[] presets, final int step) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(4), dp(16), dp(4));

        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextSize(15f);
        t.setTextColor(label());
        t.setMinWidth(dp(64));
        r.addView(t, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        final int[] holder = {AppPrefs.clamp(cur, min, max)};

        // 数值显示（需先声明，预设/步进回调都要引用）
        final TextView valText = new TextView(activity);
        valText.setTextSize(14f);
        valText.setTextColor(label());
        valText.setMinWidth(dp(44));
        valText.setGravity(Gravity.CENTER);
        valText.setTypeface(Typeface.DEFAULT_BOLD);

        // 预设档胶囊：点选即跳档，当前值命中某档时高亮
        final TextView[] chips = new TextView[presets.length];
        LinearLayout presetGroup = new LinearLayout(activity);
        presetGroup.setOrientation(LinearLayout.HORIZONTAL);
        presetGroup.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < presets.length; i++) {
            final int pv = presets[i];
            TextView chip = new TextView(activity);
            chip.setText(String.valueOf(pv));
            chip.setTextSize(12f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(9), dp(5), dp(9), dp(5));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            if (i > 0) cLp.setMarginStart(dp(6));
            chip.setLayoutParams(cLp);
            chip.setOnClickListener(v -> {
                holder[0] = AppPrefs.clamp(pv, min, max);
                refreshAdjustRow(chips, holder, presets, valText);
                saveInt(key, holder[0]);
                activity.notifyTurnSignalChanged();
                previewDebounced();
            });
            presetGroup.addView(chip);
            chips[i] = chip;
        }
        LinearLayout.LayoutParams pgLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        pgLp.setMarginStart(dp(10));
        r.addView(presetGroup, pgLp);

        // 步进按钮（长按连发）
        View minus = stepButton("−", () -> {
            holder[0] = AppPrefs.clamp(holder[0] - step, min, max);
            refreshAdjustRow(chips, holder, presets, valText);
            saveInt(key, holder[0]);
            activity.notifyTurnSignalChanged();
            previewDebounced();
        });
        View plus = stepButton("+", () -> {
            holder[0] = AppPrefs.clamp(holder[0] + step, min, max);
            refreshAdjustRow(chips, holder, presets, valText);
            saveInt(key, holder[0]);
            activity.notifyTurnSignalChanged();
            previewDebounced();
        });
        r.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        valLp.setMarginStart(dp(6));
        valLp.setMarginEnd(dp(6));
        r.addView(valText, valLp);
        r.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));

        refreshAdjustRow(chips, holder, presets, valText);
        return r;
    }

    /** 刷新调节行：数值文本 + 预设档选中态 */
    private void refreshAdjustRow(TextView[] chips, int[] holder, int[] presets, TextView valText) {
        valText.setText(holder[0] + "%");
        for (int i = 0; i < chips.length; i++) {
            boolean hit = presets[i] == holder[0];
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(hit ? IOS_BLUE : (isDarkMode() ? 0xFF2C2C2E : 0xFFF2F2F7));
            bg.setCornerRadius(dp(8));
            chips[i].setBackground(bg);
            chips[i].setTextColor(hit ? Color.WHITE : IOS_BLUE);
        }
    }

    /** iOS 风格圆形步进按钮，支持长按连发（400ms 后每 150ms 触发一次） */
    private View stepButton(String symbol, final Runnable onChange) {
        TextView b = new TextView(activity);
        b.setText(symbol);
        b.setTextSize(18f);
        b.setTextColor(label());
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(isDarkMode() ? 0xFF2C2C2E : 0xFFE9E9EA);
        b.setBackground(bg);
        final Handler repeat = new Handler(Looper.getMainLooper());
        repeatHandlers.add(repeat); // 【2026-08-03 审查修复 P2-3】登记以便 dismiss 时统一清理
        final Runnable repeater = new Runnable() {
            @Override
            public void run() {
                onChange.run();
                repeat.postDelayed(this, 150L);
            }
        };
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onChange.run();              // 单击立即触发一次
                    repeat.postDelayed(repeater, 400L); // 按住 400ms 后开始连发
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    repeat.removeCallbacks(repeater);
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    /** 防抖预览：连续操作合并为一次（300ms 窗口） */
    private void previewDebounced() {
        previewHandler.removeCallbacks(previewRunnable);
        previewHandler.postDelayed(previewRunnable, 300L);
    }

    // ── 预览按钮 ──────────────────────────────────────────────
    // 三键带选中态：点击后立即蓝底白字高亮并保持到预览结束，明确"正在预览"反馈
    private final TextView[] previewBtns = new TextView[3];
    private final Handler previewStateHandler = new Handler(Looper.getMainLooper());

    private View previewButton(String text, final String dir, boolean first, final int idx) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextSize(16f);
        b.setTextColor(IOS_BLUE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(10), 0, dp(10));
        applyPreviewButtonStyle(b, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        if (!first) {
            lp.setMarginStart(dp(8));
        }
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            setPreviewButtonSelected(idx);
            preview(dir);
        });
        previewBtns[idx] = b;
        return b;
    }

    /** 高亮指定预览按钮，其余恢复灰底；预览结束自动恢复（防抖窗口叠加预览时长） */
    private void setPreviewButtonSelected(int idx) {
        for (int i = 0; i < previewBtns.length; i++) {
            applyPreviewButtonStyle(previewBtns[i], i == idx);
        }
        previewStateHandler.removeCallbacks(previewStateClear);
        previewStateHandler.postDelayed(previewStateClear, TURN_PREVIEW_MS + 400L);
    }

    private final Runnable previewStateClear = new Runnable() {
        @Override
        public void run() {
            for (TextView b : previewBtns) {
                if (b != null) applyPreviewButtonStyle(b, false);
            }
        }
    };

    private void applyPreviewButtonStyle(TextView b, boolean selected) {
        if (b == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? IOS_BLUE : (isDarkMode() ? 0xFF2C2C2E : 0xFFF2F2F7));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setTextColor(selected ? Color.WHITE : IOS_BLUE);
        b.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void preview(String dir) {
        if (!Settings.canDrawOverlays(activity)) {
            activity.startOverlayService(); // 引导悬浮窗权限
            return;
        }
        MainActivity.startOverlayService(activity);
        activity.sendTurnSignalPreview(dir);
    }

    private void refreshEffectLabel() {
        if (effectValue == null) return;
        // 【P2-2 修复】shape==4（静态箭头）时效果不生效，值文本追加提示并置灰
        boolean staticShape = AppPrefs.getTurnSignalShape(activity) == AppPrefs.TURN_SHAPE_STATIC;
        effectValue.setText(effectName(AppPrefs.getTurnSignalEffect(activity))
                + (staticShape ? "（静态箭头无效果）" : ""));
        effectValue.setTextColor(staticShape ? Color.GRAY : IOS_BLUE);
    }

    private void showEffectSheet() {
        int cur = AppPrefs.getTurnSignalEffect(activity);
        boolean staticShape = AppPrefs.getTurnSignalShape(activity) == AppPrefs.TURN_SHAPE_STATIC;
        AlertDialog.Builder b = new AlertDialog.Builder(activity, R.style.IosAlert);
        b.setTitle(staticShape ? "动画特效（静态箭头无效果）" : "动画特效");
        b.setSingleChoiceItems(TURN_EFFECT_NAMES, cur, (d, which) -> {
            saveInt(AppPrefs.KEY_TURN_SIGNAL_EFFECT, which);
            refreshEffectLabel();
            activity.notifyTurnSignalChanged();
            preview("hazard");
            d.dismiss();
        });
        b.setNegativeButton("取消", null);
        AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(R.drawable.ios_dialog_bg);
        }
    }

    /** 箭头形状选择：V形箭头 / 流水灯带(奥迪) / 实心箭头，选中即预览 */
    private void showShapeSheet() {
        int cur = AppPrefs.getTurnSignalShape(activity);
        AlertDialog.Builder b = new AlertDialog.Builder(activity, R.style.IosAlert);
        b.setTitle("箭头形状");
        b.setSingleChoiceItems(TURN_SHAPE_NAMES, cur, (d, which) -> {
            saveInt(AppPrefs.KEY_TURN_SIGNAL_SHAPE, which);
            if (shapeValue != null) {
                shapeValue.setText(shapeName(which));
            }
            refreshEffectLabel();
            activity.notifyTurnSignalChanged();
            preview("hazard");
            d.dismiss();
        });
        b.setNegativeButton("取消", null);
        AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(R.drawable.ios_dialog_bg);
        }
    }

    private String effectName(int e) {
        if (e < 0 || e >= TURN_EFFECT_NAMES.length) {
            e = AppPrefs.DEFAULT_TURN_SIGNAL_EFFECT;
        }
        return TURN_EFFECT_NAMES[e];
    }

    private String shapeName(int s) {
        if (s < 0 || s >= TURN_SHAPE_NAMES.length) {
            s = AppPrefs.DEFAULT_TURN_SIGNAL_SHAPE;
        }
        return TURN_SHAPE_NAMES[s];
    }

    private void saveInt(String key, int v) {
        activity.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
                .edit().putInt(key, v).apply();
    }
}
