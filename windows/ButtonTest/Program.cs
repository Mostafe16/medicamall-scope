// Medica Mall – Besdata scope button test (Windows) – v2
// Checks whether the scope's hardware button reaches Windows:
//   (a) as a UVC "still image" trigger on the DirectShow still pin, or
//   (b) as a HID / keyboard event (Raw Input).
// v2: live stream connected first, several still-pin connection strategies, still-pin format list,
//     external-trigger enabled after connect + after Run, software-trigger test button, Raw Input listener.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;
using DirectShowLib;

namespace BesdataButtonTest
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    class MainForm : Form, ISampleGrabberCB
    {
        const string Ver = "v2";
        readonly ComboBox devBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Top, Font = new Font("Segoe UI", 11) };
        readonly Button startBtn = new Button { Text = "ابدأ الاختبار", Dock = DockStyle.Top, Height = 44, Font = new Font("Segoe UI", 12, FontStyle.Bold), BackColor = Color.FromArgb(13, 111, 204), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        readonly Button swBtn = new Button { Text = "اختبار تصوير من البرنامج (بدون الزرار)", Dock = DockStyle.Top, Height = 36, Font = new Font("Segoe UI", 10), Enabled = false };
        readonly Label big = new Label { Dock = DockStyle.Top, Height = 80, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 18, FontStyle.Bold), ForeColor = Color.FromArgb(10, 63, 125), Text = "اختار المنظار واضغط «ابدأ الاختبار»" };
        readonly PictureBox pic = new PictureBox { Dock = DockStyle.Top, Height = 200, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.Black };
        readonly TextBox log = new TextBox { Dock = DockStyle.Fill, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Font = new Font("Consolas", 9.5f), RightToLeft = RightToLeft.No };

        DsDevice[] devices;
        IFilterGraph2 graph;
        IMediaControl control;
        IBaseFilter cap;
        IPin still;
        IAMVideoControl vc;
        int width, height, presses, stillFrames;
        Guid subType;
        bool stillConnected, swPending;
        readonly string outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Besdata-Button-Test");
        readonly StringBuilder report = new StringBuilder();
        readonly List<object> keep = new List<object>();

        public MainForm()
        {
            Text = "Medica Mall – اختبار زرار منظار Besdata (" + Ver + ")";
            Width = 660; Height = 760; RightToLeft = RightToLeft.Yes; RightToLeftLayout = true; StartPosition = FormStartPosition.CenterScreen;
            Controls.Add(log); Controls.Add(pic); Controls.Add(big); Controls.Add(swBtn); Controls.Add(startBtn); Controls.Add(devBox);
            startBtn.Click += (s, e) => Start();
            swBtn.Click += (s, e) => SoftwareTrigger();
            FormClosing += (s, e) => Stop();
            Directory.CreateDirectory(outDir);
            Log("Tool " + Ver + " | Windows: " + Environment.OSVersion + " | 64-bit process: " + Environment.Is64BitProcess);
            devices = DsDevice.GetDevicesOfCat(FilterCategory.VideoInputDevice);
            int pick = -1;
            for (int i = 0; i < devices.Length; i++)
            {
                devBox.Items.Add(devices[i].Name);
                Log("Camera " + i + ": " + devices[i].Name + " | " + devices[i].DevicePath);
                string n = (devices[i].Name ?? "").ToLowerInvariant();
                if (pick < 0 && (n.Contains("besdata") || n.Contains("endoscope") || n.Contains("scope"))) pick = i;
            }
            if (devices.Length == 0) { big.Text = "مفيش كاميرات ظاهرة – وصّل المنظار وافتح البرنامج تاني"; startBtn.Enabled = false; }
            else devBox.SelectedIndex = pick >= 0 ? pick : 0;
        }

        void Log(string s)
        {
            string line = DateTime.Now.ToString("HH:mm:ss.fff") + "  " + s;
            lock (report) report.AppendLine(line);
            if (log.InvokeRequired) log.BeginInvoke(new Action(() => log.AppendText(line + Environment.NewLine)));
            else log.AppendText(line + Environment.NewLine);
            try { lock (report) File.WriteAllText(Path.Combine(outDir, "report.txt"), report.ToString()); } catch { }
        }

        static string Hr(int hr) { return "0x" + hr.ToString("X8"); }

        static string Sub(Guid g)
        {
            if (g == MediaSubType.MJPG) return "MJPG";
            if (g == MediaSubType.YUY2) return "YUY2";
            if (g == MediaSubType.RGB24) return "RGB24";
            if (g == MediaSubType.RGB32) return "RGB32";
            byte[] b = g.ToByteArray();
            string fourcc = Encoding.ASCII.GetString(b, 0, 4);
            return fourcc.Trim('\0').Length == 4 ? fourcc : g.ToString();
        }

        void ListFormats(IPin pin, string label)
        {
            var sc = pin as IAMStreamConfig;
            if (sc == null) { Log(label + " formats: IAMStreamConfig not available"); return; }
            int count, size;
            int hr = sc.GetNumberOfCapabilities(out count, out size);
            Log(label + " formats: " + count + " (hr=" + Hr(hr) + ")");
            IntPtr buf = Marshal.AllocCoTaskMem(Math.Max(size, 256));
            try
            {
                for (int i = 0; i < count && i < 30; i++)
                {
                    AMMediaType mt;
                    if (sc.GetStreamCaps(i, out mt, buf) < 0 || mt == null) continue;
                    string wh = "";
                    if (mt.formatType == FormatType.VideoInfo && mt.formatPtr != IntPtr.Zero)
                    {
                        var vih = (VideoInfoHeader)Marshal.PtrToStructure(mt.formatPtr, typeof(VideoInfoHeader));
                        wh = vih.BmiHeader.Width + "x" + vih.BmiHeader.Height;
                    }
                    else if (mt.formatType == FormatType.VideoInfo2 && mt.formatPtr != IntPtr.Zero)
                    {
                        var vih2 = (VideoInfoHeader2)Marshal.PtrToStructure(mt.formatPtr, typeof(VideoInfoHeader2));
                        wh = vih2.BmiHeader.Width + "x" + vih2.BmiHeader.Height + " (VIH2)";
                    }
                    Log("   [" + i + "] " + Sub(mt.subType) + " " + wh);
                    DsUtils.FreeAMMediaType(mt);
                }
            }
            finally { Marshal.FreeCoTaskMem(buf); }
        }

        bool SetStillFormat(int index)
        {
            var sc = still as IAMStreamConfig;
            if (sc == null) return false;
            int count, size;
            if (sc.GetNumberOfCapabilities(out count, out size) < 0 || index >= count) return false;
            IntPtr buf = Marshal.AllocCoTaskMem(Math.Max(size, 256));
            try
            {
                AMMediaType mt;
                if (sc.GetStreamCaps(index, out mt, buf) < 0 || mt == null) return false;
                int hr = sc.SetFormat(mt);
                Log("   set still format [" + index + "] " + Sub(mt.subType) + ": hr=" + Hr(hr));
                DsUtils.FreeAMMediaType(mt);
                return hr >= 0;
            }
            finally { Marshal.FreeCoTaskMem(buf); }
        }

        // One attempt to connect the still pin to a fresh SampleGrabber -> NullRenderer.
        bool TryStill(ICaptureGraphBuilder2 builder, string label, Guid? forceSub, bool direct)
        {
            var g = (ISampleGrabber)new SampleGrabber();
            var gf = (IBaseFilter)g;
            var nr = (IBaseFilter)new NullRenderer();
            var mt = new AMMediaType { majorType = MediaType.Video };
            if (forceSub.HasValue) { mt.subType = forceSub.Value; mt.formatType = FormatType.VideoInfo; }
            g.SetMediaType(mt); DsUtils.FreeAMMediaType(mt);
            graph.AddFilter(gf, "Grabber"); graph.AddFilter(nr, "NullStill");
            int hr;
            if (direct)
            {
                IPin gin = DsFindPin.ByDirection(gf, PinDirection.Input, 0);
                IPin gout = DsFindPin.ByDirection(gf, PinDirection.Output, 0);
                IPin nin = DsFindPin.ByDirection(nr, PinDirection.Input, 0);
                hr = graph.Connect(still, gin);
                if (hr >= 0) hr = graph.Connect(gout, nin);
            }
            else hr = builder.RenderStream(PinCategory.Still, MediaType.Video, cap, gf, nr);
            IPin peer = null;
            bool ok = hr >= 0 && still.ConnectedTo(out peer) >= 0 && peer != null;
            Log("Still attempt [" + label + "]: hr=" + Hr(hr) + (ok ? "  -> CONNECTED" : ""));
            if (ok)
            {
                var cm = new AMMediaType();
                if (g.GetConnectedMediaType(cm) >= 0)
                {
                    subType = cm.subType;
                    if (cm.formatType == FormatType.VideoInfo && cm.formatPtr != IntPtr.Zero)
                    {
                        var vih = (VideoInfoHeader)Marshal.PtrToStructure(cm.formatPtr, typeof(VideoInfoHeader));
                        width = vih.BmiHeader.Width; height = vih.BmiHeader.Height;
                    }
                    Log("   still frame type: " + Sub(subType) + " " + width + "x" + height);
                }
                DsUtils.FreeAMMediaType(cm);
                g.SetBufferSamples(false); g.SetOneShot(false); g.SetCallback(this, 1);
                keep.Add(g); keep.Add(nr);
                return true;
            }
            // clean up this attempt
            try { if (peer != null) { graph.Disconnect(peer); graph.Disconnect(still); } } catch { }
            try { graph.RemoveFilter(nr); } catch { }
            try { graph.RemoveFilter(gf); } catch { }
            try { Marshal.ReleaseComObject(g); Marshal.ReleaseComObject(nr); } catch { }
            return false;
        }

        void EnableTrigger(string when)
        {
            if (vc == null || still == null) return;
            int hr = vc.SetMode(still, VideoControlFlags.ExternalTriggerEnable);
            VideoControlFlags mode; int hr2 = vc.GetMode(still, out mode);
            Log("Enable external trigger (" + when + "): hr=" + Hr(hr) + " | mode now: " + mode + " (hr=" + Hr(hr2) + ")");
        }

        void Start()
        {
            Stop();
            var dev = devices[devBox.SelectedIndex];
            Log("==== Testing: " + dev.Name);
            try
            {
                graph = (IFilterGraph2)new FilterGraph();
                var builder = (ICaptureGraphBuilder2)new CaptureGraphBuilder2();
                Chk(builder.SetFiltergraph(graph), "SetFiltergraph");
                Chk(graph.AddSourceFilterForMoniker(dev.Mon, null, dev.Name, out cap), "AddSourceFilterForMoniker");

                IPin capPin = DsFindPin.ByCategory(cap, PinCategory.Capture, 0);
                still = DsFindPin.ByCategory(cap, PinCategory.Still, 0);
                Log("Capture pin: " + (capPin != null ? "YES" : "NO") + " | Still pin: " + (still != null ? "YES" : "NO"));
                if (capPin != null) ListFormats(capPin, "Capture pin");
                if (still != null) ListFormats(still, "Still pin");

                vc = cap as IAMVideoControl;
                if (vc != null && still != null)
                {
                    VideoControlFlags caps;
                    int hr = vc.GetCaps(still, out caps);
                    Log("Still pin caps: " + caps + " (hr=" + Hr(hr) + ")");
                }
                else Log("IAMVideoControl: " + (vc != null ? "YES" : "NO"));

                // 1) live stream first (many UVC drivers only accept the still pin after the video pin is connected)
                var nullPrev = (IBaseFilter)new NullRenderer();
                graph.AddFilter(nullPrev, "NullPreview");
                int r = builder.RenderStream(PinCategory.Preview, MediaType.Video, cap, null, nullPrev);
                if (r < 0) r = builder.RenderStream(PinCategory.Capture, MediaType.Video, cap, null, nullPrev);
                Log("Connect live stream: hr=" + Hr(r));

                // 2) still pin: several strategies
                stillConnected = false;
                if (still != null)
                {
                    stillConnected =
                        TryStill(builder, "render RGB24", MediaSubType.RGB24, false) ||
                        TryStill(builder, "render any", null, false) ||
                        TryStill(builder, "direct any", null, true);
                    for (int i = 0; i < 4 && !stillConnected; i++)
                    {
                        if (!SetStillFormat(i)) continue;
                        stillConnected =
                            TryStill(builder, "fmt" + i + " render RGB24", MediaSubType.RGB24, false) ||
                            TryStill(builder, "fmt" + i + " direct any", null, true);
                    }
                    Log("STILL PIN CONNECTED: " + (stillConnected ? "YES" : "NO"));
                    if (stillConnected) EnableTrigger("before run");
                }

                presses = 0; stillFrames = 0;
                control = (IMediaControl)graph;
                Chk(control.Run(), "Run");
                if (stillConnected) EnableTrigger("after run");
                swBtn.Enabled = stillConnected;

                if (stillConnected)
                {
                    big.Text = "دوس زرار المنظار دلوقتي…";
                    Log("READY – press the scope button (also try the software-trigger button)");
                }
                else
                {
                    big.Text = "دوس زرار المنظار – بنسمع على الكيبورد/HID بس";
                    Log("READY – still pin not connected; listening for keyboard/HID events only. Press the scope button.");
                }
                big.ForeColor = Color.FromArgb(10, 63, 125);
            }
            catch (Exception ex)
            {
                Log("ERROR: " + ex.Message);
                big.Text = "حصلت مشكلة – اقفل أي برنامج تاني بيستخدم الكاميرا وجرّب تاني";
                big.ForeColor = Color.FromArgb(198, 40, 40);
            }
        }

        void SoftwareTrigger()
        {
            if (vc == null || still == null) return;
            swPending = true;
            int hr = vc.SetMode(still, VideoControlFlags.Trigger | VideoControlFlags.ExternalTriggerEnable);
            Log("Software trigger sent: hr=" + Hr(hr));
        }

        static void Chk(int hr, string what)
        {
            if (hr < 0) throw new Exception(what + " failed: " + Hr(hr));
        }

        void Stop()
        {
            try { control?.Stop(); } catch { }
            if (graph != null) { try { Marshal.ReleaseComObject(graph); } catch { } }
            graph = null; control = null; cap = null; still = null; vc = null; keep.Clear();
        }

        public int SampleCB(double sampleTime, IMediaSample pSample) { return 0; }

        public int BufferCB(double sampleTime, IntPtr buffer, int len)
        {
            stillFrames++;
            bool sw = swPending; swPending = false;
            string who = sw ? "SOFTWARE TRIGGER" : "BUTTON";
            if (!sw) presses++;
            int n = stillFrames;
            Bitmap bmp = null;
            try
            {
                string baseName = Path.Combine(outDir, (sw ? "software-" : "button-") + n);
                if (subType == MediaSubType.MJPG)
                {
                    var bytes = new byte[len]; Marshal.Copy(buffer, bytes, 0, len);
                    File.WriteAllBytes(baseName + ".jpg", bytes);
                    using (var ms = new MemoryStream(bytes)) bmp = new Bitmap(ms);
                    Log("✔ " + who + " – still MJPG frame saved: " + baseName + ".jpg");
                }
                else if ((subType == MediaSubType.RGB24 || subType == Guid.Empty) && width > 0 && height > 0 && len >= width * height * 3)
                {
                    int stride = ((width * 3) + 3) & ~3;
                    using (var tmp = new Bitmap(width, height, stride, PixelFormat.Format24bppRgb, buffer)) bmp = (Bitmap)tmp.Clone();
                    bmp.RotateFlip(RotateFlipType.RotateNoneFlipY);
                    bmp.Save(baseName + ".jpg", ImageFormat.Jpeg);
                    Log("✔ " + who + " – still frame " + width + "x" + height + " saved: " + baseName + ".jpg");
                }
                else
                {
                    var bytes = new byte[len]; Marshal.Copy(buffer, bytes, 0, len);
                    File.WriteAllBytes(baseName + ".raw", bytes);
                    Log("✔ " + who + " – still frame received (" + Sub(subType) + ", " + len + " bytes) saved raw");
                }
            }
            catch (Exception ex) { Log("frame error: " + ex.Message); }
            var shown = bmp;
            BeginInvoke(new Action(() =>
            {
                big.Text = sw ? "✔ التصوير من البرنامج اشتغل – دلوقتي جرّب زرار المنظار" : "✔ الزرار اتقرا! (" + presses + ")";
                big.ForeColor = Color.FromArgb(11, 138, 76);
                if (shown != null) { var old = pic.Image; pic.Image = shown; old?.Dispose(); }
            }));
            return 0;
        }

        // ---------------- Raw Input: does the button arrive as a keyboard / HID event? ----------------
        [StructLayout(LayoutKind.Sequential)]
        struct RAWINPUTDEVICE { public ushort usUsagePage; public ushort usUsage; public uint dwFlags; public IntPtr hwndTarget; }
        [DllImport("user32.dll", SetLastError = true)]
        static extern bool RegisterRawInputDevices(RAWINPUTDEVICE[] devs, uint num, uint size);
        [DllImport("user32.dll")]
        static extern uint GetRawInputData(IntPtr hRawInput, uint cmd, IntPtr data, ref uint size, uint headerSize);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern uint GetRawInputDeviceInfo(IntPtr hDevice, uint cmd, StringBuilder data, ref uint size);
        const uint RIDEV_INPUTSINK = 0x100, RIDEV_PAGEONLY = 0x20, RID_INPUT = 0x10000003, RIDI_DEVICENAME = 0x20000007;
        const int WM_INPUT = 0x00FF;

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            var list = new[]
            {
                new RAWINPUTDEVICE { usUsagePage = 0x01, usUsage = 0x06, dwFlags = 0, hwndTarget = Handle },                                // keyboard (only while this window is focused)
                new RAWINPUTDEVICE { usUsagePage = 0x01, usUsage = 0x80, dwFlags = RIDEV_INPUTSINK, hwndTarget = Handle },                  // system control
                new RAWINPUTDEVICE { usUsagePage = 0x0C, usUsage = 0x00, dwFlags = RIDEV_PAGEONLY | RIDEV_INPUTSINK, hwndTarget = Handle },  // consumer control
                new RAWINPUTDEVICE { usUsagePage = 0x90, usUsage = 0x00, dwFlags = RIDEV_PAGEONLY | RIDEV_INPUTSINK, hwndTarget = Handle },  // camera control (shutter)
                new RAWINPUTDEVICE { usUsagePage = 0xFF00, usUsage = 0x00, dwFlags = RIDEV_PAGEONLY | RIDEV_INPUTSINK, hwndTarget = Handle }, // vendor defined
            };
            foreach (var d in list)
            {
                bool ok = RegisterRawInputDevices(new[] { d }, 1, (uint)Marshal.SizeOf(typeof(RAWINPUTDEVICE)));
                Log("Raw input listen page 0x" + d.usUsagePage.ToString("X") + "/0x" + d.usUsage.ToString("X") + ": " + (ok ? "OK" : "fail " + Marshal.GetLastWin32Error()));
            }
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_INPUT)
            {
                try { HandleRawInput(m.LParam); } catch (Exception ex) { Log("raw input error: " + ex.Message); }
            }
            base.WndProc(ref m);
        }

        void HandleRawInput(IntPtr h)
        {
            uint hdr = (uint)(8 + 2 * IntPtr.Size), size = 0;
            GetRawInputData(h, RID_INPUT, IntPtr.Zero, ref size, hdr);
            if (size == 0) return;
            IntPtr buf = Marshal.AllocHGlobal((int)size);
            try
            {
                if (GetRawInputData(h, RID_INPUT, buf, ref size, hdr) != size) return;
                int type = Marshal.ReadInt32(buf, 0);
                IntPtr dev = Marshal.ReadIntPtr(buf, 8);
                string name = DevName(dev);
                if (type == 1)
                {
                    int flags = Marshal.ReadInt16(buf, (int)hdr + 2);
                    int vkey = Marshal.ReadInt16(buf, (int)hdr + 6) & 0xFFFF;
                    if ((flags & 1) == 0) // key down only
                    {
                        Log("KEYBOARD event: key=" + (Keys)vkey + " (vk 0x" + vkey.ToString("X") + ") from " + name);
                        Flash("وصل كـ كيبورد: " + (Keys)vkey);
                    }
                }
                else if (type == 2)
                {
                    int sz = Marshal.ReadInt32(buf, (int)hdr), cnt = Marshal.ReadInt32(buf, (int)hdr + 4);
                    int total = Math.Min(sz * cnt, 64);
                    var sb = new StringBuilder();
                    for (int i = 0; i < total; i++) sb.Append(Marshal.ReadByte(buf, (int)hdr + 8 + i).ToString("X2")).Append(' ');
                    Log("HID event: [" + sb.ToString().Trim() + "] from " + name);
                    Flash("وصل كـ HID – ابعت التقرير");
                }
            }
            finally { Marshal.FreeHGlobal(buf); }
        }

        static string DevName(IntPtr dev)
        {
            if (dev == IntPtr.Zero) return "(unknown)";
            uint size = 0;
            GetRawInputDeviceInfo(dev, RIDI_DEVICENAME, null, ref size);
            if (size == 0) return "(unknown)";
            var sb = new StringBuilder((int)size + 1);
            GetRawInputDeviceInfo(dev, RIDI_DEVICENAME, sb, ref size);
            return sb.ToString();
        }

        void Flash(string s)
        {
            big.Text = "✔ " + s;
            big.ForeColor = Color.FromArgb(11, 138, 76);
        }
    }
}
