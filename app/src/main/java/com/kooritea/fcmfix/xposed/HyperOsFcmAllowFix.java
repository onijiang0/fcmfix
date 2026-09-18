package com.kooritea.fcmfix.xposed;

import android.content.Intent;

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
                                param.setResult(true);
                                return;
                            }
                            // BroadcastRecord: field intent
                            if (a != null) {
                                try {
                                    Object intent = XposedHelpers.getObjectField(a, "intent");
                                    if (intent instanceof Intent && shouldAllow((Intent) intent)) {
                                        printLog("HyperOS FCM allow checkApplicationAutoStart(record)", true);
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
