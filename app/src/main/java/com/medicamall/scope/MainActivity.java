package com.medicamall.scope;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.view.Gravity;
import android.view.KeyEvent;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    static final String START_URL = ORIGIN + "/assets/index.html";
    static final int REQ_PERMS = 7, REQ_FILE = 42;
    static final int WIFI_PORT = 8080;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private WebViewAssetLoader assets;

    // ---- TV / remote UI ----
    private ImageView tvPreview;
    private TextView tvStatus;
    private TextView scopeButtonIndicator;
    private int scopeButtonCount = 0;
    private Runnable pendingSingleScopePress;
    private static final long DOUBLE_PRESS_MS = 430;
    private boolean videoRecording = false;
    private boolean videoTransition = false;
    private long videoStartedAt = 0;
    private Runnable recordingTicker;
    private View captureFlash;
    private LinearLayout tvControls;
    private Button retryButton;
    private Button screenButton;
    private Button wifiButton;
    private Button qualityButton;
    private Button rotateButton;
    private Button exitButton;
    private final List<Size> qualitySizes = new ArrayList<>();
    private int qualityIndex = -1;
    private Size lastGoodQuality = null;
    private long lastFrameAt = 0;
    private int qualityGeneration = 0;
    private boolean showWebScreen = false;
    private int previewRotation = 0;
    private Bitmap lastTvBitmap;
    private final ExecutorService previewExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean previewBusy = new AtomicBoolean(false);
    private long lastPreviewQueued = 0;
    private long fpsWindowStart = 0;
    private long fpsFrames = 0;
    private float lastFps = 0f;

    private WifiStreamServer wifiServer;
    private byte[] wifiJpeg;
    private long lastWifiEncode = 0;

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

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setVisibility(View.GONE);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        tvPreview = new ImageView(this);
        tvPreview.setBackgroundColor(Color.BLACK);
        tvPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(tvPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        tvStatus = new TextView(this);
        tvStatus.setText("TV TEST • في انتظار المنظار");
        tvStatus.setTextColor(Color.WHITE);
        tvStatus.setTextSize(18);
        tvStatus.setPadding(dp(16), dp(10), dp(16), dp(10));
        tvStatus.setBackgroundColor(0xAA000000);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        statusLp.setMargins(dp(14), dp(14), dp(14), dp(14));
        root.addView(tvStatus, statusLp);

        scopeButtonIndicator = new TextView(this);
        scopeButtonIndicator.setText("زرار المنظار: 0");
        scopeButtonIndicator.setTextColor(Color.WHITE);
        scopeButtonIndicator.setTextSize(20);
        scopeButtonIndicator.setGravity(Gravity.CENTER);
        scopeButtonIndicator.setPadding(dp(18), dp(10), dp(18), dp(10));
        scopeButtonIndicator.setBackgroundColor(0xCC116EB5);
        FrameLayout.LayoutParams buttonIndicatorLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        buttonIndicatorLp.setMargins(dp(14), dp(14), dp(14), dp(14));
        root.addView(scopeButtonIndicator, buttonIndicatorLp);

        captureFlash = new View(this);
        captureFlash.setBackgroundColor(Color.WHITE);
        captureFlash.setAlpha(0f);
        captureFlash.setVisibility(View.GONE);
        root.addView(captureFlash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setupTvControls();
        setContentView(root);

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
        // Side-by-side Wi-Fi test build: do not invoke the production APK updater.
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

        // Quality mode for the X96: use the largest MJPEG size that stays within
        // roughly one megapixel. The first diagnostic build deliberately picked
        // the smallest stream (400x400); the box proved stable at 30 FPS, so we
        // can now step up quality without jumping straight to a multi-megapixel stream.
        Size bestMjpeg = null;
        Size bestAny = null;
        final int MAX_PIXELS = 1280 * 960;
        for (Size z : list) {
            int pixels = z.width * z.height;
            if (pixels <= MAX_PIXELS) {
                if (isMjpeg(z) && (bestMjpeg == null
                        || pixels > bestMjpeg.width * bestMjpeg.height)) {
                    bestMjpeg = z;
                }
                if (bestAny == null || pixels > bestAny.width * bestAny.height) {
                    bestAny = z;
                }
            }
        }
        if (bestMjpeg != null) return bestMjpeg;
        if (bestAny != null) return bestAny;

        // If every advertised mode is larger, fall back to the smallest MJPEG
        // rather than failing the camera open.
        Size smallestMjpeg = null, smallestAny = null;
        for (Size z : list) {
            int pixels = z.width * z.height;
            if (isMjpeg(z) && (smallestMjpeg == null
                    || pixels < smallestMjpeg.width * smallestMjpeg.height)) {
                smallestMjpeg = z;
            }
            if (smallestAny == null || pixels < smallestAny.width * smallestAny.height) {
                smallestAny = z;
            }
        }
        return smallestMjpeg != null ? smallestMjpeg : smallestAny;
    }

    private void initCam() {
        if (cam != null) return;
        cam = new CameraHelper();
        cam.setStateCallback(new ICameraHelper.StateCallback() {
            @Override
            public void onAttach(UsbDevice device) {
                updateTvStatus("تم اكتشاف USB للمنظار • جاري طلب الاتصال");
                if (!camOpen && isUvc(device)) cam.selectDevice(device);
            }

            @Override
            public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
                updateTvStatus("تم السماح بالـ USB • جاري فتح الكاميرا");
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
                refreshQualitySizes();
                try {
                    Size current = cam.getPreviewSize();
                    if (current != null) {
                        qualityIndex = findQualityIndex(current);
                        if (qualityButton != null) {
                            qualityButton.setText("الجودة " + current.width + "×" + current.height);
                        }
                    }
                } catch (Throwable ignored) { }

                // The device is now fully opened; this is the reliable point to ask it
                // for every UVC mode. If a higher mode exists, try one step above the
                // default 400x400 automatically, with watchdog fallback.
                ui.postDelayed(MainActivity.this::tryOneHigherQualityAutomatically, 700);

                try {
                    VideoCaptureConfig vc = cam.getVideoCaptureConfig();
                    vc.setAudioCaptureEnable(false)
                            .setVideoFrameRate(30)
                            .setBitRate(Math.max(4 * 1024 * 1024, fw * fh * 10));
                    cam.setVideoCaptureConfig(vc);
                } catch (Throwable ignored) { }
                cam.setFrameCallback(MainActivity.this::onFrame, UVCCamera.PIXEL_FORMAT_NV21);
                try {
                    cam.setButtonCallback((button, st) -> { if (st == 1) onScopeButton(); });
                } catch (Throwable ignored) { }
                cam.startPreview();
                camOpen = true;
                updateTvStatus("المنظار متصل • " + fw + "×" + fh + " • في انتظار أول Frame");
                js("open");
            }

            @Override
            public void onCameraClose(UsbDevice device) {
                camOpen = false;
                updateTvStatus("الكاميرا اتقفلت");
                js("closed");
            }

            @Override
            public void onDeviceClose(UsbDevice device) { }

            @Override
            public void onDetach(UsbDevice device) {
                camOpen = false;
                updateTvStatus("المنظار اتفصل");
                clearTvPreview();
                js("detached");
            }

            @Override
            public void onCancel(UsbDevice device) {
                updateTvStatus("تم إلغاء صلاحية USB");
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

        long now = SystemClock.uptimeMillis();
        lastFrameAt = now;
        if (fpsWindowStart == 0) fpsWindowStart = now;
        fpsFrames++;
        if (now - fpsWindowStart >= 1000) {
            lastFps = fpsFrames * 1000f / Math.max(1, now - fpsWindowStart);
            fpsFrames = 0;
            fpsWindowStart = now;
            if (!videoRecording && !videoTransition) {
                updateTvStatus("LIVE • " + fw + "×" + fh + " • "
                        + String.format(Locale.US, "%.1f", lastFps) + " FPS");
            }
        }
        queueTvPreview();
    }

    private void onScopeButton() {
        long now = SystemClock.uptimeMillis();
        if (now - lastButton < 150) return; // hardware debounce
        lastButton = now;
        ui.post(this::handleScopePressOnUi);
    }

    private void handleScopePressOnUi() {
        scopeButtonCount++;
        final int count = scopeButtonCount;

        if (scopeButtonIndicator != null && !videoRecording) {
            scopeButtonIndicator.setText("زرار المنظار: " + count + " ✓");
            scopeButtonIndicator.setBackgroundColor(0xFF1B8F3A);
        }

        if (pendingSingleScopePress != null) {
            // Second press inside the window: this is a double press.
            ui.removeCallbacks(pendingSingleScopePress);
            pendingSingleScopePress = null;
            toggleVideoRecording();
            return;
        }

        pendingSingleScopePress = () -> {
            pendingSingleScopePress = null;
            captureStillPhoto();
        };
        ui.postDelayed(pendingSingleScopePress, DOUBLE_PRESS_MS);
    }

    private void captureStillPhoto() {
        if (!camOpen || frame == null) {
            updateTvStatus("الصورة لم تُحفظ • المنظار غير جاهز");
            return;
        }
        if (videoTransition) {
            updateTvStatus("انتظر لحظة حتى يكتمل تغيير حالة التسجيل");
            return;
        }

        byte[] copy;
        int w, h;
        synchronized (frameLock) {
            if (frame == null) return;
            copy = new byte[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            w = fw;
            h = fh;
        }

        flashCapture();
        previewExec.execute(() -> {
            Uri saved = null;
            try {
                String name = "Scope_" + fileStamp() + ".jpg";
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= 29) {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/MedicaMall");
                }
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert failed");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("output stream failed");
                    YuvImage yi = new YuvImage(copy, ImageFormat.NV21, w, h, null);
                    if (!yi.compressToJpeg(new Rect(0, 0, w, h), 97, out)) {
                        throw new Exception("jpeg encode failed");
                    }
                }
                saved = uri;
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? "unknown" : t.getMessage();
                ui.post(() -> updateTvStatus("خطأ في حفظ الصورة • " + msg));
            }

            final Uri result = saved;
            if (result != null) {
                ui.post(() -> {
                    updateTvStatus("تم حفظ الصورة ✓ • " + fw + "×" + fh);
                    if (scopeButtonIndicator != null && !videoRecording) {
                        scopeButtonIndicator.setText("📷 صورة محفوظة ✓");
                        scopeButtonIndicator.setBackgroundColor(0xFF1B8F3A);
                        ui.postDelayed(() -> resetScopeIndicator(), 1200);
                    }
                });
            }
        });
    }

    private void toggleVideoRecording() {
        if (videoTransition) return;
        if (cam == null || !camOpen) {
            updateTvStatus("لا يمكن التسجيل • المنظار غير متصل");
            return;
        }

        if (videoRecording || cam.isRecording()) {
            stopVideoRecording();
        } else {
            startVideoRecording();
        }
    }

    private void startVideoRecording() {
        videoTransition = true;
        updateTvStatus("جاري بدء تسجيل الفيديو...");

        try {
            String name = "Scope_" + fileStamp() + ".mp4";
            VideoCapture.OutputFileOptions options;

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/MedicaMall");
                options = new VideoCapture.OutputFileOptions.Builder(
                        getContentResolver(),
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        cv).build();
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "MedicaMall");
                dir.mkdirs();
                options = new VideoCapture.OutputFileOptions.Builder(
                        new File(dir, name)).build();
            }

            cam.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
                @Override
                public void onStart() {
                    ui.post(() -> {
                        videoTransition = false;
                        videoRecording = true;
                        videoStartedAt = SystemClock.uptimeMillis();
                        startRecordingTicker();
                        updateTvStatus("● REC • " + fw + "×" + fh + " • اضغط مرتين للإيقاف");
                    });
                }

                @Override
                public void onVideoSaved(VideoCapture.OutputFileResults outputFileResults) {
                    ui.post(() -> {
                        videoTransition = false;
                        videoRecording = false;
                        stopRecordingTicker();
                        updateTvStatus("تم حفظ الفيديو ✓ • Movies/MedicaMall");
                        if (scopeButtonIndicator != null) {
                            scopeButtonIndicator.setText("🎥 فيديو محفوظ ✓");
                            scopeButtonIndicator.setBackgroundColor(0xFF1B8F3A);
                            ui.postDelayed(() -> resetScopeIndicator(), 1600);
                        }
                    });
                }

                @Override
                public void onError(int videoCaptureError, String message, Throwable cause) {
                    ui.post(() -> {
                        videoTransition = false;
                        videoRecording = false;
                        stopRecordingTicker();
                        updateTvStatus("خطأ في تسجيل الفيديو • " + message);
                        if (scopeButtonIndicator != null) {
                            scopeButtonIndicator.setText("خطأ تسجيل");
                            scopeButtonIndicator.setBackgroundColor(0xFFC62828);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            videoTransition = false;
            videoRecording = false;
            updateTvStatus("تعذر بدء التسجيل • " + t.getMessage());
        }
    }

    private void stopVideoRecording() {
        if (cam == null) return;
        videoTransition = true;
        updateTvStatus("جاري إيقاف وحفظ الفيديو...");
        try {
            cam.stopRecording();
        } catch (Throwable t) {
            videoTransition = false;
            updateTvStatus("تعذر إيقاف التسجيل • " + t.getMessage());
        }
    }

    private void startRecordingTicker() {
        stopRecordingTicker();
        recordingTicker = new Runnable() {
            @Override
            public void run() {
                if (!videoRecording) return;
                long sec = Math.max(0, (SystemClock.uptimeMillis() - videoStartedAt) / 1000);
                long min = sec / 60;
                sec %= 60;
                if (scopeButtonIndicator != null) {
                    scopeButtonIndicator.setText(String.format(Locale.US,
                            "● REC %02d:%02d", min, sec));
                    scopeButtonIndicator.setBackgroundColor(0xFFD32F2F);
                }
                ui.postDelayed(this, 1000);
            }
        };
        recordingTicker.run();
    }

    private void stopRecordingTicker() {
        if (recordingTicker != null) {
            ui.removeCallbacks(recordingTicker);
            recordingTicker = null;
        }
    }

    private void resetScopeIndicator() {
        if (scopeButtonIndicator == null || videoRecording) return;
        scopeButtonIndicator.setText("زرار المنظار: " + scopeButtonCount);
        scopeButtonIndicator.setBackgroundColor(0xCC116EB5);
    }

    private void flashCapture() {
        if (captureFlash == null) return;
        captureFlash.setVisibility(View.VISIBLE);
        captureFlash.setAlpha(0.65f);
        captureFlash.animate().alpha(0f).setDuration(130)
                .withEndAction(() -> captureFlash.setVisibility(View.GONE)).start();
    }

    private String fileStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
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


    // ================= TV / remote controls =================
    private void setupTvControls() {
        tvControls = new LinearLayout(this);
        tvControls.setOrientation(LinearLayout.HORIZONTAL);
        tvControls.setGravity(Gravity.CENTER);
        tvControls.setPadding(dp(12), dp(8), dp(12), dp(8));
        tvControls.setBackgroundColor(0xCC111111);

        retryButton = makeTvButton("إعادة توصيل");
        screenButton = makeTvButton("واجهة المرضى");
        wifiButton = makeTvButton("Wi-Fi");
        qualityButton = makeTvButton("الجودة");
        rotateButton = makeTvButton("تدوير");
        exitButton = makeTvButton("خروج");

        retryButton.setOnClickListener(v -> reconnectScope());
        screenButton.setOnClickListener(v -> toggleScreenMode());
        wifiButton.setOnClickListener(v -> toggleWifiStream());
        qualityButton.setOnClickListener(v -> cycleQuality());
        rotateButton.setOnClickListener(v -> rotatePreview());
        exitButton.setOnClickListener(v -> finish());

        tvControls.addView(retryButton);
        tvControls.addView(screenButton);
        tvControls.addView(wifiButton);
        tvControls.addView(qualityButton);
        tvControls.addView(rotateButton);
        tvControls.addView(exitButton);

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(tvControls, controlsLp);

        // Explicit D-pad order for cheap TV remotes.
        retryButton.setId(View.generateViewId());
        screenButton.setId(View.generateViewId());
        wifiButton.setId(View.generateViewId());
        qualityButton.setId(View.generateViewId());
        rotateButton.setId(View.generateViewId());
        exitButton.setId(View.generateViewId());

        retryButton.setNextFocusRightId(screenButton.getId());
        screenButton.setNextFocusLeftId(retryButton.getId());
        screenButton.setNextFocusRightId(wifiButton.getId());
        wifiButton.setNextFocusLeftId(screenButton.getId());
        wifiButton.setNextFocusRightId(qualityButton.getId());
        qualityButton.setNextFocusLeftId(wifiButton.getId());
        qualityButton.setNextFocusRightId(rotateButton.getId());
        rotateButton.setNextFocusLeftId(qualityButton.getId());
        rotateButton.setNextFocusRightId(exitButton.getId());
        exitButton.setNextFocusLeftId(rotateButton.getId());

        ui.postDelayed(() -> retryButton.requestFocus(), 350);
    }

    private Button makeTvButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setMinHeight(dp(58));
        b.setPadding(dp(18), dp(6), dp(18), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.06f : 1f);
            v.setScaleY(hasFocus ? 1.06f : 1f);
            b.setTextColor(hasFocus ? Color.BLACK : Color.WHITE);
            b.setBackgroundColor(hasFocus ? 0xFFFFFFFF : 0xFF116EB5);
        });
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF116EB5);
        return b;
    }

    private void updateTvStatus(String msg) {
        ui.post(() -> {
            if (tvStatus != null) tvStatus.setText("TV TEST • " + msg);
        });
    }

    private void queueTvPreview() {
        if (showWebScreen || tvPreview == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastPreviewQueued < 100) return; // target about 10 fps for the TV-box diagnostic build
        if (!previewBusy.compareAndSet(false, true)) return;
        lastPreviewQueued = now;

        byte[] copy;
        int w, h;
        synchronized (frameLock) {
            if (frame == null) {
                previewBusy.set(false);
                return;
            }
            copy = new byte[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            w = fw;
            h = fh;
        }

        previewExec.execute(() -> {
            Bitmap bmp = null;
            try {
                YuvImage yi = new YuvImage(copy, ImageFormat.NV21, w, h, null);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64 * 1024, w * h / 5));
                if (yi.compressToJpeg(new Rect(0, 0, w, h), 82, bos)) {
                    byte[] jpg = bos.toByteArray();
                    bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                }
            } catch (Throwable ignored) { }

            final Bitmap ready = bmp;
            ui.post(() -> {
                if (ready != null && tvPreview != null && !showWebScreen) {
                    Bitmap old = lastTvBitmap;
                    lastTvBitmap = ready;
                    tvPreview.setImageBitmap(ready);
                    if (old != null && old != ready && !old.isRecycled()) old.recycle();
                }
                previewBusy.set(false);
            });
        });
    }

    private void clearTvPreview() {
        ui.post(() -> {
            if (tvPreview != null) tvPreview.setImageDrawable(null);
            Bitmap old = lastTvBitmap;
            lastTvBitmap = null;
            if (old != null && !old.isRecycled()) old.recycle();
        });
    }

    private void toggleScreenMode() {
        showWebScreen = !showWebScreen;
        web.setVisibility(showWebScreen ? View.VISIBLE : View.GONE);
        tvPreview.setVisibility(showWebScreen ? View.GONE : View.VISIBLE);
        screenButton.setText(showWebScreen ? "صورة المنظار" : "واجهة المرضى");

        if (showWebScreen) {
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.requestFocus();
            ui.postDelayed(() -> {
                if (web != null) {
                    web.evaluateJavascript(
                            "window.__mmTvFocusFirst&&window.__mmTvFocusFirst()", null);
                }
            }, 180);
        } else {
            web.clearFocus();
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
            if (retryButton != null) retryButton.requestFocus();
        }

        updateTvStatus(showWebScreen
                ? "واجهة المرضى • الأسهم للحركة و OK للاختيار • Back يرجع للمنظار"
                : (camOpen ? "صورة المنظار • " + fw + "×" + fh
                : "صورة المنظار • في انتظار الاتصال"));
    }

    private void refreshQualitySizes() {
        qualitySizes.clear();
        try {
            List<Size> raw = cam == null ? null : cam.getSupportedSizeList();
            if (raw == null) return;

            // De-duplicate width/height/type/fps combinations while preserving useful modes.
            Map<String, Size> unique = new LinkedHashMap<>();
            for (Size z : raw) {
                if (z == null || z.width <= 0 || z.height <= 0) continue;
                String key = z.width + "x" + z.height + ":" + z.type + ":" + z.fps;
                unique.put(key, z);
            }
            qualitySizes.addAll(unique.values());

            // Lowest -> highest. Prefer MJPEG when two modes have same pixel count.
            Collections.sort(qualitySizes, (a, b) -> {
                int pa = a.width * a.height;
                int pb = b.width * b.height;
                if (pa != pb) return Integer.compare(pa, pb);
                if (isMjpeg(a) != isMjpeg(b)) return isMjpeg(a) ? 1 : -1;
                return Integer.compare(a.fps, b.fps);
            });
        } catch (Throwable ignored) { }

        ui.post(() -> {
            if (qualityButton != null) {
                qualityButton.setText("الجودة (" + qualitySizes.size() + ")");
            }
        });
    }

    private int findQualityIndex(Size current) {
        if (current == null) return -1;
        for (int i = 0; i < qualitySizes.size(); i++) {
            Size z = qualitySizes.get(i);
            if (z.width == current.width && z.height == current.height
                    && z.type == current.type && z.fps == current.fps) return i;
        }
        // Fallback: match dimensions only.
        for (int i = 0; i < qualitySizes.size(); i++) {
            Size z = qualitySizes.get(i);
            if (z.width == current.width && z.height == current.height) return i;
        }
        return -1;
    }

    private void tryOneHigherQualityAutomatically() {
        if (!camOpen || qualitySizes.isEmpty()) return;
        Size current = null;
        try { current = cam.getPreviewSize(); } catch (Throwable ignored) { }
        int currentPixels = current == null ? 0 : current.width * current.height;

        // Prefer a clearly better MJPEG mode, but only move one step during auto-upgrade.
        Size candidate = null;
        for (Size z : qualitySizes) {
            int p = z.width * z.height;
            if (p > currentPixels && isMjpeg(z)) {
                candidate = z;
                break;
            }
        }
        if (candidate == null) {
            for (Size z : qualitySizes) {
                if (z.width * z.height > currentPixels) {
                    candidate = z;
                    break;
                }
            }
        }
        if (candidate != null) switchQuality(candidate, true);
        else showQualityModes();
    }

    private void cycleQuality() {
        if (!camOpen || cam == null) {
            updateTvStatus("الجودة: المنظار غير متصل");
            return;
        }
        refreshQualitySizes();
        if (qualitySizes.isEmpty()) {
            updateTvStatus("المنظار لم يعلن عن دقات إضافية");
            return;
        }

        Size current = null;
        try { current = cam.getPreviewSize(); } catch (Throwable ignored) { }
        int idx = findQualityIndex(current);
        if (idx < 0) idx = qualityIndex;
        int next = (idx + 1) % qualitySizes.size();
        switchQuality(qualitySizes.get(next), false);
    }

    private void switchQuality(Size target, boolean automatic) {
        if (target == null || cam == null || !camOpen) return;
        if (videoRecording || videoTransition || cam.isRecording()) {
            updateTvStatus("أوقف تسجيل الفيديو قبل تغيير الجودة");
            return;
        }

        Size before = null;
        try { before = cam.getPreviewSize(); } catch (Throwable ignored) { }
        final Size previous = before;
        final int generation = ++qualityGeneration;

        updateTvStatus("تجربة جودة " + target.width + "×" + target.height
                + (isMjpeg(target) ? " • MJPEG" : "")
                + " • " + target.fps + " FPS");

        try {
            cam.stopPreview();
            cam.setPreviewSize(target);
            synchronized (frameLock) {
                frame = null;
                work = null;
                seq = 0;
                served = 0;
                fw = target.width;
                fh = target.height;
            }
            lastFrameAt = 0;
            cam.startPreview();

            qualityIndex = findQualityIndex(target);
            if (qualityButton != null) {
                qualityButton.setText("الجودة " + target.width + "×" + target.height);
            }

            // If no frame arrives at the new size, return to the last working size.
            ui.postDelayed(() -> {
                if (generation != qualityGeneration || !camOpen) return;
                long age = lastFrameAt == 0 ? Long.MAX_VALUE
                        : SystemClock.uptimeMillis() - lastFrameAt;
                if (age > 1600 && previous != null) {
                    updateTvStatus("الدقة " + target.width + "×" + target.height
                            + " لم تعمل • رجوع " + previous.width + "×" + previous.height);
                    try {
                        cam.stopPreview();
                        cam.setPreviewSize(previous);
                        synchronized (frameLock) {
                            frame = null;
                            work = null;
                            seq = 0;
                            served = 0;
                            fw = previous.width;
                            fh = previous.height;
                        }
                        cam.startPreview();
                        lastGoodQuality = previous;
                        qualityIndex = findQualityIndex(previous);
                        if (qualityButton != null) {
                            qualityButton.setText("الجودة " + previous.width + "×" + previous.height);
                        }
                    } catch (Throwable t) {
                        updateTvStatus("فشل الرجوع للدقة السابقة • إعادة توصيل");
                    }
                } else {
                    lastGoodQuality = target;
                    updateTvStatus("الجودة تعمل ✓ • " + target.width + "×" + target.height);
                }
            }, automatic ? 2200 : 1800);
        } catch (Throwable t) {
            updateTvStatus("الدقة دي غير مستقرة • " + t.getMessage());
            if (previous != null) {
                try {
                    cam.setPreviewSize(previous);
                    fw = previous.width;
                    fh = previous.height;
                    cam.startPreview();
                } catch (Throwable ignored) { }
            }
        }
    }

    private void showQualityModes() {
        if (qualitySizes.isEmpty()) return;
        StringBuilder sb = new StringBuilder("الدقات: ");
        int shown = 0;
        for (Size z : qualitySizes) {
            if (shown++ >= 6) {
                sb.append("...");
                break;
            }
            if (shown > 1) sb.append(" | ");
            sb.append(z.width).append("×").append(z.height);
            if (isMjpeg(z)) sb.append(" M");
        }
        updateTvStatus(sb.toString());
    }

    private void rotatePreview() {
        previewRotation = (previewRotation + 90) % 360;
        tvPreview.setRotation(previewRotation);
        rotateButton.setText("تدوير " + previewRotation + "°");
    }

    private void reconnectScope() {
        updateTvStatus("إعادة تهيئة USB والكاميرا...");
        if (pendingSingleScopePress != null) {
            ui.removeCallbacks(pendingSingleScopePress);
            pendingSingleScopePress = null;
        }
        if (cam != null && (videoRecording || cam.isRecording())) {
            try { cam.stopRecording(); } catch (Throwable ignored) { }
        }
        videoRecording = false;
        videoTransition = false;
        stopRecordingTicker();
        camOpen = false;
        synchronized (frameLock) {
            frame = null;
            work = null;
            seq = 0;
            served = 0;
        }
        scopeButtonCount = 0;
        if (scopeButtonIndicator != null) scopeButtonIndicator.setText("زرار المنظار: 0");
        clearTvPreview();
        if (cam != null) {
            try { cam.release(); } catch (Throwable ignored) { }
            cam = null;
        }
        resized = false;
        bestSize = null;
        qualitySizes.clear();
        qualityIndex = -1;
        lastGoodQuality = null;
        qualityGeneration++;
        initCam();
        ui.postDelayed(this::openFirstUvc, 500);
    }

    // ================= Wi-Fi live view =================
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleWifiStream() {
        if (wifiServer != null && wifiServer.isRunning()) {
            stopWifiStream();
            toast("تم إيقاف عرض Wi-Fi");
            return;
        }

        WifiStreamServer server = new WifiStreamServer(WIFI_PORT);
        if (!server.startServer()) {
            toast("مقدرتش أشغل بث Wi-Fi على المنفذ " + WIFI_PORT);
            return;
        }
        wifiServer = server;
        wifiButton.setText("إيقاف Wi-Fi");

        String ip = localWifiIpv4();
        final String url = ip == null ? null : "http://" + ip + ":" + WIFI_PORT + "/";
        String message;
        if (url == null) {
            message = "البث شغال، لكن مفيش عنوان شبكة محلي واضح.\n"
                    + "اتأكد إن الجهازين على نفس شبكة Wi-Fi ثم اقفل البث وشغله تاني.";
        } else {
            message = "افتح الرابط ده من اللابتوب أو أي جهاز على نفس شبكة Wi-Fi:\n\n"
                    + url
                    + "\n\nالبث محلي داخل الشبكة، والصورة فقط هي اللي بتتعرض.";
        }

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Wi-Fi Live View")
                .setMessage(message)
                .setNegativeButton("إغلاق", null);
        if (url != null) {
            dialog.setPositiveButton("نسخ الرابط", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Medica Mall Scope Wi-Fi", url));
                toast("تم نسخ الرابط");
            });
        }
        dialog.show();
    }

    private void stopWifiStream() {
        WifiStreamServer s = wifiServer;
        wifiServer = null;
        if (s != null) s.stopServer();
        if (wifiButton != null) wifiButton.setText("Wi-Fi");
    }

    private String localWifiIpv4() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || !a.isSiteLocalAddress()) continue;
                    String host = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap")
                            || name.startsWith("wifi") || name.startsWith("eth")
                            || name.startsWith("rndis")) {
                        return host;
                    }
                    if (fallback == null) fallback = host;
                }
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    private synchronized byte[] latestWifiJpeg() {
        if (!camOpen) return null;

        long now = SystemClock.uptimeMillis();
        if (wifiJpeg != null && now - lastWifiEncode < 120) return wifiJpeg;

        byte[] copy;
        int w, h;
        synchronized (frameLock) {
            if (frame == null) return null;
            copy = new byte[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            w = fw;
            h = fh;
        }

        try {
            YuvImage yi = new YuvImage(copy, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64 * 1024, w * h / 6));
            if (!yi.compressToJpeg(new Rect(0, 0, w, h), 76, bos)) return null;
            wifiJpeg = bos.toByteArray();
            lastWifiEncode = now;
            return wifiJpeg;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private class WifiStreamServer extends Thread {
        private final int port;
        private volatile boolean running;
        private ServerSocket server;

        WifiStreamServer(int port) {
            super("MedicaMall-WiFi-Stream");
            this.port = port;
        }

        boolean startServer() {
            try {
                server = new ServerSocket(port);
                server.setReuseAddress(true);
                running = true;
                start();
                return true;
            } catch (Exception e) {
                running = false;
                try { if (server != null) server.close(); } catch (Exception ignored) { }
                return false;
            }
        }

        boolean isRunning() {
            return running;
        }

        void stopServer() {
            running = false;
            try { if (server != null) server.close(); } catch (Exception ignored) { }
            interrupt();
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Socket socket = server.accept();
                    socket.setTcpNoDelay(true);
                    new Thread(() -> handleClient(socket), "MedicaMall-WiFi-Client").start();
                } catch (Exception e) {
                    if (running) ui.post(() -> toast("اتوقف بث Wi-Fi"));
                    break;
                }
            }
            running = false;
            ui.post(() -> {
                if (wifiServer == this) {
                    wifiServer = null;
                    if (wifiButton != null) wifiButton.setText("Wi-Fi View");
                }
            });
        }

        private void handleClient(Socket socket) {
            try (Socket s = socket) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();

                String first = readRequestLine(in);
                String path = "/";
                if (first != null) {
                    String[] parts = first.split(" ");
                    if (parts.length >= 2) path = parts[1];
                }
                drainHeaders(in);

                if (path.startsWith("/stream.mjpg")) {
                    streamMjpeg(out);
                } else if (path.startsWith("/snapshot.jpg")) {
                    serveSnapshot(out);
                } else {
                    serveViewer(out);
                }
            } catch (Throwable ignored) { }
        }

        private String readRequestLine(InputStream in) throws Exception {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int prev = -1;
            for (int i = 0; i < 2048; i++) {
                int c = in.read();
                if (c < 0) break;
                if (prev == '\r' && c == '\n') break;
                if (c != '\r') b.write(c);
                prev = c;
            }
            return b.toString("US-ASCII");
        }

        private void drainHeaders(InputStream in) throws Exception {
            int state = 0;
            for (int i = 0; i < 8192; i++) {
                int c = in.read();
                if (c < 0) return;
                if (state == 0 && c == '\r') state = 1;
                else if (state == 1 && c == '\n') state = 2;
                else if (state == 2 && c == '\r') state = 3;
                else if (state == 3 && c == '\n') return;
                else state = 0;
            }
        }

        private void serveViewer(OutputStream out) throws Exception {
            String html = "<!doctype html><html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
                    + "<title>Medica Mall Scope</title>"
                    + "<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}"
                    + "img{width:100%;height:100%;object-fit:contain;display:block}"
                    + ".tag{position:fixed;top:10px;left:10px;background:#116eb5;color:#fff;"
                    + "font:14px sans-serif;padding:7px 10px;border-radius:8px;opacity:.9}</style></head>"
                    + "<body><img src='/stream.mjpg'><div class='tag'>Medica Mall Scope · Live</div></body></html>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            writeText(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: "
                    + body.length + "\r\n\r\n");
            out.write(body);
            out.flush();
        }

        private void serveSnapshot(OutputStream out) throws Exception {
            byte[] jpg = latestWifiJpeg();
            if (jpg == null) {
                writeText(out, "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\nConnection: close\r\n\r\nWaiting for camera");
                out.flush();
                return;
            }
            writeText(out, "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: no-store\r\n"
                    + "Connection: close\r\nContent-Length: " + jpg.length + "\r\n\r\n");
            out.write(jpg);
            out.flush();
        }

        private void streamMjpeg(OutputStream out) throws Exception {
            writeText(out, "HTTP/1.1 200 OK\r\nConnection: close\r\nCache-Control: no-store, no-cache\r\n"
                    + "Pragma: no-cache\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n");
            while (running) {
                byte[] jpg = latestWifiJpeg();
                if (jpg == null) {
                    SystemClock.sleep(150);
                    continue;
                }
                writeText(out, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + jpg.length + "\r\n\r\n");
                out.write(jpg);
                writeText(out, "\r\n");
                out.flush();
                SystemClock.sleep(120);
            }
        }

        private void writeText(OutputStream out, String text) throws Exception {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        }
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
        if (showWebScreen) {
            toggleScreenMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                if (tvControls != null) {
                    tvControls.setVisibility(tvControls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    if (tvControls.getVisibility() == View.VISIBLE && retryButton != null) retryButton.requestFocus();
                }
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                toggleScreenMode();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updates.onResume(this);
    }

    @Override
    protected void onDestroy() {
        stopWifiStream();
        camOpen = false;
        if (pendingSingleScopePress != null) ui.removeCallbacks(pendingSingleScopePress);
        stopRecordingTicker();
        if (cam != null && (videoRecording || cam.isRecording())) {
            try { cam.stopRecording(); } catch (Throwable ignored) { }
        }
        previewExec.shutdownNow();
        clearTvPreview();
        if (cam != null) {
            try { cam.release(); } catch (Throwable ignored) { }
            cam = null;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
