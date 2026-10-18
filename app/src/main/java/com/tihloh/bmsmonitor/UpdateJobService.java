package com.tihloh.bmsmonitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/** Hourly best-effort GitHub Release checks; the user approves installation in the app. */
public final class UpdateJobService extends JobService {
    private static final int JOB_ID = 89110;
    private static final String CHANNEL = "bms_monitor_app_updates";
    private volatile boolean stopped;

    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        for (JobInfo existing : scheduler.getAllPendingJobs()) {
            if (existing.getId() == JOB_ID) return; // avoid resetting the periodic timer
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(60L * 60L * 1000L)
                .build();
        scheduler.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        if (BuildConfig.DEBUG) { jobFinished(params, false); return false; }
        new Thread(() -> {
            try {
                String version = findNewVersion();
                if (!stopped && version != null) notifyUser(version);
            } finally {
                jobFinished(params, false);
            }
        }, "BMS-hourly-update-check").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }

    private String findNewVersion() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/tihloh/BMS-Monitor-Android/releases?per_page=20"
            ).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "BMS-Monitor/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] buf = new byte[4096]; int length;
                while ((length = stream.read(buf)) != -1) {
                    if (out.size() + length > 180000) return null;
                    out.write(buf, 0, length);
                }
            }
            JSONArray releases = new JSONArray(out.toString("UTF-8"));
            String newest = null;
            for (int i=0;i<releases.length();i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                String version = release.optString("tag_name").replaceFirst("^v","");
                if (!version.matches("\\d+\\.\\d+\\.\\d+")) continue;
                JSONArray assets = release.optJSONArray("assets");
                boolean apk = false;
                if (assets != null) for(int j=0;j<assets.length();j++) {
                    JSONObject a = assets.optJSONObject(j);
                    if (a != null && a.optString("name").endsWith(".apk") && !a.optString("name").toLowerCase(java.util.Locale.US).contains("debug")) apk = true;
                }
                if (apk && newer(version, BuildConfig.VERSION_NAME)
                        && (newest == null || newer(version, newest))) newest = version;
            }
            return newest;
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private boolean newer(String a, String b) {
        String[] x=a.split("\\."), y=b.split("\\.");
        for (int i=0; i<3; i++) {
            int left=i<x.length?Integer.parseInt(x[i]):0;
            int right=i<y.length?Integer.parseInt(y[i]):0;
            if(left!=right)return left>right;
        }
        return false;
    }

    private void notifyUser(String version) {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return;
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notifications == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Battery Monitor updates", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("check_updates_now", true);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent click = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Battery Monitor " + version + " available")
                .setContentText("Tap to review and install the new version")
                .setContentIntent(click)
                .setAutoCancel(true)
                .build();
        notifications.notify(8911, notification);
    }
}
