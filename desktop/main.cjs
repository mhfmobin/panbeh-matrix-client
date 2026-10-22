// Panbeh desktop: the web app (dist/) in Electron's Chromium, plus a tray, badge, autostart and updates.
const { app, BrowserWindow, Menu, Tray, ipcMain, nativeImage, net, powerMonitor, protocol, screen, shell } = require("electron");
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");

const SCHEME = "app", ORIGIN = "app://panbeh";
// OAuth (MAS) comes back through the same URL scheme as on Android; see NATIVE_REDIRECT in src/matrix.ts
const OAUTH_SCHEME = "ir.panbeh.app";
// `npm run desktop:dev`: the vite server instead of dist/
const DEV_URL = process.env.PANBEH_DEV_URL ?? (process.argv.includes("--dev") ? "http://localhost:5173/" : undefined);
const DIST = path.join(__dirname, "..", "dist");
// index.html's policy without its inline scripts (only Capacitor's bridge and Vite's dev server need them), F-06, csp.test.ts
const CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data: https://cdn.jsdelivr.net https://tile.openstreetmap.org; media-src 'self' blob: data:; font-src 'self' data:; connect-src 'self' blob: data: https: wss: http: ws:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const RELEASES = "https://github.com/mhfmobin/panbeh-matrix-client/releases/latest";
const isMac = process.platform === "darwin", isWin = process.platform === "win32";

// a stable, secure origin: IndexedDB, WebCrypto and the crypto .wasm behave as on https (file:// doesn't)
protocol.registerSchemesAsPrivileged([{ scheme: SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, codeCache: true } }]);

// one client per profile: two would fight over the same IndexedDB stores
if (!app.requestSingleInstanceLock()) app.exit(0);

if (process.defaultApp) app.setAsDefaultProtocolClient(OAUTH_SCHEME, process.execPath, [path.resolve(process.argv[1])]);
else app.setAsDefaultProtocolClient(OAUTH_SCHEME);
// matrix: URIs (and matrix.to clicks inside the app) open in Panbeh; see src/uri.ts
if (process.defaultApp) app.setAsDefaultProtocolClient("matrix", process.execPath, [path.resolve(process.argv[1])]);
else app.setAsDefaultProtocolClient("matrix");

/** @type {BrowserWindow | null} */ let win = null;
/** @type {Tray | null} */ let tray = null;
let quitting = false, unread = 0;
const asset = (f) => path.join(__dirname, f);
const startUrl = (search = "") => (DEV_URL ?? ORIGIN + "/") + search;

// ---------- window ----------

const stateFile = () => path.join(app.getPath("userData"), "window.json");
function loadBounds() {
  try {
    const b = JSON.parse(fs.readFileSync(stateFile(), "utf8"));
    // forget a position on a monitor that's gone
    const onScreen = screen.getAllDisplays().some(({ workArea: a }) => b.x >= a.x - 50 && b.y >= a.y - 50 && b.x < a.x + a.width && b.y < a.y + a.height);
    return onScreen ? b : { width: b.width, height: b.height, maximized: b.maximized };
  } catch { return { width: 1100, height: 760 }; }
}
function saveBounds() {
  if (!win || win.isDestroyed()) return;
  try { fs.writeFileSync(stateFile(), JSON.stringify({ ...win.getNormalBounds(), maximized: win.isMaximized() })); } catch { /* not worth failing over */ }
}

function createWindow(hidden) {
  const b = loadBounds();
  win = new BrowserWindow({
    x: b.x, y: b.y, width: b.width, height: b.height, minWidth: 360, minHeight: 480,
    show: false, title: "Panbeh", backgroundColor: "#ffffff", autoHideMenuBar: true,
    icon: isWin || isMac ? undefined : asset("../build/icon.png"),
    webPreferences: {
      preload: asset("preload.cjs"), contextIsolation: true, nodeIntegration: false, sandbox: true,
      // keep syncing at full speed while hidden in the tray, or messages and notifications lag
      backgroundThrottling: false,
    },
  });
  if (b.maximized) win.maximize();
  win.once("ready-to-show", () => { if (!hidden) win.show(); });
  win.on("page-title-updated", (e) => e.preventDefault()); // stays "Panbeh"; the unread count is on the tray and badge

  // closing only hides: the client keeps syncing and notifying from the tray
  win.on("close", (e) => {
    saveBounds();
    if (quitting) return;
    e.preventDefault();
    win.hide();
  });
  win.on("closed", () => { win = null; });
  for (const ev of ["show", "hide", "minimize", "restore"]) win.on(ev, sendVisibility);

  // the app's own links open in the system browser; the window only ever shows the app
  win.webContents.setWindowOpenHandler(({ url }) => { openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => {
    if (!url.startsWith(startUrl())) { e.preventDefault(); openExternal(url); }
  });
  win.webContents.on("did-start-loading", () => { linksReady = false; });
  win.webContents.on("context-menu", (_e, p) => editMenu(p));
  win.loadURL(startUrl());
}

function show() {
  if (!win) return createWindow(false);
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function openExternal(url) {
  if (isMatrixLink(url)) return deliverLink(url);
  if (/^https?:|^mailto:/i.test(url)) shell.openExternal(url);
}

// ---------- matrix.to / matrix: links ----------

const isMatrixLink = (url) => /^matrix:/i.test(url) || /^https:\/\/matrix\.to\/#\//i.test(url);
const linkArg = (argv) => argv.find((a) => /^matrix:/i.test(a));
/** @type {string[]} links that arrived before the page registered its listener */
const pendingLinks = [];
let linksReady = false;

/** Hands a link to the page (which resolves and opens it) and brings the window forward. */
function deliverLink(url) {
  if (!win) createWindow(false);
  else show();
  if (linksReady) win.webContents.send("link", url);
  else pendingLinks.push(url);
}

/** Right-click in text fields and on selections: Chromium shows nothing by default. */
function editMenu(p) {
  const items = [];
  const s = tr();
  if (p.isEditable) items.push({ role: "undo", label: s.undo }, { type: "separator" }, { role: "cut", label: s.cut }, { role: "copy", label: s.copy }, { role: "paste", label: s.paste }, { role: "selectAll", label: s.selectAll });
  else if (p.selectionText) items.push({ role: "copy", label: s.copy });
  else if (p.linkURL && /^https?:/.test(p.linkURL)) items.push({ label: s.copyLink, click: () => require("electron").clipboard.writeText(p.linkURL) });
  if (items.length) Menu.buildFromTemplate(items).popup({ window: win });
}

// ---------- language: the page's choice, sent on every start; remembered so the tray has it before the page loads ----------

const T = {
  fa: { undo: "واگرد", cut: "برش", copy: "رونوشت", paste: "چسباندن", selectAll: "انتخاب همه", copyLink: "رونوشت پیوند", name: "پنبه", show: "نمایش پنبه", quit: "خروج", unread: (n) => `${n} گفتگوی خوانده‌نشده` },
  en: { undo: "Undo", cut: "Cut", copy: "Copy", paste: "Paste", selectAll: "Select all", copyLink: "Copy link", name: "Panbeh", show: "Show Panbeh", quit: "Quit", unread: (n) => `${n} unread chats` },
};
const langFile = () => path.join(app.getPath("userData"), "lang");
let lang = null; // read once the app is ready (getLocale needs it)
const tr = () => T[lang ??= (() => {
  try { const l = fs.readFileSync(langFile(), "utf8").trim(); if (T[l]) return l; } catch { /* first start */ }
  return app.getLocale().startsWith("fa") ? "fa" : "en";
})()];

// ---------- tray and badge ----------

const trayIcon = () => nativeImage.createFromPath(asset(unread ? "tray-unread.png" : "tray.png"));

function createTray() {
  tray = new Tray(trayIcon());
  tray.setToolTip(tr().name);
  tray.setContextMenu(trayMenu());
  // Windows/Linux: a click shows the window (macOS opens the menu, as menu bar items do)
  tray.on("click", () => (win?.isVisible() && win.isFocused() ? win.hide() : show()));
}

const trayMenu = () => Menu.buildFromTemplate([
  { label: tr().show, click: show },
  { type: "separator" },
  { label: tr().quit, click: () => { quitting = true; app.quit(); } },
]);

function setBadge(n) {
  unread = Math.max(0, n | 0);
  if (isWin) win?.setOverlayIcon(unread ? nativeImage.createFromPath(asset("badge.png")) : null, unread ? tr().unread(unread) : "");
  else app.setBadgeCount(unread); // macOS dock, Unity/KDE launchers
  tray?.setImage(trayIcon());
  tray?.setToolTip(unread ? `${tr().name} (${unread})` : tr().name);
}

// ---------- start on login ----------

const autostartFile = () => path.join(app.getPath("appData"), "autostart", "panbeh.desktop");
function getAutostart() {
  if (process.platform === "linux") return fs.existsSync(autostartFile());
  return app.getLoginItemSettings({ args: ["--hidden"] }).openAtLogin;
}
function setAutostart(on) {
  if (process.platform !== "linux") return app.setLoginItemSettings({ openAtLogin: on, args: ["--hidden"] });
  // Linux has no login-item API: an XDG autostart entry. An AppImage's own path changes on update, so use $APPIMAGE.
  if (!on) return fs.rmSync(autostartFile(), { force: true });
  const exe = process.env.APPIMAGE || process.execPath;
  fs.mkdirSync(path.dirname(autostartFile()), { recursive: true });
  fs.writeFileSync(autostartFile(), `[Desktop Entry]\nType=Application\nName=Panbeh\nExec="${exe}" --hidden\nIcon=panbeh\nX-GNOME-Autostart-enabled=true\n`);
}
const startedHidden = () => process.argv.includes("--hidden") || (isMac && app.getLoginItemSettings().wasOpenedAtLogin);

// ---------- OAuth callback ----------

/** ir.panbeh.app:/oauth?code=…&state=… → reload the app with that query, where src/matrix.ts finishes the login. */
function handleOAuthUrl(url) {
  if (!url?.startsWith(OAUTH_SCHEME + ":")) return false;
  const q = url.indexOf("?");
  if (!win) createWindow(false);
  win.loadURL(startUrl(q < 0 ? "" : url.slice(q)));
  show();
  return true;
}
const oauthArg = (argv) => argv.find((a) => a.startsWith(OAUTH_SCHEME + ":"));
// a cold start through the link: macOS hands it over before "ready", Windows/Linux in argv
let pendingOAuth = oauthArg(process.argv);

let pendingLink = linkArg(process.argv);

app.on("second-instance", (_e, argv) => {
  const link = linkArg(argv);
  if (link) deliverLink(link);
  else if (!handleOAuthUrl(oauthArg(argv))) show();
});
app.on("open-url", (e, url) => {
  e.preventDefault();
  if (isMatrixLink(url)) { if (app.isReady()) deliverLink(url); else pendingLink = url; }
  else if (app.isReady()) handleOAuthUrl(url);
  else pendingOAuth = url;
});

// ---------- updates ----------

// The page shows the state (a banner, Settings); with auto-update off it only reports a new version until asked to download.
const updatesFile = () => path.join(app.getPath("userData"), "updates.json");
const autoUpdate = () => { try { return JSON.parse(fs.readFileSync(updatesFile(), "utf8")).auto !== false; } catch { return true; } };
/** @type {{ status: "idle" | "checking" | "none" | "available" | "downloading" | "ready" | "error", version?: string, percent?: number }} */
let update = { status: "idle" };
const isDev = app.getVersion().includes("-"); // a dev build (Actions → Run workflow) only offers the release, never downloads it unasked
const setUpdate = (u) => { update = u; win?.webContents.send("update", update); };
/** @type {import("electron-updater").AppUpdater | null} */ let updater = null;

function startUpdater() {
  if (!app.isPackaged) return;
  const { autoUpdater } = require("electron-updater");
  updater = autoUpdater;
  autoUpdater.allowPrerelease = false; // only v* releases; a dev build (0.1.0-N) is older than all of them, so it's offered the latest
  // Squirrel.Mac refuses unsigned updates: there, "download" points to the release page until the app is signed
  autoUpdater.autoDownload = autoUpdate() && !isMac && !isDev;
  // a version already found stays on show while checking again
  autoUpdater.on("checking-for-update", () => { if (!update.version) setUpdate({ status: "checking" }); });
  autoUpdater.on("update-not-available", () => setUpdate({ status: "none" }));
  autoUpdater.on("update-available", (info) => setUpdate({ status: autoUpdater.autoDownload ? "downloading" : "available", version: info.version, percent: 0 }));
  autoUpdater.on("download-progress", (p) => setUpdate({ ...update, status: "downloading", percent: Math.floor(p.percent) }));
  autoUpdater.on("update-downloaded", (info) => setUpdate({ status: "ready", version: info.version }));
  autoUpdater.on("error", (e) => {
    console.error("update failed", e?.message ?? e);
    // a failed download can be tried again; a failed check keeps a version already found
    setUpdate(update.status === "downloading" ? { ...update, status: "available" } : update.version ? update : { status: "error" });
  });
  const check = () => { if (update.status !== "downloading" && update.status !== "ready") autoUpdater.checkForUpdates().catch(() => {}); };
  check();
  setInterval(check, 6 * 60 * 60 * 1000);
}

function downloadUpdate() {
  if (isMac) return shell.openExternal(RELEASES);
  if (update.status !== "available") return;
  setUpdate({ ...update, status: "downloading", percent: 0 });
  updater?.downloadUpdate().catch(() => {}); // failures arrive as "error"
}

ipcMain.handle("update-state", () => update);
ipcMain.handle("update-auto", (_e, on) => {
  if (isDev) return undefined;
  if (typeof on === "boolean") {
    try { fs.writeFileSync(updatesFile(), JSON.stringify({ auto: on })); } catch { /* stays as it was */ }
    if (updater) updater.autoDownload = on && !isMac;
    if (on && !isMac) downloadUpdate();
  }
  return autoUpdate();
});
ipcMain.handle("update-check", () => { if (updater && update.status !== "downloading" && update.status !== "ready") updater.checkForUpdates().catch(() => {}); });
ipcMain.handle("update-download", () => downloadUpdate());
// quitAndInstall is sync (Linux: pkexec waits for the password); a failure only arrives as "error" and the app stays open,
// so hand it to the page instead of doing nothing
ipcMain.handle("update-install", () => {
  if (update.status !== "ready" || !updater) return;
  let failed;
  const onError = (e) => { failed = String(e?.message ?? e); };
  updater.once("error", onError);
  quitting = true;
  updater.quitAndInstall();
  updater.off("error", onError);
  if (failed) quitting = false; // still running: closing the window hides it again
  return failed;
});

// ---------- app ----------

/** Asks the page which screen or window to share; resolves to its id, or null if cancelled. */
let picks = 0;
function pickSource(frame, sources) {
  const n = ++picks;
  return new Promise((resolve) => {
    const h = (_e, m, id) => { if (m !== n) return; ipcMain.off("picked-source", h); resolve(typeof id === "string" ? id : null); };
    ipcMain.on("picked-source", h);
    frame.send("pick-source", n, sources.map((s) => ({ id: s.id, name: s.name, thumb: s.thumbnail.toDataURL() })));
  });
}

ipcMain.on("badge", (_e, n) => setBadge(n));
ipcMain.on("lang", (_e, l) => {
  if (!T[l]) return;
  fs.writeFile(langFile(), l, () => {});
  if (tr() === T[l]) return;
  lang = l;
  tray?.setContextMenu(trayMenu());
  setBadge(unread); // the tooltips
});
ipcMain.on("focus", show);
ipcMain.on("links-ready", (e) => {
  if (e.sender !== win?.webContents) return;
  linksReady = true;
  for (const l of pendingLinks.splice(0)) win.webContents.send("link", l);
});
ipcMain.on("open-external", (_e, url) => openExternal(String(url)));
ipcMain.handle("autostart", (_e, on) => { if (typeof on === "boolean") setAutostart(on); return getAutostart(); });
ipcMain.handle("info", () => ({ version: app.getVersion(), platform: process.platform, visible: windowSeen() }));

// files saved from chats: the page asks where its blob: download landed, so the next click opens that copy instead
const downloadsFile = () => path.join(app.getPath("userData"), "downloads.json");
/** @type {Set<string> | null} */ let savedPaths = null; // only paths saved here can be opened from the page
const saved = () => savedPaths ??= new Set((() => { try { return JSON.parse(fs.readFileSync(downloadsFile(), "utf8")); } catch { return []; } })());
const finished = new Map(), waiting = new Map(); // blob URL → its path (null: cancelled) / the page waiting for it
const onDownload = (_e, item) => {
  const url = item.getURL();
  item.once("done", (_e, state) => {
    const p = state === "completed" ? item.getSavePath() : null;
    // ponytail: never pruned; one path per saved file
    if (p) { saved().add(p); fs.writeFile(downloadsFile(), JSON.stringify([...saved()]), () => {}); }
    const w = waiting.get(url);
    if (w) { waiting.delete(url); w(p); } else finished.set(url, p);
  });
};
ipcMain.handle("download-path", (_e, url) => {
  if (!finished.has(url)) return new Promise((res) => waiting.set(url, res));
  const p = finished.get(url);
  finished.delete(url);
  return p;
});
// ponytail: an extension list; programs and scripts show in their folder rather than run on a click
const RISKY = /\.(exe|msi|bat|cmd|com|scr|pif|ps1|vbs|jse?|wsf|hta|lnk|jar|sh|run|bin|appimage|desktop|deb|rpm|dmg|pkg|app|command)$/i;
ipcMain.handle("open-download", async (_e, p) => {
  if (!saved().has(p) || !fs.existsSync(p)) return false;
  if (RISKY.test(p) || await shell.openPath(p)) shell.showItemInFolder(p); // openPath resolves to an error message, "" when it worked
  return true;
});

// the page can't tell (no throttling = always "visible"): it marks messages read only while the window can be seen
let screenLocked = false;
const windowSeen = () => !!win && win.isVisible() && !win.isMinimized() && !screenLocked;
const sendVisibility = () => win?.webContents.send("visibility", windowSeen());

app.whenReady().then(() => {
  powerMonitor.on("lock-screen", () => { screenLocked = true; sendVisibility(); });
  powerMonitor.on("unlock-screen", () => { screenLocked = false; sendVisibility(); });
  if (isWin) app.setAppUserModelId("ir.panbeh.app"); // notifications and taskbar grouping
  if (!isMac) Menu.setApplicationMenu(null); // macOS keeps the default menu: it carries the copy/paste shortcuts

  protocol.handle(SCHEME, (req) => {
    const { pathname } = new URL(req.url);
    const file = path.normalize(path.join(DIST, decodeURIComponent(pathname)));
    // anything that isn't a built file (or tries to leave dist/) gets the app itself
    const ok = file.startsWith(DIST + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile();
    const target = ok ? file : path.join(DIST, "index.html");
    if (!target.endsWith(".html")) return net.fetch(pathToFileURL(target).toString());
    return net.fetch(pathToFileURL(target).toString()).then((res) => {
      const headers = new Headers(res.headers);
      headers.set("Content-Security-Policy", CSP);
      return new Response(res.body, { status: res.status, headers });
    });
  });

  // mic/camera for voice messages and calls, screen sharing, notifications, location; only for the app itself
  const ours = (url) => !!url && (url.startsWith(ORIGIN) || (!!DEV_URL && url.startsWith(DEV_URL)));
  const allowed = new Set(["media", "display-capture", "notifications", "clipboard-sanitized-write", "fullscreen", "geolocation"]);
  const { session, desktopCapturer } = require("electron");
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(allowed.has(perm) && ours(wc.getURL())));
  session.defaultSession.setPermissionCheckHandler((wc, perm) => allowed.has(perm) && ours(wc?.getURL()));
  session.defaultSession.on("will-download", onDownload);
  // OpenStreetMap's tile servers answer "Access blocked" without a Referer (osm.wiki/Blocked), and app:// pages send none
  session.defaultSession.webRequest.onBeforeSendHeaders({ urls: ["https://tile.openstreetmap.org/*"] }, (d, cb) =>
    cb({ requestHeaders: { ...d.requestHeaders, Referer: require("../package.json").homepage + "/" } }));
  // getDisplayMedia: macOS 15+ shows the system's picker (useSystemPicker) and Wayland's portal picks inside getSources;
  // elsewhere (Windows, X11, older macOS) the page shows our own picker of screens and windows
  const wayland = process.platform === "linux" && process.env.XDG_SESSION_TYPE === "wayland";
  session.defaultSession.setDisplayMediaRequestHandler(async (req, cb) => {
    try {
      if (!ours(req.frame?.url)) return cb({});
      const sources = await desktopCapturer.getSources({ types: ["screen", "window"], thumbnailSize: wayland ? { width: 0, height: 0 } : { width: 320, height: 180 } });
      let source = sources[0];
      if (!wayland && sources.length > 1) {
        const id = await pickSource(req.frame, sources);
        source = sources.find((s) => s.id === id);
      }
      if (!source) return cb({});
      // Windows can add what the computer is playing
      cb({ video: source, ...(req.audioRequested && process.platform === "win32" ? { audio: "loopback" } : {}) });
    } catch {
      cb({});
    }
  }, { useSystemPicker: true });

  createTray();
  if (!handleOAuthUrl(pendingOAuth)) createWindow(startedHidden() && !pendingLink);
  if (pendingLink) deliverLink(pendingLink);
  startUpdater();
});

app.on("activate", show); // macOS: dock icon
app.on("before-quit", () => { quitting = true; });
app.on("window-all-closed", () => { /* stay in the tray */ });
