// Panbeh desktop: the web app (dist/) in Electron's Chromium, plus a tray, badge, autostart and updates.
const { app, BrowserWindow, Menu, Tray, dialog, ipcMain, nativeImage, net, protocol, screen, shell } = require("electron");
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");

const SCHEME = "app", ORIGIN = "app://panbeh";
// OAuth (MAS) comes back through the same URL scheme as on Android; see NATIVE_REDIRECT in src/matrix.ts
const OAUTH_SCHEME = "ir.panbeh.app";
// `npm run desktop:dev`: the vite server instead of dist/
const DEV_URL = process.env.PANBEH_DEV_URL ?? (process.argv.includes("--dev") ? "http://localhost:5173/" : undefined);
const DIST = path.join(__dirname, "..", "dist");
const RELEASES = "https://github.com/mhfmobin/panbe-matrix-client/releases/latest";
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
  for (const ev of ["show", "hide", "minimize", "restore"]) win.on(ev, () => win.webContents.send("visibility", win.isVisible() && !win.isMinimized()));

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
  if (p.isEditable) items.push({ role: "undo", label: "واگرد" }, { type: "separator" }, { role: "cut", label: "برش" }, { role: "copy", label: "رونوشت" }, { role: "paste", label: "چسباندن" }, { role: "selectAll", label: "انتخاب همه" });
  else if (p.selectionText) items.push({ role: "copy", label: "رونوشت" });
  else if (p.linkURL && /^https?:/.test(p.linkURL)) items.push({ label: "رونوشت پیوند", click: () => require("electron").clipboard.writeText(p.linkURL) });
  if (items.length) Menu.buildFromTemplate(items).popup({ window: win });
}

// ---------- tray and badge ----------

const trayIcon = () => nativeImage.createFromPath(asset(unread ? "tray-unread.png" : "tray.png"));

function createTray() {
  tray = new Tray(trayIcon());
  tray.setToolTip("پنبه");
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: "نمایش پنبه", click: show },
    { type: "separator" },
    { label: "خروج", click: () => { quitting = true; app.quit(); } },
  ]));
  // Windows/Linux: a click shows the window (macOS opens the menu, as menu bar items do)
  tray.on("click", () => (win?.isVisible() && win.isFocused() ? win.hide() : show()));
}

function setBadge(n) {
  unread = Math.max(0, n | 0);
  if (isWin) win?.setOverlayIcon(unread ? nativeImage.createFromPath(asset("badge.png")) : null, unread ? `${unread} گفتگوی خوانده‌نشده` : "");
  else app.setBadgeCount(unread); // macOS dock, Unity/KDE launchers
  tray?.setImage(trayIcon());
  tray?.setToolTip(unread ? `پنبه (${unread})` : "پنبه");
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

function startUpdater() {
  if (!app.isPackaged) return;
  const { autoUpdater } = require("electron-updater");
  autoUpdater.allowPrerelease = false; // only v* releases: rolling main builds are for manual download
  // Squirrel.Mac refuses unsigned updates: there, just point to the download until the app is signed
  autoUpdater.autoDownload = !isMac;
  autoUpdater.on("update-available", async (info) => {
    if (!isMac) return;
    const r = await dialog.showMessageBox({ type: "info", buttons: ["دریافت", "بعداً"], defaultId: 0, cancelId: 1, title: "پنبه", message: `نسخه‌ی تازه‌ی پنبه (${info.version}) آماده است.` });
    if (r.response === 0) shell.openExternal(RELEASES);
  });
  autoUpdater.on("update-downloaded", async (info) => {
    const r = await dialog.showMessageBox({ type: "info", buttons: ["راه‌اندازی دوباره", "بعداً"], defaultId: 0, cancelId: 1, title: "پنبه", message: `نسخه‌ی ${info.version} دریافت شد.`, detail: "با بستن پنبه هم نصب می‌شود." });
    if (r.response === 0) { quitting = true; autoUpdater.quitAndInstall(); }
  });
  autoUpdater.on("error", (e) => console.error("update check failed", e?.message ?? e));
  const check = () => autoUpdater.checkForUpdates().catch(() => {});
  check();
  setInterval(check, 6 * 60 * 60 * 1000);
}

// ---------- app ----------

ipcMain.on("badge", (_e, n) => setBadge(n));
ipcMain.on("focus", show);
ipcMain.on("links-ready", (e) => {
  if (e.sender !== win?.webContents) return;
  linksReady = true;
  for (const l of pendingLinks.splice(0)) win.webContents.send("link", l);
});
ipcMain.on("open-external", (_e, url) => openExternal(String(url)));
ipcMain.handle("autostart", (_e, on) => { if (typeof on === "boolean") setAutostart(on); return getAutostart(); });
ipcMain.handle("info", () => ({ version: app.getVersion(), platform: process.platform, visible: !!win?.isVisible() }));

app.whenReady().then(() => {
  if (isWin) app.setAppUserModelId("ir.panbeh.app"); // notifications and taskbar grouping
  if (!isMac) Menu.setApplicationMenu(null); // macOS keeps the default menu: it carries the copy/paste shortcuts

  protocol.handle(SCHEME, (req) => {
    const { pathname } = new URL(req.url);
    const file = path.normalize(path.join(DIST, decodeURIComponent(pathname)));
    // anything that isn't a built file (or tries to leave dist/) gets the app itself
    const ok = file.startsWith(DIST + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile();
    return net.fetch(pathToFileURL(ok ? file : path.join(DIST, "index.html")).toString());
  });

  // mic for voice messages, notifications; only for the app itself
  const ours = (url) => !!url && (url.startsWith(ORIGIN) || (!!DEV_URL && url.startsWith(DEV_URL)));
  const allowed = new Set(["media", "notifications", "clipboard-sanitized-write", "fullscreen"]);
  const { session } = require("electron");
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(allowed.has(perm) && ours(wc.getURL())));
  session.defaultSession.setPermissionCheckHandler((wc, perm) => allowed.has(perm) && ours(wc?.getURL()));

  createTray();
  if (!handleOAuthUrl(pendingOAuth)) createWindow(startedHidden() && !pendingLink);
  if (pendingLink) deliverLink(pendingLink);
  startUpdater();
});

app.on("activate", show); // macOS: dock icon
app.on("before-quit", () => { quitting = true; });
app.on("window-all-closed", () => { /* stay in the tray */ });
