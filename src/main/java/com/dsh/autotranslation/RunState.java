package com.dsh.autotranslation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Live progress + per-namespace results, read by the in-game status screen. */
public final class RunState {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public static volatile boolean running;
    public static volatile String current = "";
    public static volatile int done;
    public static volatile int total;
    /** Epoch millis of the last successful run, restored from run-state.json. */
    public static volatile long lastRunAt;

    public static final Map<String, String> SUMMARIES = new ConcurrentHashMap<>();

    private RunState() {
    }

    public static void reset() {
        running = true;
        current = "";
        done = 0;
        total = 0;
        lastRunAt = 0L;
        SUMMARIES.clear();
    }

    /** Restores the stored per-namespace summary of the previous successful run. */
    public static void restore(Map<String, String> namespaces, long at) {
        running = false;
        current = "";
        done = 0;
        total = 0;
        lastRunAt = at;
        SUMMARIES.clear();
        if (namespaces != null) {
            SUMMARIES.putAll(namespaces);
        }
    }

    public static void summary(String id, int official, int cfpa, int memory, int ai, int missing, boolean skipped) {
        if (skipped) {
            SUMMARIES.put(id, "已完整（官方 " + official + "）");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(ai + cfpa).append(" 条");
        if (official > 0) {
            text.append(" · 补缺 ").append(cfpa + ai).append("/").append(official + cfpa + ai + missing);
        }
        text.append(" · CFPA ").append(cfpa);
        if (memory > 0) {
            text.append(" · 记忆 ").append(memory);
        }
        text.append(" · AI ").append(ai);
        if (missing > 0) {
            text.append(" · 缺 ").append(missing);
        }
        SUMMARIES.put(id, text.toString());
    }

    public static String text() {
        if (!running) {
            if (lastRunAt <= 0) {
                return "空闲（尚无运行记录）";
            }
            return "休眠 · 上次运行 " + TIME.format(Instant.ofEpochMilli(lastRunAt).atZone(ZoneId.systemDefault()))
                    + " · " + SUMMARIES.size() + " 个模组";
        }
        return "正在处理 " + (current.isEmpty() ? "…" : current) + "（" + done + "/" + total + "）";
    }
}
