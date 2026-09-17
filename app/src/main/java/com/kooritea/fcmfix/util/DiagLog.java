package com.kooritea.fcmfix.util;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断版专用：把关键日志追加写入固定路径，便于 adb / agent 读取。
 * 正式版 BuildConfig.DIAG=false，全部 no-op。
 *
 * 可读路径（诊断版）：
 *   adb shell cat /data/local/tmp/fcmfix-diag.log
 */
public final class DiagLog {

    private static final String[] PATHS = {
            "/data/local/tmp/fcmfix-diag.log",
            "/sdcard/fcmfix-diag.log",
            "/sdcard/Download/fcmfix-diag.log",
    };
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final Object LOCK = new Object();

    private DiagLog() {
    }

    /** 仅诊断版写入；正式版直接返回。 */
    public static void write(String tag, String line) {
        try {
            if (!com.kooritea.fcmfix.BuildConfig.DIAG) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        String ts;
        synchronized (TS) {
            ts = TS.format(new Date());
        }
        String out = ts + " [" + tag + "] " + line + "\n";
        synchronized (LOCK) {
            for (String path : PATHS) {
                try {
                    File f = new File(path);
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    if (f.exists() && f.length() > MAX_BYTES) {
                        File bak = new File(path + ".1");
                        //noinspection ResultOfMethodCallIgnored
                        f.renameTo(bak);
                    }
                    try (FileWriter fw = new FileWriter(f, true)) {
                        fw.write(out);
                    }
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
