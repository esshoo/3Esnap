package com.snapclean.app;

import android.app.Application;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.ffmpeg.FFmpeg;

public class SnapCleanApp extends Application {
    private static final String TAG = "SnapCleanApp";

    @Override
    public void onCreate() {
        super.onCreate();
        new Thread(() -> {
            try {
                YoutubeDL.getInstance().init(this);
                FFmpeg.getInstance().init(this);
                Log.d(TAG, "YoutubeDL and FFmpeg initialized successfully on-device!");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize YoutubeDL engine: " + e.getMessage(), e);
            }
        }).start();
    }
}
