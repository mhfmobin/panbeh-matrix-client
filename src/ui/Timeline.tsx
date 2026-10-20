import { t } from "../i18n.ts";
import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";
import { ClientEvent, EventStatus, EventTimeline, NotificationCountType, RoomEvent, RoomStateEvent, ThreadEvent, type MatrixEvent, type Room, type Thread } from "matrix-js-sdk";
import { cancelUpload, client, loadEvent, type Upload } from "../matrix.ts";
import { chainOf, isMessage, useTick, useUploads } from "../hooks.ts";
import { Icon } from "../icons.tsx";
import { buildRows, dayLabel, isGif, num, type Msg, type Row } from "../logic.ts";
import { Message, ProgressRing, type Actions } from "./Message.tsx";
import { audioDuration, bdi, formatSize, isVoice, me, noticeText } from "./common.tsx";
import { AudioPlayer } from "./Voice.tsx";
import { captionOf } from "./Composer.tsx";
import { useAttentive } from "../desktop.ts";
import { replyRead, unreadCount, unreadMentions, unreadThreads } from "../chats.ts";
import { pendingJump } from "./Search.tsx";

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

/** Album grouping (see buildRows): Panbeh's tag, else uncaptioned photos/videos (not GIFs) that may group by time. */
function albumOf(ev: MatrixEvent): string | undefined {
  const c = ev.getContent();
  if ((c.msgtype !== "m.image" && c.msgtype !== "m.video") || isGif(c) || c["app.panbeh.video_note"] || ev.status === EventStatus.NOT_SENT) return;
  const tag = c["app.panbeh.album"]?.id;
  if (typeof tag === "string") return "t:" + tag;
  return captionOf(c) || ev.replyEventId ? undefined : "h";
}
/** The event an album row speaks for: its captioned item (caption, edits, reply target), else the last. */
export const albumMain = (evs: MatrixEvent[]) => evs.findLast((e) => captionOf(e.getContent())) ?? evs.at(-1)!;
const rowEvents = (r: Row) => (r.type === "album" ? r.ids : r.type === "msg" ? [r.id] : []);

const readPoint = (room: Room) => ({ marker: room.getAccountData("m.fully_read")?.getContent().event_id as string | undefined, receipt: room.getEventReadUpTo(me()) });
const unreadAfterRead = (events: MatrixEvent[], read: number) => events.slice(read + 1).some((e) => isMessage(e) && e.getSender() !== me());

/** Index of the last event I've read (fully-read marker, receipt or my own event, whichever is newest), -1 if not loaded.
 *  In a thread: its newest read reply, else the root on top (seen in the chat). */
function readIndex(room: Room, events: MatrixEvent[], thread?: Thread) {
  if (thread) return events.findLastIndex((e, i) => i === 0 || e.getSender() === me() || replyRead(thread, e));
  const { marker, receipt } = readPoint(room);
  let read = -1;
  events.forEach((e, i) => { if (e.getId() === marker || e.getId() === receipt || e.getSender() === me()) read = i; });
  return read;
}

/** Row id of the last visible event I've read, if messages from others follow it. */
function unreadAnchor(room: Room, events: MatrixEvent[], thread?: Thread) {
  const read = readIndex(room, events, thread);
  if (read < 0 || !unreadAfterRead(events, read)) return;
  for (let i = read; i >= 0; i--) if (shown(events[i])) return rowId(events[i]);
}

// Where the reader left off in each chat: the topmost event on screen, kept per account (local only — a scroll
// position isn't worth syncing like drafts) so a chat reopens there. Cleared once they reach the latest again.
const resumeKey = (userId: string) => `panbeh.readpos:${userId}`;
const loadResume = (userId: string): Record<string, string> => {
  try { return JSON.parse(localStorage.getItem(resumeKey(userId)) ?? "{}") ?? {}; } catch { return {}; }
};
const getResume = (userId: string, roomId: string) => loadResume(userId)[roomId];
const putResume = (userId: string, roomId: string, eventId: string | null) => {
  try {
    const all = loadResume(userId);
    if (eventId ? all[roomId] === eventId : !all[roomId]) return; // nothing changed
    if (eventId) all[roomId] = eventId;
    else delete all[roomId];
    localStorage.setItem(resumeKey(userId), JSON.stringify(all));
  } catch { /* full or blocked: memory only */ }
};

/** The history around an old event, a page each way (the SDK's /context asks for none), decrypted; null if not found.
 *  Shown decrypted like pages: rows turning into hidden events would shift it under the reader. */
async function openWindow(room: Room, id: string) {
  const set = room.getUnfilteredTimelineSet();
  const tl = set.getTimelineForEvent(id) ?? await client.getEventTimeline(set, id);
  if (!tl) return null;
  const page = (backwards: boolean) => tl.getPaginationToken(backwards ? EventTimeline.BACKWARDS : EventTimeline.FORWARDS)
    && client.paginateEventTimeline(tl, { backwards, limit: 20 }).catch(console.warn);
  // near either end, that side too: a read point last in its run would open with nothing unread after it
  const at = tl.getEvents().findIndex((e) => e.getId() === id);
  if (tl !== room.getLiveTimeline()) await Promise.all([at < 10 && page(true), tl.getEvents().length - at <= 10 && page(false)]);
  await Promise.all(chainOf(tl).flatMap((t) => t.getEvents()).map((e) => client.decryptEventIfNeeded(e).catch(() => {})));
  return tl;
}

/** Scrolls to an event, opening the history around it if it isn't loaded; false if it can't be found. */
export type Jumper = (eventId: string, maxPages?: number) => Promise<boolean>;
type Props = { room: Room; thread?: Thread; actions: Actions; jumpRef?: RefObject<Jumper | null> };

export function Timeline({ room, thread, actions, jumpRef }: Props) {
  useTick(client, EVENTS);
  useTick(thread, THREAD_EVENTS); // thread loads its own events and resets its timeline; the client doesn't re-emit that
  const live: EventTimeline = thread ? thread.liveTimeline : room.getLiveTimeline();
  // what's shown: the live timeline, or (after jumping to an old event) a window around it that grows both ways.
  // `n` remounts the list for a new window; `at` is the event it opens on.
  const [view, setView] = useState<{ win: EventTimeline | null; n: number; at?: string }>({ win: null, n: 0 });
  const chain = chainOf(view.win ?? live);
  const atLive = chain.at(-1) === live; // reached the newest messages: following, receipts and entrances only apply here
  const chainRef = useRef(chain);
  chainRef.current = chain;
  let events = chain.flatMap((t) => t.getEvents());
  if (thread) { // some servers return thread relations newest-first; also show the root on top
    events = [...events].sort((a, b) => a.getTs() - b.getTs());
    if (thread.rootEvent && !events.some((e) => e.getId() === thread.id)) events.unshift(thread.rootEvent);
  }
  // An older page shows up only once all of it is decrypted. Shown at once, its rows above the reader changed
  // under them (undecrypted rows turning into hidden reactions, edits, call signalling) and the list jolted.
  const hold = useRef<MatrixEvent | null>(null); // the oldest event shown while a page loads
  const holdEnd = useRef<MatrixEvent | null>(null); // the newest, while a newer page loads
  const heldEnd = holdEnd.current ? events.indexOf(holdEnd.current) : -1;
  if (heldEnd >= 0) events = events.slice(0, heldEnd + 1);
  const held = hold.current ? events.indexOf(hold.current) : -1;
  if (held > 0) events = events.slice(held);
  // placed once on open and left there while the chat stays open
  // ponytail: a thread still fetching its first page on open gets no divider; re-anchor on its TimelineReset if that bites
  const [unreadAfter, setUnreadAfter] = useState(() => unreadAnchor(room, events, thread));
  // reopen where the reader left off (their last scroll-up position); threads aren't kept, and a link jump wins
  const [resume] = useState(() => (!thread && pendingJump.current?.roomId !== room.roomId ? getResume(me(), room.roomId) : undefined));
  // read point older than what's loaded, with unread messages after it: open the history around it (skeleton meanwhile)
  const [opening] = useState(() => !thread && !unreadAfter && !resume && readIndex(room, events) < 0 && unreadAfterRead(events, -1)
    && (readPoint(room).receipt ?? readPoint(room).marker ?? null));
  const [loading, setLoading] = useState(false);
  const [atBottom, setAtBottom] = useState(!unreadAfter && !opening && !resume); // opening at the divider mustn't mark everything read
  const list = useRef<VirtuosoHandle>(null);
  // Pinned to the bottom until the *user* scrolls up; layout changes (thread panel, images, late
  // thread summaries) must not unpin us, which is what Virtuoso's own atBottom would do.
  const stuck = useRef(!unreadAfter && !opening && !resume);
  const jumpingUntil = useRef(0); // while a jump settles, passing the bottom mustn't re-pin us there
  const pagingUntil = useRef(0); // while older history loads/settles, height changes mustn't drag us to the bottom
  // floating date: label of the topmost visible row while scrolling, hidden 1.2s after it stops
  const [floating, setFloating] = useState<{ label: string; show: boolean }>({ label: "", show: false });
  const topLabel = useRef(""); // the topmost visible row's day; "" while a day pill itself is on top (no double pill)
  const topKey = useRef<string | null>(null); // the topmost visible non-day row: what must stay put when rows change above it
  const bottomRef = useRef(false); // Virtuoso's at-bottom, for the scroll handler: reading scroll sizes there forced a layout per event
  const scroller = useRef<HTMLElement | null>(null);
  const scrollerRef = useCallback((el: HTMLElement | Window | null) => {
    if (!(el instanceof HTMLElement)) return;
    scroller.current = el;
    let hideT = 0;
    const onScroll = () => {
      if (bottomRef.current) return; // at the very bottom (incl. new-message follow): nothing to show
      const label = topLabel.current;
      setFloating((f) => (f.show && f.label === label ? f : { label, show: !!label }));
      clearTimeout(hideT);
      hideT = window.setTimeout(() => setFloating((f) => ({ ...f, show: false })), 1200);
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
    msgs.push({ id, sender: ev.getSender()!, ts: ev.getTs(), kind: isMessage(ev) ? "msg" : "notice", album: isMessage(ev) ? albumOf(ev) : undefined });
  }
  const rows = buildRows(msgs, undefined, unreadAfter);

  // the topmost visible event, kept so the chat reopens here; only real ids (a local echo's isn't one yet)
  const readPos = useRef<string | null>(resume ?? null);
  const saveT = useRef(0);
  const savePos = () => {
    // the first row actually on screen: Virtuoso's range starts increaseViewportBy.top above it (one layout read per range change)
    const sc = scroller.current;
    const edge = sc?.getBoundingClientRect().top ?? 0;
    const shownRows = [...(sc?.querySelectorAll<HTMLElement>("[data-index]") ?? [])].filter((n) => n.getBoundingClientRect().bottom > edge);
    const r = shownRows.map((n) => rows[Number(n.dataset.index)]).find((x) => x && x.type !== "day" && x.type !== "unread");
    const ids = r ? (r.type === "notice" ? [r.id] : rowEvents(r)) : [];
    const id = ids.map((x) => byId.get(x)).find((e) => e?.getId()?.startsWith("$"))?.getId() ?? null;
    if (!id) return; // nothing on screen (the list closing) or only a local echo: keep the last one
    readPos.current = id;
    clearTimeout(saveT.current);
    saveT.current = window.setTimeout(() => putResume(me(), room.roomId, id), 500); // not per scroll tick
  };
  useEffect(() => {
    if (thread) return; // a thread shares the room: its close mustn't clear the main timeline's pointer
    return () => { // the chat closes: keep (or clear) the pointer at once, not half a second later
      clearTimeout(saveT.current);
      if (bottomRef.current) readPos.current = null; // left at the latest
      putResume(me(), room.roomId, readPos.current);
    };
  }, []);

  const [opened, setOpened] = useState(!opening && !resume);
  useEffect(() => {
    if (resume) { // reopen around the saved position
      (async () => {
        // page the live timeline back to it (it lands reliably there, with the history above already in), plus a page
        // above so none arrives while the list settles; only a spot further back opens as a window
        const live = room.getLiveTimeline();
        const above = () => live.getEvents().findIndex((e) => e.getId() === resume);
        for (let i = 0; i < 5 && above() < 40 && live.getPaginationToken(EventTimeline.BACKWARDS); i++) {
          if (!(await client.paginateEventTimeline(live, { backwards: true, limit: 100 }).catch(() => false))) break;
        }
        await Promise.all(live.getEvents().map((e) => client.decryptEventIfNeeded(e).catch(() => {})));
        const tl = above() >= 0 ? live : await openWindow(room, resume).catch(() => null);
        if (tl) { // set before Virtuoso mounts: its at-bottom report on mount mustn't pin a window joined to the live end
          jumpingUntil.current = resumeUntil.current = Date.now() + 1500;
          setView({ win: tl === room.getLiveTimeline() ? null : tl, n: 0, at: resume });
        } else putResume(me(), room.roomId, null); // gone (redacted or pruned): the usual view next time
        setOpened(true);
      })();
      return;
    }
    if (!opening) return;
    (async () => {
      const tl = await openWindow(room, opening).catch(() => null);
      const anchor = tl && unreadAnchor(room, chainOf(tl).flatMap((t) => t.getEvents()));
      if (anchor) { setUnreadAfter(anchor); setView({ win: tl === room.getLiveTimeline() ? null : tl, n: 0 }); }
      setOpened(true); // not found: the live end, as before
    })();
  }, []);

  // Virtuoso keeps the view still only if the row on screen keeps its absolute index (firstItemIndex + position):
  // older pages prepend, and undecrypted rows above vanish once they turn out to be reactions or call signalling.
  // So anchor on the topmost visible row, else any row that survived (not day rows: older same-day messages move them).
  const absOf = useRef(new Map<string, number>());
  const firstItemIndex = useMemo(() => {
    const prev = absOf.current;
    let pos = topKey.current && prev.has(topKey.current) ? rows.findIndex((r) => r.key === topKey.current) : -1;
    if (pos < 0) pos = rows.findIndex((r) => r.type !== "day" && prev.has(r.key));
    const first = pos >= 0 ? prev.get(rows[pos].key)! - pos : START;
    absOf.current = new Map(rows.map((r, i) => [r.key, first + i]));
    return first;
  }, [rows.map((r) => r.key).join()]);

  const paging = useRef(false); // a ref, not `loading`: prefetch and startReached can both ask within one render
  // Virtuoso keeps re-applying initialTopMostItemIndex (a data index) for a moment after mounting: rows prepended
  // then put an older row at that index and the view lands there. Opening a window or at the divider hits this.
  const mountedAt = useRef(0);
  /** True while the list settles; `fn` runs once it has (one pending run per caller). */
  const settling = (timer: { current: number }, fn: () => void) => {
    if (Date.now() - mountedAt.current >= 600) return false; // ponytail: fixed settle time, Virtuoso exposes no "settled" event
    if (!timer.current) timer.current = window.setTimeout(() => { timer.current = 0; fn(); }, 650);
    return true;
  };
  const olderLater = useRef(0), newerLater = useRef(0);
  const nearTop = useRef(false); // the range starts near the top of what's loaded
  // Virtuoso reports the bottom once on mount, before it has scrolled to the divider or jump target: opened away from the
  // bottom, that mustn't pin us there or mark the chat read until the list has settled
  const [settled, setSettled] = useState(stuck.current);
  useEffect(() => {
    mountedAt.current = Date.now();
    if (stuck.current) return;
    setSettled(false);
    jumpingUntil.current = Date.now() + 700;
    const t = setTimeout(() => setSettled(true), 700);
    return () => clearTimeout(t);
  }, [view.n, opened]);
  const loadOlder = useCallback(async () => {
    const first = chainRef.current[0];
    // once settled, only if still near the top: Virtuoso's first range starts at row 0 before it scrolls to where it opens
    if (settling(olderLater, () => { if (nearTop.current) loadOlder(); })) return;
    if (paging.current || !first.getPaginationToken(EventTimeline.BACKWARDS)) return;
    paging.current = true;
    setLoading(true);
    pagingUntil.current = Infinity;
    hold.current = first.getEvents()[0] ?? null;
    await client.paginateEventTimeline(first, { backwards: true, limit: 40 }).catch(console.warn);
    const all = chainOf(first).flatMap((t) => t.getEvents()); // the page may have linked up with an older loaded run
    const page = all.slice(0, Math.max(0, all.indexOf(hold.current!)));
    await Promise.all(page.map((e) => client.decryptEventIfNeeded(e).catch(() => {})));
    hold.current = null;
    pagingUntil.current = Date.now() + 400; // Virtuoso re-anchors the prepended rows over the next frames
    paging.current = false;
    setLoading(false);
    // opened with few messages (still pinned): settle at the bottom once the page is in
    setTimeout(() => { if (stuck.current) list.current?.scrollToIndex({ index: "LAST", align: "end" }); }, 420);
  }, []);

  // in a window: page newer history in below as the reader nears its end, until it links up with the live timeline.
  // Rows added below the screen don't move it, so no settling is needed; held until decrypted like older pages.
  const pagingNew = useRef(false);
  const loadNewer = useCallback(async () => {
    const last = chainRef.current.at(-1)!;
    if (settling(newerLater, loadNewer)) return;
    if (pagingNew.current || !last.getPaginationToken(EventTimeline.FORWARDS)) return;
    pagingNew.current = true;
    setLoading(true);
    holdEnd.current = last.getEvents().at(-1) ?? null;
    await client.paginateEventTimeline(last, { backwards: false, limit: 40 }).catch(console.warn);
    const all = chainOf(last).flatMap((t) => t.getEvents());
    await Promise.all(all.slice(all.indexOf(holdEnd.current!) + 1).map((e) => client.decryptEventIfNeeded(e).catch(() => {})));
    holdEnd.current = null;
    pagingNew.current = false;
    setLoading(false);
  }, []);

  // fill the screen if we have only a handful of messages. Keep paging while pages bring only hidden
  // events (no overflow = startReached never fires again); one try per event count, so a failing request can't loop.
  const triedAt = useRef(-1);
  useEffect(() => {
    if (loading || msgs.length >= 20 || triedAt.current === events.length) return;
    triedAt.current = events.length;
    loadOlder();
  }, [view.n, events.length, loading]);

  // read receipt for the newest event while someone is looking at it (re-checked when they're back)
  const attentive = useAttentive();
  const lastEv = events.at(-1);
  useEffect(() => {
    if (!atBottom || !settled || !atLive || !lastEv || lastEv.status || !attentive || lastEv.getSender() === me()) return;
    if (room.hasUserReadEvent(me(), lastEv.getId()!)) return;
    client.sendReadReceipt(lastEv).catch(() => {});
    // fully-read marker (where the divider goes next time) only; the receipt above keeps its thread semantics
    if (!thread) client.setRoomReadMarkers(room.roomId, lastEv.getId()!).catch(() => {});
  }, [atBottom, settled, atLive, lastEv, room, attentive]);

  // jump to an event (pinned messages): page back until it's loaded, then scroll once its row renders
  const [target, setTarget] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  if (jumpRef) jumpRef.current = async (id, maxPages = 10) => {
    setLoading(true);
    try {
      if (thread) { // ponytail: threads page back (maxPages cap, ~40 events each); they're rarely long enough to need windows
        for (let page = 0; !live.getEvents().some((e) => e.getId() === id); page++) {
          if (page >= maxPages || !live.getPaginationToken(EventTimeline.BACKWARDS)) return false;
          await client.paginateEventTimeline(live, { backwards: true, limit: 40 });
        }
      } else { // one /context request opens the history around it, however far back
        const tl = await openWindow(room, id);
        if (!tl) return false;
        if (!chainRef.current.includes(tl)) {
          stuck.current = false;
          setView((v) => ({ win: tl === room.getLiveTimeline() ? null : tl, n: v.n + 1, at: id }));
        }
      }
    } catch {
      return false;
    } finally {
      setLoading(false);
    }
    setTarget(id);
    return true;
  };
  const latest = useRef({ rows, byId });
  latest.current = { rows, byId };
  const indexOf = (id: string) => latest.current.rows.findIndex((r) => rowEvents(r).some((x) => latest.current.byId.get(x)?.getId() === id));
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
  }, [target, rows.length]);
  useEffect(() => {
    if (!flash) return;
    const t = setTimeout(() => setFlash(null), 1600);
    return () => clearTimeout(t);
  }, [flash]);
  // reopened at the saved spot: aim again as Virtuoso measures the rows it placed on estimated heights, and as the
  // deferred first page of older history prepends (as a jump does), for the first moment after opening
  const resumeUntil = useRef(0);
  useEffect(() => {
    if (!opened || !resume || view.at !== resume || Date.now() > resumeUntil.current) return;
    stuck.current = false;
    const go = () => {
      const pos = indexOf(resume);
      if (pos < 0) return;
      const sc = scroller.current, row = sc?.querySelector(`[data-index="${pos}"]`);
      // drawn: put it exactly at the top; not yet: Virtuoso scrolls there on estimated heights
      if (sc && row) sc.scrollTop += row.getBoundingClientRect().top - sc.getBoundingClientRect().top;
      else list.current?.scrollToIndex({ index: pos, align: "start" });
    };
    const ts = [150, 400, 800].map((ms) => setTimeout(go, ms));
    return () => ts.forEach(clearTimeout);
  }, [opened, rows.length]);

  const lastIsMine = lastEv?.getSender() === me();
  // Virtuoso asks followOutput on every count increase, prepended history included: follow only when the end changed
  const lastKey = rows.at(-1)?.key;
  const prevLast = useRef(lastKey);
  const appended = prevLast.current !== lastKey;
  useEffect(() => { prevLast.current = lastKey; });
  // the row appended at the end plays its entrance; history pages and the first fill don't. A local echo turning into the
  // sent event changes the key too (the row remounts), which must not play it a second time.
  const [enterKey, setEnterKey] = useState<string | null>(null);
  const shownLast = useRef(lastKey);
  useEffect(() => {
    if (shownLast.current === lastKey) return;
    const was = shownLast.current;
    shownLast.current = lastKey;
    if (!was || !lastKey || !wasLive.current || !atLive || (was.startsWith("~") && lastIsMine)) return; // not pages arriving below a window
    setEnterKey(lastKey);
    const t = setTimeout(() => setEnterKey(null), 600);
    return () => clearTimeout(t);
  }, [lastKey]);
  const wasLive = useRef(atLive);
  useEffect(() => { wasLive.current = atLive; });
  const toBottom = () => {
    stuck.current = true;
    if (atLive) list.current?.scrollToIndex({ index: "LAST", align: "end" });
    else setView((v) => ({ win: null, n: v.n + 1 })); // a window far back: open the live timeline at its end instead of scrolling through
  };
  // sending from a window: go to the message just sent
  const liveLast = live.getEvents().at(-1);
  useEffect(() => { if (!atLive && liveLast?.status && liveLast.getSender() === me()) toBottom(); }, [liveLast]);
  const unread = thread ? 0 : room.getRoomUnreadNotificationCount(NotificationCountType.Total); // threads have their own button
  // Virtuoso mounted with no data stays hidden waiting for its initial "LAST" scroll, so wait for rows
  if (!rows.length || !opened) return (
    <div className="timeline-wrap" aria-busy>
      <div className="skeleton-timeline" aria-hidden>{[62, 38, 74, 50, 30, 66].map((w, i) => <i key={i} className={"skeleton " + (i % 3 === 1 ? "mine" : "")} style={{ width: w + "%" }} />)}</div>
    </div>
  );
  const divider = rows.findIndex((r) => r.type === "unread"); // read only on mount: open at the divider
  const opensAt = view.at ? rows.findIndex((r) => rowEvents(r).some((x) => byId.get(x)?.getId() === view.at)) : -1;

  return (
    <div className="timeline-wrap">
    {loading && <span className="spinner" />}
    <div className="timeline-fabs">
      {!thread && <UnreadJumps room={room} actions={actions} />}
      <button className={"tl-fab jump-bottom" + (atBottom && atLive ? " away" : "")} onClick={toBottom} title={t("timeline.latest")} aria-label={t("timeline.toLatest")} inert={atBottom && atLive}>
        <Icon name="down" />
        {unread > 0 && <span className="badge">{num(unread)}</span>}
      </button>
    </div>
    {floating.label && <div className={"pill date-float" + (floating.show ? " show" : "")} aria-hidden>{floating.label}</div>}
    <Virtuoso
      key={view.n}
      className="timeline"
      data={rows}
      firstItemIndex={firstItemIndex}
      initialTopMostItemIndex={opensAt >= 0 ? { index: opensAt, align: view.at === resume ? "start" : "center" } : view.n === 0 && divider >= 0 ? { index: divider, align: "start" } : { index: "LAST", align: "end" }}
      alignToBottom
      // my message being sent (local echo) follows even when scrolled up; mine arriving otherwise (another device,
      // or a window linking up with the live end) mustn't yank the reader down
      followOutput={() => (appended && atLive && (stuck.current || (lastIsMine && !!lastEv?.status)) ? "smooth" : false)}
      ref={list}
      scrollerRef={scrollerRef}
      atBottomStateChange={(b) => { bottomRef.current = b; if (b && atLive && Date.now() > jumpingUntil.current && Date.now() > pagingUntil.current) stuck.current = true; setAtBottom(b); }}
      // panel opening / images loading change heights; stay pinned if we were at the bottom
      totalListHeightChanged={() => stuck.current && Date.now() > pagingUntil.current && list.current?.scrollToIndex({ index: "LAST", align: "end" })}
      atBottomThreshold={80}
      skipAnimationFrameInResizeObserver // measure and compensate in the same frame; otherwise rows entering above paint one frame at the wrong spot
      startReached={() => { stuck.current = false; loadOlder(); }} // at the top we're reading history, not following the bottom
      endReached={() => { loadNewer(); }}
      rangeChanged={({ startIndex, endIndex }) => {
        const r = rows[startIndex - firstItemIndex];
        topKey.current = rows.slice(startIndex - firstItemIndex).find((x) => x.type !== "day")?.key ?? null; // day rows move
        topLabel.current = !r || r.type === "day" ? "" : r.type === "unread" ? topLabel.current : dayLabel(byId.get(r.id)!.getTs());
        // remember where the reader is: nothing at the latest, the topmost event otherwise. Not while it settles: where it
        // lands isn't the reader's choice, and Virtuoso reports the bottom on mount before it has scrolled to the saved row
        if (!thread && Date.now() > jumpingUntil.current) {
          if (bottomRef.current) { clearTimeout(saveT.current); readPos.current = null; }
          else savePos();
        }
        nearTop.current = startIndex - firstItemIndex < 15;
        if (nearTop.current) loadOlder(); // fetch the next page while there's still some left to read
        if (rows.length - (endIndex - firstItemIndex) < 15) loadNewer();
      }}
      increaseViewportBy={{ top: 600, bottom: 200 }}
      computeItemKey={(_, r) => r.key}
      components={COMPONENTS}
      context={{ roomId: room.roomId, threadId: thread?.id ?? null }}
      itemContent={(_, r: Row) => <div className="row">{
        r.type === "day" ? <div className="pill day">{r.label}</div>
        : r.type === "notice" ? <div className="pill">{noticeText(byId.get(r.id)!)}</div>
        : r.type === "unread" ? <div className="unread-divider">{t("timeline.unread")}</div>
        : r.type === "album" ? (() => {
          const evs = r.ids.map((id) => byId.get(id)!);
          return <Message ev={albumMain(evs)} album={evs} room={room} first={r.first} last={r.last} actions={actions} flash={evs.some((e) => flash === e.getId())} enter={enterKey === r.key} />;
        })()
        : <Message ev={byId.get(r.id)!} room={room} first={r.first} last={r.last} actions={actions} flash={flash === byId.get(r.id)!.getId()} enter={enterKey === r.key} />}</div>}
    />
    </div>
  );
}

/** Telegram's thread and @ buttons over the ↓: each tap takes the oldest unread one not taken yet while the chat is open. */
function UnreadJumps({ room, actions }: { room: Room; actions: Actions }) {
  const taken = useRef(new Set<string>());
  const [searching, setSearching] = useState(false);
  const [gaveUp, setGaveUp] = useState(false); // the server counts mentions we can't find (read point too far back, or stale)
  const threads = unreadThreads(room).filter((th) => !taken.current.has(th.id));
  const found = unreadMentions(room);
  const mentions = found.filter((e) => !taken.current.has(e.getId()!));
  // the server's count also has mentions not loaded yet (before what's shown); taken ones stay unread until read
  const left = gaveUp ? mentions.length : Math.max(mentions.length, unreadCount(room, NotificationCountType.Highlight) - (found.length - mentions.length));

  const openThread = async () => {
    const th = threads[0];
    taken.current.add(th.id);
    const root = th.rootEvent ?? await loadEvent(room, th.id).catch(() => null);
    if (root) actions.thread?.(root);
  };
  const nextMention = async () => {
    const untaken = () => unreadMentions(room).find((e) => !taken.current.has(e.getId()!));
    let next: MatrixEvent | undefined = mentions[0];
    if (!next) { // counted but not loaded: page the live timeline back to it
      setSearching(true);
      const live = room.getLiveTimeline();
      // ponytail: page cap, 10 × 40 events; a mention further back gives up
      for (let i = 0; i < 10 && !next && live.getPaginationToken(EventTimeline.BACKWARDS); i++) {
        await client.paginateEventTimeline(live, { backwards: true, limit: 40 }).catch(console.warn);
        await Promise.all(live.getEvents().map((e) => client.decryptEventIfNeeded(e).catch(() => {})));
        next = untaken();
      }
      setSearching(false);
      if (!next) return setGaveUp(true);
    }
    taken.current.add(next.getId()!);
    actions.jump?.(next.getId()!);
  };

  return <>
    {threads.length > 0 && (
      <button className="tl-fab" onClick={openThread} title={t("timeline.nextThread")} aria-label={t("timeline.nextThread")}>
        <Icon name="thread" />
        <span className="badge">{num(threads.length)}</span>
      </button>
    )}
    {left > 0 && (
      <button className="tl-fab" onClick={nextMention} disabled={searching} title={t("timeline.nextMention")} aria-label={t("timeline.nextMention")}>
        <Icon name="at" />
        <span className="badge">{num(left)}</span>
      </button>
    )}
  </>;
}

type Ctx = { roomId: string; threadId: string | null };
const COMPONENTS = {
  Header: () => <div style={{ height: 12 }} />,
  Footer: ({ context }: { context?: Ctx }) => <>{context && <PendingUploads {...context} />}<div style={{ height: "calc(var(--composer-h, 0px) + 8px)" }} /></>,
};

function PendingUploads({ roomId, threadId }: Ctx) {
  return useUploads(roomId, threadId).map((u) => <PendingUpload key={u.id} u={u} />);
}

function Ring({ u }: { u: Upload }) {
  return <ProgressRing f={u.error ? 0 : u.loaded ? Math.min(1, u.loaded / (u.total || 1)) : 0.04} label={t("upload.cancel")} onClick={() => cancelUpload(u.id)} />;
}

function PendingUpload({ u }: { u: Upload }) {
  const queued = !u.loaded && !u.error;
  const state = u.error ? t("upload.failed") : queued ? t("upload.queued") : `${formatSize(u.loaded)} / ${formatSize(u.total)}`;
  const voice = !!u.extra && isVoice(u.extra);
  return (
    <div className="row"><div className={"msg mine first last" + (u.error ? " failed" : "")}><div className="msg-col">
      <div className={"bubble" + (u.previewUrl && !voice ? " media" : "")} dir="auto">
        {voice ? (
          // looks like the voice note it becomes, the ring where play will be
          <AudioPlayer track={{ id: "upload:" + u.id, load: () => Promise.resolve(u.previewUrl!), duration: audioDuration(u.extra!),
            waveform: (u.extra!["org.matrix.msc1767.audio"] as { waveform?: number[] } | undefined)?.waveform }}
            lead={u.error ? <span className="file-icon"><Icon name="mic" /></span> : <Ring u={u} />} />
        ) : u.previewUrl ? (
          <div className={"media-box up-box" + (u.extra?.["app.panbeh.video_note"] ? " note" : "")}>
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
        <button title={t("msg.resend")} aria-label={t("msg.resend")} onClick={u.retry}><Icon name="send" size={17} /></button>
        <button title={t("common.delete")} aria-label={t("common.delete")} onClick={() => cancelUpload(u.id)}><Icon name="trash" size={17} /></button>
      </div>
    )}
    </div></div>
  );
}
