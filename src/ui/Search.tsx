import { useEffect, useState } from "react";
import { EventTimeline, KnownMembership, type MatrixEvent, type Room } from "matrix-js-sdk";
import { client, isEncrypted, searchOlder, searchServer } from "../matrix.ts";
import { isMessage } from "../hooks.ts";
import { normalize, stamp } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { Avatar, previewText, senderMember, senderName, Sheet } from "./common.tsx";

/** A search result waiting for its room to open; `requestJump` also pings an already-open Room. */
export const pendingJump = { current: null as { roomId: string; eventId: string } | null };
export function requestJump(roomId: string, eventId: string) {
  pendingJump.current = { roomId, eventId };
  window.dispatchEvent(new Event("panbeh-jump"));
}

const MIN = 2;
const byNewest = (a: MatrixEvent, b: MatrixEvent) => b.getTs() - a.getTs();
/** Deduped by event id, newest first. */
const merge = (evs: MatrixEvent[]) => {
  const seen = new Set<string>();
  return evs.filter((e) => !seen.has(e.getId()!) && seen.add(e.getId()!)).sort(byNewest);
};

/** Loaded messages containing the term, newest first. */
export function scanLoaded(room: Room, term: string): MatrixEvent[] {
  const t = normalize(term.trim());
  return room.getLiveTimeline().getEvents().filter((e) => isMessage(e) && normalize(previewText(e)).includes(t)).sort(byNewest);
}

function ResultRow({ ev, term, showRoom, onClick }: { ev: MatrixEvent; term: string; showRoom?: boolean; onClick: () => void }) {
  const text = previewText(ev);
  const i = text.toLowerCase().indexOf(term.trim().toLowerCase());
  const name = senderName(ev);
  return (
    <button className="user-row" onClick={onClick}>
      <Avatar mxc={senderMember(ev)?.getMxcAvatarUrl()} name={name} id={ev.getSender()!} size={42} />
      <span>
        <b>{showRoom ? `${client.getRoom(ev.getRoomId())?.name} · ${name}` : name}</b>
        <small dir="auto">
          {i < 0 ? text : <>{text.slice(0, i)}<mark>{text.slice(i, i + term.trim().length)}</mark>{text.slice(i + term.trim().length)}</>}
        </small>
      </span>
      <small>{stamp(ev.getTs())}</small>
    </button>
  );
}

/** Debounced term (>= MIN chars) -> results; `run` is re-evaluated whenever `deps` change. */
function useResults(term: string, run: (t: string) => Promise<MatrixEvent[]> | MatrixEvent[], deps: unknown[] = []) {
  const [res, setRes] = useState<MatrixEvent[] | null>(null);
  useEffect(() => {
    const t = term.trim();
    if (t.length < MIN) { setRes(null); return; }
    let live = true;
    setRes(null);
    const h = setTimeout(async () => { const r = await run(t); if (live) setRes(r); }, 300);
    return () => { live = false; clearTimeout(h); };
  }, [term, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps
  return res;
}

export function SearchSheet({ room, onJump, onClose }: { room: Room; onJump: (eventId: string, fromServer: boolean) => void; onClose: () => void }) {
  const [q, setQ] = useState("");
  const enc = isEncrypted(room);
  const [more, setMore] = useState(() => !!room.getLiveTimeline().getPaginationToken(EventTimeline.BACKWARDS));
  const [paging, setPaging] = useState(false);
  const [round, setRound] = useState(0); // bumped after paging back: rescan
  const res = useResults(q, (t) => (enc ? scanLoaded(room, t)
    // the server doesn't fold ي/ك or ZWNJ; the local scan catches loaded messages it misses
    : searchServer(t, room.roomId).then((s) => merge([...s, ...scanLoaded(room, t)]))), [round]);
  const older = async () => { setPaging(true); setMore(await searchOlder(room)); setPaging(false); setRound((r) => r + 1); };
  const ready = q.trim().length >= MIN;
  return (
    <Sheet title="جستجو در گفتگو" onClose={onClose}>
      <div className="picker">
        <label className="search">
          <Icon name="search" size={16} />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="جستجو" aria-label="جستجو در گفتگو" autoFocus />
        </label>
        {enc && <p className="muted">در گفتگوهای رمزنگاری‌شده فقط پیام‌های بارگذاری‌شده جستجو می‌شوند</p>}
        {ready && !res && <p className="muted">در حال جستجو…</p>}
        {res?.length === 0 && <p className="muted">پیامی پیدا نشد</p>}
        {res?.map((ev) => <ResultRow key={ev.getId()} ev={ev} term={q} onClick={() => { onClose(); onJump(ev.getId()!, !enc); }} />)}
        {enc && ready && more && <button className="primary" disabled={paging} onClick={older}>{paging ? "در حال جستجو…" : "جستجو در پیام‌های قدیمی‌تر"}</button>}
      </div>
    </Sheet>
  );
}

/** Sidebar: messages matching across all joined chats. */
// ponytail: global search only scans what's loaded in encrypted rooms; per-chat search can page back.
export function MessageResults({ term, onPick }: { term: string; onPick: (roomId: string, eventId: string) => void }) {
  const res = useResults(term, async (t) => {
    const joined = client.getRooms().filter((r) => r.getMyMembership() === KnownMembership.Join);
    const server = (await searchServer(t)).filter((e) => joined.some((r) => r.roomId === e.getRoomId()));
    const local = joined.filter((r) => !r.isSpaceRoom() && isEncrypted(r)).flatMap((r) => scanLoaded(r, t));
    return merge([...server, ...local]).slice(0, 50);
  });
  if (term.trim().length < MIN) return null;
  return (
    <div className="msg-results">
      <h3>پیام‌ها</h3>
      {!res && <p className="muted">در حال جستجو…</p>}
      {res?.length === 0 && <p className="muted">پیامی پیدا نشد</p>}
      {res?.map((ev) => <ResultRow key={ev.getId()} ev={ev} term={term} showRoom onClick={() => onPick(ev.getRoomId()!, ev.getId()!)} />)}
    </div>
  );
}
