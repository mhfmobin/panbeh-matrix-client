// Chat-list state kept on the server: tags (pin/archive) and "marked as unread".
import { EventTimeline, NotificationCountType, RoomEvent, type MatrixEvent, type Room, type Thread } from "matrix-js-sdk";
import { client } from "./matrix.ts";
import { unreadSince } from "./logic.ts";

export const PINNED = "m.favourite"; // Element shows these as Favourites
export const ARCHIVED = "u.archived"; // user-defined tags must start with "u."

export const hasTag = (room: Room, tag: string) => tag in room.tags;
export const setTag = (room: Room, tag: string, on: boolean) =>
  on ? client.setRoomTag(room.roomId, tag, {}) : client.deleteRoomTag(room.roomId, tag);

// MSC2867, stable since Matrix 1.12; FluffyChat and older clients write the unstable name
const MARKED = "m.marked_unread", MARKED_OLD = "com.famedly.marked_unread";
export const isMarkedUnread = (room: Room): boolean =>
  !!(room.getAccountData(MARKED)?.getContent().unread ?? room.getAccountData(MARKED_OLD)?.getContent().unread);

// the SDK's typed account-data map doesn't list these
const put = (room: Room, type: string, unread: boolean) => client.setRoomAccountData(room.roomId, type as never, { unread } as never);

export async function setMarkedUnread(room: Room, unread: boolean) {
  await put(room, MARKED, unread);
  if (!unread && room.getAccountData(MARKED_OLD)?.getContent().unread) await put(room, MARKED_OLD, false);
}

/** A thread's replies, oldest first (some servers list them newest-first), without its root. */
const repliesOf = (th: Thread) => th.liveTimeline.getEvents().filter((e) => e.getId() !== th.id).sort((a, b) => a.getTs() - b.getTs());
/** Whether I've read a reply. Not the Thread's own checks: they call the whole thread read when its "last" reply predates my
 *  unthreaded receipt (on servers listing replies newest-first, Conduit, that "last" is the oldest one), or while the chat
 *  has no threaded receipt yet. That order also puts older replies after a receipt on a newer one, so a reply older than my
 *  unthreaded receipt counts as read too: Conduit keeps no other kind, and it reads the whole chat. */
export function replyRead(th: Thread, e: MatrixEvent) {
  const me = client.getUserId()!;
  return th.room.hasUserReadEvent(me, e.getId()!) || e.getTs() <= (th.room.getLastUnthreadedReceiptFor(me)?.ts ?? 0);
}

/** A thread's unread count: its replies from others after my read point, when that's loaded. In encrypted chats the SDK
 *  counts thread replies itself as they decrypt and miscounts: one reply twice, or, after a restart, replies already
 *  read; only the server's next word on that chat corrects it. Its count stands only when the read point isn't loaded.
 *  `local`: count even when the server says none. Servers without threaded counts (Conduit, though it claims v1.4+) put
 *  thread replies in the chat's own count; chat totals leave it off so those aren't counted twice. */
export function threadUnread(th: Thread, type = NotificationCountType.Total, local = false) {
  const counted = th.room.getThreadUnreadNotificationCount(th.id, type);
  if (!counted && !local) return 0;
  const me = client.getUserId()!;
  const counts = type === NotificationCountType.Highlight ? isMention
    : (e: MatrixEvent) => e.getSender() !== me && !!client.getPushActionsForEvent(e)?.notify;
  const replies = repliesOf(th);
  return unreadSince(replies, (e) => replyRead(th, e), counts, replies.length >= th.length) ?? counted;
}

/** A chat's unread count, its threads' included. */
export const unreadCount = (room: Room, type = NotificationCountType.Total) =>
  room.getThreads().reduce((n, th) => n + threadUnread(th, type), room.getRoomUnreadNotificationCount(type));

/** Someone else's event that mentions me (by name, @room or a keyword): the server's push rules' highlight. */
export const isMention = (e: MatrixEvent) => e.getSender() !== client.getUserId() && !!client.getPushActionsForEvent(e)?.tweaks?.highlight;

const byRootTs = (a: Thread, b: Thread) => (a.rootEvent?.getTs() ?? 0) - (b.rootEvent?.getTs() ?? 0);
/** Threads with unread replies, oldest root first. */
export const unreadThreads = (room: Room) => room.getThreads().filter((th) => threadUnread(th, undefined, true) > 0).sort(byRootTs);

/** Lists the chat's threads from the server (once per chat; the SDK remembers). Since a restart the SDK knows only threads
 *  with replies in the cached timeline: an old root's new replies counted in the chat's badge, but no thread had them.
 *  A thread whose newest reply (from the server's summary) isn't loaded loads again: one that missed replies in a sync
 *  gap, or any longer than a page on Conduit, whose "backwards" pages start at the oldest reply. */
export async function loadThreads(room: Room) {
  await room.createThreadsTimelineSets();
  await room.fetchRoomThreads();
  await Promise.all(room.getThreads().map(async (th) => {
    const last = th.replyToEvent?.getId();
    const loaded = () => th.liveTimeline.getEvents().some((e) => e.getId() === last);
    // ponytail: polls for the SDK's own first page of a just-listed thread, which exposes no promise for it
    for (let i = 0; i < 50 && !th.initialEventsFetched; i++) await new Promise((r) => setTimeout(r, 200));
    if (!last || !th.initialEventsFetched || loaded()) return;
    th.timelineSet.resetLiveTimeline();
    // ponytail: 5 pages of 50; a longer thread on Conduit stays without its newest replies
    for (let i = 0; i < 5 && (i === 0 || th.liveTimeline.getPaginationToken(EventTimeline.BACKWARDS)) && !loaded(); i++)
      await client.paginateEventTimeline(th.liveTimeline, { backwards: true, limit: 50 });
  }));
}

/** Unread mentions of me that are loaded, oldest first: in the live timeline, a window opened further back, or threads. */
export function unreadMentions(room: Room) {
  const me = client.getUserId()!;
  const main = room.getUnfilteredTimelineSet().getTimelines().flatMap((t) => t.getEvents()).filter((e) => !room.hasUserReadEvent(me, e.getId()!));
  const inThreads = room.getThreads().flatMap((th) => repliesOf(th).filter((e) => !replyRead(th, e)));
  return [...main, ...inThreads].filter(isMention).sort((a, b) => a.getTs() - b.getTs());
}

/** Zero the SDK's own count (the thread's, with `threadId`) in an encrypted chat. There it counts decrypted events itself, some
 *  already read, and only the server saying zero resets it; but no receipt goes out for what's already read. Elsewhere the
 *  server's count stands (on Conduit it includes thread replies the chat view doesn't show). */
export function clearLocalUnread(room: Room, threadId?: string) {
  if (!room.hasEncryptionStateEvent()) return;
  for (const type of [NotificationCountType.Total, NotificationCountType.Highlight]) {
    if (threadId ? room.getThreadUnreadNotificationCount(threadId, type) : room.getRoomUnreadNotificationCount(type))
      threadId ? room.setThreadUnreadNotificationCount(threadId, type, 0) : room.setUnreadNotificationCount(type, 0);
  }
}

/** Receipts on the newest event and on each unread thread's newest reply + clear the manual mark. */
export async function markRead(room: Room) {
  const last = room.getLiveTimeline().getEvents().filter((e) => !e.status).at(-1);
  const unread = room.getThreads().filter((th) => threadUnread(th, undefined, true) > 0);
  const threads = unread.map((th) => repliesOf(th).filter((e) => !e.status).at(-1));
  await Promise.all([last, ...threads].map((e) => e && client.sendReadReceipt(e)));
  clearLocalUnread(room);
  unread.forEach((th) => clearLocalUnread(room, th.id));
  if (isMarkedUnread(room)) await setMarkedUnread(room, false);
}

/** Leave and drop it from the server's room list for good. */
export async function leaveAndForget(room: Room) {
  if (room.getMyMembership() !== "leave") {
    try {
      await client.leave(room.roomId);
    } catch (e) {
      if ((e as { errcode?: string }).errcode !== "M_FORBIDDEN") throw e;
      // not in the room at all: cached sync doesn't re-list forgotten rooms, so forget and restart
      await client.forget(room.roomId).catch(() => {});
      client.stopClient();
      await client.store.deleteAllData();
      location.reload();
      return;
    }
    // wait for the leave event to update the cached state before forgetting
    await new Promise<void>((done) => {
      const t = setTimeout(stop, 10_000);
      function stop() { clearTimeout(t); room.off(RoomEvent.MyMembership, check); done(); }
      function check() { if (room.getMyMembership() === "leave") stop(); }
      room.on(RoomEvent.MyMembership, check);
      check();
    });
  }
  await client.forget(room.roomId, true);
  await client.store.save(true); // ponytail: persist now; the next periodic save is minutes away
  if (location.hash.slice(1) === room.roomId) location.hash = "";
}
