package com.schuurman.rvc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "RVC.BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Boot event: " + intent.getAction());
        // Start gear monitoring service as early as possible (direct boot aware).
        Intent svc = new Intent(context, GearMonitorService.class);
        context.startService(svc);
    }
}
