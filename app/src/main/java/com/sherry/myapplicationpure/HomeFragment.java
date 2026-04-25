package com.sherry.myapplicationpure;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

public class HomeFragment extends Fragment {

    private EditText etGatewayIp;
    private EditText etBemfaKey;
    private TextView tvAppRunStatus;
    private TextView tvNetworkStatus;
    private TextView tvStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable logPoller = new Runnable() {
        @Override
        public void run() {
            MainActivity activity = main();
            if (activity != null) {
                if (tvAppRunStatus != null) {
                    tvAppRunStatus.setText(activity.getAppRunStatusText());
                }
                if (tvNetworkStatus != null) {
                    tvNetworkStatus.setText(activity.getNetworkStatusText());
                }
                if (tvStatus != null) {
                    tvStatus.setText(activity.getServiceRealtimeStatusText());
                }
            }
            handler.postDelayed(this, 1200);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        etGatewayIp = view.findViewById(R.id.etGatewayIp);
        etBemfaKey = view.findViewById(R.id.etBemfaKey);
        tvAppRunStatus = view.findViewById(R.id.tvAppRunStatus);
        tvNetworkStatus = view.findViewById(R.id.tvNetworkStatus);
        tvStatus = view.findViewById(R.id.tvStatus);

        Button btnStart = view.findViewById(R.id.btnStart);
        Button btnStop = view.findViewById(R.id.btnStop);
        Button btnResetConfig = view.findViewById(R.id.btnResetConfig);
        Button btnSyncCloudDevices = view.findViewById(R.id.btnSyncCloudDevices);

        loadInputsFromConfig();
        tvAppRunStatus.setText(main() == null ? "应用运行状态：未知" : main().getAppRunStatusText());
        tvNetworkStatus.setText(main() == null ? "网络连接状态：未知" : main().getNetworkStatusText());
        tvStatus.setText(main() == null ? "服务状态：未知" : main().getServiceRealtimeStatusText());

        btnStart.setOnClickListener(v -> {
            String ip = currentIp();
            String key = currentKey();
            if (!validate(ip, key)) return;
            MainActivity activity = main();
            if (activity != null) {
                try {
                    activity.updateGatewayAndKey(ip, key);
                    activity.saveConfigToDisk();
                    activity.restartBridge(ip, key);
                    Toast.makeText(requireContext(), "已保存配置并重启服务", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        btnStop.setOnClickListener(v -> {
            MainActivity activity = main();
            if (activity != null) {
                activity.stopBridge();
                Toast.makeText(requireContext(), "已发送停止请求", Toast.LENGTH_SHORT).show();
            }
        });

        btnResetConfig.setOnClickListener(v -> {
            MainActivity activity = main();
            if (activity != null) {
                try {
                    activity.resetConfigFromExample();
                    loadInputsFromConfig();
                    Toast.makeText(requireContext(), "已用 config.example.json 完全覆盖 config.json", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "重置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        btnSyncCloudDevices.setOnClickListener(v -> {
            String ip = currentIp();
            String key = currentKey();
            if (!validate(ip, key)) return;
            MainActivity activity = main();
            if (activity == null) return;

            btnSyncCloudDevices.setEnabled(false);
            Toast.makeText(requireContext(), "正在同步设备到巴法云...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    activity.updateGatewayAndKey(ip, key);
                    activity.saveConfigToDisk();
                    String result = activity.syncDevicesToBemfaCloud(key);
                    JSONObject obj = new JSONObject(result);
                    int added = obj.optInt("added", 0);
                    int skipped = obj.optInt("skipped", 0);
                    int failed = obj.optInt("failed", 0);
                    JSONArray detail = obj.optJSONArray("detail");
                    StringBuilder addedNames = new StringBuilder();
                    if (detail != null) {
                        for (int i = 0; i < detail.length(); i++) {
                            JSONObject item = detail.optJSONObject(i);
                            if (item == null) continue;
                            if (!"created".equals(item.optString("status", ""))) continue;
                            String name = item.optString("name", "");
                            if (name.isEmpty()) continue;
                            if (addedNames.length() > 0) addedNames.append("、");
                            addedNames.append(name);
                        }
                    }

                    String msg = "同步完成: 新增" + added + " 跳过" + skipped + " 失败" + failed;
                    String addedText = addedNames.length() == 0 ? "无" : addedNames.toString();
                    String statusText = msg + " | 新增设备: " + addedText;
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            btnSyncCloudDevices.setEnabled(true);
                            Toast.makeText(requireContext(), statusText, Toast.LENGTH_LONG).show();
                        });
                    }
                } catch (Exception e) {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            btnSyncCloudDevices.setEnabled(true);
                            Toast.makeText(requireContext(), "设备上云失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                }
            }).start();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        loadInputsFromConfig();
        handler.post(logPoller);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(logPoller);
        super.onPause();
    }

    private MainActivity main() {
        return (MainActivity) getActivity();
    }

    private void loadInputsFromConfig() {
        MainActivity activity = main();
        if (activity == null || etGatewayIp == null || etBemfaKey == null) return;
        etGatewayIp.setText(activity.getGatewayIp());
        etBemfaKey.setText(activity.getBemfaKey());
    }

    private boolean validate(String ip, String key) {
        if (ip.isEmpty()) {
            Toast.makeText(requireContext(), "请先填写网关 IP", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (key.isEmpty()) {
            Toast.makeText(requireContext(), "请先填写 Bemfa 私钥", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private String currentIp() {
        return etGatewayIp.getText() == null ? "" : etGatewayIp.getText().toString().trim();
    }

    private String currentKey() {
        return etBemfaKey.getText() == null ? "" : etBemfaKey.getText().toString().trim();
    }
}
