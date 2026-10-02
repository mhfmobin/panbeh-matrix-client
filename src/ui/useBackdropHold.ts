import { useRef, type PointerEvent, type MouseEvent } from "react";

const HOLD_MS = 450;

/** Props for a menu's full-screen backdrop. A tap on it closes the menu; holding a finger (or right-clicking) on something
 *  underneath that matches `target` closes the menu and opens that item's own menu instead of just dismissing, by re-sending it
 *  a `contextmenu` event. A menu's own opening gesture is still down when it appears, so its release is ignored for 350ms. */
export function useBackdropHold(onClose: () => void, target: string) {
  const born = useRef(Date.now());
  const hold = useRef<{ timer: number; fired: boolean; x: number; y: number } | null>(null);
  const cancel = () => { if (hold.current) clearTimeout(hold.current.timer); };
  const reopenAt = (x: number, y: number) => {
    onClose();
    setTimeout(() => { // once the backdrop is gone
      const el = document.elementsFromPoint(x, y).find((n) => n.closest(target));
      el?.dispatchEvent(new MouseEvent("contextmenu", { bubbles: true, cancelable: true, clientX: x, clientY: y }));
    }, 0);
  };
  return {
    onClick: () => { if (Date.now() - born.current > 350) onClose(); },
    onPointerDown: (e: PointerEvent) => {
      if (e.pointerType !== "touch") return;
      cancel();
      const { clientX: x, clientY: y } = e;
      hold.current = { x, y, fired: false, timer: window.setTimeout(() => { hold.current!.fired = true; navigator.vibrate?.(15); reopenAt(x, y); }, HOLD_MS) };
    },
    onPointerMove: (e: PointerEvent) => { const h = hold.current; if (h && Math.hypot(e.clientX - h.x, e.clientY - h.y) > 10) cancel(); },
    onPointerUp: cancel,
    onPointerCancel: cancel,
    onContextMenu: (e: MouseEvent) => {
      e.preventDefault();
      if (hold.current?.fired || Date.now() - born.current < 350) return; // our own long-press (or the one that opened us) already handled it
      cancel();
      reopenAt(e.clientX, e.clientY);
    },
  };
}
