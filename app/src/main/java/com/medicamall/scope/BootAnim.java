package com.medicamall.scope;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Car edition, stage 2 of the boot screen: the Android boot animation that plays after the first boot logo.
 * The head unit's own logo app (com.reglink.logodisplay) treats /custom/media/bootanimation.zip as the custom
 * animation (its "restore default" deletes that file), and the firmware ships a root helper service
 * "reglink.native_service" that the logo app uses for privileged commands. We put a one-frame Medica Mall
 * animation there through the same service. "Restore" deletes it, which is exactly the factory default.
 */
class BootAnim {

    static final String TARGET = "/custom/media/bootanimation.zip";
    private static final String DESCRIPTOR = "com.reglink.services.IReglinkNativeService";
    private static final int TX_SU = 3, TX_EXEC = 4;

    static void install(Activity a) { run(a, true); }

    static void restore(Activity a) { run(a, false); }

    private static void run(Activity a, boolean install) {
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            boolean ok = false;
            try {
                IBinder b = service();
                if (b == null) {
                    log.append("خدمة النظام reglink.native_service مش موجودة على الشاشة دي.");
                } else if (install) {
                    File tmp = new File(a.getCacheDir(), "mm_bootanimation.zip");
                    try (InputStream in = a.getAssets().open("bootanimation.zip"); OutputStream o = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    int r = su(b, "mkdir -p /custom/media && cp -f " + tmp.getAbsolutePath() + " " + TARGET
                            + " && chmod 644 " + TARGET + " && sync");
                    log.append("su=").append(r).append('\n');
                    String ls = exec(b, "ls -l " + TARGET);
                    log.append(ls == null ? "" : ls.trim()).append('\n');
                    ok = (ls != null && ls.contains("bootanimation.zip") && !ls.contains("No such file")) || (r == 0 && (ls == null || ls.trim().isEmpty()));
                    tmp.delete();  // root copied it already
                } else {
                    int r = su(b, "rm -f " + TARGET + " && sync");
                    log.append("su=").append(r).append('\n');
                    String ls = exec(b, "ls -l " + TARGET);
                    ok = r == 0 || (ls != null && ls.contains("No such file"));
                    log.append(ls == null ? "" : ls.trim()).append('\n');
                }
            } catch (Throwable t) {
                log.append("ERROR ").append(t).append('\n');
            }
            final boolean done = ok;
            final String details = log.toString();
            a.runOnUiThread(() -> new AlertDialog.Builder(a)
                    .setTitle(done ? (install ? "✔ أنيميشن ميديكا مول اتثبت" : "✔ رجع أنيميشن المصنع")
                                   : "مقدرتش أغيّر أنيميشن التشغيل")
                    .setMessage((done ? "اعمل Restart للشاشة عشان تشوفه.\n\n" : "صوّر الرسالة دي وابعتها.\n\n") + details)
                    .setPositiveButton("تمام", null)
                    .show());
        }).start();
    }

    // ---------------- vendor root helper (binder) ----------------
    private static IBinder service() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getMethod("getService", String.class).invoke(null, "reglink.native_service");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int su(IBinder b, String cmd) throws Exception {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(cmd);
            b.transact(TX_SU, d, r, 0);
            r.readException();
            return r.readInt();
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static String exec(IBinder b, String cmd) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(cmd);
            b.transact(TX_EXEC, d, r, 0);
            r.readException();
            return r.readString();
        } catch (Throwable t) {
            return "exec error: " + t;
        } finally {
            d.recycle();
            r.recycle();
        }
    }
}
