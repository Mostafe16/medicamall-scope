"""CI (macOS): bundle the latest exam screen from the live site + build the app icon set."""
import base64, gzip, io, os, re, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
URL = 'https://medicamall.com/ent-scope'
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
    'Accept': 'text/html,application/xhtml+xml', 'Accept-Encoding': 'gzip', 'Accept-Language': 'ar,en;q=0.8',
}

app = None
for attempt in range(6):
    url = URL + ('?v=%s%d' % (os.environ.get('GITHUB_RUN_ID', int(time.time())), attempt) if attempt % 2 == 0 else '')
    try:
        raw = urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=90).read()
        if raw[:2] == b'\x1f\x8b':
            raw = gzip.decompress(raw)
        m = re.search(r'var B64 = "([^"]+)"', raw.decode('utf-8', 'replace'))
        if m:
            app = base64.b64decode(m.group(1)).decode('utf-8')
            break
        print('attempt %d: B64 not found (%d bytes)' % (attempt + 1, len(raw)))
    except Exception as e:
        print('attempt %d: %s' % (attempt + 1, e))
    time.sleep(15)

if app is None:
    print('::warning::Site not reachable - the app will download the exam screen on first launch')
else:
    os.makedirs(os.path.join(HERE, 'web'), exist_ok=True)
    with open(os.path.join(HERE, 'web', 'index.html'), 'w', encoding='utf-8') as f:
        f.write(app)
    print('OK: index.html %d KB' % (len(app) // 1024))
    logo = re.search(r'id="defLogo"[^>]*?src="data:image/png;base64,([^"]+)"', app) or \
           re.search(r'src="data:image/png;base64,([^"]+)"[^>]*?id="defLogo"', app)
    if logo:
        from PIL import Image
        im = Image.open(io.BytesIO(base64.b64decode(logo.group(1)))).convert('RGBA')
        side = int(max(im.size) * 1.12)
        sq = Image.new('RGBA', (side, side), (255, 255, 255, 255))
        sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2), im)
        d = os.path.join(HERE, 'AppIcon.iconset')
        os.makedirs(d, exist_ok=True)
        for s in (16, 32, 128, 256, 512):
            sq.resize((s, s), Image.LANCZOS).save(os.path.join(d, 'icon_%dx%d.png' % (s, s)))
            sq.resize((s * 2, s * 2), Image.LANCZOS).save(os.path.join(d, 'icon_%dx%d@2x.png' % (s, s)))
        print('icon OK')
    else:
        print('::warning::logo not found - building without an icon')
