package com.medicamall.scope;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1) Exam screen: pulls the latest version from medicamall.com/ent-scope on every launch and caches it,
 *    so website edits reach every doctor without reinstalling. Served from the same origin → data is kept.
 * 2) App itself: checks version.json on the GitHub release and offers a one-tap update.
 */
public class Updates {

    static final String WEB_URL = "https://medicamall.com/ent-scope";
    static final String BASE = "https://github.com/Mostafe16/medicamall-scope/releases/download/app/";
    static final String VERSION_URL = BASE + "version.json";
    static final String APK_URL = BASE + "MedicaMall-Scope.apk";
    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 MedicaMallScope";
    static final String SHIM = "<script src=\"native-shim.js\"></script>";
    static final String SESSION_BRIDGE =
            "/* ---------- Android native media bridge (in page scope) ---------- */\\n" +
            "window.__mmPageImportNativeMedia = async function(item){\\n" +
            "  try{\\n" +
            "    if(!item || !item.url) throw new Error('missing media url');\\n" +
            "    const r = await fetch(item.url,{cache:'no-store'});\\n" +
            "    if(!r.ok) throw new Error('HTTP '+r.status);\\n" +
            "    const blob = await r.blob();\\n" +
            "    if(!blob || !blob.size) throw new Error('empty media');\\n" +
            "    await saveMedia(blob,item.type||'photo',item.ext||'jpg',item.dur||0);\\n" +
            "    await renderGallery();\\n" +
            "    try{window.BesNative&&BesNative.sessionImportResult(item.type||'photo',true,'');}catch(e){}\\n" +
            "  }catch(e){\\n" +
            "    try{window.BesNative&&BesNative.sessionImportResult(item&&item.type||'photo',false,String(e&&e.message||e||'unknown'));}catch(x){}\\n" +
            "  }\\n" +
            "};\\n" +
            "(function(){const q=window.__mmPendingNativeMedia||[];window.__mmPendingNativeMedia=[];q.forEach(x=>window.__mmPageImportNativeMedia(x));})();\\n";

    static String patchExamHtml(String html) {
        if (html == null) return null;
        if (!html.contains("__mmPageImportNativeMedia")) {
            String marker = "/* ---------- init ---------- */";
            int p = html.indexOf(marker);
            if (p >= 0) html = html.substring(0, p) + SESSION_BRIDGE + html.substring(p);
        }
        if (!html.contains("native-shim.js")) {
            html = html.contains("<head>")
                    ? html.replaceFirst("<head>", "<head>" + SHIM)
                    : SHIM + html;
        }
        return html;
    }

    private static File pendingApk;
    private static boolean dialogShown;

    // ---------------- exam screen ----------------
    static File webFile(Context c) {
        return new File(new File(c.getFilesDir(), "web"), "index.html");
    }

    /** Runs in the background; calls onReady when a newer exam screen was saved. */
    static void checkWeb(Context c, Runnable onReady) {
        new Thread(() -> {
            try {
                String page = new String(get(WEB_URL + "?v=" + System.currentTimeMillis()), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("var B64 = \"([A-Za-z0-9+/=]+)\"").matcher(page);
                if (!m.find()) return;
                String html = new String(Base64.decode(m.group(1), Base64.DEFAULT), StandardCharsets.UTF_8);
                if (!html.contains("btnSnap") || !html.contains("</html>")) return; // sanity check
                html = patchExamHtml(html);

                File f = webFile(c);
                String current;
                if (f.exists()) current = new String(read(new FileInputStream(f)), StandardCharsets.UTF_8);
                else current = new String(read(c.getAssets().open("index.html")), StandardCharsets.UTF_8);
                if (html.equals(current)) return;

                f.getParentFile().mkdirs();
                File tmp = new File(f.getPath() + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
                if (onReady != null) onReady.run();
            } catch (Throwable ignored) { }
        }).start();
    }

    // ---------------- app itself ----------------
    static long currentVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    static void checkApp(Activity a) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(new String(get(VERSION_URL), StandardCharsets.UTF_8));
                long remote = j.getLong("versionCode");
                String apk = j.optString("url", APK_URL);
                if (remote > currentVersion(a)) a.runOnUiThread(() -> showDialog(a, apk));
            } catch (Throwable ignored) { }
        }).start();
    }

    private static void showDialog(Activity a, String apk) {
        if (dialogShown || a.isFinishing()) return;
        dialogShown = true;
        new AlertDialog.Builder(a)
                .setTitle("فيه تحديث للأبلكيشن")
                .setMessage("نسخة جديدة من Medica Mall Scope متاحة.\nالمرضى والصور مش هيتمسحوا.")
                .setPositiveButton("تحديث", (d, w) -> download(a, apk))
                .setNegativeButton("بعدين", null)
                .show();
    }

    private static void download(Activity a, String apk) {
        AlertDialog wait = new AlertDialog.Builder(a)
                .setMessage("جاري تحميل التحديث…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            try {
                File dir = new File(a.getCacheDir(), "update");
                dir.mkdirs();
                File f = new File(dir, "MedicaMall-Scope.apk");
                try (OutputStream o = new FileOutputStream(f)) { o.write(get(apk)); }
                a.runOnUiThread(() -> { wait.dismiss(); install(a, f); });
            } catch (Throwable t) {
                a.runOnUiThread(() -> {
                    wait.dismiss();
                    Toast.makeText(a, "التحميل فشل – جرّب تاني", Toast.LENGTH_LONG).show();
                    dialogShown = false;
                });
            }
        }).start();
    }

    static void install(Activity a, File f) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = f;
            Toast.makeText(a, "فعّل «السماح من هذا المصدر» وارجع للأبلكيشن", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        pendingApk = null;
        Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    /** Called from onResume: continue an install that was waiting for the "unknown sources" permission. */
    static void onResume(Activity a) {
        if (pendingApk != null && pendingApk.exists()
                && (Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls())) {
            install(a, pendingApk);
        }
    }

    // ---------------- helpers ----------------
    static byte[] get(String url) throws Exception {
        HttpURLConnection con = null;
        try {
            for (int hop = 0; hop < 5; hop++) {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(15000);
                con.setReadTimeout(60000);
                con.setRequestProperty("User-Agent", UA);
                con.setRequestProperty("Cache-Control", "no-cache");
                int code = con.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = con.getHeaderField("Location");
                    con.disconnect();
                    if (loc == null) throw new Exception("redirect without location");
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code / 100 != 2) throw new Exception("HTTP " + code);
                return read(con.getInputStream());
            }
            throw new Exception("too many redirects");
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = i.read(buf)) > 0) b.write(buf, 0, r);
            return b.toByteArray();
        }
    }
}
