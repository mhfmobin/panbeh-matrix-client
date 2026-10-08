import { useCallback, useEffect, useRef, useState } from "react";

export const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

/** Exit animation for overlays their parent unmounts on close: `close()` sets `closing` (style it with a `.closing`
 *  class) and calls `onClose` once the animation has had `ms` to play. */
export function useDismiss(onClose: () => void, ms = 180) {
  const [closing, setClosing] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const latest = useRef(onClose);
  latest.current = onClose;
  useEffect(() => () => clearTimeout(timer.current), []);
  const close = useCallback(() => {
    if (timer.current) return; // already leaving
    if (reducedMotion()) return latest.current();
    setClosing(true);
    timer.current = setTimeout(() => latest.current(), ms);
  }, [ms]);
  return [closing, close] as const;
}

/** Exit animation for something the parent toggles with a boolean: `shown` stays true for `ms` after `open` turns false,
 *  while `closing` is true (style it with a `.closing` class). */
export function useExit(open: boolean, ms = 160) {
  const [mounted, setMounted] = useState(open);
  useEffect(() => {
    if (open) return setMounted(true);
    if (reducedMotion()) return setMounted(false);
    const t = setTimeout(() => setMounted(false), ms);
    return () => clearTimeout(t);
  }, [open, ms]);
  return [open || mounted, !open && mounted] as const;
}

// Where the last tap landed, so a lightbox can grow out of the thumbnail that opened it.
let tapped: { rect: DOMRect; at: number } | null = null;
if (typeof document !== "undefined") {
  document.addEventListener("click", (e) => {
    const el = (e.target as Element | null)?.closest?.("button, a, img, video");
    tapped = el ? { rect: el.getBoundingClientRect(), at: Date.now() } : null;
  }, true);
}
/** The thumbnail rect of a tap in the last moment (consumed once), or null. */
export const takeOrigin = () => { const t = tapped; tapped = null; return t && Date.now() - t.at < 800 && !reducedMotion() ? t.rect : null; };

/** Grow `el` out of `from` (a viewport rect): the FLIP of its own box. */
export function growFrom(el: HTMLElement, from: DOMRect) {
  const to = el.getBoundingClientRect();
  if (!to.width || !to.height) return;
  const s = Math.max(0.05, Math.min(from.width / to.width, from.height / to.height));
  const dx = from.left + from.width / 2 - (to.left + to.width / 2), dy = from.top + from.height / 2 - (to.top + to.height / 2);
  el.animate([{ transform: `translate(${dx}px, ${dy}px) scale(${s})`, opacity: 0.2 }, { transform: "none", opacity: 1 }], { duration: 280, easing: "cubic-bezier(0.2, 0.8, 0.2, 1)" });
}
