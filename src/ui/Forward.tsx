import { useState } from "react";
import type { MatrixEvent } from "matrix-js-sdk";
import { forwardTo } from "../matrix.ts";
import { useRooms } from "../hooks.ts";
import { num } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { errText, RoomAvatar, Sheet } from "./common.tsx";

export function ForwardSheet({ ev, onClose }: { ev: MatrixEvent; onClose: () => void }) {
  const { rows } = useRooms();
  const [q, setQ] = useState("");
  const [sel, setSel] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const shown = rows.filter((r) => !r.invite && r.room.name.toLowerCase().includes(q.trim().toLowerCase()));
  const toggle = (id: string) => setSel((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  const send = async () => {
    setBusy(true);
    try {
      await Promise.all(sel.map((id) => forwardTo(id, ev)));
      onClose();
      if (sel.length === 1) location.hash = sel[0];
    } catch (e) {
      alert(errText(e));
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
