import { client, openDM } from "./matrix.ts";
import { errText } from "./ui/common.tsx";
import { requestJump } from "./ui/Search.tsx";
import { parseMatrixHash, parseMatrixLink, type Target } from "./uri.ts";

/** Links that arrived before the first sync (cold start): the Shell drains them once it can navigate. */
const queue: string[] = [];
const EVENT = "panbeh-open-link";

/** Entry point for every platform: a matrix.to link or `matrix:` URI from outside or inside the app. */
export function handleIncomingLink(link: string) {
  queue.push(link);
  window.dispatchEvent(new Event(EVENT));
}

/** Calls `run` for queued and future links. Only call while synced. Returns an unsubscribe. */
export function drainLinks() {
  const flush = () => { for (const l of queue.splice(0)) openLink(l).catch((e) => alert(errText(e))); };
  window.addEventListener(EVENT, flush);
  flush();
  return () => window.removeEventListener(EVENT, flush);
}

const findRoom = (id: string) =>
  id.startsWith("!") ? client.getRoom(id) : client.getRooms().find((r) => r.getCanonicalAlias() === id || r.getAltAliases().includes(id));
const joinedRoom = (id: string) => { const r = findRoom(id); return r?.getMyMembership() === "join" ? r : null; };

async function openLink(link: string) {
  const t = parseMatrixLink(link) ?? parseMatrixHash(link);
  if (t) await openTarget(t);
}

export async function openTarget(t: Target) {
  if (t.kind === "user") {
    if (t.id === client.getUserId()) return;
    if (!confirm(`گفتگوی خصوصی با ${t.id} باز شود؟`)) return;
    location.hash = await openDM(t.id);
    return;
  }
  let roomId = joinedRoom(t.id)?.roomId;
  let via = t.via;
  if (!roomId) {
    if (!confirm(`به ${t.id} پیوسته شود؟`)) return;
    if (t.kind === "roomAlias") {
      const r = await client.getRoomIdForAlias(t.id);
      via = via.length ? via : r.servers;
    }
    roomId = (await client.joinRoom(t.id, via.length ? { viaServers: via } : undefined)).roomId;
  }
  location.hash = roomId;
  if (t.eventId) requestJump(roomId, t.eventId);
}
