// Chat-list state kept on the server: tags (pin/archive) and "marked as unread".
import { RoomEvent, type Room } from "matrix-js-sdk";
import { client } from "./matrix.ts";

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

/** Receipt on the newest event + clear the manual mark.
 *  ponytail: thread receipts aren't touched; threads keep their own unread counts. */
export async function markRead(room: Room) {
  const last = room.getLiveTimeline().getEvents().filter((e) => !e.status).at(-1);
  if (last) await client.sendReadReceipt(last);
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
