import { useLayoutEffect, useRef } from "react";

/** A segmented control's highlight that slides to the selected button (`.on`) instead of jumping.
 *  Put `ref` and `data-pill` on the container; the first placement doesn't animate. */
export function useSlider<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useLayoutEffect(() => { // every render: labels, counts and the selection can all change the geometry
    const box = ref.current, on = box?.querySelector<HTMLElement>("button.on");
    if (!box || !on) return;
    box.style.setProperty("--px", on.offsetLeft + "px");
    box.style.setProperty("--pw", on.offsetWidth + "px");
    if (!box.classList.contains("ready")) requestAnimationFrame(() => box.classList.add("ready"));
  });
  return ref;
}
