import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { rtl, t } from "../i18n.ts";
import { Icon, type IconName } from "../icons.tsx";
import { ReactionKey } from "./Emoji.tsx";
import { useBackdropHold } from "./useBackdropHold.ts";
import { useDismiss } from "./useDismiss.ts";

export const QUICK_REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🔥"];

type MenuItem = { icon: IconName; label: string; run: () => unknown; danger?: boolean };
type Props = { x: number; y: number; items: (MenuItem | false | "" | undefined)[]; mine: string[];
  onReact: (k: string) => void; onMore: (at: DOMRect) => void; onClose: () => void };

/** Telegram's message menu: the reactions just above the press, the actions below it, both hanging down-left from it
 *  (right / up where there's no room). `mine`: my reactions, lit; one that isn't a quick one takes the first place. */
export function MessageMenu({ x, y, items, mine, onReact, onMore, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const [closing, close] = useDismiss(onClose, 160);
  const [flip, setFlip] = useState(false);
  const extra = mine.filter((k) => !QUICK_REACTIONS.includes(k));
  const quick = QUICK_REACTIONS.filter((k, i) => mine.includes(k) || i < QUICK_REACTIONS.length - extra.length);
  useLayoutEffect(() => {
    const place = () => {
      const el = ref.current!, pad = getComputedStyle(el.parentElement!); // the backdrop's padding: the safe areas
      const [top0, right0, bottom0, left0] = ["Top", "Right", "Bottom", "Left"].map((s) => parseFloat(pad[`padding${s}` as "paddingTop"]));
      const bar = (el.firstElementChild as HTMLElement).offsetHeight + 8; // not rects: the pop-in starts scaled down
      const w = el.offsetWidth, h = bar + list.current!.scrollHeight;
      const fits = x - w >= left0, below = y + h - bar <= innerHeight - bottom0; // the actions go up from the press where they don't fit under it
      const top = Math.max(top0, Math.min(below ? y - bar : y - h, innerHeight - bottom0 - h));
      setFlip(!fits);
      Object.assign(el.style, {
        left: `${fits ? x - w : Math.min(x, innerWidth - right0 - w)}px`, top: `${top}px`,
        transformOrigin: `${fits ? "right" : "left"} ${y - top}px`, visibility: "visible",
      });
      list.current!.style.maxHeight = `${innerHeight - bottom0 - top - bar}px`;
    };
    place();
    ref.current!.focus({ preventScroll: true });
    addEventListener("resize", place);
    return () => removeEventListener("resize", place);
  }, [x, y]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); close(); return; }
      if (!["ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) return;
      e.preventDefault();
      const buttons = [...ref.current!.querySelectorAll("button")];
      const at = buttons.indexOf(document.activeElement as HTMLButtonElement);
      const back = e.key === "ArrowUp" || e.key === (rtl ? "ArrowRight" : "ArrowLeft");
      const next = e.key === "Home" ? 0 : e.key === "End" ? buttons.length - 1
        : at < 0 ? (back ? buttons.length - 1 : 0) : (at + (back ? -1 : 1) + buttons.length) % buttons.length;
      buttons[next]?.focus();
    };
    addEventListener("keydown", key);
    return () => removeEventListener("keydown", key);
  }, [close]);
  const act = (fn: () => unknown) => { onClose(); fn(); };
  const backdrop = useBackdropHold(onClose, ".msg", close); // right-clicking another message switches the menu to it (a finger hold just closes)
  return createPortal(
    <div className={"chat-menu-backdrop msg-menu-backdrop" + (closing ? " closing" : "")} {...backdrop}>
      {/* lets taps through around its two parts, to the backdrop */}
      <div className={"msg-float" + (flip ? " flip" : "")} role="menu" aria-label={t("msg.options")} tabIndex={-1} ref={ref}
        style={{ visibility: "hidden" }} onClick={(e) => e.stopPropagation()} // a hold or right-click on an item isn't one on the backdrop
        onPointerDown={(e) => e.stopPropagation()} onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); }}>
        <div className="quick-react msg-menu-react" role="group" aria-label={t("msg.react")}>
          {[...extra, ...quick].map((k) => (
            <button key={k} role="menuitem" className={mine.includes(k) ? "on" : undefined} aria-pressed={mine.includes(k)}
              onClick={() => act(() => onReact(k))}><ReactionKey k={k} /></button>
          ))}
          <button className="more" role="menuitem" title={t("msg.moreEmoji")} aria-label={t("msg.moreEmoji")}
            onClick={(e) => { const at = e.currentTarget.getBoundingClientRect(); act(() => onMore(at)); }}><Icon name="down" size={20} /></button>
        </div>
        <div className="chat-menu msg-menu-actions" ref={list}>
          {items.map((it) => it && (
            <button key={it.label} role="menuitem" className={it.danger ? "danger-item" : undefined} onClick={() => act(it.run)}><Icon name={it.icon} /> {it.label}</button>
          ))}
        </div>
      </div>
    </div>,
    document.body);
}
