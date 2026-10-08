import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type RefObject } from "react";

const HOLD_MS = 400;   // touch: hold still this long to pick a tab up
const SLOP = 8;        // px of travel that cancels the hold (touch) or starts the drag (mouse)
const EDGE = 36;       // px from the bar's edge where it autoscrolls
const GAP = 4;

type Drag = {
  i: number; id: string; x0: number; scroll0: number; x: number; started: boolean; pointer: number;
  timer?: number; raf?: number; el: HTMLElement; tabs: HTMLElement[]; centers: number[];
};

/** Drag-to-reorder for a row of tabs (`button[data-id]` children of the ref'd element), pointer events only.
 *  Touch picks a tab up after a long press so scrolling and swiping the bar still work; the mouse drags right away.
 *  The first tab is fixed. Alt+←/→ moves the focused tab. Returns props for the bar and the id being dragged. */
export function useSortableTabs(bar: RefObject<HTMLElement | null>, ids: string[], onMove: (from: number, to: number) => void) {
  const [dragging, setDragging] = useState<string | null>(null);
  const d = useRef<Drag | null>(null);
  const moved = useRef(false); // a drag just ended: swallow the click that follows it
  const live = useRef({ ids, onMove });
  live.current = { ids, onMove };

  const target = (s: Drag) => {
    const c = s.centers[s.i] + (s.x - s.x0) + (bar.current!.scrollLeft - s.scroll0);
    let best = s.i, dist = Infinity;
    s.centers.forEach((cx, j) => { if (j > 0 && Math.abs(cx - c) < dist) { dist = Math.abs(cx - c); best = j; } });
    return best;
  };
  const layout = (s: Drag) => {
    const dx = s.x - s.x0 + (bar.current!.scrollLeft - s.scroll0), to = target(s);
    const w = s.el.getBoundingClientRect().width + GAP;
    s.tabs.forEach((t, j) => {
      if (j === s.i) { t.style.transform = `translateX(${dx}px)`; return; }
      const between = s.i < to ? j > s.i && j <= to : j < s.i && j >= to;
      const dir = Math.sign(s.centers[j > s.i ? j - 1 : j + 1] - s.centers[j]); // toward the slot it moves into
      t.style.transform = between ? `translateX(${dir * w}px)` : "";
    });
  };
  const clear = (s: Drag) => s.tabs.forEach((t) => { t.style.transform = ""; t.style.transition = ""; });
  const stop = () => {
    const s = d.current;
    if (!s) return;
    clearTimeout(s.timer); cancelAnimationFrame(s.raf!);
    d.current = null;
    setDragging(null);
    return s;
  };
  const begin = (s: Drag) => {
    s.started = true;
    navigator.vibrate?.(15);
    s.el.setPointerCapture?.(s.pointer);
    s.tabs.forEach((t) => { t.style.transition = "transform 0.15s"; });
    s.el.style.transition = "none";
    s.el.style.zIndex = "2";
    setDragging(s.id);
    const tick = () => { // autoscroll while held near an edge
      const r = bar.current!.getBoundingClientRect();
      const v = s.x < r.left + EDGE ? -10 : s.x > r.right - EDGE ? 10 : 0;
      if (v) { bar.current!.scrollLeft += v; layout(s); }
      s.raf = requestAnimationFrame(tick);
    };
    tick();
    layout(s);
  };
  const end = (commit: boolean) => {
    const s = d.current;
    if (!s) return;
    const to = s.started ? target(s) : s.i;
    stop();
    clear(s); s.el.style.zIndex = "";
    if (s.started) {
      moved.current = true;
      setTimeout(() => { moved.current = false; }, 50);
      if (commit && to !== s.i) live.current.onMove(s.i, to);
    }
  };

  // touchmove can only be cancelled by a non-passive native listener; once a tab is picked up, the bar must not scroll
  useEffect(() => {
    const el = bar.current;
    if (!el) return;
    const block = (e: TouchEvent) => { if (d.current?.started) e.preventDefault(); };
    el.addEventListener("touchmove", block, { passive: false });
    return () => el.removeEventListener("touchmove", block);
  });
  useEffect(() => () => { const s = d.current; if (s) { stop(); clear(s); } }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!dragging) return;
    const esc = (e: globalThis.KeyboardEvent) => { if (e.key === "Escape") end(false); };
    addEventListener("keydown", esc);
    return () => removeEventListener("keydown", esc);
  }, [dragging]); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    dragging,
    barProps: {
      onPointerDown: (e: PointerEvent) => {
        if (e.button > 0 || d.current) return;
        const el = (e.target as HTMLElement).closest<HTMLElement>("button[data-id]");
        const tabs = [...bar.current!.querySelectorAll<HTMLElement>("button[data-id]")];
        const i = el ? tabs.indexOf(el) : -1;
        if (!el || i < 1) return; // the first tab stays put
        const s: Drag = {
          i, id: el.dataset.id!, x0: e.clientX, x: e.clientX, scroll0: bar.current!.scrollLeft, started: false, pointer: e.pointerId, el, tabs,
          centers: tabs.map((t) => { const r = t.getBoundingClientRect(); return r.left + r.width / 2; }),
        };
        d.current = s;
        if (e.pointerType === "touch") s.timer = window.setTimeout(() => begin(s), HOLD_MS);
      },
      onPointerMove: (e: PointerEvent) => {
        const s = d.current;
        if (!s) return;
        s.x = e.clientX;
        if (!s.started) {
          if (Math.abs(e.clientX - s.x0) > SLOP) { if (e.pointerType === "touch") stop(); else begin(s); }
          return;
        }
        layout(s);
      },
      onPointerUp: () => end(true),
      onPointerCancel: () => end(false),
      onLostPointerCapture: () => end(true),
      onClickCapture: (e: { stopPropagation(): void; preventDefault(): void }) => { if (moved.current) { e.stopPropagation(); e.preventDefault(); } },
      onContextMenu: (e: { preventDefault(): void }) => { if (d.current?.started) e.preventDefault(); }, // long-press menu on some browsers
      onKeyDown: (e: KeyboardEvent) => {
        if (!e.altKey || (e.key !== "ArrowLeft" && e.key !== "ArrowRight")) return;
        const el = (e.target as HTMLElement).closest<HTMLElement>("button[data-id]");
        const i = el ? live.current.ids.indexOf(el.dataset.id!) : -1;
        if (i < 1) return;
        const rtl = getComputedStyle(bar.current!).direction === "rtl";
        const to = i + ((e.key === "ArrowRight") === rtl ? -1 : 1);
        e.preventDefault();
        if (to >= 1 && to < live.current.ids.length) { live.current.onMove(i, to); requestAnimationFrame(() => bar.current?.querySelector<HTMLElement>(`button[data-id="${CSS.escape(el!.dataset.id!)}"]`)?.focus()); }
      },
    },
  };
}
