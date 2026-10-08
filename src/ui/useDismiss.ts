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
