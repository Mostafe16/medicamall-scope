package com.medicamall.scope;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Car edition: open the exam screen automatically when the head unit boots (receiver is disabled in other builds). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!BuildConfig.CAR) return;
        final Context app = c.getApplicationContext();
        final PendingResult pr = goAsync();
        // give the head unit's own launcher a few seconds to settle, then come to the front
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent i = new Intent(app, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                app.startActivity(i);
            } catch (Throwable ignored) { }
            pr.finish();
        }, 500);
    }
}
