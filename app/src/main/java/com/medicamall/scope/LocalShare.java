package com.medicamall.scope;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Car screen edition: head units usually have no WhatsApp / print service, so "share" shows a QR code instead.
 * The doctor (or patient) scans it with a phone on the same Wi-Fi (or the phone's hotspot) and downloads the
 * photos / videos / PDF straight from the screen. A small HTTP server runs only while the QR dialog is open.
 */
class LocalShare {

    private static ServerSocket server;
    private static volatile List<File> files = new ArrayList<>();
    private static volatile List<String> mimes = new ArrayList<>();
    private static volatile String token = "";
    private static volatile String title = "";

    static void show(Activity a, List<File> f, List<String> m, String t, Runnable fallback) {
        String ip = wifiIp();
        if (ip == null) {
            android.widget.Toast.makeText(a, "وصّل الشاشة بالواي فاي (أو بهوت سبوت الموبايل) عشان المشاركة بالـ QR", android.widget.Toast.LENGTH_LONG).show();
            fallback.run();
            return;
        }
        int port = start(f, m, t);
        if (port <= 0) { fallback.run(); return; }
        String url = "http://" + ip + ":" + port + "/" + token + "/";

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        ImageView qr = new ImageView(a);
        Bitmap bmp = qr(url, 420);
        if (bmp != null) qr.setImageBitmap(bmp);
        int side = (int) (220 * a.getResources().getDisplayMetrics().density);
        box.addView(qr, new LinearLayout.LayoutParams(side, side));

        TextView tv = new TextView(a);
        tv.setTextSize(16);
        tv.setTextColor(Color.parseColor("#10243a"));
        tv.setPadding(pad, 0, pad, 0);
        tv.setText(f.size() + " ملف جاهز للتحميل\n\n"
                + "1) صوّر الكود بكاميرا الموبايل\n"
                + "2) الموبايل لازم يكون على نفس الواي فاي\n"
                + "3) اضغط «تحميل» على كل ملف\n\n" + url);
        tv.setTextIsSelectable(true);
        box.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("مشاركة بالموبايل (QR)")
                .setView(box)
                .setPositiveButton("تم", null)
                .setNeutralButton("مشاركة عادية", (x, w) -> fallback.run())
                .create();
        d.setOnDismissListener(x -> stop());
        d.show();
    }

    // ---------------- server ----------------
    private static synchronized int start(List<File> f, List<String> m, String t) {
        stop();
        files = new ArrayList<>(f);
        mimes = new ArrayList<>(m);
        title = t == null ? "" : t;
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        token = sb.toString();
        for (int port = 8765; port < 8785; port++) {
            try {
                server = new ServerSocket(port);
                final ServerSocket ss = server;
                Thread th = new Thread(() -> loop(ss), "LocalShare");
                th.setDaemon(true);
                th.start();
                return port;
            } catch (Exception ignored) { }
        }
        return -1;
    }

    static synchronized void stop() {
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
            server = null;
        }
    }

    private static void loop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                Thread th = new Thread(() -> handle(s), "LocalShare-conn");
                th.setDaemon(true);
                th.start();
            } catch (Exception e) {
                if (ss.isClosed()) return;
            }
        }
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String req = in.readLine();
            if (req == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }
            String[] p = req.split(" ");
            String path = p.length > 1 ? p[1] : "/";
            OutputStream out = sock.getOutputStream();
            String base = "/" + token + "/";
            if (!path.startsWith(base)) { send(out, 404, "text/plain; charset=utf-8", "انتهت المشاركة – اعمل مشاركة جديدة من الشاشة".getBytes(StandardCharsets.UTF_8), null); return; }
            String rest = path.substring(base.length());
            if (rest.isEmpty()) { send(out, 200, "text/html; charset=utf-8", index().getBytes(StandardCharsets.UTF_8), null); return; }
            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);
            int i;
            try { i = Integer.parseInt(rest); } catch (Exception e) { i = -1; }
            List<File> fs = files;
            if (i < 0 || i >= fs.size() || !fs.get(i).exists()) { send(out, 404, "text/plain", new byte[0], null); return; }
            File f = fs.get(i);
            String mime = mimes.get(i);
            if (TextUtils.isEmpty(mime)) mime = "application/octet-stream";
            Map<String, String> hd = new HashMap<>();
            String enc = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
            hd.put("Content-Disposition", (path.contains("?view") ? "inline" : "attachment") + "; filename*=UTF-8''" + enc);
            hd.put("Content-Length", String.valueOf(f.length()));
            head(out, 200, mime, hd);
            try (InputStream fin = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = fin.read(buf)) > 0) out.write(buf, 0, r);
            }
            out.flush();
        } catch (Exception ignored) { }
    }

    private static String index() {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html dir=rtl lang=ar><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
         .append("<title>ملفات الكشف</title><style>body{font-family:system-ui,Tahoma,sans-serif;margin:0;background:#eef4fb;color:#10243a}")
         .append("h1{margin:0;padding:14px 16px;background:#0d6fcc;color:#fff;font-size:18px}.f{display:flex;align-items:center;gap:12px;background:#fff;margin:10px;padding:10px;border-radius:12px}")
         .append(".f img{width:84px;height:63px;object-fit:cover;border-radius:8px;background:#000}.f .i{width:84px;height:63px;border-radius:8px;background:#0a3f7d;color:#fff;display:flex;align-items:center;justify-content:center;font-size:26px}")
         .append(".f div{flex:1;word-break:break-all;font-size:14px}a.b{background:#0d6fcc;color:#fff;text-decoration:none;padding:10px 16px;border-radius:999px;font-weight:700}</style></head><body><h1>")
         .append(esc(TextUtils.isEmpty(title) ? "ملفات الكشف" : title)).append("</h1>");
        List<File> fs = files;
        for (int i = 0; i < fs.size(); i++) {
            String m = mimes.get(i) == null ? "" : mimes.get(i);
            b.append("<div class=f>");
            if (m.startsWith("image/")) b.append("<img loading=lazy src='").append(i).append("?view'>");
            else b.append("<span class=i>").append(m.startsWith("video/") ? "🎥" : "📄").append("</span>");
            b.append("<div>").append(esc(fs.get(i).getName())).append("<br><small>")
             .append(String.format(java.util.Locale.US, "%.1f MB", fs.get(i).length() / 1048576.0)).append("</small></div>")
             .append("<a class=b href='").append(i).append("'>تحميل</a></div>");
        }
        b.append("</body></html>");
        return b.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static void head(OutputStream out, int code, String type, Map<String, String> extra) throws Exception {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " Not Found").append("\r\n")
                .append("Content-Type: ").append(type).append("\r\nConnection: close\r\nCache-Control: no-store\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body, Map<String, String> extra) throws Exception {
        Map<String, String> hd = extra == null ? new HashMap<>() : extra;
        hd.put("Content-Length", String.valueOf(body.length));
        head(out, code, type, hd);
        out.write(body);
        out.flush();
    }

    // ---------------- helpers ----------------
    /** Site-local IPv4 of Wi-Fi / hotspot interface (wlan*, ap*, swlan*), else any site-local address. */
    static String wifiIp() {
        String any = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (!(ad instanceof Inet4Address) || !ad.isSiteLocalAddress()) continue;
                    String n = ni.getName() == null ? "" : ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return ad.getHostAddress();
                    if (any == null) any = ad.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return any;
    }

    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> h = new HashMap<>();
            h.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, h);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }
}
