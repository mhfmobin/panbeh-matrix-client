import { useEffect, useRef, useState, type MouseEvent, type PointerEvent } from "react";
import { createPortal } from "react-dom";
import DOMPurify from "dompurify";
import { handleIncomingLink } from "../openTarget.ts";
import { matrixToLink, parseMatrixLink, viaServers } from "../uri.ts";
import { EventStatus, EventType, M_POLL_START, RelationType, type MatrixEvent, type Room } from "matrix-js-sdk";
import { avatarUrl, client, isSavedGif, loadEvent, mediaUrl, pinnedIds, seenBy, toggleGif, togglePin } from "../matrix.ts";
import { abortSave, openSave, writeSave, type SaveTarget } from "../native.ts";
import { usePromise } from "../hooks.ts";
import { LINK_SRC, clock, fmtDuration, isGif, linkHref, num, osmUrl, parseGeoUri, stamp, textDir, type Gif } from "../logic.ts";
import { Icon, iconSvg, type IconName } from "../icons.tsx";
import { Avatar, colorFor, copyText, errText, formatSize, isGroupChat, me, previewText, senderMember, senderName, stripReplyFallback, toast, useChange } from "./common.tsx";
import { AudioPlayer, trackFor } from "./Voice.tsx";
import { PollBody } from "./Poll.tsx";
import { EmojiPanel, GifView } from "./Emoji.tsx";
import { LinkPreview } from "./LinkPreview.tsx";
import { captionOf, EDITABLE } from "./Composer.tsx";
import { useBackdropHold } from "./useBackdropHold.ts";
import { reducedMotion, useDismiss } from "./useDismiss.ts";
import { thumbFor } from "./Media.tsx";
import { alertDialog, confirmDialog, promptDialog } from "./dialog.tsx";

export type Actions = {
  reply: (ev: MatrixEvent) => void;
  edit: (ev: MatrixEvent) => void;
  thread?: (ev: MatrixEvent) => void; // absent inside a thread
  view: (ev: MatrixEvent) => void; // media viewer
  info: (ev: MatrixEvent) => void; // who has seen it
  reactions: (ev: MatrixEvent) => void; // who reacted with what
  profile: (userId: string) => void;
  forward: (ev: MatrixEvent) => void;
  jump?: (eventId: string) => void; // reply quote → the original
  select?: (ev: MatrixEvent) => void; // toggles multi-select membership; absent inside a thread
  selection?: ReadonlySet<string>; // event ids; present = selecting
};

const QUICK = ["👍", "❤️", "😂", "😮", "😢", "🔥"];

const LONG_PRESS = 450, SWIPE_AT = 60, SWIPE_MAX = 80;

/** Text a copy should give: body without the reply fallback; media only their caption. */
export function copyTextOf(ev: MatrixEvent): string {
  if (ev.isDecryptionFailure() || ev.getType() === EventType.RoomMessageEncrypted || M_POLL_START.matches(ev.getType())) return "";
  const c = ev.getContent();
  if (c.msgtype === "m.audio") return "";
  if (c.msgtype === "m.image" || c.msgtype === "m.video" || c.msgtype === "m.file" || ev.getType() === EventType.Sticker) return captionOf(c);
  return stripReplyFallback(String(c.body ?? ""));
}

export const copyMessages = (text: string) => copyText(text).then(() => toast("کپی شد"), (e) => alertDialog(errText(e)));

let pillRoom: Room | null = null; // the room being rendered; sanitize() is synchronous

// links in formatted messages must not navigate the app away; matrix.to user/room links become pills
DOMPurify.addHook("afterSanitizeAttributes", (n) => {
  if (n.tagName !== "A") return;
  n.setAttribute("target", "_blank");
  n.setAttribute("rel", "noreferrer noopener");
  const m = /^https:\/\/matrix\.to\/#\/([@#!][^/?]+)$/.exec(n.getAttribute("href") ?? "");
  if (!m || !pillRoom) return;
  let id = m[1];
  try { id = decodeURIComponent(id); } catch { /* keep as is */ }
  const name = id.startsWith("@") ? pillRoom.getMember(id)?.name : linkedRoom(id)?.name;
  n.className = "mention" + (id === me() ? " me" : "");
  n.setAttribute("data-mxid", id);
  n.setAttribute("data-name", name ?? id);
  n.textContent = name ?? id; // current name, Telegram-style, whatever the sender typed
});

const linkedRoom = (id: string) =>
  id.startsWith("!") ? client.getRoom(id) : client.getRooms().find((r) => r.getCanonicalAlias() === id || r.getAltAliases().includes(id));

function openRoomPill(a: HTMLAnchorElement) {
  const r = linkedRoom(a.dataset.mxid!);
  if (r?.getMyMembership() === "join") location.hash = r.roomId;
  else window.open(a.href, "_blank", "noopener");
}

type Props = { ev: MatrixEvent; room: Room; first: boolean; last: boolean; actions: Actions; flash?: boolean; enter?: boolean };

export function Message({ ev, room, first, last, actions, flash, enter }: Props) {
  const [picker, setPicker] = useState(false);
  const [fullPicker, setFullPicker] = useState(false);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null); // touch screens have no hover: long-press (or right-click) opens this
  const row = useRef<HTMLDivElement>(null);
  const g = useRef<{ id: number; x: number; y: number; timer: number; lock: boolean; px: number; crossed: boolean } | null>(null);
  const swallow = useRef(false); // the click that follows a swipe / long-press
  const mine = ev.getSender() === me();
  const [deleting, setDeleting] = useState(false);
  const remove = () => void confirmDialog("این پیام برای همه حذف شود؟", { danger: true }).then((y) => { // the bubble shrinks away, then the redaction removes the row
    if (!y) return;
    setDeleting(true);
    setTimeout(() => { client.redactEvent(room.roomId, ev.getId()!).catch((e) => { setDeleting(false); alertDialog(errText(e)); }); }, reducedMotion() ? 0 : 200);
  });
  const failed = ev.status === EventStatus.NOT_SENT;
  const group = isGroupChat(room);
  const content = ev.getContent();
  const media = content.msgtype === "m.image" || content.msgtype === "m.video" || ev.getType() === EventType.Sticker;
  const thread = actions.thread ? room.getThread(ev.getId()!) : null; // thread summaries only in the main timeline
  const replyTo = ev.getRelation()?.is_falling_back ? undefined : ev.replyEventId; // thread fallback isn't a real reply
  const canDelete = mine || room.currentState.maySendRedactionForEvent(ev, me());
  const canPin = !!ev.getId() && room.currentState.maySendStateEvent(EventType.RoomPinnedEvents, me());
  const pinned = pinnedIds(room).includes(ev.getId()!);
  const fwd = content["app.panbeh.forwarded"] as { sender: string; name?: string } | undefined;
  const selecting = !!actions.selection;
  const selected = !!actions.selection?.has(ev.getId()!);
  const live = !failed && !ev.isDecryptionFailure() && ev.status == null && !!ev.getId(); // same bar as the action bar
  const swipeOk = live && !selecting;
  const text = copyTextOf(ev);
  const copyLink = () => void copyMessages(matrixToLink(room.roomId, ev.getId()!, viaServers(room.getJoinedMembers().map((m) => m.userId))));
  const mentioned = !mine && !!client.getPushActionsForEvent(ev)?.tweaks?.highlight;
  const reacted = !!ev.getId() && !!room.relations.getChildEventsForEvent(ev.getId()!, RelationType.Annotation, EventType.Reaction)
    ?.getSortedAnnotationsByKey()?.some(([, set]) => set.size > 0);

  const setX = (px: number) => { row.current!.style.setProperty("--sx", `${-px}px`); row.current!.style.setProperty("--sp", String(Math.min(1, px / SWIPE_AT))); };
  const end = (cancel: boolean) => {
    const s = g.current;
    g.current = null;
    if (!s) return;
    clearTimeout(s.timer);
    if (s.lock) {
      setTimeout(() => { swallow.current = false; }, 350);
      row.current!.classList.add("snap"); // animate back
      setX(0);
      setTimeout(() => row.current?.classList.remove("snap", "swiping"), 220);
      if (!cancel && s.px >= SWIPE_AT) actions.reply(ev);
    }
  };
  // swipe LEFT to reply (Telegram's direction) for both own and others' messages; rows stay physically LTR so -x is left
  const inside = (e: { target: EventTarget }) => row.current!.contains(e.target as Node); // portals (menu, picker) bubble through React
  const down = (e: PointerEvent) => {
    if (!inside(e) || e.pointerType !== "touch" || !live || selecting || (e.target as HTMLElement).closest("input,video,audio")) return;
    const { clientX: x, clientY: y } = e;
    clearTimeout(g.current?.timer);
    g.current = { id: e.pointerId, x, y, lock: false, px: 0, crossed: false, timer: window.setTimeout(() => {
      swallow.current = true;
      setTimeout(() => { swallow.current = false; }, 1500); // the click normally follows at finger-up; don't eat a later tap if it doesn't
      navigator.vibrate?.(15);
      setMenu({ x, y });
    }, LONG_PRESS) };
  };
  const move = (e: PointerEvent) => {
    const s = g.current;
    if (!s || s.id !== e.pointerId) return;
    const dx = e.clientX - s.x, dy = e.clientY - s.y;
    if (!s.lock) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) < 10) return;
      clearTimeout(s.timer);
      if (dx < 0 && -dx > Math.abs(dy)) {
        s.lock = true;
        swallow.current = true;
        row.current!.classList.add("swiping");
        row.current!.setPointerCapture?.(e.pointerId);
      } else { g.current = null; return; } // vertical: let the list scroll
    }
    const raw = Math.max(0, -dx);
    s.px = raw;
    setX(raw <= SWIPE_MAX ? raw : Math.min(SWIPE_MAX + 30, SWIPE_MAX + (raw - SWIPE_MAX) * 0.25));
    if (raw >= SWIPE_AT !== s.crossed) { s.crossed = !s.crossed; if (s.crossed) navigator.vibrate?.(10); }
  };

  return (
    <div ref={row} className={`msg ${mine ? "mine" : "theirs"}${first ? " first" : ""}${last ? " last" : ""}${failed ? " failed" : ""}${flash ? " flash" : ""}${enter ? " enter" : ""}${deleting ? " deleting" : ""}${selecting ? " selecting" : ""}${selected ? " selected" : ""}`}
      onPointerDown={down} onPointerMove={move} onPointerUp={() => end(false)} onPointerCancel={() => end(true)}
      onContextMenu={(e) => { if (!live || selecting || !inside(e)) return; e.preventDefault(); setMenu((m) => m ?? { x: e.clientX, y: e.clientY }); }}
      onClickCapture={(e) => {
        if (!inside(e)) return;
        if (selecting) { e.preventDefault(); e.stopPropagation(); if (ev.getId()) actions.select!(ev); } // taps only toggle while selecting
        else if (swallow.current) { e.preventDefault(); e.stopPropagation(); swallow.current = false; }
      }}>
      {selecting && <span className="sel-check" aria-hidden>{selected && <Icon name="check" size={14} />}</span>}
      {swipeOk && <span className="swipe-ico" aria-hidden><Icon name="reply" size={20} /></span>}
      {!mine && group && (
        <div className="msg-avatar">{last && (
          <button className="plain" aria-label={senderName(ev)} onClick={(e) => { e.stopPropagation(); actions.profile(ev.getSender()!); }}>
            <Avatar mxc={senderMember(ev)?.getMxcAvatarUrl()} name={senderName(ev)} id={ev.getSender()!} size={34} />
          </button>
        )}</div>
      )}
      <div className="msg-col">
        {/* dir=auto: direction comes from the body; children with their own dir are skipped */}
        <div className={"bubble" + (media ? " media" : "") + (ev.replacingEvent() ? " edited" : "") + (mentioned ? " mentioned" : "")} dir="auto">
          {!mine && group && first && <button className="msg-sender" dir="auto" style={{ color: colorFor(ev.getSender()!) }}
            onClick={(e) => { e.stopPropagation(); actions.profile(ev.getSender()!); }}>{senderName(ev)}</button>}
          {fwd && (
            <button className="fwd-from" onClick={(e) => { e.stopPropagation(); actions.profile(fwd.sender); }}>
              هدایت‌شده از <b dir="auto">{room.getMember(fwd.sender)?.name ?? fwd.name ?? fwd.sender}</b>
            </button>
          )}
          {replyTo && <ReplyQuote room={room} id={replyTo} onJump={actions.jump} />}
          <Body ev={ev} room={room} onView={actions.view} onUser={actions.profile} />
          <LinkPreview ev={ev} />
          <span className="msg-meta" dir="rtl">
            {pinned && <Icon name="pin" size={12} />}
            {ev.replacingEvent() && <span>ویرایش‌شده</span>}
            <span>{clock(ev.getTs())}</span>
            {mine && <Ticks ev={ev} room={room} />}
          </span>
        </div>
        <Reactions ev={ev} room={room} />
        {thread && thread.length > 0 && (
          <button className="thread-link" onClick={() => actions.thread?.(ev)}>
            <Icon name="thread" size={15} /> {num(thread.length)} پاسخ
            {room.getThreadUnreadNotificationCount(thread.id) > 0 && <span className="badge">{num(room.getThreadUnreadNotificationCount(thread.id))}</span>}
          </button>
        )}
      </div>
      {failed && (
        <div className="msg-actions">
          <button title="ارسال دوباره" aria-label="ارسال دوباره" onClick={() => client.resendEvent(ev, room).catch(() => {})}><Icon name="send" size={17} /></button>
          <button title="حذف" aria-label="حذف" onClick={() => client.cancelPendingEvent(ev)}><Icon name="trash" size={17} /></button>
        </div>
      )}
      {!ev.isDecryptionFailure() && ev.status == null && (
        <div className="msg-actions">
          <button title="واکنش" aria-label="واکنش" onClick={() => setPicker((p) => !p)}><Icon name="smile" size={17} /></button>
          <button title="پاسخ" aria-label="پاسخ" onClick={() => actions.reply(ev)}><Icon name="reply" size={17} /></button>
          {ev.getId() && !M_POLL_START.matches(ev.getType()) && <button title="هدایت" aria-label="هدایت" onClick={() => actions.forward(ev)}><Icon name="forward" size={17} /></button>}
          {ev.getId() && <button title="کپی پیوند" aria-label="کپی پیوند" onClick={copyLink}><Icon name="link" size={17} /></button>}
          {actions.thread && <button title="پاسخ در رشته" aria-label="پاسخ در رشته" onClick={() => actions.thread!(ev)}><Icon name="thread" size={17} /></button>}
          {mine && <button title="دیده‌شده توسط" aria-label="دیده‌شده توسط" onClick={() => actions.info(ev)}><Icon name="info" size={17} /></button>}
          {canPin && <button title={pinned ? "برداشتن سنجاق" : "سنجاق"} aria-label={pinned ? "برداشتن سنجاق" : "سنجاق"}
            onClick={() => togglePin(room, ev.getId()!).catch((e) => alertDialog(errText(e)))}><Icon name="pin" size={17} /></button>}
          {mine && EDITABLE.includes(content.msgtype ?? "") && <button title="ویرایش" aria-label="ویرایش" onClick={() => actions.edit(ev)}><Icon name="edit" size={17} /></button>}
          {canDelete && <button title="حذف" aria-label="حذف" onClick={() => remove()}><Icon name="trash" size={17} /></button>}
          {picker && (
            <div className="quick-react" onMouseLeave={() => setPicker(false)}>
              {QUICK.map((k) => <button key={k} onClick={() => { toggleReaction(room, ev, k); setPicker(false); }}>{k}</button>)}
              <button className="more" title="اموجی‌های بیشتر" aria-label="اموجی‌های بیشتر" onClick={() => { setPicker(false); setFullPicker(true); }}><Icon name="plus" size={18} /></button>
            </div>
          )}
        </div>
      )}
      {menu && (
        <MsgMenu {...menu} onClose={() => setMenu(null)} onReact={(k) => toggleReaction(room, ev, k)} onMore={() => setFullPicker(true)} items={[
          { icon: "reply", label: "پاسخ", run: () => actions.reply(ev) },
          text && { icon: "copy", label: "کپی", run: () => void copyMessages(text) },
          ev.getId() && { icon: "link", label: "کپی پیوند", run: copyLink },
          !M_POLL_START.matches(ev.getType()) && { icon: "forward", label: "هدایت", run: () => actions.forward(ev) },
          actions.thread && { icon: "thread", label: "پاسخ در رشته", run: () => actions.thread!(ev) },
          isGif(content) && (isSavedGif(content)
            ? { icon: "close", label: "حذف از گیف‌ها", run: () => toggleGif(content).catch((e) => alertDialog(errText(e))) }
            : { icon: "plus", label: "ذخیره‌ی گیف", run: () => toggleGif(content).then(() => toast("به گیف‌ها اضافه شد"), (e) => alertDialog(errText(e))) }),
          canPin && { icon: "pin", label: pinned ? "برداشتن سنجاق" : "سنجاق", run: () => togglePin(room, ev.getId()!).catch((e) => alertDialog(errText(e))) },
          mine && EDITABLE.includes(content.msgtype ?? "") && { icon: "edit", label: "ویرایش", run: () => actions.edit(ev) },
          mine && { icon: "info", label: "دیده‌شده توسط", run: () => actions.info(ev) },
          reacted && { icon: "smile", label: "واکنش‌ها", run: () => actions.reactions(ev) },
          actions.select && { icon: "select", label: "انتخاب", run: () => actions.select!(ev) },
          !mine && ev.getId() && { icon: "flag", label: "گزارش", run: () => void report(room, ev) },
          canDelete && { icon: "trash", label: "حذف", danger: true, run: remove },
        ]} />
      )}
      {/* portal: .msg-actions fades out when the pointer leaves the message */}
      {fullPicker && createPortal(
        <div className="react-picker"><EmojiPanel onEmoji={(k) => { toggleReaction(room, ev, k); setFullPicker(false); }} onClose={() => setFullPicker(false)} /></div>,
        document.body)}
    </div>
  );
}

type MenuItem = { icon: IconName; label: string; run: () => unknown; danger?: boolean };

/** Telegram-style message menu: quick reactions + actions. A popup at the touch point, a bottom sheet on narrow screens (CSS). */
function MsgMenu({ x, y, items, onReact, onMore, onClose }: { x: number; y: number; items: (MenuItem | false | "" | undefined)[];
  onReact: (k: string) => void; onMore: () => void; onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const [closing, close] = useDismiss(onClose, 160);
  const [pos, setPos] = useState<{ left: number; top: number }>();
  useEffect(() => { // keep it on screen
    const r = ref.current!.getBoundingClientRect();
    setPos({ left: Math.max(8, Math.min(x - r.width / 2, innerWidth - r.width - 8)), top: Math.max(8, Math.min(y, innerHeight - r.height - 8)) });
    ref.current!.focus({ preventScroll: true });
  }, [x, y]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.preventDefault(); close(); } };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, [close]);
  const act = (fn: () => unknown) => { onClose(); fn(); };
  const backdrop = useBackdropHold(onClose, ".msg", close); // holding another message switches the menu to it
  return createPortal(
    <div className={"chat-menu-backdrop msg-menu-backdrop" + (closing ? " closing" : "")} {...backdrop}>
      <div className="chat-menu msg-menu" role="menu" aria-label="گزینه‌های پیام" tabIndex={-1} ref={ref} style={pos ?? { left: x, top: y, visibility: "hidden" }} onClick={(e) => e.stopPropagation()}>
        <div className="quick-react">
          {QUICK.map((k) => <button key={k} role="menuitem" onClick={() => act(() => onReact(k))}>{k}</button>)}
          <button className="more" role="menuitem" title="اموجی‌های بیشتر" aria-label="اموجی‌های بیشتر" onClick={() => act(onMore)}><Icon name="plus" size={18} /></button>
        </div>
        {items.map((it) => it && (
          <button key={it.label} role="menuitem" className={it.danger ? "danger-item" : undefined} onClick={() => act(it.run)}><Icon name={it.icon} /> {it.label}</button>
        ))}
      </div>
    </div>,
    document.body);
}

function Body({ ev, room, onView, onUser }: { ev: MatrixEvent; room: Room; onView: (ev: MatrixEvent) => void; onUser: (id: string) => void }) {
  if (ev.isDecryptionFailure()) return <p className="msg-text muted"><Icon name="lock" size={14} /> فعلاً رمزگشایی نشد. رمزنگاری را در تنظیمات باز کنید.</p>;
  if (ev.getType() === EventType.RoomMessageEncrypted) return <p className="msg-text muted">در حال رمزگشایی…</p>;
  const c = ev.getContent();
  if (M_POLL_START.matches(ev.getType())) return <PollBody ev={ev} room={room} />;
  const caption = c.msgtype !== "m.audio" && captionOf(c) && <p className="msg-text caption" dir={textDir(c.body)}>{linkify(c.body)}</p>;
  if (c.msgtype === "m.image" || ev.getType() === EventType.Sticker) return <><Image c={c} onView={() => onView(ev)} />{caption}</>;
  if (c.msgtype === "m.video" && isGif(c)) return <><GifBox c={c} />{caption}</>;
  if (c.msgtype === "m.video") return <><Video c={c} onView={() => onView(ev)} />{caption}</>;
  if (c.msgtype === "m.audio") return (
    <div onClick={(e) => e.stopPropagation()}>
      <AudioPlayer track={trackFor(ev)} />
    </div>
  );
  if (c.msgtype === "m.file") return <><FileRow c={c} />{caption}</>;
  if (c.msgtype === "m.location") return <LocationCard c={c} />;
  const emote = c.msgtype === "m.emote" ? `* ${senderName(ev)} ` : "";
  if (c.format === "org.matrix.custom.html" && typeof c.formatted_body === "string") {
    pillRoom = room;
    const html = DOMPurify.sanitize(c.formatted_body.replace(/<mx-reply>[\s\S]*?<\/mx-reply>/, ""), { FORBID_TAGS: ["style", "img"] });
    pillRoom = null;
    // the emote prefix holds a user-chosen display name: keep it out of innerHTML
    return <div className={"msg-text" + (emote ? " emote" : "")} dir={textDir(emote + stripReplyFallback(String(c.body ?? "")))}>{emote}<Html html={html} room={room} onUser={onUser} /></div>;
  }
  const text = emote + stripReplyFallback(String(c.body ?? ""));
  return <p dir={textDir(text)} className={"msg-text" + (/^\p{Extended_Pictographic}{1,3}$/u.test(text) ? " jumbo" : "")}>{linkify(text)}</p>;
}

/** Sanitized HTML; gives mention pills their avatars and opens them in-app. */
function Html({ html, room, onUser }: { html: string; room: Room; onUser: (id: string) => void }) {
  const ref = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    ref.current?.querySelectorAll<HTMLAnchorElement>("a.mention").forEach((a) => {
      const id = a.dataset.mxid!;
      const av = document.createElement("span");
      av.className = "mention-av";
      av.style.background = colorFor(id);
      av.textContent = [...(a.dataset.name ?? "").replace(/^[@#!]/, "")][0]?.toUpperCase() ?? "?";
      a.prepend(av);
      const mxc = id.startsWith("@") ? room.getMember(id)?.getMxcAvatarUrl() : linkedRoom(id)?.getMxcAvatarUrl();
      avatarUrl(mxc, 40)?.then((u) => {
        const img = document.createElement("img");
        img.src = u;
        img.alt = "";
        av.replaceChildren(img);
      }, () => {});
    });
    ref.current?.querySelectorAll("pre").forEach((pre) => {
      const b = document.createElement("button");
      b.className = "code-copy";
      b.title = b.ariaLabel = "کپی";
      b.innerHTML = iconSvg("copy", 16);
      pre.append(b);
    });
  }, [html, room]);
  const onClick = (e: MouseEvent) => {
    const t = e.target as HTMLElement;
    const pre = t.closest(".code-copy")?.parentElement;
    if (pre) {
      e.preventDefault();
      e.stopPropagation();
      void copyMessages((pre.querySelector("code") ?? pre).textContent ?? "");
      return;
    }
    const sp = t.closest("[data-mx-spoiler]");
    if (sp && !sp.classList.contains("revealed")) {
      e.preventDefault();
      e.stopPropagation();
      sp.classList.add("revealed");
      return;
    }
    const a = t.closest<HTMLAnchorElement>("a.mention");
    if (!a) {
      // other matrix.to links (events, ?via) and matrix: URIs open inside the app
      const l = t.closest<HTMLAnchorElement>("a[href]");
      if (l && parseMatrixLink(l.getAttribute("href")!)) { e.preventDefault(); e.stopPropagation(); handleIncomingLink(l.getAttribute("href")!); }
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    if (a.dataset.mxid!.startsWith("@")) onUser(a.dataset.mxid!);
    else openRoomPill(a);
  };
  return <span ref={ref} onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />;
}

const LINKIFY_RE = new RegExp(`(${LINK_SRC}|matrix:[^\\s<]+[^\\s<.,;:!?)"'])`, "gi");

function linkify(text: string) {
  return text.split(LINKIFY_RE).map((part, i) => {
    if (!(i % 2)) return part;
    if (part.startsWith("matrix:") && !parseMatrixLink(part)) return part;
    return <a key={i} href={linkHref(part)} target="_blank" rel="noreferrer noopener" onClick={parseMatrixLink(part) ? (e) => { e.preventDefault(); handleIncomingLink(part); } : undefined}>{part}</a>;
  });
}

type Content = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

function fit(info: Content | undefined, max = 320) {
  const w = info?.w ?? max, h = info?.h ?? max * 0.75;
  const s = Math.min(1, max / w, max / h);
  return { width: Math.round(w * s), height: Math.round(h * s) };
}

function Image({ c, onView }: { c: Content; onView: () => void }) {
  const size = fit(c.info);
  const thumb = usePromise(c.info?.thumbnail_file ? mediaUrl({ file: c.info.thumbnail_file }) : mediaUrl(c, { w: 640, h: 640 }));
  return (
    <button className="media-box" style={size} onClick={onView}>
      {thumb ? <img src={thumb} alt={c.body} /> : <span className="shimmer" />}
    </button>
  );
}

/** Sized from the sender's info, then from the file itself: info may be missing (older Panbeh) or wrong. */
function GifBox({ c }: { c: Content }) {
  const [size, setSize] = useState<{ w: number; h: number } | undefined>(c.info?.w && c.info?.h ? c.info : undefined);
  return <div className="media-box gif" style={fit(size)}><GifView c={c as Gif} onSize={(w, h) => w && h && setSize({ w, h })} /></div>;
}

/** The poster frame with ▶ and the length; the video itself downloads only once opened in the viewer. */
function Video({ c, onView }: { c: Content; onView: () => void }) {
  const thumb = usePromise(thumbFor(c));
  return (
    <button className="media-box video-box" style={fit(c.info)} onClick={onView} aria-label="پخش ویدیو">
      {thumb && <img src={thumb} alt="" />}
      <span className="play-badge"><Icon name="play" size={26} /></span>
      {c.info?.duration > 0 && <span className="dur-badge">{fmtDuration(c.info.duration)}</span>}
    </button>
  );
}

const R = 20, C = 2 * Math.PI * R;
/** A circle filling up with `f` (0-1) around an icon: uploads and downloads. */
export function ProgressRing({ f, label, onClick, icon = "close" }: { f: number; label: string; onClick?: () => void; icon?: IconName }) {
  const Tag = onClick ? "button" : "span"; // a span inside FileRow's button
  return (
    <Tag className="up-ring" onClick={onClick} aria-label={label}>
      <svg viewBox="0 0 48 48" width="48" height="48" aria-hidden>
        <circle cx="24" cy="24" r={R} className="up-track" />
        <circle cx="24" cy="24" r={R} className="up-arc" strokeDasharray={C} strokeDashoffset={C * (1 - f)} />
      </svg>
      <Icon name={icon} size={18} />
    </Tag>
  );
}

/** The toast after a save; the browser shows its own download. */
export function savedToast(t: SaveTarget | null) {
  if (t?.where === "downloads") toast("در پوشه‌ی دانلودها ذخیره شد");
  else if (t?.where === "picked") toast("ذخیره شد");
}

export function FileRow({ c }: { c: Content }) {
  const [prog, setProg] = useState<{ loaded: number; total: number } | null>(null);
  const busy = useRef<AbortController | null>(null); // also stops a second tap from starting another download
  const download = async () => {
    if (busy.current) { busy.current.abort(); return; } // tapping the ring cancels
    const ac = busy.current = new AbortController();
    let t: SaveTarget | null = null;
    try {
      t = await openSave(c.filename ?? c.body ?? "file", c.info?.mimetype ?? "");
      if (!t || ac.signal.aborted) return;
      setProg({ loaded: 0, total: c.info?.size ?? 0 });
      const url = await mediaUrl(c, undefined, (loaded, total) => setProg({ loaded, total }), ac.signal)!;
      await writeSave(t, url, ac.signal);
      savedToast(t);
    } catch (e) {
      abortSave(t);
      if (!ac.signal.aborted) alertDialog(errText(e));
    } finally {
      busy.current = null;
      setProg(null);
    }
  };
  const f = prog?.total ? Math.min(1, prog.loaded / prog.total) : 0;
  const state = !prog ? (c.info?.size ? formatSize(c.info.size) : "فایل")
    : prog.total ? `${num(Math.floor(f * 100))}٪ · ${formatSize(prog.loaded)} / ${formatSize(prog.total)}` : "در حال دانلود…";
  return (
    <button className="file-row" onClick={download}>
      {prog ? <ProgressRing f={f || 0.04} label="لغو دانلود" /> : <span className="file-icon"><Icon name="file" /></span>}
      <span><b>{c.filename ?? c.body}</b><small dir="auto">{state}</small></span>
    </button>
  );
}

function LocationCard({ c }: { c: Content }) {
  const loc = c["m.location"] ?? c["org.matrix.msc3488.location"];
  const g = parseGeoUri(loc?.uri ?? c.geo_uri);
  if (!g) return <p className="msg-text">{c.body}</p>;
  return (
    <div className="file-row location">
      <span className="file-icon"><Icon name="location" /></span>
      <span>
        <b dir="auto">{loc?.description || "موقعیت مکانی"}</b>
        <small><bdi dir="ltr">{g.lat.toFixed(5)}, {g.lon.toFixed(5)}</bdi>{g.acc ? ` · دقت ${num(Math.round(g.acc))} متر` : ""}</small>
        <span className="location-links">
          <a href={osmUrl(g.lat, g.lon)} target="_blank" rel="noreferrer noopener">OpenStreetMap</a>
          <a href={`https://www.google.com/maps?q=${g.lat},${g.lon}`} target="_blank" rel="noreferrer noopener">Google Maps</a>
        </span>
      </span>
    </div>
  );
}

function ReplyQuote({ room, id, onJump }: { room: Room; id: string; onJump?: (id: string) => void }) {
  const local = room.findEventById(id);
  // not loaded (older than the timeline): fetch it; loadEvent caches the promise, so this stays one request
  const ev = usePromise(local ? null : loadEvent(room, id)) ?? local;
  const go = onJump && ((e: { stopPropagation(): void }) => { e.stopPropagation(); onJump(id); });
  return (
    <div className="reply-quote" dir="auto" style={{ borderColor: ev ? colorFor(ev.getSender()!) : undefined }}
      role={go && "button"} tabIndex={go && 0} onClick={go} onKeyDown={(e) => e.key === "Enter" && go?.(e)}>
      <b dir="auto" style={{ color: ev ? colorFor(ev.getSender()!) : undefined }}>{ev ? senderName(ev) : "پاسخ"}</b>
      <span>{ev ? previewText(ev) : "پیام اصلی بارگذاری نشده"}</span>
    </div>
  );
}

function Ticks({ ev, room }: { ev: MatrixEvent; room: Room }) {
  const failed = ev.status === EventStatus.NOT_SENT;
  const sending = !failed && !!ev.status;
  const seen = failed || sending ? [] : seenBy(room, ev);
  const changed = useChange(failed ? 0 : sending ? 1 : seen.length ? 3 : 2); // clock → check → checks pop when they change
  if (failed) return <span className="tick failed" title="ارسال نشد">!</span>;
  if (sending) return <span className="tick"><Icon name="clock" size={13} /></span>;
  const title = !seen.length ? "ارسال شد"
    : isGroupChat(room) ? `دیده‌شده توسط ${num(seen.length)} نفر`
    : seen[0].ts ? `دیده‌شده ${stamp(seen[0].ts)}` : "دیده‌شده";
  return <span className={"tick" + (seen.length ? " read" : "") + (changed ? " changed" : "")} title={title}><Icon name={seen.length ? "checks" : "check"} size={15} /></span>;
}

function Reactions({ ev, room }: { ev: MatrixEvent; room: Room }) {
  const rel = room.relations.getChildEventsForEvent(ev.getId()!, RelationType.Annotation, EventType.Reaction);
  const list = rel?.getSortedAnnotationsByKey()?.filter(([, set]) => set.size > 0);
  // chips present when the message mounted don't animate (scrolling a row back into view); ones added later pop in
  const initial = useRef<Set<string>>(null);
  if (!initial.current) initial.current = new Set(list?.map(([k]) => k));
  if (!list?.length) return null;
  return (
    <div className="reactions">
      {list.map(([key, set]) => (
        <ReactionChip key={key} room={room} ev={ev} emoji={key} users={[...set]} added={!initial.current!.has(key)} />
      ))}
    </div>
  );
}

function ReactionChip({ room, ev, emoji, users, added }: { room: Room; ev: MatrixEvent; emoji: string; users: MatrixEvent[]; added: boolean }) {
  const bump = useChange(users.length);
  const mineToo = users.some((e) => e.getSender() === me());
  return (
    <button className={(mineToo ? "on" : "") + (added ? " added" : "")} title={users.map(senderName).join(", ")} onClick={() => toggleReaction(room, ev, emoji)}>
      {emoji} <span className={bump ? "bump" : undefined}>{num(users.length)}</span>
    </button>
  );
}

/** To the homeserver's admins, not the room's moderators. Score -100 = most offensive (the spec's scale). */
async function report(room: Room, ev: MatrixEvent) {
  const reason = await promptDialog("این پیام به مدیران سرور گزارش شود؟", { ok: "گزارش", danger: true, placeholder: "دلیل (اختیاری)" });
  if (reason === null) return;
  client.reportEvent(room.roomId, ev.getId()!, -100, reason).then(() => toast("گزارش برای مدیران سرور فرستاده شد"), (e) => alertDialog(errText(e)));
}

export function toggleReaction(room: Room, ev: MatrixEvent, key: string) {
  const rel = room.relations.getChildEventsForEvent(ev.getId()!, RelationType.Annotation, EventType.Reaction);
  const existing = [...(rel?.getAnnotationsBySender()?.[me()] ?? [])].find((e) => e.getRelation()?.key === key && !e.isRedacted());
  if (existing) return client.redactEvent(room.roomId, existing.getId()!);
  client.sendEvent(room.roomId, ev.threadRootId && ev.threadRootId !== ev.getId() ? ev.threadRootId : null, EventType.Reaction, {
    "m.relates_to": { rel_type: RelationType.Annotation, event_id: ev.getId()!, key },
  });
}
