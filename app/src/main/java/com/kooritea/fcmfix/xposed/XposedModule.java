package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    private static String selfPackageName = "UNKNOWN";

    protected final ClassLoader classLoader;
    public static Set<String> allowList = null;
    static final String TAG = "FcmFix";
    private static final HashMap<String, Object> config = new HashMap<>();

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static Boolean isInitReceiver = false;
    public static Boolean isBootComplete = false;
    private static Thread loadConfigThread = null;

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        instances.add(this);
        if (instances.size() == 1) {
            initContext(classLoader);
        } else if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    /**
     * system_server 中 attachBaseContext 可能装得太晚。
     * XposedMain 在 onSystemServerStarting 主动注入系统上下文。
     */
    public static void initSystemServerContext(Context systemContext) {
        if (context != null || systemContext == null) {
            return;
        }
        context = systemContext;
        try {
            if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                callAllOnCanReadConfig();
            } else {
                IntentFilter userUnlockIntentFilter = new IntentFilter();
                userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
            }
        } catch (Throwable e) {
            printLog("initSystemServerContext: " + e.getMessage());
        }
    }

    private static void initContext(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) {
                if (context == null) {
                    context = (Context) methodHookParam.thisObject;
                    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                        callAllOnCanReadConfig();
                    } else {
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                    }
                }
            }
        });
    }

    private static void callAllOnCanReadConfig() {
        initReceiver();
        if ("android".equals(getSelfPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(60000);
                    isBootComplete = true;
                    printLog("Boot Complete");
                } catch (Throwable e) {
                    printLog(e.getMessage());
                }
            }).start();
        } else {
            isBootComplete = true;
        }
        for (XposedModule instance : instances) {
            try {
                instance.onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Throwable {
    }

    protected static void printLog(String text) {
        printLog(text, false);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        String line = "[" + getSelfPackageName() + "]" + text;
        Log.i(TAG, line);
        XposedBridge.log("[fcmfix] " + line);
        if (isDiagnosticsLog && context != null) {
            try {
                Intent log = new Intent("com.kooritea.fcmfix.log");
                log.putExtra("text", line);
                // system_server 必须带 UserHandle，否则只打 without a qualified user 警告
                context.sendBroadcastAsUser(log, android.os.UserHandle.ALL);
            } catch (Throwable ignored) {
            }
        }
    }

    protected void checkUserDeviceUnlockAndUpdateConfig() {
        if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onUpdateConfig();
            } catch (Throwable e) {
                printLog("更新配置文件失败: " + e.getMessage());
            }
        }
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Throwable ignored) {
                }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName) {
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if ("com.kooritea.fcmfix".equals(packageName)) {
            return true;
        }
        if (allowList != null) {
            return allowList.contains(packageName);
        }
        return false;
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if (config.get("init") == null) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value == null ? defaultValue : (Boolean) value;
    }

    protected static void onUpdateConfig() {
        if (loadConfigThread == null) {
            loadConfigThread = new Thread() {
                @Override
                public void run() {
                    super.run();
                    try {
                        SharedPreferences remotePreferences = XposedBridge.getRemotePreferences("config");
                        if (remotePreferences == null) {
                            throw new IllegalStateException("remotePreferences 不可用");
                        }
                        allowList = remotePreferences.getStringSet("allowList", allowList == null ? new HashSet<>() : allowList);
                        if (allowList != null && "android".equals(getSelfPackageName())) {
                            printLog("[Modern Xposed API]onUpdateConfig allowList size: " + allowList.size());
                        }
                        config.put("disableAutoCleanNotification", remotePreferences.getBoolean("disableAutoCleanNotification", false));
                        config.put("includeIceBoxDisableApp", remotePreferences.getBoolean("includeIceBoxDisableApp", false));
                        config.put("noResponseNotification", remotePreferences.getBoolean("noResponseNotification", false));
                        config.put("init", true);
                    } catch (Throwable e) {
                        printLog("通过现代Xposed API读取配置失败: " + e.getMessage());
                        if (loadConfigFromProvider()) {
                            printLog("已通过 ConfigProvider 兜底加载配置, allowList size: " + (allowList == null ? 0 : allowList.size()));
                        }
                    }
                    loadConfigThread = null;
                }
            };
            loadConfigThread.start();
        }
    }

    private static boolean loadConfigFromProvider() {
        try {
            if (context == null) {
                return false;
            }
            Uri uri = Uri.parse("content://com.kooritea.fcmfix.provider");
            java.util.HashSet<String> newAllow = new java.util.HashSet<>();
            boolean init = false;
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor == null) {
                    return false;
                }
                int keyIdx = cursor.getColumnIndex("key");
                int valueIdx = cursor.getColumnIndex("value");
                if (keyIdx < 0 || valueIdx < 0) {
                    return false;
                }
                while (cursor.moveToNext()) {
                    String key = cursor.getString(keyIdx);
                    String value = cursor.getString(valueIdx);
                    if ("init".equals(key)) {
                        init = true;
                    } else if ("allowList".equals(key) && value != null) {
                        newAllow.add(value);
                    } else if ("disableAutoCleanNotification".equals(key)) {
                        config.put("disableAutoCleanNotification", "1".equals(value));
                    } else if ("includeIceBoxDisableApp".equals(key)) {
                        config.put("includeIceBoxDisableApp", "1".equals(value));
                    } else if ("noResponseNotification".equals(key)) {
                        config.put("noResponseNotification", "1".equals(value));
                    }
                }
            }
            if (!init) {
                return false;
            }
            allowList = newAllow;
            config.put("init", true);
            return true;
        } catch (Throwable e) {
            printLog("ConfigProvider 兜底失败: " + e.getMessage());
            return false;
        }
    }

    private static void onUninstallFcmfix() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmfix");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void initReceiver() {
        if (!isInitReceiver && context != null) {
            isInitReceiver = true;

            IntentFilter updateConfigIntentFilter = new IntentFilter();
            updateConfigIntentFilter.addAction("com.kooritea.fcmfix.update.config");
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                        }
                    }
                }, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                        }
                    }
                }, updateConfigIntentFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter();
            unInstallIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && "com.kooritea.fcmfix".equals(intent.getData().getSchemeSpecificPart())) {
                        Bundle extras = intent.getExtras();
                        if (extras.containsKey(Intent.EXTRA_REPLACING) && extras.getBoolean(Intent.EXTRA_REPLACING)) {
                            return;
                        }
                        onUninstallFcmfix();
                        if ("android".equals(getSelfPackageName())) {
                            printLog("Fcmfix已卸载，重启后停止生效。");
                        }
                    }
                }
            }, unInstallIntentFilter);
        }

    }

    protected void sendNotification(String title) {
        sendNotification(title, null, null);
    }

    protected void sendNotification(String title, String content) {
        sendNotification(title, content, null);
    }

    @SuppressLint("MissingPermission")
    protected void sendNotification(String title, String content, PendingIntent pendingIntent) {
        printLog(title, false);
        title = "[fcmfix]" + title;
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pendingIntent != null) {
            notification.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        notificationManager.notify((int) System.currentTimeMillis(), notification.build());
    }

    protected void createFcmfixChannel(NotificationManagerCompat notificationManager) {
        if (notificationManager.getNotificationChannel("fcmfix") == null) {
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected boolean isFCMAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE") ||
                action.contains("com.google.firebase.MESSAGING_EVENT") ||
                action.contains("com.google.firebase.INSTANCE_ID_EVENT") ||
                action.contains("MESSAGING_EVENT") ||
                action.contains("c2dm.intent.RECEIVE"));
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
