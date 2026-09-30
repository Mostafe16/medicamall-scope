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
  var tvWebVisible = false;
  window.__mmTvSetVisible = function (v) { tvWebVisible = !!v; };
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
          if (!tvWebVisible) { await sleep(120); continue; }
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


  /* Native Android capture -> patient-session bridge */
  window.__mmImportNativeMedia = function (item) {
    if (!item || !item.url) return;
    if (typeof window.__mmPageImportNativeMedia === 'function') {
      window.__mmPageImportNativeMedia(item);
      return;
    }
    (window.__mmPendingNativeMedia = window.__mmPendingNativeMedia || []).push(item);
  };

  /* ---------------- Android TV / D-pad adaptation ---------------- */
  var installTvMode = function () {
    if (!document.body || document.documentElement.getAttribute('data-mm-tv') === '1') return;
    document.documentElement.setAttribute('data-mm-tv', '1');

    var css = document.createElement('style');
    css.id = 'mmTvCss';
    css.textContent =
      ':focus{outline:4px solid #ffc233!important;outline-offset:3px!important;box-shadow:0 0 0 3px rgba(17,110,181,.35)!important}' +
      'button,a[href],select,input,textarea,[role="button"],[onclick]{scroll-margin:90px}' +
      '@media (min-width:900px){' +
      'html,body{width:100%!important;min-height:100%!important;overflow:auto!important;background:#eef5fc!important}' +
      'header{position:sticky!important;top:0!important;z-index:20!important;padding:6px 12px!important;gap:8px!important;min-height:58px!important}' +
      '.brand{gap:8px!important}.logo{width:40px!important;height:40px!important}.bes{height:20px!important}.brand h1{font-size:16px!important}.brand small{font-size:10px!important}' +
      '.chips{gap:5px!important;align-items:center!important}.chip{padding:4px 8px!important;font-size:11px!important;min-height:32px!important}' +
      '#btnPatients,#btnSettings{min-width:120px!important;min-height:42px!important;font-size:13px!important;padding:7px 12px!important}' +
      'main{grid-template-columns:minmax(0,1.35fr) minmax(315px,.65fr)!important;gap:10px!important;padding:10px!important;align-items:start!important}' +
      '.card{padding:10px!important;border-radius:12px!important;box-shadow:0 2px 9px rgba(10,63,125,.08)!important}' +
      '.stage{aspect-ratio:1/1!important;max-height:calc(100vh - 290px)!important;width:min(100%,calc(100vh - 290px))!important;min-height:300px!important;border-radius:12px!important}' +
      '.ov{font-size:12px!important}.toolbar{display:grid!important;grid-template-columns:repeat(5,minmax(0,1fr))!important;gap:6px!important;margin-top:8px!important}' +
      '.toolbar .sep{display:none!important}.toolbar button,.toolbar select{width:100%!important;min-width:0!important;max-width:none!important;padding:7px 7px!important;font-size:12px!important;min-height:42px!important;justify-content:center!important;text-align:center!important}' +
      '#btnSnap,#btnRec{font-size:14px!important;font-weight:800!important}#btnRescan,#camQuick{display:none!important}' +
      '.sliders{grid-template-columns:repeat(4,minmax(0,1fr))!important;gap:6px!important;margin-top:8px!important}' +
      '.sliders>div{text-align:center!important;position:relative!important;padding:7px 4px!important;border:1px solid #dce9f5!important;border-radius:10px!important;background:#f8fbff!important}.sliders label{font-size:11px!important;margin:0 0 2px!important;line-height:1.25!important}' +
      'aside{gap:10px!important}.side h2{font-size:14px!important;margin-bottom:6px!important;gap:6px!important}.side h2 span{gap:5px!important}.side h2 button{min-height:36px!important;padding:6px 9px!important;font-size:11px!important}' +
      '.patient{padding:8px 10px!important}.patient b{font-size:15px!important}.patient div{font-size:11px!important}' +
      '.gallery{grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:7px!important;max-height:310px!important}.item{border-radius:9px!important}.item:focus{border-color:#ffc233!important;outline:3px solid #ffc233!important}' +
      '.actions{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:6px!important}.actions button,.actions .upl{width:100%!important;min-height:40px!important;padding:7px 7px!important;font-size:11px!important;justify-content:center!important}' +
      '.hint{display:none!important}footer{display:none!important}' +
      'dialog{width:min(720px,92vw)!important;max-height:88vh!important;border-radius:14px!important}.dlg-h{padding:10px 14px!important}.dlg-h h3{font-size:16px!important}.dlg-h button{width:44px!important;height:40px!important;justify-content:center!important}' +
      '.dlg-b{padding:10px 14px!important;max-height:66vh!important;overflow:auto!important}.dlg-b label{font-size:12px!important;margin:6px 0 3px!important}.dlg-b input,.dlg-b select,.dlg-b textarea{min-height:44px!important;font-size:13px!important;padding:8px 10px!important}.dlg-b textarea{min-height:88px!important}' +
      '.dlg-f{padding:9px 14px!important;display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:8px!important}.dlg-f .sep{display:none!important}.dlg-f button{width:100%!important;min-height:42px!important;justify-content:center!important}' +
      '.grid2{gap:0 10px!important}.plist{gap:7px!important}.prow{padding:7px 9px!important;border-radius:10px!important;gap:8px!important}.prow b{font-size:13px!important}.prow small{font-size:10px!important}.prow button{min-height:38px!important;padding:6px 10px!important;font-size:11px!important}' +
      '.sigbox{padding:8px!important;margin-top:8px!important}#sigPad{display:none!important}.sigbox>label:nth-of-type(2){display:none!important}.viewer img,.viewer video{max-height:58vh!important;object-fit:contain!important}' +
      '#dlgShare .actions,#dlgSettings .actions{grid-template-columns:repeat(2,minmax(0,1fr))!important}' +
      '}' +
      '.mm-tv-stepper{display:grid;grid-template-columns:40px 58px 40px;align-items:center;justify-content:center;gap:6px;margin:4px auto 8px!important;direction:ltr;width:max-content;max-width:100%}' +
      '.mm-tv-step-btn{width:40px!important;height:38px!important;min-width:40px!important;min-height:38px!important;padding:0!important;border:0!important;border-radius:9px!important;background:#116eb5!important;color:#fff!important;font-size:22px!important;font-weight:900!important;line-height:1!important}' +
      '.mm-tv-step-val{min-width:58px!important;padding:7px 5px!important;border:1px solid #d9e5ef!important;border-radius:9px!important;background:#fff!important;color:#16324a!important;text-align:center!important;font:700 14px/1.2 system-ui,sans-serif!important}' +
      'input[type="range"][data-mm-tv-step-id]{position:absolute!important;opacity:0!important;pointer-events:none!important;width:1px!important;height:1px!important;margin:0!important}' +
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
      if (input.type !== 'range') return;
      var id = 'mmstep' + Math.random().toString(36).slice(2, 9);
      input.setAttribute('data-mm-tv-step-id', id);
      // On TV the physical slider/number input is display-only. Remote control
      // lands on the explicit minus/plus buttons instead of a touch-oriented slider.
      input.setAttribute('tabindex', '-1');

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
      var q = 'button,a[href],input,select,textarea,[role="button"],[onclick],.item,.upl';
      var list = [];
      if (root.matches && root.matches(q)) list.push(root);
      Array.prototype.push.apply(list, root.querySelectorAll(q));
      Array.prototype.forEach.call(list, function (el) {
        if (el.disabled || el.getAttribute('aria-hidden') === 'true') return;
        if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '0');
        if (el.classList && el.classList.contains('item')) el.setAttribute('role','button');
        if (el.classList && el.classList.contains('upl')) el.setAttribute('role','button');
      });
      if (root.matches && root.matches('input[type="range"]')) addStepper(root);
      Array.prototype.forEach.call(root.querySelectorAll('input[type="range"]'), addStepper);
    };

    var visibleFocusables = function () {
      var q = 'button:not([disabled]),a[href],input:not([disabled]):not([data-mm-tv-step-id]),select:not([disabled]),textarea:not([disabled]),[role="button"],[onclick],.item,.upl';
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
          el && el.type === 'range') {
        e.preventDefault();
        stepValue(el, e.key === 'ArrowRight' ? 1 : -1);
        return;
      }
      if (e.key === 'Enter' && el && el.classList && el.classList.contains('upl')) {
        e.preventDefault();
        var fi = el.querySelector('input[type="file"]');
        try { fi && fi.click(); } catch (x) {}
        return;
      }
      if (e.key === 'Enter' && el && el.classList && el.classList.contains('item')) {
        e.preventDefault();
        var now = Date.now();
        if (el.__mmLastEnter && now - el.__mmLastEnter < 450) {
          el.__mmLastEnter = 0;
          try { el.dispatchEvent(new MouseEvent('dblclick',{bubbles:true})); } catch (x) {}
        } else {
          el.__mmLastEnter = now;
          setTimeout(function(){
            if (el.__mmLastEnter) {
              el.__mmLastEnter = 0;
              try { el.click(); } catch (x) {}
            }
          }, 470);
        }
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

    // The Android TV build has one fixed UVC scope. Browser camera selectors only
    // add noise on TV, so keep the useful clinic/report settings and hide these.
    ['camSel','resSel'].forEach(function(id){
      var el=document.getElementById(id);
      if(el){ el.style.display='none'; var p=el.previousElementSibling; if(p&&p.tagName==='LABEL') p.style.display='none'; }
    });

    document.querySelectorAll('dialog').forEach(function(dlg){
      var ob = new MutationObserver(function(){
        if(dlg.open){
          makeFocusable(dlg);
          setTimeout(function(){
            var f = dlg.querySelector('input:not([type=file]):not([disabled]),select:not([disabled]),button:not([disabled]),textarea:not([disabled]),.upl');
            if(f) try{f.focus();}catch(e){}
          },60);
        }
      });
      ob.observe(dlg,{attributes:true,attributeFilter:['open']});
    });

    var mo = new MutationObserver(function (muts) {
      muts.forEach(function (m) {
        Array.prototype.forEach.call(m.addedNodes || [], function (n) {
          if (n && n.nodeType === 1) {
            if (n.matches && n.matches('input[type="range"]')) addStepper(n);
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
