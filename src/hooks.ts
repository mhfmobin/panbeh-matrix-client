import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { ClientEvent, EventType, M_POLL_START, RoomEvent, RoomMemberEvent, RoomStateEvent, UserEvent, type Room, type MatrixEvent } from "matrix-js-sdk";
import { client, getUploads, subscribeUploads } from "./matrix.ts";
import { byListOrder, lastSeen, spaceRooms, type RoomInfo } from "./logic.ts";
import { ARCHIVED, hasTag, isMarkedUnread, PINNED } from "./chats.ts";
import { isMuted } from "./notify.ts";

type Emitter = { on(e: string, f: () => void): unknown; off(e: string, f: () => void): unknown };

/** Re-render when any of `events` fire (React batches bursts). The SDK is the store. */
export function useTick(emitter: Emitter | undefined, events: string[]) {
  const [, set] = useState(0);
  const key = events.join();
  useEffect(() => {
    if (!emitter) return;
    const bump = () => set((n) => n + 1);
    events.forEach((e) => emitter.on(e, bump));
    bump(); // catch anything that fired between render and subscribe
    return () => { events.forEach((e) => emitter.off(e, bump)); };
  }, [emitter, key]); // eslint-disable-line react-hooks/exhaustive-deps
}

type Seen = { online: boolean; ts: number; at: number };
const seenCache = new Map<string, Seen>();
const SEEN_TTL = 60_000;

/** Last-seen text for a user (null = unknown) and whether they're online.
 *  /sync's last_active_ago is unreliable (Synapse reported ~30s for people idle for hours), so ask /presence,
 *  cached for a minute. Sync only supplies the live online flag. */
export function usePresence(userId: string | undefined) {
  useTick(client, [UserEvent.Presence, UserEvent.CurrentlyActive]);
  const [, bump] = useState(0);
  useEffect(() => {
    const c = userId ? seenCache.get(userId) : undefined;
    if (!userId || (c && Date.now() - c.at < SEEN_TTL)) return;
    let live = true;
    client.getPresence(userId).then((r) => {
      seenCache.set(userId, { online: r.presence === "online" || !!r.currently_active, ts: Date.now() - (r.last_active_ago ?? Infinity), at: Date.now() });
      if (live) bump((n) => n + 1);
    }, () => {}); // not every server has presence on
    return () => { live = false; };
  }, [userId]);
  const c = userId ? seenCache.get(userId) : undefined;
  const online = (userId ? client.getUser(userId)?.presence === "online" : false) || !!c?.online;
  return { text: lastSeen(online, c?.ts), online };
}

/** Uploads still in flight for this room/thread. */
export function useUploads(roomId: string, threadId: string | null) {
  const all = useSyncExternalStore(subscribeUploads, getUploads);
  return useMemo(() => all.filter((u) => u.roomId === roomId && u.threadId === threadId), [all, roomId, threadId]);
}

export function usePromise<T>(p: Promise<T> | null | undefined): T | undefined {
  const [v, setV] = useState<{ p: unknown; v: T }>();
  useEffect(() => {
    let live = true;
    p?.then((v) => live && setV({ p, v }), () => {});
    return () => { live = false; };
  }, [p]);
  return v && v.p === p ? v.v : undefined;
}

// ---------- rooms ----------

const ROOM_LIST_EVENTS = [
  ClientEvent.Room, ClientEvent.DeleteRoom, ClientEvent.AccountData,
  RoomEvent.Timeline, RoomEvent.Name, RoomEvent.MyMembership, RoomEvent.UnreadNotifications,
  RoomEvent.Receipt, RoomEvent.LocalEchoUpdated, RoomStateEvent.Events, RoomMemberEvent.Typing, "Event.decrypted",
  RoomEvent.Tags, RoomEvent.AccountData, // pin/archive, marked unread
];

export type RoomRow = RoomInfo & { room: Room; last?: MatrixEvent; ts: number; invite: boolean; pinned: boolean };

export function useRooms() {
  useTick(client, ROOM_LIST_EVENTS);
  const all = client.getVisibleRooms().filter((r) => ["join", "invite"].includes(r.getMyMembership())); // visible = minus rooms replaced by a joined upgrade
  const direct = client.getAccountData(EventType.Direct)?.getContent<Record<string, string[]>>() ?? {};
  const dmIds = new Set(Object.values(direct).flat());

  const spaces = all.filter((r) => r.isSpaceRoom() && r.getMyMembership() === "join");
  const children = (id: string) =>
    client.getRoom(id)?.currentState.getStateEvents(EventType.SpaceChild)
      .filter((e) => Array.isArray(e.getContent().via)).map((e) => e.getStateKey()!) ?? [];
  const membership = new Map<string, string[]>();
  // top-level = not a child of another joined space
  const nested = new Set(spaces.flatMap((s) => children(s.roomId)));
  const topSpaces = spaces.filter((s) => !nested.has(s.roomId));
  for (const s of topSpaces) for (const id of spaceRooms(s.roomId, children)) membership.set(id, [...(membership.get(id) ?? []), s.roomId]);

  // space invites are listed like any invite; joined spaces become folders instead
  const rows: RoomRow[] = all.filter((r) => !r.isSpaceRoom() || r.getMyMembership() === "invite").map((room) => {
    const last = lastMessage(room);
    const invite = room.getMyMembership() === "invite";
    return {
      id: room.roomId,
      room,
      last,
      ts: last?.getTs() ?? room.getLastActiveTimestamp(),
      invite,
      isDM: dmIds.has(room.roomId) || !!room.getDMInviter(),
      unread: room.getUnreadNotificationCount(),
      spaces: membership.get(room.roomId) ?? [],
      pinned: hasTag(room, PINNED),
      archived: hasTag(room, ARCHIVED),
      marked: !invite && isMarkedUnread(room),
      muted: !invite && isMuted(room),
    };
  });
  rows.sort(byListOrder);
  return { rows, spaces: topSpaces };
}

// getType() is the decrypted type once decrypted; m.room.encrypted = still pending or failed
const MESSAGE_TYPES: string[] = [EventType.RoomMessage, EventType.Sticker, EventType.RoomMessageEncrypted, M_POLL_START.name, M_POLL_START.altName];
// blocked senders are hidden here too: some servers (Conduit) ignore m.ignored_user_list and keep sending their events
export const isMessage = (e: MatrixEvent) =>
  MESSAGE_TYPES.includes(e.getType()) && !e.isRedacted() && !e.isRelation("m.replace") && !client.isUserIgnored(e.getSender()!)
  && e.getContent().msgtype !== "m.key.verification.request"; // shown as a notice

function lastMessage(room: Room) {
  const evs = room.getLiveTimeline().getEvents();
  for (let i = evs.length - 1; i >= 0; i--) if (isMessage(evs[i])) return evs[i];
}
