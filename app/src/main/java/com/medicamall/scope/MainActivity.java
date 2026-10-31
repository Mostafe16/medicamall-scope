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
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
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
import com.herohan.uvcapp.VideoCapture;
import com.herohan.uvcapp.VideoCaptureConfig;
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
    private volatile String devId = ""; // "vvvv:pppp" of the open UVC device (tells the web screen scope vs otoscope)
    private boolean resized = false;
    private Size bestSize = null;
    private final Object frameLock = new Object();
    private byte[] frame, work;
    private int fw = 1280, fh = 720;
    private long seq = 0, served = 0;
    private long lastButton = 0;
    // ---- car head-unit edition: try smaller sizes when the scope opens but sends no frames ----
    private int sizeTry = 0;
    private Runnable openWatch;
    private long lastNoUsbToast = 0;

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
        if (NATIVE_LIVE) {
            // root: page background + black stage box with the camera TextureView + the exam screen (transparent) on top
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Color.parseColor("#eef4fb"));
            stageBox = new FrameLayout(this);
            stageBox.setBackgroundColor(Color.parseColor("#05080c"));
            liveView = new TextureView(this);
            liveView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { liveTex = st; attachLive(); applyLiveTransform(); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyLiveTransform(); }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { detachLive(); liveTex = null; return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { camFrames++; }
            });
            stageBox.addView(liveView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(1, 1);
            root.addView(stageBox, sp);
            web.setBackgroundColor(Color.TRANSPARENT);
            root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(root);
        } else {
            web.setBackgroundColor(Color.WHITE);
            setContentView(web);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        if (BuildConfig.CAR) {
            // head units often ship with a large font scale / odd density: fixed layout width + 100% text
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setTextZoom(100);
            s.setSupportZoom(false);
            try {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    java.util.Set<String> origins = new java.util.HashSet<>();
                    origins.add(ORIGIN);
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, carJs(), origins);
                }
            } catch (Throwable ignored) { }
        }

        assets = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/uvc/")) {
                    if (u.getPath().startsWith("/uvc/full")) return fullResponse();
                    if (u.getPath().startsWith("/uvc/rec") && recFile != null && recFile.exists()) return fileResponse(recFile, "video/mp4");
                    return frameResponse();
                }
                if (HOST.equals(u.getHost()) && "/assets/index.html".equals(u.getPath())) {
                    File wf = Updates.webFile(MainActivity.this);
                    if (wf.exists() && !BuildConfig.CAR) {
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
            public void onPageFinished(WebView v, String url) {
                if (BuildConfig.CAR) v.evaluateJavascript(carJs(), null);
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
                    if (hasCameraPerm() && !wantsMic(request)) request.grant(request.getResources());
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
                if (BuildConfig.CAR) goFull();
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
            }
        };
        web.setWebChromeClient(chrome);

        web.addJavascriptInterface(new Bridge(), "BesNative");
        web.loadUrl(START_URL);

        if (NATIVE_LIVE && !hasMicPerm()) {
            List<String> p = new ArrayList<>();
            p.add(Manifest.permission.RECORD_AUDIO);
            if (!hasCameraPerm()) p.add(Manifest.permission.CAMERA);
            if (needsLegacyWrite()) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            requestPermissions(p.toArray(new String[0]), REQ_PERMS);
        } else if (!hasCameraPerm() || needsLegacyWrite()) requestPerms();
        initCam();
        handleUsbIntent(getIntent());
        ui.postDelayed(this::openFirstUvc, 1200);
        // car build bundles an old-WebView-compatible copy of the screen; the live (modern JS) one would break it
        if (!BuildConfig.CAR) Updates.checkWeb(this, () -> js("webupdate"));
        else {
            try { Updates.webFile(this).delete(); } catch (Throwable ignored) { }
            try {
                android.content.pm.PackageInfo wv = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this);
                if (wv != null) status("WebView " + wv.versionName + " · Android SDK " + Build.VERSION.SDK_INT);
            } catch (Throwable ignored) { }
        }
        Updates.checkApp(this);
    }

    // ================= permissions =================
    private boolean hasCameraPerm() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMicPerm() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // web page asks for the microphone (video recording with sound) and Android hasn't allowed it yet
    private boolean wantsMic(PermissionRequest r) {
        if (r == null || hasMicPerm()) return false;
        for (String s : r.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(s)) return true;
        return false;
    }

    private boolean needsLegacyWrite() {
        return Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        List<String> p = new ArrayList<>();
        if (!hasCameraPerm()) p.add(Manifest.permission.CAMERA);
        if (wantsMic(pendingWebPerm)) p.add(Manifest.permission.RECORD_AUDIO);
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

    /** Car head units (weak 32-bit MediaTek, limited USB bandwidth): sizes to try in order, biggest MJPEG ≤720p first. */
    private List<Size> carSizes() {
        List<Size> list = null, out = new ArrayList<>();
        try { list = cam.getSupportedSizeList(); } catch (Throwable ignored) { }
        if (list == null) return out;
        List<Size> mj = new ArrayList<>(), other = new ArrayList<>();
        for (Size z : list) {
            // 640x480 MJPEG: 720p made the live view lag on the MT6580 head unit (tested 2026-10-29)
            if (isMjpeg(z) && z.width * z.height <= 640 * 480) mj.add(z);
            else if (!isMjpeg(z) && z.width * z.height <= 640 * 480) other.add(z);
        }
        java.util.Comparator<Size> big = (a, b) -> Integer.compare(b.width * b.height, a.width * a.height);
        java.util.Collections.sort(mj, big);
        java.util.Collections.sort(other, big);
        for (Size z : mj) { boolean dup = false; for (Size o : out) if (o.width == z.width && o.height == z.height) dup = true; if (!dup) out.add(z); }
        for (Size z : other) { boolean dup = false; for (Size o : out) if (o.width == z.width && o.height == z.height) dup = true; if (!dup) out.add(z); }
        return out;
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
                if (BuildConfig.CAR) {
                    if (isUvc(device)) status("لقيت المنظار (" + usbId(device) + ") – بفتحه…");
                    else status("اتوصل جهاز USB مش كاميرا (" + usbId(device) + ")");
                }
                if (!camOpen && isUvc(device)) cam.selectDevice(device);
            }

            @Override
            public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
                if (BuildConfig.CAR) { carOpen(device); return; }
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
                try { devId = String.format(java.util.Locale.US, "%04x:%04x", device.getVendorId(), device.getProductId()); } catch (Throwable ignored) { devId = ""; }
                try {
                    if (!resized && !BuildConfig.CAR) {
                        resized = true;
                        Size b = pickSize();
                        if (b != null) cam.setPreviewSize(b);
                    }
                } catch (Throwable ignored) { }
                try {
                    Size p = cam.getPreviewSize();
                    if (p != null) synchronized (frameLock) { fw = p.width; fh = p.height; }
                } catch (Throwable ignored) { }
                if (!NATIVE_LIVE) cam.setFrameCallback(MainActivity.this::onFrame, UVCCamera.PIXEL_FORMAT_NV21);
                try {
                    cam.setButtonCallback((button, st) -> { if (st == 1) onScopeButton(); });
                } catch (Throwable ignored) { }
                cam.startPreview();
                camOpen = true;
                if (NATIVE_LIVE) ui.post(() -> { liveAdded = false; attachLive(); applyLiveTransform(); });
                js("open");
            }

            @Override
            public void onCameraClose(UsbDevice device) {
                camOpen = false;
                if (NATIVE_LIVE) ui.post(MainActivity.this::detachLive);
                js("closed");
            }

            @Override
            public void onDeviceClose(UsbDevice device) { }

            @Override
            public void onDetach(UsbDevice device) {
                camOpen = false;
                sizeTry = 0;
                if (openWatch != null) ui.removeCallbacks(openWatch);
                js("detached");
            }

            @Override
            public void onCancel(UsbDevice device) {
                if (BuildConfig.CAR) status("صلاحية USB اترفضت – اضغط «↻ الكاميرات» ووافق على الرسالة");
                js("cancel");
            }
        });
    }

    private void carOpen(UsbDevice device) {
        List<Size> c = carSizes();
        Size z = sizeTry < c.size() ? c.get(sizeTry) : null;
        bestSize = z;
        resized = true;
        try {
            if (z != null) cam.openCamera(z);
            else cam.openCamera();
        } catch (Throwable t) {
            try { cam.openCamera(); } catch (Throwable ignored) { }
        }
        if (sizeTry > 0) status("بجرب دقة أقل: " + (z != null ? z.width + "x" + z.height : "الافتراضية"));
        final long seqAtOpen = seq;
        final int tries = c.size();
        if (openWatch != null) ui.removeCallbacks(openWatch);
        openWatch = () -> {
            if (seq != seqAtOpen) return;                 // frames are coming – all good
            if (sizeTry >= tries) {                       // tried every size, then the default
                status("المنظار اتعرف بس مش بيبعت صورة. جرّب كابل/بورت USB تاني أو hub بباور");
                return;
            }
            sizeTry++;
            camOpen = false;
            try { cam.closeCamera(); } catch (Throwable ignored) { }
            ui.postDelayed(() -> { if (!camOpen && cam != null) cam.selectDevice(device); }, 900);
        };
        if (!NATIVE_LIVE) ui.postDelayed(openWatch, 5000);
    }

    private static String usbId(UsbDevice d) {
        try { return String.format(java.util.Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId()); } catch (Throwable t) { return "?"; }
    }

    private void status(String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void openFirstUvc() { openFirstUvc(false); }

    private void openFirstUvc(boolean manual) {
        if (cam == null || camOpen) return;
        try {
            List<UsbDevice> list = cam.getDeviceList();
            if (list == null) list = new ArrayList<>();
            for (UsbDevice d : list) {
                if (isUvc(d)) { cam.selectDevice(d); return; }
            }
            if (BuildConfig.CAR) {
                // nothing that looks like a camera: say what the head unit does see (helps pick the right port/cable)
                long now = SystemClock.uptimeMillis();
                if (!manual && now - lastNoUsbToast < 15000) return;
                lastNoUsbToast = now;
                if (list.isEmpty()) status("الشاشة مش شايفة أي جهاز USB – جرّب كابل USB تاني من الشاشة");
                else {
                    StringBuilder b = new StringBuilder();
                    for (UsbDevice d : list) b.append(b.length() > 0 ? "، " : "").append(usbId(d));
                    status("أجهزة USB موجودة بس مفيش كاميرا: " + b);
                }
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
            camFrames++;
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
        if (BuildConfig.CAR && w > 800 && w % 4 == 0 && h % 4 == 0) {   // car: half-size live view (fast on weak CPUs)
            int w2 = w / 2, h2 = h / 2;
            if (half == null || half.length != w2 * h2 * 3 / 2) half = new byte[w2 * h2 * 3 / 2];
            halfNv21(work, w, h, half);
            return jpeg(half, w2, h2, 70);
        }
        try {
            YuvImage yi = new YuvImage(work, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 4);
            long e0 = SystemClock.uptimeMillis();
            yi.compressToJpeg(new Rect(0, 0, w, h), BuildConfig.CAR ? 70 : 88, bos);
            encMs = (encMs * 7 + (SystemClock.uptimeMillis() - e0)) / 8;
            Map<String, String> hd = new HashMap<>();
            hd.put("Cache-Control", "no-store");
            hd.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("image/jpeg", null, 200, "OK", hd, new ByteArrayInputStream(bos.toByteArray()));
        } catch (Throwable t) {
            return empty();
        }
    }

    private byte[] half, fullWork;
    private volatile long encMs = 0, camFrames = 0;

    // ---- car edition: native live view (TextureView under a transparent WebView) ----
    static final boolean NATIVE_LIVE = BuildConfig.CAR;
    private FrameLayout stageBox;
    private TextureView liveView;
    private SurfaceTexture liveTex;
    private boolean liveAdded = false;
    private int stX, stY, stW, stH;            // stage rect in screen px
    private float vRot = 0, vSx = 1, vSy = 1;  // rotation / mirror+zoom from the exam screen
    private volatile String recState = "idle";
    private File recFile;

    /** NV21 → NV21 at half width/height (drop every other pixel / chroma pair). */
    private static void halfNv21(byte[] src, int w, int h, byte[] dst) {
        int w2 = w / 2, h2 = h / 2, i = 0;
        for (int y = 0; y < h2; y++) {
            int r = 2 * y * w;
            for (int x = 0; x < w2; x++) dst[i++] = src[r + 2 * x];
        }
        int so = w * h;
        for (int y = 0; y < h2 / 2; y++) {
            int r = so + 2 * y * w;
            for (int x = 0; x < w2 / 2; x++) { dst[i++] = src[r + 4 * x]; dst[i++] = src[r + 4 * x + 1]; }
        }
    }

    private WebResourceResponse jpeg(byte[] nv21, int w, int h, int q) {
        try {
            YuvImage yi = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 4);
            yi.compressToJpeg(new Rect(0, 0, w, h), q, bos);
            Map<String, String> hd = new HashMap<>();
            hd.put("Cache-Control", "no-store");
            hd.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("image/jpeg", null, 200, "OK", hd, new ByteArrayInputStream(bos.toByteArray()));
        } catch (Throwable t) {
            return empty();
        }
    }

    /** Latest frame at the camera's full size (used for photos while the live view runs at half size). */
    private WebResourceResponse fullResponse() {
        int w, h;
        if (NATIVE_LIVE && camOpen) grabOneFrame();
        synchronized (frameLock) {
            if (frame == null) return empty();
            if (fullWork == null || fullWork.length != frame.length) fullWork = new byte[frame.length];
            System.arraycopy(frame, 0, fullWork, 0, frame.length);
            w = fw; h = fh;
        }
        return jpeg(fullWork, w, h, 92);
    }

    /** Native live view has no per-frame callback: switch it on just long enough to get one fresh frame. */
    private void grabOneFrame() {
        long before;
        synchronized (frameLock) { before = seq; }
        try { cam.setFrameCallback(MainActivity.this::onFrame, UVCCamera.PIXEL_FORMAT_NV21); } catch (Throwable t) { return; }
        synchronized (frameLock) {
            long until = SystemClock.uptimeMillis() + 1200;
            while (seq == before) {
                long wait = until - SystemClock.uptimeMillis();
                if (wait <= 0) break;
                try { frameLock.wait(wait); } catch (InterruptedException e) { break; }
            }
        }
        try { cam.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_NV21); } catch (Throwable ignored) { }
    }

    // ---------------- native live view helpers ----------------
    private void attachLive() {
        if (!NATIVE_LIVE || cam == null || liveTex == null || !camOpen || liveAdded) return;
        try { cam.addSurface(liveTex, false); liveAdded = true; } catch (Throwable ignored) { }
    }

    private void detachLive() {
        if (!liveAdded) return;
        liveAdded = false;
        try { if (cam != null && liveTex != null) cam.removeSurface(liveTex); } catch (Throwable ignored) { }
    }

    /** object-fit:contain + rotation / mirror / zoom from the exam screen, applied on the GPU by the TextureView. */
    private void applyLiveTransform() {
        if (liveView == null) return;
        float W = liveView.getWidth(), H = liveView.getHeight();
        if (W <= 0 || H <= 0) return;
        float cw0, ch0;
        synchronized (frameLock) { cw0 = fw; ch0 = fh; }
        boolean swap = Math.round(vRot) % 180 != 0;
        float dw0 = swap ? ch0 : cw0, dh0 = swap ? cw0 : ch0;
        float k = Math.min(W / dw0, H / dh0);
        float cw = (swap ? dh0 : dw0) * k, ch = (swap ? dw0 : dh0) * k;
        Matrix m = new Matrix();
        m.postScale(cw / W, ch / H);
        m.postTranslate((W - cw) / 2f, (H - ch) / 2f);
        m.postRotate(vRot, W / 2f, H / 2f);
        m.postScale(vSx, vSy, W / 2f, H / 2f);
        liveView.setTransform(m);
        liveView.invalidate();
    }

    private WebResourceResponse fileResponse(File f, String mime) {
        try {
            Map<String, String> hd = new HashMap<>();
            hd.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, null, 200, "OK", hd, new FileInputStream(f));
        } catch (Exception e) { return empty(); }
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
        public boolean isCar() { return BuildConfig.CAR; }

        /** car perf overlay: camera frames so far, average JPEG encode ms, frame size */
        @JavascriptInterface
        public String perf() { synchronized (frameLock) { return camFrames + "," + encMs + "," + fw + "x" + fh; } }

        /** "WxH" of the camera when the live view is downscaled (photos should use /uvc/full.jpg), else "". */
        @JavascriptInterface
        public String fullSize() { synchronized (frameLock) { return (BuildConfig.CAR && camOpen && (fw > 800 || NATIVE_LIVE)) ? fw + "x" + fh : ""; } }

        @JavascriptInterface
        public boolean nativeLive() { return NATIVE_LIVE; }

        /** stage rect in CSS px + the page's CSS width (to scale to screen px) */
        @JavascriptInterface
        public void stageRect(float x, float y, float w, float h, float cssWidth) {
            ui.post(() -> {
                if (stageBox == null || web == null || cssWidth <= 0) return;
                float s = web.getWidth() / cssWidth;
                int nx = Math.round(x * s), ny = Math.round(y * s), nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
                if (nx == stX && ny == stY && nw == stW && nh == stH) return;
                stX = nx; stY = ny; stW = nw; stH = nh;
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(nw, nh);
                lp.leftMargin = nx; lp.topMargin = ny;
                stageBox.setLayoutParams(lp);
                liveView.post(MainActivity.this::applyLiveTransform);
            });
        }

        @JavascriptInterface
        public void viewTransform(float rot, float sx, float sy) {
            ui.post(() -> { vRot = rot; vSx = sx; vSy = sy; applyLiveTransform(); });
        }

        @JavascriptInterface
        public boolean recStart(boolean audio) {
            if (!NATIVE_LIVE || cam == null || !camOpen) return false;
            try {
                recFile = new File(getCacheDir(), "rec.mp4");
                if (recFile.exists()) recFile.delete();
                VideoCaptureConfig cfg = cam.getVideoCaptureConfig();
                if (cfg != null) {
                    cfg.setAudioCaptureEnable(audio && hasMicPerm());
                    cfg.setBitRate(2_500_000);
                    cam.setVideoCaptureConfig(cfg);
                }
                recState = "recording";
                cam.startRecording(new VideoCapture.OutputFileOptions.Builder(recFile).build(), new VideoCapture.OnVideoCaptureCallback() {
                    @Override public void onStart() { }
                    @Override public void onVideoSaved(VideoCapture.OutputFileResults r) { recState = "saved"; }
                    @Override public void onError(int code, String msg, Throwable t) { recState = "error:" + code + " " + msg; }
                });
                return true;
            } catch (Throwable t) {
                recState = "error:" + t;
                return false;
            }
        }

        @JavascriptInterface
        public void recStop() { try { if (cam != null) cam.stopRecording(); } catch (Throwable t) { recState = "error:" + t; } }

        @JavascriptInterface
        public String recState() { return recState; }

        @JavascriptInterface
        public String frameSize() { synchronized (frameLock) { return fw + "x" + fh; } }

        @JavascriptInterface
        public String version() { return "android-1"; }

        @JavascriptInterface
        public String deviceId() { return camOpen ? devId : ""; }

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
            if (BuildConfig.CAR) {
                List<File> fs = new ArrayList<>(); List<String> ms = new ArrayList<>();
                for (String id : idsCsv.split(",")) {
                    File f; String mime;
                    synchronized (outFiles) { f = outFiles.get(id); mime = outMime.get(id); }
                    if (f != null) { fs.add(f); ms.add(mime); }
                }
                if (!fs.isEmpty()) { ui.post(() -> LocalShare.show(MainActivity.this, fs, ms, title, () -> chooserShare(idsCsv, title))); return; }
            }
            chooserShare(idsCsv, title);
        }

        void chooserShare(String idsCsv, String title) {
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            for (String id : idsCsv.split(",")) {
                File f; String mime;
                synchronized (outFiles) { f = outFiles.get(id); mime = outMime.get(id); }
                if (f == null) continue;
                uris.add(FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f));
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
        public void rescan() { ui.post(() -> { if (!camOpen) { sizeTry = 0; openFirstUvc(true); } }); }

        @JavascriptInterface
        public void logoProbe() {
            if (!BuildConfig.CAR) return;
            ui.post(() -> {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 9);
                LogoProbe.run(MainActivity.this);
            });
        }

        @JavascriptInterface
        public void bootAnim(boolean install) {
            if (!BuildConfig.CAR) return;
            ui.post(() -> { if (install) BootAnim.install(MainActivity.this); else BootAnim.restore(MainActivity.this); });
        }

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
        if (BuildConfig.CAR) goFull();
        Updates.onResume(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && BuildConfig.CAR && customView == null) goFull();
    }

    /** Car screen: hide the head unit's status + navigation bars (swipe from the edge shows them for a moment). */
    @SuppressWarnings("deprecation")
    private void goFull() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /**
     * Car screen: lay the exam screen out at a fixed 1024 CSS px width whatever the head unit's density is
     * (some report 1.5x and fall into the phone layout), then apply the landscape layout from assets/car.css.
     */
    private String carJs() {
        String css = "";
        try { css = new String(Updates.read(getAssets().open("car.css")), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ignored) { }
        css = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
        return "(function(){if(window.__mmCar)return;window.__mmCar=1;var CSS='" + css + "';"
                + "var ne=0;window.addEventListener('error',function(e){if(ne++<3&&window.BesNative)try{BesNative.toast('JS: '+(e.message||'error')+' @'+(e.lineno||''))}catch(x){}});"
                + "var apply=function(){var h=document.head||document.documentElement;"
                + "var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';h.appendChild(m);}"
                + "m.content='width=1024, user-scalable=no';"
                + "if(!document.getElementById('mmCar')){var s=document.createElement('style');s.id='mmCar';s.textContent=CSS;h.appendChild(s);}"
                + "var t=document.getElementById('toolsBox');if(t&&!t.dataset.car){t.dataset.car='1';t.removeAttribute('open');t.addEventListener('toggle',function(){if(t.open)setTimeout(function(){t.scrollIntoView(false)},60)});}"
                + "var ds=document.getElementById('dlgSettings');if(ds&&!document.getElementById('mmProbe')){var pb=document.createElement('button');pb.id='mmProbe';pb.className='ghost';pb.type='button';pb.textContent='🛠 تشخيص لوجو التشغيل';pb.style.margin='12px 0';pb.onclick=function(){try{BesNative.logoProbe()}catch(x){}};(ds.querySelector('.dlg-b')||ds).appendChild(pb);"
                + "var mk=function(id,t,v){var x=document.createElement('button');x.id=id;x.className='ghost';x.type='button';x.textContent=t;x.style.margin='0 0 8px 8px';x.onclick=function(){try{BesNative.bootAnim(v)}catch(e){}};(ds.querySelector('.dlg-b')||ds).appendChild(x);};"
                + "mk('mmBa1','🎬 أنيميشن تشغيل ميديكا مول',true);mk('mmBa0','↺ أنيميشن المصنع',false);}"
                // no-op filter/transform on the live <video> still forces a GPU filter pass every frame on Mali-400: drop them
                + "var NOF='brightness(100%) contrast(100%) saturate(100%)';var fix=function(el){if(!el)return;if(el.style.filter===NOF)el.style.filter='none';var tf=el.style.transform;if(tf&&tf!=='none')el.setAttribute('data-mmtf',tf);else if(tf==='none'&&!el.getAttribute('data-mmtf'))el.setAttribute('data-mmtf','');if(tf==='rotate(0deg) scale(1, 1)'||tf==='rotate(0deg) scale(1)')el.setAttribute('data-mmtf','');if(tf==='rotate(0deg) scale(1, 1)'||tf==='rotate(0deg) scale(1)')el.style.transform='none';el.style.transition='none';};"
                + "['video','freeze'].forEach(function(id){var el=document.getElementById(id);if(!el||el.dataset.mmobs)return;el.dataset.mmobs='1';fix(el);new MutationObserver(function(){fix(el)}).observe(el,{attributes:true,attributeFilter:['style']});});"
                // native live view: make the page transparent over the stage, report its rect + rotation/mirror/zoom to Android
                + "if(window.BesNative&&BesNative.nativeLive&&BesNative.nativeLive()&&!window.__mmNat){window.__mmNat=1;"
                + "var ns=document.createElement('style');ns.textContent='html,body,main,main>section.card,.stage{background:transparent!important;box-shadow:none!important}#video{opacity:0!important}.stage .placeholder{background:transparent}';(document.head||document.documentElement).appendChild(ns);"
                + "var last='';var rep=function(){var st=document.getElementById('stage');if(!st)return;var r=st.getBoundingClientRect();var k=[r.left,r.top,r.width,r.height,window.innerWidth].map(Math.round).join(',');if(k===last)return;last=k;try{BesNative.stageRect(r.left,r.top,r.width,r.height,window.innerWidth)}catch(e){}};"
                + "rep();setInterval(rep,500);window.addEventListener('resize',rep);document.addEventListener('scroll',rep,true);"
                + "var lt='';var tr=function(){var v=document.getElementById('video');if(!v)return;var raw=v.getAttribute('data-mmtf')||'';if(raw===lt)return;lt=raw;var m=/rotate\\(([-\\d.]+)deg\\)\\s*scale\\(([-\\d.]+)(?:,\\s*([-\\d.]+))?\\)/.exec(raw);var rot=0,sx=1,sy=1;if(m){rot=+m[1];sx=+m[2];sy=m[3]!==undefined?+m[3]:sx;}try{BesNative.viewTransform(rot,sx,sy)}catch(e){}};setInterval(tr,300);}"
                // small perf readout in the corner of the live view (test builds)
                + "if(!document.getElementById('mmPerf')&&window.BesNative&&BesNative.perf){var st=document.getElementById('stage');if(st){var pv=document.createElement('div');pv.id='mmPerf';pv.style.cssText='position:absolute;left:8px;bottom:8px;z-index:5;background:rgba(0,0,0,.55);color:#fff;font:12px monospace;padding:2px 8px;border-radius:8px;direction:ltr;pointer-events:none';st.appendChild(pv);var lc=0,ls=0;setInterval(function(){try{var p=String(BesNative.perf()).split(',');var c=+p[0],s=(window.__mmPerf||{}).shown||0;pv.textContent=(window.__mmNat?('live '+(c-lc)+'fps · native · '+p[2]):('cam '+(c-lc)+'fps · screen '+(s-ls)+'fps · jpeg '+p[1]+'ms · '+p[2]));lc=c;ls=s;}catch(e){}},1000);}}"
                + "document.documentElement.classList.add('mmcar');};"
                // on-screen keyboard covers the bottom half of a 600px screen: while typing in a popup, shrink it to the top
                + "var isField=function(e){return e&&e.matches&&e.matches('input:not([type=range]):not([type=file]):not([type=checkbox]):not([type=radio]),textarea')&&e.closest&&e.closest('dialog');};"
                + "document.addEventListener('focusin',function(e){if(isField(e.target)){document.documentElement.classList.add('kb');setTimeout(function(){try{e.target.scrollIntoView({block:'center'})}catch(x){}},250);}},true);"
                + "document.addEventListener('focusout',function(e){if(isField(e.target))setTimeout(function(){if(!isField(document.activeElement))document.documentElement.classList.remove('kb');},150);},true);"
                + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',apply);else apply();})();";
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
