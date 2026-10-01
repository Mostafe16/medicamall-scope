/* Medica Mall Scope – Mac bridge (test build).
   macOS gives the scope to the page as a normal camera, so this only adds:
   diagnostics to the app log, the scope-button mode bar (📷 / 🎥) and the update bar. */
(function () {
  if (window.__mmMacShim) return;
  window.__mmMacShim = 1;
  var H = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.mm;
  var post = function (m) { try { H && H.postMessage(String(m)); } catch (e) {} };
  var log = function (m) { post('log:' + m); };
  window.addEventListener('error', function (e) { log('js error: ' + (e && e.message)); });
  window.addEventListener('unhandledrejection', function (e) { log('js promise: ' + (e && e.reason && (e.reason.message || e.reason))); });

  var hasApp = function () { return !!document.getElementById('btnSnap'); };

  /* camera diagnostics */
  var md = navigator.mediaDevices;
  if (md && md.getUserMedia) {
    var gum = md.getUserMedia.bind(md);
    md.getUserMedia = function (c) {
      return gum(c).then(function (s) {
        var t = s.getVideoTracks()[0], st = t && t.getSettings ? t.getSettings() : {};
        log('camera ok: ' + (t ? t.label : '-') + ' ' + (st.width || '?') + 'x' + (st.height || '?'));
        return s;
      }, function (e) { log('camera error: ' + (e && e.name) + ' ' + (e && e.message)); throw e; });
    };
  }

  /* on-screen confirmation */
  var badgeT = null;
  var badge = function (txt, bg) {
    var d = document.getElementById('mmBtnBadge');
    if (!d) {
      d = document.createElement('div'); d.id = 'mmBtnBadge';
      d.style.cssText = 'position:fixed;top:14px;left:50%;transform:translateX(-50%);z-index:99999;color:#fff;border-radius:999px;padding:8px 18px;font:700 15px/1.4 -apple-system,system-ui,sans-serif;box-shadow:0 6px 18px rgba(0,0,0,.25);pointer-events:none;direction:rtl';
      (document.body || document.documentElement).appendChild(d);
    }
    d.textContent = txt; d.style.background = bg || '#0a3f7d'; d.style.display = 'block';
    clearTimeout(badgeT); badgeT = setTimeout(function () { d.style.display = 'none'; }, 1800);
  };

  /* scope button: same mode bar as the Windows app */
  var clickId = function (id) { var b = document.getElementById(id); if (b && !b.disabled) { b.click(); return true; } log(id + ' not clickable'); return false; };
  var recBtnText = function () { var b = document.getElementById('btnRec'); return b ? b.textContent.trim() : '-'; };
  var MODE_KEY = 'mm_btn_mode';
  var mode = 'photo';
  try { mode = localStorage.getItem(MODE_KEY) === 'video' ? 'video' : 'photo'; } catch (e) {}
  var paintMode = function () {
    var bar = document.getElementById('mmBtnMode');
    if (!bar) return;
    bar.querySelectorAll('button').forEach(function (b) {
      var on = b.getAttribute('data-m') === mode;
      b.style.background = on ? (mode === 'video' ? '#c62828' : '#0d6fcc') : '#fff';
      b.style.color = on ? '#fff' : '#0a3f7d';
    });
  };
  var setMode = function (m) {
    mode = m === 'video' ? 'video' : 'photo';
    try { localStorage.setItem(MODE_KEY, mode); } catch (e) {}
    paintMode(); log('button mode -> ' + mode);
  };
  var addModeBar = function () {
    if (!hasApp() || document.getElementById('mmBtnMode') || !document.body) return;
    var bar = document.createElement('div'); bar.id = 'mmBtnMode';
    bar.style.cssText = 'position:fixed;left:14px;bottom:14px;z-index:9998;display:flex;align-items:center;gap:6px;background:#fff;border:1px solid #cfe0f2;border-radius:999px;padding:5px 6px 5px 12px;box-shadow:0 6px 18px rgba(10,63,125,.18);font:700 13px/1.3 -apple-system,system-ui,sans-serif;color:#0a3f7d;direction:rtl';
    bar.innerHTML = '<span>زر المنظار:</span>'
      + '<button type="button" data-m="photo" style="border:1px solid #cfe0f2;border-radius:999px;padding:6px 12px;font:inherit;cursor:pointer">📷 صورة</button>'
      + '<button type="button" data-m="video" style="border:1px solid #cfe0f2;border-radius:999px;padding:6px 12px;font:inherit;cursor:pointer">🎥 فيديو</button>';
    bar.querySelectorAll('button').forEach(function (b) { b.onclick = function () { setMode(b.getAttribute('data-m')); }; });
    document.body.appendChild(bar); paintMode();
  };
  var onButton = function () {
    if (mode === 'video') {
      var wasRec = /إيقاف/.test(recBtnText());
      log('button -> ' + (wasRec ? 'stop' : 'start') + ' video');
      badge(wasRec ? '⏹ إيقاف الفيديو' : '⏺ بدأ تسجيل الفيديو', '#c62828');
      clickId('btnRec');
      setTimeout(function () { log('video button now: ' + recBtnText()); }, 900);
      return;
    }
    log('button -> photo');
    badge('📷 صورة');
    clickId('btnSnap');
  };

  var showUpdateBar = function () {
    if (document.getElementById('mmUpd') || !document.body) return;
    var d = document.createElement('div'); d.id = 'mmUpd';
    d.style.cssText = 'position:fixed;left:50%;transform:translateX(-50%);top:12px;z-index:99999;max-width:560px;width:calc(100% - 24px);background:#0a3f7d;color:#fff;border-radius:14px;padding:10px 12px;display:flex;align-items:center;gap:10px;font:14px/1.5 -apple-system,system-ui,sans-serif;box-shadow:0 8px 24px rgba(10,63,125,.35);direction:rtl';
    d.innerHTML = '<div style="flex:1"><b>✨ فيه تحديث لشاشة الكشف</b><br><span style="color:#cfe6fb;font-size:12.5px">المرضى والصور مش هتتأثر</span></div>'
      + '<button id="mmUpdGo" style="background:#ffc233;color:#0a3f7d;border:0;border-radius:999px;padding:8px 14px;font-weight:800;cursor:pointer">تحديث دلوقتي</button>'
      + '<button id="mmUpdX" style="background:transparent;border:0;color:#cfe6fb;font-size:20px;cursor:pointer">×</button>';
    document.body.appendChild(d);
    document.getElementById('mmUpdGo').onclick = function () { post('reloadWeb'); };
    document.getElementById('mmUpdX').onclick = function () { d.remove(); };
  };

  /* events from the Mac app (forwarded into the exam-screen iframe when the live site is open) */
  window.__macNative = function (evt) {
    if (!hasApp()) {
      var fr = document.querySelectorAll('iframe');
      for (var i = 0; i < fr.length; i++) { try { fr[i].contentWindow.__macNative && fr[i].contentWindow.__macNative(evt); } catch (e) {} }
      return;
    }
    if (evt === 'button') onButton();
    else if (evt === 'webupdate') showUpdateBar();
    else if (evt.indexOf('toast:') === 0) badge(evt.slice(6));
  };

  var ready = function () {
    if (!hasApp()) return;
    addModeBar();
    log('exam screen ready; secure=' + window.isSecureContext + ' camera-api=' + !!(md && md.getUserMedia)
      + ' recorder=' + (window.MediaRecorder ? ['video/webm;codecs=vp9', 'video/webm', 'video/mp4'].filter(function (t) { return MediaRecorder.isTypeSupported(t); }).join('|') || 'no-formats' : 'none'));
    setTimeout(function () {
      if (md && md.enumerateDevices) md.enumerateDevices().then(function (l) {
        log('page cameras: ' + l.filter(function (d) { return d.kind === 'videoinput'; }).map(function (d) { return d.label || '(no label)'; }).join(' | '));
      });
    }, 4000);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, { once: true }); else ready();
})();
