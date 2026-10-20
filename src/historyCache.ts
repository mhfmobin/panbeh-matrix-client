// Chats' history as it was loaded, kept on the device so it reads again offline (and pages back instantly online). The sync
// cache keeps only each chat's last 50 events; this keeps everything a chat's main timeline held, as runs of consecutive
// events: a gap in the history (back after a long time away) starts a new run, and paging back across it joins the two.
// Events are kept as they came off the wire, so encrypted ones stay encrypted at rest (the crypto store decrypts them offline).
// The SDK's own back-paging requests are answered from here (netFetch → cachedPage); nothing else knows about it.
// ponytail: no size cap; a Settings knob like the media cache's if it grows too big
import { EventStatus, EventTimeline, Method, RoomEvent, type IEvent, type MatrixEvent, type Room } from "matrix-js-sdk";
import { client } from "./matrix.ts";
import { cachedSlice } from "./logic.ts";

type Row = { id: string; roomId: string; run: string; seq: number; raw: Partial<IEvent> };
type Run = { id: string; roomId: string; back: string | null }; // back: the server's token before the run's oldest event; null at the chat's start
/** A live timeline's run. seq of its first event; minSeq is the run's oldest kept, older than the timeline's after a join. */
type Live = { run: string; first: string; firstSeq: number; minSeq: number; written: Map<string, number> };

export const historyName = (userId: string) => `panbeh-history:${userId}`;
const PREFIX = "pbcache."; // our page tokens: the event the next page goes back from

let db: Promise<IDBDatabase> | null = null;
function open() {
  return db ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(historyName(client.getSafeUserId()), 1);
    r.onupgradeneeded = () => {
      r.result.createObjectStore("events", { keyPath: "id" }).createIndex("run_seq", ["run", "seq"]);
      r.result.createObjectStore("runs", { keyPath: "id" });
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => { db = null; reject(r.error); };
  });
}
const done = (t: IDBTransaction) => new Promise<void>((res, rej) => { t.oncomplete = () => res(); t.onerror = t.onabort = () => rej(t.error); });
const req = <T>(r: IDBRequest<T>) => new Promise<T>((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
const put = async (store: string, v: unknown) => { const t = (await open()).transaction(store, "readwrite"); t.objectStore(store).put(v); await done(t); };
const get = async <T>(store: string, key: string) => req((await open()).transaction(store).objectStore(store).get(key)) as Promise<T | undefined>;
const before = (run: string, seq: number) => IDBKeyRange.bound([run, -Infinity], [run, seq], false, true);
/** Up to n of a run's rows, newest first ("prev") or oldest first ("next"). */
const rows = async (range: IDBKeyRange, n: number, dir: IDBCursorDirection = "prev") => {
  const c = (await open()).transaction("events").objectStore("events").index("run_seq").openCursor(range, dir);
  return new Promise<Row[]>((res, rej) => {
    const out: Row[] = [];
    c.onerror = () => rej(c.error);
    c.onsuccess = () => { const x = c.result; if (!x || out.length >= n) return res(out); out.push(x.value); x.continue(); };
  });
};

// ---------- keeping ----------

const live = new WeakMap<EventTimeline, Live>();
/** roomId → its live timeline's back token as last seen, and the event it goes back from: paging from it can come from here. */
const tokens = new Map<string, { token: string; oldest: string }>();

/** A timeline's first event already kept (after a restart, or a gap that joins an older run): carry on that run. */
async function adopt(first: string): Promise<Live> {
  const y = await get<Row>("events", first);
  if (!y) return { run: crypto.randomUUID(), first, firstSeq: 0, minSeq: 0, written: new Map() };
  const [oldest] = await rows(IDBKeyRange.bound([y.run, -Infinity], [y.run, Infinity]), 1, "next");
  return { run: y.run, first, firstSeq: y.seq, minSeq: oldest?.seq ?? y.seq, written: new Map() };
}

/** Paged back into an older run's event y: the older part of that run joins this one, shifted to line up. */
async function join(l: Live, y: Row) {
  const delta = l.firstSeq - y.seq;
  const t = (await open()).transaction(["events", "runs"], "readwrite");
  const c = t.objectStore("events").index("run_seq").openCursor(before(y.run, y.seq));
  c.onsuccess = () => {
    const x = c.result;
    if (!x) return;
    const v = x.value as Row;
    l.minSeq = Math.min(l.minSeq, v.seq + delta);
    x.update({ ...v, run: l.run, seq: v.seq + delta });
    x.continue();
  };
  const runs = t.objectStore("runs");
  runs.get(y.run).onsuccess = (e) => {
    const old = (e.target as IDBRequest<Run | undefined>).result;
    if (old) runs.put({ ...old, id: l.run });
    runs.delete(y.run);
  };
  await done(t);
}

/** The live timeline and the older ones paging back has linked it to, oldest first. The SDK drops a timeline's back token
 *  once it links it to an older one: the back token is the oldest's. Taken for the live one's alone, that null read as the
 *  chat's start, and after a restart paging stopped there. */
function liveRun(room: Room) {
  const tls = [room.getLiveTimeline()];
  for (let p; (p = tls[0].getNeighbouringTimeline(EventTimeline.BACKWARDS)); ) tls.unshift(p);
  return tls;
}

async function flush(room: Room) {
  const run = liveRun(room), tl = run.at(-1)!;
  const evs = run.flatMap((t) => t.getEvents()).filter((e) => !e.status && e.getId()?.startsWith("$")); // not local echoes
  if (!evs.length) return;
  let l = live.get(tl);
  if (!l) live.set(tl, l = await adopt(evs[0].getId()!));
  const k = evs.findIndex((e) => e.getId() === l.first); // rows paged in before it since last time
  if (k < 0) return; // ponytail: its first event left the timeline (not seen happen); kept again from the next restart
  l.firstSeq -= k;
  l.first = evs[0].getId()!;
  if (k) {
    const y = await get<Row>("events", l.first);
    if (y && y.run !== l.run) await join(l, y);
  }
  const t = (await open()).transaction(["events", "runs"], "readwrite");
  const s = t.objectStore("events");
  // positions settle as our own messages go out (local echoes are skipped until then): rewrite only what moved
  evs.forEach((e, i) => {
    const id = e.getId()!, seq = l.firstSeq + i;
    if (l.written.get(id) === seq) return;
    l.written.set(id, seq);
    s.put({ id, roomId: room.roomId, run: l.run, seq, raw: e.event } satisfies Row);
  });
  const back = run[0].getPaginationToken(EventTimeline.BACKWARDS);
  // the run's back token is the timeline's only while the timeline reaches back to the run's start
  if (l.firstSeq <= l.minSeq && !back?.startsWith(PREFIX)) {
    l.minSeq = l.firstSeq;
    t.objectStore("runs").put({ id: l.run, roomId: room.roomId, back } satisfies Run);
  }
  await done(t);
  if (back) tokens.set(room.roomId, { token: back, oldest: l.first });
}

const due = new Map<string, Room>();
let timer = 0;
let chain: Promise<unknown> = Promise.resolve(); // one write at a time: a flush reads what the last one wrote
function schedule(room: Room) {
  due.set(room.roomId, room);
  if (timer) return;
  timer = window.setTimeout(() => {
    timer = 0;
    const list = [...due.values()];
    due.clear();
    chain = chain.then(async () => { for (const r of list) await flush(r).catch((e) => console.warn("history cache write failed", e)); });
  }, 1000);
}

/** A redacted message mustn't come back from here, loaded or not. */
async function redact(id: string, by: Partial<IEvent>) {
  const t = (await open()).transaction("events", "readwrite");
  const s = t.objectStore("events");
  s.get(id).onsuccess = (e) => {
    const r = (e.target as IDBRequest<Row | undefined>).result;
    if (!r) return;
    const { event_id, room_id, sender, origin_server_ts, type, state_key } = r.raw;
    s.put({ ...r, raw: { event_id, room_id, sender, origin_server_ts, type, state_key, content: {}, unsigned: { redacted_because: by as IEvent } } });
  };
  await done(t);
}

/** Before startClient, so the cached sync's events are caught too. Not in the headless page. */
export function startHistoryCache() {
  // not RoomEvent.Redaction: that only comes for a target that's loaded. Ours come as local echoes.
  const redacted = (ev: MatrixEvent) => { const id = ev.getAssociatedId(); if (id) void redact(id, ev.event).catch(() => {}); };
  client.on(RoomEvent.Timeline, (ev, room, _start, _removed, data) => {
    if (ev.isRedaction()) redacted(ev);
    if (room && data.timeline && liveRun(room).includes(data.timeline)) schedule(room);
  });
  client.on(RoomEvent.LocalEchoUpdated, (ev, room) => {
    if (ev.isRedaction()) { if (ev.status === EventStatus.SENT) redacted(ev); }
    else schedule(room); // our message got its id
  });
}

// ---------- serving ----------

/** The SDK paging a chat back from where we have its history: that page, from here. null: ask the server. */
export async function cachedPage(url: string): Promise<Response | null> {
  const u = new URL(url);
  const m = u.pathname.match(/\/rooms\/([^/]+)\/messages$/);
  const from = u.searchParams.get("from");
  if (!m || !from || u.searchParams.get("dir") !== "b") return null;
  const roomId = decodeURIComponent(m[1]);
  const known = tokens.get(roomId);
  const at = from.startsWith(PREFIX) ? from.slice(PREFIX.length) : known?.token === from ? known.oldest : null;
  if (!at) return null;
  try {
    const x = await get<Row>("events", at);
    if (!x) return null;
    const n = Math.min(Number(u.searchParams.get("limit")) || 20, 100);
    const older = await rows(before(x.run, x.seq), n + 1);
    if (!older.length && !from.startsWith(PREFIX)) return null; // nothing kept before it: the server's page
    const run = await get<Run>("runs", x.run);
    let back = run?.back ?? null;
    const oldest = older.at(-1) ?? x;
    // kept before the fix in liveRun: a run "at the chat's start" that isn't. The server's token from before its oldest event
    if (!back && older.length <= n && oldest.raw.type !== "m.room.create") {
      back = (await client.http.authedRequest<{ start?: string }>(Method.Get, `/rooms/${encodeURIComponent(roomId)}/context/${encodeURIComponent(oldest.id)}`, { limit: "0" })).start ?? null;
      if (back && run) await put("runs", { ...run, back });
    }
    const { chunk, end } = cachedSlice(older, n, back, PREFIX);
    return new Response(JSON.stringify({ chunk: chunk.map((r) => r.raw), start: from, ...(end && { end }) }), { headers: { "Content-Type": "application/json" } });
  } catch (e) {
    console.warn("history cache read failed", e);
    return null;
  }
}
