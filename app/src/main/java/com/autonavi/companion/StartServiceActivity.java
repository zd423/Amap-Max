package com.autonavi.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * 透明无 UI 的入口 Activity。
 * 被外部（第三方自动化工具「打开应用」模式）启动时，仅负责拉起悬浮窗服务，
 * 随后立即 finish()，因此不会向用户展示任何页面。
 *
 * 用法：在工具里选「打开应用」时，挑名为「AMap Max 服务」的入口即可。
 * 若工具支持「发送意图 / 启动服务」，直接对 OverlayService 发
 * com.autonavi.companion.START_OVERLAY_SERVICE 亦可（见 AndroidManifest）。
 */
public class StartServiceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 不调用 setContentView —— 全透明、无页面。

        Intent svc = new Intent(this, OverlayService.class);
        svc.setAction(OverlayService.ACTION_START_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable ignored) {
            // 极端情况下退回普通启动，避免崩溃
            try {
                startService(svc);
            } catch (Throwable ignore) {
            }
        }

        finish();
    }
}
