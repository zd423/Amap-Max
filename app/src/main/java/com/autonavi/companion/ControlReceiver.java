package com.autonavi.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 手动/脚本触发通道。adb 无需打开界面即可触发：
 *   adb shell am broadcast -a com.autonavi.companion.action.DISABLE_AEB
 */
public class ControlReceiver extends BroadcastReceiver {

    public static final String ACTION_DISABLE = "com.autonavi.companion.action.DISABLE_AEB";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("AebDisabler", "ControlReceiver action=" + (intent == null ? null : intent.getAction()));
        AebDisableService.start(context, true);
    }
}
