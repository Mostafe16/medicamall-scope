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
// runtime APIs newer than old head-unit WebViews (only defined when missing)
const POLY = `<script>(function(){
var rd=function(b,how){return new Promise(function(res,rej){var f=new FileReader();f.onload=function(){res(f.result)};f.onerror=function(){rej(f.error)};f[how](b)})};
if(window.Blob&&!Blob.prototype.arrayBuffer)Blob.prototype.arrayBuffer=function(){return rd(this,'readAsArrayBuffer')};
if(window.Blob&&!Blob.prototype.text)Blob.prototype.text=function(){return rd(this,'readAsText')};
if(!Array.prototype.flat)Array.prototype.flat=function(d){d=d===undefined?1:d;return this.reduce(function(a,v){return a.concat(Array.isArray(v)&&d>0?v.flat(d-1):v)},[])};
if(!Array.prototype.flatMap)Array.prototype.flatMap=function(f,t){return this.map(f,t).flat()};
if(window.Promise&&!Promise.prototype.finally)Promise.prototype.finally=function(f){return this.then(function(v){return Promise.resolve(f()).then(function(){return v})},function(e){return Promise.resolve(f()).then(function(){throw e})})};
if(!Object.fromEntries)Object.fromEntries=function(it){var o={};Array.from(it).forEach(function(e){o[e[0]]=e[1]});return o};
})();</script>`;
html = html.replace('<head>', '<head>' + POLY);
fs.writeFileSync(htmlPath, html);

const shimPath = path.join(assets, 'native-shim.js');
const shim = esbuild.transformSync(fs.readFileSync(shimPath, 'utf8'), { loader: 'js', target: TARGET, charset: 'utf8' }).code;
fs.writeFileSync(shimPath, shim);

if (/\?\.[A-Za-z_$(\[]/.test(html.replace(/<script src[^>]*>/g, '')) && /\w\?\.\w/.test(html)) console.warn('note: "?." still present (may be inside strings/regex)');
console.log('legacy web OK:', n, 'inline scripts + native-shim.js lowered to', TARGET.join(','));
