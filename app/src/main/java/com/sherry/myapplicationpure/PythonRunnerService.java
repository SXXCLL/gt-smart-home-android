package com.sherry.myapplicationpure;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.content.BroadcastReceiver;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PythonRunnerService extends Service {

    public static final String ACTION_START = "com.sherry.myapplicationpure.action.START";
    public static final String ACTION_START_AUTO = "com.sherry.myapplicationpure.action.START_AUTO";
    public static final String ACTION_STOP = "com.sherry.myapplicationpure.action.STOP";
    public static final String ACTION_DEVICE_CONTROL = "com.sherry.myapplicationpure.action.DEVICE_CONTROL";
    public static final String ACTION_HEALTH_LOOP_START = "com.sherry.myapplicationpure.action.HEALTH_LOOP_START";
    public static final String ACTION_HEALTH_LOOP_STOP = "com.sherry.myapplicationpure.action.HEALTH_LOOP_STOP";
    public static final String EXTRA_HEALTH_INTERVAL_SEC = "health_interval_sec";
    public static final String EXTRA_GATEWAY_IP = "gateway_ip";
    public static final String EXTRA_BEMFA_KEY = "bemfa_key";
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_DEVICE_ON = "device_on";

    private static final String CHANNEL_ID = "python_runner";
    private static final String HEALTH_CHANNEL_ID = "python_runner_health_silent";
    private static final int NOTIF_ID = 42;
    private static final int HEALTH_NOTIF_ID = 43;
    private static final int MAX_SYS_LOG_LINES = 1000;
    private static final int DEFAULT_HEALTH_INTERVAL_SEC = 120;
    private static final int GATEWAY_PORT = 4196;
    private static final int GATEWAY_CONNECT_TIMEOUT_MS = 10_000;
    private static final long AUTO_RETRY_INTERVAL_MS = 60_000L;
    private static final long AUTO_RETRY_WINDOW_MS = 30 * 60_000L;

    private final ExecutorService bridgeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager.NetworkCallback wakeNetworkCallback;
    private boolean networkCallbackRegistered = false;
    private boolean networkStateInitialized = false;
    private BroadcastReceiver dozeReceiver;
    private boolean dozeReceiverRegistered = false;
    private File sysLogFile;
    private boolean isNetworkConnected = false;
    private boolean autoRetryActive = false;
    private long autoRetryWindowStartElapsed = 0L;
    private String currentGatewayIp = "";
    private String currentBemfaKey = "";
    private boolean stopRequestedByUser = false;
    private final AtomicBoolean healthLoopRunning = new AtomicBoolean(false);
    private final Object healthLoopLock = new Object();
    private Thread healthCheckThread;
    private volatile long healthCheckIntervalMs = DEFAULT_HEALTH_INTERVAL_SEC * 1000L;
    private final Runnable autoRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoRetryActive) return;
            if (SystemClock.elapsedRealtime() - autoRetryWindowStartElapsed > AUTO_RETRY_WINDOW_MS) {
                autoRetryActive = false;
                setAutoRetryWindowState(false, 0L);
                return;
            }
            if (isNetworkConnected && !isBemfaConnected() && !running.get()) {
                startWork(currentGatewayIp, currentBemfaKey);
            }
            retryHandler.postDelayed(this, AUTO_RETRY_INTERVAL_MS);
        }
    };
    @Override
    public void onCreate() {
        super.onCreate();
        stopRequestedByUser = false;
        persistServiceAlive(true);
        createNotificationChannel();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        sysLogFile = new File(getFilesDir(), "sys.log");
        loadHealthIntervalFromPrefs();
        registerNetworkCallback();
        registerDozeReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequestedByUser = true;
            persistServiceAlive(false);
            stopWork();
            stopHealthCheckLoop("service_stop");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        stopRequestedByUser = false;
        if (ACTION_HEALTH_LOOP_START.equals(action)) {
            int intervalSec = intent == null ? -1 : intent.getIntExtra(EXTRA_HEALTH_INTERVAL_SEC, -1);
            if (intervalSec > 0) {
                setHealthIntervalSec(intervalSec);
            }
            startHealthCheckLoop(true, "manual_button");
            return START_STICKY;
        }
        if (ACTION_HEALTH_LOOP_STOP.equals(action)) {
            stopHealthCheckLoop("manual_button");
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            String ip = intent.getStringExtra(EXTRA_GATEWAY_IP);
            String bemfaKey = intent.getStringExtra(EXTRA_BEMFA_KEY);
            currentGatewayIp = ip == null ? "" : ip;
            currentBemfaKey = bemfaKey == null ? "" : bemfaKey;
            try {
                startForeground(NOTIF_ID, buildNotification("运行中：Bemfa 桥接 @" + ip));
            } catch (RuntimeException e) {
                // 避免 ForegroundServiceStartNotAllowedException 导致整进程崩溃
                e.printStackTrace();
                stopSelf();
                return START_NOT_STICKY;
            }
            appendSysLog("手动启动: 立即触发桥接线程启动尝试");
            startWork(currentGatewayIp, currentBemfaKey);
            // 手动启动也纳入“自动重试窗口”策略，避免首轮失败后不再重连。
            ensureReconnectWindowAndTryStart("manual_start");
            startHealthCheckLoop(false, "manual_start");
            commandExecutor.submit(this::runQuickGatewayProbeOnly);
            return START_STICKY;
        }

        if (ACTION_START_AUTO.equals(action)) {
            String ip = intent.getStringExtra(EXTRA_GATEWAY_IP);
            String bemfaKey = intent.getStringExtra(EXTRA_BEMFA_KEY);
            currentGatewayIp = ip == null ? "" : ip;
            currentBemfaKey = bemfaKey == null ? "" : bemfaKey;
            try {
                startForeground(NOTIF_ID, buildNotification("自动连接窗口：等待网络 @" + ip));
            } catch (RuntimeException e) {
                e.printStackTrace();
                stopSelf();
                return START_NOT_STICKY;
            }
            ensureReconnectWindowAndTryStart("auto_start");
            startHealthCheckLoop(false, "auto_start");
            commandExecutor.submit(this::runQuickGatewayProbeOnly);
            return START_STICKY;
        }

        if (ACTION_DEVICE_CONTROL.equals(action)) {
            String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
            boolean on = intent.getBooleanExtra(EXTRA_DEVICE_ON, true);
            String gatewayIp = intent.getStringExtra(EXTRA_GATEWAY_IP);
            controlDevice(gatewayIp, deviceName, on);
            return START_STICKY;
        }

        return START_STICKY;
    }

    private void startWork(String gatewayIp, String bemfaKey) {
        if (!running.compareAndSet(false, true)) return;
        persistBridgeWorkerRunning(true);

        bridgeExecutor.submit(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python py = Python.getInstance();
                PyObject mod = py.getModule("app_entry");
                mod.callAttr("run_forever", gatewayIp, bemfaKey);
            } catch (PyException e) {
                appendSysLog("桥接线程异常退出 PyException=" + e.getMessage());
                appendSysLog("检查 runtime.log 可查看 Python 侧详细异常堆栈");
                e.printStackTrace();
            } catch (Exception e) {
                appendSysLog("桥接线程异常退出 Exception=" + e.getMessage());
                e.printStackTrace();
            } finally {
                running.set(false);
                persistBridgeWorkerRunning(false);
            }
        });
    }

    private void startAutoRetryWindow() {
        autoRetryActive = true;
        autoRetryWindowStartElapsed = SystemClock.elapsedRealtime();
        setAutoRetryWindowState(true, System.currentTimeMillis() + AUTO_RETRY_WINDOW_MS);
        retryHandler.removeCallbacks(autoRetryRunnable);
        if (isNetworkConnected && !isBemfaConnected() && !running.get()) {
            startWork(currentGatewayIp, currentBemfaKey);
        }
        retryHandler.postDelayed(autoRetryRunnable, AUTO_RETRY_INTERVAL_MS);
    }

    private void ensureReconnectWindowAndTryStart(String reason) {
        // 每次触发都续期开 30 分钟窗口
        startAutoRetryWindow();
        appendSysLog("自动重试窗口续开30分钟 reason=" + reason);
        if (isNetworkConnected && !isBemfaConnected() && !running.get()) {
            appendSysLog("触发立即重连尝试 reason=" + reason);
            startWork(currentGatewayIp, currentBemfaKey);
        }
    }

    private void controlDevice(String gatewayIp, String deviceName, boolean on) {
        if (deviceName == null || deviceName.trim().isEmpty()) return;
        String targetIp = gatewayIp == null || gatewayIp.trim().isEmpty() ? currentGatewayIp : gatewayIp.trim();
        commandExecutor.submit(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python py = Python.getInstance();
                py.getModule("app_entry").callAttr(
                    "control_device",
                    targetIp,
                    deviceName,
                    on
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void stopWork() {
        try {
            if (Python.isStarted()) {
                Python py = Python.getInstance();
                py.getModule("app_entry").callAttr("stop");
            }
        } catch (Exception ignored) {
        } finally {
            running.set(false);
            persistBridgeWorkerRunning(false);
            autoRetryActive = false;
            setAutoRetryWindowState(false, 0L);
            retryHandler.removeCallbacks(autoRetryRunnable);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("smart-home-zigbee")
            .setContentText(text)
            .setOngoing(true)
            .build();
    }

    private Notification buildHealthNotification(String text) {
        return new NotificationCompat.Builder(this, HEALTH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("后台巡检提醒")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Python 后台服务",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationChannel healthChannel = new NotificationChannel(
                HEALTH_CHANNEL_ID,
                "后台巡检提醒",
                NotificationManager.IMPORTANCE_LOW
            );
            healthChannel.enableVibration(false);
            healthChannel.setVibrationPattern(new long[]{0L});
            healthChannel.setSound(null, (AudioAttributes) null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
                nm.createNotificationChannel(healthChannel);
            }
        }
    }

    private void startHealthCheckLoop(boolean restartIfRunning, String reason) {
        synchronized (healthLoopLock) {
            if (healthLoopRunning.get()) {
                if (!restartIfRunning) return;
                stopHealthCheckLoopLocked("restart_before_start");
            }
            healthLoopRunning.set(true);
            healthCheckThread = new Thread(() -> {
                appendSysLog("巡检线程已启动 reason=" + reason + ", interval_sec=" + (healthCheckIntervalMs / 1000L));
                while (healthLoopRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        runPeriodicHealthCheck();
                    } catch (Exception e) {
                        appendSysLog("巡检线程异常: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(healthCheckIntervalMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                appendSysLog("巡检线程已退出");
            }, "health-check-loop");
            healthCheckThread.start();
        }
    }

    private void stopHealthCheckLoop(String reason) {
        synchronized (healthLoopLock) {
            stopHealthCheckLoopLocked(reason);
        }
    }

    private void stopHealthCheckLoopLocked(String reason) {
        if (!healthLoopRunning.getAndSet(false)) return;
        appendSysLog("已停止巡检线程 reason=" + reason);
        if (healthCheckThread != null) {
            healthCheckThread.interrupt();
            healthCheckThread = null;
        }
    }

    private void runPeriodicHealthCheck() {
        appendSysLog("健康巡检触发");
        boolean bemfaOk = isBemfaConnected();
        boolean gatewayOk = isGatewayReachable(currentGatewayIp);
        persistGatewayTcpState(gatewayOk);
        appendSysLog("健康巡检结果 bemfa=" + bemfaOk + ", gateway=" + gatewayOk + ", running=" + running.get());

        if (bemfaOk && gatewayOk) {
            appendSysLog("健康巡检无异常：仅记录状态，等待下次巡检");
            return;
        }

        appendSysLog("检测到连接异常，开始执行巡检修复流程");
        wakeDeviceAndRequestNetwork();
        wakeScreenBriefly();
        sendStrongHealthNotification();
        if (running.get()) {
            stopWork();
            retryHandler.postDelayed(() -> ensureReconnectWindowAndTryStart("health_check_restart"), 1500L);
        } else {
            ensureReconnectWindowAndTryStart("health_check_start");
        }
    }

    private void sendStrongHealthNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                long intervalSec = Math.max(1L, healthCheckIntervalMs / 1000L);
                nm.notify(HEALTH_NOTIF_ID, buildHealthNotification("每" + intervalSec + "秒线程巡检已执行，已触发联网与重连检查"));
            }
        } catch (Exception ignored) {
        }
    }

    private void wakeDeviceAndRequestNetwork() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":periodic_health_check"
                );
                wakeLock.acquire(10_000L);
                appendSysLog("巡检: 已申请10秒WakeLock");
            }
        } catch (Exception ignored) {
        }

        if (connectivityManager == null) return;
        try {
            if (wakeNetworkCallback != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(wakeNetworkCallback);
                } catch (Exception ignored) {
                }
            }
            wakeNetworkCallback = new ConnectivityManager.NetworkCallback() {};
            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            connectivityManager.requestNetwork(request, wakeNetworkCallback, 15_000);
            appendSysLog("巡检: 已请求系统网络连接");
            retryHandler.postDelayed(() -> {
                if (connectivityManager != null && wakeNetworkCallback != null) {
                    try {
                        connectivityManager.unregisterNetworkCallback(wakeNetworkCallback);
                    } catch (Exception ignored) {
                    }
                }
            }, 20_000L);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreenBriefly() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                getPackageName() + ":periodic_health_screen_wakeup"
            );
            screenWakeLock.acquire(3_000L);
            appendSysLog("巡检: 已请求点亮屏幕(3秒)");
        } catch (Exception ignored) {
        }
    }

    private boolean isGatewayReachable(String gatewayIp) {
        if (gatewayIp == null || gatewayIp.trim().isEmpty()) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(gatewayIp.trim(), GATEWAY_PORT), GATEWAY_CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null || (networkCallback != null && networkCallbackRegistered)) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean wasConnected = isNetworkConnected;
                isNetworkConnected = true;
                if (!wasConnected || !networkStateInitialized) {
                    appendSysLog("网络已连接（Service）");
                    persistNetworkConnectedNow();
                }
                networkStateInitialized = true;
                if (!isBemfaConnected()) {
                    ensureReconnectWindowAndTryStart("network_available");
                }
            }

            @Override
            public void onLost(Network network) {
                boolean wasConnected = isNetworkConnected;
                isNetworkConnected = false;
                if (wasConnected || !networkStateInitialized) {
                    appendSysLog("网络已断开（Service）");
                    persistNetworkDisconnectedNow();
                }
                networkStateInitialized = true;
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
            updateInitialNetworkState();
        } catch (Exception ignored) {
        }
    }

    private void registerDozeReceiver() {
        if (dozeReceiver != null && dozeReceiverRegistered) return;
        dozeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                if (!PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())) return;
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        boolean idle = pm.isDeviceIdleMode();
                        appendSysLog(idle ? "Doze: 进入休眠模式（Service）" : "Doze: 退出休眠模式（Service）");
                        persistDozeState(idle);
                        if (!idle) {
                            ensureReconnectWindowAndTryStart("doze_exit");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        try {
            registerReceiver(dozeReceiver, filter);
            dozeReceiverRegistered = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    boolean idle = pm.isDeviceIdleMode();
                    appendSysLog(idle ? "初始状态: Doze休眠中（Service）" : "初始状态: Doze未休眠（Service）");
                    persistDozeState(idle);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void updateInitialNetworkState() {
        if (connectivityManager == null) return;
        try {
            Network active = connectivityManager.getActiveNetwork();
            if (active == null) {
                isNetworkConnected = false;
                networkStateInitialized = true;
                appendSysLog("初始状态: 网络未连接（Service）");
                persistInitialNetworkState(false);
                return;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
            isNetworkConnected = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            networkStateInitialized = true;
            appendSysLog(isNetworkConnected ? "初始状态: 网络已连接（Service）" : "初始状态: 网络未连接（Service）");
            persistInitialNetworkState(isNetworkConnected);
        } catch (Exception ignored) {
            isNetworkConnected = false;
            networkStateInitialized = true;
        }
    }

    private void persistInitialNetworkState(boolean connected) {
        long now = System.currentTimeMillis();
        if (connected) {
            boolean hasConnectedBefore = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .getLong(MainActivity.KEY_NET_CONNECTED_SINCE_WALL_MS, 0L) > 0L;
            if (!hasConnectedBefore) {
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.KEY_NET_CONNECTED, true)
                    .putLong(MainActivity.KEY_NET_CONNECTED_SINCE_WALL_MS, now)
                    .putLong(MainActivity.KEY_NET_LAST_CONNECTED_WALL_MS, now)
                    .apply();
            } else {
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.KEY_NET_CONNECTED, true)
                    .apply();
            }
        } else {
            getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(MainActivity.KEY_NET_CONNECTED, false)
                .apply();
        }
    }

    private void persistNetworkConnectedNow() {
        long now = System.currentTimeMillis();
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_NET_CONNECTED, true)
            .putLong(MainActivity.KEY_NET_CONNECTED_SINCE_WALL_MS, now)
            .putLong(MainActivity.KEY_NET_LAST_CONNECTED_WALL_MS, now)
            .apply();
    }

    private void persistNetworkDisconnectedNow() {
        long now = System.currentTimeMillis();
        long sinceWall = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getLong(MainActivity.KEY_NET_CONNECTED_SINCE_WALL_MS, 0L);
        long duration = sinceWall > 0L ? Math.max(0L, now - sinceWall) : 0L;
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_NET_CONNECTED, false)
            .putLong(MainActivity.KEY_NET_LAST_DURATION_MS, duration)
            .apply();
    }

    private void persistDozeState(boolean isIdle) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_DOZE_IDLE, isIdle)
            .putLong(MainActivity.KEY_DOZE_UPDATED_WALL_MS, System.currentTimeMillis())
            .apply();
    }

    private void persistServiceAlive(boolean alive) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_SERVICE_ALIVE, alive)
            .putLong(MainActivity.KEY_SERVICE_ALIVE_UPDATED_WALL_MS, System.currentTimeMillis())
            .apply();
    }

    private void persistBridgeWorkerRunning(boolean active) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_BRIDGE_WORKER_RUNNING, active)
            .putLong(MainActivity.KEY_BRIDGE_WORKER_UPDATED_WALL_MS, System.currentTimeMillis())
            .apply();
    }

    private void persistGatewayTcpState(boolean ok) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_GATEWAY_TCP_OK, ok)
            .putLong(MainActivity.KEY_GATEWAY_TCP_CHECKED_WALL_MS, System.currentTimeMillis())
            .apply();
    }

    private void runQuickGatewayProbeOnly() {
        try {
            boolean gatewayOk = isGatewayReachable(currentGatewayIp);
            persistGatewayTcpState(gatewayOk);
        } catch (Exception ignored) {
        }
    }

    private void loadHealthIntervalFromPrefs() {
        int sec = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getInt(MainActivity.KEY_HEALTH_CHECK_INTERVAL_SEC, DEFAULT_HEALTH_INTERVAL_SEC);
        setHealthIntervalSec(sec);
    }

    private void setHealthIntervalSec(int sec) {
        int safeSec = Math.max(5, sec);
        healthCheckIntervalMs = safeSec * 1000L;
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(MainActivity.KEY_HEALTH_CHECK_INTERVAL_SEC, safeSec)
            .apply();
        appendSysLog("巡检间隔已更新 interval_sec=" + safeSec);
    }

    private boolean isBemfaConnected() {
        try {
            File statusFile = new File(getFilesDir(), "bridge_status.json");
            if (!statusFile.exists()) return false;
            try (FileInputStream fis = new FileInputStream(statusFile)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                JSONObject root = new JSONObject(new String(baos.toByteArray(), StandardCharsets.UTF_8));
                return root.optBoolean("connected", false);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void setAutoRetryWindowState(boolean active, long endWallMs) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_AUTO_RETRY_ACTIVE, active)
            .putLong(MainActivity.KEY_AUTO_RETRY_END_WALL_MS, endWallMs)
            .apply();
    }

    private synchronized void appendSysLog(String msg) {
        ensureSysLogWithinLimit();
        String line = formatWallTime(System.currentTimeMillis()) + " [SYS] " + msg + "\n";
        try (FileOutputStream fos = new FileOutputStream(sysLogFile, true)) {
            fos.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private synchronized void ensureSysLogWithinLimit() {
        if (sysLogFile == null || !sysLogFile.exists()) return;
        if (countLogLines(sysLogFile) > MAX_SYS_LOG_LINES) {
            try (FileOutputStream fos = new FileOutputStream(sysLogFile, false)) {
                fos.write("".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    private int countLogLines(File file) {
        int count = 0;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                for (int i = 0; i < len; i++) {
                    if (buffer[i] == '\n') count++;
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private String formatWallTime(long timeMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(timeMs));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        persistServiceAlive(false);
        stopWork();
        stopHealthCheckLoop(stopRequestedByUser ? "service_destroy_user" : "service_destroy_system");
        if (connectivityManager != null && networkCallback != null && networkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallbackRegistered = false;
        }
        if (connectivityManager != null && wakeNetworkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(wakeNetworkCallback);
            } catch (Exception ignored) {
            }
            wakeNetworkCallback = null;
        }
        if (dozeReceiver != null && dozeReceiverRegistered) {
            try {
                unregisterReceiver(dozeReceiver);
            } catch (Exception ignored) {
            }
            dozeReceiverRegistered = false;
        }
        bridgeExecutor.shutdownNow();
        commandExecutor.shutdownNow();
        super.onDestroy();
    }
}
