package com.tihloh.bmsmonitor;

import android.app.Activity;
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
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** GitHub Releases updater adapted from tihloh/pos-source. Explicit user confirmation required. */
public final class UpdateManager {
    private static final String API = "https://api.github.com/repos/tihloh/BMS-Monitor-Android/releases/latest";
    private static final long INTERVAL = 12 * 60 * 60 * 1000L;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static final class Release {
        public final String version, title, notes, apkUrl, pageUrl;
        Release(String version, String title, String notes, String apkUrl, String pageUrl) {
            this.version=version; this.title=title; this.notes=notes; this.apkUrl=apkUrl; this.pageUrl=pageUrl;
        }
    }

    public UpdateManager(Context context) { app = context.getApplicationContext(); }

    public void check(boolean force, Consumer<Release> onResult, Consumer<String> onError) {
        io.execute(() -> {
            try {
                if (!force) {
                    long previous = app.getSharedPreferences("update_check",0).getLong("last",0);
                    if (System.currentTimeMillis()-previous < INTERVAL) return;
                }
                HttpURLConnection conn = open(API);
                String json;
                try {
                    if (conn.getResponseCode()!=200) throw new Exception("GitHub returned HTTP "+conn.getResponseCode());
                    try (InputStream input=conn.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                        byte[] buf=new byte[8192]; int n;
                        while((n=input.read(buf))!=-1) bytes.write(buf,0,n);
                        json=new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
                    }
                } finally { conn.disconnect(); }
                JSONObject obj=new JSONObject(json);
                String version=obj.optString("tag_name").replaceFirst("^v","");
                JSONArray assets=obj.optJSONArray("assets");
                String apk=null;
                if (assets != null) for (int i=0;i<assets.length();i++) {
                    JSONObject asset=assets.optJSONObject(i);
                    if (asset!=null && asset.optString("name").endsWith(".apk")) {
                        apk=asset.optString("browser_download_url"); break;
                    }
                }
                app.getSharedPreferences("update_check",0).edit()
                        .putLong("last",System.currentTimeMillis()).apply();
                final String url=apk;
                Release release = newer(version, BuildConfig.VERSION_NAME) ?
                    new Release(version,obj.optString("name"),obj.optString("body"),url,obj.optString("html_url")):null;
                main.post(() -> onResult.accept(release));
            } catch(Exception e) { main.post(() -> onError.accept(e.getMessage())); }
        });
    }

    private HttpURLConnection open(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(30000);
        c.setRequestProperty("Accept","application/vnd.github+json");
        c.setRequestProperty("User-Agent","BMS-Monitor/"+BuildConfig.VERSION_NAME);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    public boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT<26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    public void askInstallPermission(Activity activity) {
        if(Build.VERSION.SDK_INT>=26) {
            Intent intent=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:"+activity.getPackageName()));
            activity.startActivity(intent);
        }
    }

    public void install(Release release, Consumer<String> status) {
        if(release.apkUrl==null || !release.apkUrl.startsWith(
                "https://github.com/tihloh/BMS-Monitor-Android/releases/download/")) {
            status.accept("No approved APK asset found in this release."); return;
        }
        io.execute(() -> {
            PackageInstaller installer=app.getPackageManager().getPackageInstaller();
            int id=-1;
            try {
                HttpURLConnection c=open(release.apkUrl);
                try {
                    if(c.getResponseCode()!=200) throw new Exception("APK download HTTP "+c.getResponseCode());
                    PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(app.getPackageName());
                    if(Build.VERSION.SDK_INT>=31) params.setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                    id=installer.createSession(params);
                    try(PackageInstaller.Session session=installer.openSession(id);
                        InputStream input=c.getInputStream();
                        OutputStream output=session.openWrite("base.apk",0,c.getContentLengthLong()>0?
                                c.getContentLengthLong():-1L)) {
                        byte[] buffer=new byte[65536];
                        int count;long total=0;
                        while((count=input.read(buffer))!=-1) {
                            total+=count;
                            if(total>50_000_000) throw new Exception("Unexpectedly large APK");
                            output.write(buffer,0,count);
                        }
                        session.fsync(output);
                        Intent done=new Intent(app,UpdateInstallReceiver.class);
                        done.setAction(UpdateInstallReceiver.ACTION);
                        PendingIntent pending=PendingIntent.getBroadcast(app,id,done,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                        session.commit(pending.getIntentSender());
                    }
                    main.post(() -> status.accept("Downloaded. Awaiting Android installation approval…"));
                } finally { c.disconnect(); }
            } catch(Exception e) {
                if(id!=-1) try {installer.abandonSession(id);}catch(Exception ignored) {}
                main.post(() -> status.accept("Update failed: "+e.getMessage()));
            }
        });
    }

    static boolean newer(String remote, String local) {
        String[] r=remote.split("[.-]"),l=local.split("[.-]");
        for(int i=0;i<Math.max(r.length,l.length);i++) {
            int a=segment(r,i),b=segment(l,i);
            if(a!=b) return a>b;
        }
        return false;
    }
    private static int segment(String[] x,int i) {
        if(i>=x.length)return 0;
        try {return Integer.parseInt(x[i].replaceAll("[^0-9]","")); }
        catch(Exception e){return 0;}
    }
    public void shutdown() { io.shutdownNow(); }
}
