const P = {
  send: "M3.4 20.4 21 12 3.4 3.6 3.4 10l12.6 2-12.6 2z",
  attach: "M16.5 6.5v10a4.5 4.5 0 0 1-9 0V5a3 3 0 0 1 6 0v10.5a1.5 1.5 0 0 1-3 0V6.5",
  smile: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM8.5 14.5s1.2 2 3.5 2 3.5-2 3.5-2M9 9.5h.01M15 9.5h.01",
  forward: "M14 8V4l7 7-7 7v-4.1c-5 0-8.5 1.6-11 5.1 1-5 4-10 11-11z",
  reply: "M10 8V4L3 11l7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z",
  edit: "M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4",
  trash: "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6",
  thread: "M4 5h16v11H9l-5 4V5zM8 9h8M8 12h5",
  close: "M6 6l12 12M18 6 6 18",
  settings: "M4 6h16M4 12h16M4 18h16",
  search: "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4",
  check: "M5 12.5 10 17 19 7",
  checks: "M2 12.5 6.5 17 15 7M11 16l1 1 9-10",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2",
  file: "M14 3H6v18h12V7l-4-4zM14 3v4h4",
  back: "M15 5l-7 7 7 7",
  down: "M6 9l6 6 6-6",
  lock: "M6 11h12v10H6zM8 11V8a4 4 0 0 1 8 0v3",
  pencil: "M4 20h4L19 9l-4-4L4 16v4z",
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01",
  user: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0",
  group: "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2 20a7 7 0 0 1 14 0M16 4.5a3.5 3.5 0 0 1 0 6.5M18 13.5a7 7 0 0 1 4 6.5",
  space: "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
  link: "M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1",
  plus: "M12 5v14M5 12h14",
  mic: "M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zM5 11a7 7 0 0 0 14 0M12 18v3",
  play: "M8 5v14l11-7z",
  pause: "M7 5h3v14H7zM14 5h3v14h-3z",
  stop: "M7 7h10v10H7z",
  pin: "M9 4h6l-1 6 3 3H7l3-3zM12 13v8",
  poll: "M5 20V10M12 20V4M19 20v-7",
  location: "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21zM12 12a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z",
  bell: "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0",
  bellOff: "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0M3 3l18 18",
  list: "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01",
  download: "M12 4v11M7 10l5 5 5-5M5 20h14",
  chevron: "M9 5l7 7-7 7",
  archive: "M3 4h18v4H3zM5 8v12h14V8M10 12h4",
  copy: "M9 9h11v11H9zM5 15V4h11",
  select: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM8 12.5l3 3 5-6",
  unread: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 12h.01",
};

export type IconName = keyof typeof P;
const FLIP = new Set<IconName>(["back", "send", "reply", "forward", "chevron"]); // directional, mirrored for RTL
const FILLED = new Set<IconName>(["send", "play", "pause", "stop"]);

export const Icon = ({ name, size = 20 }: { name: IconName; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
    strokeLinecap="round" strokeLinejoin="round" aria-hidden style={FLIP.has(name) ? { transform: "scaleX(-1)" } : undefined}>
    <path d={P[name]} fill={FILLED.has(name) ? "currentColor" : "none"} />
  </svg>
);
