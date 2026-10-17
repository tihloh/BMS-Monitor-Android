package com.tihloh.bmsmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * GitHub Release based, opt-in Android update checker inspired by the owner's POS application.
 * Never silently installs; Android's PackageInstaller requires confirmation.
 */
public final class UpdateClient {
    private static final String API = "https://api.github.com/repos/tihloh/BMS-Monitor-Android/releases?per_page=15";
    private static final String DOWNLOAD_PREFIX =
            "https://github.com/tihloh/BMS-Monitor-Android/releases/download/";
    private static final long INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final long MAX_BYTES = 50L * 1024L * 1024L;
    private final Activity activity;

    public interface Listener { void status(String message); }

    public UpdateClient(Activity activity) { this.activity = activity; }

    public void check(boolean manual, Listener listener) {
        long last = activity.getSharedPreferences("updates", 0).getLong("last", 0L);
        if (!manual && System.currentTimeMillis() - last < INTERVAL_MS) return;
        if (manual) listener.status("Checking GitHub releases…");
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(10000);
                con.setRequestProperty("Accept", "application/vnd.github+json");
                con.setRequestProperty("User-Agent", "BMSMonitorAndroid/" + BuildConfig.VERSION_NAME);
                JSONArray releases;
                try {
                    if (con.getResponseCode() != 200)
                        throw new IllegalStateException("GitHub returned HTTP " + con.getResponseCode());
                    releases = new JSONArray(readText(con.getInputStream()));
                } finally { con.disconnect(); }
                activity.getSharedPreferences("updates", 0).edit().putLong("last", System.currentTimeMillis()).apply();

                JSONObject found = null;
                String bestVersion = BuildConfig.VERSION_NAME;
                String apk = null;
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.optJSONObject(i);
                    if (release == null || release.optBoolean("draft")) continue;
                    String version = release.optString("tag_name").replaceFirst("^[vV]", "");
                    if (compare(version, bestVersion) <= 0) continue;
                    JSONArray assets = release.optJSONArray("assets");
                    String assetUrl = null;
                    if (assets != null) for (int k = 0; k < assets.length(); k++) {
                        JSONObject asset = assets.optJSONObject(k);
                        if (asset != null && asset.optString("name").toLowerCase(Locale.US).endsWith(".apk")) {
                            String link = asset.optString("browser_download_url");
                            long bytes = asset.optLong("size");
                            if (link.startsWith(DOWNLOAD_PREFIX) && bytes > 0 && bytes <= MAX_BYTES) {
                                assetUrl = link;
                                break;
                            }
                        }
                    }
                    if (assetUrl != null) {
                        found = release;
                        bestVersion = version;
                        apk = assetUrl;
                    }
                }
                final JSONObject picked = found;
                final String version = bestVersion, url = apk;
                activity.runOnUiThread(() -> {
                    if (picked == null) {
                        if (manual) listener.status("Up to date · v" + BuildConfig.VERSION_NAME);
                        return;
                    }
                    listener.status("Update available: v" + version);
                    new AlertDialog.Builder(activity)
                            .setTitle("Update BMS Monitor to v" + version + "?")
                            .setMessage("A newer APK is available on this project's GitHub Releases. " +
                                    "Android will request your confirmation before installing. " +
                                    "Updates require APKs signed by the same signing key.")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Download & install", (d, which) -> install(version, url, listener))
                            .show();
                });
            } catch (Exception e) {
                if (manual) activity.runOnUiThread(() ->
                        listener.status("Update check failed: " + e.getMessage()));
            }
        }, "bms-update-check").start();
    }

    private void install(String version, String apkUrl, Listener listener) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("Allow app installation")
                    .setMessage("Android requires permission to install downloaded APK updates. " +
                            "Return to BMS Monitor and tap Check updates again after granting it.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (d,w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    }).show();
            return;
        }
        listener.status("Downloading update v" + version + "…");
        new Thread(() -> {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            try {
                if (!apkUrl.startsWith(DOWNLOAD_PREFIX))
                    throw new SecurityException("Unexpected APK download URL");
                HttpURLConnection con = (HttpURLConnection) new URL(apkUrl).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent", "BMSMonitorAndroid/" + BuildConfig.VERSION_NAME);
                try {
                    int code = con.getResponseCode();
                    if (code != 200) throw new IllegalStateException("Download HTTP " + code);
                    if (con.getContentLengthLong() > MAX_BYTES)
                        throw new IllegalStateException("APK exceeds the permitted download size");
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(activity.getPackageName());
                    if (Build.VERSION.SDK_INT >= 31)
                        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                    sessionId = installer.createSession(params);
                    try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                        try (InputStream input = con.getInputStream();
                             OutputStream output = session.openWrite("base.apk", 0, -1)) {
                        byte[] buf = new byte[16384];
                        int count;
                        long total = 0;
                        while ((count = input.read(buf)) >= 0) {
                            total += count;
                            if (total > MAX_BYTES) throw new IllegalStateException("APK exceeds maximum size");
                            output.write(buf, 0, count);
                        }
                            session.fsync(output);
                        }
                        Intent broadcast = new Intent(activity, UpdateInstallReceiver.class);
                        broadcast.setAction(UpdateInstallReceiver.ACTION_STATUS);
                        PendingIntent pending = PendingIntent.getBroadcast(activity, sessionId,
                                broadcast, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                        session.commit(pending.getIntentSender());
                    }
                    activity.runOnUiThread(() -> listener.status("Update downloaded · waiting for Android installer"));
                } finally { con.disconnect(); }
            } catch (Exception e) {
                if (sessionId >= 0) try { installer.abandonSession(sessionId); }
                    catch (Exception ignored) { }
                activity.runOnUiThread(() -> listener.status("Update failed: " + e.getMessage()));
            }
        }, "bms-apk-installer").start();
    }

    private static String readText(InputStream in) throws Exception {
        try (InputStream stream = in) {
            byte[] bytes = new byte[1048576];
            int used = 0, n;
            while ((n = stream.read(bytes, used, bytes.length - used)) > 0) {
                used += n;
                if (used == bytes.length) throw new IllegalStateException("Release metadata too large");
            }
            return new String(bytes, 0, used, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static int compare(String left, String right) {
        String[] a = left.replaceFirst("^[vV]", "").split("[.\\-]");
        String[] b = right.replaceFirst("^[vV]", "").split("[.\\-]");
        for (int i = 0; i < 3; i++) {
            int n = numeric(a, i), m = numeric(b, i);
            if (n != m) return Integer.compare(n, m);
        }
        return 0;
    }
    private static int numeric(String[] segments, int index) {
        if (index >= segments.length || !segments[index].matches("\\d+")) return 0;
        try { return Integer.parseInt(segments[index]); }
        catch (NumberFormatException e) { return 0; }
    }
}
