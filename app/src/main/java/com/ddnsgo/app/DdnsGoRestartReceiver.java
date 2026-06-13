package com.ddnsgo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class DdnsGoRestartReceiver extends BroadcastReceiver {

    public static final String ACTION_RESTART = "com.ddnsgo.app.RESTART_DDNS_GO";
    private static final String TAG = "DdnsGoRestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RESTART.equals(intent.getAction())) {
            return;
        }

        Intent serviceIntent = new Intent(context, DdnsGoService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart ddns-go service", e);
        }
    }
}
