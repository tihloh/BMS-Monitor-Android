package com.tihloh.bmsmonitor;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.tihloh.bmsmonitor.UPDATE_INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33)
                confirm = result.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            else
                confirm = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); }
                catch (Exception e) { toast(context, "Open BMS Monitor and approve installation."); }
            } else {
                toast(context, "Android did not provide install confirmation.");
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            toast(context, "BMS Monitor updated successfully.");
        } else {
            String why = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast(context, "Could not update: " + (why != null ? why :
                    "Check APK signing key; uninstall/reinstall may be required."));
        }
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
