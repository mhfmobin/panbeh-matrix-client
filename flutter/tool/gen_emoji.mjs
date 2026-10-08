// Run from the repo root: node flutter/tool/gen_emoji.mjs
// Same data and search text as src/ui/Emoji.tsx (emojibase en + CLDR fa), no skin tones (group 2 skipped).
import { readFileSync, writeFileSync } from "node:fs";

const en = JSON.parse(readFileSync("node_modules/emojibase-data/en/compact.json", "utf8"));
const fa = JSON.parse(readFileSync("node_modules/cldr-annotations-modern/annotations/fa/annotations.json", "utf8")).annotations.annotations;
const GROUPS = [0, 1, 3, 4, 5, 6, 7, 8, 9];
const strip = (s) => s.replace(/️/g, "");
// same as normalize() in lib/logic.dart
const normalize = (s) => s.toLowerCase().replaceAll("ي", "ی").replaceAll("ك", "ک").replaceAll("‌", "");
const names = new Map(Object.entries(fa).map(([k, v]) => [strip(k), v]));
const groups = new Map(GROUPS.map((g) => [g, []]));
for (const e of en.sort((a, b) => (a.order ?? 0) - (b.order ?? 0))) {
  if (!groups.has(e.group)) continue;
  const f = names.get(strip(e.unicode));
  const name = f?.tts?.[0] ?? e.label;
  // words repeated in name/label/tags are dropped: `contains` search gives the same hits
  const search = [...new Set(normalize([name, ...(f?.default ?? []), e.label, ...(e.tags ?? [])].join(" ")).split(" "))].join(" ");
  groups.get(e.group).push([e.unicode, name, search]);
}
writeFileSync("flutter/assets/emoji.json", JSON.stringify(GROUPS.map((g) => groups.get(g))));
