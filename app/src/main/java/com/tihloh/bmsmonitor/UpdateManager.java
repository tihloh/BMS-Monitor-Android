package com.tihloh.bmsmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mirrors POS' GitHub-release checker and internal PackageInstaller flow.
 *  No third-party update service, no APK left in Downloads.
 */
public final class UpdateManager {
    static final String LATEST_URL =
            "https://api.github.com/repos/tihloh/BMS-Monitor-Android/releases/latest";
    public static final String PREFS = "bms_updates";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean checkRunning;
    private String shownVersion;

    public static final class Info {
        public final String version;
        public final String notes;
        public final String downloadUrl;
        Info(String version, String notes, String downloadUrl) {
            this.version = version; this.notes = notes; this.downloadUrl = downloadUrl;
        }
    }

    public UpdateManager(Activity activity) { this.activity = activity; }

    public static Info fetchAvailable(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST_URL).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "BMSMonitorAndroid/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            if (connection.getResponseCode() != 200) return null;
            byte[] raw;
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1 && out.size() < 128000) out.write(chunk, 0, n);
                raw = out.toByteArray();
            }
            JSONObject data = new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            String version = data.optString("tag_name", "").replaceFirst("^v", "");
            if (!newer(version, BuildConfig.VERSION_NAME)) return null;
            JSONArray assets = data.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (name.endsWith(".apk") && url.startsWith(
                        "https://github.com/tihloh/BMS-Monitor-Android/releases/download/")) {
                    return new Info(version, data.optString("body", ""), url);
                }
            }
        } catch (Exception ignored) {
            // Connection errors must not prevent offline monitoring.
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    static boolean newer(String remote, String local) {
        if (!remote.matches("\\d+(\\.\\d+){0,3}")) return false;
        String[] a = remote.split("\\."), b = local.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bi = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (ai != bi) return ai > bi;
        }
        return false;
    }

    public void check(boolean manual) {
        if (checkRunning) return;
        checkRunning = true;
        EXECUTOR.execute(() -> {
            Info available = fetchAvailable(activity.getApplicationContext());
            ui.post(() -> {
                checkRunning = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (available == null) {
                    if (manual) android.widget.Toast.makeText(activity,
                            "No newer compatible release found, or you're offline.",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                if (!manual && available.version.equals(shownVersion)) return;
                shownVersion = available.version;
                showPrompt(available);
            });
        });
    }

    private void showPrompt(Info info) {
        String body = "Version " + info.version + " is available.\n\n" +
                (info.notes.length() > 500 ? info.notes.substring(0, 500) + "…" : info.notes) +
                "\n\nAndroid may ask you to confirm the installation.";
        new AlertDialog.Builder(activity)
                .setTitle("BMS Monitor update")
                .setMessage(body)
                .setNegativeButton("Later", (d, w) -> {})
                .setPositiveButton("Update", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= 26 &&
                            !activity.getPackageManager().canRequestPackageInstalls()) {
                        android.widget.Toast.makeText(activity,
                                "Allow app installs for BMS Monitor, then check for updates again.",
                                android.widget.Toast.LENGTH_LONG).show();
                        Intent request = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(request);
                    } else {
                        installAsync(activity.getApplicationContext(), info, message ->
                                ui.post(() -> {
                                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                                        android.widget.Toast.makeText(activity, message,
                                                android.widget.Toast.LENGTH_LONG).show();
                                    }
                                }));
                    }
                }).show();
    }

    public interface ResultCallback { void onResult(String message); }

    /** Streams APK into a PackageInstaller session, avoiding public Downloads. */
    public static void installAsync(Context context, Info info, ResultCallback callback) {
        EXECUTOR.execute(() -> {
            String result = stageInstall(context, info);
            if (callback != null) callback.onResult(result);
        });
    }

    static String stageInstall(Context context, Info info) {
        if (info == null || info.downloadUrl == null) return "Update asset unavailable";
        HttpURLConnection connection = null;
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int sessionId = -1;
        try {
            connection = (HttpURLConnection) new URL(info.downloadUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "BMSMonitorAndroid/" + info.version);
            if (connection.getResponseCode() != 200)
                throw new IllegalStateException("Download failed: HTTP " + connection.getResponseCode());
            long size = connection.getContentLengthLong();
            if (size > 50L * 1024 * 1024)
                throw new IllegalStateException("APK exceeds 50 MB limit");
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            if (Build.VERSION.SDK_INT >= 31)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = connection.getInputStream();
                     OutputStream out = session.openWrite("base.apk", 0, size > 0 ? size : -1)) {
                    byte[] buffer = new byte[32768];
                    long total = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > 50L * 1024 * 1024) throw new IllegalStateException("APK too large");
                        out.write(buffer, 0, read);
                    }
                    session.fsync(out);
                }
                Intent intent = new Intent(context, UpdateInstallReceiver.class)
                        .setAction(UpdateInstallReceiver.ACTION);
                PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
            }
            return "Update downloaded. Complete the Android installation prompt.";
        } catch (Exception e) {
            if (sessionId != -1) {
                try { installer.abandonSession(sessionId); } catch (Exception ignored) {}
            }
            return "Update failed: " + e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
