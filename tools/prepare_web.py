"""Pull the latest exam-screen web app from the Medica Mall site and bundle it into the APK."""
import base64, gzip, os, re, sys, time, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
MIPMAP = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'mipmap-xxxhdpi')

BASE_URL = os.environ.get('WEB_URL', 'https://medicamall.com/ent-scope')
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 13; SM-A536E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml',
    'Accept-Encoding': 'gzip',
    'Accept-Language': 'ar,en;q=0.8',
}



SESSION_BRIDGE = r"""
/* ---------- Android native media bridge (in page scope) ---------- */
window.__mmPageImportNativeMedia = async function(item){
  try{
    if(!item || !item.url) throw new Error('missing media url');
    const r = await fetch(item.url, {cache:'no-store'});
    if(!r.ok) throw new Error('HTTP '+r.status);
    const blob = await r.blob();
    if(!blob || !blob.size) throw new Error('empty media');
    await saveMedia(blob, item.type || 'photo', item.ext || 'jpg', item.dur || 0);
    await renderGallery();
    try{ window.BesNative && BesNative.sessionImportResult(item.type || 'photo', true, ''); }catch(e){}
  }catch(e){
    try{ window.BesNative && BesNative.sessionImportResult(item && item.type || 'photo', false, String(e && e.message || e || 'unknown')); }catch(x){}
  }
};
(function(){
  const q = window.__mmPendingNativeMedia || [];
  window.__mmPendingNativeMedia = [];
  q.forEach(x => window.__mmPageImportNativeMedia(x));
})();
"""

def inject_session_bridge(html):
    if '__mmPageImportNativeMedia' in html:
        return html
    marker = '/* ---------- init ---------- */'
    if marker in html:
        return html.replace(marker, SESSION_BRIDGE + '\n' + marker, 1)
    return html


def fetch_app():
    """Exam-screen HTML from the live page (a few retries; Cloudflare sometimes answers with a challenge page)."""
    for attempt in range(6):
        url = BASE_URL
        if url.startswith('http') and attempt % 2 == 0:
            url += ('&' if '?' in url else '?') + 'v=' + os.environ.get('GITHUB_RUN_ID', str(int(time.time()))) + str(attempt)
        try:
            raw = urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=90).read()
            if raw[:2] == b'\x1f\x8b':
                raw = gzip.decompress(raw)
            page = raw.decode('utf-8', 'replace')
            m = re.search(r'var B64 = "([^"]+)"', page)
            if m:
                return base64.b64decode(m.group(1)).decode('utf-8')
            print('attempt %d: B64 not found (%d bytes)' % (attempt + 1, len(page)))
        except Exception as e:
            print('attempt %d: %s' % (attempt + 1, e))
        time.sleep(float(os.environ.get("RETRY_SLEEP","15")))
    return None


def from_previous_apk():
    """Fallback: reuse the exam screen bundled in the last published APK."""
    apk = os.environ.get('PREV_APK', '/tmp/prev.apk')
    if not os.path.exists(apk):
        return None
    with zipfile.ZipFile(apk) as z:
        html = z.read('assets/index.html').decode('utf-8')
    return html.replace('<script src="native-shim.js"></script>', '', 1)


app = fetch_app()
if app is None:
    app = from_previous_apk()
    if app is None:
        sys.exit('ERROR: could not get the exam screen from the site or the previous APK')
    print('::warning::Site not reachable - reused exam screen from the previous APK')

app = inject_session_bridge(app)
if '<head>' in app:
    app = app.replace('<head>', '<head><script src="native-shim.js"></script>', 1)
else:
    app = '<script src="native-shim.js"></script>' + app

os.makedirs(ASSETS, exist_ok=True)
with open(os.path.join(ASSETS, 'index.html'), 'w', encoding='utf-8') as f:
    f.write(app)

logo = re.search(r'id="defLogo"[^>]*?src="data:image/png;base64,([^"]+)"', app) or \
       re.search(r'src="data:image/png;base64,([^"]+)"[^>]*?id="defLogo"', app)
if not logo:
    sys.exit('ERROR: Medica Mall logo (defLogo) not found')
os.makedirs(MIPMAP, exist_ok=True)
with open(os.path.join(MIPMAP, 'ic_launcher.png'), 'wb') as f:
    f.write(base64.b64decode(logo.group(1)))

print('OK: index.html %d KB, has rescan=%s' % (len(app) // 1024, 'btnRescan' in app))
