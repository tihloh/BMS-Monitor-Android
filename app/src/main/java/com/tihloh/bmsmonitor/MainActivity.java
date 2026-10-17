package com.tihloh.bmsmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A compact, accessible BLE dashboard with explicit discovery and update feedback. */
public final class MainActivity extends Activity implements BleClient.Listener {
    private static final int PERMS=200;
    private static final int TEXT=Color.rgb(19,38,54);
    private static final int SECONDARY=Color.rgb(93,109,125);
    private static final int ACCENT=Color.rgb(9,112,136);
    private static final int BG=Color.rgb(244,247,251);
    private static final int WHITE=Color.WHITE;

    private BleClient ble;
    private UpdateManager updater;
    private final Set<String> seen=new HashSet<>();
    private final StringBuilder history=new StringBuilder();
    private boolean automaticConnection=false, showingLogs=false;
    private int deviceCount=0;
    private String preferredAddress="";
    private TextView status, guidance, scanCount, soc, mode, voltage, current, power, remaining,
            total, stamp, cellHeading, logText, updateInfo;
    private LinearLayout devices, cellGrid, logPanel;
    private ProgressBar socProgress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16,39,57));
        getWindow().setNavigationBarColor(Color.rgb(16,39,57));
        preferredAddress=getPreferences(0).getString("last_device","");
        ble=new BleClient(this,this);
        updater=new UpdateManager(this);
        UpdateJobService.schedule(this);
        render();
        onStatus("Not connected",false);
        updater.check(false,this::offerUpdate,e -> onLog("Update check: "+e));
    }

    @Override protected void onResume() {
        super.onResume();
        if(ble!=null && hasPermissions() && !ble.isReady() && !automaticConnection) {
            // Begin automatic discovery only for a previously selected battery.
            if(!preferredAddress.isEmpty()) scan(true);
        }
    }

    private void render() {
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root=column();
        root.setPadding(dp(16),dp(19),dp(16),dp(28));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout top=row();
        LinearLayout heading=column();
        heading.addView(text("BMS Monitor",26,TEXT,true));
        TextView subtitle=text("GENTAI · BLE BATTERY DASHBOARD",11,SECONDARY,true);
        subtitle.setLetterSpacing(.06f);
        heading.addView(subtitle, topMargin(3));
        top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button update=button("Updates",false);
        update.setOnClickListener(v->{
            updateInfo.setText("Checking GitHub Releases…");
            updater.check(true,r->{ if(r==null)updateInfo.setText("You have the latest version.");
                else offerUpdate(r);}, e->updateInfo.setText("Update check failed: "+e));
        });
        top.addView(update,new LinearLayout.LayoutParams(dp(93),dp(45)));
        root.addView(top);
        updateInfo=text("Version "+BuildConfig.VERSION_NAME+" · Experimental",11,SECONDARY,false);
        root.addView(updateInfo,topMargin(9));

        LinearLayout connection=card();
        LinearLayout titleRow=row();
        TextView deviceTitle=text("BLUETOOTH CONNECTION",12,SECONDARY,true);
        titleRow.addView(deviceTitle,new LinearLayout.LayoutParams(0,-2,1));
        titleRow.addView(text("READ ONLY",11,ACCENT,true));
        connection.addView(titleRow);
        status=text("○  Not connected",17,TEXT,true);
        connection.addView(status,topMargin(8));
        guidance=text("Tap Scan to find ECO039E. Close the Gentai app first.",12,SECONDARY,false);
        guidance.setLineSpacing(dp(2),1f);
        connection.addView(guidance,topMargin(7));
        LinearLayout controls=row();
        Button scanButton=button("Scan for BMS",true);
        scanButton.setOnClickListener(v->scan(false));
        controls.addView(scanButton,weighted());
        Button disconnect=button("Disconnect",false);
        disconnect.setOnClickListener(v->{
            automaticConnection=false;
            ble.disconnect();
            onStatus("Disconnected",false);
            guidance.setText("Tap Scan for BMS to connect again.");
        });
        controls.addView(disconnect,weighted());
        connection.addView(controls,topMargin(12));
        root.addView(connection,topMargin(17));

        LinearLayout discovery=card();
        LinearLayout devicesHeading=row();
        TextView found=text("NEARBY DEVICES",12,SECONDARY,true);
        devicesHeading.addView(found,new LinearLayout.LayoutParams(0,-2,1));
        scanCount=text("0 found",12,ACCENT,true);
        devicesHeading.addView(scanCount);
        discovery.addView(devicesHeading);
        devices=column();
        devices.addView(text("Press Scan for BMS. Detected devices appear here.",13,SECONDARY,false));
        discovery.addView(devices,topMargin(9));
        root.addView(discovery,topMargin(10));

        LinearLayout charge=card();
        charge.addView(text("STATE OF CHARGE",12,SECONDARY,true));
        soc=text("-- %",44,ACCENT,true);
        charge.addView(soc,topMargin(7));
        socProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        socProgress.setMax(100);
        socProgress.setProgress(0);
        if(Build.VERSION.SDK_INT>=21) socProgress.setProgressTintList(
                android.content.res.ColorStateList.valueOf(ACCENT));
        charge.addView(socProgress,topMargin(5));
        mode=text("Waiting for battery telemetry",13,SECONDARY,false);
        charge.addView(mode,topMargin(9));
        root.addView(charge,topMargin(14));

        LinearLayout metrics1=row();
        LinearLayout v1=metric("VOLTAGE");voltage=(TextView)v1.getChildAt(1);
        LinearLayout v2=metric("CURRENT");current=(TextView)v2.getChildAt(1);
        metrics1.addView(v1,weightedCard());
        metrics1.addView(v2,weightedCard());
        root.addView(metrics1,topMargin(10));

        LinearLayout metrics2=row();
        LinearLayout v3=metric("POWER");power=(TextView)v3.getChildAt(1);
        LinearLayout v4=metric("REMAINING");remaining=(TextView)v4.getChildAt(1);
        metrics2.addView(v3,weightedCard());
        metrics2.addView(v4,weightedCard());
        root.addView(metrics2,topMargin(10));

        LinearLayout details=card();
        total=text("Rated capacity: -- Ah",13,TEXT,true);details.addView(total);
        stamp=text("Last received: --",12,SECONDARY,false);
        details.addView(stamp,topMargin(6));
        root.addView(details,topMargin(10));

        LinearLayout cells=card();
        cellHeading=text("Cell voltages",14,TEXT,true);cells.addView(cellHeading);
        cellGrid=column();
        cellGrid.addView(text("Waiting for compatible cell data…",12,SECONDARY,false));
        cells.addView(cellGrid,topMargin(8));
        root.addView(cells,topMargin(10));

        LinearLayout diagnostics=card();
        Button toggle=button("Diagnostics ▾",false);
        toggle.setOnClickListener(v->{
            showingLogs=!showingLogs;
            toggle.setText(showingLogs?"Diagnostics ▴":"Diagnostics ▾");
            logPanel.setVisibility(showingLogs?View.VISIBLE:View.GONE);
        });
        diagnostics.addView(toggle);
        logPanel=column();
        logPanel.setVisibility(View.GONE);
        Button copy=button("Copy Bluetooth log",false);
        copy.setOnClickListener(v->{
            ClipboardManager manager=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(manager!=null) {
                manager.setPrimaryClip(ClipData.newPlainText("BMS BLE log",history.toString()));
                Toast.makeText(this,"Log copied",Toast.LENGTH_SHORT).show();
            }
        });
        logPanel.addView(copy,topMargin(7));
        logText=text("No events",11,SECONDARY,false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMaxLines(18);
        logPanel.addView(logText,topMargin(7));
        diagnostics.addView(logPanel);
        root.addView(diagnostics,topMargin(10));
        TextView disclaimer=text("Experimental monitor • Do not use for battery protection decisions",
                11,SECONDARY,false);
        root.addView(disclaimer,topMargin(12));
    }

    private void scan(boolean automatic) {
        if(ble==null)return;
        automaticConnection=automatic;
        if(!ble.bluetoothEnabled()) {
            guidance.setText("Bluetooth is OFF. Turn it on and retry.");
            try {startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));}
            catch(Exception e){guidance.setText("Please enable Bluetooth in Android Settings.");}
            return;
        }
        if(!hasPermissions()) {
            String[] p=Build.VERSION.SDK_INT>=31
                ?new String[]{Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.ACCESS_FINE_LOCATION}
                :new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(p,PERMS);
            return;
        }
        LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
        if(lm!=null && !lm.isLocationEnabled()) {
            guidance.setText("Enable Android Location for reliable BLE discovery, then retry.");
            if(!automatic)new AlertDialog.Builder(this).setTitle("Enable Location")
                .setMessage("Some Android devices require Location to be enabled for nearby BLE scans.")
                .setNegativeButton("Later",null)
                .setPositiveButton("Settings",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .show();
            return;
        }
        seen.clear();deviceCount=0;
        devices.removeAllViews();
        devices.addView(text("Searching nearby… keep the battery within 1 meter.",13,SECONDARY,false));
        scanCount.setText("Scanning…");
        guidance.setText("Searching for ECO039E and other Bluetooth LE devices.");
        ble.scan();
    }

    private boolean hasPermissions() {
        if(Build.VERSION.SDK_INT>=31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
                    &&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
                    &&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode!=PERMS)return;
        if(hasPermissions())scan(automaticConnection);
        else {
            guidance.setText("Nearby Devices and Location permission are required to scan. Tap Scan to retry.");
            if(!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION))
                guidance.append(" If blocked, open Android App Info → Permissions.");
        }
    }

    @SuppressLint("MissingPermission")
    @Override public void onDevice(BluetoothDevice device,String name,int rssi) {
        if(!seen.add(device.getAddress()))return;
        if(deviceCount++==0)devices.removeAllViews();
        scanCount.setText(deviceCount+" found");
        String address=device.getAddress();
        boolean relevant=name.toUpperCase(Locale.US).contains("ECO")
                ||name.toUpperCase(Locale.US).contains("BMS")
                ||address.equalsIgnoreCase(preferredAddress);
        Button choice=button((relevant?"●  ":"○  ")+name+"  ·  "+rssi+" dBm",relevant);
        choice.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        choice.setAllCaps(false);
        choice.setTextSize(13);
        choice.setOnClickListener(v->select(device,name));
        devices.addView(choice,topMargin(5));
        if(automaticConnection && address.equalsIgnoreCase(preferredAddress)) {
            automaticConnection=false;
            select(device,name);
        }
    }

    private void select(BluetoothDevice device,String name) {
        preferredAddress=device.getAddress();
        getPreferences(0).edit().putString("last_device",preferredAddress).apply();
        devices.removeAllViews();
        devices.addView(text("Selected: "+name+" ("+preferredAddress+")",12,SECONDARY,false));
        guidance.setText("Connecting… Keep the Gentai app closed.");
        ble.connect(device);
    }

    @Override public void onStatus(String s,boolean ready) {
        runOnUiThread(()->{
            if(status!=null){status.setText((ready?"●  ":"○  ")+s);
                status.setTextColor(ready?ACCENT:TEXT);}
            if(guidance!=null && ready)guidance.setText("Live telemetry enabled. Updates approximately every 5 seconds.");
            else if(guidance!=null && (s.startsWith("Scan complete")||s.contains("failed")||
                    s.contains("timed out")||s.contains("unavailable")))
                guidance.setText(s+" Check Nearby Devices permission and close other BMS apps.");
        });
    }

    @Override public void onInfo(BatteryProtocol.BatteryInfo info) {
        runOnUiThread(()->{
            soc.setText(String.format(Locale.US,"%.0f%%",info.socPercent));
            socProgress.setProgress((int)Math.round(info.socPercent));
            voltage.setText(String.format(Locale.US,"%.2f V",info.voltageV));
            current.setText(String.format(Locale.US,"%+.2f A",info.currentA));
            power.setText(String.format(Locale.US,"%+.1f W",info.powerW));
            remaining.setText(String.format(Locale.US,"%.2f Ah",info.remainingAh));
            total.setText(String.format(Locale.US,"Rated capacity: %.1f Ah",info.totalAh));
            mode.setText(info.currentA<-.03?"Discharging":info.currentA>.03?"Charging":"Idle");
            stamp.setText("Last received: "+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));
        });
    }

    @Override public void onCells(double[] cells) {
        runOnUiThread(()->{
            cellGrid.removeAllViews();
            double min=Double.MAX_VALUE,max=0;
            for(double v:cells){min=Math.min(min,v);max=Math.max(max,v);}
            cellHeading.setText(String.format(Locale.US,"Cell voltages  ·  Δ %.0f mV",(max-min)*1000));
            for(int start=0;start<cells.length;start+=4) {
                LinearLayout row=row();
                for(int i=start;i<Math.min(start+4,cells.length);i++) {
                    TextView t=text(String.format(Locale.US,"%02d\n%.3f V",i+1,cells[i]),12,TEXT,true);
                    t.setGravity(Gravity.CENTER);
                    t.setBackground(bg(Color.rgb(234,246,248),12));
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(55),1);
                    p.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(t,p);
                }
                cellGrid.addView(row);
            }
        });
    }

    @Override public void onLog(String event) {
        runOnUiThread(()->{
            String now=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date());
            history.insert(0,now+"  "+event+"\n");
            if(history.length()>10000)history.setLength(10000);
            if(logText!=null)logText.setText(history.toString());
        });
    }

    private void offerUpdate(UpdateManager.Release r) {
        if(r==null)return;
        updateInfo.setText("New version "+r.version+" available");
        String notes=r.notes==null||r.notes.isEmpty()?"A new release is available.":r.notes;
        if(notes.length()>800)notes=notes.substring(0,800)+"…";
        new AlertDialog.Builder(this).setTitle("BMS Monitor "+r.version)
            .setMessage(notes+"\n\nAndroid will ask you to approve installation.")
            .setNegativeButton("Later",null)
            .setPositiveButton("Update",(dialog,which)->{
                if(!updater.canInstall(this)) {
                    Toast.makeText(this,"Allow updates from this app, then tap Updates again.",Toast.LENGTH_LONG).show();
                    updater.askInstallPermission(this);
                } else {
                    updateInfo.setText("Downloading update…");
                    updater.install(r,message->{updateInfo.setText(message);
                        Toast.makeText(this,message,Toast.LENGTH_LONG).show();});
                }
            }).show();
    }

    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(14),dp(14),dp(14),dp(14));l.setBackground(bg(WHITE,16));l.setElevation(dp(2));return l;}
    private LinearLayout metric(String title){LinearLayout l=card();l.addView(text(title,11,SECONDARY,true));
        l.addView(text("--",21,TEXT,true),topMargin(7));return l;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String value,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(value);b.setTextSize(13);
        b.setTextColor(primary?WHITE:TEXT);b.setBackground(bg(primary?ACCENT:Color.rgb(229,237,244),10));
        b.setPadding(dp(10),dp(6),dp(10),dp(6));b.setMinHeight(dp(43));return b;}
    private GradientDrawable bg(int color,int radius){GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private LinearLayout.LayoutParams topMargin(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(top);return p;}
    private LinearLayout.LayoutParams weighted(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);
        p.setMargins(dp(3),0,dp(3),0);return p;}
    private LinearLayout.LayoutParams weightedCard(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
        p.setMargins(dp(3),0,dp(3),0);return p;}
    private int dp(int v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    @Override protected void onDestroy(){if(ble!=null)ble.close();if(updater!=null)updater.shutdown();super.onDestroy();}
}
