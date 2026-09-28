// Medica Mall – Besdata scope button test (Windows)
// Checks whether the scope's hardware button reaches Windows as a UVC "still image" trigger.
// Build: GitHub Actions (windows-latest). Needs: .NET Framework 4.8 (built into Windows 10/11).
using System;
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
        readonly ComboBox devBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Top, Font = new Font("Segoe UI", 11) };
        readonly Button startBtn = new Button { Text = "ابدأ الاختبار", Dock = DockStyle.Top, Height = 44, Font = new Font("Segoe UI", 12, FontStyle.Bold), BackColor = Color.FromArgb(13, 111, 204), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        readonly Label big = new Label { Dock = DockStyle.Top, Height = 90, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 20, FontStyle.Bold), ForeColor = Color.FromArgb(10, 63, 125), Text = "اختار المنظار واضغط «ابدأ الاختبار»" };
        readonly PictureBox pic = new PictureBox { Dock = DockStyle.Top, Height = 220, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.Black };
        readonly TextBox log = new TextBox { Dock = DockStyle.Fill, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Font = new Font("Consolas", 9.5f), RightToLeft = RightToLeft.No };

        DsDevice[] devices;
        IFilterGraph2 graph;
        IMediaControl control;
        ISampleGrabber grabber;
        int width, height, presses;
        bool diagOnly, gotFrame;
        readonly string outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Besdata-Button-Test");
        readonly StringBuilder report = new StringBuilder();

        public MainForm()
        {
            Text = "Medica Mall – اختبار زرار منظار Besdata";
            Width = 640; Height = 720; RightToLeft = RightToLeft.Yes; RightToLeftLayout = true; StartPosition = FormStartPosition.CenterScreen;
            Controls.Add(log); Controls.Add(pic); Controls.Add(big); Controls.Add(startBtn); Controls.Add(devBox);
            startBtn.Click += (s, e) => Start();
            FormClosing += (s, e) => Stop();
            Directory.CreateDirectory(outDir);
            Log("Windows: " + Environment.OSVersion + " | 64-bit process: " + Environment.Is64BitProcess);
            devices = DsDevice.GetDevicesOfCat(FilterCategory.VideoInputDevice);
            int pick = -1;
            for (int i = 0; i < devices.Length; i++)
            {
                devBox.Items.Add(devices[i].Name);
                Log("Camera " + i + ": " + devices[i].Name);
                string n = (devices[i].Name ?? "").ToLowerInvariant();
                if (pick < 0 && (n.Contains("besdata") || n.Contains("endoscope") || n.Contains("scope"))) pick = i;
            }
            if (devices.Length == 0) { big.Text = "مفيش كاميرات ظاهرة – وصّل المنظار وافتح البرنامج تاني"; startBtn.Enabled = false; }
            else devBox.SelectedIndex = pick >= 0 ? pick : 0;
        }

        void Log(string s)
        {
            string line = DateTime.Now.ToString("HH:mm:ss") + "  " + s;
            report.AppendLine(line);
            if (log.InvokeRequired) log.BeginInvoke(new Action(() => log.AppendText(line + Environment.NewLine)));
            else log.AppendText(line + Environment.NewLine);
            try { File.WriteAllText(Path.Combine(outDir, "report.txt"), report.ToString()); } catch { }
        }

        void Start()
        {
            Stop();
            var dev = devices[devBox.SelectedIndex];
            Log("---- Testing: " + dev.Name);
            try
            {
                graph = (IFilterGraph2)new FilterGraph();
                var builder = (ICaptureGraphBuilder2)new CaptureGraphBuilder2();
                Check(builder.SetFiltergraph(graph), "SetFiltergraph");
                IBaseFilter cap;
                Check(graph.AddSourceFilterForMoniker(dev.Mon, null, dev.Name, out cap), "AddSourceFilterForMoniker");

                IPin still = DsFindPin.ByCategory(cap, PinCategory.Still, 0);
                Log("Still pin: " + (still != null ? "YES" : "NO"));

                var vc = cap as IAMVideoControl;
                if (vc != null && still != null)
                {
                    VideoControlFlags caps;
                    int hr = vc.GetCaps(still, out caps);
                    Log("Still pin caps: " + caps + " (hr=0x" + hr.ToString("X") + ")");
                    hr = vc.SetMode(still, VideoControlFlags.ExternalTriggerEnable);
                    Log("Enable external trigger: hr=0x" + hr.ToString("X"));
                }
                else Log("IAMVideoControl: " + (vc != null ? "YES" : "NO"));

                grabber = (ISampleGrabber)new SampleGrabber();
                var mt = new AMMediaType { majorType = MediaType.Video, subType = MediaSubType.RGB24, formatType = FormatType.VideoInfo };
                Check(grabber.SetMediaType(mt), "SetMediaType"); DsUtils.FreeAMMediaType(mt);
                var grabFilter = (IBaseFilter)grabber;
                Check(graph.AddFilter(grabFilter, "Grabber"), "AddFilter grabber");
                var nullStill = (IBaseFilter)new NullRenderer();
                Check(graph.AddFilter(nullStill, "NullStill"), "AddFilter null");

                // Still pin (the button) -> grabber. If there is no still pin, listen on the capture pin instead
                // so we at least confirm the camera streams.
                int r = builder.RenderStream(still != null ? PinCategory.Still : PinCategory.Capture, MediaType.Video, cap, grabFilter, nullStill);
                Log("Connect " + (still != null ? "still" : "capture") + " pin -> grabber: hr=0x" + r.ToString("X"));

                // keep the live stream running (many cameras only trigger while streaming)
                if (still != null)
                {
                    var nullPrev = (IBaseFilter)new NullRenderer();
                    graph.AddFilter(nullPrev, "NullPreview");
                    r = builder.RenderStream(PinCategory.Preview, MediaType.Video, cap, null, nullPrev);
                    if (r < 0) r = builder.RenderStream(PinCategory.Capture, MediaType.Video, cap, null, nullPrev);
                    Log("Connect live stream: hr=0x" + r.ToString("X"));
                }

                var media = new AMMediaType();
                if (grabber.GetConnectedMediaType(media) >= 0 && media.formatPtr != IntPtr.Zero)
                {
                    var vih = (VideoInfoHeader)Marshal.PtrToStructure(media.formatPtr, typeof(VideoInfoHeader));
                    width = vih.BmiHeader.Width; height = vih.BmiHeader.Height;
                    Log("Frame size: " + width + "x" + height);
                }
                DsUtils.FreeAMMediaType(media);

                grabber.SetBufferSamples(false);
                grabber.SetOneShot(false);
                grabber.SetCallback(this, 1);
                presses = 0; diagOnly = still == null; gotFrame = false;
                control = (IMediaControl)graph;
                Check(control.Run(), "Run");
                big.Text = still != null ? "دوس زرار المنظار دلوقتي…" : "الكاميرا دي مفيهاش Still pin – الزرار غالبًا مش هيتقري";
                big.ForeColor = Color.FromArgb(10, 63, 125);
                Log(still != null ? "READY – press the scope button" : "NO STILL PIN – capture pin connected for diagnostics only");
            }
            catch (Exception ex)
            {
                Log("ERROR: " + ex.Message);
                big.Text = "حصلت مشكلة – اقفل أي برنامج تاني بيستخدم الكاميرا وجرّب تاني";
                big.ForeColor = Color.FromArgb(198, 40, 40);
            }
        }

        static void Check(int hr, string what)
        {
            if (hr < 0) throw new Exception(what + " failed: 0x" + hr.ToString("X"));
        }

        void Stop()
        {
            try { control?.Stop(); } catch { }
            if (graph != null) { try { Marshal.ReleaseComObject(graph); } catch { } }
            graph = null; control = null; grabber = null;
        }

        public int SampleCB(double sampleTime, IMediaSample pSample) { return 0; }

        public int BufferCB(double sampleTime, IntPtr buffer, int len)
        {
            if (diagOnly) { if (!gotFrame) { gotFrame = true; Log("Camera is streaming (capture pin) – but no still pin for the button"); } return 0; }
            presses++;
            int n = presses;
            Bitmap bmp = null;
            try
            {
                if (width > 0 && height > 0 && len >= width * height * 3)
                {
                    int stride = ((width * 3) + 3) & ~3;
                    using (var tmp = new Bitmap(width, height, stride, PixelFormat.Format24bppRgb, buffer))
                    {
                        bmp = (Bitmap)tmp.Clone();
                    }
                    bmp.RotateFlip(RotateFlipType.RotateNoneFlipY);
                    string file = Path.Combine(outDir, "button-" + n + ".jpg");
                    bmp.Save(file, ImageFormat.Jpeg);
                    Log("✔ BUTTON #" + n + " – still frame " + width + "x" + height + " saved: " + file);
                }
                else Log("✔ BUTTON #" + n + " – frame received (" + len + " bytes)");
            }
            catch (Exception ex) { Log("frame error: " + ex.Message); }
            var shown = bmp;
            BeginInvoke(new Action(() =>
            {
                big.Text = "✔ الزرار اتقرا! (" + n + ")";
                big.ForeColor = Color.FromArgb(11, 138, 76);
                if (shown != null) { var old = pic.Image; pic.Image = shown; old?.Dispose(); }
            }));
            return 0;
        }
    }
}
