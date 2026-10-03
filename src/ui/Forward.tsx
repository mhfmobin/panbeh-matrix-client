import { useState } from "react";
import { M_POLL_START, type MatrixEvent } from "matrix-js-sdk";
import { forwardTo } from "../matrix.ts";
import { useRooms } from "../hooks.ts";
import { num } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { errText, RoomAvatar, Sheet } from "./common.tsx";
import { alertDialog } from "./dialog.tsx";

/** `evs` in timeline order; onSent runs after a successful send (the caller leaves selection mode). */
export function ForwardSheet({ evs, onClose, onSent }: { evs: MatrixEvent[]; onClose: () => void; onSent?: () => void }) {
  const { rows } = useRooms();
  const [q, setQ] = useState("");
  const [sel, setSel] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const shown = rows.filter((r) => !r.invite && r.room.name.toLowerCase().includes(q.trim().toLowerCase()));
  const toggle = (id: string) => setSel((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  const send = async () => {
    setBusy(true);
    try {
      const list = evs.filter((e) => !M_POLL_START.matches(e.getType())); // polls can't be forwarded
      await Promise.all(sel.map(async (id) => { for (const e of list) await forwardTo(id, e); })); // rooms in parallel, messages in order
      onSent?.();
      onClose();
      if (sel.length === 1) location.hash = sel[0];
    } catch (e) {
      alertDialog(errText(e));
      setBusy(false);
    }
  };
  return (
    <Sheet title="هدایت به…" onClose={onClose}>
      <label className="search">
        <Icon name="search" size={16} />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="جستجو" aria-label="جستجو" autoFocus />
      </label>
      {shown.map(({ room }) => (
        <button key={room.roomId} className="user-row" role="checkbox" aria-checked={sel.includes(room.roomId)} onClick={() => toggle(room.roomId)}>
          <RoomAvatar room={room} size={42} /><span><b>{room.name}</b></span>
          {sel.includes(room.roomId) && <Icon name="check" size={18} />}
        </button>
      ))}
      <button className="primary" disabled={!sel.length || busy} onClick={send}>
        {busy ? "در حال ارسال…" : `ارسال به ${num(sel.length)} گفتگو`}
      </button>
    </Sheet>
  );
}
