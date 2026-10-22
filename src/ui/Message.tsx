import { rtl, t } from "../i18n.ts";
import { useEffect, useMemo, useRef, useState, type CSSProperties, type MouseEvent, type PointerEvent } from "react";
import { createPortal } from "react-dom";
import DOMPurify from "dompurify";
import { handleIncomingLink } from "../openTarget.ts";
import { matrixToLink, parseMatrixLink, viaServers } from "../uri.ts";
import { Direction, EventStatus, EventType, M_POLL_START, MatrixEventEvent, RelationType, RoomEvent, type MatrixEvent, type Room } from "matrix-js-sdk";
import { DecryptionFailureCode, EventShieldColour, EventShieldReason, type EventEncryptionInfo } from "matrix-js-sdk/lib/crypto-api/index.js";
import { addToMyPack, avatarUrl, inMyPack, BLURHASH, client, isCached, isSavedGif, loadEvent, mediaUrl, pinnedIds, prefetch, quickReaction, seenBy, toggleGif, togglePin } from "../matrix.ts";
import { abortSave, geoClick, isMetered, isNative, openSave, openSaved, rememberSave, writeSave, type SaveTarget } from "../native.ts";
import { chainOf, usePromise } from "../hooks.ts";
import { threadUnread } from "../chats.ts";
import { HASHTAG_SRC, LINK_SRC, blurhashDecode, clock, fmtDuration, isEmojiOnly, isGif, linkHref, neshanUrl, num, parseGeoUri, shouldAutoDl, stamp, textDir, type DlKind, type Usage } from "../logic.ts";
import { Icon, iconSvg, type IconName } from "../icons.tsx";
import { Avatar, colorFor, copyText, errText, formatSize, isGroupChat, me, senderMember, senderName, stripReplyFallback, toast, useChange, isVoice } from "./common.tsx";
import { AudioPlayer, NoteRing, stopPlayer, trackFor } from "./Voice.tsx";
import { PollBody } from "./Poll.tsx";
import { EmojiPanel, PreviewText, ReactionKey, tinted } from "./Emoji.tsx";
import { LinkPreview } from "./LinkPreview.tsx";
import { captionOf, EDITABLE } from "./Composer.tsx";
import { reducedMotion } from "./useDismiss.ts";
import { MessageMenu } from "./MessageMenu.tsx";
import { thumbFor } from "./Media.tsx";
import { alertDialog, confirmDialog, promptDialog } from "./dialog.tsx";
import { loadPrefs } from "./Settings.tsx";
import { emojiImg, emojiSplit, watch } from "../jsx/jsx-runtime.ts";
import { useTrustVersion } from "./Verify.tsx";

export type Actions = {
  reply: (ev: MatrixEvent) => void;
  edit: (ev: MatrixEvent) => void;
  thread?: (ev: MatrixEvent) => void; // absent inside a thread
  view: (ev: MatrixEvent) => void; // media viewer
  info: (ev: MatrixEvent) => void; // who has seen it
  reactions: (ev: MatrixEvent) => void; // who reacted with what
  profile: (userId: string) => void;
  forward: (ev: MatrixEvent | MatrixEvent[]) => void; // several: an album
  jump?: (eventId: string) => void; // reply quote → the original
  select?: (ev: MatrixEvent) => void; // toggles multi-select membership; absent inside a thread
  selection?: ReadonlySet<string>; // event ids; present = selecting
};

const DOUBLE_TAP = 250; // touch: how long a tap waits for a second one before opening the menu
const LONG_PRESS = 450, SWIPE_AT = 60, SWIPE_MAX = 80;
/** The last press was a finger. Touch works like Telegram: a tap on a message opens its menu, holding it selects it. */
let touched = false;
addEventListener("pointerdown", (e) => { touched = e.pointerType === "touch"; }, true);

/** Text a copy should give: body without the reply fallback; media only their caption. */
export function copyTextOf(ev: MatrixEvent): string {
  if (ev.isDecryptionFailure() || ev.getType() === EventType.RoomMessageEncrypted || M_POLL_START.matches(ev.getType())) return "";
  const c = ev.getContent();
  if (c.msgtype === "m.audio") return "";
  if (c.msgtype === "m.image" || c.msgtype === "m.video" || c.msgtype === "m.file" || ev.getType() === EventType.Sticker) return captionOf(c);
  return stripReplyFallback(String(c.body ?? ""));
}

/** The selection's text if it touches `el`. Emoji pictures give their emoji (Selection.toString skips images; a copy keeps
 *  their alt), and the time and avatars, which can't be selected, are left out. */
function selectedText(el: Node) {
  const s = getSelection();
  if (!s?.rangeCount || s.isCollapsed || !s.getRangeAt(0).intersectsNode(el)) return "";
  const r = s.getRangeAt(0), at = r.commonAncestorContainer;
  const box = document.createElement("div"); // innerText needs it laid out, to give the line breaks
  box.style.cssText = `position:fixed;left:-9999px;top:0;white-space:${getComputedStyle(at instanceof Element ? at : at.parentElement!).whiteSpace}`;
  box.append(r.cloneContents());
  box.querySelectorAll(".msg-meta, .avatar").forEach((n) => n.remove());
  box.querySelectorAll("img").forEach((i) => i.replaceWith(i.alt));
  document.body.append(box);
  const text = box.innerText.replace(/^\n+|\n+$/g, ""); // a triple-click takes the line's break too
  box.remove();
  return text;
}

export const copyMessages = (text: string) => copyText(text).then(() => toast(t("msg.copied")), (e) => alertDialog(errText(e)));

let pillRoom: Room | null = null; // the room being rendered; sanitize() is synchronous

// The Matrix spec's allowlist (client-server API, m.room.message formatting), not DOMPurify's permissive defaults: no
// style, forms, media, SVG or app classes from the sender. <img> is listed nowhere: the hook below replaces it.
const PURIFY = {
  ALLOWED_TAGS: ["font", "del", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "p", "a", "ul", "ol", "sup", "sub", "li", "b", "i", "u",
    "strong", "em", "s", "code", "hr", "br", "div", "table", "thead", "tbody", "tr", "th", "td", "caption", "pre", "span", "details", "summary"],
  // class is narrowed by a hook; data-mxc/data-code are the emote span the <img> hook makes
  ALLOWED_ATTR: ["href", "start", "alt", "title", "color", "data-mx-color", "data-mx-bg-color", "data-mx-spoiler", "class", "data-mxc", "data-code"],
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false,
  ALLOWED_URI_REGEXP: /^(?:https?|ftp|mailto|magnet|matrix):/i,
  // DOMPurify checks every other attribute's value against that regexp too: these aren't URLs
  ADD_URI_SAFE_ATTR: ["start", "color", "data-mx-color", "data-mx-bg-color", "data-mx-spoiler", "data-mxc", "data-code"],
};
const HEX = /^#[0-9a-f]{6}$/i;

// only our own classes: a code block's language and the emote span
DOMPurify.addHook("uponSanitizeAttribute", (n, data) => {
  if (data.attrName === "class") data.keepAttr = (n.tagName === "CODE" && /^language-[\w+-]+$/.test(data.attrValue)) || (n.tagName === "SPAN" && data.attrValue === "emote");
});

// links in formatted messages must not navigate the app away; matrix.to user/room links become pills
DOMPurify.addHook("afterSanitizeAttributes", (n) => {
  // the spec's colours are the only inline style a message gets, and only as #rrggbb
  const fg = n.getAttribute("data-mx-color") ?? n.getAttribute("color"), bg = n.getAttribute("data-mx-bg-color");
  n.removeAttribute("color");
  if (fg && HEX.test(fg)) (n as HTMLElement).style.color = fg;
  if (bg && HEX.test(bg)) (n as HTMLElement).style.backgroundColor = bg;
  if (n.tagName !== "A") return;
  n.setAttribute("target", "_blank");
  n.setAttribute("rel", "noreferrer noopener");
  const href = n.getAttribute("href") ?? "";
  const m = /^https:\/\/matrix\.to\/#\/([@#!][^/?]+)$/.exec(href);
  if (!m || !pillRoom) { if (href) n.setAttribute("title", href); return; } // where it really goes, whatever the text says
  let id = m[1];
  try { id = decodeURIComponent(id); } catch { /* keep as is */ }
  const name = id.startsWith("@") ? pillRoom.getMember(id)?.name : linkedRoom(id)?.name;
  n.className = "mention" + (id === me() ? " me" : "");
  n.setAttribute("data-mxid", id);
  n.setAttribute("data-name", name ?? id);
  n.textContent = name ?? id; // current name, whatever the sender typed
});

// custom emoji (MSC2545) become spans the Html effect fills from authenticated media; any other image, its alt text.
// Inserted after the <img> (still forbidden, so removed), the way DOMPurify itself keeps a removed element's content.
DOMPurify.addHook("uponSanitizeElement", (n, data) => {
  if (data.tagName !== "img") return;
  const img = n as Element, src = img.getAttribute("src") ?? "", alt = img.getAttribute("alt") ?? img.getAttribute("title") ?? "";
  let out: Node = img.ownerDocument.createTextNode(alt);
  if (img.hasAttribute("data-mx-emoticon") && src.startsWith("mxc://")) {
    const s = img.ownerDocument.createElement("span");
    s.className = "emote";
    s.setAttribute("data-mxc", src);
    s.setAttribute("data-code", alt);
    s.setAttribute("title", alt);
    s.textContent = alt; // until the image loads
    out = s;
  }
  img.parentNode?.insertBefore(out, img.nextSibling);
});
const EMOTES_ONLY = /^(\s*<span class="emote"[^>]*>[^<]*<\/span>){1,3}\s*$/;

/** Someone's custom emoji or sticker into mine. */
function grab(code: string, url: string, use: Usage, info?: Record<string, unknown>) {
  if (inMyPack(url, use)) return toast(use === "sticker" ? t("msg.inStickers") : t("msg.inEmoji"));
  void confirmDialog(t(use === "sticker" ? "msg.addStickerQ" : "msg.addEmojiQ", { code }), { ok: t("common.add") }).then((y) => {
    if (y) addToMyPack(code, { url, info }, use).then(() => toast(use === "sticker" ? t("msg.addedSticker") : t("msg.addedEmoji")), (e) => alertDialog(errText(e)));
  });
}

const linkedRoom = (id: string) =>
  id.startsWith("!") ? client.getRoom(id) : client.getRooms().find((r) => r.getCanonicalAlias() === id || r.getAltAliases().includes(id));

function openRoomPill(a: HTMLAnchorElement) {
  const r = linkedRoom(a.dataset.mxid!);
  if (r?.getMyMembership() === "join") location.hash = r.roomId;
  else window.open(a.href, "_blank", "noopener");
}

/** album: photos/videos shown as one grid; `ev` is the one that speaks for them (caption, time, menu) and the actions cover all. */
type Props = { ev: MatrixEvent; room: Room; first: boolean; last: boolean; actions: Actions; flash?: boolean; enter?: boolean; album?: MatrixEvent[] };

export function Message({ ev, room, first, last, actions, flash, enter, album }: Props) {
  const [fullPicker, setFullPicker] = useState<DOMRect | null>(null); // the button that opened it
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null); // touch screens have no hover: a tap (or right-click) opens this
  const [sel, setSel] = useState(""); // what was selected inside this message when its menu opened (the menu's focus can collapse it)
  const row = useRef<HTMLDivElement>(null);
  const g = useRef<{ id: number; x: number; y: number; timer: number; lock: boolean; px: number; crossed: boolean } | null>(null);
  const swallow = useRef(false); // the click that follows a swipe / hold
  const textSel = useRef(false); // touch: this press began while text in a selected message was selected; its tap only clears that
  const tap = useRef<{ x: number; y: number; timer: number } | null>(null); // touch: a first tap, waiting to see if it's a double
  useEffect(() => () => clearTimeout(tap.current?.timer), []);
  const openMenu = (x: number, y: number) => { setSel(selectedText(row.current!)); setMenu((m) => m ?? { x, y }); };
  const mine = ev.getSender() === me();
  const [deleting, setDeleting] = useState(false);
  const items = album ?? [ev];
  const remove = () => void confirmDialog(album ? t("msg.deleteN", { count: album.length }) : t("msg.deleteQ"), { danger: true }).then((y) => { // the bubble shrinks away, then the redaction removes the row
    if (!y) return;
    setDeleting(true);
    setTimeout(() => { Promise.all(items.map((e) => client.redactEvent(room.roomId, e.getId()!))).catch(() => setDeleting(false)); }, reducedMotion() ? 0 : 200);
  });
  const failed = ev.status === EventStatus.NOT_SENT;
  const group = isGroupChat(room);
  const content = ev.getContent();
  const media = !!album || content.msgtype === "m.image" || content.msgtype === "m.video" || ev.getType() === EventType.Sticker;
  const thread = actions.thread ? room.getThread(ev.getId()!) : null; // thread summaries only in the main timeline
  const replyTo = ev.getRelation()?.is_falling_back ? undefined : ev.replyEventId; // thread fallback isn't a real reply
  const canDelete = items.every((e) => e.getSender() === me() || room.currentState.maySendRedactionForEvent(e, me()));
  const canPin = !!ev.getId() && room.currentState.maySendStateEvent(EventType.RoomPinnedEvents, me());
  const pinned = pinnedIds(room).includes(ev.getId()!);
  const fwd = content["app.panbeh.forwarded"] as { sender: string; name?: string } | undefined;
  const selecting = !!actions.selection;
  const selected = !!actions.selection?.has(ev.getId()!);
  const select = () => items.forEach((e) => e.getId() && actions.select!(e));
  const forward = () => actions.forward(album ?? ev);
  const live = !failed && !ev.isDecryptionFailure() && ev.status == null && !!ev.getId(); // same bar as the action bar
  const swipeOk = live && !selecting;
  const text = copyTextOf(ev);
  const copyLink = () => void copyMessages(matrixToLink(room.roomId, ev.getId()!, viaServers(room.getJoinedMembers().map((m) => m.userId))));
  const mentioned = !mine && !!client.getPushActionsForEvent(ev)?.tweaks?.highlight;
  const reacted = items.some((e) => !!e.getId() && !!room.relations.getChildEventsForEvent(e.getId()!, RelationType.Annotation, EventType.Reaction)
    ?.getSortedAnnotationsByKey()?.some(([, set]) => set.size > 0));

  useEffect(() => { // unselected: its text can't be selected any more, nor stay so
    const s = getSelection();
    if (!selected && s?.anchorNode && row.current?.contains(s.anchorNode)) s.removeAllRanges();
  }, [selected]);

  const setX = (px: number) => { row.current!.style.setProperty("--sx", `${-px}px`); row.current!.style.setProperty("--sp", String(Math.min(1, px / SWIPE_AT))); };
  const end = (cancel: boolean) => {
    if (swallow.current) setTimeout(() => { swallow.current = false; }, 350); // eat only the click this finger-up brings
    const s = g.current;
    g.current = null;
    if (!s) return;
    clearTimeout(s.timer);
    if (s.lock) {
      row.current!.classList.add("snap"); // animate back
      setX(0);
      setTimeout(() => row.current?.classList.remove("snap", "swiping"), 220);
      if (!cancel && s.px >= SWIPE_AT) actions.reply(ev);
    }
  };
  // swipe LEFT to reply for both own and others' messages; rows stay physically LTR so -x is left
  const inside = (e: { target: EventTarget }) => row.current!.contains(e.target as Node); // portals (menu, picker) bubble through React
  /** A tap / click on the message itself, not on a link, button or media. */
  const onBody = (e: MouseEvent) => live && inside(e) && !(e.target as HTMLElement).closest("a,button,input,video,audio,[role=button],[data-mx-spoiler]");
  /** Double-tap / double-click: the reaction chosen in settings. */
  const quick = () => { const q = quickReaction(); if (!q.off) void toggleReaction(room, ev, q.key, q.shortcode); };
  const down = (e: PointerEvent) => {
    const s = getSelection();
    textSel.current = !!s && !s.isCollapsed && !!s.anchorNode?.parentElement?.closest(".msg.selected");
    if (!inside(e) || e.pointerType !== "touch" || !live || selecting || (e.target as HTMLElement).closest("input,video,audio")) return;
    const { clientX: x, clientY: y } = e;
    clearTimeout(g.current?.timer);
    g.current = { id: e.pointerId, x, y, lock: false, px: 0, crossed: false, timer: window.setTimeout(() => {
      g.current = null; // held: no swipe after this
      swallow.current = true;
      navigator.vibrate?.(15);
      if (actions.select) select(); else openMenu(x, y); // a thread has no selection: its hold still opens the menu
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
      onContextMenu={(e) => {
        if (!inside(e)) return;
        // Android's own long-press: our hold timer has it. Except on a selected message's text: that's where the system's
        // text selection starts (Telegram's), with its own Copy and Select all.
        if (touched) return void (selected && (e.target as HTMLElement).closest(".msg-text") || e.preventDefault());
        if (!live || selecting) return;
        e.preventDefault();
        openMenu(e.clientX, e.clientY);
      }}
      onClickCapture={(e) => {
        if (!inside(e)) return;
        if (swallow.current) { e.preventDefault(); e.stopPropagation(); swallow.current = false; } // first: a hold that just selected
        else if (selecting) { // taps only toggle while selecting; one that ends a text selection does just that
          e.preventDefault(); e.stopPropagation();
          if (textSel.current) getSelection()?.removeAllRanges(); else select();
        }
      }}
      onClick={(e) => { // touch: a tap anywhere on the row opens the menu (Telegram's), unless on a link, button or media; two react
        if (!touched || !onBody(e)) return;
        const { clientX: x, clientY: y } = e, p = tap.current;
        if (quickReaction().off) return openMenu(x, y);
        clearTimeout(p?.timer);
        tap.current = null;
        if (p && Math.hypot(x - p.x, y - p.y) < 30) return quick();
        tap.current = { x, y, timer: window.setTimeout(() => { tap.current = null; openMenu(x, y); }, DOUBLE_TAP) };
      }}
      onMouseDown={(e) => { if (e.detail === 2 && !touched && !selecting && onBody(e) && !quickReaction().off) e.preventDefault(); }} // the double-click reacts: no word selected
      onDoubleClick={(e) => { if (!touched && !selecting && onBody(e)) quick(); }}>
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
              {t("msg.forwardedFrom")} <b dir="auto">{room.getMember(fwd.sender)?.name ?? fwd.name ?? fwd.sender}</b>
            </button>
          )}
          {replyTo && <ReplyQuote room={room} id={replyTo} onJump={actions.jump} />}
          <Body ev={ev} room={room} onView={actions.view} onUser={actions.profile} album={album} />
          <LinkPreview ev={ev} />
          <span className="msg-meta" dir={rtl ? "rtl" : "ltr"}>
            {pinned && <Icon name="pin" size={12} />}
            {ev.replacingEvent() && <span>{t("msg.edited")}</span>}
            {ev.isEncrypted() && <Shield ev={ev} onVerify={actions.profile} />}
            <span>{clock(ev.getTs())}</span>
            {mine && <Ticks ev={ev} room={room} />}
          </span>
        </div>
        {items.map((e) => <Reactions key={e.getTxnId() ?? e.getId()} ev={e} room={room} />)}
        {thread && thread.length > 0 && (
          <button className="thread-link" onClick={() => actions.thread?.(ev)}>
            <Icon name="thread" size={15} /> {t("thread.replies", { count: thread.length })}
            {threadUnread(thread, undefined, true) > 0 && <span className="badge">{num(threadUnread(thread, undefined, true))}</span>}
          </button>
        )}
      </div>
      {failed && (
        <div className="msg-actions">
          <button title={t("msg.resend")} aria-label={t("msg.resend")} onClick={() => client.resendEvent(ev, room).catch(() => {})}><Icon name="send" size={17} /></button>
          <button title={t("common.delete")} aria-label={t("common.delete")} onClick={() => client.cancelPendingEvent(ev)}><Icon name="trash" size={17} /></button>
        </div>
      )}
      {menu && (
        <MessageMenu {...menu} onClose={() => setMenu(null)} mine={[...new Set(myReactions(room, ev).map((e) => e.getRelation()!.key!))]}
          onReact={(k) => toggleReaction(room, ev, k)} onMore={setFullPicker} items={[
          { icon: "reply", label: t("msg.reply"), run: () => actions.reply(ev) },
          sel && { icon: "copy", label: t("msg.copySelection"), run: () => void copyMessages(sel) },
          text && { icon: "copy", label: t("common.copy"), run: () => void copyMessages(text) },
          ev.getId() && { icon: "link", label: t("common.copyLink"), run: copyLink },
          !M_POLL_START.matches(ev.getType()) && { icon: "forward", label: t("msg.forward"), run: forward },
          actions.thread && { icon: "thread", label: t("msg.replyThread"), run: () => actions.thread!(ev) },
          ev.getType() === EventType.Sticker && content.url?.startsWith("mxc://") && !inMyPack(content.url, "sticker") &&
            { icon: "plus", label: t("msg.addToMyStickers"), run: () => grab(content.body || "sticker", content.url, "sticker", content.info) },
          isGif(content) && (isSavedGif(content)
            ? { icon: "close", label: t("msg.removeGif"), run: () => toggleGif(content).catch((e) => alertDialog(errText(e))) }
            : { icon: "plus", label: t("msg.saveGif"), run: () => toggleGif(content).then(() => toast(t("msg.gifAdded")), (e) => alertDialog(errText(e))) }),
          canPin && { icon: "pin", label: pinned ? t("msg.unpin") : t("msg.pin"), run: () => togglePin(room, ev.getId()!).catch((e) => alertDialog(errText(e))) },
          mine && EDITABLE.includes(content.msgtype ?? "") && { icon: "edit", label: t("msg.edit"), run: () => actions.edit(ev) },
          mine && { icon: "info", label: t("msg.seenBy"), run: () => actions.info(ev) },
          reacted && { icon: "smile", label: t("reactions.title"), run: () => actions.reactions(ev) },
          actions.select && !touched && { icon: "select", label: t("msg.select"), run: select }, // touch selects by holding
          !mine && ev.getId() && { icon: "flag", label: t("msg.report"), run: () => void report(room, ev) },
          canDelete && { icon: "trash", label: t("common.delete"), danger: true, run: remove },
        ]} />
      )}
      {fullPicker && createPortal(
        <div className="react-picker" style={pickerAt(fullPicker)}>
          <EmojiPanel room={room} onEmoji={(k, custom) => { toggleReaction(room, ev, custom?.img.url ?? k, custom && k); setFullPicker(null); }} onClose={() => setFullPicker(null)} />
        </div>,
        document.body)}
    </div>
  );
}

/** Whether media of `kind` downloads by itself in this chat, by the auto-download rules of the network we're on. */
const autoDl = (room: Room, kind: DlKind, c: Content) => {
  const p = loadPrefs().autoDl;
  return shouldAutoDl(isMetered() ? p.mobile : p.wifi, kind, !isGroupChat(room), c.info?.size);
};

function Body({ ev, room, onView, onUser, album }: { ev: MatrixEvent; room: Room; onView: (ev: MatrixEvent) => void; onUser: (id: string) => void; album?: MatrixEvent[] }) {
  useEffect(() => { // auto-download: videos, files and voice go into the media cache, so opening them needs no network
    const c: Content = ev.getContent();
    const kind: DlKind | null = c.msgtype === "m.video" ? (isGif(c) ? null : "videos") : c.msgtype === "m.file" ? "files"
      : c.msgtype === "m.audio" ? (isVoice(c) ? "voice" : "files") : null;
    if (kind && autoDl(room, kind, c)) prefetch(c);
  }, [ev, room]);
  if (ev.isDecryptionFailure()) return <p className="msg-text muted"><Icon name="lock" size={14} /> {t(FAILURE_TEXT[ev.decryptionFailureReason!] ?? "msg.undecryptable")}</p>;
  if (ev.getType() === EventType.RoomMessageEncrypted) return <p className="msg-text muted">{t("preview.decrypting")}</p>;
  const c = ev.getContent();
  if (M_POLL_START.matches(ev.getType())) return <PollBody ev={ev} room={room} />;
  const rich = c.format === "org.matrix.custom.html" && typeof c.formatted_body === "string" ? cleanHtml(c.formatted_body, room) : null;
  const caption = c.msgtype !== "m.audio" && captionOf(c) && (rich
    ? <div className="msg-text caption" dir={textDir(c.body)}><Html html={rich} room={room} onUser={onUser} /></div>
    : <p className="msg-text caption" dir={textDir(c.body)}>{linkify(c.body)}</p>);
  if (album) return <><AlbumGrid evs={album} room={room} onView={onView} />{caption}</>;
  if (c.msgtype === "m.image" || ev.getType() === EventType.Sticker) return <><Image c={c} onView={() => onView(ev)} auto={ev.getType() === EventType.Sticker || autoDl(room, isGif(c) ? "gifs" : "photos", c)} />{caption}</>;
  if (c.msgtype === "m.video" && c["app.panbeh.video_note"]) return <VideoNote c={c} room={room} />;
  if (c.msgtype === "m.video" && isGif(c)) return <><GifBox c={c} auto={autoDl(room, "gifs", c)} />{caption}</>;
  if (c.msgtype === "m.video") return <><Video c={c} onView={() => onView(ev)} />{caption}</>;
  if (c.msgtype === "m.audio") return (
    <div onClick={(e) => e.stopPropagation()}>
      <AudioPlayer track={trackFor(ev)} />
    </div>
  );
  if (c.msgtype === "m.file") return <><FileRow c={c} />{caption}</>;
  if (c.msgtype === "m.location") return <LocationCard c={c} />;
  const emote = c.msgtype === "m.emote" ? `* ${senderName(ev)} ` : "";
  if (rich !== null) {
    const html = rich, jumbo = !emote && EMOTES_ONLY.test(html);
    // the emote prefix holds a user-chosen display name: keep it out of innerHTML
    return <div className={"msg-text" + (emote ? " emote" : jumbo ? " jumbo" : "")} dir={textDir(emote + stripReplyFallback(String(c.body ?? "")))}>{emote}<Html html={html} room={room} onUser={onUser} big={jumbo} /></div>;
  }
  const text = emote + stripReplyFallback(String(c.body ?? ""));
  return <p dir={textDir(text)} className={"msg-text" + (isEmojiOnly(text) ? " jumbo" : "")}>{linkify(text)}</p>;
}

/** Messages refused for who sent them (Settings › Privacy › only verified devices), not for a missing key. */
const FAILURE_TEXT: Partial<Record<DecryptionFailureCode, "msg.unsignedDevice" | "msg.identityChanged">> = {
  [DecryptionFailureCode.UNSIGNED_SENDER_DEVICE]: "msg.unsignedDevice",
  [DecryptionFailureCode.UNKNOWN_SENDER_DEVICE]: "msg.unsignedDevice",
  [DecryptionFailureCode.SENDER_IDENTITY_PREVIOUSLY_VERIFIED]: "msg.identityChanged",
};

const SHIELD_TEXT = {
  [EventShieldReason.UNVERIFIED_IDENTITY]: "shield.unverified",
  [EventShieldReason.UNSIGNED_DEVICE]: "shield.unsigned",
  [EventShieldReason.UNKNOWN_DEVICE]: "shield.unknownDevice",
  [EventShieldReason.AUTHENTICITY_NOT_GUARANTEED]: "shield.authenticity",
  [EventShieldReason.VERIFICATION_VIOLATION]: "shield.violation",
  [EventShieldReason.MISMATCHED_SENDER]: "shield.mismatch",
} as const;

/** A warning on an encrypted message whose sender or device we can't vouch for; nothing at all when everything checks out.
 *  Tapping says why, and offers to verify the sender when that would fix it. */
function Shield({ ev, onVerify }: { ev: MatrixEvent; onVerify: (userId: string) => void }) {
  const [info, setInfo] = useState<EventEncryptionInfo | null>(null);
  const ver = useTrustVersion();
  useEffect(() => {
    const check = () => { client.getCrypto()?.getEncryptionInfoForEvent(ev).then(setInfo, () => {}); };
    check();
    ev.on(MatrixEventEvent.Decrypted, check);
    return () => { ev.off(MatrixEventEvent.Decrypted, check); };
  }, [ev, ver]);
  if (!info || info.shieldColour === EventShieldColour.NONE) return null;
  const why = t((info.shieldReason !== null && SHIELD_TEXT[info.shieldReason as keyof typeof SHIELD_TEXT]) || "shield.other");
  const sender = ev.getSender()!, red = info.shieldColour === EventShieldColour.RED;
  const open = (e: MouseEvent) => {
    e.stopPropagation();
    if (!red || sender === me()) return void alertDialog(why);
    void confirmDialog(why, { ok: t("trust.verify") }).then((y) => { if (y) onVerify(sender); });
  };
  return <button className={"shield" + (red ? "" : " grey")} title={why} aria-label={why} onClick={open}><Icon name="shield" size={13} /></button>;
}

/** A formatted_body made safe to show, its reply fallback dropped; pills resolve against `room`. */
function cleanHtml(html: string, room: Room) {
  pillRoom = room;
  try { return tagHtml(DOMPurify.sanitize(html.replace(/<mx-reply>[\s\S]*?<\/mx-reply>/, ""), PURIFY)); }
  finally { pillRoom = null; }
}

/** Opens search for a hashtag: Room listens for this, as for Ctrl+F. */
const openHashtag = (tag: string) => dispatchEvent(new CustomEvent("panbeh-search", { detail: tag }));

/** Hashtags in sanitized HTML's text (not in links or code) become the same buttons as in plain text. */
function tagHtml(html: string) {
  if (!html.includes("#")) return html;
  const t = document.createElement("template");
  t.innerHTML = html;
  const walk = document.createTreeWalker(t.content, NodeFilter.SHOW_TEXT), texts: Text[] = [];
  while (walk.nextNode()) if (!walk.currentNode.parentElement?.closest("a, code, pre")) texts.push(walk.currentNode as Text);
  const re = new RegExp(`(${HASHTAG_SRC})`, "u");
  for (const n of texts) {
    const parts = n.data.split(re);
    if (parts.length < 2) continue;
    n.replaceWith(...parts.map((p, i) => {
      if (!(i % 2)) return p;
      const b = document.createElement("button");
      b.type = "button";
      b.className = "hashtag";
      b.textContent = p;
      return b;
    }));
  }
  return t.innerHTML;
}

/** Sanitized HTML; gives mention pills their avatars and opens them in-app. */
/** Message HTML with its emoji as Apple's pictures (the JSX runtime's job everywhere else); code stays as typed. */
function emojiHtml(html: string, big: boolean) {
  if (emojiSplit(html).length === 1) return html;
  const t = document.createElement("template");
  t.innerHTML = html;
  const w = document.createTreeWalker(t.content, NodeFilter.SHOW_TEXT), texts: Text[] = [];
  while (w.nextNode()) texts.push(w.currentNode as Text);
  for (const n of texts) {
    const parts = emojiSplit(n.data);
    if (parts.length > 1 && !n.parentElement?.closest("pre, code")) n.replaceWith(...parts.map((p, i) => (i % 2 ? emojiImg(p, big) : p)));
  }
  return t.innerHTML;
}

function Html({ html, room, onUser, big = false }: { html: string; room: Room; onUser: (id: string) => void; big?: boolean }) {
  const ref = useRef<HTMLSpanElement>(null);
  const shown = useMemo(() => emojiHtml(html, big), [html, big]);
  useEffect(() => ref.current?.querySelectorAll<HTMLImageElement>("img.emoji").forEach(watch), [shown]);
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
    ref.current?.querySelectorAll<HTMLElement>('span.emote[data-mxc^="mxc://"]').forEach((s) => {
      mediaUrl({ url: s.dataset.mxc! })?.then(async (u) => {
        const img = document.createElement("img");
        img.src = u;
        img.alt = s.dataset.code ?? "";
        img.draggable = false;
        if (await tinted(s.dataset.mxc!, u)) {
          img.className = "tint";
          img.style.setProperty("--src", `url("${u}")`);
        }
        s.replaceChildren(img);
      }, () => {});
    });
    ref.current?.querySelectorAll("pre").forEach((pre) => {
      const b = document.createElement("button");
      b.className = "code-copy";
      b.title = b.ariaLabel = t("common.copy");
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
    const em = t.closest<HTMLElement>('span.emote[data-mxc^="mxc://"]');
    if (em) {
      e.preventDefault();
      e.stopPropagation();
      grab(em.dataset.code ?? "", em.dataset.mxc!, "emoticon");
      return;
    }
    const sp = t.closest("[data-mx-spoiler]");
    if (sp && !sp.classList.contains("revealed")) {
      e.preventDefault();
      e.stopPropagation();
      sp.classList.add("revealed");
      return;
    }
    const tag = t.closest("button.hashtag");
    if (tag) {
      e.preventDefault();
      e.stopPropagation();
      openHashtag(tag.textContent!);
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
  return <span ref={ref} onClick={onClick} dangerouslySetInnerHTML={{ __html: shown }} />;
}

const LINKIFY_RE = new RegExp(`(${LINK_SRC}|${HASHTAG_SRC}|matrix:[^\\s<]+[^\\s<.,;:!?)"'])`, "giu");

function linkify(text: string) {
  return text.split(LINKIFY_RE).map((part, i) => {
    if (!(i % 2)) return part;
    if (part.startsWith("#")) return <button key={i} type="button" className="hashtag" onClick={(e) => { e.stopPropagation(); openHashtag(part); }}>{part}</button>;
    if (part.startsWith("matrix:") && !parseMatrixLink(part)) return part;
    return <a key={i} href={linkHref(part)} target="_blank" rel="noreferrer noopener" onClick={parseMatrixLink(part) ? (e) => { e.preventDefault(); handleIncomingLink(part); } : undefined}>{part}</a>;
  });
}

type Content = Record<string, any>;

function fit(info: Content | undefined, max = 320) {
  const w = info?.w ?? max, h = info?.h ?? max * 0.75;
  const s = Math.min(1, max / w, max / h);
  return { width: Math.round(w * s), height: Math.round(h * s) };
}

/** A download gated by auto-download: runs by itself when `auto` or already on the device, otherwise on start().
 *  A user-started one reports progress and can be cancelled; a failed one goes back to waiting. */
function useMedia(c: Content, thumb: { w: number; h: number } | undefined, auto: boolean) {
  const [go, setGo] = useState(auto);
  const [prog, setProg] = useState<number | null>(null);
  const ac = useRef<AbortController | null>(null);
  useEffect(() => { if (!go) void isCached(c, thumb).then((y) => { if (y) setGo(true); }); }, []);
  const p = useMemo(() => {
    if (!go) return null;
    if (auto) return mediaUrl(c, thumb);
    ac.current = new AbortController();
    return mediaUrl(c, thumb, (loaded, total) => setProg(total ? loaded / total : 0), ac.current.signal);
  }, [go]);
  useEffect(() => { p?.catch(() => { setGo(false); setProg(null); }); }, [p]);
  const url = usePromise(p);
  return { url, idle: !go, prog: go && !url ? prog : null, start: () => setGo(true), cancel: () => ac.current?.abort() };
}

const blurs = new Map<string, string>();
/** A blurhash as a 32×32 image URL; null if there's none or it's invalid. */
function blurUrl(hash: unknown) {
  if (typeof hash !== "string") return null;
  let u = blurs.get(hash);
  if (u === undefined) {
    const px = blurhashDecode(hash, 32, 32);
    u = "";
    if (px) {
      const cv = Object.assign(document.createElement("canvas"), { width: 32, height: 32 });
      cv.getContext("2d")!.putImageData(new ImageData(px as Uint8ClampedArray<ArrayBuffer>, 32, 32), 0, 0);
      u = cv.toDataURL();
    }
    blurs.set(hash, u);
  }
  return u || null;
}

/** Media not downloaded yet: its blurhash (or a tiny server thumbnail when unencrypted), its size, and download / cancel. */
function Pending({ c, dl, label, noBg }: { c: Content; dl: ReturnType<typeof useMedia>; label?: string; noBg?: boolean }) {
  const blur = noBg ? null : blurUrl(c.info?.[BLURHASH]);
  const tinyOf = noBg ? null : c.info?.thumbnail_url ? { url: c.info.thumbnail_url } : c.msgtype === "m.image" && c.url ? c : null;
  const tiny = usePromise(blur || !tinyOf ? null : mediaUrl(tinyOf, { w: 32, h: 32 }));
  const bg = blur ?? tiny;
  return (
    <>
      {bg ? <img className="media-blur" src={bg} alt="" /> : !dl.idle && dl.prog === null && <span className="shimmer" />}
      {dl.idle ? (
        <button className="dl-btn" onClick={(e) => { e.stopPropagation(); dl.start(); }} aria-label={t("media.download")}>
          <Icon name="download" size={22} />
          {(label || c.info?.size > 0) && <small>{[label, c.info?.size > 0 && formatSize(c.info.size)].filter(Boolean).join(" · ")}</small>}
        </button>
      ) : dl.prog !== null && (
        <span className="dl-ring" onClick={(e) => e.stopPropagation()}><ProgressRing f={dl.prog || 0.04} label={t("media.cancelDownload")} onClick={dl.cancel} /></span>
      )}
    </>
  );
}

/** tile: a cell of an album grid, sized by the grid. */
function Image({ c, onView, auto, tile }: { c: Content; onView: () => void; auto: boolean; tile?: boolean }) {
  const size = tile ? undefined : fit(c.info);
  const own = c.info?.thumbnail_file; // the sender's thumbnail; else a server-side one (encrypted: the file itself)
  const dl = useMedia(own ? { file: own } : c, own ? undefined : { w: 640, h: 640 }, auto);
  if (!dl.url) return <div className="media-box" style={size}><Pending c={c} dl={dl} /></div>;
  return (
    <button className="media-box" style={size} onClick={onView}>
      <img src={dl.url} alt={c.body} />
    </button>
  );
}

/** Sized from the sender's info, then from the file itself: info may be missing (older Panbeh) or wrong. Plays by itself, looped and muted. */
function GifBox({ c, auto }: { c: Content; auto: boolean }) {
  const [size, setSize] = useState<{ w: number; h: number } | undefined>(c.info?.w && c.info?.h ? c.info : undefined);
  const dl = useMedia(c, undefined, auto);
  return (
    <div className="media-box gif" style={fit(size)}>
      {dl.url ? <video src={dl.url} autoPlay loop muted playsInline disablePictureInPicture onLoadedMetadata={(e) => setSize({ w: e.currentTarget.videoWidth, h: e.currentTarget.videoHeight })} />
        : <Pending c={c} dl={dl} label="GIF" />}
    </div>
  );
}

/** The poster frame with ▶ and the length; the video itself downloads once opened, or ahead of that by auto-download. */
function Video({ c, onView, tile }: { c: Content; onView: () => void; tile?: boolean }) {
  const thumb = usePromise(thumbFor(c));
  const poster = thumb ?? blurUrl(c.info?.[BLURHASH]);
  return (
    <button className="media-box video-box" style={tile ? undefined : fit(c.info)} onClick={onView} aria-label={t("media.playVideo")}>
      {poster && <img src={poster} alt="" />}
      <span className="play-badge"><Icon name="play" size={26} /></span>
      {c.info?.duration > 0 && <span className="dur-badge">{fmtDuration(c.info.duration)}</span>}
    </button>
  );
}

/** The round video message playing with sound, so starting another (or a voice message) stops it. */
let noteWithSound: { el: HTMLVideoElement; quiet: () => void } | null = null;

/** A round video message. Auto-downloaded ones play muted on a loop; a tap plays it from the start with sound (a ring shows
 *  how far), another tap pauses. Not downloaded: its poster, size and a download button; once it's in, it plays with sound. */
function VideoNote({ c, room }: { c: Content; room: Room }) {
  const auto = autoDl(room, "voice", c);
  const dl = useMedia(c, undefined, auto);
  const poster = usePromise(thumbFor(c)) ?? blurUrl(c.info?.[BLURHASH]);
  const v = useRef<HTMLVideoElement>(null);
  const [sound, setSound] = useState(false);
  const [paused, setPaused] = useState(false);
  const [pos, setPos] = useState(0);
  const wantSound = useRef(false);
  const dur = c.info?.duration > 0 ? c.info.duration : 0;
  const quiet = () => { // back to the silent loop (or stopped, if it wasn't auto-playing)
    const el = v.current;
    setSound(false); setPaused(false); setPos(0);
    if (noteWithSound?.el === el) noteWithSound = null;
    if (!el) return;
    el.muted = true; el.loop = true;
    if (auto) void el.play().catch(() => {}); else el.pause();
  };
  const loud = () => {
    const el = v.current!;
    if (noteWithSound && noteWithSound.el !== el) noteWithSound.quiet();
    stopPlayer();
    noteWithSound = { el, quiet };
    el.currentTime = 0; el.muted = false; el.loop = false;
    void el.play().catch(() => {});
    setSound(true); setPaused(false);
  };
  useEffect(() => { if (dl.url && wantSound.current) { wantSound.current = false; loud(); } }, [dl.url]);
  useEffect(() => () => { if (noteWithSound?.el === v.current) noteWithSound = null; }, []);
  const tap = (e: MouseEvent) => {
    e.stopPropagation();
    if (!dl.url) { if (dl.idle) { wantSound.current = true; dl.start(); } return; }
    const el = v.current!;
    if (!sound) loud();
    else if (el.paused) { void el.play(); setPaused(false); } else { el.pause(); setPaused(true); }
  };
  const left = sound && dur ? Math.max(0, dur - pos * 1000) : dur;
  return (
    <div className={"note" + (sound ? " sound" : "")} onClick={tap} role="button" tabIndex={0} aria-label={sound && !paused ? t("media.pause") : t("media.playNote")}
      onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); tap(e as unknown as MouseEvent); } }}>
      {dl.url ? <video ref={v} src={dl.url} poster={poster ?? undefined} autoPlay={auto} muted loop playsInline disablePictureInPicture
        onTimeUpdate={(e) => sound && setPos(e.currentTarget.currentTime)} onEnded={quiet} />
        : poster ? <img src={poster} alt="" /> : <span className="shimmer" />}
      {!dl.url && <Pending c={c} dl={dl} noBg={!!poster} />}
      {sound && <NoteRing className="note-ring" video={v} duration={dur} />}
      {sound && paused && <span className="play-badge"><Icon name="play" size={26} /></span>}
      {dl.url && <span className="dur-badge">{fmtDuration(left)}{!sound && <Icon name="mute" size={12} />}</span>}
    </div>
  );
}

/** Photos and videos sent together, as one grid: 2 side by side, 3 as one big and two small, 4 as 2×2, more in rows of 2 then 3. */
function AlbumGrid({ evs, room, onView }: { evs: MatrixEvent[]; room: Room; onView: (ev: MatrixEvent) => void }) {
  return (
    <div className={"album n" + Math.min(evs.length, 5)}>
      {evs.map((e, i) => {
        const c: Content = e.getContent(), key = e.getTxnId() ?? e.getId();
        return (
          <div key={key} className="album-cell" style={evs.length > 4 ? { gridColumn: `span ${albumSpan(i, evs.length)}` } : undefined}>
            {c.msgtype === "m.video" ? <Video c={c} onView={() => onView(e)} tile /> : <Image c={c} onView={() => onView(e)} auto={autoDl(room, "photos", c)} tile />}
          </div>
        );
      })}
    </div>
  );
}

/** Columns (of 6) cell `i` of an `n`-item album takes past 4 items: 2 on the first row, then rows of 3; the last row's cells widen to fill it. */
function albumSpan(i: number, n: number) {
  if (i < 2) return 3;
  const left = (n - 2) % 3 || 3;
  return i >= n - left ? 6 / left : 2;
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
export function savedToast(to: SaveTarget | null) {
  if (to?.where === "downloads") toast(t("save.downloads"));
  else if (to?.where === "picked") toast(t("save.saved"));
}

export function FileRow({ c }: { c: Content }) {
  const [prog, setProg] = useState<{ loaded: number; total: number } | null>(null);
  const busy = useRef<AbortController | null>(null); // also stops a second tap from starting another download
  const download = async () => {
    if (busy.current) { busy.current.abort(); return; } // tapping the ring cancels
    const ac = busy.current = new AbortController();
    const key = c.file?.url ?? c.url;
    let t: SaveTarget | null = null;
    try {
      if (key && await openSaved(key, c.info?.mimetype ?? "")) return; // saved before: open that copy
      t = await openSave(c.filename ?? c.body ?? "file", c.info?.mimetype ?? "");
      if (!t || ac.signal.aborted) return;
      setProg({ loaded: 0, total: c.info?.size ?? 0 });
      const url = await mediaUrl(c, undefined, (loaded, total) => setProg({ loaded, total }), ac.signal)!;
      await writeSave(t, url, ac.signal);
      if (key) rememberSave(key, t, url);
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
  const state = !prog ? (c.info?.size ? formatSize(c.info.size) : t("composer.file"))
    : prog.total ? `${t("poll.pct", { n: Math.floor(f * 100) })} · ${formatSize(prog.loaded)} / ${formatSize(prog.total)}` : t("media.downloading");
  return (
    <button className="file-row" onClick={download}>
      {prog ? <ProgressRing f={f || 0.04} label={t("media.cancelDownload")} /> : <span className="file-icon"><Icon name="file" /></span>}
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
        <b dir="auto">{loc?.description || t("loc.title")}</b>
        <small><bdi dir="ltr">{g.lat.toFixed(5)}, {g.lon.toFixed(5)}</bdi>{g.acc ? " · " + t("msg.locAccuracy", { count: Math.round(g.acc) }) : ""}</small>
        <span className="location-links">
          {isNative ? <a href={neshanUrl(g.lat, g.lon)} onClick={geoClick(g.lat, g.lon, loc?.description)}>{t("loc.openMap")}</a> : <>
            <a href={neshanUrl(g.lat, g.lon)} target="_blank" rel="noreferrer noopener">{t("loc.neshan")}</a>
            <a href={`https://www.google.com/maps?q=${g.lat},${g.lon}`} target="_blank" rel="noreferrer noopener">{t("loc.google")}</a>
          </>}
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
      <b dir="auto" style={{ color: ev ? colorFor(ev.getSender()!) : undefined }}>{ev ? senderName(ev) : t("msg.reply")}</b>
      <span>{ev ? <PreviewText ev={ev} /> : t("msg.originalNotLoaded")}</span>
    </div>
  );
}

export function Ticks({ ev, room }: { ev: MatrixEvent; room: Room }) {
  const failed = ev.status === EventStatus.NOT_SENT;
  const sending = !failed && !!ev.status;
  const seen = failed || sending ? [] : seenBy(room, ev);
  const changed = useChange(failed ? 0 : sending ? 1 : seen.length ? 3 : 2); // clock → check → checks pop when they change
  if (failed) return <span className="tick failed" title={t("upload.failed")}>!</span>;
  if (sending) return <span className="tick"><Icon name="clock" size={13} /></span>;
  const title = !seen.length ? t("tick.sent")
    : isGroupChat(room) ? t("tick.seenByN", { count: seen.length })
    : seen[0].ts ? t("tick.seenAt", { when: stamp(seen[0].ts) }) : t("tick.seen");
  return <span className={"tick" + (seen.length ? " read" : "") + (changed ? " changed" : "")} title={title}><Icon name={seen.length ? "checks" : "check"} size={15} /></span>;
}

// A message's reactions come after it, so a window opened around an old message (a jump, the unread divider) lacks the
// ones sent past its end until paging forwards reaches them, and its counts came up short. Those messages ask the server.
const fetchedReactions = new Map<string, MatrixEvent>(); // reaction id → our copy
const askedReactions = new Set<string>(); // message ids
async function fetchReactions(room: Room, ev: MatrixEvent) {
  const id = ev.getId()!, set = room.getUnfilteredTimelineSet(), tl = set.getTimelineForEvent(id);
  if (!id.startsWith("$") || !tl || chainOf(tl).at(-1) === room.getLiveTimeline() || askedReactions.has(id)) return;
  if (!askedReactions.size) { // our copies aren't in a timeline, so the SDK can't find them to redact: un-reacting left the chip
    client.on(RoomEvent.Timeline, (r, rm) => {
      const x = r.isRedaction() && rm ? fetchedReactions.get(r.getAssociatedId()!) : undefined;
      if (x && !x.isRedacted()) x.makeRedacted(r, rm!);
    });
  }
  askedReactions.add(id);
  const map = client.getEventMapper();
  try {
    let from: string | undefined;
    do { // encrypted reactions aren't m.reaction to the server: ask for every annotation
      const r = await client.fetchRelations(room.roomId, id, RelationType.Annotation, null, { dir: Direction.Backward, limit: 100, from });
      for (const raw of r.chunk) {
        if (fetchedReactions.has(raw.event_id)) continue;
        const e = map(raw);
        fetchedReactions.set(raw.event_id, e);
        room.relations.aggregateChildEvent(e, set); // the SDK skips ones it has, and waits for decryption
      }
      from = r.next_batch ?? undefined;
    } while (from);
  } catch (e) {
    askedReactions.delete(id); // try again when it's next shown
    console.warn("reactions fetch failed", e);
  }
}

function Reactions({ ev, room }: { ev: MatrixEvent; room: Room }) {
  useEffect(() => { void fetchReactions(room, ev); }, [room, ev]);
  const rel = room.relations.getChildEventsForEvent(ev.getId()!, RelationType.Annotation, EventType.Reaction);
  const list = rel?.getSortedAnnotationsByKey()?.filter(([, set]) => set.size > 0);
  // chips present when the message mounted don't animate (scrolling a row back into view); ones added later pop in
  const initial = useRef<Set<string>>(null);
  if (!initial.current) initial.current = new Set(list?.map(([k]) => k));
  // reacting grows the row: keep my new chip in view (above the composer), like Telegram
  const box = useRef<HTMLDivElement>(null);
  const mine = list?.filter(([, set]) => [...set].some((e) => e.getSender() === me())).length ?? 0;
  const mineBefore = useRef(mine);
  useEffect(() => {
    if (mine > mineBefore.current) box.current?.scrollIntoView({ block: "nearest" });
    mineBefore.current = mine;
  }, [mine]);
  if (!list?.length) return null;
  return (
    <div className="reactions" ref={box}>
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
    <button className={(mineToo ? "on" : "") + (added ? " added" : "")} title={users.map(senderName).join(", ")} onClick={() => toggleReaction(room, ev, emoji)}
      onContextMenu={emoji.startsWith("mxc://") ? (e) => { e.preventDefault(); e.stopPropagation(); grab(shortcodeOf(users[0]) || "emoji", emoji, "emoticon"); } : undefined}>
      <ReactionKey k={emoji} ev={users[0]} />{users.length > 1 && <> <span className={bump ? "bump" : undefined}>{num(users.length)}</span></>}
    </button>
  );
}

/** To the homeserver's admins, not the room's moderators. Score -100 = most offensive (the spec's scale). */
async function report(room: Room, ev: MatrixEvent) {
  const reason = await promptDialog(t("msg.reportQ"), { ok: t("msg.report"), danger: true, placeholder: t("msg.reportReason") });
  if (reason === null) return;
  client.reportEvent(room.roomId, ev.getId()!, -100, reason).then(() => toast(t("msg.reported")), (e) => alertDialog(errText(e)));
}

const shortcodeOf = (reaction?: MatrixEvent) => String(reaction?.getContent()["com.beeper.reaction.shortcode"] ?? "");

const myReactions = (room: Room, ev: MatrixEvent) => [...(room.relations.getChildEventsForEvent(ev.getId()!, RelationType.Annotation, EventType.Reaction)
  ?.getAnnotationsBySender()?.[me()] ?? [])].filter((e) => !e.isRedacted());

/** Where the full reaction picker goes: hanging down-left from the button that opened it, above it where it doesn't fit. */
export function pickerAt(r: DOMRect) {
  const w = Math.min(360, innerWidth - 32), h = Math.min(380, innerHeight - 16);
  const below = r.bottom + 6 + h <= innerHeight - 8;
  return { "--left": `${Math.max(8, Math.min(r.right - w, innerWidth - w - 8))}px`, "--top": `${below ? r.bottom + 6 : Math.max(8, r.top - 6 - h)}px` } as CSSProperties;
}

/** `shortcode`: a custom emoji's ":code:" (key is then its mxc), so other clients can name it. */
export function toggleReaction(room: Room, ev: MatrixEvent, key: string, shortcode?: string) {
  const existing = myReactions(room, ev).find((e) => e.getRelation()?.key === key);
  if (existing) return client.redactEvent(room.roomId, existing.getId()!);
  client.sendEvent(room.roomId, ev.threadRootId && ev.threadRootId !== ev.getId() ? ev.threadRootId : null, EventType.Reaction, {
    "m.relates_to": { rel_type: RelationType.Annotation, event_id: ev.getId()!, key },
    ...(shortcode && { "com.beeper.reaction.shortcode": shortcode }),
  });
}
