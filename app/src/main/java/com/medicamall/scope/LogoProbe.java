package com.medicamall.scope;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Car edition diagnostic: the head unit's boot-logo picker (logodisplay / car settings) only lists its built-in logos,
 * and the folder it reads custom logos from isn't documented. This collects the evidence to find it:
 * a report (device, logo-related apps, every file/folder named *logo* / *boot* in the usual system and storage places)
 * plus copies of the logo-related APKs, then offers them to a phone through the QR share.
 */
class LogoProbe {

    static void run(Activity a) {
        Toast.makeText(a, "بجمع معلومات لوجو التشغيل…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<File> out = new ArrayList<>();
            List<String> mimes = new ArrayList<>();
            StringBuilder r = new StringBuilder();
            File dir = new File(a.getCacheDir(), "probe");
            deleteDir(dir);
            dir.mkdirs();
            try {
                r.append("== device ==\n")
                 .append("model=").append(Build.MODEL).append(" board=").append(Build.BOARD).append(" hw=").append(Build.HARDWARE)
                 .append(" brand=").append(Build.BRAND).append(" product=").append(Build.PRODUCT).append('\n')
                 .append("display=").append(Build.DISPLAY).append(" sdk=").append(Build.VERSION.SDK_INT).append(" release=").append(Build.VERSION.RELEASE).append('\n')
                 .append("fingerprint=").append(Build.FINGERPRINT).append("\n\n== apps (logo/boot/setting/factory/launcher) ==\n");

                PackageManager pm = a.getPackageManager();
                int apks = 0;
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    String pkg = ai.packageName == null ? "" : ai.packageName;
                    String label = String.valueOf(pm.getApplicationLabel(ai));
                    String key = (pkg + " " + label).toLowerCase(Locale.US);
                    boolean logo = key.contains("logo") || key.contains("boot");
                    boolean other = key.contains("setting") || key.contains("factory") || key.contains("launcher") || key.contains("carsys") || key.contains("canbus");
                    if (!logo && !other) continue;
                    r.append(logo ? "* " : "  ").append(label).append(" | ").append(pkg).append(" | ").append(ai.sourceDir).append('\n');
                    if ((logo || key.contains("setting")) && apks < 8 && ai.sourceDir != null && !pkg.equals("com.android.settings")) {
                        File src = new File(ai.sourceDir);
                        if (src.canRead() && src.length() < 80L * 1024 * 1024) {
                            File dst = new File(dir, pkg + ".apk");
                            if (copy(src, dst)) { out.add(dst); mimes.add("application/vnd.android.package-archive"); apks++; }
                        }
                    }
                }

                r.append("\n== files/folders named *logo* or *boot* ==\n");
                int[] count = {0};
                String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
                String[] roots = {"/system/media", "/system/etc", "/system/app", "/system/priv-app", "/system/vendor", "/vendor", "/oem", "/product",
                        "/odm", "/custom", "/system/usr", ext, "/mnt/sdcard", "/storage", "/data/local/tmp", "/cache"};
                for (String root : roots) walk(new File(root), 0, r, count);

                r.append("\n== storage roots ==\n");
                for (String root : new String[]{ext, "/storage", "/mnt", "/mnt/media_rw"}) {
                    File[] ls = new File(root).listFiles();
                    r.append(root).append(": ");
                    if (ls == null) r.append("(not readable)");
                    else for (File f : ls) r.append(f.getName()).append(f.isDirectory() ? "/ " : " ");
                    r.append('\n');
                }
            } catch (Throwable t) {
                r.append("\nERROR ").append(t).append('\n');
            }
            try {
                File rep = new File(dir, "logo-report.txt");
                try (OutputStream o = new FileOutputStream(rep)) { o.write(r.toString().getBytes(StandardCharsets.UTF_8)); }
                out.add(0, rep);
                mimes.add(0, "text/plain; charset=utf-8");
            } catch (Throwable ignored) { }
            a.runOnUiThread(() -> LocalShare.show(a, out, mimes, "تشخيص لوجو التشغيل",
                    () -> Toast.makeText(a, "التقرير جاهز بس المشاركة محتاجة واي فاي", Toast.LENGTH_LONG).show()));
        }).start();
    }

    private static void walk(File f, int depth, StringBuilder r, int[] count) {
        if (count[0] > 600 || depth > 5 || f == null) return;
        File[] ls;
        try { ls = f.listFiles(); } catch (Throwable t) { return; }
        if (ls == null) return;
        for (File c : ls) {
            String n = c.getName().toLowerCase(Locale.US);
            if (n.contains("logo") || n.contains("boot") || n.contains("splash")) {
                r.append(c.isDirectory() ? "[D] " : "[F] ").append(c.getAbsolutePath());
                if (c.isFile()) r.append("  ").append(c.length()).append(" B");
                r.append('\n');
                count[0]++;
            }
            if (c.isDirectory() && !n.startsWith(".") && !n.equals("proc") && !n.equals("sys")) walk(c, depth + 1, r, count);
            if (count[0] > 600) return;
        }
    }

    private static boolean copy(File src, File dst) {
        try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteDir(File d) {
        File[] ls = d.listFiles();
        if (ls != null) for (File f : ls) { if (f.isDirectory()) deleteDir(f); f.delete(); }
        d.delete();
    }
}
