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

/** FLIP for a list row that kept its identity while the list reordered: slides from where it was (in scroller content
 *  coordinates, so scrolling alone isn't a move). Rows new to the list or far away just appear. */
const lastY = new Map<string, number>();
export function flipRow(el: HTMLElement, id: string) {
  const sc = el.closest<HTMLElement>("[data-virtuoso-scroller]");
  if (!sc) return;
  const y = el.getBoundingClientRect().top - sc.getBoundingClientRect().top + sc.scrollTop;
  const was = lastY.get(id);
  lastY.set(id, y);
  if (was === undefined || Math.abs(was - y) < 2 || Math.abs(was - y) > 400 || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  el.animate([{ transform: `translateY(${was - y}px)` }, { transform: "none" }], { duration: 280, easing: "cubic-bezier(0.2, 0.8, 0.2, 1)" });
}
