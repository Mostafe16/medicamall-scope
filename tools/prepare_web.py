"""Pull the latest exam-screen web app from the Medica Mall site and bundle it into the APK."""
import base64, gzip, os, re, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
MIPMAP = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'mipmap-xxxhdpi')

url = os.environ.get('WEB_URL', 'https://medicamall.com/ent-scope')
if url.startswith('http'):
    url += ('&' if '?' in url else '?') + 'v=' + os.environ.get('GITHUB_RUN_ID', str(int(time.time())))
req = urllib.request.Request(url, headers={
    'User-Agent': 'Mozilla/5.0 (Linux; Android 13; SM-A536E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml',
    'Accept-Encoding': 'gzip',
    'Accept-Language': 'ar,en;q=0.8',
})
raw = urllib.request.urlopen(req, timeout=90).read()
if raw[:2] == b'\x1f\x8b':
    raw = gzip.decompress(raw)
page = raw.decode('utf-8', 'replace')

m = re.search(r'var B64 = "([^"]+)"', page)
if not m:
    sys.exit('ERROR: exam-screen code (var B64) not found on %s – got %d bytes' % (url, len(page)))
app = base64.b64decode(m.group(1)).decode('utf-8')

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
