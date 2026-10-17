package com.tihloh.bmsmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.tihloh.bmsmonitor.INSTALL_STATUS";
    @Override public void onReceive(Context context, Intent intent) {
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation;
            if(Build.VERSION.SDK_INT>=33) confirmation=intent.getParcelableExtra(
                    Intent.EXTRA_INTENT,Intent.class);
            else confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirmation!=null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {context.startActivity(confirmation);}
                catch(Exception e){Toast.makeText(context,"Open Android to confirm update",Toast.LENGTH_LONG).show();}
            }
        } else if(status!=PackageInstaller.STATUS_SUCCESS) {
            String message=intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context,"Update error: "+message,Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context,"BMS Monitor updated",Toast.LENGTH_SHORT).show();
        }
    }
}
