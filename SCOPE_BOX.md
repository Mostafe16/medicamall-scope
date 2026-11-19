# Medica Mall Scope Box

Isolated branch `test/scope-box` (forked from `test/tv-box-stable-v3` @ a8663ae).
Goal: plug an Android TV box (X96 Mini) into any HDMI screen + the scope on USB → it becomes a scope display.

- App id `com.medicamall.scope.box`, label "Medica Mall Scope Box" — installs next to production and TV test.
- Build: `.github/workflows/scope-box.yml` → pre-release `scope-box` → `MedicaMall-Scope-Box.apk`.
- Auto-start after boot (BootReceiver, on by default).
- Dedicated mode ON by default: `ScopeHome` alias registers the app as the home screen. First launch opens the home chooser once per app start until Medica Mall is the default (pick «Medica Mall Scope Box» → «دايماً»). Technician menu can switch it off.
- Always full screen (immersive), screen kept on.
- Technician menu: press «خروج» (normal press in dedicated mode; long-press also works) → PIN (default 2580): auto-start, dedicated mode, home chooser, Android settings, change PIN, exit.
- Scope unplug/replug reconnects automatically (existing onAttach/onDetach).

Not merged anywhere. main / tv-box-stable-v3 untouched.
