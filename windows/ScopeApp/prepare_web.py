"""CI: bundle the latest exam screen (from the live site, or the last Android APK as fallback) + app icon."""
import base64, gzip, io, os, re, sys, time, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
URL = 'https://medicamall.com/ent-scope'
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml', 'Accept-Encoding': 'gzip', 'Accept-Language': 'ar,en;q=0.8',
}


def from_site():
    for attempt in range(6):
        url = URL + ('?v=%s%d' % (os.environ.get('GITHUB_RUN_ID', int(time.time())), attempt) if attempt % 2 == 0 else '')
        try:
            raw = urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=90).read()
            if raw[:2] == b'\x1f\x8b':
                raw = gzip.decompress(raw)
            m = re.search(r'var B64 = "([^"]+)"', raw.decode('utf-8', 'replace'))
            if m:
                return base64.b64decode(m.group(1)).decode('utf-8')
            print('attempt %d: B64 not found (%d bytes)' % (attempt + 1, len(raw)))
        except Exception as e:
            print('attempt %d: %s' % (attempt + 1, e))
        time.sleep(15)
    return None


def from_apk(path='prev.apk'):
    if not os.path.exists(path):
        return None
    with zipfile.ZipFile(path) as z:
        html = z.read('assets/index.html').decode('utf-8')
    return html.replace('<script src="native-shim.js"></script>', '', 1)


app = from_site()
if app is None:
    app = from_apk()
    if app is None:
        sys.exit('ERROR: exam screen not available from the site or the Android APK')
    print('::warning::Site not reachable - used the exam screen from the Android APK')

os.makedirs(os.path.join(HERE, 'web'), exist_ok=True)
with open(os.path.join(HERE, 'web', 'index.html'), 'w', encoding='utf-8') as f:
    f.write(app)

logo = re.search(r'id="defLogo"[^>]*?src="data:image/png;base64,([^"]+)"', app) or \
       re.search(r'src="data:image/png;base64,([^"]+)"[^>]*?id="defLogo"', app)
if logo:
    from PIL import Image
    im = Image.open(io.BytesIO(base64.b64decode(logo.group(1)))).convert('RGBA')
    side = max(im.size)
    sq = Image.new('RGBA', (side, side), (255, 255, 255, 0))
    sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    sq.save(os.path.join(HERE, 'app.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print('icon OK')
else:
    print('::warning::logo not found - building without an icon')
print('OK: index.html %d KB' % (len(app) // 1024))
