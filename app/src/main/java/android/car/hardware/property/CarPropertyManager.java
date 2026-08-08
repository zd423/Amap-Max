package android.car.hardware.property;

/**
 * 编译期占位桩，运行时由 AAOS 系统框架的真实 CarPropertyManager 替换。
 */
public class CarPropertyManager {

    public int getIntProperty(int propertyId, int area) {
        return -1;
    }

    public void setIntProperty(int propertyId, int area, int value) {
    }
}
