/* Medica Mall Scope – Windows bridge.
   The Windows app reads the USB scope natively (DirectShow) and serves frames at /uvc/frame.jpg;
   this makes it look like a normal camera to the web app and turns the scope's button into photo / record. */
(function () {
  var W = window.chrome && window.chrome.webview;
  if (!W) return;
  var FAKE = 'besdata-usb-native', LABEL = 'BESDATA Endoscope (USB)', SCOPE_RE = /besdata|endoscope/i;
  var md = navigator.mediaDevices;
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  var S = { connected: false, w: 1280, h: 720 };
  try { var x = new XMLHttpRequest(); x.open('GET', '/native/state', false); x.send(); if (x.status === 200) S = JSON.parse(x.responseText); } catch (e) {}
  window.__besState = function (s) { S = s || S; };
  var connected = function () { return !!S.connected; };

  /* ---------------- camera ---------------- */
  var active = null;
  if (md && md.getUserMedia) {
    var _gum = md.getUserMedia.bind(md);
    var _enum = md.enumerateDevices.bind(md);

    var wantsNative = function (c) {
      if (!c || !c.video || !connected()) return false;
      var v = c.video;
      if (v === true || !v.deviceId) return true;
      var id = v.deviceId;
      var s = typeof id === 'string' ? id : (id.exact || id.ideal);
      if (Array.isArray(s)) return s.indexOf(FAKE) >= 0;
      return s === FAKE;
    };

    var nativeStream = async function () {
      if (active) active.stop();
      var c = document.createElement('canvas');
      c.width = S.w || 1280; c.height = S.h || 720;
      var ctx = c.getContext('2d');
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, c.width, c.height);
      var stream = c.captureStream(30);
      var t = stream.getVideoTracks()[0];
      var st = { running: true, ended: false };
      var gs = t.getSettings ? t.getSettings.bind(t) : function () { return {}; };
      try { Object.defineProperty(t, 'label', { get: function () { return LABEL; } }); } catch (e) {}
      try { Object.defineProperty(t, 'readyState', { get: function () { return st.ended ? 'ended' : 'live'; } }); } catch (e) {}
      t.getSettings = function () { return Object.assign({}, gs(), { deviceId: FAKE, width: c.width, height: c.height, frameRate: 30 }); };
      t.getCapabilities = function () { return {}; };
      var _stop = t.stop.bind(t);
      t.stop = function () { st.running = false; st.ended = true; _stop(); if (active && active.track === t) active = null; };

      var first = null, gotFirst = new Promise(function (r) { first = r; });
      (async function loop() {
        while (st.running) {
          try {
            var r = await fetch('/uvc/frame.jpg?t=' + Date.now(), { cache: 'no-store' });
            if (r.status === 200) {
              var bmp = await createImageBitmap(await r.blob());
              if (c.width !== bmp.width || c.height !== bmp.height) { c.width = bmp.width; c.height = bmp.height; }
              ctx.drawImage(bmp, 0, 0);
              if (bmp.close) bmp.close();
              first();
            } else { await sleep(80); }
          } catch (e) { await sleep(200); }
        }
      })();
      active = {
        track: t,
        stop: function () { st.running = false; },
        end: function () {
          if (st.ended) return;
          st.running = false; st.ended = true;
          try { t.dispatchEvent(new Event('ended')); } catch (e) {}
        }
      };
      await Promise.race([gotFirst, sleep(2500)]);
      return stream;
    };

    md.getUserMedia = function (c) { return wantsNative(c) ? nativeStream() : _gum(c); };
    md.enumerateDevices = async function () {
      var l = [];
      try { l = Array.from(await _enum()); } catch (e) {}
      if (connected()) {
        // the app holds the scope itself – hide Windows' own entry for it and offer the native one first
        l = l.filter(function (d) { return !(d.kind === 'videoinput' && SCOPE_RE.test(d.label || '')); });
        l = [{ deviceId: FAKE, groupId: 'besdata', kind: 'videoinput', label: LABEL, toJSON: function () { return this; } }].concat(l);
      }
      return l;
    };
  }

  /* scope's own button: 1 press = photo, 2 presses = record */
  var btnT = null;
  var clickId = function (id) { var b = document.getElementById(id); if (b && !b.disabled) b.click(); };
  var onButton = function () {
    if (btnT) { clearTimeout(btnT); btnT = null; clickId('btnRec'); return; }
    btnT = setTimeout(function () { btnT = null; clickId('btnSnap'); }, 450);
  };

  /* new exam-screen version downloaded in the background */
  var showUpdateBar = function () {
    if (document.getElementById('mmUpd') || !document.body) return;
    var d = document.createElement('div');
    d.id = 'mmUpd';
    d.style.cssText = 'position:fixed;left:50%;transform:translateX(-50%);top:12px;z-index:99999;max-width:560px;width:calc(100% - 24px);background:#0a3f7d;color:#fff;border-radius:14px;padding:10px 12px;display:flex;align-items:center;gap:10px;font:14px/1.5 system-ui,"Segoe UI",sans-serif;box-shadow:0 8px 24px rgba(10,63,125,.35);direction:rtl';
    d.innerHTML = '<div style="flex:1"><b>✨ فيه تحديث لشاشة الكشف</b><br><span style="color:#cfe6fb;font-size:12.5px">المرضى والصور مش هتتأثر</span></div>'
      + '<button id="mmUpdGo" style="background:#ffc233;color:#0a3f7d;border:0;border-radius:999px;padding:8px 14px;font-weight:800;cursor:pointer">تحديث دلوقتي</button>'
      + '<button id="mmUpdX" style="background:transparent;border:0;color:#cfe6fb;font-size:20px;cursor:pointer">×</button>';
    document.body.appendChild(d);
    document.getElementById('mmUpdGo').onclick = function () { W.postMessage('reloadWeb'); };
    document.getElementById('mmUpdX').onclick = function () { d.remove(); };
  };

  /* events from the Windows app */
  window.__besNative = function (evt) {
    if (evt === 'button') { onButton(); return; }
    if (evt === 'webupdate') { showUpdateBar(); return; }
    if ((evt === 'detached' || evt === 'closed') && active) active.end();
    try { md && md.dispatchEvent(new Event('devicechange')); } catch (e) {}
  };
})();
