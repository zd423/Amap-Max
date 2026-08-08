package android.car;

import android.content.Context;
import android.content.ServiceConnection;

/**
 * 编译期占位桩。真机上由 AAOS 系统框架的同名类替换（boot classpath 优先加载真实实现），
 * 与 BCM 原厂 APK 自带 android.car 桩的做法一致。非 AAOS 设备上这些桩不会生效。
 */
public final class Car {

    public static final String PROPERTY_SERVICE = "property";

    public static final int CONNECTION_TYPE_EMBEDDED = 5;

    private Car() {
    }

    public static Car createCar(Context context, ServiceConnection connection) {
        return null;
    }

    public static Car createCar(Context context, android.os.Handler handler, int connectionType,
            ServiceConnection connection) {
        return null;
    }

    public <T> T getCarManager(Class<T> clazz) {
        return null;
    }

    public boolean isConnected() {
        return false;
    }

    public void disconnect() {
    }
}
