package com.medicamall.scope;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Scope Box: open the exam screen as soon as the TV box finishes booting. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        if (!BoxMode.autoStart(c)) return;
        // In box (home-screen) mode Android opens us itself; no need to start twice.
        if (BoxMode.homeMode(c)) return;
        try {
            Intent open = new Intent(c, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(open);
        } catch (Throwable ignored) {
            // Android 10+ may block a background start; box mode covers those devices.
        }
    }
}
