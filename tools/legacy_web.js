// Car head units ship old Android System WebView (often Chrome < 80) that can't parse modern JS
// (optional chaining, optional catch binding…) – the whole exam-screen script dies silently.
// This rewrites the bundled exam screen + native shim down to Chrome 58 syntax. Used by the car build only.
const fs = require('fs');
const path = require('path');
const esbuild = require('esbuild');
const TARGET = ['chrome58'];
const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

const htmlPath = path.join(assets, 'index.html');
let html = fs.readFileSync(htmlPath, 'utf8');
let n = 0;
html = html.replace(/<script>([\s\S]*?)<\/script>/g, (all, code) => {
  const out = esbuild.transformSync(code, { loader: 'js', target: TARGET, charset: 'utf8', legalComments: 'none' }).code;
  n++;
  return '<script>' + out.replace(/<\/script/gi, '<\\/script') + '</script>';
});
if (!n) { console.error('no inline scripts found'); process.exit(1); }
fs.writeFileSync(htmlPath, html);

const shimPath = path.join(assets, 'native-shim.js');
const shim = esbuild.transformSync(fs.readFileSync(shimPath, 'utf8'), { loader: 'js', target: TARGET, charset: 'utf8' }).code;
fs.writeFileSync(shimPath, shim);

if (/\?\.[A-Za-z_$(\[]/.test(html.replace(/<script src[^>]*>/g, '')) && /\w\?\.\w/.test(html)) console.warn('note: "?." still present (may be inside strings/regex)');
console.log('legacy web OK:', n, 'inline scripts + native-shim.js lowered to', TARGET.join(','));
