package com.kooritea.fcmfix.xposed;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

/**
 * GMS 进程兜底：在发送 FCM 广播的源头直接加上 FLAG_INCLUDE_STOPPED_PACKAGES。
 *
 * 部分 ROM（ColorOS 16 / 某些 Android 16-17 机型）会把 system_server 中
 * broadcastIntentLocked 内联，导致系统侧 hook 不触发。此模块在 GMS 侧
 * hook sendBroadcast/sendOrderedBroadcast，从源头补标志。
 * 需要模块作用域包含 com.google.android.gms。
 */
public class GmsDeliveryFix extends XposedModule {

    public GmsDeliveryFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHook();
        } catch (Throwable e) {
            printLog("GmsDeliveryFix hook error: " + e.getMessage());
        }
    }

    private void startHook() {
        for (String className : new String[]{"android.content.ContextWrapper", "android.content.ContextImpl"}) {
            try {
                XposedUtils.findAndHookMethodAnyParam(className, classLoader, "sendBroadcast",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                                    onBroadcastSent((Intent) param.args[0]);
                                }
                            }
                        }, Intent.class);
            } catch (Throwable e) {
                printLog("GmsDeliveryFix sendBroadcast 挂接失败 (" + className + "): " + e.getMessage());
            }
            try {
                XposedHelpers.findAndHookMethod(className, classLoader, "sendBroadcast",
                        Intent.class, String.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                                    onBroadcastSent((Intent) param.args[0]);
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }
            try {
                XposedUtils.findAndHookMethodAnyParam(className, classLoader, "sendOrderedBroadcast",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                                    onBroadcastSent((Intent) param.args[0]);
                                }
                            }
                        }, Intent.class, String.class, Bundle.class, BroadcastReceiver.class, Handler.class, int.class, String.class, Bundle.class);
            } catch (Throwable e) {
                printLog("GmsDeliveryFix sendOrderedBroadcast 挂接失败 (" + className + "): " + e.getMessage());
            }
            try {
                XposedUtils.findAndHookMethodAnyParam(className, classLoader, "sendOrderedBroadcast",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                                    onBroadcastSent((Intent) param.args[0]);
                                }
                            }
                        }, Intent.class, String.class, BroadcastReceiver.class, Handler.class, int.class, String.class, Bundle.class);
            } catch (Throwable ignored) {
            }
        }
    }

    private void onBroadcastSent(Intent intent) {
        try {
            if (!isBootComplete || intent == null) {
                return;
            }
            if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0) {
                return;
            }
            if (!isFCMIntent(intent)) {
                return;
            }
            String target = intent.getComponent() != null
                    ? intent.getComponent().getPackageName()
                    : intent.getPackage();
            if (target == null || !targetIsAllow(target)) {
                return;
            }
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            printLog("GMS 侧加入 FLAG_INCLUDE_STOPPED_PACKAGES: " + target, true);
        } catch (Throwable e) {
            printLog("GmsDeliveryFix onBroadcastSent error: " + e.getMessage());
        }
    }
}
