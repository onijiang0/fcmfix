package com.kooritea.fcmfix.xposed;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GMS 长连接保活 + FCM 服务启动放行。
 *
 * 断连常见原因：HyperOS 冻结 GMS、取消/延迟心跳闹钟、断 GMS 后台网络、
 * 应用 standby 桶降级。这里在 system_server 侧把这些路径钉住。
 *
 * 推特等走 FirebaseMessagingService 的应用：GMS 是 startService/bindService
 * 唤起，不是简单广播，需要在服务启动时也给临时功耗白名单。
 */
public class GmsKeepAliveFix extends XposedModule {

    private static final String GMS = "com.google.android.gms";
    private static final String GMS_PERSISTENT = "com.google.android.gms.persistent";

    public GmsKeepAliveFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHooks();
        } catch (Throwable e) {
            printLog("GmsKeepAliveFix error: " + e.getMessage());
        }
    }

    private void startHooks() {
        hookDeviceIdleWhitelist();
        hookAppStandby();
        hookAlarmManager();
        hookNetworkPolicy();
        hookAmsServiceStart();
        hookScreenOffProtection();
    }

    private boolean screenOffKeepAliveRunning = false;

    /**
     * 锁屏/灭屏后 HyperOS 会冻结 GMS、断后台网络、进 Doze。
     * 灭屏瞬间重新打功耗白名单，并周期续期。
     */
    private void hookScreenOffProtection() {
        // PowerManagerService.goToSleep / onWakefulnessChanged
        Class<?> pms = XposedHelpers.findClassIfExists("com.android.server.power.PowerManagerService", classLoader);
        if (pms != null) {
            for (String mn : new String[]{"goToSleep", "goToSleepNoUpdateLocked", "updateWakefulnessLocked", "setWakefulnessLocked"}) {
                Method m = XposedUtils.tryFindMethodMostParam(pms, mn);
                if (m == null) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isBootComplete) {
                            return;
                        }
                        printLog("灭屏路径触发: " + mn + "，续期 GMS 白名单", true);
                        reassertGmsAlive("screen-off");
                        startScreenOffKeepAlive();
                    }
                });
                printLog("KeepAlive 已挂接 PowerManagerService#" + mn);
            }
        }

        // DeviceIdleController：深度 Doze 步进时再补白名单
        Class<?> dic = XposedHelpers.findClassIfExists("com.android.server.DeviceIdleController", classLoader);
        if (dic != null) {
            for (String mn : new String[]{"stepIdleStateLocked", "exitMaintenanceEarlyIfNeededLocked", "becomeActiveLocked"}) {
                Method m = XposedUtils.tryFindMethodMostParam(dic, mn);
                if (m == null) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isBootComplete) {
                            return;
                        }
                        printLog("DeviceIdle 步进: " + mn + "，续期 GMS", true);
                        reassertGmsAlive("doze-step");
                    }
                });
                printLog("KeepAlive 已挂接 DeviceIdleController#" + mn);
            }
        }

        // HyperOS 灭屏冻结：GreezeManagerService
        Class<?> gmsSvc = XposedHelpers.findClassIfExists("com.miui.server.greeze.GreezeManagerService", classLoader);
        if (gmsSvc != null) {
            for (String mn : new String[]{"freezePackages", "freezeApp", "freezeUid", "onScreenOff", "screenOff", "handleScreenOff", "checkFreeze"}) {
                Method m = XposedUtils.tryFindMethodMostParam(gmsSvc, mn);
                if (m == null) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBootComplete || param.args == null) {
                            return;
                        }
                        for (Object a : param.args) {
                            if (a instanceof String && isGms((String) a)) {
                                printLog("Greeze 灭屏冻结 GMS 已拦截: " + mn, true);
                                param.setResult(null);
                                return;
                            }
                            if (a instanceof Integer) {
                                // uid：GMS 常见 uid 范围太宽，只在参数含包名时拦
                            }
                        }
                    }
                });
                printLog("KeepAlive 已挂接 Greeze#" + mn);
            }
        }

        // 监听 ACTION_SCREEN_OFF，续期
        try {
            if (context != null) {
                android.content.IntentFilter f = new android.content.IntentFilter();
                f.addAction(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_SCREEN_ON);
                context.registerReceiver(new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (!isBootComplete) {
                            return;
                        }
                        if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                            printLog("SCREEN_OFF：立即续期 GMS 并启动保活", true);
                            reassertGmsAlive("SCREEN_OFF");
                            startScreenOffKeepAlive();
                        } else {
                            printLog("SCREEN_ON", true);
                            reassertGmsAlive("SCREEN_ON");
                        }
                    }
                }, f);
                printLog("KeepAlive 已注册 SCREEN_OFF 监听");
            }
        } catch (Throwable e) {
            printLog("SCREEN_OFF 注册失败: " + e.getMessage());
        }
    }

    private synchronized void startScreenOffKeepAlive() {
        if (screenOffKeepAliveRunning) {
            return;
        }
        screenOffKeepAliveRunning = true;
        Thread t = new Thread(() -> {
            try {
                // 灭屏后前 15 分钟每 90s 续期一次（最容易被掐的窗口）
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(90_000);
                    reassertGmsAlive("screen-off-tick-" + i);
                    // 若已亮屏则退出由 SCREEN_ON 再续一次即可
                    try {
                        if (context != null) {
                            Object pm = context.getSystemService(Context.POWER_SERVICE);
                            if (pm != null && Boolean.TRUE.equals(XposedHelpers.callMethod(pm, "isInteractive"))) {
                                printLog("已亮屏，停止灭屏保活循环", true);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                screenOffKeepAliveRunning = false;
            }
        }, "fcmfix-screen-off-keepalive");
        t.setDaemon(true);
        t.start();
    }

    /** 统一续期：功耗白名单 + 设备空闲白名单。 */
    private void reassertGmsAlive(String reason) {
        addToPowerAllowlist(GMS, 10 * 60 * 1000L);
        try {
            Class<?> dic = XposedHelpers.findClassIfExists("com.android.server.DeviceIdleController", classLoader);
            if (dic != null && context != null) {
                // 通过系统服务调用
                Object svc = context.getSystemService("deviceidle");
                if (svc != null) {
                    XposedHelpers.callMethod(svc, "addPowerSaveWhitelistApp", GMS);
                }
            }
        } catch (Throwable ignored) {
        }
        printLog("reassertGmsAlive[" + reason + "]", true);
    }

    private static boolean isGms(String pkg) {
        return GMS.equals(pkg) || GMS_PERSISTENT.equals(pkg) || (pkg != null && pkg.startsWith(GMS + "."));
    }

    private static void hookQuiet(Class<?> clazz, String name, XC_MethodHook hook) {
        if (clazz == null) {
            return;
        }
        Method m = XposedUtils.tryFindMethodMostParam(clazz, name);
        if (m == null) {
            printLog("KeepAlive 未找到 " + clazz.getName() + "#" + name);
            return;
        }
        try {
            XposedBridge.deoptimize(m);
        } catch (Throwable ignored) {
        }
        XposedBridge.hookMethod(m, hook);
        printLog("KeepAlive 已挂接 " + clazz.getSimpleName() + "#" + name);
    }

    private void addToPowerAllowlist(String pkg, long durationMs) {
        try {
            Context ctx = context;
            if (ctx == null || pkg == null || Build.VERSION.SDK_INT < 31) {
                return;
            }
            Class<?> pemCls = XposedHelpers.findClassIfExists("android.os.PowerExemptionManager", classLoader);
            if (pemCls == null) {
                return;
            }
            Object pem = null;
            try {
                pem = XposedHelpers.callMethod(ctx, "getSystemService", "power_exemption");
            } catch (Throwable ignored) {
            }
            if (pem == null) {
                try {
                    pem = XposedHelpers.callStaticMethod(pemCls, "getInstance", ctx);
                } catch (Throwable ignored) {
                }
            }
            if (pem == null) {
                try {
                    pem = pemCls.getConstructor(Context.class).newInstance(ctx);
                } catch (Throwable ignored) {
                    return;
                }
            }
            int reason = 200;
            for (String rn : new String[]{"REASON_GMS", "REASON_PUSH", "REASON_NOTIFICATION", "REASON_MAIN"}) {
                try {
                    reason = pemCls.getField(rn).getInt(null);
                    break;
                } catch (Throwable ignored) {
                }
            }
            try {
                XposedHelpers.callMethod(pem, "addToTemporaryAllowList", pkg, reason, "FCMFixKeepAlive", durationMs);
                printLog("KeepAlive 临时白名单: " + pkg + " " + durationMs + "ms", true);
            } catch (Throwable e1) {
                try {
                    // API 31+ 另一签名
                    XposedHelpers.callMethod(pem, "addToAllowlist", pkg);
                    printLog("KeepAlive 永久白名单: " + pkg, true);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            printLog("addToPowerAllowlist error: " + e.getMessage());
        }
    }

    /** DeviceIdleController：把 GMS 加入省电白名单，避免 Doze 掐连接。 */
    private void hookDeviceIdleWhitelist() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.android.server.DeviceIdleController", classLoader);
        if (clazz == null) {
            return;
        }
        hookQuiet(clazz, "addPowerSaveWhitelistApp", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // 正常调用后无需改参；这里在系统 ready 后主动塞 GMS
            }
        });
        hookQuiet(clazz, "onBootPhase", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object phase = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    // PHASE_BOOT_COMPLETED = 1000 / PHASE_ACTIVITY_MANAGER_READY = 550
                    if (phase instanceof Integer && (Integer) phase >= 550) {
                        Method add = XposedUtils.tryFindMethodMostParam(clazz, "addPowerSaveWhitelistApp");
                        if (add != null) {
                            add.invoke(param.thisObject, GMS);
                            printLog("DeviceIdle 白名单 +gms", true);
                        }
                        // 内部数组 mPowerSaveWhitelistUserApps / mPowerSaveWhitelistSystemApps
                        for (String fn : new String[]{"mPowerSaveWhitelistUserApps", "mPowerSaveWhitelistSystemApps"}) {
                            try {
                                Field f = findField(clazz, fn);
                                if (f != null) {
                                    f.setAccessible(true);
                                    Object raw = f.get(param.thisObject);
                                    if (raw instanceof android.util.ArraySet) {
                                        @SuppressWarnings("unchecked")
                                        android.util.ArraySet<String> set = (android.util.ArraySet<String>) raw;
                                        if (!set.contains(GMS)) {
                                            set.add(GMS);
                                            printLog("DeviceIdle " + fn + " +gms", true);
                                        }
                                    } else if (raw instanceof java.util.Set) {
                                        @SuppressWarnings("unchecked")
                                        java.util.Set<String> set = (java.util.Set<String>) raw;
                                        if (!set.contains(GMS)) {
                                            set.add(GMS);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable e) {
                    printLog("DeviceIdle whitelist error: " + e.getMessage());
                }
            }
        });
    }

    /** AppStandby：GMS 永远 ACTIVE，避免桶降级后心跳被限。 */
    private void hookAppStandby() {
        String[] names = {
                "com.android.server.app.AppStandbyController",
                "com.android.server.usage.AppStandbyController"
        };
        for (String cn : names) {
            Class<?> clazz = XposedHelpers.findClassIfExists(cn, classLoader);
            if (clazz == null) {
                continue;
            }
            hookQuiet(clazz, "getAppStandbyBucket", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = null;
                        if (param.args != null) {
                            for (Object a : param.args) {
                                if (a instanceof String) {
                                    pkg = (String) a;
                                    break;
                                }
                            }
                        }
                        if (isGms(pkg) && param.getResult() instanceof Integer) {
                            int STANDBY_BUCKET_ACTIVE = 5; // UsageStatsManager.STANDBY_BUCKET_ACTIVE
                            if ((Integer) param.getResult() != STANDBY_BUCKET_ACTIVE) {
                                param.setResult(STANDBY_BUCKET_ACTIVE);
                                printLog("AppStandby GMS -> ACTIVE", true);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            hookQuiet(clazz, "setAppStandbyBucket", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null) {
                            return;
                        }
                        for (Object a : param.args) {
                            if (a instanceof String && isGms((String) a)) {
                                printLog("AppStandby 拒绝降低 GMS 桶", true);
                                param.setResult(null);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            return;
        }
    }

    /** AlarmManager：GMS 心跳/重连闹钟强制 exact + allowWhileIdle。 */
    private void hookAlarmManager() {
        String[] names = {
                "com.android.server.alarm.AlarmManagerService",
                "com.android.server.AlarmManagerService"
        };
        for (String cn : names) {
            Class<?> clazz = XposedHelpers.findClassIfExists(cn, classLoader);
            if (clazz == null) {
                continue;
            }
            // set/setExact/setExactAndAllowWhileIdle/setAlarmClock 等
            for (String mn : new String[]{"set", "setExact", "setExactAndAllowWhileIdle", "setAlarmClock", "setWindow", "setImpl"}) {
                Method m = XposedUtils.tryFindMethodMostParam(clazz, mn);
                if (m == null) {
                    continue;
                }
                final int uidIdx = findUidIndex(m);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBootComplete || param.args == null) {
                            return;
                        }
                        boolean gms = false;
                        // 找 package 字符串或 PendingIntent 的 creator
                        for (Object a : param.args) {
                            if (a instanceof String && isGms((String) a)) {
                                gms = true;
                                break;
                            }
                        }
                        if (!gms && uidIdx >= 0 && uidIdx < param.args.length && param.args[uidIdx] instanceof Integer) {
                            // 无法直接判包名，跳过
                        }
                        if (!gms) {
                            // PendingIntent / AlarmManager.AlarmClockInfo 反射
                            for (Object a : param.args) {
                                if (a == null) {
                                    continue;
                                }
                                String cn2 = a.getClass().getName();
                                if (cn2.contains("PendingIntent") || cn2.contains("AlarmClockInfo")) {
                                    try {
                                        Object pi = a;
                                        if (cn2.contains("AlarmClockInfo")) {
                                            pi = XposedHelpers.callMethod(a, "getShowIntent");
                                        }
                                        if (pi != null) {
                                            Object creator = XposedHelpers.callMethod(pi, "getCreatorPackage");
                                            if (creator instanceof String && isGms((String) creator)) {
                                                gms = true;
                                                break;
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }
                        if (!gms) {
                            return;
                        }
                        // 不要乱改 flags：GMS 注册/checkin 闹钟被改坏会导致拿不到 FCM token
                        printLog("Alarm 捕获 GMS 闹钟: " + m.getName(), true);
                    }
                });
                printLog("KeepAlive 已挂接 AlarmManagerService#" + mn);
            }
            return;
        }
    }

    private static int findUidIndex(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (pts[i] == int.class) {
                // 多为 uid 或 flags，不强制
            }
        }
        return -1;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** NetworkPolicy：GMS 不断网。 */
    private void hookNetworkPolicy() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.android.server.net.NetworkPolicyManagerService", classLoader);
        if (clazz == null) {
            return;
        }
        hookQuiet(clazz, "isUidNetworkingBlocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    // 若结果为 true 且 uid 对应 GMS，改 false
                    if (!Boolean.TRUE.equals(param.getResult())) {
                        return;
                    }
                    int uid = -1;
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                        uid = (Integer) param.args[0];
                    }
                    if (uid < 0) {
                        return;
                    }
                    // GMS uid 通常是 10xxx，用 PackageManager 对照
                    Context ctx = context;
                    if (ctx == null) {
                        return;
                    }
                    String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                    if (pkgs != null) {
                        for (String p : pkgs) {
                            if (isGms(p)) {
                                param.setResult(false);
                                printLog("NetworkPolicy 放行 GMS uid=" + uid, true);
                                return;
                            }
                            if (p != null && allowList != null && allowList.contains(p)) {
                                // 勾选应用在 FCM 场景也尽量不断网（宽松）
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * AMS startService / bindService：
     * GMS 唤起目标应用 FirebaseMessagingService 时（推特等），给临时功耗白名单。
     */
    private void hookAmsServiceStart() {
        Class<?> ams = XposedHelpers.findClassIfExists("com.android.server.am.ActivityManagerService", classLoader);
        if (ams == null) {
            return;
        }
        for (String mn : new String[]{"startService", "startForegroundService", "bindService", "bindServiceInstance"}) {
            Method m = XposedUtils.tryFindMethodMostParam(ams, mn);
            if (m == null) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isBootComplete || param.args == null) {
                        return;
                    }
                    try {
                        Intent intent = null;
                        String pkg = null;
                        for (Object a : param.args) {
                            if (a instanceof Intent) {
                                intent = (Intent) a;
                                break;
                            }
                            if (a instanceof ComponentName) {
                                pkg = ((ComponentName) a).getPackageName();
                            }
                        }
                        if (intent != null) {
                            ComponentName cn = intent.getComponent();
                            if (cn != null) {
                                pkg = cn.getPackageName();
                            } else if (pkg == null) {
                                pkg = intent.getPackage();
                            }
                            String action = intent.getAction();
                            // FirebaseMessagingService / INSTANCE_ID / c2dm
                            boolean fcmService = action != null && (
                                    action.contains("firebase.MESSAGING_EVENT")
                                    || action.contains("firebase.INSTANCE_ID_EVENT")
                                    || action.contains("c2dm.intent.RECEIVE")
                                    || action.contains("MESSAGING_EVENT"));
                            String cls = cn != null ? cn.getClassName() : null;
                            boolean fcmClass = cls != null && (
                                    cls.contains("FirebaseMessaging")
                                    || cls.contains("InstanceId")
                                    || cls.contains("FCM")
                                    || cls.contains("C2dm"));
                            if (fcmService || fcmClass) {
                                if (pkg != null && (isGms(pkg) || (allowList != null && allowList.contains(pkg)))) {
                                    addToPowerAllowlist(pkg, 5 * 60 * 1000L);
                                    if (intent.getPackage() != null || cn != null) {
                                        // 确保 stopped 应用能被 startService
                                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                    }
                                    printLog("AMS " + mn + " FCM 服务放行: " + pkg, true);
                                }
                            }
                        }
                        if (pkg != null && isGms(pkg)) {
                            addToPowerAllowlist(GMS, 10 * 60 * 1000L);
                        }
                    } catch (Throwable e) {
                        printLog("AMS service hook error: " + e.getMessage());
                    }
                }
            });
            printLog("KeepAlive 已挂接 AMS#" + mn);
        }
    }
}
