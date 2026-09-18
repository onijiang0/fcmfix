package com.kooritea.fcmfix;

import android.content.Context;

import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.xposed.AutoStartFix;
import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.GmsDeliveryFix;
import com.kooritea.fcmfix.xposed.GmsKeepAliveFix;
import com.kooritea.fcmfix.xposed.HyperOsFcmAllowFix;
import com.kooritea.fcmfix.xposed.HyperOsGreezeFix;
import com.kooritea.fcmfix.xposed.KeepNotification;
import com.kooritea.fcmfix.xposed.MiuiLocalNotificationFix;
import com.kooritea.fcmfix.xposed.OplusProxyFix;
import com.kooritea.fcmfix.xposed.PowerkeeperFix;
import com.kooritea.fcmfix.xposed.ReconnectManagerFix;
import com.kooritea.fcmfix.xposed.XposedModule;

import io.github.libxposed.api.XposedModuleInterface;

public class XposedMain extends io.github.libxposed.api.XposedModule {

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        XposedModule.setSelfPackageName("android");

        ClassLoader classLoader = param.getClassLoader();
        safeInit(() -> new BroadcastFix(classLoader), "BroadcastFix");
        safeInit(() -> new MiuiLocalNotificationFix(classLoader), "MiuiLocalNotificationFix");
        safeInit(() -> new AutoStartFix(classLoader), "AutoStartFix");
        safeInit(() -> new KeepNotification(classLoader), "KeepNotification");
        safeInit(() -> new OplusProxyFix(classLoader), "OplusProxyFix");
        // 仅 FCM+allowList 的 HyperOS 放行，不再 no-op GMS 建网/闹钟
        safeInit(() -> new HyperOsFcmAllowFix(classLoader), "HyperOsFcmAllowFix");
        // HyperOsGreezeFix / GmsKeepAliveFix 激进 hook 会干扰其它应用 FCM 投递
        // safeInit(() -> new HyperOsGreezeFix(classLoader), "HyperOsGreezeFix");
        // safeInit(() -> new GmsKeepAliveFix(classLoader), "GmsKeepAliveFix");
        // system_server 中 attachBaseContext 可能装得太晚，主动拿系统上下文
        initSystemServerContext(classLoader);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);

        if ("com.google.android.gms".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.google.android.gms");
            safeInit(() -> new ReconnectManagerFix(param.getClassLoader()), "ReconnectManagerFix");
            safeInit(() -> new GmsDeliveryFix(param.getClassLoader()), "GmsDeliveryFix");
        }

        if ("com.miui.powerkeeper".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.miui.powerkeeper");
            safeInit(() -> new PowerkeeperFix(param.getClassLoader()), "PowerkeeperFix");
        }
    }

    private interface InitTask {
        void run();
    }

    private void safeInit(InitTask task, String name) {
        try {
            XposedBridge.log("[fcmfix] start hook " + name);
            task.run();
        } catch (Throwable e) {
            XposedBridge.log("[fcmfix] " + name + " 初始化失败: " + e.getMessage());
        }
    }

    private void initSystemServerContext(ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            Object systemContext = XposedHelpers.callMethod(activityThread, "getSystemContext");
            if (systemContext instanceof Context) {
                XposedModule.initSystemServerContext((Context) systemContext);
                XposedBridge.log("[fcmfix] 系统上下文初始化成功");
            } else {
                XposedBridge.log("[fcmfix] 系统上下文获取失败: getSystemContext 返回 null");
            }
        } catch (Throwable e) {
            XposedBridge.log("[fcmfix] 系统上下文初始化失败: " + e.getMessage());
        }
    }
}
