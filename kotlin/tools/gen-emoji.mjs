// usage: npm i emojibase-data@17 cldr-annotations-modern@45 in DIR, then node gen-emoji.mjs DIR ../app/src/main/assets/emoji.json
// builds kotlin/app/src/main/assets/emoji.json from emojibase (en/compact) + CLDR fa annotations, like src/ui/Emoji.tsx
import fs from "node:fs";
const dir = process.argv[2], out = process.argv[3];
const en = JSON.parse(fs.readFileSync(`${dir}/node_modules/emojibase-data/en/compact.json`, "utf8"));
const fa = JSON.parse(fs.readFileSync(`${dir}/node_modules/cldr-annotations-modern/annotations/fa/annotations.json`, "utf8"));
const strip = (s) => s.replace(/️/g, "");
const names = new Map(Object.entries(fa.annotations.annotations).map(([k, v]) => [strip(k), v]));
const GROUPS = [[0, "😀", "شکلک‌ها"], [1, "👋", "آدم‌ها"], [3, "🐱", "حیوانات و طبیعت"], [4, "🍔", "خوراکی‌ها"],
  [5, "✈️", "سفر و مکان‌ها"], [6, "⚽", "فعالیت‌ها"], [7, "💡", "اشیا"], [8, "❤️", "نمادها"], [9, "🏳️", "پرچم‌ها"]];
const byGroup = new Map();
for (const e of en.sort((a, b) => (a.order ?? 0) - (b.order ?? 0))) {
  if (e.group == null || e.group === 2) continue;
  const f = names.get(strip(e.unicode));
  const name = f?.tts?.[0] ?? e.label;
  if (!byGroup.has(e.group)) byGroup.set(e.group, []);
  byGroup.get(e.group).push([e.unicode, name, [...new Set([...(f?.default ?? []), e.label, ...(e.tags ?? [])])].join(" ")]);
}
fs.writeFileSync(out, JSON.stringify(GROUPS.map(([id, icon, label]) => ({ id, icon, label, emoji: byGroup.get(id) ?? [] }))));
