package com.ddnsgo.app;

import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class DdnsGoRestartReceiver extends BroadcastReceiver {

    public static final String ACTION_RESTART = "com.ddnsgo.app.RESTART_DDNS_GO";
    public static final String ACTION_WATCHDOG = "com.ddnsgo.app.WATCHDOG_DDNS_GO";
    private static final String TAG = "DdnsGoRestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !shouldStartService(intent.getAction())) {
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

    private boolean shouldStartService(String action) {
        return ACTION_RESTART.equals(action)
                || ACTION_WATCHDOG.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || ConnectivityManager.CONNECTIVITY_ACTION.equals(action);
    }
}
