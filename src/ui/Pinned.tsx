import { useMemo, useState } from "react";
import { EventType, RoomStateEvent, type Room } from "matrix-js-sdk";
import { client, loadEvent, pinnedIds, togglePin } from "../matrix.ts";
import { usePromise, useTick } from "../hooks.ts";
import { num } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { Avatar, errText, me, previewText, senderMember, senderName, Sheet } from "./common.tsx";
import { alertDialog } from "./dialog.tsx";

/** Telegram-style bar under the header: shows one pin; a click jumps to it and moves on to the next older one. */
export function PinnedBar({ room, onJump }: { room: Room; onJump: (id: string) => Promise<boolean> }) {
  useTick(client, [RoomStateEvent.Events]);
  const ids = pinnedIds(room);
  const [idx, setIdx] = useState<number | null>(null); // null = the newest
  const [list, setList] = useState(false);
  const i = idx == null || idx >= ids.length ? ids.length - 1 : idx;
  const ev = usePromise(useMemo(() => (ids[i] ? loadEvent(room, ids[i]) : null), [room, ids[i]])); // eslint-disable-line react-hooks/exhaustive-deps
  if (!ids.length) return null;

  const jump = async (id: string) => { if (!(await onJump(id))) setList(true); };
  return (
    <>
      <div className="pinned-bar">
        <button className="pinned-main" onClick={() => { jump(ids[i]); setIdx((i - 1 + ids.length) % ids.length); }}>
          <span className="pinned-ticks">{ids.length > 1 && ids.slice(-4).map((id) => <i key={id} className={id === ids[i] ? "on" : ""} />)}</span>
          <span className="pinned-text">
            <b>پیام سنجاق‌شده{ids.length > 1 && ` ${num(i + 1)} از ${num(ids.length)}`}</b>
            <span dir="auto">{ev ? previewText(ev) : "…"}</span>
          </span>
        </button>
        <button className="icon-btn" onClick={() => setList(true)} title="همه‌ی پیام‌های سنجاق‌شده" aria-label="همه‌ی پیام‌های سنجاق‌شده"><Icon name="list" /></button>
      </div>
      {list && <PinnedList room={room} ids={ids} onJump={(id) => { setList(false); onJump(id); }} onClose={() => setList(false)} />}
    </>
  );
}

function PinnedList({ room, ids, onJump, onClose }: { room: Room; ids: string[]; onJump: (id: string) => void; onClose: () => void }) {
  const canPin = room.currentState.maySendStateEvent(EventType.RoomPinnedEvents, me());
  return (
    <Sheet title={`پیام‌های سنجاق‌شده (${num(ids.length)})`} onClose={onClose}>
      {[...ids].reverse().map((id) => <PinnedRow key={id} room={room} id={id} canPin={canPin} onJump={onJump} />)}
    </Sheet>
  );
}

function PinnedRow({ room, id, canPin, onJump }: { room: Room; id: string; canPin: boolean; onJump: (id: string) => void }) {
  const ev = usePromise(useMemo(() => loadEvent(room, id), [room, id]));
  return (
    <div className="user-row">
      <button className="pinned-row" onClick={() => onJump(id)}>
        {ev ? <Avatar mxc={senderMember(ev)?.getMxcAvatarUrl()} name={senderName(ev)} id={ev.getSender()!} size={42} /> : <Avatar name="…" id={id} size={42} />}
        <span><b>{ev ? senderName(ev) : "…"}</b><small dir="auto">{ev ? previewText(ev) : "در حال بارگذاری…"}</small></span>
      </button>
      {canPin && (
        <button className="icon-btn" title="برداشتن سنجاق" aria-label="برداشتن سنجاق" onClick={() => togglePin(room, id).catch((e) => alertDialog(errText(e)))}>
          <Icon name="close" size={18} />
        </button>
      )}
    </div>
  );
}
