import { lang, t } from "./i18n.ts";
import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { downloadPath, openDownload, setUpdater, type Update } from "./desktop.ts";
import { applyPrefs, loadPrefs } from "./ui/Settings.tsx";
import { toast } from "./ui/common.tsx";

/** body: the one-line form (older app builds); sender (in groups), text and ts make the conversation-style notification.
 *  mention: to the mentions channel; hidden: mustn't name the chat (no conversation shortcut). */
type Payload = { roomId: string; eventId?: string; title: string; body: string; sender: string; text: string; ts: number; group: boolean; icon?: string; senderIcon?: string;
  sound: boolean; mention?: boolean; hidden?: boolean; openRoom?: string };
/** Reply / mark-as-read / mute pressed on a message notification. */
export type NotifyAction = { action: "reply" | "read" | "mute"; roomId: string; text?: string };
/** interval: minutes between background checks, 0 = real-time. */
type Status = { permission: "granted" | "denied" | "default"; service: boolean; batteryOptimized: boolean; fullScreen: boolean; interval: number };
type CallPayload = { roomId: string; eventId: string; caller: string; video: boolean; timeout: number; icon?: string };
/** "Share with Panbeh" from another app: text and/or content:// files. */
export type Shared = { text?: string; files: { uri: string; name: string; type: string }[] };
/** A button on the incoming-call notification; "open" = the notification itself (full-screen on the lock screen).
 *  hangup / hold / unhold: from Android's Telecom (a headset or car button, a phone call coming in) for the call in progress. */
export type CallAction = { action: "answer" | "decline" | "open" | "hangup" | "hold" | "unhold"; roomId: string; eventId: string; video: boolean };
interface PanbehPlugin {
  showNotification(p: Payload): Promise<void>;
  cancel(p: { roomId: string }): Promise<void>;
  unnotify(p: { roomId: string; eventId: string }): Promise<void>;
  openNotifySettings(): Promise<void>;
  cancelAll(): Promise<void>;
  startService(p?: { interval?: number }): Promise<void>;
  syncState(p: { state: string }): Promise<void>;
  log(p: { msg: string }): Promise<void>;
  stopService(): Promise<void>;
  status(): Promise<Status>;
  requestNotifyPermission(): Promise<{ permission: Status["permission"] }>;
  requestBatteryExemption(): Promise<void>;
  takeLaunchRoom(): Promise<{ roomId?: string | null }>;
  takeLaunchLink(): Promise<{ link?: string | null }>;
  showCall(p: CallPayload): Promise<void>;
  cancelCall(p: { roomId: string }): Promise<void>;
  callActive(p: { on: boolean; video: boolean; roomId?: string; name?: string }): Promise<void>;
  setSpeaker(p: { on: boolean }): Promise<void>;
  audioRoutes(): Promise<Routes>;
  setAudioRoute(p: { id: number }): Promise<void>;
  setPip(p: { on: boolean }): Promise<void>;
  holdIme(p: { on: boolean }): Promise<void>;
  setImmersive(p: { on: boolean }): Promise<void>;
  requestFullScreen(): Promise<void>;
  takeLaunchCall(): Promise<Partial<CallAction>>;
  saveOpen(p: { name: string; mime: string; ask: boolean }): Promise<{ id: string | null; picked?: boolean }>;
  saveChunk(p: { id: string; data: string }): Promise<void>;
  saveClose(p: { id: string }): Promise<{ uri?: string }>; // older app builds resolve nothing
  openFile(p: { uri: string; mime: string }): Promise<void>;
  openGeo(p: { lat: number; lon: number; label?: string }): Promise<void>;
  saveAbort(p: { id: string }): Promise<void>;
  takeLaunchShare(): Promise<Partial<Shared>>;
  pickImages(p: { multiple: boolean }): Promise<{ files: { uri: string; name: string; type: string }[] }>;
  pickCamera(): Promise<{ files: { uri: string; name: string; type: string }[] }>;
  clipboardImage(): Promise<{ files: { uri: string; name: string; type: string }[] }>;
  biometricAvailable(): Promise<{ available: boolean }>;
  biometricUnlock(p: { title: string; cancel: string }): Promise<{ ok: boolean }>;
  metered(): Promise<{ metered: boolean }>;
  setLanguage(p: { lang: string }): Promise<void>;
  fontScale(): Promise<{ scale: number }>;
  updateDownload(p: { url: string }): Promise<void>;
  updateInstall(): Promise<void>;
  bazaarCheck(): Promise<{ newer: boolean }>;
  bazaarAutoUpdate(): Promise<{ on: boolean }>;
  bazaarOpen(p: { page: "details" | "autoupdate" }): Promise<void>;
  addListener(e: "openRoom", f: (d: { roomId: string }) => void): Promise<PluginListenerHandle>;
  addListener(e: "openLink", f: (d: { link: string }) => void): Promise<PluginListenerHandle>;
  addListener(e: "callAction", f: (d: CallAction) => void): Promise<PluginListenerHandle>;
  addListener(e: "notifyAction", f: (d: NotifyAction) => void): Promise<PluginListenerHandle>;
  addListener(e: "share", f: (d: Shared) => void): Promise<PluginListenerHandle>;
  addListener(e: "imeImage", f: (d: Pick<Shared, "files">) => void): Promise<PluginListenerHandle>;
  addListener(e: "audioRoutes", f: (d: Routes) => void): Promise<PluginListenerHandle>;
  addListener(e: "updateProgress", f: (d: { percent: number }) => void): Promise<PluginListenerHandle>;
}
type Headless = { showNotification(json: string): void; cancel(roomId: string): void; unnotify?(roomId: string, eventId: string): void; stopService(): void; showCall(json: string): void; cancelCall(roomId: string): void; syncState(state: string): void };

const headless = (window as unknown as { PanbehAndroid?: Headless }).PanbehAndroid;
const plugin = registerPlugin<PanbehPlugin>("Panbeh");

/** Running inside the Android app (either WebView). */
export const isNative = !!headless || Capacitor.isNativePlatform();
/** The background service's invisible copy of the app: sync and notify only, no UI. */
export const isHeadless = !!headless && new URLSearchParams(location.search).has("headless");
// notifications and their channels in the page's language (older app builds: the phone's)
if (isNative && !isHeadless) plugin.setLanguage({ lang }).catch(() => {});
// the phone's font size scales message text (applyPrefs); cached for the next start's first paint. A change restarts the activity.
const FONT_SCALE = "panbeh.sysFontScale";
export const sysFontScale = () => { try { return localStorage.getItem(FONT_SCALE) ?? 1; } catch { return 1; } };
if (isNative && !isHeadless) plugin.fontScale().then(({ scale }) => {
  if (String(scale) === String(sysFontScale())) return;
  try { localStorage.setItem(FONT_SCALE, String(scale)); } catch { /* no storage */ }
  applyPrefs();
}, () => {}); // older app builds: no method, 1×

export function nativeNotify(p: Payload) {
  if (headless) headless.showNotification(JSON.stringify(p));
  else plugin.showNotification(p).catch(() => {});
}
export function nativeCancel(roomId: string) {
  if (headless) headless.cancel(roomId);
  else plugin.cancel({ roomId }).catch(() => {});
}
/** A deleted message: out of its chat's notification. */
export function nativeUnnotify(roomId: string, eventId: string) {
  if (headless) headless.unnotify?.(roomId, eventId); // optional: an older service build doesn't have it
  else plugin.unnotify({ roomId, eventId }).catch(() => {});
}
/** Android's notification settings for the app (each channel's sound and vibration). */
export const openNotifySettings = () => plugin.openNotifySettings();
export const nativeCancelAll = () => { if (!headless) plugin.cancelAll().catch(() => {}); };
export const nativeStatus = () => plugin.status();
export const requestNotifyPermission = () => plugin.requestNotifyPermission().then((r) => r.permission);
export const requestBatteryExemption = () => plugin.requestBatteryExemption();
export function setBackgroundService(on: boolean) {
  if (headless) { if (!on) headless.stopService(); return; }
  (on ? plugin.startService() : plugin.stopService()).catch(() => {});
}
/** Real-time (0) or a check every N minutes; restarts the service in that mode. */
export const setBackgroundInterval = (minutes: number) => plugin.startService({ interval: minutes });

/** The client's sync state, shown in the background service's notification. */
export function nativeSyncState(state: string) {
  if (headless) headless.syncState(state);
  else plugin.syncState({ state }).catch(() => {});
}
/** A diagnostics line: logcat tag PanbehSync on Android (the headless page's console is forwarded there), else the console. */
export function nativeLog(msg: string) {
  if (Capacitor.isNativePlatform() && !headless) plugin.log({ msg }).catch(() => {});
  else console.info(msg);
}
/** The service asks the page to reconnect now: network back, its watchdog, or an interval check. */
export function onKick(f: (newNetwork: boolean) => void) {
  if (isNative) (window as unknown as { panbehKick?: (n?: boolean) => void }).panbehKick = (n) => f(!!n);
}

/** Notification taps: the room it was for, now (cold start) and later. Returns an unsubscribe. */
export function onOpenRoom(open: (roomId: string) => void) {
  if (!isNative || headless) return () => {};
  plugin.takeLaunchRoom().then((r) => r.roomId && open(r.roomId), () => {});
  const h = plugin.addListener("openRoom", (d) => open(d.roomId));
  return () => { h.then((x) => x.remove()); };
}

/** matrix.to / matrix: links opened with the app (VIEW intents): now (cold start) and later. Returns an unsubscribe. */
export function onOpenLink(open: (link: string) => void) {
  if (!isNative || headless) return () => {};
  plugin.takeLaunchLink().then((r) => r.link && open(r.link), () => {});
  const h = plugin.addListener("openLink", (d) => open(d.link));
  return () => { h.then((x) => x.remove()); };
}

export function nativeShowCall(p: CallPayload) {
  if (headless) headless.showCall(JSON.stringify(p));
  else plugin.showCall(p).catch(() => {});
}
export function nativeCancelCall(roomId: string) {
  if (headless) headless.cancelCall(roomId);
  else plugin.cancelCall({ roomId }).catch(() => {});
}
/** In a call: keeps mic/camera alive in the background (foreground service) and the call on the lock screen. */
export const nativeCallActive = (on: boolean, video: boolean, room?: { roomId: string; name: string }) => {
  if (!headless) plugin.callActive({ on, video, roomId: room?.roomId, name: room?.name }).catch(() => {});
};
export const nativeSpeaker = (on: boolean) => { if (!headless) plugin.setSpeaker({ on }).catch(() => {}); };
/** Where call audio can go (Android 12+; empty before, where it's only the speaker toggle). */
export type AudioRoute = { id: number; kind: "earpiece" | "speaker" | "wired" | "bluetooth"; name: string };
export type Routes = { routes: AudioRoute[]; current: number };
/** Where call audio can go now and whenever that changes (a headset connects, audio moves). Returns an unsubscribe. */
export function watchAudioRoutes(f: (r: Routes) => void) {
  if (headless) return () => {};
  plugin.audioRoutes().then(f, () => {});
  const h = plugin.addListener("audioRoutes", f);
  return () => { h.then((x) => x.remove()); };
}
export const nativeSetAudioRoute = (id: number) => plugin.setAudioRoute({ id });
/** A video call is on screen: leaving the app shrinks it to picture-in-picture. */
export const nativePip = (on: boolean) => { if (!headless) plugin.setPip({ on }).catch(() => {}); };
/** The emoji panel stands in for the keyboard: the page keeps its height until a rising keyboard has covered the panel. */
export const nativeHoldIme = (on: boolean) => { if (isNative && !headless) plugin.holdIme({ on }).catch(() => {}); };
/** A call's video fills the screen: the system bars hide. */
export const nativeImmersive = (on: boolean) => { if (!headless) plugin.setImmersive({ on }).catch(() => {}); };
/** Android 14+: lets the user allow ringing over the lock screen. */
export const requestFullScreen = () => plugin.requestFullScreen();

/** Incoming-call notification buttons: now (cold start) and later. The headless page gets them via window.panbehCallAction. */
export function onCallAction(f: (a: CallAction) => void) {
  if (!isNative) return () => {};
  if (headless) {
    (window as unknown as { panbehCallAction?: unknown }).panbehCallAction = f;
    return () => {};
  }
  plugin.takeLaunchCall().then((a) => a.action && f(a as CallAction), () => {});
  const h = plugin.addListener("callAction", f);
  return () => { h.then((x) => x.remove()); };
}

/** On mobile data (or a metered Wi‑Fi): what the auto-download rules pick between. Synchronous for rendering, so it
 *  answers from a cache the Android check refreshes in the background (older app builds: never metered). */
const metered = { v: false, at: 0 };
const refreshMetered = () => {
  if (Date.now() - metered.at < 10_000) return;
  metered.at = Date.now();
  plugin.metered().then((r) => { metered.v = r.metered; }, () => {});
};
if (isNative && !headless) refreshMetered();
export function isMetered() {
  if (!isNative) return (navigator as { connection?: { type?: string } }).connection?.type === "cellular";
  refreshMetered();
  return metered.v;
}

/** The app lock's fingerprint/face unlock: whether the phone has one set up, and asking for it. */
export const biometricAvailable = () => (isNative && !headless ? plugin.biometricAvailable().then((r) => r.available, () => false) : Promise.resolve(false));
export const biometricUnlock = () => plugin.biometricUnlock({ title: t("native.unlockTitle"), cancel: t("native.unlockPin") }).then((r) => r.ok, () => false);

/** Message notification buttons, in the app's page or (via window.panbehNotifyAction) the headless one. Returns an unsubscribe. */
export function onNotifyAction(f: (a: NotifyAction) => void) {
  if (!isNative) return () => {};
  if (headless) {
    (window as unknown as { panbehNotifyAction?: unknown }).panbehNotifyAction = f;
    return () => {};
  }
  const h = plugin.addListener("notifyAction", f);
  return () => { h.then((x) => x.remove()); };
}

const blobToDataUrl = (b: Blob) => new Promise<string>((res, rej) => {
  const r = new FileReader();
  r.onload = () => res(r.result as string);
  r.onerror = () => rej(r.error);
  r.readAsDataURL(b);
});

/** Blob/object URL → data URL, for handing images to native code. */
export const toDataUrl = async (url: string) => blobToDataUrl(await (await fetch(url)).blob());

/** Where a file is being saved: the browser's download, the phone's Downloads folder, or a place picked in Android's "Save as". */
export type SaveTarget = { name: string; id?: string; where: "browser" | "downloads" | "picked"; uri?: string };
const CHUNK = 512 * 1024; // per bridge call, so a big file never crosses as one giant base64 string

/** Opens a file to save into, before downloading, so "Save as" (if the user wants it asked) comes first. null = they backed out. */
export async function openSave(name: string, mime: string): Promise<SaveTarget | null> {
  if (!isNative) return { name, where: "browser" };
  const r = await plugin.saveOpen({ name, mime: mime || "application/octet-stream", ask: loadPrefs().askSave });
  return r.id ? { name, id: r.id, where: r.picked ? "picked" : "downloads" } : null;
}

/** Writes a blob/object URL into an opened target (WebViews ignore <a download>, so Android gets it in chunks). */
export async function writeSave(t: SaveTarget, url: string, signal?: AbortSignal) {
  if (!t.id) {
    Object.assign(document.createElement("a"), { href: url, download: t.name }).click();
    return;
  }
  try {
    const b = await (await fetch(url)).blob();
    for (let i = 0; i < b.size; i += CHUNK) {
      signal?.throwIfAborted();
      const d = await blobToDataUrl(b.slice(i, i + CHUNK));
      await plugin.saveChunk({ id: t.id, data: d.slice(d.indexOf(",") + 1) });
    }
    t.uri = (await plugin.saveClose({ id: t.id })).uri;
  } catch (e) {
    abortSave(t);
    throw e;
  }
}

/** Drops a half-written file (cancelled or failed). Safe to call twice. */
export function abortSave(t: SaveTarget | null) {
  if (t?.id) plugin.saveAbort({ id: t.id }).catch(() => {});
}

// where files were saved (Android: a content URI; desktop: a path), by mxc URL, so tapping one again opens that copy
// ponytail: never pruned; one short entry per saved file
const SAVED = "panbeh.saved";
const savedMap = (): Record<string, string> => { try { return JSON.parse(localStorage.getItem(SAVED) ?? "{}"); } catch { return {}; } };
function setSaved(key: string, loc?: string) {
  const m = savedMap();
  if (loc) m[key] = loc; else delete m[key];
  try { localStorage.setItem(SAVED, JSON.stringify(m)); } catch { /* full: it just downloads again */ }
}

/** After writeSave: remembers the copy (desktop: once the download finishes; a browser can't open its downloads). */
export function rememberSave(key: string, t: SaveTarget, url: string) {
  if (t.uri) setSaved(key, t.uri);
  else if (t.where === "browser") void downloadPath(url).then((p) => p && setSaved(key, p), () => {});
}

/** A map link's click: on Android the system "open with" chooser (any map app) instead of the website, which stays the
 *  fallback (an app build without openGeo); elsewhere undefined, the link stays a link. */
export const geoClick = (lat: number, lon: number, label?: string) => isNative ? (e: { preventDefault(): void; currentTarget: HTMLAnchorElement }) => {
  e.preventDefault();
  const href = e.currentTarget.href;
  plugin.openGeo({ lat, lon, label }).catch(() => window.open(href, "_blank"));
} : undefined;

/** Opens the saved copy of `key`. false: not saved here, or deleted/moved since (then forgotten, so it downloads again). */
export async function openSaved(key: string, mime: string): Promise<boolean> {
  const loc = savedMap()[key];
  if (!loc) return false;
  try {
    if (isNative) { await plugin.openFile({ uri: loc, mime }); return true; }
    if (await openDownload(loc)) return true;
  } catch (e) {
    if ((e as Error).message === "no app") throw new Error(t("native.noApp"));
    if ((e as Error).message !== "gone") throw e;
  }
  setSaved(key);
  return false;
}

/** Saves something already downloaded (a blob/object URL). null = "Save as" was backed out of. */
export async function saveFile(url: string, name: string): Promise<SaveTarget | null> {
  const t = await openSave(name, isNative ? (await (await fetch(url)).blob()).type : "");
  if (t) await writeSave(t, url);
  return t;
}

/** Things shared to the app (SEND intents): now (cold start) and later. Files are read through Capacitor's content:// bridge. */
export function onShare(f: (text: string, files: File[]) => void) {
  if (!isNative || headless) return () => {};
  const take = async (s: Partial<Shared>) => {
    const files = await Promise.all((s.files ?? []).map(async (x) => {
      const b = await (await fetch(Capacitor.convertFileSrc(x.uri))).blob();
      return new File([b], x.name, { type: x.type || b.type });
    }));
    if (s.text || files.length) f(s.text ?? "", files);
  };
  const fail = (e: unknown) => console.error("share failed", e);
  plugin.takeLaunchShare().then(take).catch(fail);
  const h = plugin.addListener("share", (d) => void take(d).catch(fail));
  return () => { h.then((x) => x.remove()); };
}

/** A photo from native (picked from the gallery or captured by the camera): a content:// or file:// URI read through
 *  Capacitor's bridge, like shared files. */
const uriToFile = async (x: { uri: string; name: string; type: string }, name?: string) => {
  const b = await (await fetch(Capacitor.convertFileSrc(x.uri))).blob();
  return new File([b], name || x.name, { type: x.type || b.type });
};

/** The gallery, opened straight from the picker like a messenger app: the picked photos come back as content:// URIs,
 *  read through Capacitor's bridge like shared files. null = the picker couldn't be opened (the caller falls back). */
export async function pickGalleryPhotos(multiple = true): Promise<File[] | null> {
  if (!isNative || headless) return null;
  try {
    const r = await plugin.pickImages({ multiple });
    return await Promise.all((r.files ?? []).map((x) => uriToFile(x)));
  } catch (e) {
    console.error("gallery pick failed", e);
    return null;
  }
}

/** The camera, opened straight from the app like a messenger: the full-size capture comes back from the cache
 *  (the next capture overwrites it), named like a photo from the gallery. null = the camera couldn't be opened. */
export async function captureCameraPhoto(): Promise<File[] | null> {
  if (!isNative || headless) return null;
  try {
    const r = await plugin.pickCamera();
    const d = new Date(), p = (n: number) => String(n).padStart(2, "0");
    const name = `IMG_${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.jpg`;
    return await Promise.all((r.files ?? []).map((x) => uriToFile(x, name)));
  } catch (e) {
    console.error("camera capture failed", e);
    return null;
  }
}

/** An image copied in another app (a screenshot, "copy image"): read straight off the Android clipboard like a picked
 *  photo, because the WebView carries no image on the paste event. null = not on the Android app; [] = no image there. */
export async function clipboardImages(): Promise<File[] | null> {
  if (!isNative || headless) return null;
  try {
    const r = await plugin.clipboardImage();
    return await Promise.all((r.files ?? []).map((x) => uriToFile(x)));
  } catch (e) {
    console.error("clipboard read failed", e);
    return [];
  }
}

/** An image the keyboard inserted (Gboard's copied-image pill or clipboard screen, a GIF or sticker): see PanbehWebView. */
export function onImeImage(f: (files: File[]) => void) {
  if (!isNative || headless) return () => {};
  const h = plugin.addListener("imeImage", (d) => void Promise.all(d.files.map((x) => uriToFile(x))).then(f).catch((e) => console.error("keyboard image failed", e)));
  return () => { h.then((x) => x.remove()); };
}

// ---------- updates: the newest v* GitHub release's APK, handed to Android's installer (it asks; no silent install) ----------
const RELEASE = "https://api.github.com/repos/mhfmobin/panbeh-matrix-client/releases/latest"; // "latest" skips the rolling pre-release
const version = (import.meta.env.VITE_APP_VERSION ?? "").replace(/^v/, "");
const devRun = /^\d+\.\d+\.\d+-(\d+)$/.exec(version)?.[1]; // a dev build (Actions → Run workflow): this run number is its versionCode
const AUTO_UPDATE = "panbeh.autoUpdate";
const autoUpdate = () => { try { return localStorage.getItem(AUTO_UPDATE) !== "0"; } catch { return true; } };
let upd: Update = { status: "idle" }, apk = "";
const updSubs = new Set<(u: Update) => void>();
const setUpd = (u: Update) => { upd = u; updSubs.forEach((f) => f(u)); };

async function checkUpdate() {
  if (upd.status === "checking" || upd.status === "downloading" || upd.status === "ready") return;
  if (!upd.version) setUpd({ status: "checking" }); // a version already found stays on show
  try {
    const r = await fetch(RELEASE);
    if (!r.ok) throw new Error(`${r.status}`);
    const rel = await r.json() as { tag_name: string; assets: { name: string; browser_download_url: string }[] };
    const v = rel.tag_name.replace(/^v/, "");
    apk = rel.assets.find((a) => a.name.endsWith(".apk"))?.browser_download_url ?? "";
    const newer = devRun ? await releaseRun(rel.tag_name) > +devRun : v.localeCompare(version, undefined, { numeric: true }) > 0;
    if (!apk || !newer) return setUpd({ status: "none" });
    setUpd({ status: "available", version: v });
    if (autoUpdate() && !isMetered() && !devRun) downloadUpdate(); // on mobile data it only says so; a dev build only offers it
  } catch {
    setUpd(upd.version ? { status: "available", version: upd.version } : { status: "error" });
  }
}
/** The run number (so the versionCode) of a release's APK: Android won't install a lower one, so a dev build made after it waits for the next release. */
async function releaseRun(tag: string) {
  const r = await fetch(`https://api.github.com/repos/mhfmobin/panbeh-matrix-client/actions/workflows/android.yml/runs?event=push&status=success&per_page=1&branch=${encodeURIComponent(tag)}`);
  if (!r.ok) throw new Error(`${r.status}`);
  return ((await r.json()) as { workflow_runs: { run_number: number }[] }).workflow_runs[0]?.run_number ?? 0;
}
function downloadUpdate() {
  if (upd.status !== "available") return;
  const v = upd.version;
  setUpd({ status: "downloading", version: v, percent: 0 });
  const h = plugin.addListener("updateProgress", (d) => setUpd({ status: "downloading", version: v, percent: d.percent }));
  plugin.updateDownload({ url: apk })
    .then(() => setUpd({ status: "ready", version: v }), () => { setUpd({ status: "available", version: v }); toast(t("update.downloadFailed")); })
    .finally(() => h.then((x) => x.remove()));
}

// ---------- the Cafe Bazaar build (VITE_STORE=bazaar): no installing of our own (Bazaar's rule), Bazaar says when there's a newer version and updates it ----------
export const isBazaar = import.meta.env.VITE_STORE === "bazaar";
async function bazaarCheck() {
  if (upd.status === "checking") return;
  setUpd({ status: "checking" });
  try { setUpd({ status: (await plugin.bazaarCheck()).newer ? "available" : "none" }); } catch { setUpd({ status: "error" }); }
}

// CI builds only: a local build has no version and a debug key, so a release can't install over it
if (isNative && !headless && isBazaar && /^\d+\.\d+\.\d+(-\d+)?$/.test(version)) {
  setUpdater({
    state: async () => upd,
    onState: (cb) => { updSubs.add(cb); return () => { updSubs.delete(cb); }; },
    // Bazaar's own auto-update for this app (no switch without Bazaar 26.2+); flipping it opens Bazaar's sheet, Settings re-reads on return
    auto: async (on) => {
      if (typeof on === "boolean") await plugin.bazaarOpen({ page: "autoupdate" }).catch(() => {});
      return plugin.bazaarAutoUpdate().then((r) => r.on, () => undefined);
    },
    check: async () => void bazaarCheck(),
    download: async () => { await plugin.bazaarOpen({ page: "details" }).catch(() => toast(t("update.error"))); },
    install: async () => {},
  });
  void bazaarCheck();
  setInterval(() => void bazaarCheck(), 6 * 60 * 60 * 1000);
} else if (isNative && !headless && /^\d+\.\d+\.\d+(-\d+)?$/.test(version)) {
  setUpdater({
    state: async () => upd,
    onState: (cb) => { updSubs.add(cb); return () => { updSubs.delete(cb); }; },
    auto: async (on) => {
      if (devRun) return undefined;
      if (typeof on === "boolean") {
        try { localStorage.setItem(AUTO_UPDATE, on ? "1" : "0"); } catch { /* stays as it was */ }
        if (on) downloadUpdate();
      }
      return autoUpdate();
    },
    check: async () => void checkUpdate(),
    download: async () => downloadUpdate(),
    install: async () => { if (upd.status === "ready") await plugin.updateInstall().catch(() => setUpd({ status: "available", version: upd.version })); },
  });
  void checkUpdate();
  setInterval(() => void checkUpdate(), 6 * 60 * 60 * 1000);
}
