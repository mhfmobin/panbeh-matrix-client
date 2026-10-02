import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";

/** The Android app: our own WebView (Capacitor plugin) or the background service's headless one (JS interface). */
type Payload = { roomId: string; title: string; body: string; icon?: string; sound: boolean; openRoom?: string };
type Status = { permission: "granted" | "denied" | "default"; service: boolean; batteryOptimized: boolean };
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
  addListener(e: "openRoom", f: (d: { roomId: string }) => void): Promise<PluginListenerHandle>;
}
type Headless = { showNotification(json: string): void; cancel(roomId: string): void; stopService(): void };

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
