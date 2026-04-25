package com.sherry.myapplicationpure;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        boolean shouldStart =
            Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!shouldStart) return;

        try {
            boolean autoStartEnabled = context
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_SERVICE_AUTO_START, false);
            if (!autoStartEnabled) return;

            File configFile = new File(context.getFilesDir(), "config.json");
            if (!configFile.exists()) return;

            JSONObject root = readJson(configFile);
            JSONObject gateway = root.optJSONObject("gateway");
            JSONObject bemfa = root.optJSONObject("bemfa");
            if (gateway == null || bemfa == null) return;

            String ip = gateway.optString("ip", "").trim();
            String key = bemfa.optString("key", "").trim();
            if (ip.isEmpty() || key.isEmpty()) return;

            Intent serviceIntent = new Intent(context, PythonRunnerService.class);
            serviceIntent.setAction(PythonRunnerService.ACTION_START_AUTO);
            serviceIntent.putExtra(PythonRunnerService.EXTRA_GATEWAY_IP, ip);
            serviceIntent.putExtra(PythonRunnerService.EXTRA_BEMFA_KEY, key);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception ignored) {
        }
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
}
