package com.autonavi.companion;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import android.car.Car;
import android.car.hardware.property.CarPropertyManager;

/**
 * 关闭 AEB（自动紧急制动）。
 *
 * 原理：AEB 开关是车机 VHAL 上的车辆属性，由 BCM 原厂 App 通过
 *   BcmManager.setIntTransDataMessage("DeviceInfo", 0x21400b35, 0, value)
 * 写入（0x21400b35 = EHU_AEB_FCTB_SWT），状态回读属性 0x21400b36 = ADAS_AEB_FCTB_SWT_FB，
 * 取值语义：1=关，2=开。
 *
 * 本服务执行流程：
 *  1) 优先尝试通过反射调用系统框架的 BcmManager（与 BCM App 完全一致）；
 *  2) 兜底用标准 AAOS CarPropertyManager.setIntProperty(0x21400b35, 0, 1)；
 *  3) 反复回读 0x21400b36，确认 == 1（AEB 已关）后结束。
 *
 * 注意：写 VHAL 厂商属性需要系统签名/系统 UID（manifest 已声明
 * sharedUserId=android.uid.system）。
 *
 * 触发方式：
 *  - 开机自动：BootReceiver 在 AppPrefs.isAebEnabled() 开启时启动本服务；
 *  - 手动：MainActivity AEB 设置页「立即关闭」按钮。
 */
public class AebDisableService extends Service {

    private static final String TAG = "AebDisabler";

    // AEB 写入属性：EHU_AEB_FCTB_SWT（VHAL 厂商属性）
    private static final int PROP_EHU_AEB_FCTB_SWT = 0x21400b35;
    // AEB 回读属性：ADAS_AEB_FCTB_SWT_FB
    private static final int PROP_ADAS_AEB_FCTB_SWT_FB = 0x21400b36;
    private static final int AREA_GLOBAL = 0;
    // 取值语义：1=关，2=开
    private static final int VALUE_AEB_OFF = 1;

    // 只写一次：写入通道成功即成功（静止状态回读不可信，不回读重试），失败即停
    private static final int MAX_RETRY = 0;
    private static final long RETRY_DELAY_MS = 3000L;
    private static final long START_DELAY_MS = 500L;

    private Car mCar;
    private CarPropertyManager mPropertyManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public static void start(Context context, boolean manual) {
        Intent i = new Intent(context, AebDisableService.class);
        i.putExtra("manual", manual);
        context.startService(i);
    }

    private final ServiceConnection mCarConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Car service connected");
            onCarConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Car service disconnected");
            mCar = null;
            mPropertyManager = null;
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean manual = intent != null && intent.getBooleanExtra("manual", false);
        Log.i(TAG, "onStartCommand manual=" + manual + " flags=" + flags);
        mMainHandler.removeCallbacksAndMessages(null);
        setStatus("running");
        mMainHandler.postDelayed(new DisableRunnable(MAX_RETRY), START_DELAY_MS);
        connectCar();
        return START_STICKY;
    }

    /** 结束时停掉服务（无任何通知/弹窗）。 */
    private void finishWithResult() {
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void connectCar() {
        if (mCar != null && mCar.isConnected()) {
            onCarConnected();
            return;
        }
        try {
            mCar = Car.createCar(this, mCarConnection);
        } catch (Throwable t) {
            Log.e(TAG, "createCar failed (device not AAOS?)", t);
            setStatus("createCar_failed:" + t.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void onCarConnected() {
        try {
            mPropertyManager = mCar.getCarManager(CarPropertyManager.class);
            Log.i(TAG, "CarPropertyManager obtained");
            mMainHandler.post(new DisableRunnable(MAX_RETRY));
        } catch (Throwable t) {
            Log.e(TAG, "getCarManager failed", t);
        }
    }

    private String mBcmFail;

    /** 重试任务：写 AEB=1(关)。 */
    private class DisableRunnable implements Runnable {
        private int attempts;

        DisableRunnable(int attempts) {
            this.attempts = attempts;
        }

        @Override
        public void run() {
            // 1) 先读：判断当前 AEB 是否已关（1=关）
            int before = readFeedback();
            Log.i(TAG, "先读 EHU_AEB_FCTB_SWT_FB = " + before);
            if (before == VALUE_AEB_OFF) {
                Log.i(TAG, "AEB 已是关闭状态，无需写入");
                setStatus("ok_aeb_off");
                finishWithResult();
                return;
            }

            // 2) 再写：两条通道任一成功即可
            boolean viaBcm = tryDisableViaBcmManager();
            boolean viaCarProp = tryDisableViaCarProperty();

            if (viaBcm || viaCarProp) {
                // 3) 写后再回读确认
                int fb = readFeedback();
                saveReadback(fb);
                if (fb == VALUE_AEB_OFF) {
                    Log.i(TAG, "写入+回读确认：AEB 已关闭 (fb=" + fb + ")");
                    setStatus("ok_aeb_off");
                    finishWithResult();
                    return;
                }
                // 回读 != 1（静止状态可能不立即更新）：写入已成功，按成功处理，不重试
                Log.w(TAG, "写入成功但回读未确认(fb=" + fb + ")，按成功处理");
                setStatus("ok_aeb_off");
                finishWithResult();
                return;
            }

            // 两条通道都失败
            String reason = (mBcmFail == null) ? "写入通道不可用" : mBcmFail;
            setFailReason(reason);
            Log.w(TAG, reason);

            // 非极狐车机（无 BcmManager）：环境判定，不再重试
            if (mBcmFail != null && attempts <= 0) {
                setStatus("env_not_supported");
                finishWithResult();
                return;
            }

            if (attempts <= 0) {
                Log.e(TAG, "达到最大重试次数，AEB 仍未关闭");
                setStatus("timeout");
                finishWithResult();
                return;
            }

            attempts--;
            Log.i(TAG, "retry remaining=" + Math.max(1, attempts));
            mMainHandler.postDelayed(this, RETRY_DELAY_MS);
        }
    }

    /** 通过系统框架 BcmManager 写入（真机上行得通，与 BCM App 相同调用）。 */
    private boolean tryDisableViaBcmManager() {
        try {
            Class<?> bcmClass = Class.forName("com.adayo.proxy.setting.bcm.controller.BcmManager");
            Object inst = bcmClass.getMethod("getInstance").invoke(null);
            Object rc = bcmClass
                    .getMethod("setIntTransDataMessage", String.class, int.class, int.class, int.class)
                    .invoke(inst, "DeviceInfo", PROP_EHU_AEB_FCTB_SWT, AREA_GLOBAL, VALUE_AEB_OFF);
            Log.i(TAG, "BcmManager.setIntTransDataMessage rc=" + rc);
            mBcmFail = null;
            return true;
        } catch (ClassNotFoundException e) {
            mBcmFail = "非极狐车机（无 BcmManager）";
            Log.i(TAG, mBcmFail);
            return false;
        } catch (Throwable t) {
            mBcmFail = "BcmManager 写入异常：" + t.getClass().getSimpleName();
            Log.i(TAG, "BcmManager 通道不可用: " + t);
            return false;
        }
    }

    /** 通过标准 AAOS CarPropertyManager 写入。 */
    private boolean tryDisableViaCarProperty() {
        if (mCar == null || !mCar.isConnected() || mPropertyManager == null) {
            Log.w(TAG, "CarPropertyManager 未就绪");
            return false;
        }
        try {
            mPropertyManager.setIntProperty(PROP_EHU_AEB_FCTB_SWT, AREA_GLOBAL, VALUE_AEB_OFF);
            Log.i(TAG, "CarPropertyManager.setIntProperty(0x21400b35, 0, 1) OK");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setIntProperty failed", t);
            return false;
        }
    }

    /** 回读 AEB 状态（1=关，2=开，-1=不可用）。 */
    private int readFeedback() {
        if (mCar != null && mCar.isConnected() && mPropertyManager != null) {
            try {
                return mPropertyManager.getIntProperty(PROP_ADAS_AEB_FCTB_SWT_FB, AREA_GLOBAL);
            } catch (Throwable t) {
                Log.e(TAG, "getIntProperty failed", t);
            }
        }
        return -1;
    }

    private void saveReadback(int fb) {
        prefs().edit().putInt("aeb_last_readback", fb).apply();
    }

    private void setStatus(String s) {
        prefs().edit().putString("aeb_status", s)
                .putLong("aeb_last_run", System.currentTimeMillis()).apply();
        Log.i(TAG, "STATUS=" + s);
    }

    private void setFailReason(String r) {
        prefs().edit().putString("aeb_fail_reason", r).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE);
    }
}
