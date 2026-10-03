/** The desktop app (Electron): desktop/preload.cjs exposes this bridge. */
type Bridge = {
  setBadge(n: number): void;
  focus(): void;
  openExternal(url: string): void;
  autostart(on?: boolean): Promise<boolean>;
  info(): Promise<{ version: string; platform: string; visible: boolean }>;
  onVisibility(cb: (visible: boolean) => void): () => void;
};

const bridge = (window as unknown as { panbehDesktop?: Bridge }).panbehDesktop;

/** Running inside the desktop app. */
export const isDesktop = !!bridge;

export const setBadge = (n: number) => bridge?.setBadge(n);
/** Brings the window back, even from the tray (window.focus() can't). */
export const showWindow = () => bridge ? bridge.focus() : focus();
export const openExternal = (url: string) => bridge?.openExternal(url);
export const getAutostart = () => bridge?.autostart() ?? Promise.resolve(false);
export const setAutostart = (on: boolean) => bridge?.autostart(on) ?? Promise.resolve(false);
export const desktopVersion = () => bridge?.info().then((i) => i.version);

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
