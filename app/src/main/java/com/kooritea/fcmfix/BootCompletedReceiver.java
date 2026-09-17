package com.kooritea.fcmfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("fcmfix", "Boot completed, notify hooked processes to reload config");
        try {
            context.sendBroadcast(new Intent("com.kooritea.fcmfix.update.config"));
        } catch (Throwable e) {
            Log.e("fcmfix", "send update config broadcast failed: " + e.getMessage());
        }
    }
}