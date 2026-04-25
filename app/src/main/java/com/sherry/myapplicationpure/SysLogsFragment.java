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

public class SysLogsFragment extends Fragment {

    private TextView tvSysLogs;
    private EditText etHealthIntervalSec;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null && tvSysLogs != null) {
                tvSysLogs.setText(activity.readSysLog());
            }
            handler.postDelayed(this, 1200);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sys_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvSysLogs = view.findViewById(R.id.tvSysLogs);
        etHealthIntervalSec = view.findViewById(R.id.etHealthIntervalSec);
        Button btnClearSysLogs = view.findViewById(R.id.btnClearSysLogs);
        Button btnStartHealthLoop = view.findViewById(R.id.btnStartHealthLoop);
        Button btnStopHealthLoop = view.findViewById(R.id.btnStopHealthLoop);

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            tvSysLogs.setText(activity.readSysLog());
            if (etHealthIntervalSec != null) {
                etHealthIntervalSec.setText(String.valueOf(activity.getHealthCheckIntervalSec()));
            }
        }

        btnClearSysLogs.setOnClickListener(v -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) {
                act.clearSysLog();
                tvSysLogs.setText(act.readSysLog());
            }
        });

        btnStartHealthLoop.setOnClickListener(v -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) {
                int sec = 120;
                try {
                    String text = etHealthIntervalSec != null && etHealthIntervalSec.getText() != null
                        ? etHealthIntervalSec.getText().toString().trim()
                        : "";
                    if (!text.isEmpty()) {
                        sec = Integer.parseInt(text);
                    }
                } catch (Exception ignored) {
                }
                if (sec < 5) sec = 5;
                if (etHealthIntervalSec != null) {
                    etHealthIntervalSec.setText(String.valueOf(sec));
                }
                act.startHealthCheckLoopFromUi(sec);
                Toast.makeText(requireContext(), "已发送巡检启动/重启请求", Toast.LENGTH_SHORT).show();
            }
        });

        btnStopHealthLoop.setOnClickListener(v -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) {
                act.stopHealthCheckLoopFromUi();
                Toast.makeText(requireContext(), "已发送巡检停止请求", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(poller);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(poller);
        super.onPause();
    }
}
