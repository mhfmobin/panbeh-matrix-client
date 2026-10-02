import { useState } from "react";
import type { RoomRow } from "../hooks.ts";
import { normalize, num } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { RoomAvatar, Sheet } from "./common.tsx";

const MAX = 8;

/** Ctrl/Cmd+K: jump to any chat (archived too) by name. */
export function QuickSwitch({ rows, onPick, onClose }: { rows: RoomRow[]; onPick: (id: string) => void; onClose: () => void }) {
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const n = normalize(q.trim());
  const hits = rows.filter((r) => !n || normalize(r.room.name).includes(n)).slice(0, MAX);
  const i = Math.min(sel, hits.length - 1);
  return (
    <Sheet title="رفتن به گفتگو" onClose={onClose}>
      <label className="search">
        <Icon name="search" size={16} />
        <input autoFocus value={q} placeholder="نام گفتگو" aria-label="نام گفتگو"
          onChange={(e) => { setQ(e.target.value); setSel(0); }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); setSel((i + (e.key === "ArrowDown" ? 1 : hits.length - 1)) % (hits.length || 1)); }
            if (e.key === "Enter" && hits[i]) onPick(hits[i].id);
          }} />
      </label>
      <div className="quick-list" role="listbox" aria-label="گفتگوها">
        {hits.map((r, k) => (
          <button key={r.id} role="option" aria-selected={k === i} className={"user-row" + (k === i ? " on" : "")}
            onMouseEnter={() => setSel(k)} onClick={() => onPick(r.id)}>
            <RoomAvatar room={r.room} size={36} />
            <span><b>{r.room.name}</b>{r.archived && <small>بایگانی</small>}</span>
            {r.unread > 0 && <span className={"badge" + (r.muted ? " muted" : "")}>{num(r.unread)}</span>}
          </button>
        ))}
        {!hits.length && <p className="muted">گفتگویی پیدا نشد</p>}
      </div>
      <p className="kbd-hint">↑ ↓ برای انتخاب، Enter برای باز کردن، Esc برای بستن</p>
    </Sheet>
  );
}
