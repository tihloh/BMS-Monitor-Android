package com.tihloh.bmsmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Update flow based on tihloh/pos-source: GitHub release check and
 * Android PackageInstaller with explicit Android install confirmation.
 * No silent installation, no unknown third-party APK servers.
 */
public final class ReleaseUpdater {
    public interface Callback {
        void onMessage(String text);
        void onVersion(String version);
    }
    private static final String REPO = "tihloh/BMS-Monitor-Android";
    private static final long CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L;
    private static final String PREFS = "bms_updates";
    private static final String LAST_CHECK = "last_check";
    private final Activity activity;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private Release pendingPermission;

    private static final class Release {
        final String version, notes, apkUrl, htmlUrl;
        Release(String version, String notes, String apkUrl, String htmlUrl) {
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.htmlUrl = htmlUrl;
        }
    }

    public ReleaseUpdater(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        cleanupStaleSessions();
    }

    public void check(boolean force) {
        long now = System.currentTimeMillis();
        long last = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(LAST_CHECK, 0);
        if (BuildConfig.DEBUG) {
            callback.onMessage("Development build: in-app production updates are unavailable. New dev APKs require separate installation; see GitHub Releases.");
            return;
        }
        if (!force && now - last < CHECK_INTERVAL_MS) return;
        if (busy) return;
        busy = true;
        callback.onMessage("Checking GitHub releases…");
        new Thread(() -> {
            try {
                Release newest = latestRelease();
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(LAST_CHECK, System.currentTimeMillis()).apply();
                main.post(() -> {
                    busy = false;
                    if (newest == null) {
                        callback.onMessage("No compatible production update available. Development APKs cannot replace production installations.");
                        return;
                    }
                    int diff = compareVersion(newest.version, BuildConfig.VERSION_NAME);
                    callback.onVersion(newest.version);
                    if (diff <= 0) {
                        callback.onMessage("You're using the latest available version.");
                        if (force) Toast.makeText(activity, "Battery Monitor is up to date", Toast.LENGTH_SHORT).show();
                    } else {
                        callback.onMessage("Version " + newest.version + " is available.");
                        install(newest);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    busy = false;
                    callback.onMessage("Update check unavailable. " + e.getClass().getSimpleName());
                });
            }
        }, "BMS-release-check").start();
    }

    private static Release latestRelease() throws Exception {
        // Stable production releases only. Dev/debug APKs have a different package
        // ID and are never valid in-place replacements for this application.
        String body = getText("https://api.github.com/repos/" + REPO + "/releases?per_page=15");
        JSONArray releases = new JSONArray(body);
        Release latest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease")) continue;

            String tag = release.optString("tag_name", "");
            if (!tag.matches("v?\\d+\\.\\d+\\.\\d+")) continue;
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            String assetUrl = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int k = 0; k < assets.length(); k++) {
                    JSONObject asset = assets.optJSONObject(k);
                    if (asset != null && asset.optString("name", "")
                            .matches("Battery-Monitor-v?" + java.util.regex.Pattern.quote(version) + "\\.apk")) {
                        assetUrl = asset.optString("browser_download_url", "");
                        break;
                    }
                }
            }
            if (assetUrl.isEmpty()) continue;
            Release r = new Release(version, release.optString("body", ""), assetUrl,
                    release.optString("html_url", ""));
            if (latest == null || compareVersion(r.version, latest.version) > 0) latest = r;
        }
        return latest;
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "BMSMonitor-Android");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) throw new IllegalStateException("HTTP " + conn.getResponseCode());
            try (InputStream stream = conn.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = stream.read(b)) > 0) {
                    out.write(b, 0, n);
                    if (out.size() > 1024 * 1024) throw new IllegalStateException("Release response too large");
                }
                return out.toString("UTF-8");
            }
        } finally { conn.disconnect(); }
    }

    private static int compareVersion(String a, String b) {
        String[] left = a.replaceFirst("^v", "").split("\\.");
        String[] right = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < left.length ? Integer.parseInt(left[i]) : 0;
            int y = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /** Android manages PackageInstaller session bytes in private storage, not Downloads. */
    private void cleanupStaleSessions() {
        new Thread(() -> {
            try {
                PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
                long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
                for (SessionInfo info : installer.getMySessions()) {
                    if (info.getCreatedMillis() < cutoff && !info.isActive()) {
                        installer.abandonSession(info.getSessionId());
                    }
                }
            } catch (Exception ignored) {
                // Stale-session cleanup is best effort.
            }
        }, "BMS-update-cleanup").start();
    }

    private void install(Release release) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingPermission = release;
            new AlertDialog.Builder(activity)
                    .setTitle("Allow app updates")
                    .setMessage("Android needs permission to install updates from Battery Monitor. "
                            + "Enable Allow from this source, then return here.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        activity.startActivity(new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())));
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> pendingPermission = null)
                    .show();
            return;
        }
        pendingPermission = null;
        if (BuildConfig.DEBUG) {
            callback.onMessage("Experimental APK cannot upgrade to a differently signed production APK. One-time reinstall required.");
            new AlertDialog.Builder(activity).setTitle("Production installation")
                    .setMessage("Your current experimental APK may have a different signing key. Android cannot replace it with a production APK. Save any data you need, uninstall the experimental app and install the production release once. Future releases will use the permanent signing key.")
                    .setPositiveButton("View releases", (dialog, which) -> activity.startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + REPO + "/releases"))))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (busy) return;
        busy = true;
        callback.onMessage("Preparing update " + release.version + " internally…");
        new Thread(() -> downloadAndInstall(release), "BMS-apk-installer").start();
    }

    /** Called only when the user returns from Android unknown-app-sources settings. */
    public void onResume() {
        if (pendingPermission != null && activity.getPackageManager().canRequestPackageInstalls()) {
            Release r = pendingPermission;
            pendingPermission = null;
            install(r);
        }
    }

    private void downloadAndInstall(Release release) {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        Integer sessionId = null;
        HttpURLConnection conn = null;
        File pendingApk = null;
        try {
            URL uri = new URL(release.apkUrl);
            if (!"https".equalsIgnoreCase(uri.getProtocol()) ||
                    !"github.com".equalsIgnoreCase(uri.getHost())) {
                throw new SecurityException("Untrusted APK download URL");
            }
            conn = (HttpURLConnection) uri.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "BMSMonitor-Android");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Download HTTP " + status);
            pendingApk = File.createTempFile("bms-update-", ".apk", activity.getCacheDir());
            long actualSize = 0;
            long advertisedSize = conn.getContentLengthLong();
            if (advertisedSize > 100L * 1024 * 1024)
                throw new IOException("Update file is unexpectedly large");
            try (InputStream input = conn.getInputStream();
                 FileOutputStream output = new FileOutputStream(pendingApk)) {
                byte[] bytes = new byte[16384];
                int n;
                while ((n = input.read(bytes)) != -1) {
                    actualSize += n;
                    if (actualSize > 100L * 1024 * 1024)
                        throw new IOException("Update exceeds maximum allowed size");
                    output.write(bytes, 0, n);
                }
                output.getFD().sync();
            }
            if (advertisedSize > 0 && actualSize != advertisedSize)
                throw new IOException("Incomplete APK download: expected " + advertisedSize + " bytes, got " + actualSize);
            validateApk(pendingApk);

            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (FileInputStream input = new FileInputStream(pendingApk);
                     OutputStream output = session.openWrite("base.apk", 0, actualSize)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    session.fsync(output);
                }
                // All session output streams MUST close before committing.
                Intent callbackIntent = new Intent(activity, UpdateInstallReceiver.class);
                callbackIntent.setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS);
                PendingIntent pending = PendingIntent.getBroadcast(activity,
                        sessionId, callbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
                sessionId = null;
                main.post(() -> callback.onMessage("Update prepared. Android may request installation approval."));
            }
        } catch (Exception e) {
            if (sessionId != null) installer.abandonSession(sessionId);
            main.post(() -> {
                callback.onMessage("Update failed: " + e.getMessage());
                new AlertDialog.Builder(activity).setTitle("Update failed")
                        .setMessage("Could not install this update. Ensure it uses the same signing certificate and a higher versionCode.\\n\\n" + e.getMessage())
                        .setPositiveButton("OK", null).show();
            });
        } finally {
            if (conn != null) conn.disconnect();
            if (pendingApk != null && pendingApk.exists() && !pendingApk.delete())
                android.util.Log.w("BMS-Updater", "Could not clean cached APK: " + pendingApk.getName());
            busy = false;
        }
    }
    /** Reject corrupt or incompatible APKs before committing an Android installer session. */
    private void validateApk(File apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            if (zip.getEntry("AndroidManifest.xml") == null)
                throw new IOException("Downloaded file is not a valid Android APK");
        }
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !activity.getPackageName().equals(candidate.packageName)) {
            String actual = candidate == null ? "unrecognized APK" : candidate.packageName;
            throw new IOException("Incorrect APK package: " + actual
                    + ". Expected " + activity.getPackageName()
                    + ". Development APKs cannot upgrade the production app.");
        }
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), flags);
        long candidateCode = Build.VERSION.SDK_INT >= 28
                ? candidate.getLongVersionCode() : candidate.versionCode;
        long installedCode = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode() : installed.versionCode;
        if (candidateCode <= installedCode)
            throw new IOException("APK versionCode is not newer than installed version");
        Signature[] current = signatures(installed);
        Signature[] incoming = signatures(candidate);
        if (current != null && incoming != null && current.length > 0 &&
                incoming.length > 0 && !Arrays.equals(current, incoming))
            throw new IOException("APK signing certificate differs from the installed app. A one-time reinstall is required.");
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28)
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        return info.signatures;
    }

}
