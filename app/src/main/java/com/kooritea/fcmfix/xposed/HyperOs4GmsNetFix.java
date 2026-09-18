package com.kooritea.fcmfix.xposed;

import android.content.Context;
import android.os.Build;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.DiagLog;
import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * 澎湃 OS4（HyperOS）GMS 网络保活 —— 对照 HyperFCMLive，只动 GMS，不全局 no-op。
 *
 * 锁屏/Doze「断网」常见链路（HyperFCMLive 同源）：
 * 1. AwareResourceControl.mNoNetworkBlackUids / mGmsLimitEnabled / isRestrictNet
 * 2. ListAppsManager 黑名单 / 数据白名单
 * 3. NetworkPolicy 对 uid 断网
 * 4. powerkeeper Doze 白名单不含 GMS
 *
 * 与 HyperFCMLive 差异：不 no-op updateGmsNetWork/initGmsChain（那是正向建网）。
 */
public class HyperOs4GmsNetFix extends XposedModule {

    private static final String GMS = "com.google.android.gms";

    public HyperOs4GmsNetFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startSystem();
        } catch (Throwable e) {
            printLog("HyperOs4GmsNetFix error: " + e.getMessage());
            DiagLog.write("Os4Net", "init error " + e.getMessage());
        }
    }

    private static boolean isGmsUid(Context ctx, int uid) {
        try {
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) {
                return false;
            }
            for (String p : pkgs) {
                if (GMS.equals(p) || (p != null && p.startsWith(GMS + "."))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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

    private void startSystem() {
        hookAwareResourceControl();
        hookListAppsManager();
        hookNetworkPolicy();
    }

    /** HyperFCMLive: com.miui.server.greeze.power.AwareResourceControl — 断网黑名单。 */
    private void hookAwareResourceControl() {
        Class<?> clazz = XposedHelpers.findClassIfExists(
                "com.miui.server.greeze.power.AwareResourceControl", classLoader);
        if (clazz == null) {
            printLog("Os4Net AwareResourceControl 不存在");
            return;
        }
        hookConstructAfter(clazz, thisObject -> {
            patchGmsOutList(thisObject, clazz, "mNoNetworkBlackUids", true);
            setFalseIfPresent(thisObject, clazz, "mGmsLimitEnabled");
            setFalseIfPresent(thisObject, clazz, "isRestrictNet");
            printLog("Os4Net AwareResourceControl 已补 GMS 断网白名单");
            DiagLog.write("Os4Net", "AwareResourceControl patched");
        });
        // 运行时再查一次 isRestrictNet 等只读路径无字段则跳过
        hookQuiet(clazz, "isUidRestrictedForNetwork", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isBootComplete || !Boolean.TRUE.equals(param.getResult()) || context == null) {
                    return;
                }
                for (Object a : param.args != null ? param.args : new Object[0]) {
                    if (a instanceof Integer && isGmsUid(context, (Integer) a)) {
                        param.setResult(false);
                        printLog("Os4Net isUidRestrictedForNetwork gms=false", true);
                        DiagLog.write("Os4Net", "unrestrict uid gms");
                    }
                }
            }
        });
        printLog("Os4Net 已挂接 AwareResourceControl");
    }

    /** HyperFCMLive: ListAppsManager — 系统黑名单 / 数据白名单。 */
    private void hookListAppsManager() {
        Class<?> clazz = XposedHelpers.findClassIfExists(
                "com.miui.server.greeze.power.ListAppsManager", classLoader);
        if (clazz == null) {
            printLog("Os4Net ListAppsManager 不存在");
            return;
        }
        hookConstructAfter(clazz, thisObject -> {
            patchGmsOutList(thisObject, clazz, "mSystemBlackList", true);
            patchGmsOutList(thisObject, clazz, "SYSTEM_BLACK_LIST", true);
            patchGmsOutList(thisObject, clazz, "mUseDataWhiteList", false);
            patchGmsOutList(thisObject, clazz, "USE_DATA_WHITE_LIST", false);
            printLog("Os4Net ListAppsManager 黑白名单已补 GMS");
            DiagLog.write("Os4Net", "ListAppsManager patched");
        });
        hookQuiet(clazz, "isInWhiteList", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) {
                    return;
                }
                for (Object a : param.args) {
                    if (a instanceof String && isGms((String) a)) {
                        param.setResult(true);
                        printLog("Os4Net ListAppsManager isInWhiteList gms=true", true);
                    }
                }
            }
        });
        printLog("Os4Net 已挂接 ListAppsManager");
    }

    /** 网络策略：GMS 不被标为 blocked。 */
    private void hookNetworkPolicy() {
        Class<?> clazz = XposedHelpers.findClassIfExists(
                "com.android.server.net.NetworkPolicyManagerService", classLoader);
        if (clazz == null) {
            return;
        }
        Method m = XposedUtils.tryFindMethodMostParam(clazz, "isUidNetworkingBlocked");
        if (m == null) {
            printLog("Os4Net 未找到 isUidNetworkingBlocked");
            return;
        }
        try {
            XposedBridge.deoptimize(m);
        } catch (Throwable ignored) {
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isBootComplete || !Boolean.TRUE.equals(param.getResult()) || context == null) {
                    return;
                }
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int uid = (Integer) param.args[0];
                if (isGmsUid(context, uid)) {
                    param.setResult(false);
                    printLog("Os4Net NetworkPolicy 放行 GMS uid=" + uid, true);
                    DiagLog.write("Os4Net", "netpolicy allow gms uid=" + uid);
                }
            }
        });
        printLog("Os4Net 已挂接 NetworkPolicyManagerService#isUidNetworkingBlocked");
    }

    /** powerkeeper：Doze 白名单 + GMS；不在这里 no-op 建网方法。 */
    public void hookPowerkeeper(ClassLoader powerkeeperClassLoader) {
        try {
            Class<?> helper = XposedHelpers.findClassIfExists(
                    "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper", powerkeeperClassLoader);
            if (helper != null) {
                Method m = XposedUtils.tryFindMethodMostParam(helper, "getDozeWhiteListApps");
                if (m != null) {
                    try {
                        XposedBridge.deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isBootComplete || !(param.getResult() instanceof Collection)) {
                                return;
                            }
                            @SuppressWarnings("unchecked")
                            Collection<Object> list = (Collection<Object>) param.getResult();
                            if (!list.contains(GMS)) {
                                try {
                                    list.add(GMS);
                                    printLog("Os4Net dozeWhiteList +gms", true);
                                    DiagLog.write("Os4Net", "dozeWhiteList +gms");
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    });
                    printLog("Os4Net 已挂接 powerkeeper getDozeWhiteListApps");
                }
            }
        } catch (Throwable e) {
            printLog("Os4Net powerkeeper error: " + e.getMessage());
        }
    }

    private static boolean isGms(String pkg) {
        return GMS.equals(pkg) || (pkg != null && pkg.startsWith(GMS + "."));
    }

    private void patchGmsOutList(Object instance, Class<?> clazz, String fieldName, boolean remove) {
        Field f = findField(clazz, fieldName);
        if (f == null) {
            return;
        }
        try {
            f.setAccessible(true);
            Object raw = f.get(instance);
            if (raw instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<String> list = (Collection<String>) raw;
                if (remove) {
                    if (list.remove(GMS) || list.remove(GMS + ".persistent")) {
                        printLog("Os4Net " + clazz.getSimpleName() + "#" + fieldName + " -gms");
                    }
                } else if (!list.contains(GMS)) {
                    list.add(GMS);
                    printLog("Os4Net " + clazz.getSimpleName() + "#" + fieldName + " +gms");
                }
            }
        } catch (Throwable e) {
            printLog("Os4Net patch " + fieldName + ": " + e.getMessage());
        }
    }

    private void setFalseIfPresent(Object instance, Class<?> clazz, String fieldName) {
        Field f = findField(clazz, fieldName);
        if (f == null || f.getType() != boolean.class) {
            return;
        }
        try {
            f.setAccessible(true);
            f.setBoolean(instance, false);
            printLog("Os4Net " + clazz.getSimpleName() + "#" + fieldName + " = false");
        } catch (Throwable e) {
            printLog("Os4Net set " + fieldName + ": " + e.getMessage());
        }
    }

    private static void hookQuiet(Class<?> clazz, String name, XC_MethodHook hook) {
        Method m = XposedUtils.tryFindMethodMostParam(clazz, name);
        if (m == null) {
            printLog("Os4Net 未找到 " + clazz.getName() + "#" + name);
            return;
        }
        try {
            XposedBridge.deoptimize(m);
        } catch (Throwable ignored) {
        }
        XposedBridge.hookMethod(m, hook);
        printLog("Os4Net 已挂接 " + clazz.getSimpleName() + "#" + name);
    }

    private static void hookConstructAfter(Class<?> clazz, ConstructCallback cb) {
        Constructor<?>[] ctors = clazz.getDeclaredConstructors();
        if (ctors == null || ctors.length == 0) {
            return;
        }
        Constructor<?> best = ctors[0];
        for (Constructor<?> c : ctors) {
            if (c.getParameterTypes().length >= best.getParameterTypes().length) {
                best = c;
            }
        }
        try {
            XposedBridge.hookMethod(best, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        cb.after(param.thisObject);
                    } catch (Throwable e) {
                        printLog("Os4Net construct after: " + e.getMessage());
                    }
                }
            });
            printLog("Os4Net 已挂接构造 " + clazz.getName());
        } catch (Throwable e) {
            printLog("Os4Net 构造挂接失败 " + e.getMessage());
        }
    }

    private interface ConstructCallback {
        void after(Object thisObject);
    }
}
