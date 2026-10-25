import { t } from "../i18n.ts";
import { useEffect, useState } from "react";
import { EventTimeline, KnownMembership, type MatrixEvent, type Room } from "matrix-js-sdk";
import { client, isEncrypted, searchOlder, searchServer } from "../matrix.ts";
import { isMessage } from "../hooks.ts";
import { backfill, backfillDone, indexOn, searchIndex } from "../searchIndex.ts";
import { hashtagsOf, matchRange, normalize, soleHashtag, stamp } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { Avatar, previewText, senderMember, senderName, Sheet } from "./common.tsx";
import { useSlider } from "./useSlider.ts";

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
  const hit = matchRange(text, term.trim());
  const name = senderName(ev);
  return (
    <button className="user-row result-row" onClick={onClick}>
      <Avatar mxc={senderMember(ev)?.getMxcAvatarUrl()} name={name} id={ev.getSender()!} size={42} />
      <span>
        <b>{showRoom ? `${client.getRoom(ev.getRoomId())?.name} · ${name}` : name}</b>
        <small dir="auto">
          {!hit ? text : <>{text.slice(0, hit[0])}<mark>{text.slice(...hit)}</mark>{text.slice(hit[1])}</>}
        </small>
      </span>
      <small>{stamp(ev.getTs())}</small>
    </button>
  );
}

/** Server results, or none if the server couldn't search (flagged through `failed`). */
const serverOr = (p: Promise<MatrixEvent[]>, failed: () => void) => p.catch(() => { failed(); return []; });

/** Debounced term (>= MIN chars) -> results, and whether the server search failed; `run` is re-evaluated whenever `deps` change. */
function useResults(term: string, run: (t: string, failed: () => void) => Promise<MatrixEvent[]> | MatrixEvent[], deps: unknown[] = []) {
  const [res, setRes] = useState<{ evs: MatrixEvent[]; failed: boolean } | null>(null);
  useEffect(() => {
    const t = term.trim();
    if (t.length < MIN) { setRes(null); return; }
    let live = true, failed = false;
    setRes(null);
    const tag = soleHashtag(t); // every source matches substrings: "#tag" would find "#tagging" too
    const h = setTimeout(async () => {
      let evs = await run(tag ? t.slice(1) : t, () => { failed = true; }); // servers' full-text search may choke on "#"
      if (tag) evs = evs.filter((e) => hashtagsOf(previewText(e)).some((x) => normalize(x) === tag));
      if (live) setRes({ evs, failed });
    }, 300);
    return () => { live = false; clearTimeout(h); };
  }, [term, ...deps]);
  return res;
}

const ServerFailed = () => <p className="muted">{t("search.serverFailed")}</p>;

export function SearchSheet({ room, initial = "", onJump, onClose }: { room: Room; initial?: string; onJump: (eventId: string, fromServer: boolean) => void; onClose: () => void }) {
  const [q, setQ] = useState(initial);
  const [all, setAll] = useState(false); // across all chats
  const pill = useSlider<HTMLDivElement>();
  const enc = isEncrypted(room);
  const indexed = enc && indexOn(); // search the local index, and "older" indexes further back instead of loading the timeline
  const [more, setMore] = useState(() => indexed || !!room.getLiveTimeline().getPaginationToken(EventTimeline.BACKWARDS));
  useEffect(() => { if (indexed) backfillDone(room.roomId).then((d) => setMore(!d), () => {}); }, [room, indexed]);
  const [paging, setPaging] = useState(false);
  const [round, setRound] = useState(0); // bumped after paging back: rescan
  const found = useResults(q, (t, failed) => (indexed ? searchIndex(t, room.roomId).then((ix) => merge([...ix, ...scanLoaded(room, t)]), () => scanLoaded(room, t))
    : enc ? scanLoaded(room, t)
    // the server doesn't fold ي/ك or ZWNJ; the local scan catches loaded messages it misses
    // offline: what the device has indexed
    : searchServer(t, room.roomId).catch(() => { failed(); return indexOn() ? searchIndex(t, room.roomId).catch(() => []) : []; })
      .then((s) => merge([...s, ...scanLoaded(room, t)]))), [round]);
  const res = found?.evs;
  const older = async () => {
    setPaging(true);
    setMore(await (indexed ? backfill(room) : searchOlder(room)).catch(() => true));
    setPaging(false);
    setRound((r) => r + 1);
  };
  const ready = q.trim().length >= MIN;
  return (
    <Sheet title={t("search.inChat")} onClose={onClose}>
      <div className="picker">
        <label className="search">
          <Icon name="search" size={16} />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("common.search")} aria-label={t("search.inChat")} autoFocus={!initial} />
        </label>
        <div className="segmented" data-pill ref={pill}>
          <button className={all ? "" : "on"} onClick={() => setAll(false)}>{t("search.thisChat")}</button>
          <button className={all ? "on" : ""} onClick={() => setAll(true)}>{t("search.allChats")}</button>
        </div>
        {all ? <MessageResults term={q} onPick={(roomId, eventId) => { onClose(); requestJump(roomId, eventId); location.hash = roomId; }} /> : <>
        {enc && <p className="muted">{indexed ? t("search.indexed") : t("search.loadedOnly")}</p>}
        {ready && !res && <p className="muted">{t("search.searching")}</p>}
        {found?.failed && <ServerFailed />}
        {res?.length === 0 && <p className="muted">{t("search.none")}</p>}
        {res?.map((ev) => <ResultRow key={ev.getId()} ev={ev} term={q} onClick={() => { onClose(); onJump(ev.getId()!, !enc); }} />)}
        {enc && ready && more && <button className="primary" disabled={paging} onClick={older}>{paging ? t("search.searching") : t("search.older")}</button>}
        </>}
      </div>
    </Sheet>
  );
}

/** Sidebar: messages matching across all joined chats. */
// ponytail: without the search index, global search only scans what's loaded in encrypted rooms; per-chat search can page back.
export function MessageResults({ term, onPick }: { term: string; onPick: (roomId: string, eventId: string) => void }) {
  const found = useResults(term, async (t, failed) => {
    const joined = client.getRooms().filter((r) => r.getMyMembership() === KnownMembership.Join);
    const server = (await serverOr(searchServer(t), failed)).filter((e) => joined.some((r) => r.roomId === e.getRoomId()));
    const local = joined.filter((r) => !r.isSpaceRoom() && isEncrypted(r)).flatMap((r) => scanLoaded(r, t));
    if (indexOn()) local.push(...(await searchIndex(t).catch(() => [])).filter((e) => joined.some((r) => r.roomId === e.getRoomId())));
    return merge([...server, ...local]).slice(0, 50);
  });
  if (term.trim().length < MIN) return null;
  const res = found?.evs;
  return (
    <div className="msg-results">
      <h3>{t("search.messages")}</h3>
      {!res && <p className="muted">{t("search.searching")}</p>}
      {found?.failed && <ServerFailed />}
      {res?.length === 0 && <p className="muted">{t("search.none")}</p>}
      {res?.map((ev) => <ResultRow key={ev.getId()} ev={ev} term={term} showRoom onClick={() => onPick(ev.getRoomId()!, ev.getId()!)} />)}
    </div>
  );
}
