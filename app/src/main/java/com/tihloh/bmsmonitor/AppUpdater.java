package com.tihloh.bmsmonitor;

import android.app.Activity;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** POS-style GitHub Release updater. Installation always requires Android approval. */
public final class AppUpdater {
    private static final String LATEST_URL =
            "https://api.github.com/repos/tihloh/BMS-Monitor-Android/releases/latest";
    private static final long INTERVAL = 12 * 60 * 60 * 1000L;
    private static final long MAX_APK = 30L * 1024 * 1024;

    public interface Callback {
        void onAvailable(Update update);
        void onNoUpdate(String reason);
    }

    public interface Progress {
        void onStatus(String message);
    }

    public static final class Update {
        public final String version;
        public final String notes;
        public final String apk;
        Update(String version, String notes, String apk) {
            this.version = version;
            this.notes = notes;
            this.apk = apk;
        }
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AppUpdater(Context context) {
        this.context = context.getApplicationContext();
    }

    public void check(boolean manual, Callback callback) {
        if (!manual) {
            long last = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
                    .getLong("last_check", 0);
            if (System.currentTimeMillis() - last < INTERVAL) return;
        }
        executor.execute(() -> {
            try {
                Update update = fetchLatest(context);
                context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                        .putLong("last_check", System.currentTimeMillis()).apply();
                if (update == null) callback.onNoUpdate("You already have the latest BMS Monitor.");
                else callback.onAvailable(update);
            } catch (Exception e) {
                callback.onNoUpdate("Could not check for updates. Connect to the internet and retry.");
            }
        });
    }

    public static Update fetchLatest(Context ctx) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(LATEST_URL).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "BMS-Monitor-Android/" +
                BuildConfig.VERSION_NAME);
        try {
            if (con.getResponseCode() != 200) throw new IllegalStateException("HTTP " + con.getResponseCode());
            String json = readSmall(con.getInputStream(), 512 * 1024);
            JSONObject root = new JSONObject(json);
            String version = root.optString("tag_name").replaceFirst("^v", "");
            if (!newer(version, BuildConfig.VERSION_NAME)) return null;
            JSONArray assets = root.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name");
                String url = asset.optString("browser_download_url");
                if (name.endsWith(".apk") && url.startsWith(
                        "https://github.com/tihloh/BMS-Monitor-Android/releases/download/")) {
                    return new Update(version, root.optString("body"), url);
                }
            }
            return null;
        } finally {
            con.disconnect();
        }
    }

    static boolean newer(String remote, String local) {
        String[] a = remote.split("[-+]")[0].split("\\.");
        String[] b = local.split("[-+]")[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? numeric(a[i]) : 0;
            int bi = i < b.length ? numeric(b[i]) : 0;
            if (ai != bi) return ai > bi;
        }
        return false;
    }

    private static int numeric(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception ignored) { return 0; }
    }

    private static String readSmall(InputStream input, int limit) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int n;
            while ((n = in.read(block)) > 0) {
                if (out.size() + n > limit) throw new IllegalStateException("JSON response too large");
                out.write(block, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    public void install(Activity activity, Update update, Progress progress) {
        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            progress.onStatus("Allow APK installations, then tap Update again.");
            return;
        }
        progress.onStatus("Downloading update. Android will ask before installation.");
        executor.execute(() -> {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(update.apk).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setRequestProperty("User-Agent", "BMSMonitor/" + BuildConfig.VERSION_NAME);
                if (con.getResponseCode() != 200)
                    throw new IllegalStateException("HTTP " + con.getResponseCode());
                long announcedSize = con.getContentLengthLong();
                if (announcedSize > MAX_APK) throw new IllegalStateException("APK too large");
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                if (Build.VERSION.SDK_INT >= 31)
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId);
                     InputStream in = con.getInputStream();
                     OutputStream out = session.openWrite("base.apk", 0,
                             announcedSize > 0 ? announcedSize : -1)) {
                    byte[] block = new byte[16 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(block)) != -1) {
                        total += n;
                        if (total > MAX_APK) throw new IllegalStateException("APK exceeds size limit");
                        out.write(block, 0, n);
                    }
                    session.fsync(out);
                    Intent intent = new Intent(context, InstallStatusReceiver.class);
                    intent.setAction("com.tihloh.bmsmonitor.INSTALL_STATUS");
                    PendingIntent pi = PendingIntent.getBroadcast(context, sessionId, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(pi.getIntentSender());
                    progress.onStatus("Update downloaded. Confirm installation when Android prompts.");
                }
            } catch (Exception e) {
                if (sessionId != -1) installer.abandonSession(sessionId);
                progress.onStatus("Update failed: " + e.getMessage());
            } finally {
                if (con != null) con.disconnect();
            }
        });
    }
}
