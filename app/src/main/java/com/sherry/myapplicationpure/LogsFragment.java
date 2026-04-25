package com.sherry.myapplicationpure;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class LogsFragment extends Fragment {

    private TextView tvLogs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null && tvLogs != null) {
                tvLogs.setText(activity.readRuntimeLog());
            }
            handler.postDelayed(this, 1200);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvLogs = view.findViewById(R.id.tvLogs);
        Button btnClearLogs = view.findViewById(R.id.btnClearLogs);

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            tvLogs.setText(activity.readRuntimeLog());
        }

        btnClearLogs.setOnClickListener(v -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) {
                act.clearRuntimeLog();
                tvLogs.setText(act.readRuntimeLog());
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
