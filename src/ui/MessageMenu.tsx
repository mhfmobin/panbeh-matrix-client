import { useEffect, useLayoutEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { rtl, t } from "../i18n.ts";
import { Icon, type IconName } from "../icons.tsx";
import { useBackdropHold } from "./useBackdropHold.ts";
import { useDismiss } from "./useDismiss.ts";

export const QUICK_REACTIONS = ["❤️", "👍", "😁", "🤣", "😭", "👎", "🔥"];

type MenuItem = { icon: IconName; label: string; run: () => unknown; danger?: boolean };
type Props = { x: number; y: number; items: (MenuItem | false | "" | undefined)[];
  onReact: (k: string) => void; onMore: () => void; onClose: () => void };

/** A reaction capsule above a separate, narrower action menu, kept beside the press and inside the visible viewport. */
export function MessageMenu({ x, y, items, onReact, onMore, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const bounds = useRef<HTMLDivElement>(null);
  const actions = useRef<HTMLDivElement>(null);
  const [closing, close] = useDismiss(onClose, 160);
  useLayoutEffect(() => {
    const place = () => {
      const viewport = window.visualViewport;
      const inset = getComputedStyle(bounds.current!);
      const left = (viewport?.offsetLeft ?? 0) + parseFloat(inset.paddingLeft);
      const top = (viewport?.offsetTop ?? 0) + parseFloat(inset.paddingTop);
      const right = (viewport?.offsetLeft ?? 0) + (viewport?.width ?? innerWidth) - parseFloat(inset.paddingRight);
      const bottom = (viewport?.offsetTop ?? 0) + (viewport?.height ?? innerHeight) - parseFloat(inset.paddingBottom);
      const width = ref.current!.offsetWidth, actionWidth = actions.current!.offsetWidth;
      const height = Math.max(0, bottom - top);
      const menuHeight = Math.min(ref.current!.scrollHeight, height);
      const capsuleLeft = Math.max(left, Math.min(x - width / 2, right - width));
      // The actions follow the press independently of the wider reaction bar.
      const actionLeft = Math.max(0, Math.min(x - capsuleLeft - (rtl ? actionWidth * 0.7 : actionWidth * 0.3), width - actionWidth));
      Object.assign(ref.current!.style, {
        left: `${capsuleLeft}px`, top: `${Math.max(top, Math.min(y - 66, bottom - menuHeight))}px`, maxHeight: `${height}px`, visibility: "visible",
      });
      actions.current!.style.marginLeft = `${actionLeft}px`;
    };
    place();
    ref.current!.focus({ preventScroll: true });
    const observer = new ResizeObserver(place);
    observer.observe(ref.current!);
    window.addEventListener("resize", place);
    window.visualViewport?.addEventListener("resize", place);
    window.visualViewport?.addEventListener("scroll", place);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", place);
      window.visualViewport?.removeEventListener("resize", place);
      window.visualViewport?.removeEventListener("scroll", place);
    };
  }, [x, y]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); close(); return; }
      if (!["ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) return;
      e.preventDefault();
      e.stopPropagation();
      const buttons = [...ref.current!.querySelectorAll<HTMLButtonElement>("button")];
      const at = buttons.indexOf(document.activeElement as HTMLButtonElement);
      const backward = e.key === "ArrowUp" || e.key === (rtl ? "ArrowRight" : "ArrowLeft");
      const next = e.key === "Home" ? 0 : e.key === "End" ? buttons.length - 1
        : at < 0 ? (backward ? buttons.length - 1 : 0) : (at + (backward ? -1 : 1) + buttons.length) % buttons.length;
      buttons[next]?.focus({ preventScroll: true });
      buttons[next]?.scrollIntoView({ block: "nearest" });
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [close]);
  const act = (fn: () => unknown) => { onClose(); fn(); };
  const backdrop = useBackdropHold(onClose, ".msg", close);
  return createPortal(
    <div ref={bounds} className={"chat-menu-backdrop msg-menu-backdrop message-context-backdrop" + (closing ? " closing" : "")} {...backdrop}>
      <div className="message-context" role="menu" aria-label={t("msg.options")} tabIndex={-1} ref={ref}
        style={{ visibility: "hidden" }}
        onClick={(e) => e.stopPropagation()} onPointerDown={(e) => e.stopPropagation()} onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); }}>
        <div className="message-reaction-bar" role="group" aria-label={t("msg.react")}>
          {QUICK_REACTIONS.map((k) => <button key={k} type="button" role="menuitem" aria-label={k} onClick={() => act(() => onReact(k))}>{k}</button>)}
          <button type="button" className="more" role="menuitem" title={t("msg.moreEmoji")} aria-label={t("msg.moreEmoji")}
            onClick={() => act(onMore)}><Icon name="down" size={24} /></button>
        </div>
        <div className="message-context-actions" dir={rtl ? "rtl" : "ltr"} ref={actions}>
          {items.map((it) => it && (
            <button key={it.label} type="button" role="menuitem" className={it.danger ? "danger-item" : undefined} onClick={() => act(it.run)}>
              <MessageMenuIcon name={it.icon} /><span>{it.label}</span>
            </button>
          ))}
        </div>
      </div>
    </div>, document.body);
}

// These lighter outlines match the floating menu; other icon uses keep their existing appearance.
function MessageMenuIcon({ name }: { name: IconName }) {
  const paths: Partial<Record<IconName, string>> = {
    reply: "M10 5 3 12l7 7M3 12h11a6 6 0 0 1 6 6v3",
    forward: "m14 3 7 7-7 7M21 10H10a6 6 0 0 0-6 6v5",
    copy: "M8 7h11a2 2 0 0 1 2 2v12H10a2 2 0 0 1-2-2V7ZM15 3H5a2 2 0 0 0-2 2v12",
    pin: "m15 3 6 6-3 2-2 5-3 1-6-6 1-3 5-2 2-3ZM10 14l-7 7",
    trash: "M3 6h18M9 6V3h6v3M5 6l1.5 14a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1L19 6",
  };
  return paths[name] ? <svg width={24} height={24} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}
    strokeLinecap="round" strokeLinejoin="round" aria-hidden style={rtl && (name === "reply" || name === "forward") ? { transform: "scaleX(-1)" } : undefined}>
    <path d={paths[name]} />
  </svg> : <Icon name={name} size={24} />;
}
