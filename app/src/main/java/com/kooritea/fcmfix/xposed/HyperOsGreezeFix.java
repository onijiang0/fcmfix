package com.kooritea.fcmfix.xposed;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * HyperOS / 澎湃 HyperGreeze 链路修复。
 *
 * 参考 HyperFCMLive（io.github.howard20181.hyperos.fcmlive）思路：
 * 系统侧 Greeze/Policy 把 GMS 推送链路限网、defer 广播、强杀进程时放行。
 * 与 fcmfix 原有 AutoStartFix/PowerkeeperFix 互补，覆盖 HyperOS 4 新增类。
 */
public class HyperOsGreezeFix extends XposedModule {

    private static final String GMS = "com.google.android.gms";
    private static final String GMS_PERSISTENT = "com.google.android.gms.persistent";
    private static final String C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";

    public HyperOsGreezeFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHooks();
        } catch (Throwable e) {
            printLog("HyperOsGreezeFix hook error: " + e.getMessage());
        }
    }

    private void startHooks() {
        // system_server 侧
        hookGreezeManagerService();
        hookDomesticPolicyManager();
        hookInternationalPolicyManager();
        hookListAppsManager();
        hookAwareResourceControl();
        hookProcessPolicy();
        hookProcessCleanerBase();
        hookAmsPowerExemption();
    }

    private static boolean isGmsPackage(String pkg) {
        return GMS.equals(pkg) || GMS_PERSISTENT.equals(pkg) || (pkg != null && pkg.startsWith(GMS + "."));
    }

    private boolean shouldProtect(String pkg, Intent intent) {
        if (pkg == null) {
            return false;
        }
        if (isGmsPackage(pkg)) {
            return true;
        }
        if (intent != null && isFCMIntent(intent) && targetIsAllow(pkg)) {
            return true;
        }
        return false;
    }

    private static boolean isFcmRelatedIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE")
                || action.contains("GCM_RECONNECT")
                || action.contains("gcm.CONNECTED")
                || action.contains("gcm.DISCONNECTED")
                || action.contains("HEARTBEAT_ALARM"));
    }

    private static void hookMethodQuiet(Class<?> clazz, String name, XC_MethodHook hook) {
        if (clazz == null) {
            return;
        }
        Method m = XposedUtils.tryFindMethodMostParam(clazz, name);
        if (m == null) {
            printLog("HyperGreeze 未找到 " + clazz.getName() + "#" + name);
            return;
        }
        try {
            com.kooritea.fcmfix.libxposed.XposedBridge.deoptimize(m);
        } catch (Throwable ignored) {
        }
        com.kooritea.fcmfix.libxposed.XposedBridge.hookMethod(m, hook);
        printLog("HyperGreeze 已挂接 " + clazz.getSimpleName() + "#" + name);
    }

    // ---------- GreezeManagerService ----------
    private void hookGreezeManagerService() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.miui.server.greeze.GreezeManagerService", classLoader);
        if (clazz == null) {
            return;
        }
        // isAllowBroadcast: FCM / GMS 放行
        hookMethodQuiet(clazz, "isAllowBroadcast", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete || param.args == null) {
                    return;
                }
                for (Object arg : param.args) {
                    if (arg instanceof Intent) {
                        Intent intent = (Intent) arg;
                        if (isFcmRelatedIntent(intent)) {
                            printLog("Greeze isAllowBroadcast 放行: " + intent.getAction(), true);
                            param.setResult(true);
                            return;
                        }
                        String pkg = intent.getComponent() != null
                                ? intent.getComponent().getPackageName() : intent.getPackage();
                        if (shouldProtect(pkg, intent)) {
                            printLog("Greeze isAllowBroadcast 放行: " + pkg, true);
                            param.setResult(true);
                            return;
                        }
                    } else if (arg instanceof String && isGmsPackage((String) arg)) {
                        param.setResult(true);
                        return;
                    }
                }
            }
        });
        // deferBroadcastForMiui: 不 defer FCM/GMS
        hookMethodQuiet(clazz, "deferBroadcastForMiui", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete || param.args == null) {
                    return;
                }
                for (Object arg : param.args) {
                    if (arg instanceof Intent && isFcmRelatedIntent((Intent) arg)) {
                        printLog("Greeze deferBroadcastForMiui 跳过 FCM", true);
                        param.setResult(null);
                        return;
                    }
                    if (arg instanceof String && isGmsPackage((String) arg)) {
                        param.setResult(null);
                        return;
                    }
                }
            }
        });
        // triggerGMSLimitAction: 对 GMS 直接跳过
        hookMethodQuiet(clazz, "triggerGMSLimitAction", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) {
                    return;
                }
                if (param.args != null) {
                    for (Object arg : param.args) {
                        if (arg instanceof String && (isGmsPackage((String) arg) || GMS.equals(arg))) {
                            printLog("Greeze triggerGMSLimitAction 跳过", true);
                            param.setResult(null);
                            return;
                        }
                    }
                }
                printLog("Greeze triggerGMSLimitAction 跳过（默认保护 GMS）", true);
                param.setResult(null);
            }
        });
    }

    // ---------- DomesticPolicyManager ----------
    private void hookDomesticPolicyManager() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.miui.server.greeze.DomesticPolicyManager", classLoader);
        if (clazz == null) {
            return;
        }
        hookMethodQuiet(clazz, "deferBroadcast", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete || param.args == null) {
                    return;
                }
                for (Object arg : param.args) {
                    if (arg instanceof Intent) {
                        Intent intent = (Intent) arg;
                        if (isFcmRelatedIntent(intent) || shouldProtect(
                                intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage(),
                                intent)) {
                            printLog("DomesticPolicy deferBroadcast 跳过", true);
                            param.setResult(null);
                            return;
                        }
                    }
                }
            }
        });
    }

    // ---------- InternationalPolicyManager ----------
    private void hookInternationalPolicyManager() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.miui.server.greeze.InternationalPolicyManager", classLoader);
        if (clazz == null) {
            return;
        }
        hookMethodQuiet(clazz, "isPushApp", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete || param.args == null) {
                    return;
                }
                for (Object arg : param.args) {
                    if (arg instanceof String && isGmsPackage((String) arg)) {
                        printLog("InternationalPolicy isPushApp GMS=true", true);
                        param.setResult(true);
                        return;
                    }
                }
            }
        });
    }

    // ---------- ListAppsManager ----------
    private void hookListAppsManager() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.miui.server.greeze.power.ListAppsManager", classLoader);
        if (clazz == null) {
            return;
        }
        hookMethodQuiet(clazz, "isInWhiteList", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete || param.args == null) {
                    return;
                }
                for (Object arg : param.args) {
                    if (arg instanceof String && isGmsPackage((String) arg)) {
                        param.setResult(true);
                        return;
                    }
                }
            }
        });
        // 构造后把 GMS 从黑名单挪到数据白名单
        hookConstructorAfter(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    patchStringListField(param.thisObject, clazz,
                            new String[]{"mSystemBlackList", "SYSTEM_BLACK_LIST"},
                            GMS, true);
                    patchStringListField(param.thisObject, clazz,
                            new String[]{"mUseDataWhiteList", "USE_DATA_WHITE_LIST"},
                            GMS, false);
                } catch (Throwable e) {
                    printLog("ListAppsManager patch 失败: " + e.getMessage());
                }
            }
        });
    }

    private static void hookConstructorAfter(Class<?> clazz, XC_MethodHook hook) {
        java.lang.reflect.Constructor<?>[] ctors = clazz.getDeclaredConstructors();
        if (ctors == null || ctors.length == 0) {
            printLog("HyperGreeze 无构造: " + clazz.getName());
            return;
        }
        java.lang.reflect.Constructor<?> best = ctors[0];
        for (java.lang.reflect.Constructor<?> c : ctors) {
            if (c.getParameterTypes().length >= best.getParameterTypes().length) {
                best = c;
            }
        }
        try {
            com.kooritea.fcmfix.libxposed.XposedBridge.hookMethod(best, hook);
            printLog("HyperGreeze 已挂接构造 " + clazz.getName());
        } catch (Throwable e) {
            printLog("HyperGreeze 构造挂接失败 " + clazz.getName() + ": " + e.getMessage());
        }
    }

    private static void patchStringListField(Object instance, Class<?> clazz, String[] fieldNames, String value, boolean remove) {
        for (String name : fieldNames) {
            try {
                Field f = findField(clazz, name);
                if (f == null) {
                    continue;
                }
                f.setAccessible(true);
                Object raw = f.get(instance);
                if (raw instanceof Collection) {
                    @SuppressWarnings("unchecked")
                    Collection<String> list = (Collection<String>) raw;
                    if (remove) {
                        list.remove(value);
                    } else if (!list.contains(value)) {
                        list.add(value);
                    }
                    printLog("HyperGreeze " + clazz.getSimpleName() + "#" + name + (remove ? " remove " : " add ") + value);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
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

    // ---------- AwareResourceControl ----------
    private void hookAwareResourceControl() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.miui.server.greeze.power.AwareResourceControl", classLoader);
        if (clazz == null) {
            return;
        }
        // 构造后：从断网黑名单移除 GMS，关闭 GMS 限制开关
        hookConstructorAfter(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    patchStringListField(param.thisObject, clazz,
                            new String[]{"mNoNetworkBlackUids"}, GMS, true);
                    for (String fname : new String[]{"mGmsLimitEnabled", "isRestrictNet"}) {
                        Field f = findField(clazz, fname);
                        if (f != null && f.getType() == boolean.class) {
                            f.setAccessible(true);
                            f.setBoolean(param.thisObject, false);
                            printLog("HyperGreeze " + clazz.getSimpleName() + "#" + fname + " = false");
                        }
                    }
                } catch (Throwable e) {
                    printLog("AwareResourceControl patch 失败: " + e.getMessage());
                }
            }
        });
    }

    // ---------- ProcessPolicy ----------
    private void hookProcessPolicy() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.android.server.am.ProcessPolicy", classLoader);
        if (clazz == null) {
            return;
        }
        hookMethodQuiet(clazz, "getWhiteList", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isBootComplete || param.getResult() == null) {
                    return;
                }
                Object result = param.getResult();
                if (result instanceof Collection) {
                    @SuppressWarnings("unchecked")
                    Collection<Object> list = (Collection<Object>) result;
                    if (!list.contains(GMS)) {
                        try {
                            list.add(GMS);
                            printLog("ProcessPolicy getWhiteList +gms", true);
                        } catch (Throwable ignored) {
                        }
                    }
                } else if (result instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) result;
                    if (!list.contains(GMS)) {
                        list.add(GMS);
                    }
                }
            }
        });
    }

    // ---------- ProcessCleanerBase ----------
    private void hookProcessCleanerBase() {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.android.server.am.ProcessCleanerBase", classLoader);
        if (clazz == null) {
            return;
        }
        hookMethodQuiet(clazz, "isForceStopEnable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete || param.args == null) {
                    return;
                }
                for (Object arg : param.args) {
                    if (arg instanceof String && isGmsPackage((String) arg)) {
                        printLog("ProcessCleaner isForceStopEnable GMS=false", true);
                        param.setResult(false);
                        return;
                    }
                }
            }
        });
    }

    // ---------- AMS: C2DM 时把目标加入临时功耗白名单 ----------
    private void hookAmsPowerExemption() {
        Class<?> ams = XposedHelpers.findClassIfExists("com.android.server.am.ActivityManagerService", classLoader);
        if (ams == null) {
            return;
        }
        for (String name : new String[]{"broadcastIntentWithFeature", "broadcastIntent"}) {
            Method m = XposedUtils.tryFindMethodMostParam(ams, name);
            if (m == null) {
                continue;
            }
            int intentIdx = -1;
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] == Intent.class) {
                    intentIdx = i;
                    break;
                }
            }
            if (intentIdx < 0) {
                continue;
            }
            final int idx = intentIdx;
            try {
                com.kooritea.fcmfix.libxposed.XposedBridge.deoptimize(m);
            } catch (Throwable ignored) {
            }
            com.kooritea.fcmfix.libxposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isBootComplete || param.args == null || param.args[idx] == null) {
                        return;
                    }
                    Intent intent = (Intent) param.args[idx];
                    if (!C2DM_RECEIVE.equals(intent.getAction()) && !isFCMIntent(intent)) {
                        return;
                    }
                    try {
                        String target = intent.getComponent() != null
                                ? intent.getComponent().getPackageName() : intent.getPackage();
                        if (target == null) {
                            return;
                        }
                        if (!isGmsPackage(target) && !targetIsAllow(target)) {
                            return;
                        }
                        Context ctx = context;
                        if (ctx == null) {
                            return;
                        }
                        Class<?> pemCls = XposedHelpers.findClassIfExists("android.os.PowerExemptionManager", classLoader);
                        if (pemCls == null || Build.VERSION.SDK_INT < 31) {
                            return;
                        }
                        Object pem = XposedHelpers.callMethod(ctx, "getSystemService", "power_exemption");
                        if (pem == null) {
                            pem = XposedHelpers.callStaticMethod(pemCls, "getInstance", ctx);
                        }
                        if (pem == null) {
                            // 部分版本用构造
                            try {
                                java.lang.reflect.Constructor<?> ctor = pemCls.getConstructor(Context.class);
                                pem = ctor.newInstance(ctx);
                            } catch (Throwable ignored) {
                                return;
                            }
                        }
                        // addToTemporaryAllowList(pkg, reason, tag, duration)
                        int reason = 999; // TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE 之外的系统 reason，反射兜底
                        try {
                            Field f = pemCls.getField("REASON_GMS");
                            reason = f.getInt(null);
                        } catch (Throwable ignored) {
                        }
                        try {
                            XposedHelpers.callMethod(pem, "addToTemporaryAllowList",
                                    target, reason, "FCMFix", 5 * 60 * 1000L);
                            printLog("PowerExemption 临时白名单: " + target, true);
                        } catch (Throwable e1) {
                            try {
                                // 更老签名：addToAllowlist(uid, duration, type, reason, tag)
                                PackageManager pm = ctx.getPackageManager();
                                List<ResolveInfo> ris = pm.queryBroadcastReceivers(
                                        new Intent(C2DM_RECEIVE).setPackage(target), 0);
                                printLog("PowerExemption 回退查询 " + target + " receivers=" + (ris == null ? 0 : ris.size()));
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable e) {
                        printLog("PowerExemption hook error: " + e.getMessage());
                    }
                }
            });
            printLog("HyperGreeze 已挂接 AMS#" + name + " (PowerExemption)");
        }
    }

    // powerkeeper 进程
    public void hookPowerkeeper(ClassLoader powerkeeperClassLoader) {
        try {
            Class<?> gmsObserver = XposedHelpers.findClassIfExists(
                    "com.miui.powerkeeper.utils.GmsObserver", powerkeeperClassLoader);
            if (gmsObserver != null) {
                for (String n : new String[]{"initGmsChain", "updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock"}) {
                    hookMethodQuietOn(powerkeeperClassLoader, gmsObserver, n);
                }
            }
            Class<?> netd = XposedHelpers.findClassIfExists(
                    "com.miui.powerkeeper.utils.NetdExecutor", powerkeeperClassLoader);
            if (netd != null) {
                hookMethodQuietOn(powerkeeperClassLoader, netd, "initGmsChain");
            }
            Class<?> doze = XposedHelpers.findClassIfExists(
                    "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper", powerkeeperClassLoader);
            if (doze != null) {
                hookMethodQuietOn(powerkeeperClassLoader, doze, "getDozeWhiteListApps");
            }
        } catch (Throwable e) {
            printLog("HyperOsGreezeFix powerkeeper error: " + e.getMessage());
        }
    }

    private void hookMethodQuietOn(ClassLoader cl, Class<?> clazz, String name) {
        Method m = XposedUtils.tryFindMethodMostParam(clazz, name);
        if (m == null) {
            return;
        }
        try {
            com.kooritea.fcmfix.libxposed.XposedBridge.deoptimize(m);
        } catch (Throwable ignored) {
        }
        // GMS 限流相关：直接 no-op，避免 HyperOS 掐 GMS 心跳/网络/wakelock
        com.kooritea.fcmfix.libxposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) {
                    return;
                }
                // getDozeWhiteListApps 需要返回含 GMS 的列表，不能 no-op
                if ("getDozeWhiteListApps".equals(name)) {
                    return;
                }
                printLog("HyperGreeze powerkeeper no-op: " + clazz.getSimpleName() + "#" + name, true);
                param.setResult(null);
            }
        });
        printLog("HyperGreeze 已挂接 powerkeeper " + clazz.getSimpleName() + "#" + name);
    }
}
