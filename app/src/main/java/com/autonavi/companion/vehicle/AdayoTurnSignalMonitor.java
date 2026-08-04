package com.autonavi.companion.vehicle;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极狐转向信号监控器 (Adayo Turn Signal Monitor)
 * <p>
 * 通过 logcat 读取车辆 CAN 总线日志（commservice tag），
 * 用正则匹配 lamp_lturn / lamp_rturn 状态值，
 * 综合判断转向方向（左转/右转/双闪/关闭）。
 * <p>
 * 用法：
 *   AdayoTurnSignalMonitor.start(context);
 *   AdayoTurnSignalMonitor.addListener(listener);
 *   ...
 *   AdayoTurnSignalMonitor.removeListener(listener);
 *   AdayoTurnSignalMonitor.stop();
 * <p>
 * 移植自 arcfox-turn-hud（Navi-Link 逆向还原版），集成到 AMap Max 时的改动：
 *   1. package 由 com.navi.link.vehicle 改为 com.autonavi.companion.vehicle
 *   2. persist() 由 commit() 改为 apply()，避免转向灯高频闪烁时在后台线程同步写盘
 *   3. 新增 isRunning()，供 Service 判断监控线程状态，避免重复 start
 */
public final class AdayoTurnSignalMonitor {

    private static final String TAG = "AmapTurnSignal";
    private static final String DIAGNOSTIC_PREFS = "turn_signal_diagnostics";
    private static final int MAX_HISTORY = 32;
    private static final int MAX_RAW_SAMPLES = 24;

    /** logcat 匹配正则: {"sub":"lamp_X"..."status":数字} */
    private static final Pattern TURN_LAMP =
            Pattern.compile("\"sub\":\"lamp_(lturn|rturn)\".*?\"status\":(-?\\d+)");

    /** 宽松采样：只要行里出现 lamp / turn_signal / lturn / rturn 就记录原始行（无论能否解析） */
    private static final Pattern SAMPLE_HINT =
            Pattern.compile("(?i)lamp_?(lturn|rturn|left|right)|turn[_ ]?signal");

    /** 候选 commservice tag（不同固件可能不同大小写/下划线变体） */
    private static final String[] COMMSERVICE_TAGS = {
            "commservice", "comm_service", "CommService", "COMMService",
            "commservice:D", "com.arcsoft.commservice", "AmapTurnSignal"
    };

    /** 状态快照 */
    private static final AtomicReference<Snapshot> latest =
            new AtomicReference<>(Snapshot.unavailable());
    /** 监听器列表（线程安全） */
    private static final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();

    private static volatile Context appContext;
    private static volatile boolean running;
    private static volatile Thread worker;
    private static volatile Process logcatProcess;

    private static volatile int leftRaw, rightRaw;
    private static volatile boolean leftKnown, rightKnown;
    private static volatile boolean leftDirty, rightDirty;
    private static volatile boolean replayingHistory;

    /** 原始日志采样环形缓冲（诊断用：实车格式不匹配时回传定位） */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> rawSamples =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private AdayoTurnSignalMonitor() {}

    // ================================================================
    // 公共 API
    // ================================================================

    /** 启动转向信号监控（创建后台线程读取 logcat） */
    public static synchronized void start(Context context) {
        if (context == null || running) return;
        appContext = context.getApplicationContext();
        running = true;
        replayingHistory = true;
        leftRaw = rightRaw = 0;
        leftKnown = rightKnown = false;
        leftDirty = rightDirty = false;
        latest.set(Snapshot.unavailable());
        worker = new Thread(AdayoTurnSignalMonitor::monitorLoop, "amap-turn-signal");
        worker.start();
    }

    /** 停止转向信号监控 */
    public static synchronized void stop() {
        running = false;
        replayingHistory = false;
        Process p = logcatProcess;
        logcatProcess = null;
        if (p != null) p.destroy();
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        appContext = null;
    }

    /** 监控线程是否在运行 */
    public static boolean isRunning() {
        return running;
    }

    /** 获取最新快照 */
    public static Snapshot snapshot() {
        return latest.get();
    }

    /**
     * 外部注入快照（用于模拟器自测或车机广播通道）。
     * 注入会覆盖 logcat 读取到的最新状态，并通知所有监听器。
     */
    public static synchronized void inject(Direction direction, int leftRaw, int rightRaw) {
        Snapshot value = new Snapshot(true, direction, leftRaw, rightRaw, SystemClock.elapsedRealtime());
        latest.set(value);
        leftKnown = rightKnown = true;
        leftDirty = rightDirty = false;
        persist(value);
        Log.d(TAG, "Turn signal injected=" + direction
                + " leftRaw=" + leftRaw + " rightRaw=" + rightRaw);
        for (Listener listener : listeners) {
            try { listener.onTurnSignalChanged(value); } catch (Throwable t) {
                Log.w(TAG, "listener failed on inject", t);
            }
        }
    }

    /** 注册监听器 */
    public static void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    /** 移除监听器 */
    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ================================================================
    // 内部逻辑
    // ================================================================

    /** 主监控循环：持续运行 logcat 并读取输出 */
    private static void monitorLoop() {
        replayLogBuffer(); // 先回放历史缓冲区
        while (running) {
            Process process = null;
            try {
                // 同时监听 commservice 多个 tag 变体与自身 TAG，方便在 logcat 中观察 monitor 是否活着。
                // *:S 放在最后：把所有非显式指定的 tag 设为 silent。
                // 【实车兼容】不同固件 commservice 的 tag 大小写/命名有差异，全部列入过滤。
                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add("logcat");
                cmd.add("-b");
                cmd.add("main");
                cmd.add("-v");
                cmd.add("raw");
                cmd.add("-T");
                cmd.add("1");
                for (String t : COMMSERVICE_TAGS) {
                    cmd.add(t + ":D");
                }
                cmd.add("*:S");
                process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                logcatProcess = process;
                readProcess(process);
            } catch (Throwable error) {
                if (running) {
                    Log.w(TAG, "Turn signal feed unavailable: " + error.getClass().getSimpleName());
                }
            } finally {
                if (process != null) process.destroy();
                if (logcatProcess == process) logcatProcess = null;
            }
            if (!running) break;
            try { Thread.sleep(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 回放 logcat 历史缓冲区 */
    private static void replayLogBuffer() {
        Process process = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("logcat");
            cmd.add("-b");
            cmd.add("main");
            cmd.add("-d");
            cmd.add("-v");
            cmd.add("raw");
            for (String t : COMMSERVICE_TAGS) {
                cmd.add(t + ":D");
            }
            cmd.add("*:S");
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            readProcess(process);
            process.waitFor();
        } catch (Throwable error) {
            if (running) {
                Log.w(TAG, "Turn signal history unavailable: " + error.getClass().getSimpleName());
            }
        } finally {
            if (process != null) process.destroy();
        }
        replayingHistory = false;
        leftDirty = rightDirty = false;
        publishIfReady("history");
    }

    /** 读取 Process 输出流，逐行消费 */
    private static void readProcess(Process process) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                consumeLine(line);
            }
        }
    }

    /** 解析并消费单行 logcat 输出 */
    private static void consumeLine(String line) {
        if (line == null || line.isEmpty()) return;
        // 【诊断采样】含 lamp/turn 关键词的行全部保留原始内容（无论能否解析），
        // 实车固件格式不匹配时，可回传 diagnostics 精确定位。
        if (SAMPLE_HINT.matcher(line).find()) {
            rawSamples.add(line);
            while (rawSamples.size() > MAX_RAW_SAMPLES) {
                rawSamples.poll();
            }
        }
        Event event = parseEvent(line);
        if (event == null) return;
        if (event.left) {
            leftRaw = event.rawStatus;
            leftKnown = true;
            leftDirty = true;
        } else {
            rightRaw = event.rawStatus;
            rightKnown = true;
            rightDirty = true;
        }
        if (!replayingHistory && leftDirty && rightDirty) {
            leftDirty = rightDirty = false;
            publishIfReady("commservice");
        }
    }

    /** 从日志行解析转向事件 */
    static Event parseEvent(String line) {
        Matcher matcher = TURN_LAMP.matcher(line != null ? line : "");
        if (!matcher.find()) return null;
        try {
            boolean left = "lturn".equals(matcher.group(1));
            int status = Integer.parseInt(matcher.group(2));
            return new Event(left, status);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 根据左右灯状态判断方向 */
    static Direction directionFor(int leftStatus, int rightStatus) {
        boolean left = leftStatus != 0;
        boolean right = rightStatus != 0;
        if (left && right) return Direction.HAZARD;
        if (left) return Direction.LEFT;
        if (right) return Direction.RIGHT;
        return Direction.OFF;
    }

    /** 发布状态变更（防抖：值不变不发） */
    private static void publishIfReady(String source) {
        if (!leftKnown || !rightKnown) return;
        Direction direction = directionFor(leftRaw, rightRaw);
        Snapshot before = latest.get();
        if (before.available
                && before.leftRaw == leftRaw
                && before.rightRaw == rightRaw) return;

        Snapshot value = new Snapshot(true, direction, leftRaw, rightRaw,
                SystemClock.elapsedRealtime());
        latest.set(value);
        persist(value);
        Log.d(TAG, "Turn signal=" + direction
                + " leftRaw=" + leftRaw + " rightRaw=" + rightRaw
                + " source=" + source);
        for (Listener listener : listeners) {
            try { listener.onTurnSignalChanged(value); } catch (Throwable t) {
                Log.w(TAG, "listener failed on publish", t);
            }
        }
    }

    /** 持久化诊断历史 */
    private static void persist(Snapshot value) {
        Context context = appContext;
        if (context == null || value == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(DIAGNOSTIC_PREFS, 0);
            JSONArray previous = new JSONArray(prefs.getString("history", "[]"));
            JSONArray history = new JSONArray();
            int start = Math.max(0, previous.length() - MAX_HISTORY + 1);
            for (int i = start; i < previous.length(); i++) {
                JSONObject item = previous.optJSONObject(i);
                if (item != null) history.put(item);
            }
            long now = System.currentTimeMillis();
            JSONObject item = new JSONObject();
            item.put("time", now);
            item.put("direction", value.direction.name());
            item.put("left", value.leftRaw);
            item.put("right", value.rightRaw);
            history.put(item);
            // 【诊断采样】把最近原始日志行一并落盘，实车格式不匹配时回传定位。
            // 采样只在有变化时落盘，避免高频闪烁时每次都写全量。
            JSONArray samples = new JSONArray();
            for (String s : rawSamples) {
                samples.put(s);
            }
            prefs.edit()
                    .putString("direction", value.direction.name())
                    .putInt("left_raw", value.leftRaw)
                    .putInt("right_raw", value.rightRaw)
                    .putLong("updated_at", now)
                    .putLong("transition_count", prefs.getLong("transition_count", 0) + 1)
                    .putString("history", history.toString())
                    .putString("raw_samples", samples.toString())
                    .putLong("sample_count", rawSamples.size())
                    .apply();
        } catch (Throwable error) {
            Log.w(TAG, "Turn signal history save failed: " + error.getClass().getSimpleName());
        }
    }

    // ================================================================
    // 内部类型
    // ================================================================

    /** 转向方向枚举 */
    public enum Direction { OFF, LEFT, RIGHT, HAZARD }

    /** 单次解析事件 */
    static class Event {
        final boolean left;
        final int rawStatus;
        Event(boolean left, int rawStatus) { this.left = left; this.rawStatus = rawStatus; }
    }

    /** 转向状态快照 */
    public static class Snapshot {
        public final boolean available;
        public final Direction direction;
        public final int leftRaw, rightRaw;
        public final long updatedElapsedMs;

        Snapshot(boolean available, Direction direction, int leftRaw, int rightRaw, long elapsedMs) {
            this.available = available;
            this.direction = direction;
            this.leftRaw = leftRaw;
            this.rightRaw = rightRaw;
            this.updatedElapsedMs = elapsedMs;
        }

        static Snapshot unavailable() {
            return new Snapshot(false, Direction.OFF, 0, 0, 0);
        }
    }

    /** 转向状态变更监听器 */
    public interface Listener {
        void onTurnSignalChanged(Snapshot snapshot);
    }
}
