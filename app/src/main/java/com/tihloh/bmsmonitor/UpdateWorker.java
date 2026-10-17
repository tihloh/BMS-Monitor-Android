package com.tihloh.bmsmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/** POS-style periodic release checks. Installer always uses Android security confirmation. */
public final class UpdateWorker extends Worker {
    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context ctx = getApplicationContext();
        UpdateManager.Info info = UpdateManager.fetchAvailable(ctx);
        if (info == null) return Result.success();
        SharedPreferences prefs = ctx.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE);
        String staged = prefs.getString("last_staged_version", "");
        if (info.version.equals(staged)) return Result.success();
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls())
            return Result.success();
        String result = UpdateManager.stageInstall(ctx, info);
        if (result.startsWith("Update downloaded")) {
            prefs.edit().putString("last_staged_version", info.version).apply();
            return Result.success();
        }
        return Result.retry();
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest task = new PeriodicWorkRequest.Builder(
                UpdateWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("bms-auto-update",
                ExistingPeriodicWorkPolicy.KEEP, task);
    }
}
