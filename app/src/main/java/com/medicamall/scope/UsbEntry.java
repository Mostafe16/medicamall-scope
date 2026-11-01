package com.medicamall.scope;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Car edition: the exam screen is the head unit's home screen. When the scope is plugged in, Android delivers
 * USB_DEVICE_ATTACHED here (so the USB permission still goes to this app); we just bring the existing home
 * screen to the front. Starting MainActivity directly would open a second copy on top of the home one.
 * The camera itself is picked up by the open screen's USB monitor.
 */
public class UsbEntry extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (MainActivity.bringLiveToFront(this)) { finish(); return; }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setPackage(getPackageName());
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(home);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            } catch (Throwable ignored) { }
        }
        finish();
    }
}
