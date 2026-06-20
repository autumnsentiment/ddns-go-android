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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DdnsGoService extends Service {

    private static final String TAG = "DdnsGoService";
    private static final String CHANNEL_ID = "ddns_go_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String ASSET_BINARY_NAME = "ddns-go";
    private static final String NATIVE_EXECUTABLE_NAME = "libddnsgo.so";
    private static final String IPV4_ADDRESS_FILE_PREFIX = "ddns-go-ipv4-";
    private static final String IPV6_ADDRESS_FILE_PREFIX = "ddns-go-ipv6-";
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

    public static String getAndroidNetInterfacesJson(File filesDir) {
        AndroidNetInterfaces interfaces = getAndroidNetInterfaces();
        writeAndroidInterfaceAddressFiles(filesDir, interfaces);
        return "{\"ipv4\":" + toInterfaceJsonArray(
                interfaces.ipv4,
                filesDir,
                IPV4_ADDRESS_FILE_PREFIX
        )
                + ",\"ipv6\":" + toInterfaceJsonArray(
                interfaces.ipv6,
                filesDir,
                IPV6_ADDRESS_FILE_PREFIX
        ) + "}";
    }

    public static void refreshAndroidInterfaceAddressFiles(File filesDir) {
        writeAndroidInterfaceAddressFiles(filesDir, getAndroidNetInterfaces());
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
        refreshAndroidInterfaceAddressFiles(getFilesDir());
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
                refreshAndroidInterfaceAddressFiles(getFilesDir());

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
        refreshAndroidInterfaceAddressFiles(getFilesDir());
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
                        refreshAndroidInterfaceAddressFiles(getFilesDir());
                        scheduleWatchdogAlarm(WATCHDOG_INTERVAL_MS);
                        startDdnsGo();
                        runHealthCheck();
                    }, PROCESS_RESTART_DELAY_MS);
                }

                @Override
                public void onLost(Network network) {
                    mainHandler.postDelayed(() -> {
                        refreshAndroidInterfaceAddressFiles(getFilesDir());
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

    private static AndroidNetInterfaces getAndroidNetInterfaces() {
        AndroidNetInterfaces result = new AndroidNetInterfaces();
        try {
            java.util.Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || networkInterface.isVirtual()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                boolean addedIpv4 = false;
                boolean addedIpv6 = false;
                java.util.Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                        continue;
                    }
                    if (address instanceof Inet4Address && !addedIpv4) {
                        result.ipv4.add(new AndroidNetInterface(name, address.getHostAddress()));
                        addedIpv4 = true;
                    } else if (address instanceof Inet6Address && !addedIpv6) {
                        result.ipv6.add(new AndroidNetInterface(
                                name,
                                stripIpv6Scope(address.getHostAddress())
                        ));
                        addedIpv6 = true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to detect Android network interfaces", e);
        }
        return result;
    }

    private static LanAddresses getLanAddresses() {
        AndroidNetInterfaces interfaces = getAndroidNetInterfaces();
        LanAddresses lanAddresses = new LanAddresses();
        if (!interfaces.ipv4.isEmpty()) {
            lanAddresses.ipv4 = interfaces.ipv4.get(0).address;
        }
        if (!interfaces.ipv6.isEmpty()) {
            lanAddresses.ipv6 = interfaces.ipv6.get(0).address;
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

    private static void writeAndroidInterfaceAddressFiles(
            File filesDir,
            AndroidNetInterfaces interfaces
    ) {
        if (filesDir == null) {
            return;
        }
        Set<String> activeIpv4Files = new HashSet<>();
        Set<String> activeIpv6Files = new HashSet<>();
        for (AndroidNetInterface item : interfaces.ipv4) {
            File file = getInterfaceAddressFile(filesDir, IPV4_ADDRESS_FILE_PREFIX, item.name);
            if (writeAddressFile(file, item.address)) {
                activeIpv4Files.add(file.getName());
            }
        }
        for (AndroidNetInterface item : interfaces.ipv6) {
            File file = getInterfaceAddressFile(filesDir, IPV6_ADDRESS_FILE_PREFIX, item.name);
            if (writeAddressFile(file, item.address)) {
                activeIpv6Files.add(file.getName());
            }
        }
        deleteStaleAddressFiles(filesDir, IPV4_ADDRESS_FILE_PREFIX, activeIpv4Files);
        deleteStaleAddressFiles(filesDir, IPV6_ADDRESS_FILE_PREFIX, activeIpv6Files);
    }

    private static void deleteStaleAddressFiles(File filesDir, String prefix, Set<String> activeFiles) {
        File[] files = filesDir.listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".txt"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (activeFiles.contains(file.getName())) {
                continue;
            }
            if (!file.delete() && file.exists()) {
                Log.w(TAG, "Unable to delete stale interface address file: "
                        + file.getAbsolutePath());
            }
        }
    }

    private static boolean writeAddressFile(File file, String address) {
        if (file == null || address == null || address.isEmpty()) {
            return false;
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(address.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to write interface address file: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    private static String getInterfaceAddressCommand(
            File filesDir,
            String prefix,
            String interfaceName
    ) {
        if (filesDir == null || interfaceName == null || interfaceName.isEmpty()) {
            return "";
        }
        return "cat " + shellQuote(getInterfaceAddressFile(
                filesDir,
                prefix,
                interfaceName
        ).getAbsolutePath());
    }

    private static File getInterfaceAddressFile(
            File filesDir,
            String prefix,
            String interfaceName
    ) {
        return new File(filesDir, prefix + sanitizeInterfaceName(interfaceName) + ".txt");
    }

    private static String sanitizeInterfaceName(String interfaceName) {
        StringBuilder builder = new StringBuilder(interfaceName.length());
        for (int i = 0; i < interfaceName.length(); i++) {
            char c = interfaceName.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.length() == 0 ? "unknown" : builder.toString();
    }

    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String toInterfaceJsonArray(
            List<AndroidNetInterface> interfaces,
            File filesDir,
            String filePrefix
    ) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < interfaces.size(); i++) {
            AndroidNetInterface item = interfaces.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"name\":\"")
                    .append(jsonEscape(item.name))
                    .append("\",\"address\":\"")
                    .append(jsonEscape(item.address))
                    .append("\",\"cmd\":\"")
                    .append(jsonEscape(getInterfaceAddressCommand(filesDir, filePrefix, item.name)))
                    .append("\"}");
        }
        builder.append(']');
        return builder.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        builder.append("\\u");
                        for (int j = hex.length(); j < 4; j++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    private static class AndroidNetInterfaces {
        final List<AndroidNetInterface> ipv4 = new ArrayList<>();
        final List<AndroidNetInterface> ipv6 = new ArrayList<>();
    }

    private static class AndroidNetInterface {
        final String name;
        final String address;

        AndroidNetInterface(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    private static class LanAddresses {
        String ipv4;
        String ipv6;
    }
}
