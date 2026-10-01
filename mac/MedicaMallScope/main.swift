// Medica Mall Scope – Mac TEST build
// Same exam screen as medicamall.com/ent-scope inside WKWebView. macOS reads the scope as a normal UVC camera.
// This test build also logs every USB / HID device and button event, to find out whether the scope's own
// button reaches macOS (and lets the doctor "learn" any USB button / foot pedal as the scope button).
import Cocoa
import WebKit
import AVFoundation
import IOKit
import IOKit.hid

let APP_HOST = URL(string: "https://scope.medicamall.local/")!
let SITE = "https://medicamall.com/ent-scope"

func isScopeName(_ s: String) -> Bool {
    let l = s.lowercased()
    return l.contains("besdata") || l.contains("endoscope")
}

// MARK: - log (~/Library/Logs/MedicaMallScope.log)
enum Log {
    static let url: URL = {
        let d = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent("MedicaMallScope.log")
    }()
    static var lines: [String] = []
    static var onLine: ((String) -> Void)?
    static let fmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "HH:mm:ss.SSS"; return f }()
    static func w(_ s: String) {
        let line = fmt.string(from: Date()) + "  " + s
        DispatchQueue.main.async {
            lines.append(line)
            if lines.count > 3000 { lines.removeFirst(1000) }
            onLine?(line)
            if let data = (line + "\n").data(using: .utf8) {
                if let h = try? FileHandle(forWritingTo: url) { h.seekToEndOfFile(); h.write(data); try? h.close() }
                else { try? data.write(to: url) }
            }
        }
    }
}

// MARK: - exam screen (latest from the site, cached; bundled copy as fallback)
enum WebStore {
    static let file: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MedicaMallScope", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent("index.html")
    }()
    static func current() -> String? {
        if let s = try? String(contentsOf: file, encoding: .utf8), s.contains("btnSnap") { return s }
        if let u = Bundle.main.url(forResource: "index", withExtension: "html"), let s = try? String(contentsOf: u, encoding: .utf8) { return s }
        return nil
    }
    /// calls back on the main thread with true when a newer exam screen was saved
    static func update(_ done: @escaping (Bool) -> Void) {
        var req = URLRequest(url: URL(string: SITE + "?v=\(Int(Date().timeIntervalSince1970))")!)
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.timeoutInterval = 60
        req.setValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: req) { data, _, err in
            var changed = false
            defer { DispatchQueue.main.async { done(changed) } }
            guard let data = data, let page = String(data: data, encoding: .utf8) else { Log.w("web update: \(err?.localizedDescription ?? "no data")"); return }
            guard let r = page.range(of: "var B64 = \"") else { Log.w("web update: B64 not found (\(data.count) bytes)"); return }
            let rest = page[r.upperBound...]
            guard let end = rest.firstIndex(of: "\"") else { return }
            guard let raw = Data(base64Encoded: String(rest[..<end])), let html = String(data: raw, encoding: .utf8),
                  html.contains("btnSnap"), html.contains("</html>") else { Log.w("web update: bad payload"); return }
            if html == current() { Log.w("web update: already latest"); return }
            do { try html.write(to: file, atomically: true, encoding: .utf8); changed = true; Log.w("web update: saved new exam screen") }
            catch { Log.w("web update: save failed \(error)") }
        }.resume()
    }
}

// MARK: - HID monitor (every button of every external USB/Bluetooth HID device)
final class HIDMonitor {
    let mgr: IOHIDManager
    var scopeVIDs = Set<Int>()
    var learning = false
    var onPress: ((String, Bool) -> Void)?   // (key, comesFromScope)
    private var last = [String: Int]()

    init() { mgr = IOHIDManagerCreate(kCFAllocatorDefault, IOOptionBits(kIOHIDOptionsTypeNone)) }

    static func prop(_ d: IOHIDDevice, _ k: String) -> Any? { IOHIDDeviceGetProperty(d, k as CFString) }
    static func int(_ d: IOHIDDevice, _ k: String) -> Int { (prop(d, k) as? NSNumber)?.intValue ?? 0 }
    static func name(_ d: IOHIDDevice) -> String { (prop(d, kIOHIDProductKey) as? String) ?? "?" }
    static func builtIn(_ d: IOHIDDevice) -> Bool { (prop(d, kIOHIDBuiltInKey) as? NSNumber)?.boolValue ?? false }
    static func describe(_ d: IOHIDDevice) -> String {
        let tr = (prop(d, kIOHIDTransportKey) as? String) ?? "?"
        return "\"\(name(d))\" vid=\(hex(int(d, kIOHIDVendorIDKey))) pid=\(hex(int(d, kIOHIDProductIDKey))) transport=\(tr) usage=\(hex(int(d, kIOHIDPrimaryUsagePageKey))):\(hex(int(d, kIOHIDPrimaryUsageKey)))\(builtIn(d) ? " built-in" : "")"
    }

    func start() {
        let access = IOHIDCheckAccess(kIOHIDRequestTypeListenEvent)
        Log.w("HID input monitoring: " + (access == kIOHIDAccessTypeGranted ? "granted" : access == kIOHIDAccessTypeDenied ? "DENIED" : "not asked yet"))
        if access != kIOHIDAccessTypeGranted { _ = IOHIDRequestAccess(kIOHIDRequestTypeListenEvent) }

        IOHIDManagerSetDeviceMatching(mgr, nil)
        let ctx = Unmanaged.passUnretained(self).toOpaque()
        IOHIDManagerRegisterDeviceMatchingCallback(mgr, { ctx, _, _, dev in
            guard let ctx = ctx else { return }
            Unmanaged<HIDMonitor>.fromOpaque(ctx).takeUnretainedValue().added(dev)
        }, ctx)
        IOHIDManagerRegisterDeviceRemovalCallback(mgr, { _, _, _, dev in
            Log.w("HID removed: " + HIDMonitor.describe(dev))
        }, ctx)
        IOHIDManagerRegisterInputValueCallback(mgr, { ctx, _, _, value in
            guard let ctx = ctx else { return }
            Unmanaged<HIDMonitor>.fromOpaque(ctx).takeUnretainedValue().input(value)
        }, ctx)
        IOHIDManagerScheduleWithRunLoop(mgr, CFRunLoopGetMain(), CFRunLoopMode.defaultMode.rawValue)
        let r = IOHIDManagerOpen(mgr, IOOptionBits(kIOHIDOptionsTypeNone))
        Log.w("HID manager open: " + (r == 0 ? "OK" : "0x" + String(UInt32(bitPattern: r), radix: 16)))
    }

    func added(_ d: IOHIDDevice) {
        let scope = isScopeName(HIDMonitor.name(d)) || scopeVIDs.contains(HIDMonitor.int(d, kIOHIDVendorIDKey))
        Log.w("HID device: " + HIDMonitor.describe(d) + (scope ? "   <-- SCOPE" : ""))
    }

    func input(_ v: IOHIDValue) {
        let el = IOHIDValueGetElement(v)
        let dev = IOHIDElementGetDevice(el)
        if HIDMonitor.builtIn(dev) && !learning { return }
        let page = Int(IOHIDElementGetUsagePage(el)), usage = Int(IOHIDElementGetUsage(el))
        if usage == 0 || usage > 0xFFFF { return }
        if page == 1 && (0x30...0x38).contains(usage) { return }            // mouse / stick movement
        let val = IOHIDValueGetIntegerValue(v)
        let vid = HIDMonitor.int(dev, kIOHIDVendorIDKey), pid = HIDMonitor.int(dev, kIOHIDProductIDKey)
        let key = "\(hex(vid)):\(hex(pid)):\(hex(page)):\(hex(usage))"
        if last[key] == val { return }
        last[key] = val
        let nm = HIDMonitor.name(dev)
        let fromScope = isScopeName(nm) || scopeVIDs.contains(vid)
        // ordinary keyboards: never write which key was typed
        if page == 7 && !fromScope && !learning {
            if val != 0 { Log.w("HID key press from \"\(nm)\"") }
        } else {
            Log.w("HID \"\(nm)\" [\(key)] = \(val)" + (fromScope ? "   <-- SCOPE" : ""))
        }
        if val != 0 { onPress?(key, fromScope) }
    }
}

func hex(_ n: Int) -> String { String(format: "%04X", n) }

// MARK: - USB / camera inventory (for diagnosis)
func ioNum(_ s: io_registry_entry_t, _ k: String) -> Int {
    (IORegistryEntryCreateCFProperty(s, k as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? NSNumber)?.intValue ?? -1
}
func ioStr(_ s: io_registry_entry_t, _ k: String) -> String {
    (IORegistryEntryCreateCFProperty(s, k as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? String) ?? "?"
}
func ioClass(_ s: io_object_t) -> String {
    var buf = [CChar](repeating: 0, count: 128)
    IOObjectGetClass(s, &buf)
    return String(cString: buf)
}

func logUSB() {
    var it: io_iterator_t = 0
    guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("IOUSBHostDevice"), &it) == KERN_SUCCESS else { Log.w("USB: list failed"); return }
    var s = IOIteratorNext(it)
    while s != 0 {
        let name = ioStr(s, "USB Product Name")
        Log.w("USB: \"\(name)\" vid=\(hex(ioNum(s, "idVendor"))) pid=\(hex(ioNum(s, "idProduct")))" + (isScopeName(name) ? "   <-- SCOPE" : ""))
        var ci: io_iterator_t = 0
        if IORegistryEntryGetChildIterator(s, kIOServicePlane, &ci) == KERN_SUCCESS {
            var c = IOIteratorNext(ci)
            while c != 0 {
                let cls = ioNum(c, "bInterfaceClass")
                if cls >= 0 {
                    var drivers: [String] = []
                    var gi: io_iterator_t = 0
                    if IORegistryEntryGetChildIterator(c, kIOServicePlane, &gi) == KERN_SUCCESS {
                        var g = IOIteratorNext(gi)
                        while g != 0 { drivers.append(ioClass(g)); IOObjectRelease(g); g = IOIteratorNext(gi) }
                        IOObjectRelease(gi)
                    }
                    Log.w("      interface #\(ioNum(c, "bInterfaceNumber")) class=\(cls) sub=\(ioNum(c, "bInterfaceSubClass")) driver=[\(drivers.joined(separator: ","))]")
                }
                IOObjectRelease(c)
                c = IOIteratorNext(ci)
            }
            IOObjectRelease(ci)
        }
        IOObjectRelease(s)
        s = IOIteratorNext(it)
    }
    IOObjectRelease(it)
}

/// returns vendor ids of cameras whose name looks like the scope
func logCameras() -> Set<Int> {
    var vids = Set<Int>()
    let ds = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera, .externalUnknown], mediaType: .video, position: .unspecified)
    for d in ds.devices {
        let scope = isScopeName(d.localizedName)
        Log.w("Camera: \"\(d.localizedName)\" model=\(d.modelID)" + (scope ? "   <-- SCOPE" : ""))
        if scope, let r = d.modelID.range(of: "VendorID_") {
            let digits = d.modelID[r.upperBound...].prefix { $0.isNumber }
            if let v = Int(digits) { vids.insert(v) }
        }
    }
    if ds.devices.isEmpty { Log.w("Camera: none found") }
    return vids
}

// MARK: - app
final class AppDelegate: NSObject, NSApplicationDelegate, WKUIDelegate, WKNavigationDelegate, WKScriptMessageHandler, WKDownloadDelegate {
    var window: NSWindow!
    var web: WKWebView!
    var logWindow: NSWindow?
    var logView: NSTextView?
    let hid = HIDMonitor()
    var learnedKey: String? = UserDefaults.standard.string(forKey: "scopeButtonKey")
    var lastPress = Date.distantPast
    var lastDownload: URL?

    func applicationDidFinishLaunching(_ n: Notification) {
        Log.w("==== start \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] ?? "?") macOS \(ProcessInfo.processInfo.operatingSystemVersionString)")
        buildMenu()

        let cfg = WKWebViewConfiguration()
        let ucc = WKUserContentController()
        ucc.add(self, name: "mm")
        if let u = Bundle.main.url(forResource: "mac-shim", withExtension: "js"), let js = try? String(contentsOf: u, encoding: .utf8) {
            ucc.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        } else { Log.w("mac-shim.js missing") }
        cfg.userContentController = ucc
        cfg.mediaTypesRequiringUserActionForPlayback = []
        setPref(cfg.preferences, "developerExtrasEnabled", true)
        setPref(cfg.preferences, "mediaDevicesEnabled", true)
        setPref(cfg.preferences, "mediaCaptureRequiresSecureConnection", false)
        if #available(macOS 12.3, *) { cfg.preferences.isElementFullscreenEnabled = true }

        web = WKWebView(frame: NSRect(x: 0, y: 0, width: 1280, height: 820), configuration: cfg)
        web.uiDelegate = self
        web.navigationDelegate = self
        if #available(macOS 13.3, *) { web.isInspectable = true }

        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1280, height: 820),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Medica Mall – شاشة الكشف (Mac Test)"
        window.contentView = web
        window.setFrameAutosaveName("main")
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        AVCaptureDevice.requestAccess(for: .video) { ok in Log.w("camera permission: " + (ok ? "allowed" : "DENIED")) }

        hid.scopeVIDs = logCameras()
        logUSB()
        hid.onPress = { [weak self] key, fromScope in self?.press(key: key, fromScope: fromScope) }
        hid.start()
        if let k = learnedKey { Log.w("learned button: \(k)") }

        loadApp()
        WebStore.update { [weak self] changed in if changed { self?.native("webupdate") } }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ s: NSApplication) -> Bool { true }

    func setPref(_ p: WKPreferences, _ key: String, _ v: Bool) {
        let setter = "_set" + key.prefix(1).uppercased() + key.dropFirst() + ":"
        if p.responds(to: NSSelectorFromString(setter)) { p.setValue(v, forKey: key) } else { Log.w("pref \(key) not available") }
    }

    func loadApp() {
        if let html = WebStore.current() {
            web.loadHTMLString(html, baseURL: APP_HOST)
        } else {
            Log.w("no exam screen cached/bundled -> loading the live site")
            web.load(URLRequest(url: URL(string: SITE)!))
        }
    }

    func native(_ evt: String) {
        web.evaluateJavaScript("window.__macNative&&window.__macNative(\(jsString(evt)))", completionHandler: nil)
    }
    func jsString(_ s: String) -> String {
        let d = try? JSONSerialization.data(withJSONObject: [s])
        let a = d.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(a.dropFirst().dropLast())
    }

    // ---- scope button
    func press(key: String, fromScope: Bool) {
        if hid.learning {
            hid.learning = false
            learnedKey = key
            UserDefaults.standard.set(key, forKey: "scopeButtonKey")
            Log.w("LEARNED scope button = \(key)")
            native("toast:✅ اتسجل الزرار – جرّب تدوس عليه")
            return
        }
        guard fromScope || key == learnedKey else { return }
        if Date().timeIntervalSince(lastPress) < 0.35 { return }
        lastPress = Date()
        Log.w("scope button (\(key)) -> exam screen")
        native("button")
    }

    @objc func learnButton(_ s: Any?) {
        hid.learning = true
        Log.w("learning: waiting for the next button press…")
        native("toast:دوس على زرار المنظار (أو الدواسة) دلوقتي")
        showLog(nil)
    }
    @objc func forgetButton(_ s: Any?) {
        learnedKey = nil
        UserDefaults.standard.removeObject(forKey: "scopeButtonKey")
        Log.w("learned button cleared")
    }
    @objc func simulatePress(_ s: Any?) { Log.w("simulated press"); native("button") }
    @objc func reloadApp(_ s: Any?) { loadApp() }
    @objc func openLiveSite(_ s: Any?) { Log.w("loading the live site"); web.load(URLRequest(url: URL(string: SITE)!)) }
    @objc func checkWebUpdate(_ s: Any?) {
        WebStore.update { [weak self] changed in
            if changed { self?.loadApp() } else { self?.native("toast:شاشة الكشف آخر نسخة") }
        }
    }
    @objc func rescan(_ s: Any?) {
        hid.scopeVIDs.formUnion(logCameras())
        logUSB()
        showLog(nil)
    }
    @objc func openLogFile(_ s: Any?) { NSWorkspace.shared.activateFileViewerSelecting([Log.url]) }

    @objc func showLog(_ s: Any?) {
        if logWindow == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 760, height: 460),
                             styleMask: [.titled, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
            w.title = "سجل الأجهزة والزراير"
            w.isReleasedWhenClosed = false
            let sv = NSScrollView(frame: w.contentView!.bounds)
            sv.autoresizingMask = [.width, .height]
            sv.hasVerticalScroller = true
            let tv = NSTextView(frame: sv.bounds)
            tv.isEditable = false
            tv.font = NSFont.monospacedSystemFont(ofSize: 11.5, weight: .regular)
            tv.autoresizingMask = [.width]
            tv.string = Log.lines.joined(separator: "\n") + "\n"
            sv.documentView = tv
            w.contentView = sv
            w.center()
            logWindow = w
            logView = tv
            Log.onLine = { [weak self] line in
                guard let tv = self?.logView else { return }
                tv.textStorage?.append(NSAttributedString(string: line + "\n", attributes: [.font: tv.font as Any, .foregroundColor: NSColor.textColor]))
                tv.scrollToEndOfDocument(nil)
            }
        }
        logWindow?.makeKeyAndOrderFront(nil)
        logView?.scrollToEndOfDocument(nil)
    }

    func buildMenu() {
        let main = NSMenu()
        func item(_ t: String, _ a: Selector?, _ k: String, _ m: NSEvent.ModifierFlags = .command, target: AnyObject? = nil) -> NSMenuItem {
            let i = NSMenuItem(title: t, action: a, keyEquivalent: k)
            i.keyEquivalentModifierMask = m
            i.target = target
            return i
        }
        let appItem = NSMenuItem(); let appMenu = NSMenu()
        appMenu.addItem(item("About Medica Mall Scope", #selector(NSApplication.orderFrontStandardAboutPanel(_:)), ""))
        appMenu.addItem(.separator())
        appMenu.addItem(item("Hide", #selector(NSApplication.hide(_:)), "h"))
        appMenu.addItem(item("Quit", #selector(NSApplication.terminate(_:)), "q"))
        appItem.submenu = appMenu; main.addItem(appItem)

        let editItem = NSMenuItem(); let edit = NSMenu(title: "Edit")
        edit.addItem(item("Undo", Selector(("undo:")), "z"))
        edit.addItem(item("Redo", Selector(("redo:")), "z", [.command, .shift]))
        edit.addItem(.separator())
        edit.addItem(item("Cut", #selector(NSText.cut(_:)), "x"))
        edit.addItem(item("Copy", #selector(NSText.copy(_:)), "c"))
        edit.addItem(item("Paste", #selector(NSText.paste(_:)), "v"))
        edit.addItem(item("Select All", #selector(NSText.selectAll(_:)), "a"))
        editItem.submenu = edit; main.addItem(editItem)

        let viewItem = NSMenuItem(); let view = NSMenu(title: "View")
        view.addItem(item("Reload", #selector(reloadApp(_:)), "r", target: self))
        view.addItem(item("Full Screen", #selector(NSWindow.toggleFullScreen(_:)), "f", [.command, .control]))
        viewItem.submenu = view; main.addItem(viewItem)

        let scItem = NSMenuItem(); let sc = NSMenu(title: "Scope")
        sc.addItem(item("سجل الأجهزة والزراير", #selector(showLog(_:)), "l", target: self))
        sc.addItem(item("اتعلّم زرار المنظار / الدواسة", #selector(learnButton(_:)), "b", target: self))
        sc.addItem(item("امسح الزرار المتسجل", #selector(forgetButton(_:)), "", target: self))
        sc.addItem(item("جرّب الزرار (محاكاة)", #selector(simulatePress(_:)), ".", target: self))
        sc.addItem(.separator())
        sc.addItem(item("افحص الأجهزة تاني", #selector(rescan(_:)), "", target: self))
        sc.addItem(item("تحديث شاشة الكشف من الموقع", #selector(checkWebUpdate(_:)), "", target: self))
        sc.addItem(item("افتح نسخة الموقع المباشرة", #selector(openLiveSite(_:)), "", target: self))
        sc.addItem(item("افتح ملف السجل", #selector(openLogFile(_:)), "", target: self))
        scItem.submenu = sc; main.addItem(scItem)

        NSApp.mainMenu = main
    }

    // ---- web: messages from the page
    func userContentController(_ u: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let s = m.body as? String else { return }
        if s == "reloadWeb" { loadApp(); return }
        if s.hasPrefix("log:") { Log.w("web: " + s.dropFirst(4)) }
    }

    // ---- web: camera / dialogs / files
    @available(macOS 12.0, *)
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        Log.w("camera request from \(origin.host) -> allowed")
        decisionHandler(.grant)
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        let a = NSAlert(); a.messageText = message; a.addButton(withTitle: "تمام")
        a.beginSheetModal(for: window) { _ in completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        let a = NSAlert(); a.messageText = message
        a.addButton(withTitle: "تمام"); a.addButton(withTitle: "إلغاء")
        a.beginSheetModal(for: window) { r in completionHandler(r == .alertFirstButtonReturn) }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (String?) -> Void) {
        let a = NSAlert(); a.messageText = prompt
        let f = NSTextField(frame: NSRect(x: 0, y: 0, width: 300, height: 24)); f.stringValue = defaultText ?? ""
        a.accessoryView = f
        a.addButton(withTitle: "تمام"); a.addButton(withTitle: "إلغاء")
        a.beginSheetModal(for: window) { r in completionHandler(r == .alertFirstButtonReturn ? f.stringValue : nil) }
    }

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping ([URL]?) -> Void) {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = parameters.allowsMultipleSelection
        p.canChooseDirectories = false
        p.beginSheetModal(for: window) { r in completionHandler(r == .OK ? p.urls : nil) }
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let u = navigationAction.request.url, let s = u.scheme, s == "http" || s == "https" || s == "mailto" || s == "tel" {
            NSWorkspace.shared.open(u)
        } else {
            Log.w("blocked popup: \(navigationAction.request.url?.absoluteString.prefix(80) ?? "")")
        }
        return nil
    }

    // ---- web: navigation + downloads (backups, report PDF, photos)
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, preferences: WKWebpagePreferences,
                 decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        if navigationAction.shouldPerformDownload { decisionHandler(.download, preferences); return }
        if let u = navigationAction.request.url, let s = u.scheme, navigationAction.targetFrame?.isMainFrame ?? false {
            let ours = u.host == APP_HOST.host || u.absoluteString.hasPrefix(SITE)
            if (s == "http" || s == "https") && !ours && navigationAction.navigationType == .linkActivated {
                NSWorkspace.shared.open(u); decisionHandler(.cancel, preferences); return
            }
            if s == "mailto" || s == "tel" || s == "whatsapp" { NSWorkspace.shared.open(u); decisionHandler(.cancel, preferences); return }
        }
        decisionHandler(.allow, preferences)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        decisionHandler(navigationResponse.canShowMIMEType ? .allow : .download)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) { download.delegate = self }
    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) { download.delegate = self }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { Log.w("page loaded: \(webView.url?.absoluteString ?? "-")") }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { Log.w("page failed: \(error.localizedDescription)") }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { Log.w("page failed: \(error.localizedDescription)") }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { Log.w("web process crashed -> reload"); loadApp() }

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String,
                  completionHandler: @escaping (URL?) -> Void) {
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        var url = dir.appendingPathComponent(suggestedFilename)
        let base = url.deletingPathExtension().lastPathComponent, ext = url.pathExtension
        var i = 1
        while FileManager.default.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent("\(base) (\(i))" + (ext.isEmpty ? "" : ".\(ext)")); i += 1
        }
        lastDownload = url
        Log.w("download -> \(url.path)")
        completionHandler(url)
    }
    func downloadDidFinish(_ download: WKDownload) {
        if let u = lastDownload { NSWorkspace.shared.activateFileViewerSelecting([u]) }
    }
    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        Log.w("download failed: \(error.localizedDescription)")
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
