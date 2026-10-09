import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { loadPrefs } from "./ui/Settings.tsx";

/** The Android app: our own WebView (Capacitor plugin) or the background service's headless one (JS interface). */
type Payload = { roomId: string; title: string; body: string; icon?: string; sound: boolean; openRoom?: string };
/** interval: minutes between background checks, 0 = real-time. */
type Status = { permission: "granted" | "denied" | "default"; service: boolean; batteryOptimized: boolean; fullScreen: boolean; interval: number };
type CallPayload = { roomId: string; eventId: string; caller: string; video: boolean; timeout: number; icon?: string };
/** A button on the incoming-call notification; "open" = the notification itself (full-screen on the lock screen). */
/** "Share with Panbeh" from another app: text and/or content:// files. */
export type Shared = { text?: string; files: { uri: string; name: string; type: string }[] };
/** hangup / hold / unhold: from Android's Telecom (a headset or car button, a phone call coming in) for the call in progress. */
export type CallAction = { action: "answer" | "decline" | "open" | "hangup" | "hold" | "unhold"; roomId: string; eventId: string; video: boolean };
interface PanbehPlugin {
  showNotification(p: Payload): Promise<void>;
  cancel(p: { roomId: string }): Promise<void>;
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
  setImmersive(p: { on: boolean }): Promise<void>;
  requestFullScreen(): Promise<void>;
  takeLaunchCall(): Promise<Partial<CallAction>>;
  saveOpen(p: { name: string; mime: string; ask: boolean }): Promise<{ id: string | null; picked?: boolean }>;
  saveChunk(p: { id: string; data: string }): Promise<void>;
  saveClose(p: { id: string }): Promise<void>;
  saveAbort(p: { id: string }): Promise<void>;
  takeLaunchShare(): Promise<Partial<Shared>>;
  addListener(e: "openRoom", f: (d: { roomId: string }) => void): Promise<PluginListenerHandle>;
  addListener(e: "openLink", f: (d: { link: string }) => void): Promise<PluginListenerHandle>;
  addListener(e: "callAction", f: (d: CallAction) => void): Promise<PluginListenerHandle>;
  addListener(e: "share", f: (d: Shared) => void): Promise<PluginListenerHandle>;
  addListener(e: "audioRoutes", f: (d: Routes) => void): Promise<PluginListenerHandle>;
}
type Headless = { showNotification(json: string): void; cancel(roomId: string): void; stopService(): void; showCall(json: string): void; cancelCall(roomId: string): void; syncState(state: string): void };

const headless = (window as unknown as { PanbehAndroid?: Headless }).PanbehAndroid;
const plugin = registerPlugin<PanbehPlugin>("Panbeh");

/** Running inside the Android app (either WebView). */
export const isNative = !!headless || Capacitor.isNativePlatform();
/** The background service's invisible copy of the app: sync and notify only, no UI. */
export const isHeadless = !!headless && new URLSearchParams(location.search).has("headless");

export function nativeNotify(p: Payload) {
  if (headless) headless.showNotification(JSON.stringify(p));
  else plugin.showNotification(p).catch(() => {});
}
export function nativeCancel(roomId: string) {
  if (headless) headless.cancel(roomId);
  else plugin.cancel({ roomId }).catch(() => {});
}
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

const blobToDataUrl = (b: Blob) => new Promise<string>((res, rej) => {
  const r = new FileReader();
  r.onload = () => res(r.result as string);
  r.onerror = () => rej(r.error);
  r.readAsDataURL(b);
});

/** Blob/object URL → data URL, for handing images to native code. */
export const toDataUrl = async (url: string) => blobToDataUrl(await (await fetch(url)).blob());

/** Where a file is being saved: the browser's download, the phone's Downloads folder, or a place picked in Android's "Save as". */
export type SaveTarget = { name: string; id?: string; where: "browser" | "downloads" | "picked" };
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
    await plugin.saveClose({ id: t.id });
  } catch (e) {
    abortSave(t);
    throw e;
  }
}

/** Drops a half-written file (cancelled or failed). Safe to call twice. */
export function abortSave(t: SaveTarget | null) {
  if (t?.id) plugin.saveAbort({ id: t.id }).catch(() => {});
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
