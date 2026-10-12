package com.medicamall.scope;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Scope Box: turns any HDMI screen + Android TV box into a dedicated scope display.
 *  - auto-start after boot (BootReceiver)
 *  - optional "box mode": the app registers as the box's home screen (ScopeHome alias)
 *  - always full screen
 *  - hidden technician menu (long-press «خروج», PIN protected) to get back to Android
 */
final class BoxMode {
    private BoxMode() { }

    static final String PREFS = "scope_box";
    static final String KEY_AUTOSTART = "autostart";
    static final String KEY_PIN = "tech_pin";
    static final String DEFAULT_PIN = "2580";
    static final String HOME_ALIAS = "com.medicamall.scope.ScopeHome";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean autoStart(Context c) {
        return prefs(c).getBoolean(KEY_AUTOSTART, true);
    }

    static void setAutoStart(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_AUTOSTART, on).apply();
    }

    static boolean homeMode(Context c) {
        int s = c.getPackageManager().getComponentEnabledSetting(new ComponentName(c, HOME_ALIAS));
        return s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static void setHomeMode(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(
                new ComponentName(c, HOME_ALIAS),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                   : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /** Full screen with no status / navigation bar; bars come back briefly on a swipe only. */
    @SuppressWarnings("deprecation")
    static void immersive(Activity a) {
        Window w = a.getWindow();
        if (w == null) return;
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------- technician menu ----------------

    static void askPin(Activity a, Runnable onExitApp) {
        EditText pin = new EditText(a);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("الكود");
        new AlertDialog.Builder(a)
                .setTitle("إعدادات الفني")
                .setView(pin)
                .setPositiveButton("دخول", (d, w) -> {
                    String want = prefs(a).getString(KEY_PIN, DEFAULT_PIN);
                    if (want.equals(pin.getText().toString().trim())) techMenu(a, onExitApp);
                    else Toast.makeText(a, "الكود غلط", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
        pin.requestFocus();
    }

    private static void techMenu(Activity a, Runnable onExitApp) {
        boolean auto = autoStart(a);
        boolean home = homeMode(a);
        String[] items = {
                (auto ? "✅ " : "⬜ ") + "يفتح لوحده بعد تشغيل الجهاز",
                (home ? "✅ " : "⬜ ") + "وضع الجهاز المخصص (شاشة رئيسية)",
                "اختيار الشاشة الرئيسية للجهاز",
                "إعدادات أندرويد",
                "تغيير كود الفني",
                "خروج من البرنامج",
        };
        new AlertDialog.Builder(a)
                .setTitle("إعدادات الفني • Scope Box")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            setAutoStart(a, !auto);
                            toast(a, !auto ? "هيفتح لوحده بعد التشغيل" : "اتلغى الفتح التلقائي");
                            techMenu(a, onExitApp);
                            break;
                        case 1:
                            setHomeMode(a, !home);
                            if (!home) {
                                toast(a, "اضغط HOME واختار «Medica Mall Scope Box» ثم «دايماً»");
                                openHomeChooser(a);
                            } else {
                                toast(a, "اتلغى وضع الجهاز المخصص • اضغط HOME واختار الواجهة الأصلية");
                                openHomeChooser(a);
                            }
                            break;
                        case 2:
                            openHomeChooser(a);
                            break;
                        case 3:
                            safeStart(a, new Intent(Settings.ACTION_SETTINGS));
                            break;
                        case 4:
                            changePin(a);
                            break;
                        default:
                            if (homeMode(a)) {
                                toast(a, "وضع الجهاز المخصص شغال • البرنامج هيرجع لوحده. اقفل الوضع الأول");
                            } else if (onExitApp != null) {
                                onExitApp.run();
                            }
                    }
                })
                .setNegativeButton("رجوع", null)
                .show();
    }

    private static void changePin(Activity a) {
        EditText pin = new EditText(a);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setHint("كود جديد (4 أرقام أو أكتر)");
        new AlertDialog.Builder(a)
                .setTitle("تغيير كود الفني")
                .setView(pin)
                .setPositiveButton("حفظ", (d, w) -> {
                    String p = pin.getText().toString().trim();
                    if (p.length() < 4) { toast(a, "الكود لازم 4 أرقام على الأقل"); return; }
                    prefs(a).edit().putString(KEY_PIN, p).apply();
                    toast(a, "اتحفظ الكود الجديد");
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static void openHomeChooser(Activity a) {
        if (Build.VERSION.SDK_INT >= 21 && safeStart(a, new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        safeStart(a, Intent.createChooser(home, "الشاشة الرئيسية"));
    }

    private static boolean safeStart(Activity a, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toast(Context c, String m) {
        Toast.makeText(c, m, Toast.LENGTH_LONG).show();
    }
}
