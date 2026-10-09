// A local index of decrypted messages, so encrypted chats can be searched beyond what's loaded (the server can't
// search them). Off by default: it keeps message text in plain form on the device. Fed as messages decrypt — while
// scrolling, from background sync on Android, and by backfill() paging a chat's history.
import { EventType, MatrixEvent, MatrixEventEvent, RoomEvent, type Room } from "matrix-js-sdk";
import { client, historyPage } from "./matrix.ts";
import { isMessage } from "./hooks.ts";
import { normalize } from "./logic.ts";

type Rec = { id: string; roomId: string; ts: number; sender: string; text: string };
type Meta = { roomId: string; token: string | null; done: boolean }; // where backfill goes on in a room

export const indexName = (userId: string) => `panbeh-search:${userId}`;
// Settings writes the pref; read here directly (Settings imports this module)
export const indexOn = () => { try { return !!JSON.parse(localStorage.getItem("panbeh.prefs") ?? "{}").searchIndex; } catch { return false; } };

let db: Promise<IDBDatabase> | null = null;
function open() {
  return db ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(indexName(client.getSafeUserId()), 1);
    r.onupgradeneeded = () => {
      const msgs = r.result.createObjectStore("msgs", { keyPath: "id" });
      msgs.createIndex("room_ts", ["roomId", "ts"]);
      msgs.createIndex("ts", "ts");
      r.result.createObjectStore("meta", { keyPath: "roomId" });
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => { db = null; reject(r.error); };
  });
}
const done = (t: IDBTransaction) => new Promise<void>((res, rej) => { t.oncomplete = () => res(); t.onerror = t.onabort = () => rej(t.error); });
const req = <T>(r: IDBRequest<T>) => new Promise<T>((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });

/** What a message is found by: its text (no reply quote), a caption, or a file's name. */
function textOf(c: Record<string, unknown>) {
  const body = typeof c.body === "string" ? c.body.replace(/^(> .*\n)+\n?/, "") : "";
  return typeof c.filename === "string" && c.filename !== body ? `${body} ${c.filename}` : body;
}

// ---------- writing: batched, one transaction a second ----------

const puts = new Map<string, Rec>();
const edits = new Map<string, { sender: string; text: string }>(); // original id → its new text
const dels = new Set<string>();
let timer = 0;
const schedule = () => { if (!timer) timer = window.setTimeout(() => void flush(), 1000); };

/** Writes what's queued. backfill() awaits it so a search right after sees the page. */
export async function flush() {
  clearTimeout(timer);
  timer = 0;
  if (!puts.size && !edits.size && !dels.size) return;
  const p = [...puts.values()], e = [...edits], d = [...dels];
  puts.clear(); edits.clear(); dels.clear();
  try {
    const t = (await open()).transaction("msgs", "readwrite");
    const s = t.objectStore("msgs");
    for (const r of p) s.put(r);
    for (const [id, edit] of e) {
      s.get(id).onsuccess = (ev) => { // only the author's edits count, and only of messages we have
        const r = (ev.target as IDBRequest<Rec | undefined>).result;
        if (r && r.sender === edit.sender) s.put({ ...r, text: edit.text });
      };
    }
    for (const id of d) s.delete(id);
    await done(t);
  } catch (err) { console.warn("search index write failed", err); }
}

function add(ev: MatrixEvent) {
  if (!indexOn() || ev.isDecryptionFailure() || ev.getType() !== EventType.RoomMessage) return;
  const id = ev.getId(), roomId = ev.getRoomId();
  if (!id || !roomId || ev.status) return;
  if (ev.isRelation("m.replace")) {
    const target = ev.getRelation()?.event_id, nc = ev.getContent()["m.new_content"];
    if (target && nc) { edits.set(target, { sender: ev.getSender()!, text: textOf(nc) }); schedule(); }
    return;
  }
  if (!isMessage(ev)) return;
  const text = textOf(ev.getContent()); // with its latest edit, if that arrived first
  if (!text.trim()) return;
  puts.set(id, { id, roomId, ts: ev.getTs(), sender: ev.getSender()!, text });
  schedule();
}

/** Indexes messages as they decrypt (only events that were encrypted decrypt, so only encrypted chats). Returns an unsubscribe. */
export function startSearchIndex() {
  const onDecrypted = (ev: MatrixEvent) => add(ev);
  const onRedaction = (ev: MatrixEvent) => { const id = ev.getAssociatedId(); if (id && indexOn()) { dels.add(id); schedule(); } };
  client.on(MatrixEventEvent.Decrypted, onDecrypted);
  client.on(RoomEvent.Redaction, onRedaction);
  return () => { client.off(MatrixEventEvent.Decrypted, onDecrypted); client.off(RoomEvent.Redaction, onRedaction); void flush(); };
}

/** Turned on: take in what's already loaded and decrypted, which won't decrypt again. */
export function indexLoaded() {
  for (const room of client.getRooms()) if (room.hasEncryptionStateEvent()) room.getLiveTimeline().getEvents().forEach(add);
}

/** Turned off, or signing out: the index goes. */
export async function dropIndex() {
  puts.clear(); edits.clear(); dels.clear();
  const d = await db?.catch(() => null);
  d?.close();
  db = null;
  await req(indexedDB.deleteDatabase(indexName(client.getSafeUserId()))).catch(() => {});
}

// ---------- reading ----------

/** Indexed messages containing `term`, newest first: in one room, or everywhere. As events for the result list. */
export async function searchIndex(term: string, roomId?: string, limit = 50): Promise<MatrixEvent[]> {
  if (!indexOn()) return [];
  await flush();
  const t = normalize(term.trim());
  const s = (await open()).transaction("msgs").objectStore("msgs");
  const cursor = roomId
    ? s.index("room_ts").openCursor(IDBKeyRange.bound([roomId, -Infinity], [roomId, Infinity]), "prev")
    : s.index("ts").openCursor(null, "prev");
  const out: MatrixEvent[] = [];
  // ponytail: a linear scan; fine for tens of thousands of messages, a word index if it gets slow
  await new Promise<void>((resolve, reject) => {
    cursor.onerror = () => reject(cursor.error);
    cursor.onsuccess = () => {
      const c = cursor.result;
      if (!c || out.length >= limit) return resolve();
      const r = c.value as Rec;
      if (normalize(r.text).includes(t)) out.push(new MatrixEvent({
        event_id: r.id, room_id: r.roomId, sender: r.sender, origin_server_ts: r.ts, type: EventType.RoomMessage, content: { msgtype: "m.text", body: r.text },
      }));
      c.continue();
    };
  });
  return out;
}

/** Whether a room's history has been indexed all the way back. */
export async function backfillDone(roomId: string) {
  const m = await req((await open()).transaction("meta").objectStore("meta").get(roomId)) as Meta | undefined;
  return !!m?.done;
}

/** Pages a room's history further back (from where the last call stopped) so it's indexed. Resolves to whether more remains. */
export async function backfill(room: Room, pages = 5): Promise<boolean> {
  const meta = (await req((await open()).transaction("meta").objectStore("meta").get(room.roomId)) as Meta | undefined) ?? { roomId: room.roomId, token: null, done: false };
  for (let i = 0; i < pages && !meta.done; i++) {
    const { end } = await historyPage(room, meta.token); // decrypting each event feeds the index
    meta.token = end;
    meta.done = !end;
    const t = (await open()).transaction("meta", "readwrite");
    t.objectStore("meta").put(meta);
    await done(t);
  }
  await flush();
  return !meta.done;
}
