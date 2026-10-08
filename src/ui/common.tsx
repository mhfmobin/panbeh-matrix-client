import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { EventType, M_POLL_START, type MatrixEvent, type Room } from "matrix-js-sdk";
import { PollStartEvent } from "matrix-js-sdk/lib/extensible_events_v1/PollStartEvent.js";
import { avatarUrl, client, isDirect } from "../matrix.ts";
import { usePromise } from "../hooks.ts";
import { getCallNotificationExpiry, type IRTCNotificationContent } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { fmtDuration, HISTORY, isGif, isVideoOffer, JOIN_RULES, legacyOutcome, levelChanges, num, roleLabel, rtcOutcome, type CallEv, type CallOutcome } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { useDismiss } from "./useDismiss.ts";

const COLORS = ["#e17076", "#faa774", "#a695e7", "#7bc862", "#6ec9cb", "#65aadd", "#ee7aae"];
export const colorFor = (id: string) => COLORS[([...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 0) >>> 0) % COLORS.length];

export function Avatar({ mxc, name, id, size = 42 }: { mxc?: string | null; name: string; id: string; size?: number }) {
  const url = usePromise(avatarUrl(mxc, size * 2));
  const letter = [...name.replace(/^[@#!]/, "")][0]?.toUpperCase() ?? "?";
  return (
    <div className="avatar" style={{ width: size, height: size, fontSize: size * 0.42, background: url ? undefined : colorFor(id) }}>
      {url ? <img src={url} alt="" draggable={false} /> : letter}
    </div>
  );
}

/** The room's photo; a DM without one shows the other person's. Groups never borrow a member's photo:
 *  the SDK's fallback picks one for any room with ≤2 members, which then vanished when a third joined. */
export const roomAvatarMxc = (room: Room) =>
  room.getMxcAvatarUrl() ?? (isDirect(room) ? room.getAvatarFallbackMember()?.getMxcAvatarUrl() : undefined);

export const RoomAvatar = ({ room, size }: { room: Room; size?: number }) => (
  <Avatar mxc={roomAvatarMxc(room)} name={room.name} id={room.roomId} size={size} />
);

/** The sender's *current* profile (Telegram-style); ev.sender is a snapshot from when it was sent. */
export const senderMember = (ev: MatrixEvent) => client.getRoom(ev.getRoomId())?.getMember(ev.getSender()!) ?? ev.sender;
export const senderName = (ev: MatrixEvent) => senderMember(ev)?.name ?? ev.getSender() ?? "";

/** Three bouncing dots after "typing" text. */
export const Dots = () => <span className="dots" aria-hidden><i /><i /><i /></span>;

/** True for a moment after `value` changes (not on mount): drives one-shot "bump" animations. */
export function useChange(value: unknown, ms = 450) {
  const prev = useRef(value);
  const [on, setOn] = useState(false);
  useEffect(() => {
    if (prev.current === value) return;
    prev.current = value;
    setOn(true);
    const t = setTimeout(() => setOn(false), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return on;
}

/** Small transient message at the bottom of the screen. */
export function toast(text: string) {
  const el = Object.assign(document.createElement("div"), { className: "toast", textContent: text });
  el.setAttribute("role", "status");
  document.body.append(el);
  setTimeout(() => el.classList.add("closing"), 1550);
  setTimeout(() => el.remove(), 1800);
}

/** Clipboard write; the textarea fallback covers insecure origins and WebViews without the async API. */
export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const t = Object.assign(document.createElement("textarea"), { value: text });
    t.style.cssText = "position:fixed;opacity:0";
    document.body.append(t);
    t.select();
    const ok = document.execCommand("copy");
    t.remove();
    if (!ok) throw new Error("copy failed");
  }
}

/** Bidi-isolate a name inside a Farsi sentence so Latin names/punctuation don't reorder it. */
export const bdi = (s: string) => "\u2068" + s + "\u2069";

/** Farsi message for an SDK/fetch error. */
export function errText(e: unknown): string {
  const err = e as { errcode?: string; message?: string; name?: string };
  if (err?.errcode === "M_FORBIDDEN") return "نام کاربری یا رمز عبور اشتباه است.";
  // getUserMedia (calls, voice messages)
  if (err?.name === "NotAllowedError") return "اجازه‌ی دسترسی به میکروفون یا دوربین داده نشد. آن را از تنظیمات مرورگر یا دستگاه باز کنید.";
  if (err?.name === "NotFoundError") return "میکروفون یا دوربینی پیدا نشد.";
  const m = err?.message ?? String(e);
  return /fetch|network/i.test(m) ? "به سرور دسترسی نیست. نشانی و پورت را بررسی کنید." : m;
}

const cap = (c: Record<string, any>): string | undefined => (c.filename && c.body !== c.filename ? c.body : undefined); // eslint-disable-line @typescript-eslint/no-explicit-any

/** One-line text for list previews and reply quotes. */
export function previewText(ev: MatrixEvent): string {
  if (ev.isDecryptionFailure()) return "🔒 پیام رمزنگاری‌شده";
  if (ev.getType() === EventType.RoomMessageEncrypted) return "🔒 در حال رمزگشایی…";
  const c = ev.getContent();
  switch (c.msgtype) {
    case "m.image": return "🖼 " + (cap(c) ?? "عکس");
    case "m.video": return isGif(c) ? "🎞 " + (cap(c) ?? "گیف") : "🎬 " + (cap(c) ?? "ویدیو");
    case "m.audio": return isVoice(c) ? "🎤 پیام صوتی " + fmtDuration(audioDuration(c)) : "🎵 صدا";
    case "m.location": return "📍 موقعیت مکانی";
    case "m.file": return "📎 " + (cap(c) ?? c.filename ?? c.body ?? "فایل");
    case "m.emote": return `* ${senderName(ev)} ${c.body}`;
    case VERIFY_REQUEST: return "🔐 درخواست تأیید هویت";
  }
  if (ev.getType() === EventType.Sticker) return "استیکر";
  if (M_POLL_START.matches(ev.getType())) return "📊 " + (parsePoll(ev)?.question.text ?? "نظرسنجی");
  return stripReplyFallback(String(c.body ?? ""));
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Content = Record<string, any>;
export const isVoice = (c: Content) => !!(c["org.matrix.msc3245.voice"] ?? c["m.voice"]);
/** ms; 0 if unknown */
export const audioDuration = (c: Content): number => c.info?.duration ?? c["org.matrix.msc1767.audio"]?.duration ?? 0;

/** Poll start content (edit-aware, stable or unstable names); null if malformed. */
export function parsePoll(ev: MatrixEvent) {
  try { return new PollStartEvent({ type: ev.getType(), content: ev.getContent() as never }); } catch { return null; }
}

export const stripReplyFallback = (body: string) => body.replace(/^(> .*\n)+\n?/, "");

/** Text for state events worth showing as a centered pill; null = hide. */
/** In-chat verification starts with an m.room.message whose body is an English "your client doesn't support" fallback. */
export const VERIFY_REQUEST = "m.key.verification.request";

export function noticeText(ev: MatrixEvent): string | null {
  const who = bdi(senderName(ev));
  const c = ev.getContent(), prev = ev.getPrevContent();
  switch (ev.getType()) {
    case EventType.RoomMessage: return c.msgtype === VERIFY_REQUEST ? `${who} درخواست تأیید هویت فرستاد` : null;
    case EventType.RoomCreate: return `${who} گفتگو را ایجاد کرد`;
    case EventType.RoomName: return c.name ? `${who} نام گفتگو را به «${bdi(c.name)}» تغییر داد` : `${who} نام گفتگو را حذف کرد`;
    case EventType.RoomTopic: return `${who} موضوع را تغییر داد`;
    case EventType.RoomEncryption: return "پیام‌ها رمزنگاری سرتاسری شده‌اند";
    case EventType.RoomPinnedEvents: {
      const n = c.pinned?.length ?? 0, was = prev.pinned?.length ?? 0;
      return n > was ? `${who} پیامی را سنجاق کرد` : n < was ? `${who} سنجاق پیامی را برداشت` : null;
    }
    case EventType.RoomMember: {
      const target = bdi(c.displayname ?? ev.getStateKey() ?? "");
      const prevName = bdi(prev.displayname ?? c.displayname ?? ev.getStateKey() ?? "");
      if (c.membership === prev.membership) return null; // profile changes are noise
      switch (c.membership) {
        case "join": return `${target} پیوست`;
        case "invite": return `${who} ${target} را دعوت کرد`;
        case "knock": return `${target} درخواست عضویت داد`;
        case "leave":
          if (ev.getSender() === ev.getStateKey()) return prev.membership === "knock" ? `${prevName} درخواست عضویتش را پس گرفت` : `${prevName} خارج شد`;
          return prev.membership === "ban" ? `${who} مسدودیت ${prevName} را برداشت`
            : prev.membership === "knock" ? `${who} درخواست عضویت ${prevName} را رد کرد`
            : prev.membership === "invite" ? `${who} دعوت ${prevName} را لغو کرد`
            : `${who} ${prevName} را بیرون کرد`;
        case "ban": return `${who} ${prevName} را مسدود کرد`;
      }
      return null;
    }
    // room creation sets these too; only later changes are news
    case EventType.RoomPowerLevels: {
      if (!Object.keys(prev).length) return null; // room creation; v12 rooms start without `users` (creators are implicit)
      const [c1, ...more] = levelChanges(prev as never, c as never);
      if (!c1) return null;
      const name = bdi(client.getRoom(ev.getRoomId())?.getMember(c1.id)?.name ?? c1.id);
      return `${who} نقش ${name} را به «${roleLabel(c1.to) ?? "عضو"}» تغییر داد` + (more.length ? ` (و ${num(more.length)} نفر دیگر)` : "");
    }
    case EventType.RoomJoinRules:
      return prev.join_rule && c.join_rule !== prev.join_rule ? `${who} شیوه‌ی پیوستن را به «${JOIN_RULES[c.join_rule] ?? c.join_rule}» تغییر داد` : null;
    case EventType.RoomHistoryVisibility:
      return prev.history_visibility && c.history_visibility !== prev.history_visibility ? `${who} دسترسی به تاریخچه را به «${HISTORY[c.history_visibility] ?? c.history_visibility}» تغییر داد` : null;
    case EventType.RoomTombstone: return `${who} این گروه را ارتقا داد`;
    case EventType.RTCNotification: {
      const kind = c["m.call.intent"] === "video" ? "تماس تصویری" : "تماس صوتی";
      if (c.notification_type !== "ring") return `${who} ${kind} گروهی را شروع کرد`;
      return callLine(ev, who, kind, rtcOutcome(callEv(ev), getCallNotificationExpiry(c as IRTCNotificationContent, ev.getTs()), eventsAfter(ev)));
    }
    case EventType.CallInvite: return callLine(ev, who, isVideoOffer(c as { offer?: { sdp?: string } }) ? "تماس تصویری" : "تماس صوتی", legacyOutcome(callEv(ev), eventsAfter(ev)));
  }
  return null;
}

const callEv = (e: MatrixEvent): CallEv => ({ id: e.getId(), type: e.getType(), sender: e.getSender()!, ts: e.getTs(), content: e.getContent() });
/** The loaded events after `ev`, to see how its call went. */
function eventsAfter(ev: MatrixEvent) {
  const evs = client.getRoom(ev.getRoomId())?.getTimelineForEvent(ev.getId()!)?.getEvents() ?? [];
  return evs.slice(evs.indexOf(ev) + 1).map(callEv);
}

/** A 1:1 call's line in the timeline: who called, and how it went. */
function callLine(ev: MatrixEvent, who: string, kind: string, o: CallOutcome) {
  const mine = ev.getSender() === me();
  switch (o.state) {
    case "ended": return `${who} ${kind} گرفت · ${fmtDuration(o.duration!)}`;
    case "missed": return mine ? `${kind} بی‌پاسخ` : `${kind} از دست رفته از ${who}`;
    case "declined": return mine ? `${kind} رد شد` : `${kind} ${who} را رد کردید`;
    default: return `${who} ${kind} گرفت`;
  }
}

export const isGroupChat = (room: Room) => room.getJoinedMemberCount() > 2;

export const me = () => client.getSafeUserId();

export function formatSize(n: number) {
  const u = ["بایت", "کیلوبایت", "مگابایت", "گیگابایت"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${num(+n.toFixed(i ? 1 : 0))} ${u[i]}`;
}

/** Side sheet: settings, chat info, new chat, seen-by. */
export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const [closing, close] = useDismiss(onClose, 220);
  return (
    <div className={"sheet-backdrop" + (closing ? " closing" : "")} onClick={close} onKeyDown={(e) => e.key === "Escape" && close()}>
      <div className="sheet" onClick={(e) => e.stopPropagation()} role="dialog" aria-label={title}>
        <header className="sheet-head">
          <h2>{title}</h2>
          <button className="icon-btn" onClick={close} aria-label="بستن"><Icon name="close" /></button>
        </header>
        {children}
      </div>
    </div>
  );
}

/** Dropdown in the app's menu style (a bottom sheet on phones): Android WebView's native picker looks foreign. */
export function Select<T extends string | number>({ value, options, onChange, disabled }:
  { value: T; options: [T, string][]; onChange: (v: T) => void; disabled?: boolean }) {
  const [at, setAt] = useState<DOMRect | null>(null);
  const [closing, close] = useDismiss(() => setAt(null), 150);
  const menu = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number }>();
  useLayoutEffect(() => { // under the button, kept on screen
    if (!at) return setPos(undefined);
    const { offsetWidth: w, offsetHeight: h } = menu.current!; // not the rect: the pop-in animation starts scaled down
    setPos({ left: Math.max(8, Math.min(at.right - w, innerWidth - w - 8)), top: at.bottom + h + 8 > innerHeight ? Math.max(8, at.top - h - 4) : at.bottom + 4 });
    menu.current!.focus({ preventScroll: true });
  }, [at]);
  useEffect(() => {
    if (!at) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.preventDefault(); close(); } };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, [at, close]);
  const pick = (v: T) => { setAt(null); if (v !== value) onChange(v); };
  return (
    <>
      <button type="button" className="select" disabled={disabled} aria-haspopup="listbox" aria-expanded={!!at}
        onClick={(e) => { e.preventDefault(); setAt(e.currentTarget.getBoundingClientRect()); }}>
        <span>{options.find(([v]) => v === value)?.[1] ?? String(value)}</span><Icon name="down" size={18} />
      </button>
      {at && createPortal(
        <div className={"chat-menu-backdrop msg-menu-backdrop select-backdrop" + (closing ? " closing" : "")} onClick={close}>
          <div className="chat-menu msg-menu select-menu" role="listbox" tabIndex={-1} ref={menu} onClick={(e) => e.stopPropagation()}
            style={{ minWidth: at.width, ...(pos ?? { left: 0, top: 0, visibility: "hidden" }) }}>
            {options.map(([v, label]) => (
              <button key={v} type="button" role="option" aria-selected={v === value} className={v === value ? "on" : undefined} onClick={() => pick(v)}>
                <Icon name="check" /> {label}
              </button>
            ))}
          </div>
        </div>,
        document.body)}
    </>
  );
}
