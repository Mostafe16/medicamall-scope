/* Medica Mall Scope – Android bridge.
   Makes the USB scope (read natively via UVC) look like a normal camera to the web app,
   and routes downloads / share / report printing / external links to Android. */
(function () {
  var N = window.BesNative;
  if (!N) return;
  var FAKE = 'besdata-usb-native', LABEL = 'BESDATA Endoscope (USB)';
  var md = navigator.mediaDevices;
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  var connected = function () { try { return N.isConnected(); } catch (e) { return false; } };

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
      var sz = String(N.frameSize() || '1280x720').split('x').map(Number);
      var c = document.createElement('canvas');
      c.width = sz[0] || 1280; c.height = sz[1] || 720;
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
            } else { await sleep(60); }
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
      await Promise.race([gotFirst, sleep(2000)]);
      return stream;
    };

    md.getUserMedia = function (c) { return wantsNative(c) ? nativeStream() : _gum(c); };
    md.enumerateDevices = async function () {
      var l = [];
      try { l = await _enum(); } catch (e) {}
      if (connected()) {
        l = [{ deviceId: FAKE, groupId: 'besdata', kind: 'videoinput', label: LABEL, toJSON: function () { return this; } }].concat(Array.from(l));
      }
      return l;
    };
  }

  /* scope's own button: 1 press = photo, 2 presses = record */
  var btnT = null;
  var clickId = function (id) { var b = document.getElementById(id); if (b && !b.disabled) b.click(); };
  var onButton = function () {
    if (btnT) { clearTimeout(btnT); btnT = null; clickId('btnRec'); return; }
    btnT = setTimeout(function () { btnT = null; clickId('btnSnap'); }, 400);
  };

  /* new exam-screen version downloaded in the background */
  var showUpdateBar = function () {
    if (document.getElementById('mmUpd')) return;
    var d = document.createElement('div');
    d.id = 'mmUpd';
    d.style.cssText = 'position:fixed;left:10px;right:10px;top:10px;z-index:99999;background:#0a3f7d;color:#fff;border-radius:14px;padding:10px 12px;display:flex;align-items:center;gap:10px;font:14px/1.5 system-ui,sans-serif;box-shadow:0 8px 24px rgba(10,63,125,.35);direction:rtl';
    d.innerHTML = '<div style="flex:1"><b>✨ فيه تحديث لشاشة الكشف</b><br><span style="color:#cfe6fb;font-size:12.5px">المرضى والصور مش هتتأثر</span></div>'
      + '<button id="mmUpdGo" style="background:#ffc233;color:#0a3f7d;border:0;border-radius:999px;padding:8px 14px;font-weight:800">تحديث دلوقتي</button>'
      + '<button id="mmUpdX" style="background:transparent;border:0;color:#cfe6fb;font-size:20px">×</button>';
    document.body.appendChild(d);
    document.getElementById('mmUpdGo').onclick = function () { N.reloadWeb(); };
    document.getElementById('mmUpdX').onclick = function () { d.remove(); };
  };

  /* events from Android */
  window.__besNative = function (evt) {
    if (evt === 'button') { onButton(); return; }
    if (evt === 'webupdate') { showUpdateBar(); return; }
    if ((evt === 'detached' || evt === 'closed') && active) active.end();
    if (evt === 'open') N.toast('المنظار اتوصل ✔');
    try { md && md.dispatchEvent(new Event('devicechange')); } catch (e) {}
  };


  /* ---------------- Android TV / D-pad adaptation ---------------- */
  var installTvMode = function () {
    if (!document.body || document.documentElement.getAttribute('data-mm-tv') === '1') return;
    document.documentElement.setAttribute('data-mm-tv', '1');

    var css = document.createElement('style');
    css.id = 'mmTvCss';
    css.textContent =
      ':focus{outline:4px solid #ffc233!important;outline-offset:3px!important;box-shadow:0 0 0 3px rgba(17,110,181,.35)!important}' +
      'button,a[href],select,input,textarea,[role="button"],[onclick]{scroll-margin:110px}' +
      '.mm-tv-stepper{display:inline-flex;align-items:center;gap:8px;margin:7px 8px;vertical-align:middle;direction:ltr}' +
      '.mm-tv-step-btn{width:54px!important;height:50px!important;min-width:54px!important;min-height:50px!important;padding:0!important;border:0!important;border-radius:12px!important;background:#116eb5!important;color:#fff!important;font-size:28px!important;font-weight:900!important;line-height:1!important}' +
      '.mm-tv-step-val{min-width:76px;padding:10px 12px;border:2px solid #d9e5ef;border-radius:10px;background:#fff;color:#16324a;text-align:center;font:700 17px/1.2 system-ui,sans-serif}' +
      'input[type="range"]{min-width:180px}' +
      '@media (min-width:900px){button,select,input[type="button"],input[type="submit"]{min-height:46px}}';
    document.head.appendChild(css);

    var fire = function (el, type) {
      try { el.dispatchEvent(new Event(type, { bubbles:true })); } catch (e) {}
    };

    var updateStepper = function (input) {
      var id = input.getAttribute('data-mm-tv-step-id');
      if (!id) return;
      var out = document.querySelector('[data-mm-tv-step-val="' + id + '"]');
      if (out) out.textContent = input.value;
    };

    var stepValue = function (input, dir) {
      try {
        if (dir > 0 && input.stepUp) input.stepUp();
        else if (dir < 0 && input.stepDown) input.stepDown();
        else throw new Error('no step api');
      } catch (e) {
        var cur = parseFloat(input.value || '0');
        var step = parseFloat(input.step || '');
        if (!isFinite(step) || step === 0) step = input.type === 'range' ? 1 : 1;
        var next = cur + (dir * step);
        var min = parseFloat(input.min || ''), max = parseFloat(input.max || '');
        if (isFinite(min)) next = Math.max(min, next);
        if (isFinite(max)) next = Math.min(max, next);
        input.value = String(next);
      }
      fire(input, 'input');
      fire(input, 'change');
      updateStepper(input);
    };

    var addStepper = function (input) {
      if (!input || input.disabled || input.readOnly || input.getAttribute('data-mm-tv-step-id')) return;
      if (input.type !== 'range' && input.type !== 'number') return;
      var id = 'mmstep' + Math.random().toString(36).slice(2, 9);
      input.setAttribute('data-mm-tv-step-id', id);

      var wrap = document.createElement('span');
      wrap.className = 'mm-tv-stepper';
      wrap.setAttribute('data-mm-tv-stepper', id);

      var minus = document.createElement('button');
      minus.type = 'button';
      minus.className = 'mm-tv-step-btn';
      minus.textContent = '−';
      minus.setAttribute('aria-label', 'تقليل القيمة');
      minus.onclick = function (e) { e.preventDefault(); stepValue(input, -1); };

      var val = document.createElement('span');
      val.className = 'mm-tv-step-val';
      val.setAttribute('data-mm-tv-step-val', id);
      val.textContent = input.value;

      var plus = document.createElement('button');
      plus.type = 'button';
      plus.className = 'mm-tv-step-btn';
      plus.textContent = '+';
      plus.setAttribute('aria-label', 'زيادة القيمة');
      plus.onclick = function (e) { e.preventDefault(); stepValue(input, 1); };

      wrap.appendChild(minus);
      wrap.appendChild(val);
      wrap.appendChild(plus);
      if (input.parentNode) input.parentNode.insertBefore(wrap, input.nextSibling);
      input.addEventListener('input', function () { updateStepper(input); });
      input.addEventListener('change', function () { updateStepper(input); });
    };

    var makeFocusable = function (root) {
      root = root && root.querySelectorAll ? root : document;
      var q = 'button,a[href],input,select,textarea,[role="button"],[onclick]';
      Array.prototype.forEach.call(root.querySelectorAll(q), function (el) {
        if (el.disabled || el.getAttribute('aria-hidden') === 'true') return;
        if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '0');
      });
      Array.prototype.forEach.call(root.querySelectorAll('input[type="range"],input[type="number"]'), addStepper);
    };

    var visibleFocusables = function () {
      var q = 'button:not([disabled]),a[href],input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[role="button"],[onclick]';
      return Array.prototype.filter.call(document.querySelectorAll(q), function (el) {
        var r = el.getBoundingClientRect();
        var st = getComputedStyle(el);
        return r.width > 4 && r.height > 4 && st.display !== 'none' && st.visibility !== 'hidden';
      });
    };

    var center = function (el) {
      var r = el.getBoundingClientRect();
      return { x:r.left + r.width/2, y:r.top + r.height/2 };
    };

    var spatialMove = function (key) {
      var items = visibleFocusables();
      if (!items.length) return false;
      var cur = document.activeElement;
      if (items.indexOf(cur) < 0) {
        items[0].focus();
        try { items[0].scrollIntoView({block:'center', inline:'nearest'}); } catch (e) {}
        return true;
      }
      var c = center(cur), best = null, bestScore = 1e12;
      items.forEach(function (el) {
        if (el === cur) return;
        var p = center(el), dx = p.x - c.x, dy = p.y - c.y;
        var main, cross;
        if (key === 'ArrowRight') { if (dx <= 6) return; main = dx; cross = Math.abs(dy); }
        else if (key === 'ArrowLeft') { if (dx >= -6) return; main = -dx; cross = Math.abs(dy); }
        else if (key === 'ArrowDown') { if (dy <= 6) return; main = dy; cross = Math.abs(dx); }
        else { if (dy >= -6) return; main = -dy; cross = Math.abs(dx); }
        var score = main + cross * 2.2;
        if (score < bestScore) { bestScore = score; best = el; }
      });
      if (!best) return false;
      best.focus();
      try { best.scrollIntoView({block:'center', inline:'nearest', behavior:'smooth'}); } catch (e) {}
      return true;
    };

    document.addEventListener('keydown', function (e) {
      var el = document.activeElement;
      if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') &&
          el && (el.type === 'range' || el.type === 'number')) {
        e.preventDefault();
        stepValue(el, e.key === 'ArrowRight' ? 1 : -1);
        return;
      }
      if (e.key === 'Enter' && el && el.matches &&
          el.matches('[role="button"],[onclick]') && el.tagName !== 'BUTTON' && el.tagName !== 'A') {
        e.preventDefault();
        try { el.click(); } catch (x) {}
        return;
      }
      if (e.key === 'ArrowUp' || e.key === 'ArrowDown' ||
          e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        if (el && el.tagName === 'SELECT' && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) return;
        if (el && (el.type === 'text' || el.tagName === 'TEXTAREA') &&
            (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) return;
        if (spatialMove(e.key)) e.preventDefault();
      }
    }, true);

    makeFocusable(document);
    var mo = new MutationObserver(function (muts) {
      muts.forEach(function (m) {
        Array.prototype.forEach.call(m.addedNodes || [], function (n) {
          if (n && n.nodeType === 1) {
            if (n.matches && n.matches('input[type="range"],input[type="number"]')) addStepper(n);
            makeFocusable(n);
          }
        });
      });
    });
    mo.observe(document.body, { childList:true, subtree:true });

    window.__mmTvFocusFirst = function () {
      makeFocusable(document);
      var items = visibleFocusables();
      if (items.length) {
        items[0].focus();
        try { items[0].scrollIntoView({block:'center', inline:'nearest'}); } catch (e) {}
      }
    };
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', installTvMode, { once:true });
  } else {
    installTvMode();
  }

  /* ---------------- files: save / share ---------------- */
  var b64of = function (blob) {
    return new Promise(function (res, rej) {
      var fr = new FileReader();
      fr.onload = function () { var s = String(fr.result); res(s.slice(s.indexOf(',') + 1)); };
      fr.onerror = rej; fr.readAsDataURL(blob);
    });
  };
  var sendBlob = async function (blob, name) {
    var id = Math.random().toString(36).slice(2) + Date.now().toString(36);
    N.fileBegin(id, name || 'file', blob.type || '');
    var CH = 512 * 1024;
    for (var o = 0; o < blob.size; o += CH) N.fileChunk(id, await b64of(blob.slice(o, o + CH)));
    N.fileEnd(id);
    return id;
  };
  var saveUrl = function (href, name) {
    fetch(href).then(function (r) { return r.blob(); })
      .then(function (b) { return sendBlob(b, name); })
      .then(function (id) { N.saveFiles(id); })
      .catch(function () { N.toast('مقدرتش أحفظ الملف'); });
  };
  var handleAnchor = function (a) {
    var h = a.href || '';
    if (a.hasAttribute('download') && /^(blob:|data:)/.test(h)) { saveUrl(h, a.getAttribute('download') || 'file'); return true; }
    if (/^(https?:|mailto:|tel:|whatsapp:)/.test(h) && h.indexOf(location.origin) !== 0) { N.openExternal(h); return true; }
    return false;
  };
  var _click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () { if (!handleAnchor(this)) return _click.call(this); };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest && e.target.closest('a[href]');
    if (a && handleAnchor(a)) e.preventDefault();
  }, true);

  var share = async function (d) {
    d = d || {};
    var ids = [];
    var files = d.files || [];
    for (var i = 0; i < files.length; i++) ids.push(await sendBlob(files[i], files[i].name));
    if (!ids.length) {
      var txt = [d.title, d.text, d.url].filter(Boolean).join('\n');
      N.openExternal('https://wa.me/?text=' + encodeURIComponent(txt));
      return;
    }
    N.shareFiles(ids.join(','), d.title || '');
  };
  try { Object.defineProperty(navigator, 'share', { value: share, configurable: true }); } catch (e) {}
  try { Object.defineProperty(navigator, 'canShare', { value: function () { return true; }, configurable: true }); } catch (e) {}

  /* ---------------- report window → Android print (Save as PDF) ---------------- */
  var _open = window.open;
  window.open = function (url) {
    if (!url || url === 'about:blank') {
      var buf = '';
      var doc = {
        write: function () { buf += Array.prototype.join.call(arguments, ''); },
        writeln: function () { buf += Array.prototype.join.call(arguments, '') + '\n'; },
        open: function () { buf = ''; },
        close: function () {
          var m = buf.match(/<title>([\s\S]*?)<\/title>/i);
          N.printHtml(buf, m ? m[1] : 'Medica Mall Report');
        }
      };
      return { document: doc, print: function () {}, focus: function () {}, close: function () {} };
    }
    var abs = new URL(url, location.href).href;
    if (abs.indexOf(location.origin) === 0) return _open.apply(window, arguments);
    N.openExternal(abs);
    return null;
  };
})();
