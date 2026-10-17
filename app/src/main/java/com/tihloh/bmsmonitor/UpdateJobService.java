package com.tihloh.bmsmonitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Native Android periodic release check, analogous to POS's hourly updater. */
public final class UpdateJobService extends JobService {
    private static final int JOB_ID=821014;
    private static final String CHANNEL="bms_update_releases";
    private UpdateManager manager;
    public static void schedule(Context context) {
        JobScheduler jobs=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(jobs==null)return;
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(context,UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(60L*60L*1000L)
                .build();
        jobs.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        manager=new UpdateManager(this);
        manager.check(true,release->{
            if(release!=null)notifyUpdate(release);
            jobFinished(parameters,false);
            manager.shutdown();
        },error->{
            jobFinished(parameters,false);
            manager.shutdown();
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        if(manager!=null)manager.shutdown();
        return true;
    }

    private void notifyUpdate(UpdateManager.Release release) {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return;
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(notifications==null)return;
        if(Build.VERSION.SDK_INT>=26)notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL,"BMS Monitor updates",NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("BMS Monitor "+release.version+" available")
                .setContentText("Open the app to review and install the update.")
                .setContentIntent(pending).setAutoCancel(true).build();
        notifications.notify(8822,notification);
    }
}
