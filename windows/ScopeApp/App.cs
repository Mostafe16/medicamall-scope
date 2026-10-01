// Medica Mall Scope – Windows app
// Same exam screen as medicamall.com/ent-scope, inside WebView2. The Besdata scope is read natively (DirectShow):
// live video is served to the page at /uvc/frame.jpg and the scope's hardware button (UVC still-pin trigger)
// becomes photo (1 press) / record (2 presses). Auto-updates: exam screen from the site, app from GitHub releases.
using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using DirectShowLib;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace MedicaMallScope
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            bool background = Environment.GetCommandLineArgs().Any(a => a == "--background");
            if (!background && Installer.RunInstalledCopyIfNeeded()) return;
            bool created, first;
            using (var showEvt = new EventWaitHandle(false, EventResetMode.AutoReset, "MedicaMallScope_Show", out created))
            using (var mutex = new Mutex(true, "MedicaMallScope_SingleInstance", out first))
            {
                // already running (maybe hidden in the tray): ask it to show its window
                if (!first) { if (!background) showEvt.Set(); return; }
                Application.Run(new MainForm(background, showEvt));
            }
        }
    }

    static class Paths
    {
        public static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MedicaMallScope");
        public static readonly string App = Path.Combine(Root, "app");
        public static readonly string Web = Path.Combine(Root, "web");
        public static readonly string Data = Path.Combine(Root, "WebView2");
        public static string ExeDir { get { return AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\'); } }
        public const string ExeName = "MedicaMallScope.exe";

        public static void Log(string s)
        {
            try
            {
                Directory.CreateDirectory(Root);
                File.AppendAllText(Path.Combine(Root, "log.txt"), DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + "  " + s + Environment.NewLine);
            }
            catch { }
        }
    }

    // First run from the downloaded folder: copy itself to %LOCALAPPDATA%\MedicaMallScope\app, add shortcuts, start from there.
    static class Installer
    {
        public static bool RunInstalledCopyIfNeeded()
        {
            try
            {
                if (string.Equals(Paths.ExeDir, Paths.App, StringComparison.OrdinalIgnoreCase)) return false;
                if (Environment.GetCommandLineArgs().Any(a => a == "--portable")) return false;
                string installedExe = Path.Combine(Paths.App, Paths.ExeName);
                Version mine = Assembly.GetExecutingAssembly().GetName().Version, theirs = null;
                try { if (File.Exists(installedExe)) theirs = AssemblyName.GetAssemblyName(installedExe).Version; } catch { }
                if (theirs == null || theirs < mine)
                {
                    Directory.CreateDirectory(Paths.App);
                    CopyDir(Paths.ExeDir, Paths.App);
                    Paths.Log("installed " + mine + " to " + Paths.App);
                }
                EnsureShortcuts(true);
                Process.Start(new ProcessStartInfo(installedExe) { UseShellExecute = true, WorkingDirectory = Paths.App });
                return true;
            }
            catch (Exception ex) { Paths.Log("install failed, running in place: " + ex.Message); return false; }
        }

        static void CopyDir(string src, string dst)
        {
            foreach (var d in Directory.GetDirectories(src, "*", SearchOption.AllDirectories))
                Directory.CreateDirectory(Path.Combine(dst, d.Substring(src.Length + 1)));
            foreach (var f in Directory.GetFiles(src, "*", SearchOption.AllDirectories))
                File.Copy(f, Path.Combine(dst, f.Substring(src.Length + 1)), true);
        }

        static void EnsureShortcuts(bool force)
        {
            string exe = Path.Combine(Paths.App, Paths.ExeName);
            foreach (var folder in new[] { Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Environment.GetFolderPath(Environment.SpecialFolder.Programs) })
            {
                try
                {
                    string lnk = Path.Combine(folder, "Medica Mall Scope.lnk");
                    if (force || !File.Exists(lnk)) MakeShortcut(lnk, exe);
                }
                catch (Exception ex) { Paths.Log("shortcut: " + ex.Message); }
            }
        }

        static void MakeShortcut(string lnkPath, string target)
        {
            var t = Type.GetTypeFromProgID("WScript.Shell");
            object shell = Activator.CreateInstance(t);
            object lnk = t.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { lnkPath });
            var lt = lnk.GetType();
            lt.InvokeMember("TargetPath", BindingFlags.SetProperty, null, lnk, new object[] { target });
            lt.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, lnk, new object[] { Path.GetDirectoryName(target) });
            lt.InvokeMember("Description", BindingFlags.SetProperty, null, lnk, new object[] { "Medica Mall – Besdata ENT scope" });
            lt.InvokeMember("Save", BindingFlags.InvokeMethod, null, lnk, null);
        }
    }

    // ---------------------------------------------------------------- scope camera (DirectShow)
    class ScopeCamera
    {
        class Grab : ISampleGrabberCB
        {
            public Action<IntPtr, int> OnBuf;
            public int SampleCB(double t, IMediaSample s) { return 0; }
            public int BufferCB(double t, IntPtr b, int len) { try { var a = OnBuf; if (a != null) a(b, len); } catch { } return 0; }
        }

        IFilterGraph2 graph;
        IMediaControl control;
        IPin still;
        IAMVideoControl vc;
        ISampleGrabber capGrab, stillGrab;
        readonly Grab capCb = new Grab(), stillCb = new Grab();
        readonly object frameLock = new object();
        byte[] raw, jpg;
        int seq, encSeq = -1, servedSeq = -1;
        static readonly ImageCodecInfo JpegCodec = ImageCodecInfo.GetImageEncoders().First(c => c.MimeType == "image/jpeg");

        public string DevicePath;
        public bool Running, HasButton;
        public int W = 1280, H = 720;
        public event Action Button;

        public static DsDevice Find()
        {
            DsDevice found = null;
            foreach (var d in DsDevice.GetDevicesOfCat(FilterCategory.VideoInputDevice))
            {
                string n = (d.Name ?? "").ToLowerInvariant();
                if (found == null && (n.Contains("besdata") || n.Contains("endoscope"))) found = d;
                else d.Dispose();
            }
            return found;
        }

        static void Chk(int hr, string what) { if (hr < 0) throw new Exception(what + " failed: 0x" + hr.ToString("X8")); }

        public bool Start(DsDevice dev)
        {
            Stop();
            try
            {
                graph = (IFilterGraph2)new FilterGraph();
                var builder = (ICaptureGraphBuilder2)new CaptureGraphBuilder2();
                Chk(builder.SetFiltergraph(graph), "SetFiltergraph");
                IBaseFilter cap;
                Chk(graph.AddSourceFilterForMoniker(dev.Mon, null, dev.Name, out cap), "AddSourceFilter");

                IPin capPin = DsFindPin.ByCategory(cap, PinCategory.Capture, 0);
                if (capPin != null) PickBestFormat(capPin);

                // live video -> RGB24 grabber
                capGrab = (ISampleGrabber)new SampleGrabber();
                var mt = new AMMediaType { majorType = MediaType.Video, subType = MediaSubType.RGB24, formatType = FormatType.VideoInfo };
                capGrab.SetMediaType(mt); DsUtils.FreeAMMediaType(mt);
                var gf = (IBaseFilter)capGrab;
                var nr = (IBaseFilter)new NullRenderer();
                graph.AddFilter(gf, "CapGrab"); graph.AddFilter(nr, "CapNull");
                Chk(builder.RenderStream(PinCategory.Capture, MediaType.Video, cap, gf, nr), "Render capture");
                var cm = new AMMediaType();
                Chk(capGrab.GetConnectedMediaType(cm), "GetConnectedMediaType");
                var vih = (VideoInfoHeader)Marshal.PtrToStructure(cm.formatPtr, typeof(VideoInfoHeader));
                W = vih.BmiHeader.Width; H = Math.Abs(vih.BmiHeader.Height);
                DsUtils.FreeAMMediaType(cm);
                capGrab.SetBufferSamples(false); capGrab.SetOneShot(false);
                capCb.OnBuf = OnFrame; capGrab.SetCallback(capCb, 1);

                // scope button = UVC still-pin trigger (connect after the live stream, enable after Run)
                HasButton = false;
                still = DsFindPin.ByCategory(cap, PinCategory.Still, 0);
                vc = cap as IAMVideoControl;
                if (still != null)
                {
                    foreach (var sub in new Guid?[] { MediaSubType.RGB24, null })
                    {
                        var sg = (ISampleGrabber)new SampleGrabber();
                        var smt = new AMMediaType { majorType = MediaType.Video };
                        if (sub.HasValue) { smt.subType = sub.Value; smt.formatType = FormatType.VideoInfo; }
                        sg.SetMediaType(smt); DsUtils.FreeAMMediaType(smt);
                        var sgf = (IBaseFilter)sg;
                        var snr = (IBaseFilter)new NullRenderer();
                        graph.AddFilter(sgf, "StillGrab"); graph.AddFilter(snr, "StillNull");
                        int hr = builder.RenderStream(PinCategory.Still, MediaType.Video, cap, sgf, snr);
                        if (hr >= 0)
                        {
                            stillGrab = sg;
                            sg.SetBufferSamples(false); sg.SetOneShot(false);
                            stillCb.OnBuf = (b, l) => { var a = Button; if (a != null) a(); };
                            sg.SetCallback(stillCb, 1);
                            HasButton = true;
                            break;
                        }
                        Paths.Log("still pin connect (" + (sub.HasValue ? "RGB24" : "any") + "): 0x" + hr.ToString("X8"));
                        try { graph.RemoveFilter(snr); graph.RemoveFilter(sgf); } catch { }
                    }
                }

                control = (IMediaControl)graph;
                Chk(control.Run(), "Run");
                if (HasButton && vc != null)
                {
                    int hr = vc.SetMode(still, VideoControlFlags.ExternalTriggerEnable);
                    Paths.Log("button trigger enable: 0x" + hr.ToString("X8"));
                }
                DevicePath = dev.DevicePath;
                Running = true;
                Paths.Log("scope started: " + dev.Name + " " + W + "x" + H + " button=" + HasButton);
                return true;
            }
            catch (Exception ex)
            {
                Paths.Log("scope start failed: " + ex.Message);
                Stop();
                return false;
            }
        }

        void PickBestFormat(IPin pin)
        {
            var sc = pin as IAMStreamConfig;
            if (sc == null) return;
            int count, size;
            if (sc.GetNumberOfCapabilities(out count, out size) < 0) return;
            IntPtr buf = Marshal.AllocCoTaskMem(Math.Max(size, 256));
            try
            {
                int best = -1; long bestScore = -1;
                for (int i = 0; i < count; i++)
                {
                    AMMediaType mt;
                    if (sc.GetStreamCaps(i, out mt, buf) < 0 || mt == null) continue;
                    if (mt.formatType == FormatType.VideoInfo && mt.formatPtr != IntPtr.Zero)
                    {
                        var v = (VideoInfoHeader)Marshal.PtrToStructure(mt.formatPtr, typeof(VideoInfoHeader));
                        int w = v.BmiHeader.Width, h = Math.Abs(v.BmiHeader.Height);
                        if (w <= 1920 && h <= 1920)
                        {
                            long score = (long)w * h * 2 + (mt.subType == MediaSubType.MJPG ? 1 : 0);
                            if (score > bestScore) { bestScore = score; best = i; }
                        }
                    }
                    DsUtils.FreeAMMediaType(mt);
                }
                if (best >= 0)
                {
                    AMMediaType mt;
                    if (sc.GetStreamCaps(best, out mt, buf) >= 0 && mt != null)
                    {
                        int hr = sc.SetFormat(mt);
                        Paths.Log("capture format [" + best + "] set: 0x" + hr.ToString("X8"));
                        DsUtils.FreeAMMediaType(mt);
                    }
                }
            }
            finally { Marshal.FreeCoTaskMem(buf); }
        }

        void OnFrame(IntPtr b, int len)
        {
            lock (frameLock)
            {
                if (raw == null || raw.Length != len) raw = new byte[len];
                Marshal.Copy(b, raw, 0, len);
                seq++;
                Monitor.PulseAll(frameLock);
            }
        }

        // Latest frame as JPEG; waits briefly for a newer frame than the last one served.
        public byte[] GetJpeg(int waitMs)
        {
            byte[] copy; int s, w, h;
            lock (frameLock)
            {
                if (seq == servedSeq) Monitor.Wait(frameLock, waitMs);
                if (raw == null) return null;
                servedSeq = seq;
                if (encSeq == seq && jpg != null) return jpg;
                copy = (byte[])raw.Clone(); s = seq; w = W; h = H;
            }
            byte[] enc = Encode(copy, w, h);
            lock (frameLock) { if (enc != null) { jpg = enc; encSeq = s; } return jpg; }
        }

        static byte[] Encode(byte[] data, int w, int h)
        {
            int stride = ((w * 3) + 3) & ~3;
            if (w <= 0 || h <= 0 || data.Length < stride * h) return null;
            var hnd = GCHandle.Alloc(data, GCHandleType.Pinned);
            try
            {
                IntPtr lastRow = hnd.AddrOfPinnedObject() + stride * (h - 1); // RGB24 is bottom-up
                using (var bmp = new Bitmap(w, h, -stride, PixelFormat.Format24bppRgb, lastRow))
                using (var ms = new MemoryStream())
                using (var ep = new EncoderParameters(1))
                {
                    ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 90L);
                    bmp.Save(ms, JpegCodec, ep);
                    return ms.ToArray();
                }
            }
            finally { hnd.Free(); }
        }

        public void Stop()
        {
            Running = false;
            capCb.OnBuf = null; stillCb.OnBuf = null;
            try { if (control != null) control.Stop(); } catch { }
            try { if (graph != null) Marshal.ReleaseComObject(graph); } catch { }
            graph = null; control = null; still = null; vc = null; capGrab = null; stillGrab = null;
            lock (frameLock) { raw = null; jpg = null; encSeq = -1; servedSeq = -1; }
        }
    }

    // ---------------------------------------------------------------- exam screen (web app) files
    static class WebApp
    {
        static string Cached { get { return Path.Combine(Paths.Web, "index.html"); } }
        static string Bundled { get { return Path.Combine(Paths.ExeDir, "web", "index.html"); } }

        public static string Raw()
        {
            string c = Cached, b = Bundled;
            bool hc = File.Exists(c), hb = File.Exists(b);
            if (!hc && !hb) return null;
            string pick = hc && hb ? (File.GetLastWriteTimeUtc(c) >= File.GetLastWriteTimeUtc(b) ? c : b) : (hc ? c : b);
            return File.ReadAllText(pick, Encoding.UTF8);
        }

        public static string Html()
        {
            string app = Raw();
            if (app == null)
                return "<!doctype html><html dir=\"rtl\"><head><meta charset=\"utf-8\"><script src=\"/win-shim.js\"></script></head>" +
                       "<body style=\"font-family:Segoe UI,Tahoma;display:grid;place-items:center;height:100vh;color:#0a3f7d\"><h2>جاري تحميل شاشة الكشف… اتأكد إن النت شغال</h2></body></html>";
            int i = app.IndexOf("<head>", StringComparison.OrdinalIgnoreCase);
            const string tag = "<script src=\"/win-shim.js\"></script>";
            return i >= 0 ? app.Insert(i + 6, tag) : tag + app;
        }

        public static void Save(string html)
        {
            Directory.CreateDirectory(Paths.Web);
            File.WriteAllText(Cached, html, new UTF8Encoding(false));
        }
    }

    // ---------------------------------------------------------------- updates
    static class Updates
    {
        const string SiteUrl = "https://medicamall.com/ent-scope";
        const string Rel = "https://github.com/Mostafe16/medicamall-scope/releases/download/win-app/";

        static HttpClient Http()
        {
            var h = new HttpClientHandler { AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate };
            var c = new HttpClient(h) { Timeout = TimeSpan.FromSeconds(120) };
            c.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
            c.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "text/html,application/xhtml+xml,application/json,*/*");
            c.DefaultRequestHeaders.TryAddWithoutValidation("Accept-Language", "ar,en;q=0.8");
            return c;
        }

        // Returns true when a newer exam screen was saved.
        public static async Task<bool> CheckWebAsync()
        {
            try
            {
                string page;
                using (var c = Http()) page = await c.GetStringAsync(SiteUrl + "?v=" + DateTime.UtcNow.Ticks);
                var m = Regex.Match(page, "var B64 = \"([A-Za-z0-9+/=]+)\"");
                if (!m.Success) { Paths.Log("web update: B64 not found (" + page.Length + " chars)"); return false; }
                string html = Encoding.UTF8.GetString(Convert.FromBase64String(m.Groups[1].Value));
                if (html == WebApp.Raw()) return false;
                WebApp.Save(html);
                Paths.Log("web update saved (" + html.Length + " chars)");
                return true;
            }
            catch (Exception ex) { Paths.Log("web update: " + ex.Message); return false; }
        }

        public static async Task CheckAppAsync(MainForm owner)
        {
            try
            {
                int cur = Assembly.GetExecutingAssembly().GetName().Version.Build;
                string json;
                using (var c = Http()) json = await c.GetStringAsync(Rel + "version.json?t=" + DateTime.UtcNow.Ticks);
                var m = Regex.Match(json, "\"version\"\\s*:\\s*(\\d+)");
                if (!m.Success) return;
                int latest = int.Parse(m.Groups[1].Value);
                Paths.Log("app version " + cur + ", latest " + latest);
                if (latest <= cur) return;
                if (MessageBox.Show(owner, "فيه نسخة جديدة من برنامج Medica Mall Scope.\nتحب تحدّث دلوقتي؟ (دقيقة واحدة – المرضى والصور مش هتتأثر)",
                        "Medica Mall – تحديث", MessageBoxButtons.YesNo, MessageBoxIcon.Information) != DialogResult.Yes) return;

                string tmp = Path.Combine(Path.GetTempPath(), "mmscope-update-" + latest);
                if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
                Directory.CreateDirectory(tmp);
                string zip = Path.Combine(tmp, "app.zip"), src = Path.Combine(tmp, "files");
                byte[] bytes;
                using (var c = Http()) bytes = await c.GetByteArrayAsync(Rel + "MedicaMallScope.zip?t=" + DateTime.UtcNow.Ticks);
                File.WriteAllBytes(zip, bytes);
                ZipFile.ExtractToDirectory(zip, src);
                if (!File.Exists(Path.Combine(src, Paths.ExeName))) throw new Exception("update package incomplete");

                string dst = Paths.ExeDir, exe = Path.Combine(dst, Paths.ExeName);
                Func<string, string> q = s => "'" + s.Replace("'", "''") + "'";
                string ps = "Wait-Process -Id " + Process.GetCurrentProcess().Id + " -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 700; " +
                            "Copy-Item -Path " + q(src + "\\*") + " -Destination " + q(dst) + " -Recurse -Force; " +
                            "Start-Process -FilePath " + q(exe) + " -WorkingDirectory " + q(dst);
                Process.Start(new ProcessStartInfo("powershell.exe",
                    "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(ps)))
                { UseShellExecute = false, CreateNoWindow = true });
                Paths.Log("updating to " + latest);
                owner.ExitApp();
            }
            catch (Exception ex) { Paths.Log("app update: " + ex.Message); }
        }
    }

    // ---------------------------------------------------------------- main window (+ tray: opens by itself when the scope is plugged in)
    class MainForm : Form
    {
        const string Host = "https://scope.medicamall.local";
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run", RunName = "MedicaMallScope";
        readonly WebView2 web = new WebView2 { Dock = DockStyle.Fill };
        readonly ScopeCamera cam = new ScopeCamera();
        readonly System.Windows.Forms.Timer poll = new System.Windows.Forms.Timer { Interval = 2000 };
        readonly NotifyIcon tray = new NotifyIcon();
        readonly ToolStripMenuItem autoItem = new ToolStripMenuItem("يفتح لوحده مع Windows ولما المنظار يتوصل");
        CoreWebView2Environment env;
        string shim = "";
        DateTime nextStartTry = DateTime.MinValue, lastUpdateCheck = DateTime.MinValue;
        bool allowShow, inited, exiting, lastPresent, balloonShown;

        static string AutoOffFlag { get { return Path.Combine(Paths.Root, "autostart-off"); } }

        public MainForm(bool background, EventWaitHandle showEvt)
        {
            allowShow = !background;
            lastPresent = !background; // started in the background: a scope that is already plugged in opens the window
            Text = "Medica Mall – شاشة كشف منظار الأنف والأذن";
            Width = 1280; Height = 820; StartPosition = FormStartPosition.CenterScreen; WindowState = FormWindowState.Maximized;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            Controls.Add(web);
            try
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("win-shim.js"))
                using (var r = new StreamReader(s, Encoding.UTF8)) shim = r.ReadToEnd();
            }
            catch (Exception ex) { Paths.Log("shim: " + ex.Message); }

            // tray icon
            var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes };
            menu.Items.Add("فتح شاشة الكشف", null, (s, e) => ShowApp());
            autoItem.Checked = !File.Exists(AutoOffFlag);
            autoItem.Click += (s, e) => SetAutoStart(!autoItem.Checked);
            menu.Items.Add(autoItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("خروج نهائي", null, (s, e) => ExitApp());
            tray.Icon = Icon ?? SystemIcons.Application;
            tray.Text = "Medica Mall Scope";
            tray.ContextMenuStrip = menu;
            tray.Visible = true;
            tray.DoubleClick += (s, e) => ShowApp();
            SetAutoStart(autoItem.Checked);

            cam.Button += () => { Paths.Log("scope button"); try { BeginInvoke(new Action(() => Event("button"))); } catch { } };
            Shown += (s, e) => EnsureInit();
            FormClosing += OnClosing;
            poll.Tick += (s, e) => Tick();
            poll.Start();

            // another launch (desktop icon) while we run hidden -> show this window
            var t = new Thread(() =>
            {
                while (true)
                {
                    try { showEvt.WaitOne(); BeginInvoke(new Action(ShowApp)); } catch { Thread.Sleep(1000); }
                }
            }) { IsBackground = true };
            t.Start();
            Paths.Log("start " + Assembly.GetExecutingAssembly().GetName().Version + " from " + Paths.ExeDir + (background ? " (background)" : ""));
        }

        protected override void SetVisibleCore(bool value)
        {
            if (!allowShow)
            {
                value = false;
                if (!IsHandleCreated) CreateHandle();
            }
            base.SetVisibleCore(value);
        }

        public void ShowApp()
        {
            allowShow = true;
            if (!Visible) Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Maximized;
            TopMost = true; TopMost = false;
            Activate();
            EnsureInit();
            BeginInvoke(new Action(Tick));
            if (inited && web.CoreWebView2 != null && DateTime.Now - lastUpdateCheck > TimeSpan.FromHours(6)) CheckUpdates();
        }

        public void ExitApp()
        {
            exiting = true;
            try { tray.Visible = false; tray.Dispose(); } catch { }
            Close();
        }

        void OnClosing(object sender, FormClosingEventArgs e)
        {
            if (!exiting && e.CloseReason == CloseReason.UserClosing)
            {
                // keep running in the tray: release the scope, open again automatically when it is plugged in
                e.Cancel = true;
                Hide();
                allowShow = false;
                if (cam.Running) { cam.Stop(); Event("detached"); }
                if (!balloonShown)
                {
                    balloonShown = true;
                    try { tray.ShowBalloonTip(5000, "Medica Mall Scope", "البرنامج شغال في الخلفية وهيفتح لوحده أول ما توصّل المنظار.", ToolTipIcon.Info); } catch { }
                }
                return;
            }
            poll.Stop(); cam.Stop();
            try { tray.Visible = false; } catch { }
        }

        void SetAutoStart(bool on)
        {
            try
            {
                Directory.CreateDirectory(Paths.Root);
                using (var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey, true) ?? Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey))
                {
                    if (on)
                    {
                        k.SetValue(RunName, "\"" + Application.ExecutablePath + "\" --background");
                        if (File.Exists(AutoOffFlag)) File.Delete(AutoOffFlag);
                    }
                    else
                    {
                        k.DeleteValue(RunName, false);
                        File.WriteAllText(AutoOffFlag, "1");
                    }
                }
                autoItem.Checked = on;
            }
            catch (Exception ex) { Paths.Log("autostart: " + ex.Message); }
        }

        void EnsureInit()
        {
            if (inited) return;
            inited = true;
            var _ = InitAsync();
        }

        async Task InitAsync()
        {
            try
            {
                env = await CoreWebView2Environment.CreateAsync(null, Paths.Data);
                await web.EnsureCoreWebView2Async(env);
            }
            catch (Exception ex)
            {
                Paths.Log("webview2: " + ex.Message);
                if (MessageBox.Show(this, "البرنامج محتاج «Microsoft Edge WebView2 Runtime» (مجاني من مايكروسوفت).\nأفتح صفحة التحميل؟",
                        "Medica Mall", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
                    OpenExternal("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
                ExitApp();
                return;
            }
            var cw = web.CoreWebView2;
            cw.Settings.IsStatusBarEnabled = false;
            cw.AddWebResourceRequestedFilter(Host + "/*", CoreWebView2WebResourceContext.All);
            cw.WebResourceRequested += OnRequest;
            cw.PermissionRequested += (s, e) =>
            {
                if (e.PermissionKind == CoreWebView2PermissionKind.Camera || e.PermissionKind == CoreWebView2PermissionKind.Microphone ||
                    e.PermissionKind == CoreWebView2PermissionKind.ClipboardRead)
                    e.State = CoreWebView2PermissionState.Allow;
            };
            cw.NewWindowRequested += (s, e) =>
            {
                string u = e.Uri ?? "";
                if (IsExternal(u)) { e.Handled = true; OpenExternal(u); }
            };
            cw.NavigationStarting += (s, e) =>
            {
                string u = e.Uri ?? "";
                if (IsExternal(u)) { e.Cancel = true; OpenExternal(u); }
            };
            cw.WebMessageReceived += (s, e) =>
            {
                string m = null;
                try { m = e.TryGetWebMessageAsString(); } catch { }
                if (m == "reloadWeb") cw.Reload();
                else if (m != null && m.StartsWith("log:")) Paths.Log("web: " + m.Substring(4));
            };
            cw.Navigate(Host + "/index.html");
            Tick();
            CheckUpdates();
        }

        async void CheckUpdates()
        {
            lastUpdateCheck = DateTime.Now;
            bool hadApp = WebApp.Raw() != null;
            if (await Updates.CheckWebAsync())
            {
                if (hadApp) Event("webupdate");
                else if (web.CoreWebView2 != null) web.CoreWebView2.Reload();
            }
            await Updates.CheckAppAsync(this);
        }

        static bool IsExternal(string u)
        {
            if (u.StartsWith(Host, StringComparison.OrdinalIgnoreCase)) return false;
            return !(u.StartsWith("about:") || u.StartsWith("data:") || u.StartsWith("blob:") || u.Length == 0);
        }

        static void OpenExternal(string u)
        {
            try { Process.Start(new ProcessStartInfo(u) { UseShellExecute = true }); } catch (Exception ex) { Paths.Log("open " + u + ": " + ex.Message); }
        }

        CoreWebView2WebResourceResponse Resp(byte[] body, int code, string reason, string type)
        {
            return env.CreateWebResourceResponse(new MemoryStream(body), code, reason, "Content-Type: " + type + "\r\nCache-Control: no-store");
        }

        string StateJson()
        {
            return "{\"connected\":" + (cam.Running ? "true" : "false") + ",\"w\":" + cam.W + ",\"h\":" + cam.H + ",\"button\":" + (cam.HasButton ? "true" : "false") + "}";
        }

        async void OnRequest(object sender, CoreWebView2WebResourceRequestedEventArgs e)
        {
            string path = "";
            CoreWebView2Deferral d = null;
            try
            {
                path = new Uri(e.Request.Uri).AbsolutePath;
                if (path == "/uvc/frame.jpg")
                {
                    d = e.GetDeferral();
                    byte[] jpg = null;
                    if (cam.Running) jpg = await Task.Run(() => cam.GetJpeg(150));
                    e.Response = jpg == null ? Resp(new byte[0], 204, "No Content", "text/plain") : Resp(jpg, 200, "OK", "image/jpeg");
                }
                else if (path == "/native/state") e.Response = Resp(Encoding.UTF8.GetBytes(StateJson()), 200, "OK", "application/json");
                else if (path == "/win-shim.js") e.Response = Resp(Encoding.UTF8.GetBytes(shim), 200, "OK", "text/javascript; charset=utf-8");
                else if (path == "/" || path == "/index.html") e.Response = Resp(Encoding.UTF8.GetBytes(WebApp.Html()), 200, "OK", "text/html; charset=utf-8");
                else e.Response = Resp(new byte[0], 404, "Not Found", "text/plain");
            }
            catch (Exception ex) { Paths.Log("request " + path + ": " + ex.Message); }
            finally { if (d != null) d.Complete(); }
        }

        void Event(string evt)
        {
            try
            {
                if (web.CoreWebView2 != null)
                    web.CoreWebView2.ExecuteScriptAsync("window.__besState&&window.__besState(" + StateJson() + ");window.__besNative&&window.__besNative('" + evt + "');");
            }
            catch { }
        }

        void Tick()
        {
            DsDevice dev = null;
            try { dev = ScopeCamera.Find(); } catch { }
            bool present = dev != null, arrived = present && !lastPresent;
            lastPresent = present;
            try
            {
                if (!Visible)
                {
                    // hidden in the tray: don't hold the scope; plugging it in opens the window
                    if (cam.Running) cam.Stop();
                    if (arrived) { Paths.Log("scope plugged in -> opening"); ShowApp(); }
                    return;
                }
                if (cam.Running && (dev == null || dev.DevicePath != cam.DevicePath))
                {
                    cam.Stop();
                    Paths.Log("scope detached");
                    Event("detached");
                }
                if (!cam.Running && dev != null && DateTime.Now >= nextStartTry)
                {
                    if (cam.Start(dev)) Event("open");
                    else nextStartTry = DateTime.Now.AddSeconds(10);
                }
            }
            finally { if (dev != null) dev.Dispose(); }
        }
    }
}
