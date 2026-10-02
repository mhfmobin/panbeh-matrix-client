import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";
import { ClientEvent, EventTimeline, RoomEvent, RoomStateEvent, ThreadEvent, type MatrixEvent, type Room, type Thread } from "matrix-js-sdk";
import { cancelUpload, client, type Upload } from "../matrix.ts";
import { isMessage, useTick, useUploads } from "../hooks.ts";
import { Icon } from "../icons.tsx";
import { buildRows, dayLabel, num, type Msg, type Row } from "../logic.ts";
import { Message, type Actions } from "./Message.tsx";
import { bdi, formatSize, me, noticeText } from "./common.tsx";

const EVENTS = [
  RoomEvent.Timeline, RoomEvent.TimelineReset, RoomEvent.LocalEchoUpdated, RoomEvent.Redaction, RoomEvent.Receipt,
  RoomEvent.UnreadNotifications, ThreadEvent.Update, ThreadEvent.NewReply, "Event.decrypted", "Event.replaced", "Event.status",
  RoomStateEvent.Members, // late-loaded profiles
  ClientEvent.AccountData, // block list
];
const THREAD_EVENTS = [RoomEvent.Timeline, RoomEvent.TimelineReset, ThreadEvent.Update, ThreadEvent.NewReply];
const START = 1_000_000_000;

const shown = (ev: MatrixEvent) => isMessage(ev) || !!noticeText(ev);
const rowId = (ev: MatrixEvent) => ev.getTxnId() ?? ev.getId()!; // stable across local echo → remote echo, so rows don't remount

/** Row id of the last visible event I've read (fully-read marker, receipt or my own event, whichever is newest),
 *  if messages from others follow it. */
function unreadAnchor(room: Room, events: MatrixEvent[]) {
  const marker = room.getAccountData("m.fully_read")?.getContent().event_id;
  const receipt = room.getEventReadUpTo(me());
  let read = -1;
  events.forEach((e, i) => { if (e.getId() === marker || e.getId() === receipt || e.getSender() === me()) read = i; });
  // ponytail: a read point older than the loaded page gives no divider; finding it would mean paging back on every open
  if (read < 0 || !events.slice(read + 1).some((e) => isMessage(e) && e.getSender() !== me())) return;
  for (let i = read; i >= 0; i--) if (shown(events[i])) return rowId(events[i]);
}

/** Scrolls to an event, loading older history if needed; false if it's too far back. */
export type Jumper = (eventId: string, maxPages?: number) => Promise<boolean>;
type Props = { room: Room; thread?: Thread; actions: Actions; jumpRef?: RefObject<Jumper | null> };

export function Timeline({ room, thread, actions, jumpRef }: Props) {
  useTick(client, EVENTS);
  useTick(thread, THREAD_EVENTS); // thread loads its own events and resets its timeline; the client doesn't re-emit that
  const timeline: EventTimeline = thread ? thread.liveTimeline : room.getLiveTimeline();
  let events = timeline.getEvents();
  if (thread) { // some servers return thread relations newest-first; also show the root on top
    events = [...events].sort((a, b) => a.getTs() - b.getTs());
    if (thread.rootEvent && !events.some((e) => e.getId() === thread.id)) events.unshift(thread.rootEvent);
  }
  // placed once on open and left there while the chat stays open
  const [unreadAfter] = useState(() => (thread ? undefined : unreadAnchor(room, events)));
  const [loading, setLoading] = useState(false);
  const [atBottom, setAtBottom] = useState(!unreadAfter); // opening at the divider mustn't mark everything read
  const list = useRef<VirtuosoHandle>(null);
  // Pinned to the bottom until the *user* scrolls up; layout changes (thread panel, images, late
  // thread summaries) must not unpin us, which is what Virtuoso's own atBottom would do.
  const stuck = useRef(!unreadAfter);
  const jumpingUntil = useRef(0); // while a jump settles, passing the bottom mustn't re-pin us there
  // floating date: label of the topmost visible row while scrolling, hidden 1.2s after it stops
  const [floating, setFloating] = useState<{ label: string; show: boolean }>({ label: "", show: false });
  const scrollerRef = useCallback((el: HTMLElement | Window | null) => {
    if (!(el instanceof HTMLElement)) return;
    let hideT = 0, raf = 0;
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        if (el.scrollHeight - el.scrollTop - el.clientHeight < 40) return; // at the very bottom (incl. new-message follow): nothing to show
        const top = el.getBoundingClientRect().top;
        const r = [...el.querySelectorAll<HTMLElement>(".row[data-ts],.row[data-label]")].find((x) => x.getBoundingClientRect().bottom > top + 1);
        if (!r) return;
        const label = r.dataset.label ?? dayLabel(Number(r.dataset.ts));
        setFloating((f) => (f.show && f.label === label ? f : { label, show: true }));
        clearTimeout(hideT);
        hideT = window.setTimeout(() => setFloating((f) => ({ ...f, show: false })), 1200);
      });
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    const unpin = () => { stuck.current = false; };
    el.addEventListener("wheel", (e) => { if (e.deltaY < 0) unpin(); }, { passive: true });
    el.addEventListener("touchstart", unpin, { passive: true });
    el.addEventListener("mousedown", (e) => { // scrollbar drag; the scrollbar sits on the left in RTL
      const x = e.clientX - el.getBoundingClientRect().left - el.clientLeft;
      if (x < 0 || x > el.clientWidth) unpin();
    });
    el.addEventListener("keydown", (e) => { if (["ArrowUp", "PageUp", "Home"].includes(e.key)) unpin(); });
  }, []);

  const byId = new Map<string, MatrixEvent>();
  const msgs: Msg[] = [];
  for (const ev of events) {
    if (!shown(ev)) continue;
    const id = rowId(ev);
    byId.set(id, ev);
    msgs.push({ id, sender: ev.getSender()!, ts: ev.getTs(), kind: isMessage(ev) ? "msg" : "notice" });
  }
  const rows = buildRows(msgs, undefined, unreadAfter);

  // Virtuoso keeps scroll position on prepend only if firstItemIndex drops by the prepended count.
  // Anchor on the first non-day row (day rows keep their key when older same-day messages arrive).
  const anchor = useRef<{ key: string; abs: number } | null>(null);
  const firstItemIndex = useMemo(() => {
    const pos = rows.findIndex((r) => r.type !== "day");
    if (pos < 0) return START;
    const prev = anchor.current;
    const oldPos = prev ? rows.findIndex((r) => r.key === prev.key) : -1;
    const first = prev && oldPos >= 0 ? prev.abs - oldPos : START;
    anchor.current = { key: rows[pos].key, abs: first + pos };
    return first;
  }, [rows.map((r) => r.key).join()]); // eslint-disable-line react-hooks/exhaustive-deps

  const loadOlder = useCallback(async () => {
    if (loading || !timeline.getPaginationToken(EventTimeline.BACKWARDS)) return;
    setLoading(true);
    await client.paginateEventTimeline(timeline, { backwards: true, limit: 40 }).catch(console.warn);
    setLoading(false);
  }, [timeline, loading]);

  // fill the screen if we have only a handful of messages. Keep paging while pages bring only hidden
  // events (no overflow = startReached never fires again); one try per event count, so a failing request can't loop.
  const triedAt = useRef(-1);
  useEffect(() => {
    if (loading || msgs.length >= 20 || triedAt.current === events.length) return;
    triedAt.current = events.length;
    loadOlder();
  }, [timeline, events.length, loading]); // eslint-disable-line react-hooks/exhaustive-deps

  // read receipt for the newest event while we're looking at it (re-checked when the tab becomes visible)
  const [hidden, setHidden] = useState(document.hidden);
  useEffect(() => {
    const onVis = () => setHidden(document.hidden);
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, []);
  const lastEv = events.at(-1);
  useEffect(() => {
    if (!atBottom || !lastEv || lastEv.status || hidden || lastEv.getSender() === me()) return;
    if (room.hasUserReadEvent(me(), lastEv.getId()!)) return;
    client.sendReadReceipt(lastEv).catch(() => {});
    // fully-read marker (where the divider goes next time) only; the receipt above keeps its thread semantics
    if (!thread) client.setRoomReadMarkers(room.roomId, lastEv.getId()!).catch(() => {});
  }, [atBottom, lastEv, room, hidden]); // eslint-disable-line react-hooks/exhaustive-deps

  // jump to an event (pinned messages): page back until it's loaded, then scroll once its row renders
  const [target, setTarget] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  if (jumpRef) jumpRef.current = async (id, maxPages = 10) => {
    setLoading(true);
    try {
      // ponytail: maxPages (default 10, ~400 events) cap; older pins need a timeline window around the event (client.getEventTimeline)
      for (let page = 0; !timeline.getEvents().some((e) => e.getId() === id); page++) {
        if (page >= maxPages || !timeline.getPaginationToken(EventTimeline.BACKWARDS)) return false;
        await client.paginateEventTimeline(timeline, { backwards: true, limit: 40 });
      }
    } catch {
      return false;
    } finally {
      setLoading(false);
    }
    setTarget(id);
    return true;
  };
  // messages that arrive while the chat is open pop in; rows Virtuoso merely re-mounts while scrolling don't
  const mountedAt = useRef(Date.now()), entering = useRef(new Map<string, number>());
  const isEntering = (ev: MatrixEvent) => {
    const id = ev.getTxnId() ?? ev.getId() ?? ""; // txn id is stable across local echo → sent
    let until = entering.current.get(id);
    if (until === undefined) {
      until = ev.getTs() >= mountedAt.current - 2000 && Date.now() - ev.getTs() < 5000 ? Date.now() + 450 : 0;
      entering.current.set(id, until);
    }
    return Date.now() < until;
  };
  const latest = useRef({ rows, byId });
  latest.current = { rows, byId };
  const indexOf = (id: string) => latest.current.rows.findIndex((r) => r.type === "msg" && latest.current.byId.get(r.id)?.getId() === id);
  useEffect(() => {
    if (!target || indexOf(target) < 0) return; // not rendered yet
    stuck.current = false;
    jumpingUntil.current = Date.now() + 1000;
    const id = target;
    // looked up each time: landing near the top makes startReached prepend older rows, shifting the index
    const go = () => { const pos = indexOf(id); if (pos >= 0) list.current?.scrollToIndex({ index: pos, align: "center" }); };
    go();
    // after paging back Virtuoso re-anchors the prepended rows, and far jumps land on estimated heights of
    // rows it hasn't measured yet: aim again as it measures
    [150, 400, 800].forEach((ms) => setTimeout(go, ms));
    setFlash(target);
    setTarget(null);
  }, [target, rows.length]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!flash) return;
    const t = setTimeout(() => setFlash(null), 1600);
    return () => clearTimeout(t);
  }, [flash]);

  const lastIsMine = lastEv?.getSender() === me();
  const toBottom = () => { stuck.current = true; list.current?.scrollToIndex({ index: "LAST", align: "end" }); };
  const unread = thread ? 0 : room.getUnreadNotificationCount();
  // Virtuoso mounted with no data stays hidden waiting for its initial "LAST" scroll, so wait for rows
  if (!rows.length) return <div className="timeline-wrap"><span className="spinner" /></div>;
  const divider = rows.findIndex((r) => r.type === "unread"); // read only on mount: open at the divider

  return (
    <div className="timeline-wrap">
    {loading && <span className="spinner" />}
    {!atBottom && (
      <button className="jump-bottom" onClick={toBottom} title="آخرین پیام" aria-label="رفتن به آخرین پیام">
        <Icon name="down" />
        {unread > 0 && <span className="badge">{num(unread)}</span>}
      </button>
    )}
    {floating.label && <div className={"pill date-float" + (floating.show ? " show" : "")} aria-hidden>{floating.label}</div>}
    <Virtuoso
      className="timeline"
      data={rows}
      firstItemIndex={firstItemIndex}
      initialTopMostItemIndex={divider >= 0 ? { index: divider, align: "start" } : { index: "LAST", align: "end" }}
      alignToBottom
      followOutput={() => (stuck.current || lastIsMine ? "smooth" : false)}
      ref={list}
      scrollerRef={scrollerRef}
      atBottomStateChange={(b) => { if (b && Date.now() > jumpingUntil.current) stuck.current = true; setAtBottom(b); }}
      // panel opening / images loading change heights; stay pinned if we were at the bottom
      totalListHeightChanged={() => stuck.current && list.current?.scrollToIndex({ index: "LAST", align: "end" })}
      atBottomThreshold={80}
      startReached={loadOlder}
      increaseViewportBy={{ top: 600, bottom: 200 }}
      computeItemKey={(_, r) => r.key}
      components={COMPONENTS}
      context={{ roomId: room.roomId, threadId: thread?.id ?? null }}
      itemContent={(_, r: Row) => <div className="row" data-label={r.type === "day" ? r.label : undefined}
        data-ts={r.type === "msg" || r.type === "notice" ? byId.get(r.id)?.getTs() : undefined}>{
        r.type === "day" ? <div className="pill day">{r.label}</div>
        : r.type === "notice" ? <div className="pill">{noticeText(byId.get(r.id)!)}</div>
        : r.type === "unread" ? <div className="unread-divider">پیام‌های خوانده‌نشده</div>
        : <Message ev={byId.get(r.id)!} room={room} first={r.first} last={r.last} actions={actions} flash={flash === byId.get(r.id)!.getId()} enter={isEntering(byId.get(r.id)!)} />}</div>}
    />
    </div>
  );
}

type Ctx = { roomId: string; threadId: string | null };
const COMPONENTS = {
  Header: () => <div style={{ height: 12 }} />,
  Footer: ({ context }: { context?: Ctx }) => <>{context && <PendingUploads {...context} />}<div style={{ height: 8 }} /></>,
};

function PendingUploads({ roomId, threadId }: Ctx) {
  return useUploads(roomId, threadId).map((u) => <PendingUpload key={u.id} u={u} />);
}

const R = 20, C = 2 * Math.PI * R;
function Ring({ u }: { u: Upload }) {
  const f = u.error ? 0 : u.loaded ? Math.min(1, u.loaded / (u.total || 1)) : 0.04;
  return (
    <button className="up-ring" onClick={() => cancelUpload(u.id)} aria-label="لغو ارسال">
      <svg viewBox="0 0 48 48" width="48" height="48" aria-hidden>
        <circle cx="24" cy="24" r={R} className="up-track" />
        <circle cx="24" cy="24" r={R} className="up-arc" strokeDasharray={C} strokeDashoffset={C * (1 - f)} />
      </svg>
      <Icon name="close" size={18} />
    </button>
  );
}

function PendingUpload({ u }: { u: Upload }) {
  const queued = !u.loaded && !u.error;
  const state = u.error ? "ارسال نشد" : queued ? "در صف…" : `${formatSize(u.loaded)} / ${formatSize(u.total)}`;
  return (
    <div className="row"><div className={"msg mine first last" + (u.error ? " failed" : "")}><div className="msg-col">
      <div className={"bubble" + (u.previewUrl ? " media" : "")} dir="auto">
        {u.previewUrl ? (
          <div className="media-box up-box">
            {u.file.type.startsWith("video/") ? <video src={u.previewUrl} preload="metadata" muted /> : <img src={u.previewUrl} alt="" />}
            {!u.error && <Ring u={u} />}
            {u.error && <span className="up-state">{state}</span>}
          </div>
        ) : (
          <div className="file-row">
            {u.error ? <span className="file-icon"><Icon name="file" /></span> : <Ring u={u} />}
            <span><b>{bdi(u.file.name)}</b><small dir="auto">{state}</small></span>
          </div>
        )}
      </div>
    </div>
    {u.error && (
      <div className="msg-actions">
        <button title="ارسال دوباره" aria-label="ارسال دوباره" onClick={u.retry}><Icon name="send" size={17} /></button>
        <button title="حذف" aria-label="حذف" onClick={() => cancelUpload(u.id)}><Icon name="trash" size={17} /></button>
      </div>
    )}
    </div></div>
  );
}
