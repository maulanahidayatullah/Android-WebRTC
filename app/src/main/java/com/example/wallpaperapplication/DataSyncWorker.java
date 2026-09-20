package com.example.wallpaperapplication;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class DataSyncWorker extends Worker {
    private static final String TAG = "DataSyncWorker";

    public DataSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting data sync work...");
        Context context = getApplicationContext();

        // 1. Ensure Service is Running (Boot Hardening)
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs.getBoolean("streaming_enabled", false)) {
                Intent intent = new Intent(context, StreamingService.class);
                ContextCompat.startForegroundService(context, intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart service from worker", e);
        }

        return Result.success();
    }
}
