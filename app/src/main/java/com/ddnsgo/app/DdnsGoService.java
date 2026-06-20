package com.ddnsgo.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;

public class DdnsGoService extends Service {

    private static final String TAG = "DdnsGoService";
    private static final String CHANNEL_ID = "ddns_go_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String ASSET_BINARY_NAME = "ddns-go";
    private static final String NATIVE_EXECUTABLE_NAME = "libddnsgo.so";
    private static final int PORT = 9876;
    private static final String LISTEN_ADDRESS = ":" + PORT;
    private static final long PROCESS_RESTART_DELAY_MS = 3000L;
    private static final long SERVICE_RESTART_DELAY_MS = 2000L;
    private static final long WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60 * 1000L;
    private static final long HEALTH_START_GRACE_MS = 20 * 1000L;
    private static final int HEALTH_FAILURE_LIMIT = 3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            runHealthCheck();
            if (!destroyed) {
                mainHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
            }
        }
    };

    private Process ddnsGoProcess;
    private boolean startRequested;
    private boolean destroyed;
    private int failedHealthChecks;
    private long processStartElapsed;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered;

    public static String getLocalWebUrl() {
        return "http://127.0.0.1:" + PORT + "/";
    }

    public static String getLanWebUrl() {
        LanAddresses addresses = getLanAddresses();
        if (addresses.ipv4 == null && addresses.ipv6 == null) {
            return "IPv4: http://<phone-lan-ip>:" + PORT + "/"
                    + "\nIPv6: http://[<phone-ipv6>]:" + PORT + "/";
        }
        StringBuilder builder = new StringBuilder();
        if (addresses.ipv4 != null) {
            builder.append("IPv4: http://").append(addresses.ipv4).append(":").append(PORT).append("/");
        }
        if (addresses.ipv6 != null) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("IPv6: http://[").append(addresses.ipv6).append("]:").append(PORT).append("/");
        }
        return builder.toString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        createNotificationChannel();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String localUrl = getLocalWebUrl();
        String lanUrl = getLanWebUrl();
        String notificationText = lanUrl.replace("\n", "  ");
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ddns-go")
                .setContentText(notificationText)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Local: " + localUrl + "\nLAN: " + lanUrl))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        acquireKeepAliveLocks();
        scheduleWatchdogAlarm(WATCHDOG_INTERVAL_MS);
        scheduleHealthCheck(HEALTH_CHECK_INTERVAL_MS);
        startDdnsGo();

        return START_STICKY;
    }

    private synchronized void startDdnsGo() {
        if (startRequested || isProcessAlive(ddnsGoProcess)) {
            return;
        }
        startRequested = true;
        new Thread(() -> {
            Process process = null;
            boolean shouldRestart = true;
            try {
                File binaryFile = resolveDdnsGoExecutable();
                if (binaryFile == null) {
                    Log.e(TAG, "ddns-go executable is missing");
                    shouldRestart = false;
                    return;
                }
                if (!binaryFile.canExecute() && !binaryFile.setExecutable(true, false)) {
                    Log.e(TAG, "ddns-go binary is not executable: " + binaryFile.getAbsolutePath());
                    shouldRestart = false;
                    return;
                }

                String binaryPath = binaryFile.getAbsolutePath();
                String configPath = getFilesDir().getAbsolutePath() + "/ddns-go-config.yaml";

                ProcessBuilder pb = new ProcessBuilder(
                        binaryPath,
                        "-l", LISTEN_ADDRESS,
                        "-f", "300",
                        "-c", configPath
                );
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                process = pb.start();
                ddnsGoProcess = process;
                processStartElapsed = SystemClock.elapsedRealtime();
                failedHealthChecks = 0;
                logProcessOutput(process);
                int exitCode = process.waitFor();
                Log.w(TAG, "ddns-go exited with code " + exitCode);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start ddns-go", e);
            } finally {
                synchronized (this) {
                    if (ddnsGoProcess == process) {
                        ddnsGoProcess = null;
                    }
                    startRequested = false;
                }
                if (!destroyed && shouldRestart) {
                    scheduleWatchdogAlarm(WATCHDOG_INTERVAL_MS);
                    mainHandler.postDelayed(this::startDdnsGo, PROCESS_RESTART_DELAY_MS);
                }
            }
        }, "ddns-go-runner").start();
    }

    private void scheduleHealthCheck(long delayMs) {
        mainHandler.removeCallbacks(healthCheckRunnable);
        mainHandler.postDelayed(healthCheckRunnable, delayMs);
    }

    private void runHealthCheck() {
        if (destroyed || startRequested) {
            return;
        }
        scheduleWatchdogAlarm(WATCHDOG_INTERVAL_MS);
        if (!isProcessAlive(ddnsGoProcess)) {
            Log.w(TAG, "Health check found ddns-go stopped, starting it");
            startDdnsGo();
            return;
        }
        if (SystemClock.elapsedRealtime() - processStartElapsed < HEALTH_START_GRACE_MS) {
            return;
        }
        if (isLocalWebReady()) {
            failedHealthChecks = 0;
            return;
        }
        failedHealthChecks++;
        Log.w(TAG, "Health check failed " + failedHealthChecks + "/" + HEALTH_FAILURE_LIMIT);
        if (failedHealthChecks >= HEALTH_FAILURE_LIMIT) {
            failedHealthChecks = 0;
            restartDdnsGoProcess();
        }
    }

    private boolean isLocalWebReady() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(getLocalWebUrl()).openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setUseCaches(false);
            return connection.getResponseCode() > 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void restartDdnsGoProcess() {
        Log.w(TAG, "Restarting ddns-go after health check failures");
        Process process;
        synchronized (this) {
            process = ddnsGoProcess;
        }
        if (isProcessAlive(process)) {
            process.destroy();
        }
        mainHandler.postDelayed(this::startDdnsGo, PROCESS_RESTART_DELAY_MS);
    }

    private File resolveDdnsGoExecutable() {
        File nativeExecutable = new File(
                getApplicationInfo().nativeLibraryDir,
                NATIVE_EXECUTABLE_NAME
        );
        if (nativeExecutable.exists() && nativeExecutable.length() > 0) {
            return nativeExecutable;
        }

        Log.w(TAG, "Native executable missing, falling back to assets copy: "
                + nativeExecutable.getAbsolutePath());
        return installAssetBinaryFallback();
    }

    private File installAssetBinaryFallback() {
        File binaryFile = new File(getFilesDir(), ASSET_BINARY_NAME);
        if (binaryFile.exists() && binaryFile.length() > 0) {
            return binaryFile;
        }

        try (InputStream is = getAssets().open(ASSET_BINARY_NAME);
             FileOutputStream fos = new FileOutputStream(binaryFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.flush();
            if (!binaryFile.setExecutable(true, false)) {
                Log.e(TAG, "Failed to set ddns-go executable permission");
            }
            return binaryFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to install ddns-go binary", e);
            return null;
        }
    }

    private void acquireKeepAliveLocks() {
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            getPackageName() + ":ddns-go"
                    );
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to acquire CPU wake lock", e);
        }

        try {
            if (wifiLock == null) {
                WifiManager wifiManager = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            getPackageName() + ":ddns-go"
                    );
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to acquire Wi-Fi lock", e);
        }
    }

    private void releaseKeepAliveLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to release Wi-Fi lock", e);
        }

        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to release CPU wake lock", e);
        }
    }

    private void registerNetworkCallback() {
        if (networkCallbackRegistered) {
            return;
        }
        try {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                return;
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mainHandler.postDelayed(() -> {
                        scheduleWatchdogAlarm(WATCHDOG_INTERVAL_MS);
                        startDdnsGo();
                        runHealthCheck();
                    }, PROCESS_RESTART_DELAY_MS);
                }

                @Override
                public void onLost(Network network) {
                    mainHandler.postDelayed(() -> {
                        scheduleWatchdogAlarm(WATCHDOG_INTERVAL_MS);
                        runHealthCheck();
                    }, PROCESS_RESTART_DELAY_MS);
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
            networkCallbackRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to register network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered || networkCallback == null) {
            return;
        }
        try {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to unregister network callback", e);
        } finally {
            networkCallbackRegistered = false;
            networkCallback = null;
        }
    }

    private void logProcessOutput(Process process) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, line);
                }
            } catch (Exception e) {
                Log.w(TAG, "Stopped reading ddns-go output", e);
            }
        }, "ddns-go-output").start();
    }

    private static boolean isProcessAlive(Process process) {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (isProcessAlive(ddnsGoProcess)) {
            ddnsGoProcess.destroy();
        }
        unregisterNetworkCallback();
        releaseKeepAliveLocks();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleServiceRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                "ddns-go Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps ddns-go running in the background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void scheduleServiceRestart() {
        scheduleWatchdogAlarm(SERVICE_RESTART_DELAY_MS);
    }

    private void scheduleWatchdogAlarm(long delayMs) {
        Intent restartIntent = new Intent(getApplicationContext(), DdnsGoRestartReceiver.class);
        restartIntent.setAction(DdnsGoRestartReceiver.ACTION_WATCHDOG);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                getApplicationContext(),
                1,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        long triggerAt = SystemClock.elapsedRealtime() + SERVICE_RESTART_DELAY_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
            );
        } else {
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
            );
        }
    }

    private static LanAddresses getLanAddresses() {
        LanAddresses lanAddresses = new LanAddresses();
        try {
            java.util.Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || networkInterface.isVirtual()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses =
                        networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    if (address instanceof Inet4Address && lanAddresses.ipv4 == null) {
                        lanAddresses.ipv4 = address.getHostAddress();
                    } else if (address instanceof Inet6Address && lanAddresses.ipv6 == null) {
                        lanAddresses.ipv6 = stripIpv6Scope(address.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to detect LAN address", e);
        }
        return lanAddresses;
    }

    private static String stripIpv6Scope(String address) {
        int scopeIndex = address.indexOf('%');
        if (scopeIndex >= 0) {
            return address.substring(0, scopeIndex);
        }
        return address;
    }

    private static class LanAddresses {
        String ipv4;
        String ipv6;
    }
}
