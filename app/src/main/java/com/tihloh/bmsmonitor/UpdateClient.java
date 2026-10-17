package com.tihloh.bmsmonitor;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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

/** GitHub release update checker and user-confirmed Android PackageInstaller updater. */
public final class UpdateClient {
    public interface Listener {
        void onAvailable(Release release);
        void onMessage(String message);
    }

    public static final class Release {
        public final String version, notes, downloadUrl;
        Release(String version, String notes, String downloadUrl) {
            this.version = version;
            this.notes = notes;
            this.downloadUrl = downloadUrl;
        }
    }

    private static final String API = "https://api.github.com/repos/tihloh/BMS-Monitor-Android/releases/tags/experimental-latest";
    private static final String TRUSTED_PATH =
            "https://github.com/tihloh/BMS-Monitor-Android/releases/download/";
    private static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private final Context context;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Release available;

    public UpdateClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void check(boolean manual) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            long last = context.getSharedPreferences("updates", 0).getLong("checked", 0);
            if (!manual && now - last < CHECK_INTERVAL_MS) return;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "BMSMonitorAndroid/" + BuildConfig.VERSION_NAME);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(12000);
                if (conn.getResponseCode() != 200) throw new Exception("GitHub HTTP " + conn.getResponseCode());
                StringBuilder body = new StringBuilder();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (body.length() + n > 128000) throw new Exception("Release metadata too large");
                        body.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                JSONObject json = new JSONObject(body.toString());
                String notes = json.optString("body", "");
                String name = json.optString("name", "");
                String version = parseVersion(notes);
                if (version.isEmpty()) version = parseVersion(name);
                if (version.isEmpty()) throw new Exception("Release does not specify versionName");
                JSONArray assets = json.optJSONArray("assets");
                String apk = "";
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && "BMS-Monitor-debug.apk".equals(asset.optString("name"))) {
                        apk = asset.optString("browser_download_url");
                        break;
                    }
                }
                if (!apk.startsWith(TRUSTED_PATH)) throw new Exception("Trusted APK asset not found");
                context.getSharedPreferences("updates", 0).edit().putLong("checked", now).apply();
                if (newer(version, BuildConfig.VERSION_NAME)) {
                    available = new Release(version, notes, apk);
                    Release found = available;
                    main.post(() -> listener.onAvailable(found));
                } else if (manual) message("BMS Monitor is up to date (v" + BuildConfig.VERSION_NAME + ")");
            } catch (Exception e) {
                if (manual) message("Update check failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void install(Release release) {
        if (release == null || !release.downloadUrl.startsWith(TRUSTED_PATH)) {
            message("Untrusted update URL");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            message("Enable Allow from this source, then tap Update again.");
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            if (context instanceof android.app.Activity) ((android.app.Activity) context).startActivity(i);
            return;
        }
        message("Downloading update… Android will ask for your approval.");
        io.execute(() -> {
            HttpURLConnection conn = null;
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            Integer sessionId = null;
            try {
                URL source = new URL(release.downloadUrl);
                conn = (HttpURLConnection) source.openConnection();
                conn.setRequestProperty("User-Agent", "BMSMonitorAndroid/" + BuildConfig.VERSION_NAME);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() != 200) throw new Exception("Download returned HTTP " + conn.getResponseCode());
                long size = conn.getContentLengthLong();
                if (size > 100L * 1024 * 1024) throw new Exception("APK is unexpectedly large");
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                if (Build.VERSION.SDK_INT >= 31)
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = session.openWrite("base.apk", 0, size > 0 ? size : -1)) {
                    byte[] buf = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > 100L * 1024 * 1024) throw new Exception("Download exceeded size limit");
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                    }
                    Intent callback = new Intent(context, UpdateInstallReceiver.class);
                    callback.setAction(UpdateInstallReceiver.ACTION);
                    PendingIntent intent = PendingIntent.getBroadcast(context, sessionId, callback,
                            PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                    session.commit(intent.getIntentSender());
                }
                message("APK prepared — confirm installation if Android prompts you.");
                sessionId = null;
            } catch (Exception e) {
                if (sessionId != null) {
                    try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
                }
                message("Update install failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String parseVersion(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)(?:versionName|version|v)\\s*[:= ]\\s*v?(\\d+\\.\\d+\\.\\d+)").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static boolean newer(String remote, String current) {
        String[] a = remote.split("\\."), b = current.split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private void message(String message) {
        main.post(() -> listener.onMessage(message));
    }

    public void close() { io.shutdownNow(); }
}
