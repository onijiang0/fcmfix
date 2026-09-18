package com.kooritea.fcmfix.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Method;

/**
 * 极简 HyperOS 放行：仅当「FCM 广播且目标在 allowList」时放行，
 * 不再 no-op GmsObserver / 改闹钟，避免影响 TikTok 等其它推送。
 *
 * 对应 FCM Diagnostics:
 *   Received com.google.android.gm → No response (10~20ms)
 *   进程被 Greeze 冻住，收器无法执行。
 */
public class HyperOsFcmAllowFix extends XposedModule {

    public HyperOsFcmAllowFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            start();
        } catch (Throwable e) {
            printLog("HyperOsFcmAllowFix error: " + e.getMessage());
        }
    }

    private static boolean isC2dm(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE")
                || action.contains("c2dm.intent.RECEIVE")
                || action.contains("MESSAGING_EVENT"));
    }

    private boolean shouldAllow(Intent intent) {
        if (intent == null || !isBootComplete) {
            return false;
        }
        if (!isC2dm(intent) && !isFCMIntent(intent)) {
            return false;
        }
        String pkg = intent.getComponent() != null
                ? intent.getComponent().getPackageName() : intent.getPackage();
        return pkg != null && targetIsAllow(pkg);
    }

    /** Doze/锁屏 No response：FCM 放行时给目标临时功耗白名单，便于 App 处理广播。 */
    private void grantPowerAllow(String pkg) {
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
            for (String rn : new String[]{"REASON_GMS", "REASON_PUSH", "REASON_NOTIFICATION"}) {
                try {
                    reason = pemCls.getField(rn).getInt(null);
                    break;
                } catch (Throwable ignored) {
                }
            }
            try {
                XposedHelpers.callMethod(pem, "addToTemporaryAllowList", pkg, reason, "FCMFcmAllow", 3 * 60 * 1000L);
                printLog("HyperAllow PowerExemption " + pkg, true);
                com.kooritea.fcmfix.util.DiagLog.write("HyperAllow", "powerAllow " + pkg);
            } catch (Throwable e1) {
                try {
                    XposedHelpers.callMethod(pem, "addToAllowlist", pkg);
                    printLog("HyperAllow PowerAllowlist " + pkg, true);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            printLog("grantPowerAllow error: " + e.getMessage());
        }
    }

    private static String pkgOf(Intent intent) {
        return intent.getComponent() != null
                ? intent.getComponent().getPackageName() : intent.getPackage();
    }

    private void start() {
        Class<?> gms = XposedHelpers.findClassIfExists("com.miui.server.greeze.GreezeManagerService", classLoader);
        if (gms != null) {
            hookBoolTrueForFcm(gms, "isAllowBroadcast");
            hookSkipForFcm(gms, "deferBroadcastForMiui");
        }
        Class<?> domestic = XposedHelpers.findClassIfExists("com.miui.server.greeze.DomesticPolicyManager", classLoader);
        if (domestic != null) {
            hookSkipForFcm(domestic, "deferBroadcast");
        }
        Class<?> stub = XposedHelpers.findClassIfExists("com.android.server.am.BroadcastQueueModernStubImpl", classLoader);
        if (stub != null) {
            Method m = XposedUtils.tryFindMethodMostParam(stub, "checkApplicationAutoStart");
            if (m != null) {
                try {
                    XposedBridge.deoptimize(m);
                } catch (Throwable ignored) {
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBootComplete || param.args == null) {
                            return;
                        }
                        for (Object a : param.args) {
                            if (a instanceof Intent && shouldAllow((Intent) a)) {
                                printLog("HyperOS FCM allow checkApplicationAutoStart", true);
                                com.kooritea.fcmfix.util.DiagLog.write("HyperAllow", "autoStart allow");
                                grantPowerAllow(pkgOf((Intent) a));
                                param.setResult(true);
                                return;
                            }
                            // BroadcastRecord: field intent
                            if (a != null) {
                                try {
                                    Object intent = XposedHelpers.getObjectField(a, "intent");
                                    if (intent instanceof Intent && shouldAllow((Intent) intent)) {
                                        printLog("HyperOS FCM allow checkApplicationAutoStart(record)", true);
                                        grantPowerAllow(pkgOf((Intent) intent));
                                        param.setResult(true);
                                        return;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                });
                printLog("HyperOsFcmAllow 已挂接 BroadcastQueueModernStubImpl#checkApplicationAutoStart");
            }
        }
    }

    private void hookBoolTrueForFcm(Class<?> clazz, String name) {
        Method m = XposedUtils.tryFindMethodMostParam(clazz, name);
        if (m == null) {
            return;
        }
        try {
            XposedBridge.deoptimize(m);
        } catch (Throwable ignored) {
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) {
                    return;
                }
                for (Object a : param.args) {
                    if (a instanceof Intent && shouldAllow((Intent) a)) {
                        printLog("HyperOS FCM allow " + clazz.getSimpleName() + "#" + name, true);
                        com.kooritea.fcmfix.util.DiagLog.write("HyperAllow", name + " true");
                        grantPowerAllow(pkgOf((Intent) a));
                        param.setResult(true);
                        return;
                    }
                }
            }
        });
        printLog("HyperOsFcmAllow 已挂接 " + clazz.getSimpleName() + "#" + name);
    }

    private void hookSkipForFcm(Class<?> clazz, String name) {
        Method m = XposedUtils.tryFindMethodMostParam(clazz, name);
        if (m == null) {
            return;
        }
        try {
            XposedBridge.deoptimize(m);
        } catch (Throwable ignored) {
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) {
                    return;
                }
                for (Object a : param.args) {
                    if (a instanceof Intent && shouldAllow((Intent) a)) {
                        printLog("HyperOS FCM skip " + clazz.getSimpleName() + "#" + name, true);
                        com.kooritea.fcmfix.util.DiagLog.write("HyperAllow", name + " skip");
                        param.setResult(null);
                        return;
                    }
                }
            }
        });
        printLog("HyperOsFcmAllow 已挂接 " + clazz.getSimpleName() + "#" + name);
    }
}
