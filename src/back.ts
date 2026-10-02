import { App } from "@capacitor/app";
import { isNative } from "./native.ts";

// Android back button: a LIFO of handlers for things that are open, then Esc for overlays we don't track,
// then "leave the chat" (hash), then low-priority handlers (e.g. sidebar folder/archive), else minimize.
type Handler = () => boolean | void; // false = not consumed
const normal: Handler[] = [], low: Handler[] = [];

/** Register a back handler (newest first). Returns unregister. */
export function pushBack(fn: Handler, opts?: { low?: boolean }) {
  const s = opts?.low ? low : normal;
  s.push(fn);
  return () => { const i = s.lastIndexOf(fn); if (i >= 0) s.splice(i, 1); };
}

const OVERLAY = "[role=dialog], [role=menu], [role=listbox], .composer-mode, .select-bar";
const run = (s: Handler[]) => { for (let i = s.length - 1; i >= 0; i--) if (s[i]() !== false) return true; return false; };

/** true if something consumed the press. */
export function goBack(): boolean {
  if (run(normal)) return true;
  const ov = document.querySelectorAll(OVERLAY);
  if (ov.length) {
    // Esc handlers live on the overlay or its field; with focus elsewhere (body, a button) aim at the topmost overlay
    const a = document.activeElement;
    const top = ov[ov.length - 1];
    // the composer's reply/edit bar and @ list close from its textarea's keydown
    const field = top.closest(".composer")?.querySelector("textarea");
    const to = field ?? (a && a !== document.body && top.contains(a) ? a : top);
    to.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true }));
    return true;
  }
  if (location.hash) { location.hash = ""; return true; }
  return run(low);
}

export function startBackButton() {
  if (!isNative) return () => {};
  const h = App.addListener("backButton", () => { if (!goBack()) App.minimizeApp(); });
  return () => { h.then((x) => x.remove()); };
}

(window as unknown as { __panbehBack: unknown }).__panbehBack = goBack; // for testing
