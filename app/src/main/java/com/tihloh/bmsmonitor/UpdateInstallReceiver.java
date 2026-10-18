package com.tihloh.bmsmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** Routes Android install approval and preserves the full error for Updates & About. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_STATUS = "com.tihloh.bmsmonitor.UPDATE_INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); }
                catch (Exception e) { report(context, "Android installation approval could not be opened: " + e.getMessage()); }
            } else report(context, "Android did not provide an installation approval screen.");
        } else if (code == PackageInstaller.STATUS_SUCCESS) {
            context.getSharedPreferences("bms_updates", Context.MODE_PRIVATE).edit()
                    .remove("last_install_error").apply();
            Toast.makeText(context, "Battery Monitor updated", Toast.LENGTH_LONG).show();
        } else {
            String details = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String failure = (details == null || details.isEmpty()) ? "Android status code: " + code : details;
            if (code == PackageInstaller.STATUS_FAILURE_INVALID)
                failure = "Android rejected the APK. Verify it is a complete production APK for this app, not a development APK.\n\n" + failure;
            else if (code == PackageInstaller.STATUS_FAILURE_CONFLICT)
                failure = "Update signing or installed package conflicts with this APK. A different signing key cannot update the installed app.\n\n" + failure;
            report(context, failure);
        }
    }

    private static void report(Context context, String message) {
        context.getSharedPreferences("bms_updates", Context.MODE_PRIVATE).edit()
                .putString("last_install_error", message).apply();
        Toast.makeText(context, "Update failed. Open Updates & About for details.", Toast.LENGTH_LONG).show();
    }
}
