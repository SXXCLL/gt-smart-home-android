package com.sherry.myapplicationpure;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int MAX_LOG_LINES = 1000;
    private static final int MAX_SYS_LOG_LINES = 1000;
    private static final String KEY_BOOT_META_LOGGED_AT = "boot_meta_logged_at";
    public static final String PREFS_NAME = "keepalive_prefs";
    public static final String KEY_SERVICE_AUTO_START = "service_auto_start";
    public static final String KEY_AUTO_RETRY_ACTIVE = "auto_retry_active";
    public static final String KEY_AUTO_RETRY_END_WALL_MS = "auto_retry_end_wall_ms";
    public static final String KEY_NET_CONNECTED = "net_connected";
    public static final String KEY_NET_CONNECTED_SINCE_WALL_MS = "net_connected_since_wall_ms";
    public static final String KEY_NET_LAST_CONNECTED_WALL_MS = "net_last_connected_wall_ms";
    public static final String KEY_NET_LAST_DURATION_MS = "net_last_duration_ms";
    public static final String KEY_DOZE_IDLE = "doze_idle";
    public static final String KEY_DOZE_UPDATED_WALL_MS = "doze_updated_wall_ms";
    public static final String KEY_SERVICE_ALIVE = "service_alive";
    public static final String KEY_SERVICE_ALIVE_UPDATED_WALL_MS = "service_alive_updated_wall_ms";
    public static final String KEY_BRIDGE_WORKER_RUNNING = "bridge_worker_running";
    public static final String KEY_BRIDGE_WORKER_UPDATED_WALL_MS = "bridge_worker_updated_wall_ms";
    public static final String KEY_GATEWAY_TCP_OK = "gateway_tcp_ok";
    public static final String KEY_GATEWAY_TCP_CHECKED_WALL_MS = "gateway_tcp_checked_wall_ms";
    public static final String KEY_HEALTH_CHECK_INTERVAL_SEC = "health_check_interval_sec";
    private static long processStartWallMs = 0L;
    private static long processStartElapsedMs = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject configJson;
    private File configFile;
    private File runtimeLogFile;
    private File sysLogFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        configFile = new File(getFilesDir(), "config.json");
        runtimeLogFile = new File(getFilesDir(), "runtime.log");
        sysLogFile = new File(getFilesDir(), "sys.log");
        configJson = loadConfigFromDisk();
        initProcessStartTime();
        scheduleBootMetaLog();

        Button btnGlobalBack = findViewById(R.id.btnGlobalBack);
        Button btnGlobalHome = findViewById(R.id.btnGlobalHome);
        Button btnGlobalRecents = findViewById(R.id.btnGlobalRecents);

        btnGlobalBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        btnGlobalHome.setOnClickListener(v -> {
            moveTaskToBack(true);
            appendRuntimeLog("[APP] 触发主页键（moveTaskToBack）");
        });
        btnGlobalRecents.setOnClickListener(v -> {
            clearRuntimeLog();
            clearSysLog();
            Toast.makeText(this, "已清空日志", Toast.LENGTH_SHORT).show();
        });

        ViewPager2 viewPager = findViewById(R.id.viewPager);
        TabLayout tabLayout = findViewById(R.id.tabLayout);
        viewPager.setAdapter(new MainPagerAdapter(this));
        String[] titles = {"主页", "日志", "Sys日志"};
        for (String title : titles) {
            tabLayout.addTab(tabLayout.newTab().setText(title));
        }

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                TabLayout.Tab tab = tabLayout.getTabAt(position);
                if (tab != null && !tab.isSelected()) {
                    tab.select();
                }
            }
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (viewPager.getCurrentItem() != position) {
                    // 关闭动画，页签点击时瞬间切换
                    viewPager.setCurrentItem(position, false);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    public synchronized String getGatewayIp() {
        JSONObject gateway = configJson.optJSONObject("gateway");
        return gateway == null ? "" : gateway.optString("ip", "");
    }

    public synchronized String getBemfaKey() {
        JSONObject bemfa = configJson.optJSONObject("bemfa");
        return bemfa == null ? "" : bemfa.optString("key", "");
    }

    public synchronized JSONArray getLightsArrayCopy() {
        JSONArray src = configJson.optJSONArray("lights");
        if (src == null) return new JSONArray();
        try {
            return new JSONArray(src.toString());
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public synchronized JSONArray getAcsArrayCopy() {
        JSONArray src = configJson.optJSONArray("acs");
        if (src == null) return new JSONArray();
        try {
            return new JSONArray(src.toString());
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public synchronized JSONArray getHeatsArrayCopy() {
        JSONArray src = configJson.optJSONArray("heats");
        if (src == null) return new JSONArray();
        try {
            return new JSONArray(src.toString());
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public synchronized void updateGatewayAndKey(String ip, String key) {
        JSONObject gateway = configJson.optJSONObject("gateway");
        if (gateway == null) gateway = new JSONObject();
        try {
            gateway.put("ip", ip);
            gateway.put("port", gateway.optInt("port", 4196));
            configJson.put("gateway", gateway);
        } catch (JSONException ignored) {
        }

        JSONObject bemfa = configJson.optJSONObject("bemfa");
        if (bemfa == null) bemfa = new JSONObject();
        try {
            bemfa.put("enabled", true);
            bemfa.put("broker", bemfa.optString("broker", "bemfa.com"));
            bemfa.put("port", bemfa.optInt("port", 9501));
            bemfa.put("key", key);
            configJson.put("bemfa", bemfa);
        } catch (JSONException ignored) {
        }
    }

    public synchronized void updateLightParams(int index, String devNo, String devCh) {
        JSONArray lights = configJson.optJSONArray("lights");
        if (lights == null || index < 0 || index >= lights.length()) return;
        JSONObject item = lights.optJSONObject(index);
        if (item == null) return;
        try {
            item.put("dev_no", devNo);
            item.put("dev_ch", devCh);
        } catch (JSONException ignored) {
        }
    }

    public synchronized void saveConfigToDisk() throws Exception {
        ensureConfigSkeleton();
        writeJson(configFile, configJson);
        appendRuntimeLog("[APP] 配置已写入 config.json");
    }

    public synchronized String readConfigRawText() throws Exception {
        if (configFile.exists()) {
            JSONObject root = readJson(configFile);
            return root.toString(2);
        }
        ensureConfigSkeleton();
        writeJson(configFile, configJson);
        return configJson.toString(2);
    }

    public synchronized void saveConfigRawText(String rawText) throws Exception {
        JSONObject root = new JSONObject(rawText);
        ensureConfigSkeleton(root);
        writeJson(configFile, root);
        configJson = root;
        appendRuntimeLog("[APP] 文本配置已保存到 config.json");
    }

    public synchronized void resetConfigFromExample() throws Exception {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        Python py = Python.getInstance();
        PyObject pathObj = py.getModule("app_entry").callAttr("reset_config_from_example");
        configJson = loadConfigFromDisk();
        ensureConfigSkeleton();
        writeJson(configFile, configJson);
        appendRuntimeLog("[APP] 已从 config.example.json 重置配置: " + (pathObj == null ? "" : pathObj.toString()));
    }

    public String syncDevicesToBemfaCloud(String bemfaKey) throws Exception {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        Python py = Python.getInstance();
        PyObject result = py.getModule("app_entry").callAttr("sync_devices_to_bemfa_cloud", bemfaKey == null ? "" : bemfaKey);
        String text = result == null ? "" : result.toString();
        appendRuntimeLog("[APP] 一键添加设备到云结果: " + text);
        return text;
    }

    public synchronized String getConfigStatusText() {
        int lights = configJson.optJSONArray("lights") == null ? 0 : configJson.optJSONArray("lights").length();
        int acs = configJson.optJSONArray("acs") == null ? 0 : configJson.optJSONArray("acs").length();
        int heats = configJson.optJSONArray("heats") == null ? 0 : configJson.optJSONArray("heats").length();
        return "config: " + configFile.getAbsolutePath() + " | exists=" + configFile.exists()
            + " | lights=" + lights + " | acs=" + acs + " | heats=" + heats;
    }

    public synchronized String getAppRunStatusText() {
        if (processStartWallMs <= 0 || processStartElapsedMs <= 0) {
            return "应用运行状态：未知";
        }
        String launchTime = formatWallTime(processStartWallMs);
        long runningMs = Math.max(0, SystemClock.elapsedRealtime() - processStartElapsedMs);
        return "首次冷启动: " + launchTime + " | 已运行: " + formatDuration(runningMs);
    }

    public synchronized String getNetworkStatusText() {
        boolean connected = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_NET_CONNECTED, false);
        long connectedSinceWall = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_NET_CONNECTED_SINCE_WALL_MS, 0L);
        long lastConnectedWall = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_NET_LAST_CONNECTED_WALL_MS, 0L);
        long lastDurationMs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_NET_LAST_DURATION_MS, 0L);

        if (lastConnectedWall <= 0L && connectedSinceWall <= 0L) {
            return "网络连接状态：尚未检测到联网";
        }

        long shownConnectedWall = lastConnectedWall > 0L ? lastConnectedWall : connectedSinceWall;
        String connectedTime = formatWallTime(shownConnectedWall);
        if (connected && connectedSinceWall > 0L) {
            long duration = Math.max(0, System.currentTimeMillis() - connectedSinceWall);
            return "上次连网: " + connectedTime + " | 本次已持续: " + formatDuration(duration);
        }
        return "上次连网: " + connectedTime + " | 上次持续: " + formatDuration(lastDurationMs) + " | 当前: 未连接";
    }

    public synchronized String getLogDirStatusText() {
        String runtimePath = runtimeLogFile == null ? "未知" : runtimeLogFile.getAbsolutePath();
        String sysPath = sysLogFile == null ? "未知" : sysLogFile.getAbsolutePath();
        return "日志文件：runtime=" + runtimePath + " | sys=" + sysPath;
    }

    public String getBootAutoStartStatusText() {
        boolean enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_AUTO_START, false);
        return "开机自启状态：" + (enabled ? "已开启" : "未开启");
    }

    public String getBridgeAutoConnectStatusText() {
        boolean connected = isBridgeConnected();
        String connectedText = connected ? "已连接巴法云" : "未连接巴法云";
        long updatedAt = getBridgeStatusUpdatedAt();
        String updateText = updatedAt > 0 ? formatWallTime(updatedAt) : "未知";

        boolean retryActive = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RETRY_ACTIVE, false);
        long endWallMs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_AUTO_RETRY_END_WALL_MS, 0L);

        if (retryActive && endWallMs > 0) {
            long remainingMs = Math.max(0, endWallMs - System.currentTimeMillis());
            return "巴法云连接状态：" + connectedText + " | 自动重试窗口剩余: " + formatDuration(remainingMs) + " | 状态更新时间: " + updateText;
        }
        return "巴法云连接状态：" + connectedText + " | 自动重试窗口: 未开启 | 状态更新时间: " + updateText;
    }

    public String getServiceRealtimeStatusText() {
        boolean serviceAlive = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ALIVE, false);
        boolean workerRunning = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_BRIDGE_WORKER_RUNNING, false);
        long gatewayCheckedAt = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_GATEWAY_TCP_CHECKED_WALL_MS, 0L);
        boolean gatewayOk = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_GATEWAY_TCP_OK, false);

        boolean cloudConnected = isBridgeConnected();
        long cloudUpdatedAt = getBridgeStatusUpdatedAt();

        String serviceText = serviceAlive ? "运行中" : "未运行";
        String workerText = workerRunning ? "桥接线程运行中" : "桥接线程未运行";
        String cloudText = cloudConnected ? "已连接" : "未连接";
        String cloudUpdateText = cloudUpdatedAt > 0 ? formatWallTime(cloudUpdatedAt) : "未知";
        String gatewayText;
        if (gatewayCheckedAt <= 0L) {
            gatewayText = "未检测";
        } else {
            gatewayText = (gatewayOk ? "可达" : "不可达") + "@" + formatWallTime(gatewayCheckedAt);
        }

        return "服务状态\n"
            + "服务进程: " + serviceText + "\n"
            + "桥接线程: " + workerText + "\n"
            + "云桥接: " + cloudText + " (更新时间: " + cloudUpdateText + ")\n"
            + "网关TCP: " + gatewayText;
    }

    public void startBridge(String ip, String key) {
        Intent intent = new Intent(this, PythonRunnerService.class);
        intent.setAction(PythonRunnerService.ACTION_START);
        intent.putExtra(PythonRunnerService.EXTRA_GATEWAY_IP, ip);
        intent.putExtra(PythonRunnerService.EXTRA_BEMFA_KEY, key);
        // 用户在前台主动点击时，直接 startService 更不容易触发 FGS 启动时机限制。
        startService(intent);
        setServiceAutoStartEnabled(true);
        appendRuntimeLog("[APP] 启动服务 gateway_ip=" + ip + ", key_len=" + key.length());
    }

    public void stopBridge() {
        Intent intent = new Intent(this, PythonRunnerService.class);
        intent.setAction(PythonRunnerService.ACTION_STOP);
        startService(intent);
        setServiceAutoStartEnabled(false);
        appendRuntimeLog("[APP] 停止服务请求已发送");
    }

    public void restartBridge(String ip, String key) {
        stopBridge();
        handler.postDelayed(() -> startBridge(ip, key), 450);
    }

    public void controlDevice(String deviceName, boolean on) {
        String gatewayIp = getGatewayIp();
        Intent intent = new Intent(this, PythonRunnerService.class);
        intent.setAction(PythonRunnerService.ACTION_DEVICE_CONTROL);
        intent.putExtra(PythonRunnerService.EXTRA_GATEWAY_IP, gatewayIp);
        intent.putExtra(PythonRunnerService.EXTRA_DEVICE_NAME, deviceName);
        intent.putExtra(PythonRunnerService.EXTRA_DEVICE_ON, on);
        startService(intent);
        appendRuntimeLog("[APP] 设备控制 name=" + deviceName + ", action=" + (on ? "on" : "off"));
    }

    public void startHealthCheckLoopFromUi(int intervalSec) {
        int safeSec = Math.max(5, intervalSec);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_HEALTH_CHECK_INTERVAL_SEC, safeSec)
            .apply();
        Intent intent = new Intent(this, PythonRunnerService.class);
        intent.setAction(PythonRunnerService.ACTION_HEALTH_LOOP_START);
        intent.putExtra(PythonRunnerService.EXTRA_HEALTH_INTERVAL_SEC, safeSec);
        startService(intent);
    }

    public void stopHealthCheckLoopFromUi() {
        Intent intent = new Intent(this, PythonRunnerService.class);
        intent.setAction(PythonRunnerService.ACTION_HEALTH_LOOP_STOP);
        startService(intent);
    }

    public int getHealthCheckIntervalSec() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_HEALTH_CHECK_INTERVAL_SEC, 120);
    }

    public void applyKeepAlivePolicies() {
        requestIgnoreBatteryOptimizations();
        applyRootKeepAliveCommands();
    }

    public synchronized String readRuntimeLog() {
        if (!runtimeLogFile.exists()) {
            return "日志文件还未生成。启动服务后会显示运行日志。";
        }
        ensureLogWithinLimit();
        try (FileInputStream fis = new FileInputStream(runtimeLogFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    public synchronized String readSysLog() {
        if (!sysLogFile.exists()) {
            return "Sys日志还未生成。系统网络/Doze 状态变化后会显示记录。";
        }
        ensureSysLogWithinLimit();
        try (FileInputStream fis = new FileInputStream(sysLogFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "读取Sys日志失败: " + e.getMessage();
        }
    }

    public synchronized void clearRuntimeLog() {
        try (FileOutputStream fos = new FileOutputStream(runtimeLogFile, false)) {
            fos.write("".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public synchronized void clearSysLog() {
        try (FileOutputStream fos = new FileOutputStream(sysLogFile, false)) {
            fos.write("".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private synchronized void appendRuntimeLog(String msg) {
        ensureLogWithinLimit();
        try (FileOutputStream fos = new FileOutputStream(runtimeLogFile, true)) {
            fos.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private synchronized void appendSysLog(String msg) {
        ensureSysLogWithinLimit();
        String line = formatWallTime(System.currentTimeMillis()) + " [SYS] " + msg + "\n";
        try (FileOutputStream fos = new FileOutputStream(sysLogFile, true)) {
            fos.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            String pkg = getPackageName();
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + pkg));
                startActivity(intent);
                appendRuntimeLog("[APP] 已拉起电池优化白名单授权页面");
            } else {
                appendRuntimeLog("[APP] 电池优化白名单已授权");
            }
        } catch (Exception e) {
            appendRuntimeLog("[APP] 拉起白名单授权失败: " + e.getMessage());
        }
    }

    private void applyRootKeepAliveCommands() {
        new Thread(() -> {
            String pkg = getPackageName();
            int uid = getApplicationInfo().uid;
            List<String> commands = new ArrayList<>();
            commands.add("cmd deviceidle whitelist +" + pkg);
            commands.add("dumpsys deviceidle whitelist +" + pkg);
            commands.add("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow");
            commands.add("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");
            commands.add("am set-inactive " + pkg + " false");
            commands.add("cmd netpolicy add restrict-background-whitelist " + uid);
            commands.add("settings put global app_standby_enabled 0");
            if (Build.VERSION.SDK_INT >= 29) {
                commands.add("cmd activity set-standby-bucket " + pkg + " active");
            }

            for (String cmd : commands) {
                int code = runSu(cmd);
                appendRuntimeLog("[ROOT] " + cmd + " => exit=" + code);
            }
        }).start();
    }

    private int runSu(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor();
        } catch (Exception e) {
            appendRuntimeLog("[ROOT] 执行失败: " + command + ", error=" + e.getMessage());
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private synchronized void ensureLogWithinLimit() {
        if (!runtimeLogFile.exists()) return;
        if (countLogLines(runtimeLogFile) > MAX_LOG_LINES) {
            clearRuntimeLog();
        }
    }

    private synchronized void ensureSysLogWithinLimit() {
        if (!sysLogFile.exists()) return;
        if (countLogLines(sysLogFile) > MAX_SYS_LOG_LINES) {
            clearSysLog();
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

    private synchronized JSONObject loadConfigFromDisk() {
        if (!configFile.exists()) {
            return createDefaultConfig();
        }
        try {
            JSONObject root = readJson(configFile);
            ensureConfigSkeleton(root);
            return root;
        } catch (Exception ignored) {
            return createDefaultConfig();
        }
    }

    private JSONObject createDefaultConfig() {
        JSONObject root = new JSONObject();
        try {
            root.put("gateway", new JSONObject().put("ip", "").put("port", 4196));
            root.put("bemfa", new JSONObject().put("enabled", true).put("broker", "bemfa.com").put("port", 9501).put("key", ""));
            root.put("lights", new JSONArray());
            root.put("acs", new JSONArray());
            root.put("heats", new JSONArray());
            root.put("fresh_airs", new JSONArray());
            root.put("scenes", new JSONObject().put("hardware", new JSONArray()).put("software", new JSONObject()));
        } catch (JSONException ignored) {
        }
        return root;
    }

    private void ensureConfigSkeleton() throws Exception {
        ensureConfigSkeleton(configJson);
    }

    private void ensureConfigSkeleton(JSONObject root) throws Exception {
        if (!root.has("gateway")) root.put("gateway", new JSONObject().put("ip", "").put("port", 4196));
        if (!root.has("bemfa")) root.put("bemfa",
            new JSONObject().put("enabled", true).put("broker", "bemfa.com").put("port", 9501).put("key", ""));
        if (!root.has("lights")) root.put("lights", new JSONArray());
        if (!root.has("acs")) root.put("acs", new JSONArray());
        if (!root.has("heats")) root.put("heats", new JSONArray());
        if (!root.has("fresh_airs")) root.put("fresh_airs", new JSONArray());
        if (!root.has("scenes")) root.put("scenes", new JSONObject().put("hardware", new JSONArray()).put("software", new JSONObject()));
        root.remove("fresh_air");
    }

    private JSONObject readJson(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return new JSONObject(new String(baos.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private void writeJson(File file, JSONObject root) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void setServiceAutoStartEnabled(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_AUTO_START, enabled)
            .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private synchronized void initProcessStartTime() {
        if (processStartWallMs == 0L || processStartElapsedMs == 0L) {
            processStartWallMs = System.currentTimeMillis();
            processStartElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    private void scheduleBootMetaLog() {
        handler.postDelayed(() -> tryLogBootMetaWithRetry(0), 2000L);
    }

    private void tryLogBootMetaWithRetry(int attempt) {
        if (attempt > 10 || processStartWallMs <= 0L) return;

        long lastLoggedAt = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_BOOT_META_LOGGED_AT, 0L);
        if (lastLoggedAt == processStartWallMs) return;

        boolean configReady = configFile != null && configFile.exists();
        if (!configReady && attempt < 10) {
            handler.postDelayed(() -> tryLogBootMetaWithRetry(attempt + 1), 1500L);
            return;
        }

        appendSysLog("冷启动信息 | " + getConfigStatusText());
        appendSysLog("冷启动信息 | " + getLogDirStatusText());
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_BOOT_META_LOGGED_AT, processStartWallMs)
            .apply();
    }

    private String formatWallTime(long timeMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(timeMs));
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private boolean isBridgeConnected() {
        try {
            File statusFile = new File(getFilesDir(), "bridge_status.json");
            if (!statusFile.exists()) return false;
            JSONObject root = readJson(statusFile);
            return root.optBoolean("connected", false);
        } catch (Exception e) {
            return false;
        }
    }

    private long getBridgeStatusUpdatedAt() {
        try {
            File statusFile = new File(getFilesDir(), "bridge_status.json");
            if (!statusFile.exists()) return 0L;
            JSONObject root = readJson(statusFile);
            long sec = root.optLong("updated_at", 0L);
            return sec > 0 ? sec * 1000L : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
