package com.autonavi.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AmapCompanion";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        String action = intent == null ? "" : intent.getAction();
        boolean isAutoStartEvent = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action);
        if (!isAutoStartEvent) {
            return;
        }
        if (!AppPrefs.isAutoStartEnabled(context)) {
            Log.d(TAG, "skip auto starting overlay service after " + action);
            return;
        }
        Log.d(TAG, "auto start overlay service after " + action);
        MainActivity.startOverlayService(context);
        // 【关闭 AEB】独立开关：打开过则开机自动再关一次（车辆重启后 AEB 自动恢复开启）
        if (AppPrefs.isAebEnabled(context)) {
            Log.d(TAG, "auto start AEB disabler after " + action);
            AebDisableService.start(context, false);
        }
    }
}
