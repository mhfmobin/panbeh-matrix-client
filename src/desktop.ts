import { useSyncExternalStore } from "react";
import { isLocked, onLockChange } from "./lock.ts";

/** The desktop app (Electron): desktop/preload.cjs exposes this bridge. */
type Bridge = {
  setBadge(n: number): void;
  focus(): void;
  openExternal(url: string): void;
  autostart(on?: boolean): Promise<boolean>;
  info(): Promise<{ version: string; platform: string; visible: boolean }>;
  onVisibility(cb: (visible: boolean) => void): () => void;
  onLink(cb: (link: string) => void): () => void;
  onPickSource?(cb: (sources: ShareSource[]) => Promise<string | null>): () => void; // older desktop builds lack it
};
/** A screen ("screen:…") or window to share, with a thumbnail data URL. */
export type ShareSource = { id: string; name: string; thumb: string };

const bridge = (window as unknown as { panbehDesktop?: Bridge }).panbehDesktop;

/** Running inside the desktop app. */
export const isDesktop = !!bridge;

export const setBadge = (n: number) => bridge?.setBadge(n);
/** Brings the window back, even from the tray (window.focus() can't). */
export const showWindow = () => bridge ? bridge.focus() : focus();
export const openExternal = (url: string) => bridge?.openExternal(url);
export const getAutostart = () => bridge?.autostart() ?? Promise.resolve(false);
export const setAutostart = (on: boolean) => bridge?.autostart(on) ?? Promise.resolve(false);

// the page stays "visible" in the tray (background throttling is off so it keeps syncing): ask the window instead
let windowVisible = true;
const listeners = new Set<() => void>();
if (bridge) {
  bridge.info().then((i) => { windowVisible = i.visible; listeners.forEach((f) => f()); }, () => {});
  bridge.onVisibility((v) => { windowVisible = v; listeners.forEach((f) => f()); });
}
/** False while the window is hidden in the tray or minimized. */
export const isWindowVisible = () => windowVisible && document.visibilityState === "visible";
export function onWindowVisibility(f: () => void) {
  listeners.add(f);
  document.addEventListener("visibilitychange", f);
  return () => { listeners.delete(f); document.removeEventListener("visibilitychange", f); };
}

// "Someone is looking": visible, focused, unlocked, and touched in the last minute. Read receipts wait for it.
// ponytail: browsers report no OS screen lock (Idle Detection needs a permission), so the idle timeout stands in for it
const IDLE_MS = 60_000;
let lastInput = Date.now(), idleTimer = 0;
const attention = new Set<() => void>();
const ping = () => attention.forEach((f) => f());
const onInput = () => {
  const wasIdle = Date.now() - lastInput >= IDLE_MS;
  lastInput = Date.now();
  if (wasIdle) ping();
  clearTimeout(idleTimer);
  idleTimer = window.setTimeout(ping, IDLE_MS);
};
for (const ev of ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"]) addEventListener(ev, onInput, { capture: true, passive: true });
idleTimer = window.setTimeout(ping, IDLE_MS);
for (const ev of ["focus", "blur"]) addEventListener(ev, ping);
onWindowVisibility(ping);
onLockChange(ping);

export const isAttentive = () => isWindowVisible() && document.hasFocus() && !isLocked() && Date.now() - lastInput < IDLE_MS;
const onAttentive = (f: () => void) => { attention.add(f); return () => { attention.delete(f); }; };
export const useAttentive = () => useSyncExternalStore(onAttentive, isAttentive);

/** matrix.to / matrix: links the OS or a click handed to the app. Returns an unsubscribe. */
export const onDesktopLink = (cb: (link: string) => void) => bridge?.onLink(cb) ?? (() => {});

/** Screen sharing on Windows/X11: show our picker; resolve to the chosen source id, or null. Returns an unsubscribe. */
export const onPickSource = (cb: (sources: ShareSource[]) => Promise<string | null>) => bridge?.onPickSource?.(cb) ?? (() => {});
