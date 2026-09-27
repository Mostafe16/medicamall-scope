package com.medicamall.scope;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    static final String START_URL = ORIGIN + "/assets/index.html";
    static final int REQ_PERMS = 7, REQ_FILE = 42;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private WebViewAssetLoader assets;

    // ---- UVC camera ----
    private ICameraHelper cam;
    private volatile boolean camOpen = false;
    private boolean resized = false;
    private Size bestSize = null;
    private final Object frameLock = new Object();
    private byte[] frame, work;
    private int fw = 1280, fh = 720;
    private long seq = 0, served = 0;
    private long lastButton = 0;

    // ---- web helpers ----
    private PermissionRequest pendingWebPerm;
    private ValueCallback<Uri[]> fileCb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebView printView;
    private WebChromeClient chrome;
    private final Map<String, File> outFiles = new HashMap<>();
    private final Map<String, String> outMime = new HashMap<>();
    private final Map<String, OutputStream> outStreams = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        assets = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/uvc/")) {
                    return frameResponse();
                }
                if (HOST.equals(u.getHost()) && "/assets/index.html".equals(u.getPath())) {
                    File wf = Updates.webFile(MainActivity.this);
                    if (wf.exists()) {
                        try {
                            Map<String, String> hd = new HashMap<>();
                            hd.put("Cache-Control", "no-cache");
                            return new WebResourceResponse("text/html", "utf-8", 200, "OK", hd, new FileInputStream(wf));
                        } catch (Exception ignored) { }
                    }
                }
                return assets.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                ui.post(() -> {
                    if (hasCameraPerm()) request.grant(request.getResources());
                    else {
                        pendingWebPerm = request;
                        requestPerms();
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                view.setBackgroundColor(Color.BLACK);
                ((FrameLayout) getWindow().getDecorView()).addView(view,
                        new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                ((FrameLayout) getWindow().getDecorView()).removeView(customView);
                customView = null;
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
            }
        };
        web.setWebChromeClient(chrome);

        web.addJavascriptInterface(new Bridge(), "BesNative");
        web.loadUrl(START_URL);

        if (!hasCameraPerm() || needsLegacyWrite()) requestPerms();
        initCam();
        handleUsbIntent(getIntent());
        ui.postDelayed(this::openFirstUvc, 1200);
        Updates.checkWeb(this, () -> js("webupdate"));
        Updates.checkApp(this);
    }

    // ================= permissions =================
    private boolean hasCameraPerm() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsLegacyWrite() {
        return Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        List<String> p = new ArrayList<>();
        if (!hasCameraPerm()) p.add(Manifest.permission.CAMERA);
        if (needsLegacyWrite()) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (pendingWebPerm != null) {
            if (hasCameraPerm()) pendingWebPerm.grant(pendingWebPerm.getResources());
            else pendingWebPerm.deny();
            pendingWebPerm = null;
        }
        if (hasCameraPerm()) ui.postDelayed(this::openFirstUvc, 300);
    }

    // ================= UVC =================
    private static boolean isUvc(UsbDevice d) {
        if (d == null) return false;
        if (d.getDeviceClass() == 14) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == 14) return true;
        }
        return false;
    }

    private static boolean isMjpeg(Size z) {
        return z.type == UVCCamera.UVC_VS_FRAME_MJPEG || z.type == UVCCamera.UVC_VS_FORMAT_MJPEG
                || z.type == UVCCamera.FRAME_FORMAT_MJPEG;
    }

    private Size pickSize() {
        List<Size> list = null;
        try { list = cam.getSupportedSizeList(); } catch (Throwable ignored) { }
        if (list == null || list.isEmpty()) return null;
        Size best = null;
        for (Size z : list) {                     // largest MJPEG up to 1080p
            if (!isMjpeg(z) || z.width * z.height > 1920 * 1080) continue;
            if (best == null || z.width * z.height > best.width * best.height) best = z;
        }
        if (best == null) {
            for (Size z : list) {                 // otherwise largest up to 720p
                if (z.width * z.height > 1280 * 720) continue;
                if (best == null || z.width * z.height > best.width * best.height) best = z;
            }
        }
        return best;
    }

    private void initCam() {
        if (cam != null) return;
        cam = new CameraHelper();
        cam.setStateCallback(new ICameraHelper.StateCallback() {
            @Override
            public void onAttach(UsbDevice device) {
                if (!camOpen && isUvc(device)) cam.selectDevice(device);
            }

            @Override
            public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
                bestSize = pickSize();
                resized = bestSize != null;
                try {
                    if (bestSize != null) cam.openCamera(bestSize);
                    else cam.openCamera();
                } catch (Throwable t) {
                    resized = false;
                    cam.openCamera();
                }
            }

            @Override
            public void onCameraOpen(UsbDevice device) {
                try {
                    if (!resized) {
                        resized = true;
                        Size b = pickSize();
                        if (b != null) cam.setPreviewSize(b);
                    }
                } catch (Throwable ignored) { }
                try {
                    Size p = cam.getPreviewSize();
                    if (p != null) synchronized (frameLock) { fw = p.width; fh = p.height; }
                } catch (Throwable ignored) { }
                cam.setFrameCallback(MainActivity.this::onFrame, UVCCamera.PIXEL_FORMAT_NV21);
                try {
                    cam.setButtonCallback((button, st) -> { if (st == 1) onScopeButton(); });
                } catch (Throwable ignored) { }
                cam.startPreview();
                camOpen = true;
                js("open");
            }

            @Override
            public void onCameraClose(UsbDevice device) {
                camOpen = false;
                js("closed");
            }

            @Override
            public void onDeviceClose(UsbDevice device) { }

            @Override
            public void onDetach(UsbDevice device) {
                camOpen = false;
                js("detached");
            }

            @Override
            public void onCancel(UsbDevice device) {
                js("cancel");
            }
        });
    }

    private void openFirstUvc() {
        if (cam == null || camOpen) return;
        try {
            List<UsbDevice> list = cam.getDeviceList();
            if (list == null) return;
            for (UsbDevice d : list) {
                if (isUvc(d)) { cam.selectDevice(d); return; }
            }
        } catch (Throwable ignored) { }
    }

    private void handleUsbIntent(Intent i) {
        if (i == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) return;
        UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (d != null && isUvc(d) && cam != null && !camOpen) {
            ui.postDelayed(() -> { if (!camOpen) cam.selectDevice(d); }, 500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUsbIntent(intent);
    }

    private void onFrame(ByteBuffer buf) {
        synchronized (frameLock) {
            int n = buf.remaining();
            if (n < fw * fh * 3 / 2) return;
            if (frame == null || frame.length != n) frame = new byte[n];
            buf.get(frame, 0, n);
            seq++;
            frameLock.notifyAll();
        }
    }

    private void onScopeButton() {
        long now = SystemClock.uptimeMillis();
        if (now - lastButton < 150) return; // debounce
        lastButton = now;
        js("button");
    }

    private WebResourceResponse frameResponse() {
        int w, h;
        synchronized (frameLock) {
            long until = SystemClock.uptimeMillis() + 200;
            while (camOpen && seq == served) {
                long wait = until - SystemClock.uptimeMillis();
                if (wait <= 0) break;
                try { frameLock.wait(wait); } catch (InterruptedException e) { break; }
            }
            if (frame == null || seq == served) return empty();
            if (work == null || work.length != frame.length) work = new byte[frame.length];
            System.arraycopy(frame, 0, work, 0, frame.length);
            w = fw; h = fh; served = seq;
        }
        try {
            YuvImage yi = new YuvImage(work, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 4);
            yi.compressToJpeg(new Rect(0, 0, w, h), 88, bos);
            Map<String, String> hd = new HashMap<>();
            hd.put("Cache-Control", "no-store");
            hd.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("image/jpeg", null, 200, "OK", hd, new ByteArrayInputStream(bos.toByteArray()));
        } catch (Throwable t) {
            return empty();
        }
    }

    private WebResourceResponse empty() {
        Map<String, String> hd = new HashMap<>();
        hd.put("Cache-Control", "no-store");
        return new WebResourceResponse("image/jpeg", null, 204, "No Content", hd, new ByteArrayInputStream(new byte[0]));
    }

    private void js(String evt) {
        ui.post(() -> {
            if (web != null) web.evaluateJavascript("window.__besNative&&window.__besNative('" + evt + "')", null);
        });
    }

    // ================= misc helpers =================
    private void openExternal(String url) {
        ui.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "مفيش تطبيق يفتح الرابط ده", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String safeName(String n) {
        String s = (n == null ? "file" : n).replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        return s.isEmpty() ? "file" : s;
    }

    private Uri saveToGallery(File f, String mime) throws Exception {
        String name = f.getName();
        boolean img = mime != null && mime.startsWith("image/");
        boolean vid = mime != null && mime.startsWith("video/");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            Uri coll;
            if (img) {
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MedicaMall");
                coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            } else if (vid) {
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MedicaMall");
                coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else {
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MedicaMall");
                coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            }
            Uri uri = getContentResolver().insert(coll, cv);
            if (uri == null) throw new Exception("insert failed");
            try (InputStream in = new FileInputStream(f); OutputStream out = getContentResolver().openOutputStream(uri)) {
                copy(in, out);
            }
            return uri;
        } else {
            String dirName = img ? Environment.DIRECTORY_PICTURES : vid ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
            File dir = new File(Environment.getExternalStoragePublicDirectory(dirName), "MedicaMall");
            dir.mkdirs();
            File dst = new File(dir, name);
            try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
            return Uri.fromFile(dst);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[64 * 1024];
        int r;
        while ((r = in.read(b)) > 0) out.write(b, 0, r);
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ================= JS bridge =================
    class Bridge {
        @JavascriptInterface
        public boolean isConnected() { return camOpen; }

        @JavascriptInterface
        public String frameSize() { synchronized (frameLock) { return fw + "x" + fh; } }

        @JavascriptInterface
        public String version() { return "android-1"; }

        @JavascriptInterface
        public void fileBegin(String id, String name, String mime) {
            try {
                File dir = new File(getCacheDir(), "out");
                dir.mkdirs();
                File f = new File(dir, safeName(name));
                synchronized (outFiles) {
                    outFiles.put(id, f);
                    outMime.put(id, mime);
                    outStreams.put(id, new FileOutputStream(f));
                }
            } catch (Exception e) { toast("خطأ في حفظ الملف"); }
        }

        @JavascriptInterface
        public void fileChunk(String id, String b64) {
            try {
                OutputStream o;
                synchronized (outFiles) { o = outStreams.get(id); }
                if (o != null) o.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) { toast("خطأ في حفظ الملف"); }
        }

        @JavascriptInterface
        public void fileEnd(String id) {
            try {
                OutputStream o;
                synchronized (outFiles) { o = outStreams.remove(id); }
                if (o != null) o.close();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void saveFiles(String idsCsv) {
            int ok = 0;
            for (String id : idsCsv.split(",")) {
                File f; String mime;
                synchronized (outFiles) { f = outFiles.get(id); mime = outMime.get(id); }
                if (f == null) continue;
                try { saveToGallery(f, mime); ok++; } catch (Exception e) { toast("مقدرتش أحفظ " + f.getName()); }
            }
            if (ok > 0) toast("✔ اتحفظ في المعرض / Download › MedicaMall");
        }

        @JavascriptInterface
        public void shareFiles(String idsCsv, String title) {
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            for (String id : idsCsv.split(",")) {
                File f; String mime;
                synchronized (outFiles) { f = outFiles.get(id); mime = outMime.get(id); }
                if (f == null) continue;
                uris.add(FileProvider.getUriForFile(MainActivity.this, "com.medicamall.scope.files", f));
                String m = (mime == null || mime.isEmpty()) ? "*/*" : mime;
                if (type == null) type = m;
                else if (!type.equals(m)) type = type.split("/")[0].equals(m.split("/")[0]) ? type.split("/")[0] + "/*" : "*/*";
            }
            if (uris.isEmpty()) return;
            Intent send;
            if (uris.size() == 1) {
                send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            send.setType(type);
            if (title != null && !title.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, title);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "مشاركة");
            ui.post(() -> startActivity(chooser));
        }

        @JavascriptInterface
        public void printHtml(String html, String title) {
            ui.post(() -> {
                WebView pv = new WebView(MainActivity.this);
                pv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        ui.postDelayed(() -> {
                            String job = (title == null || title.isEmpty()) ? "Medica Mall Report" : title;
                            PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                            pm.print(job, v.createPrintDocumentAdapter(job),
                                    new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                        }, 400);
                    }
                });
                pv.loadDataWithBaseURL(ORIGIN + "/", html, "text/html", "UTF-8", null);
                printView = pv; // keep a reference until printing is done
            });
        }

        @JavascriptInterface
        public void openExternal(String url) { MainActivity.this.openExternal(url); }

        @JavascriptInterface
        public void reloadWeb() { ui.post(() -> web.reload()); }

        @JavascriptInterface
        public void toast(String msg) { MainActivity.this.toast(msg); }
    }

    // ================= lifecycle =================
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updates.onResume(this);
    }

    @Override
    protected void onDestroy() {
        camOpen = false;
        if (cam != null) {
            try { cam.release(); } catch (Throwable ignored) { }
            cam = null;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
