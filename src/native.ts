import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";

/** The Android app: our own WebView (Capacitor plugin) or the background service's headless one (JS interface). */
type Payload = { roomId: string; title: string; body: string; icon?: string; sound: boolean; openRoom?: string };
type Status = { permission: "granted" | "denied" | "default"; service: boolean; batteryOptimized: boolean; fullScreen: boolean };
type CallPayload = { roomId: string; eventId: string; caller: string; video: boolean; timeout: number; icon?: string };
/** A button on the incoming-call notification; "open" = the notification itself (full-screen on the lock screen). */
export type CallAction = { action: "answer" | "decline" | "open"; roomId: string; eventId: string; video: boolean };
interface PanbehPlugin {
  showNotification(p: Payload): Promise<void>;
  cancel(p: { roomId: string }): Promise<void>;
  cancelAll(): Promise<void>;
  startService(): Promise<void>;
  stopService(): Promise<void>;
  status(): Promise<Status>;
  requestNotifyPermission(): Promise<{ permission: Status["permission"] }>;
  requestBatteryExemption(): Promise<void>;
  takeLaunchRoom(): Promise<{ roomId?: string | null }>;
  takeLaunchLink(): Promise<{ link?: string | null }>;
  showCall(p: CallPayload): Promise<void>;
  cancelCall(p: { roomId: string }): Promise<void>;
  callActive(p: { on: boolean; video: boolean }): Promise<void>;
  setSpeaker(p: { on: boolean }): Promise<void>;
  audioRoutes(): Promise<{ routes: AudioRoute[]; current: number }>;
  setAudioRoute(p: { id: number }): Promise<void>;
  setPip(p: { on: boolean }): Promise<void>;
  requestFullScreen(): Promise<void>;
  takeLaunchCall(): Promise<Partial<CallAction>>;
  addListener(e: "openRoom", f: (d: { roomId: string }) => void): Promise<PluginListenerHandle>;
  addListener(e: "openLink", f: (d: { link: string }) => void): Promise<PluginListenerHandle>;
  addListener(e: "callAction", f: (d: CallAction) => void): Promise<PluginListenerHandle>;
}
type Headless = { showNotification(json: string): void; cancel(roomId: string): void; stopService(): void; showCall(json: string): void; cancelCall(roomId: string): void };

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
export const nativeCallActive = (on: boolean, video: boolean) => { if (!headless) plugin.callActive({ on, video }).catch(() => {}); };
export const nativeSpeaker = (on: boolean) => { if (!headless) plugin.setSpeaker({ on }).catch(() => {}); };
/** Where call audio can go (Android 12+; empty before, where it's only the speaker toggle). */
export type AudioRoute = { id: number; kind: "earpiece" | "speaker" | "wired" | "bluetooth"; name: string };
export const nativeAudioRoutes = () => plugin.audioRoutes().catch(() => ({ routes: [] as AudioRoute[], current: -1 }));
export const nativeSetAudioRoute = (id: number) => plugin.setAudioRoute({ id });
/** A video call is on screen: leaving the app shrinks it to picture-in-picture. */
export const nativePip = (on: boolean) => { if (!headless) plugin.setPip({ on }).catch(() => {}); };
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

/** Blob/object URL → data URL, for handing images to native code. */
export async function toDataUrl(url: string) {
  const b = await (await fetch(url)).blob();
  return new Promise<string>((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(r.result as string);
    r.onerror = () => rej(r.error);
    r.readAsDataURL(b);
  });
}
