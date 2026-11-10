// Medica Mall Scope (Capture) – separate test build of the Windows app for the analog "1CH USB Video Capture" dongle
// (Somagic "SMI Grabber Device", USB 1C88). Installs side by side with the normal app (own folder, shortcut and updates)
// and carries the dongle's Windows driver (drivers\SmiGrabberDriver.exe, from the dongle's CD).
// Medica Mall Scope – Windows app
// Same exam screen as medicamall.com/ent-scope, inside WebView2. The Besdata scope is read natively (DirectShow):
// live video is served to the page at /uvc/frame.jpg and the scope's hardware button (UVC still-pin trigger)
// becomes photo (1 press) / record (2 presses). Auto-updates: exam screen from the site, app from GitHub releases.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Management;
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
            using (var showEvt = new EventWaitHandle(false, EventResetMode.AutoReset, "MedicaMallScopeCapture_Show", out created))
            using (var mutex = new Mutex(true, "MedicaMallScopeCapture_SingleInstance", out first))
            {
                // already running (maybe hidden in the tray): ask it to show its window
                if (!first) { if (!background) showEvt.Set(); return; }
                Application.Run(new MainForm(background, showEvt));
            }
        }
    }

    static class Paths
    {
        public static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MedicaMallScopeCapture");
        public static readonly string App = Path.Combine(Root, "app");
        public static readonly string Web = Path.Combine(Root, "web");
        public static readonly string Data = Path.Combine(Root, "WebView2");
        public static string ExeDir { get { return AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\'); } }
        public const string ExeName = "MedicaMallScopeCapture.exe";

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
                    string lnk = Path.Combine(folder, "Medica Mall Scope (Capture).lnk");
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
            lt.InvokeMember("Description", BindingFlags.SetProperty, null, lnk, new object[] { "Medica Mall – scope via USB video capture" });
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
        public bool Running, HasButton, IsOto, IsGrab, IsHdmiDev;
        public int W = 1280, H = 720;
        public event Action Button;

        // the analog USB capture dongle (Somagic SMI Grabber / EasyCAP-type, USB 1C88:xxxx)
        public static bool IsCapture(DsDevice d)
        {
            string n = ((d == null ? null : d.Name) ?? "").ToLowerInvariant();
            string p = ((d == null ? null : d.DevicePath) ?? "").ToLowerInvariant();
            return n.Contains("smi grabber") || n.Contains("usb video capture") || n.Contains("easycap") || n.Contains("somagic") ||
                   n.Contains("usbtv") || n.Contains("usb 2861") || p.Contains("vid_1c88");
        }

        // HDMI -> USB capture cards (MacroSilicon MS2109/2130 "USB Video", Cam Link, ...): plain UVC cameras, no analog setup.
        // (a laptop's own HDMI port is output only – the camera's HDMI goes through one of these cards)
        public static bool IsHdmi(DsDevice d)
        {
            string n = ((d == null ? null : d.Name) ?? "").ToLowerInvariant();
            string p = ((d == null ? null : d.DevicePath) ?? "").ToLowerInvariant();
            return n.Contains("hdmi") || n.Contains("macrosilicon") || n.Contains("cam link") || n.Contains("usb video") || n.Contains("usb3 video") ||
                   n.Contains("capture") || p.Contains("vid_534d") || p.Contains("vid_345f") || p.Contains("vid_0fd9");
        }

        public static bool IsOtoscope(DsDevice d)
        {
            string p = ((d == null ? null : d.DevicePath) ?? "").ToLowerInvariant();
            return p.Contains("vid_1224&pid_2825");
        }

        // the scope (Besdata) first; otherwise the Video Otoscope (USB 1224:2825)
        public static DsDevice Find()
        {
            DsDevice scope = null, oto = null, grab = null, hdmi = null;
            foreach (var d in DsDevice.GetDevicesOfCat(FilterCategory.VideoInputDevice))
            {
                string n = (d.Name ?? "").ToLowerInvariant();
                if (scope == null && (n.Contains("besdata") || n.Contains("endoscope"))) scope = d;
                else if (grab == null && IsCapture(d)) grab = d;
                else if (hdmi == null && IsHdmi(d)) hdmi = d;
                // the otoscope has no still-image pin and no HID (Button Spy, 2026-10-10): its button can't reach the PC,
                // so it stays a normal Windows camera (WebView2) – the app does not hold it natively
                else if (oto == null && false && IsOtoscope(d)) oto = d;
                else d.Dispose();
            }
            if (scope != null) { if (oto != null) oto.Dispose(); if (grab != null) grab.Dispose(); if (hdmi != null) hdmi.Dispose(); return scope; }
            if (grab != null) { if (oto != null) oto.Dispose(); if (hdmi != null) hdmi.Dispose(); return grab; }
            if (hdmi != null) { if (oto != null) oto.Dispose(); return hdmi; }
            return oto;
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

                bool grabDev = IsCapture(dev);
                if (grabDev) SetupAnalog(builder, cap);

                IPin capPin = DsFindPin.ByCategory(cap, PinCategory.Capture, 0);
                bool hdmiDev = !grabDev && IsHdmi(dev);
                if (capPin != null) PickBestFormat(capPin, grabDev ? (AnalogSettings.Pal ? 576 : 480) : hdmiDev ? 1080 : 1920);

                // live video -> RGB24 grabber
                capGrab = (ISampleGrabber)new SampleGrabber();
                var mt = new AMMediaType { majorType = MediaType.Video, subType = MediaSubType.RGB24, formatType = FormatType.VideoInfo };
                capGrab.SetMediaType(mt); DsUtils.FreeAMMediaType(mt);
                var gf = (IBaseFilter)capGrab;
                var nr = (IBaseFilter)new NullRenderer();
                graph.AddFilter(gf, "CapGrab"); graph.AddFilter(nr, "CapNull");
                Chk(builder.RenderStream(PinCategory.Capture, MediaType.Video, cap, gf, nr), "Render capture");
                if (grabDev) SetupAnalog(builder, cap); // RenderStream adds the dongle's crossbar filter: route its input now
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
                IsOto = IsOtoscope(dev);
                IsGrab = grabDev || hdmiDev;
                IsHdmiDev = hdmiDev;
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

        // analog dongle: route the chosen input (composite / S-Video) to the decoder and set PAL / NTSC
        static void SetupAnalog(ICaptureGraphBuilder2 builder, IBaseFilter cap)
        {
            try
            {
                object o;
                if (builder.FindInterface(null, null, cap, typeof(IAMCrossbar).GUID, out o) >= 0 && o is IAMCrossbar)
                {
                    var xb = (IAMCrossbar)o;
                    int outs, ins;
                    xb.get_PinCounts(out outs, out ins);
                    var want = AnalogSettings.SVideo ? PhysicalConnectorType.Video_SVideo : PhysicalConnectorType.Video_Composite;
                    for (int oi = 0; oi < outs; oi++)
                    {
                        int rel; PhysicalConnectorType ot;
                        xb.get_CrossbarPinInfo(false, oi, out rel, out ot);
                        if (ot != PhysicalConnectorType.Video_VideoDecoder) continue;
                        for (int ii = 0; ii < ins; ii++)
                        {
                            PhysicalConnectorType it;
                            xb.get_CrossbarPinInfo(true, ii, out rel, out it);
                            if (it == want) { Paths.Log("crossbar " + it + " -> decoder: 0x" + xb.Route(oi, ii).ToString("X8")); break; }
                        }
                    }
                }
                else Paths.Log("no crossbar on the capture device");
            }
            catch (Exception ex) { Paths.Log("crossbar: " + ex.Message); }
            try
            {
                var dec = cap as IAMAnalogVideoDecoder;
                if (dec != null)
                {
                    var std = AnalogSettings.Pal ? AnalogVideoStandard.PAL_B : AnalogVideoStandard.NTSC_M;
                    Paths.Log("tv format " + std + ": 0x" + dec.put_TVFormat(std).ToString("X8"));
                }
            }
            catch (Exception ex) { Paths.Log("tv format: " + ex.Message); }
        }

        void PickBestFormat(IPin pin, int maxH)
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
                        if (w <= 1920 && h <= maxH)
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

    // ---------------------------------------------------------------- photos / videos on a USB drive (the device in storage mode)
    static class DriveWatch
    {
        static readonly string[] Exts = { ".jpg", ".jpeg", ".png", ".bmp", ".mp4", ".avi", ".mov", ".mkv", ".3gp" };
        static readonly object Lock = new object();
        static string[] known = new string[0];
        static List<string> files = new List<string>();
        static string root = "";

        // true when a removable drive appeared since the last call
        public static bool Changed()
        {
            string[] now;
            try { now = DriveInfo.GetDrives().Where(d => d.DriveType == DriveType.Removable && d.IsReady).Select(d => d.RootDirectory.FullName).OrderBy(x => x).ToArray(); }
            catch { return false; }
            bool added = now.Except(known).Any();
            known = now;
            return added;
        }

        static void Walk(string dir, int depth, List<string> acc)
        {
            if (depth > 4 || acc.Count >= 800) return;
            try
            {
                foreach (var f in Directory.GetFiles(dir))
                    if (Exts.Contains(Path.GetExtension(f).ToLowerInvariant())) acc.Add(f);
                foreach (var d in Directory.GetDirectories(dir))
                {
                    var n = Path.GetFileName(d);
                    if (n.StartsWith(".") || n.Equals("System Volume Information", StringComparison.OrdinalIgnoreCase) || n.StartsWith("$")) continue;
                    Walk(d, depth + 1, acc);
                }
            }
            catch { }
        }

        public static string ListJson()
        {
            var sb = new StringBuilder("{\"drives\":[");
            lock (Lock)
            {
                files = new List<string>();
                bool firstDrive = true;
                foreach (var r in known)
                {
                    var acc = new List<string>();
                    Walk(r, 0, acc);
                    string label = "";
                    try { label = new DriveInfo(r).VolumeLabel; } catch { }
                    if (!firstDrive) sb.Append(','); firstDrive = false;
                    sb.Append("{\"root\":\"").Append(Esc(r)).Append("\",\"label\":\"").Append(Esc(label)).Append("\",\"files\":[");
                    for (int i = 0; i < acc.Count; i++)
                    {
                        var fi = new FileInfo(acc[i]);
                        long ms = (long)(fi.LastWriteTimeUtc - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
                        if (i > 0) sb.Append(',');
                        sb.Append("{\"id\":").Append(files.Count).Append(",\"name\":\"").Append(Esc(fi.Name)).Append("\",\"path\":\"").Append(Esc(acc[i].Substring(r.Length)))
                          .Append("\",\"size\":").Append(fi.Length).Append(",\"mtime\":").Append(ms).Append('}');
                        files.Add(acc[i]);
                    }
                    sb.Append("]}");
                }
            }
            return sb.Append("]}").ToString();
        }

        public static byte[] Read(int id, out string name)
        {
            name = "";
            string p;
            lock (Lock) { if (id < 0 || id >= files.Count) return null; p = files[id]; }
            name = Path.GetFileName(p);
            try { return File.ReadAllBytes(p); } catch (Exception ex) { Paths.Log("drive read: " + ex.Message); return null; }
        }

        static string Esc(string s) { return (s ?? "").Replace("\\", "\\\\").Replace("\"", "\\\""); }
    }

    // ---------------------------------------------------------------- capture dongle: input / TV system (saved per PC)
    static class AnalogSettings
    {
        static string FileP { get { return Path.Combine(Paths.Root, "analog.txt"); } }
        static string Read() { try { return File.Exists(FileP) ? File.ReadAllText(FileP) : ""; } catch { return ""; } }
        static void Write(bool sv, bool pal) { try { Directory.CreateDirectory(Paths.Root); File.WriteAllText(FileP, (sv ? "svideo" : "composite") + " " + (pal ? "pal" : "ntsc")); } catch { } }
        public static bool SVideo { get { return Read().Contains("svideo"); } set { Write(value, Pal); } }
        public static bool Pal { get { return !Read().Contains("ntsc"); } set { Write(SVideo, value); } } // Egypt = PAL
    }

    // ---------------------------------------------------------------- capture dongle driver (Somagic SMI USB Grabber, bundled)
    static class GrabberDriver
    {
        public static string Setup { get { return Path.Combine(Paths.ExeDir, "drivers", "SmiGrabberDriver.exe"); } }

        // true when a Somagic dongle (USB VID 1C88) is plugged in but Windows has no working driver for it
        public static bool PluggedWithoutDriver()
        {
            try
            {
                using (var q = new ManagementObjectSearcher("SELECT Name, ConfigManagerErrorCode FROM Win32_PnPEntity WHERE DeviceID LIKE '%VID_1C88%'"))
                {
                    bool seen = false, ok = false;
                    foreach (ManagementObject m in q.Get())
                    {
                        seen = true;
                        object e = m["ConfigManagerErrorCode"];
                        if (e != null && Convert.ToInt32(e) == 0) ok = true;
                        Paths.Log("dongle pnp: " + m["Name"] + " err=" + e);
                    }
                    return seen && !ok;
                }
            }
            catch (Exception ex) { Paths.Log("dongle pnp: " + ex.Message); return false; }
        }

        // diagnostics: every camera Windows offers + capture-like USB devices (with their driver state) go to log.txt
        public static void LogDevices()
        {
            try
            {
                foreach (var d in DsDevice.GetDevicesOfCat(FilterCategory.VideoInputDevice))
                {
                    Paths.Log("video device: " + d.Name + " | " + d.DevicePath + (ScopeCamera.IsCapture(d) ? "  <- capture dongle" : ""));
                    d.Dispose();
                }
            }
            catch (Exception ex) { Paths.Log("video devices: " + ex.Message); }
            try
            {
                using (var q = new ManagementObjectSearcher("SELECT Name, DeviceID, PNPClass, ConfigManagerErrorCode FROM Win32_PnPEntity WHERE DeviceID LIKE 'USB%' OR ConfigManagerErrorCode <> 0"))
                    foreach (ManagementObject m in q.Get())
                    {
                        string id = (m["DeviceID"] ?? "").ToString();
                        object e = m["ConfigManagerErrorCode"];
                        bool problem = e != null && Convert.ToInt32(e) != 0;
                        string cls = (m["PNPClass"] ?? "").ToString();
                        if (problem || cls == "Camera" || cls == "Image" || cls == "MEDIA" || id.ToUpperInvariant().Contains("VID_1C88") || id.ToUpperInvariant().Contains("VID_EB1A"))
                            Paths.Log("usb: " + m["Name"] + " | " + cls + " | " + id + " | err=" + e);
                    }
            }
            catch (Exception ex) { Paths.Log("usb devices: " + ex.Message); }
        }

        public static void Install(IWin32Window owner)
        {
            if (!File.Exists(Setup)) { MessageBox.Show(owner, "ملف التعريف مش موجود جنب البرنامج.", "Medica Mall", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }
            try
            {
                Process.Start(new ProcessStartInfo(Setup) { UseShellExecute = true, Verb = "runas", WorkingDirectory = Path.GetDirectoryName(Setup) });
                Paths.Log("driver setup started");
                MessageBox.Show(owner, "كمّل خطوات تسطيب التعريف (Next / Install).\nبعد ما يخلص: شيل كارت الكاميرا من الـ USB ورجّعه تاني، والصورة هتظهر لوحدها.",
                    "Medica Mall – تعريف كارت الكاميرا", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex) { Paths.Log("driver setup: " + ex.Message); }
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
        // exam screen: web/index.html in the public repo (plain HTML)
        const string SiteUrl = "https://raw.githubusercontent.com/Mostafe16/medicamall-scope/main/web/index.html";
        const string Rel = "https://github.com/Mostafe16/medicamall-scope/releases/download/capture-test/";

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
                // GitHub serves plain HTML; the old site page wrapped it as base64 in 'var B64'
                var m = Regex.Match(page, "var B64 = \"([A-Za-z0-9+/=]+)\"");
                string html = m.Success ? Encoding.UTF8.GetString(Convert.FromBase64String(m.Groups[1].Value)) : page;
                if (!html.Contains("btnSnap") || !html.Contains("</html>")) { Paths.Log("web update: not an exam screen (" + page.Length + " chars)"); return false; }
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
                using (var c = Http()) bytes = await c.GetByteArrayAsync(Rel + "MedicaMallScope-Capture.zip?t=" + DateTime.UtcNow.Ticks);
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
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run", RunName = "MedicaMallScopeCapture";
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
            Text = "Medica Mall – شاشة كشف المنظار (كارت الكاميرا)";
            Width = 1280; Height = 820; StartPosition = FormStartPosition.CenterScreen; WindowState = FormWindowState.Maximized;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            Controls.Add(web);
            BuildDriverBar();
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
            menu.Items.Add("تسطيب تعريف كارت الكاميرا (SMI Grabber)", null, (s, e) => GrabberDriver.Install(this));
            var svItem = new ToolStripMenuItem("كارت الكاميرا: مدخل S-Video (بدل الأصفر Composite)") { Checked = AnalogSettings.SVideo };
            svItem.Click += (s, e) => { AnalogSettings.SVideo = svItem.Checked = !svItem.Checked; RestartCam(); };
            var ntscItem = new ToolStripMenuItem("كارت الكاميرا: نظام NTSC (بدل PAL)") { Checked = !AnalogSettings.Pal };
            ntscItem.Click += (s, e) => { ntscItem.Checked = !ntscItem.Checked; AnalogSettings.Pal = !ntscItem.Checked; RestartCam(); };
            menu.Items.Add(svItem);
            menu.Items.Add(ntscItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("خروج نهائي", null, (s, e) => ExitApp());
            tray.Icon = Icon ?? SystemIcons.Application;
            tray.Text = "Medica Mall Scope (Capture)";
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

        // top bar: "install the capture card driver" button, always visible + the card's status
        readonly Panel driverBar = new Panel { Dock = DockStyle.Top, Height = 46, BackColor = Color.FromArgb(255, 244, 214), RightToLeft = RightToLeft.Yes };
        readonly Label driverLbl = new Label { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleRight, Font = new Font("Segoe UI", 11f), ForeColor = Color.FromArgb(10, 63, 125), Padding = new Padding(0, 0, 12, 0) };
        readonly Button driverBtn = new Button { Dock = DockStyle.Right, Width = 260, Text = "⬇  تسطيب تعريف كارت الكاميرا", Font = new Font("Segoe UI", 11f, FontStyle.Bold),
                                                 BackColor = Color.FromArgb(255, 193, 7), ForeColor = Color.FromArgb(10, 63, 125), FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand };
        void BuildDriverBar()
        {
            driverBtn.FlatAppearance.BorderSize = 0;
            driverBtn.Click += (s, e) => GrabberDriver.Install(this);
            driverBar.Padding = new Padding(8, 6, 8, 6);
            driverBar.Controls.Add(driverLbl);
            driverBar.Controls.Add(driverBtn);
            Controls.Add(driverBar);
            UpdateDriverBar();
        }

        void UpdateDriverBar()
        {
            bool ok = cam.Running && cam.IsGrab;
            driverBar.BackColor = ok ? Color.FromArgb(220, 245, 226) : Color.FromArgb(255, 244, 214);
            driverLbl.Text = ok ? (cam.IsHdmiDev ? "✓ كارت HDMI شغال" : "✓ كارت الكاميرا شغال") + (cam.W > 0 ? " (" + cam.W + "×" + cam.H + ")" : "")
                               : cam.Running ? "المنظار شغال. لكارت الكاميرا: سطّب التعريف لو أول مرة، ووصّل الكارت."
                               : "كارت الكاميرا مش ظاهر: دوس «تسطيب تعريف كارت الكاميرا» لو أول مرة، وبعدين شيل الكارت ورجّعه في الـ USB. (كارت HDMI مش محتاج تعريف)";
        }

        void RestartCam()
        {
            if (cam.Running) { cam.Stop(); Event("detached"); }
            nextStartTry = DateTime.MinValue;
            Tick();
        }

        bool driverAsked;
        void CheckDriver()
        {
            if (driverAsked) return;
            driverAsked = true;
            Task.Run(() => GrabberDriver.LogDevices());
            var _ = Task.Run(() => GrabberDriver.PluggedWithoutDriver()).ContinueWith(t =>
            {
                if (!t.Result) return;
                try
                {
                    BeginInvoke(new Action(() =>
                    {
                        if (MessageBox.Show(this, "كارت الكاميرا (USB Video Capture) متوصل بس التعريف بتاعه مش متسطب.\nأسطّب التعريف دلوقتي؟",
                                "Medica Mall – تعريف كارت الكاميرا", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
                            GrabberDriver.Install(this);
                    }));
                }
                catch { }
            });
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
                else if (m != null && m.StartsWith("wa:")) ShareWhatsApp(m.Substring(3));
            };
            cw.Navigate(Host + "/index.html");
            Tick();
            CheckDriver();
            CheckUpdates();
        }

        // Report → WhatsApp: the PDF goes to the clipboard (Ctrl+V in the chat) and the patient's chat opens with the message.
        // payload: phone \n base64(utf8 text) \n base64(utf8 file name) \n base64(pdf)
        static void ShareWhatsApp(string payload)
        {
            try
            {
                var p = payload.Split(new[] { '\n' }, 4);
                if (p.Length < 4) return;
                string phone = Regex.Replace(p[0], "\\D", "");
                string text = Encoding.UTF8.GetString(Convert.FromBase64String(p[1]));
                string name = Encoding.UTF8.GetString(Convert.FromBase64String(p[2]));
                foreach (char c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
                if (name.Length == 0) name = "report.pdf";
                string dir = Path.Combine(Path.GetTempPath(), "ScopeReports");
                Directory.CreateDirectory(dir);
                string file = Path.Combine(dir, name);
                System.IO.File.WriteAllBytes(file, Convert.FromBase64String(p[3]));
                var list = new System.Collections.Specialized.StringCollection { file };
                try { Clipboard.SetFileDropList(list); } catch (Exception ex) { Paths.Log("wa clipboard: " + ex.Message); }
                string q = (phone.Length > 0 ? "phone=" + phone + "&" : "") + "text=" + Uri.EscapeDataString(text);
                try { Process.Start(new ProcessStartInfo("whatsapp://send?" + q) { UseShellExecute = true }); }
                catch { OpenExternal("https://web.whatsapp.com/send?" + q); }
            }
            catch (Exception ex) { Paths.Log("wa: " + ex.Message); }
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
            return "{\"connected\":" + (cam.Running ? "true" : "false") + ",\"w\":" + cam.W + ",\"h\":" + cam.H + ",\"button\":" + (cam.HasButton ? "true" : "false") + ",\"kind\":\"" + (cam.Running && cam.IsOto ? "oto" : "scope") + "\"}";
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
                else if (path == "/native/drive")
                {
                    d = e.GetDeferral();
                    string json = await Task.Run(() => DriveWatch.ListJson());
                    e.Response = Resp(Encoding.UTF8.GetBytes(json), 200, "OK", "application/json");
                }
                else if (path == "/native/drivefile")
                {
                    d = e.GetDeferral();
                    int id; int.TryParse(HttpUtility_Query(e.Request.Uri, "id"), out id);
                    string name = "";
                    byte[] b = await Task.Run(() => DriveWatch.Read(id, out name));
                    string ext = Path.GetExtension(name).ToLowerInvariant();
                    string type = ext == ".png" ? "image/png" : ext == ".bmp" ? "image/bmp" : (ext == ".jpg" || ext == ".jpeg") ? "image/jpeg" : ext == ".mov" ? "video/quicktime" : ext == ".avi" ? "video/x-msvideo" : "video/mp4";
                    e.Response = b == null ? Resp(new byte[0], 404, "Not Found", "text/plain") : Resp(b, 200, "OK", type);
                }
                else if (path == "/native/state") e.Response = Resp(Encoding.UTF8.GetBytes(StateJson()), 200, "OK", "application/json");
                else if (path == "/win-shim.js") e.Response = Resp(Encoding.UTF8.GetBytes(shim), 200, "OK", "text/javascript; charset=utf-8");
                else if (path == "/" || path == "/index.html") e.Response = Resp(Encoding.UTF8.GetBytes(WebApp.Html()), 200, "OK", "text/html; charset=utf-8");
                else e.Response = Resp(new byte[0], 404, "Not Found", "text/plain");
            }
            catch (Exception ex) { Paths.Log("request " + path + ": " + ex.Message); }
            finally { if (d != null) d.Complete(); }
        }

        static string HttpUtility_Query(string uri, string key)
        {
            var m = Regex.Match(uri, "[?&]" + key + "=([^&]*)");
            return m.Success ? Uri.UnescapeDataString(m.Groups[1].Value) : "";
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
            // the device plugged in as a USB drive -> tell the exam screen (it asks before importing)
            try { if (DriveWatch.Changed() && Visible) { Paths.Log("usb drive appeared"); Event("drive"); } } catch { }
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
            finally { if (dev != null) dev.Dispose(); try { UpdateDriverBar(); } catch { } }
        }
    }
}
