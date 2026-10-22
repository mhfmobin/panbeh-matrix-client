import { LANGS, LOCALE, lang, setLang, t } from "../i18n.ts";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { SystemBars, SystemBarsStyle } from "@capacitor/core";
import { ClientEvent, PushRuleActionName, PushRuleKind, RuleId, TweakName, type PushRuleAction } from "matrix-js-sdk";
import { CryptoEvent, type DeviceVerificationStatus } from "matrix-js-sdk/lib/crypto-api/index.js";
import { accountManageUrl, addAccount, client, sessions, switchAccount, deleteDevices, deviceManageUrl, isOAuth, logout, NeedsPassword, quickReaction, recoveryState, setBlocked, setMyAvatar, setQuickReaction, setShareLastSeen, applyIsolation, setupRecovery, unlock, withPassword, type Quick } from "../matrix.ts";
import { useTick } from "../hooks.ts";
import { Icon, type IconName } from "../icons.tsx";
import { Avatar, errText, formatSize, me, Select, Sheet } from "./common.tsx";
import { CACHE_MB_DEFAULT, cacheClear, cacheSize, cacheTrim } from "../mediaCache.ts";
import { dropIndex, indexLoaded } from "../searchIndex.ts";
import { LockSettings } from "./Lock.tsx";
import { lockConf } from "../lock.ts";
import { useSlider } from "./useSlider.ts";
import { mergeAutoDl, num, stamp, type AutoDl, type DlKind } from "../logic.ts";
import { decryptKeyFile, encryptKeyFile } from "../keyfile.ts";
import { showVerification } from "./Verify.tsx";
import { appVersion, canUpdate, checkUpdate, downloadUpdate, getAutostart, getAutoUpdate, installUpdate, isDesktop, setAutostart, setAutoUpdate, useUpdate, type Update } from "../desktop.ts";
import { isBazaar, isNative, nativeCancelAll, nativeStatus, openNotifySettings, requestBatteryExemption, requestFullScreen, requestNotifyPermission, saveFile, setBackgroundInterval, setBackgroundService } from "../native.ts";
import { alertDialog, confirmDialog, editAvatar } from "./dialog.tsx";
import { LinkSheet } from "./LinkSheet.tsx";
import { MyPacks } from "./Packs.tsx";
import { EmojiPanel, ReactionKey } from "./Emoji.tsx";
import { pickerAt } from "./Message.tsx";
import { matrixToLink } from "../uri.ts";
import NOTES from "../releaseNotes.json";

type Prefs = { theme: "system" | "light" | "dark"; accent: string; wallpaper: string; notify: boolean; notifyDMs: boolean; notifyGroups: boolean; notifyPreview: "all" | "name" | "none"; previews: boolean; previewsEncrypted: boolean; shareLastSeen: boolean; signedOnly: boolean; searchIndex: boolean; dev: boolean; legacyCalls: boolean; enterSends: boolean; askSave: boolean;
  camQuality: "360" | "540" | "720" | "1080"; screenQuality: "720" | "1080" | "1080hi"; audioQuality: "low" | "normal" | "high";
  lowData: "off" | "auto" | "on";
  cacheMB: number; autoDl: AutoDl;
  recordMode: "voice" | "video"; noteQuality: "384" | "480" | "640" }; // the mic button records voice or round video; video message size (px) // media kept on the device (mediaCache.ts reads it from localStorage too) // calls: everyone's video at its lowest layer (auto = while our connection is poor)
const ACCENTS = ["#007ded", "#8774e1", "#40a7a0", "#e5864a", "#e0578b", "#4fae4e"];
const WALLPAPERS = { doodle: t("set.wpDoodle"), gradient: t("set.wpGradient"), plain: t("set.wpPlain") };
const THEMES = { system: t("set.themeSystem"), light: t("set.themeLight"), dark: t("set.themeDark") };
/** "1080p": a resolution, so no thousands separator. */
const res = (n: number) => n.toLocaleString(LOCALE, { useGrouping: false }) + "p";
const CAM_Q: [Prefs["camQuality"], string][] = [["360", res(360)], ["540", res(540)], ["720", res(720)], ["1080", res(1080)]];
const SCREEN_Q: [Prefs["screenQuality"], string][] = [["720", t("set.fps", { res: res(720), fps: 15 })], ["1080", t("set.fps", { res: res(1080), fps: 15 })], ["1080hi", t("set.fps", { res: res(1080), fps: 30 })]];
const NOTE_Q: [Prefs["noteQuality"], string][] = [["384", res(384)], ["480", res(480)], ["640", res(640)]];
const AUDIO_Q: [Prefs["audioQuality"], string][] = [["low", t("set.qLow")], ["normal", t("set.qNormal")], ["high", t("set.qHigh")]];
const CACHE_MB: [number, string][] = [[250, formatSize(250 << 20)], [500, formatSize(500 << 20)], [1024, formatSize(1 << 30)], [2048, formatSize(2 << 30)], [5120, formatSize(5 * 2 ** 30)]];
const LOW_DATA: [Prefs["lowData"], string][] = [["off", t("common.off")], ["auto", t("set.lowDataAuto")], ["on", t("packs.tintAlways")]];

// the Android app defaults to notifying: it asks for permission on first start. The desktop app needs no permission.
export const loadPrefs = (): Prefs => migrate({ theme: "system", accent: ACCENTS[0], wallpaper: "doodle", notify: isNative || isDesktop, notifyDMs: true, notifyGroups: true, notifyPreview: "all", previews: true, previewsEncrypted: false, shareLastSeen: true, signedOnly: true, searchIndex: true, dev: false, legacyCalls: false, enterSends: true, askSave: false, camQuality: "720", screenQuality: "1080hi", audioQuality: "high", lowData: "off", cacheMB: CACHE_MB_DEFAULT, recordMode: "voice", noteQuality: "640", ...JSON.parse(localStorage.getItem("panbeh.prefs") ?? "{}") });
const migrate = (p: Prefs): Prefs => ({ ...p, autoDl: mergeAutoDl(p.autoDl), // nested: kinds added later get their defaults
  accent: p.accent === "#3390ec" ? ACCENTS[0] : p.accent }); // the old default blue

/** Enter (or Ctrl/⌘+Enter when Enter is set to a new line) sends. */
export const isSendKey = (e: { key: string; shiftKey: boolean; ctrlKey: boolean; metaKey: boolean; nativeEvent: { isComposing: boolean } }) =>
  e.key === "Enter" && !e.nativeEvent.isComposing && (loadPrefs().enterSends ? !e.shiftKey : e.ctrlKey || e.metaKey);

/** Answering legacy m.call.* calls: developer options only. */
export const legacyCallsOn = () => { const p = loadPrefs(); return p.dev && p.legacyCalls; };

let prefsApplied = false;
export function applyPrefs(p = loadPrefs()) {
  const root = document.documentElement;
  if (prefsApplied) { // colours fade between themes instead of snapping
    root.classList.add("theme-switching");
    setTimeout(() => root.classList.remove("theme-switching"), 350);
  }
  prefsApplied = true;
  if (p.theme === "system") delete root.dataset.theme; else root.dataset.theme = p.theme;
  root.dataset.wallpaper = p.wallpaper;
  root.style.setProperty("--accent", p.accent);
  // status-bar icons follow the app's theme, not just the phone's
  if (isNative) SystemBars.setStyle({ style: { system: SystemBarsStyle.Default, light: SystemBarsStyle.Light, dark: SystemBarsStyle.Dark }[p.theme] }).catch(() => {});
}

export type View = "main" | "chats" | "notify" | "privacy" | "data" | "account" | "app"
  | "devices" | "password" | "keys" | "blocked" | "deactivate" | "link" | "lock" | "autodl" | "emoji";
/** Where a sub-page's back goes; the rest go to the main page. */
const PARENT: Partial<Record<View, View>> = { lock: "privacy", blocked: "privacy", keys: "privacy", devices: "privacy", autodl: "data", emoji: "chats", link: "account", password: "account", deactivate: "account" };
const ACCOUNT: [View, IconName, string, string][] = [
  ["link", "link", t("set.myLinkQr"), t("set.myLinkHint")],
  ["password", "lock", t("set.changePassword"), ""],
  ["deactivate", "trash", t("set.deactivate"), t("set.deactivateHint")],
];

/** A row that opens another page of settings. */
const Row = ({ icon, label, hint, onClick }: { icon: IconName; label: string; hint?: string; onClick: () => void }) => (
  <button className="user-row" onClick={onClick}>
    <span className="device-box"><Icon name={icon} /></span><span><b>{label}</b>{hint && <small>{hint}</small>}</span>
  </button>
);

const VERSION = import.meta.env.VITE_APP_VERSION ?? "dev";

// ---------- release notes (src/releaseNotes.json, by version) ----------
const ALL_NOTES = NOTES as Record<string, Record<string, string[]>>;
const BARE = VERSION.replace(/^v/, "");
const notes: string[] | undefined = ALL_NOTES[BARE]?.[lang];
const NOTES_SEEN = "panbeh.notesSeen";
const newer = (a: string, b: string) => a.localeCompare(b, undefined, { numeric: true }) > 0;
/** The notes of every version after `seen` up to this one, newest first; just this one's with nothing to compare. */
const showNotes = (seen = "") => {
  const vs = seen ? Object.keys(ALL_NOTES).filter((v) => newer(v, seen) && !newer(v, BARE) && ALL_NOTES[v][lang]).sort((a, b) => b.localeCompare(a, undefined, { numeric: true })) : [BARE];
  if (notes) alertDialog(t("notes.title", { v: ver(BARE) }), { body: vs.map((v) => <section key={v} className="notes">
    <h2>{t("notes.title", { v: ver(v) })}</h2>
    <ul dir="auto">{ALL_NOTES[v][lang].map((n, i) => <li key={i}>{n}</li>)}</ul>
  </section>) });
};
/** A new install has nothing to compare with: its version's notes count as read. */
export const skipNotes = () => { try { localStorage.setItem(NOTES_SEEN, VERSION); } catch { /* no storage */ } };
/** After an update, once: what's new in every version since the last one opened. */
export function notesAfterUpdate() {
  let seen;
  try {
    seen = localStorage.getItem(NOTES_SEEN);
    if (!notes || seen === VERSION) return;
    localStorage.setItem(NOTES_SEEN, VERSION);
  } catch { return; } // no storage: they'd show on every start
  showNotes(seen?.replace(/^v/, ""));
}

export function Settings({ start = "main", security, onClose, onSecurityChange }: { start?: View; security: string; onClose: () => void; onSecurityChange: () => void }) {
  const [prefs, setPrefs] = useState(loadPrefs);
  const set = (patch: Partial<Prefs>) => {
    const next = { ...prefs, ...patch };
    setPrefs(next);
    localStorage.setItem("panbeh.prefs", JSON.stringify(next));
    applyPrefs(next);
  };
  const [quick, setQuick] = useState(quickReaction); // synced (account data), not a pref
  const [quickPick, setQuickPick] = useState<DOMRect | null>(null); // the row that opened the picker
  const saveQuick = (q: Quick) => {
    setQuick(q);
    setQuickReaction(q).catch((e) => { setQuick(quickReaction()); void alertDialog(errText(e)); });
  };

  const [view, setView] = useState<View>(start);
  const pill = useSlider<HTMLDivElement>();
  const u = useUpdate();
  // the open pages, each stacked inside its parent's sheet, so going in or back only slides the top one
  const render = (v: View, child: ReactNode): ReactNode => {
    const up = () => setView(PARENT[v] ?? "main");
    const page = (title: string, body: ReactNode) => <Sheet key={v} title={title} onClose={v === "main" ? onClose : up}>{body}{child}</Sheet>;
    if (v === "lock") return <LockSettings key={v} onClose={up} />;
    if (v === "link") return <LinkSheet key={v} title={t("set.myLink")} link={matrixToLink(me())} onClose={up} />;
    if (v === "autodl") return page(t("set.autoDl"), <AutoDownload prefs={prefs} set={set} />);
    if (v === "emoji") return page(t("packs.both"), <MyPacks />);
    if (v === "devices") return page(t("set.devices"), <Devices />);
    if (v === "password") return page(t("set.changePassword"), isOAuth() ? <AccountPage action="org.matrix.profile" /> : <ChangePassword />);
    if (v === "keys") return page(t("set.keys"), <Keys />);
    if (v === "blocked") return page(t("set.blocked"), <Blocked />);
    if (v === "deactivate") return page(t("set.deactivate"), isOAuth() ? <AccountPage action="org.matrix.account_deactivate" /> : <Deactivate />);

    if (v === "chats") return page(t("set.chats"), <>
      <h3>{t("set.appearance")}</h3>
      <div className="segmented" data-pill ref={pill}>
        {(Object.keys(THEMES) as Prefs["theme"][]).map((t) => (
          <button key={t} className={prefs.theme === t ? "on" : ""} onClick={() => set({ theme: t })}>{THEMES[t]}</button>
        ))}
      </div>
      <div className="swatches" role="radiogroup" aria-label={t("set.accent")}>
        {ACCENTS.map((a) => (
          <button key={a} role="radio" aria-checked={prefs.accent === a} aria-label={a} className={prefs.accent === a ? "on" : ""} style={{ background: a }} onClick={() => set({ accent: a })} />
        ))}
      </div>
      <div className="wallpapers">
        {Object.entries(WALLPAPERS).map(([w, label]) => (
          <button key={w} data-wallpaper={w} className={"wallpaper-thumb" + (prefs.wallpaper === w ? " on" : "")} onClick={() => set({ wallpaper: w })}>
            <span>{label}</span>
          </button>
        ))}
      </div>
      <h3>{t("chats")}</h3>
      <label className="switch-row"><span>{t("set.previews")}<small>{t("set.previewsHint")}</small></span>
        <input type="checkbox" role="switch" checked={prefs.previews} onChange={(e) => set({ previews: e.target.checked })} /></label>
      <label className="switch-row"><span>{t("set.previewsEncrypted")}<small>{t("set.previewsEncryptedHint")}</small></span>
        <input type="checkbox" role="switch" disabled={!prefs.previews} checked={prefs.previews && prefs.previewsEncrypted} onChange={(e) => set({ previewsEncrypted: e.target.checked })} /></label>
      <label className="switch-row"><span>{t("set.enterSends")}<small>{t("set.enterSendsHint")}</small></span>
        <input type="checkbox" role="switch" checked={prefs.enterSends} onChange={(e) => set({ enterSends: e.target.checked })} /></label>
      {isNative && <label className="switch-row"><span>{t("set.askSave")}<small>{t("set.askSaveHint")}</small></span>
        <input type="checkbox" role="switch" checked={prefs.askSave} onChange={(e) => set({ askSave: e.target.checked })} /></label>}
      <label className="switch-row"><span>{t("set.quickReact")}<small>{t("set.quickReactHint")}</small></span>
        <input type="checkbox" role="switch" checked={!quick.off} onChange={(e) => saveQuick({ ...quick, off: !e.target.checked })} /></label>
      {!quick.off && <button className="select-row" onClick={(e) => setQuickPick(e.currentTarget.getBoundingClientRect())}>
        {t("set.quickReactEmoji")}<span className="quick-emoji"><ReactionKey k={quick.key} /></span></button>}
      {quickPick && createPortal(
        <div className="react-picker over-sheet" style={pickerAt(quickPick)}>
          <EmojiPanel onEmoji={(k, custom) => { saveQuick(custom ? { key: custom.img.url, shortcode: k } : { key: k }); setQuickPick(null); }} onClose={() => setQuickPick(null)} />
        </div>,
        document.body)}
      <h3>{t("packs.both")}</h3>
      <Row icon="smile" label={t("packs.both")} hint={t("set.emojiHint")} onClick={() => setView("emoji")} />
    </>);

    if (v === "notify") return page(t("notify.title"), <>
      <Notifications prefs={prefs} set={set} />
      <PushRules />
    </>);

    if (v === "privacy") return page(t("set.privacy"), <>
      <h3>{t("set.privacyH")}</h3>
      <Row icon="lock" label={t("lock.title")} hint={lockConf() ? t("mute.on") : t("set.lockOffHint")} onClick={() => setView("lock")} />
      <label className="switch-row"><span>{t("set.lastSeen")}<small>{t("set.lastSeenHint")}</small></span>
        <input type="checkbox" role="switch" checked={prefs.shareLastSeen} onChange={(e) => { set({ shareLastSeen: e.target.checked }); void setShareLastSeen(e.target.checked); }} /></label>
      <label className="switch-row"><span>{t("set.searchIndex")}<small>{t("set.searchIndexHint")}</small></span>
        <input type="checkbox" role="switch" checked={prefs.searchIndex} onChange={(e) => { set({ searchIndex: e.target.checked }); if (e.target.checked) indexLoaded(); else void dropIndex(); }} /></label>
      <Row icon="user" label={t("set.blocked")} onClick={() => setView("blocked")} />
      <h3>{t("set.encryption")}</h3>
      <Encryption onChange={onSecurityChange} />
      <label className="switch-row"><span>{t("set.signedOnly")}<small>{t("set.signedOnlyHint")}</small></span>
        <input type="checkbox" role="switch" checked={prefs.signedOnly} onChange={async (e) => {
          const on = e.target.checked;
          if (!on && !await confirmDialog(t("set.signedOnlyOffQ"), { danger: true, ok: t("common.off") })) return;
          set({ signedOnly: on });
          applyIsolation(on);
        }} /></label>
      <Row icon="file" label={t("set.keys")} hint={t("set.keysHint")} onClick={() => setView("keys")} />
      <Row icon="screen" label={t("set.sessions")} hint={t("set.sessionsHint")} onClick={() => setView("devices")} />
    </>);

    if (v === "data") return page(t("set.data"), <>
      <h3>{t("set.storage")}</h3>
      <Row icon="download" label={t("set.autoDl")} hint={t("set.autoDlHint")} onClick={() => setView("autodl")} />
      <Storage prefs={prefs} set={set} />
      <h3>{t("set.quality")}</h3>
      <label className="select-row">{t("set.camQ")}
        <Select value={prefs.camQuality} options={CAM_Q} onChange={(v) => set({ camQuality: v })} /></label>
      {!isNative && <label className="select-row">{t("call.share")}
        <Select value={prefs.screenQuality} options={SCREEN_Q} onChange={(v) => set({ screenQuality: v })} /></label>}
      <label className="select-row">{t("voice.videoMessage")}
        <Select value={prefs.noteQuality} options={NOTE_Q} onChange={(v) => set({ noteQuality: v })} /></label>
      <label className="select-row">{t("set.audioQ")}
        <Select value={prefs.audioQuality} options={AUDIO_Q} onChange={(v) => set({ audioQuality: v })} /></label>
      <label className="select-row">{t("set.lowData")}
        <Select value={prefs.lowData} options={LOW_DATA} onChange={(v) => set({ lowData: v })} /></label>
      <p className="muted">{t("set.qualityHint")}</p>
    </>);

    if (v === "account") return page(t("set.account"), ACCOUNT.map(([v, icon, label, hint]) => <Row key={v} icon={icon} label={label} hint={hint} onClick={() => setView(v)} />));

    if (v === "app") return page(t("set.app"), <>
      {isDesktop && <Autostart />}
      {canUpdate ? <Updates /> : <p className="muted">{t("app.name")} <span dir="ltr">{VERSION}</span></p>}
      {notes && <Row icon="info" label={t("notes.row")} onClick={() => showNotes()} />}
      <h3>{t("set.dev")}</h3>
      <label className="switch-row"><span>{t("set.dev")}<small>{t("set.devHint")}</small></span>
        <input type="checkbox" role="switch" checked={prefs.dev} onChange={(e) => set({ dev: e.target.checked })} /></label>
      {prefs.dev && (
        <label className="switch-row"><span>{t("set.legacyCalls")}<small>{t("set.legacyCallsHint")}</small></span>
          <input type="checkbox" role="switch" checked={prefs.legacyCalls} onChange={(e) => set({ legacyCalls: e.target.checked })} /></label>
      )}
    </>);

    const newVersion = u.status === "available" || u.status === "ready" || u.status === "downloading";
    return page(t("settings.title"), <>
      <MyProfile />

      <h3>{t("set.accounts")}</h3>
      {sessions().map((a) => (
        <button key={a.userId} className="user-row" disabled={a.userId === client.getUserId()} onClick={() => switchAccount(a.userId)}>
          <span className="device-box"><Icon name={a.userId === client.getUserId() ? "check" : "user"} /></span>
          <span><b dir="ltr">{a.userId}</b><small dir="ltr">{new URL(a.baseUrl).host}</small></span>
        </button>
      ))}
      <Row icon="plus" label={t("set.addAccount")} onClick={addAccount} />

      <h3>{t("settings.title")}</h3>
      <div className="user-row">
        <span className="device-box"><Icon name="globe" /></span><span><b>{t("settings.language")}</b></span>
        <Select value={lang} options={LANGS} onChange={setLang} />
      </div>
      <Row icon="thread" label={t("set.chats")} hint={t("set.chatsHint", { theme: THEMES[prefs.theme] })} onClick={() => setView("chats")} />
      <Row icon="bell" label={t("notify.title")} hint={prefs.notify ? t("mute.on") : t("common.off")} onClick={() => setView("notify")} />
      <Row icon="lock" label={t("set.privacy")}
        hint={security === "unlock" ? t("set.unverified") : security === "setup" ? t("set.noRecovery") : t("set.privacyHint")} onClick={() => setView("privacy")} />
      <Row icon="download" label={t("set.data")} hint={t("set.dataHint")} onClick={() => setView("data")} />
      <Row icon="user" label={t("set.account")} hint={t("set.accountHint")} onClick={() => setView("account")} />
      <Row icon="info" label={t("set.app")} hint={newVersion ? t("update.newIs", { v: ver(u.version) }) : t("app.name") + " " + ver(VERSION.replace(/^v/, ""))} onClick={() => setView("app")} />

      <button className="danger" onClick={() => confirmDialog(t("set.signOutQ"), { danger: true }).then((y) => y && void logout())}>{t("set.signOut")}</button>
    </>);
  };
  const chain: View[] = [view];
  while (chain[0] !== "main") chain.unshift(PARENT[chain[0]] ?? "main");
  return <>{chain.reduceRight<ReactNode>((child, v) => render(v, child), null)}</>;
}

function Notifications(props: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) {
  return isNative ? <AndroidNotifications {...props} /> : <WebNotifications {...props} />;
}

const KindSwitches = ({ prefs, set }: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) => (
  <>
    <label className="switch-row"><span>{t("set.dms")}</span>
      <input type="checkbox" role="switch" checked={prefs.notifyDMs} onChange={(e) => set({ notifyDMs: e.target.checked })} /></label>
    <label className="switch-row"><span>{t("folder.groups")}<small>{t("set.groupsHint")}</small></span>
      <input type="checkbox" role="switch" checked={prefs.notifyGroups} onChange={(e) => set({ notifyGroups: e.target.checked })} /></label>
    <label className="select-row">{t("set.notifyContent")}
      <Select value={prefs.notifyPreview} options={PREVIEWS} onChange={(v) => set({ notifyPreview: v })} />
    </label>
  </>
);
const PREVIEWS: [Prefs["notifyPreview"], string][] = [["all", t("set.pvAll")], ["name", t("set.pvName")], ["none", t("set.pvNone")]];

/** Background check modes: minutes between checks, 0 = real-time. */
const INTERVALS: [number, string][] = [[0, t("set.realtime")], ...[1, 2, 5, 10, 15, 30].map((m): [number, string] => [m, t("set.everyMin", { count: m })]), [60, t("set.everyHour")]];

/** In the app: Android notifications, delivered by a background service that keeps the client syncing. */
function AndroidNotifications({ prefs, set }: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) {
  const [st, setSt] = useState<Awaited<ReturnType<typeof nativeStatus>>>();
  useEffect(() => { // re-read after coming back from Android's settings screens
    const refresh = () => { if (document.visibilityState === "visible") nativeStatus().then(setSt, () => {}); };
    refresh();
    document.addEventListener("visibilitychange", refresh);
    return () => document.removeEventListener("visibilitychange", refresh);
  }, []);
  if (!st) return null;
  const toggle = async (want: boolean) => {
    if (want) {
      const p = await requestNotifyPermission();
      setSt({ ...st, permission: p });
      if (p !== "granted") return;
    } else nativeCancelAll();
    set({ notify: want });
    setBackgroundService(want);
  };
  const on = prefs.notify && st.permission === "granted";
  return (
    <>
      <label className="switch-row"><span>{t("set.notifyNew")}<small>{t("set.notifyNewAndroid")}</small></span>
        <input type="checkbox" role="switch" checked={on} onChange={(e) => toggle(e.target.checked)} /></label>
      {on && <KindSwitches prefs={prefs} set={set} />}
      {on && (
        <button className="user-row" onClick={() => void openNotifySettings()}>
          <span className="device-box"><Icon name="bell" /></span>
          <span><b>{t("set.sound")}</b><small>{t("set.soundHint")}</small></span>
        </button>
      )}
      {on && <>
        <label className="select-row">{t("set.bg")}
          <Select value={st.interval} options={INTERVALS} onChange={(m) => { setSt({ ...st, interval: m }); void setBackgroundInterval(m); }} />
        </label>
        <p className="muted">{t("set.bgHint")}</p>
      </>}
      {on && st.batteryOptimized && (
        <button className="user-row" onClick={() => requestBatteryExemption()}>
          <span className="device-box"><Icon name="bell" /></span>
          <span><b>{t("set.battery")}</b><small>{t("set.batteryHint")}</small></span>
        </button>
      )}
      {on && !st.fullScreen && (
        <button className="user-row" onClick={() => requestFullScreen()}>
          <span className="device-box"><Icon name="phone" /></span>
          <span><b>{t("set.fullScreen")}</b><small>{t("set.fullScreenHint")}</small></span>
        </button>
      )}
      {st.permission === "denied" && <p className="muted">{t("set.notifyDeniedAndroid")}</p>}
    </>
  );
}

function WebNotifications({ prefs, set }: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) {
  const supported = "Notification" in window;
  const [perm, setPerm] = useState(supported ? Notification.permission : "denied");
  if (!supported) return <p className="muted">{t("set.noNotify")}</p>;
  const toggle = async (want: boolean) => {
    if (want && perm !== "granted") {
      const p = await Notification.requestPermission(); // must run inside the click
      setPerm(p);
      if (p !== "granted") return;
    }
    set({ notify: want });
  };
  const on = prefs.notify && perm === "granted";
  return (
    <>
      <label className="switch-row"><span>{t("set.notifyNew")}<small>{isDesktop ? t("set.notifyNewDesktop") : t("set.notifyNewWeb")}</small></span>
        <input type="checkbox" role="switch" checked={on} onChange={(e) => toggle(e.target.checked)} /></label>
      {/* local only: turning the server's message rules off would also zero the unread badges */}
      {on && <KindSwitches prefs={prefs} set={set} />}
      {perm === "denied" && <p className="muted">{t("set.notifyDeniedWeb")}</p>}
    </>
  );
}

/** Downloaded media kept on the device: how much, how much is used, and emptying it. */
function Storage({ prefs, set }: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) {
  const [used, setUsed] = useState<number>();
  const refresh = () => { cacheSize().then(setUsed, () => {}); };
  useEffect(refresh, []);
  return (
    <>
      <label className="select-row">{t("set.cache")}
        <Select value={prefs.cacheMB} options={CACHE_MB} onChange={(v) => { set({ cacheMB: v }); void cacheTrim().then(refresh); }} />
      </label>
      <button className="user-row" disabled={!used} onClick={() => confirmDialog(t("set.clearCacheQ")).then((y) => { if (y) void cacheClear().then(refresh); })}>
        <span className="device-box"><Icon name="trash" /></span>
        <span><b>{t("set.clearCache")}</b><small>{used === undefined ? "…" : t("set.used", { size: formatSize(used) })}</small></span>
      </button>
      <p className="muted">{t("set.cacheHint")}</p>
    </>
  );
}

const DL_KINDS: [DlKind, string][] = [["photos", t("dl.photos")], ["videos", t("dl.videos")], ["files", t("dl.files")], ["gifs", t("dl.gifs")], ["voice", t("dl.voice")]];
const DL_MB: [number, string][] = [...[1, 5, 10, 50, 100].map((mb): [number, string] => [mb, t("dl.upTo", { size: formatSize(mb << 20) })]), [0, t("dl.noLimit")]];
const DL_NETS: [keyof AutoDl, string][] = [["mobile", t("dl.mobile")], ["wifi", t("dl.wifi")]];

/** What downloads by itself, per network, kind of media and kind of chat; the rest waits for a tap. */
function AutoDownload({ prefs, set }: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) {
  const put = (net: keyof AutoDl, kind: DlKind, patch: object) =>
    set({ autoDl: { ...prefs.autoDl, [net]: { ...prefs.autoDl[net], [kind]: { ...prefs.autoDl[net][kind], ...patch } } } });
  return (
    <>
      {DL_NETS.map(([net, title]) => (
        <section key={net}>
          <h3>{title}</h3>
          {DL_KINDS.map(([kind, label]) => {
            const r = prefs.autoDl[net][kind];
            return (
              <div key={kind} className="dl-row">
                <b>{label}</b>
                {"maxMB" in r && (r.dm || r.group) && <Select value={r.maxMB} options={DL_MB} onChange={(v) => put(net, kind, { maxMB: v })} />}
                <div className="dl-chips">
                  <button className={r.dm ? "on" : ""} aria-pressed={r.dm} onClick={() => put(net, kind, { dm: !r.dm })}>{t("set.dms")}</button>
                  <button className={r.group ? "on" : ""} aria-pressed={r.group} onClick={() => put(net, kind, { group: !r.group })}>{t("folder.groups")}</button>
                </div>
              </div>
            );
          })}
        </section>
      ))}
      <p className="muted">{t("dl.rest")} {isNative ? t("dl.meteredAndroid") : t("dl.desktopWifi")}</p>
    </>
  );
}

/** The desktop app starts with the system, into the tray. */
function Autostart() {
  const [on, setOn] = useState<boolean>();
  useEffect(() => { getAutostart().then(setOn, () => {}); }, []);
  return on !== undefined && (
    <label className="switch-row"><span>{t("set.autostart")}<small>{t("set.autostartHint")}</small></span>
      <input type="checkbox" role="switch" checked={on} onChange={(e) => setAutostart(e.target.checked).then(setOn, (err) => alertDialog(errText(err)))} /></label>
  );
}

/** "0.2.0" in the language's digits. */
const ver = (v = "") => v.replace(/\d+/g, (d) => num(+d));

/** What clicking an update offer does: download, or restart into the new version, each asked first. False if declined. */
async function actOnUpdate(u: Update) {
  if (isBazaar) { downloadUpdate(); return false; } // opens Bazaar's update sheet, which asks; the banner then waits until restart
  const yes = u.status === "available" ? await confirmDialog(t("update.downloadQ", { v: ver(u.version) }), { ok: t("update.download") })
    : !isDesktop ? await confirmDialog(t("update.installQ", { v: ver(u.version) }), { ok: t("update.install") })
    : await confirmDialog(t("update.restartQ", { v: ver(u.version) }), { ok: t("update.restart") });
  if (!yes) return false;
  if (u.status === "available") downloadUpdate();
  else void installUpdate().then((err) => { if (err) void alertDialog(t("update.installFailed", { err })); });
  return true;
}

function Updates() {
  const u = useUpdate();
  const [auto, setAuto] = useState<boolean>();
  const [version, setVersion] = useState("");
  useEffect(() => { // auto re-read on coming back: the Bazaar build's switch is set in Bazaar
    const refresh = () => { if (document.visibilityState === "visible") getAutoUpdate().then(setAuto, () => {}); };
    refresh();
    appVersion().then(setVersion);
    document.addEventListener("visibilitychange", refresh);
    return () => document.removeEventListener("visibilitychange", refresh);
  }, []);
  const status: Record<Update["status"], string> = {
    idle: t("update.check"), checking: t("update.checking"), none: t("update.none"),
    available: isBazaar ? t("update.bazaarAvailable") : t("update.available", { v: ver(u.version) }), downloading: t("update.downloading", { v: ver(u.version), pct: t("poll.pct", { n: u.percent ?? 0 }) }),
    ready: t(isDesktop ? "update.readyRestart" : "update.readyInstall", { v: ver(u.version) }), error: t("update.error"),
  };
  const click = () => (u.status === "available" || u.status === "ready" ? void actOnUpdate(u) : checkUpdate());
  return (
    <>
      {auto !== undefined && (
        <label className="switch-row"><span>{t(isBazaar ? "update.bazaarAuto" : "update.auto")}<small>{t(isBazaar ? "update.bazaarAutoHint" : "update.autoHint")}</small></span>
          <input type="checkbox" role="switch" checked={auto} onChange={(e) => setAutoUpdate(e.target.checked).then(setAuto, (err) => alertDialog(errText(err)))} /></label>
      )}
      <button className="user-row" disabled={u.status === "checking" || u.status === "downloading"} onClick={click}>
        <span className="device-box"><Icon name="download" /></span>
        <span><b>{t("app.name")} {ver(version)}</b><small>{status[u.status]}</small></span>
      </button>
    </>
  );
}

/** Above the chat list: a new version found (auto-update off) or downloading or ready to install. */
export function UpdateBanner() {
  const u = useUpdate();
  const [later, setLater] = useState<string>(); // "not now" for this version, until restart
  if (u.status === "downloading") return (
    <div className="banner update" role="status">
      <Icon name="download" size={16} /> {t("update.downloading", { v: ver(u.version), pct: t("poll.pct", { n: u.percent ?? 0 }) })}
      <i style={{ width: (u.percent ?? 0) + "%" }} />
    </div>
  );
  if ((u.status !== "available" && u.status !== "ready") || later === u.status + u.version) return null;
  const go = async () => { if (!await actOnUpdate(u) && u.status === "available") setLater(u.status + u.version); };
  return (
    <button className="banner" onClick={() => void go()}>
      <Icon name="download" size={16} /> {u.status === "ready" ? t("update.bannerReady", { v: ver(u.version) }) : isBazaar ? t("update.bazaarBanner") : t("update.bannerNew", { v: ver(u.version) })}
    </button>
  );
}

const KEYWORD_ACTIONS: PushRuleAction[] = [PushRuleActionName.Notify, { set_tweak: TweakName.Sound, value: "default" }, { set_tweak: TweakName.Highlight }];

/** Server push rules (shared with the user's other apps): @room mentions and keywords. */
function PushRules() {
  useTick(client, [ClientEvent.AccountData]); // push rules arrive as account data
  const [word, setWord] = useState("");
  const [busy, setBusy] = useState(false);
  const act = (fn: () => Promise<unknown>) => { setBusy(true); fn().catch((e) => alertDialog(errText(e))).finally(() => setBusy(false)); };
  const g = client.pushRules?.global;
  const roomRules = (g?.override ?? []).filter((r) => r.rule_id === RuleId.AtRoomNotification || r.rule_id === RuleId.IsRoomMention);
  const keywords = (g?.content ?? []).filter((r) => !r.rule_id.startsWith(".") && r.pattern);
  const add = (e: FormEvent) => {
    e.preventDefault();
    const w = word.trim();
    if (w) act(async () => { await client.addPushRule("global", PushRuleKind.ContentSpecific, w, { actions: KEYWORD_ACTIONS, pattern: w }); setWord(""); });
  };
  return (
    <>
      {roomRules.length > 0 && (
        <label className="switch-row"><span>{t("set.roomMention")}<small>{t("set.roomMentionHint")}</small></span>
          <input type="checkbox" role="switch" checked={roomRules.some((r) => r.enabled)} disabled={busy}
            onChange={(e) => act(() => Promise.all(roomRules.map((r) => client.setPushRuleEnabled("global", PushRuleKind.Override, r.rule_id, e.target.checked))))} /></label>
      )}
      <form className="form keywords" onSubmit={add}>
        <small className="muted">{t("set.keywordsHint")}</small>
        {keywords.length > 0 && (
          <div className="chips">
            {keywords.map((r) => (
              <span key={r.rule_id} className="chip">
                <button type="button" className="plain" disabled={busy} aria-label={t("set.removeKeyword", { w: r.pattern ?? "" })}
                  onClick={() => act(() => client.deletePushRule("global", PushRuleKind.ContentSpecific, r.rule_id))}><Icon name="close" size={16} /></button>
                {r.pattern}
              </span>
            ))}
          </div>
        )}
        <div className="form-head">
          <input value={word} onChange={(e) => setWord(e.target.value)} placeholder={t("set.newKeyword")} aria-label={t("set.newKeyword")} />
          <button className="primary" disabled={busy || !word.trim()}>{t("common.add")}</button>
        </div>
      </form>
    </>
  );
}

type ProfileInfo = { displayname?: string; avatar_url?: string };
const profileKey = () => `panbeh.profile:${me()}`; // dropped in logout(), like drafts
const cachedProfile = (): ProfileInfo => { try { return JSON.parse(localStorage.getItem(profileKey()) ?? "{}"); } catch { return {}; } };

/** My name + photo, read from the server (client.getUser can be missing or stale); the last copy shows instantly meanwhile. */
function MyProfile() {
  const [profile, setProfile] = useState(cachedProfile);
  const [name, setName] = useState(() => profile.displayname ?? "");
  const [busy, setBusy] = useState(false);
  const keep = (p: ProfileInfo) => { setProfile(p); try { localStorage.setItem(profileKey(), JSON.stringify(p)); } catch { /* not remembered */ } };
  useEffect(() => {
    const was = profile.displayname ?? "";
    client.getProfileInfo(me()).then((p) => { keep(p); setName((n) => (n === was ? p.displayname ?? "" : n)); }, () => {}); // keep what they started typing
  }, []);
  const act = async (fn: () => Promise<ProfileInfo>) => {
    setBusy(true);
    try { keep({ ...profile, ...(await fn()) }); } catch (e) { alertDialog(errText(e)); }
    setBusy(false);
  };
  const dirty = name.trim() !== "" && name.trim() !== (profile.displayname ?? "");

  return (
    <div className="profile">
      <div className="avatar-col">
      <label className="avatar-edit" title={t("info.changePhoto")}>
        <input type="file" accept="image/*" hidden disabled={busy} onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void editAvatar(f).then((a) => a && act(async () => ({ avatar_url: await setMyAvatar(a) }))); }} />
        <Avatar mxc={profile.avatar_url} name={profile.displayname || me()} id={me()} size={64} />
      </label>
      {profile.avatar_url && <button className="photo-remove" disabled={busy}
        onClick={() => confirmDialog(t("set.removeAvatarQ"), { danger: true }).then((y) => y && void act(async () => (await client.setAvatarUrl(""), { avatar_url: undefined })))}>{t("info.removePhoto")}</button>}
      </div>
      <form className="form" onSubmit={(e) => { e.preventDefault(); const n = name.trim(); act(async () => (await client.setDisplayName(n), { displayname: n })); }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("info.name")} aria-label={t("info.name")} required />
        <span dir="ltr">{me()}</span>
        {dirty && <button className="primary" disabled={busy}>{t("common.save")}</button>}
      </form>
    </div>
  );
}

function Encryption({ onChange }: { onChange: () => void }) {
  const [state, setState] = useState<"ok" | "unlock" | "setup" | null>(null);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [newKey, setNewKey] = useState("");
  const oauth = isOAuth();

  useEffect(() => {
    const check = () => { recoveryState().then(setState); };
    check();
    // verified from another device: cross-signing and backup secrets arrive by gossip
    client.on(CryptoEvent.KeyBackupDecryptionKeyCached, check);
    client.on(CryptoEvent.UserTrustStatusChanged, check);
    return () => { client.off(CryptoEvent.KeyBackupDecryptionKeyCached, check); client.off(CryptoEvent.UserTrustStatusChanged, check); };
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (state === "unlock") await unlock(input);
      else setNewKey(await setupRecovery(input || undefined));
      setInput("");
      setState("ok");
      onChange();
    } catch (err) {
      setError(errText(err));
    }
    setBusy(false);
  }
  const otherDevice = async () => {
    try { showVerification(await client.getCrypto()!.requestOwnUserVerification()); } catch (e) { setError(errText(e)); }
  };

  if (newKey) return (
    <div className="card">
      <p>{t("rec.keepKey")}</p>
      <code className="recovery-key" dir="ltr">{newKey}</code>
      <button onClick={() => navigator.clipboard.writeText(newKey)}>{t("common.copy")}</button>
    </div>
  );
  if (!state) return <p className="muted">{t("update.checking")}</p>;
  if (state === "ok") return <p className="card ok"><Icon name="lock" size={16} /> {t("rec.ok")}</p>;
  return (
    <form className="card" onSubmit={submit}>
      <p>{state === "unlock"
        ? t("rec.unlockHint")
        : t("rec.setupHint") + (oauth ? "" : " " + t("rec.confirmPassword"))}</p>
      {!(oauth && state === "setup") && <input type={state === "setup" ? "password" : "text"} value={input} onChange={(e) => setInput(e.target.value)}
        placeholder={state === "unlock" ? t("rec.keyPlaceholder") : t("rec.accountPassword")} required autoComplete="off" />}
      {error && <p className="error">{error}</p>}
      <button className="primary" disabled={busy}>{busy ? t("common.working") : state === "unlock" ? t("rec.unlock") : t("rec.setup")}</button>
      {state === "unlock" && <button type="button" onClick={otherDevice}>{t("rec.otherDevice")}</button>}
    </form>
  );
}

type Device = { device_id: string; display_name?: string; last_seen_ts?: number; last_seen_ip?: string };

function Devices() {
  const [list, setList] = useState<Device[] | null>(null);
  const [trust, setTrust] = useState<Record<string, DeviceVerificationStatus | null>>({});
  const [error, setError] = useState("");
  const [pending, setPending] = useState<string[] | null>(null); // ids waiting for the account password
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [noUrl, setNoUrl] = useState(false);
  const oauth = isOAuth();
  const refresh = () => client.getDevices().then((r) => setList(r.devices), (e) => setError(errText(e)));
  useEffect(() => {
    refresh();
    addEventListener("focus", refresh); // back from the account-management tab
    return () => removeEventListener("focus", refresh);
  }, []);
  // verified badges; re-checked when a verification finishes
  useEffect(() => {
    if (!list) return;
    const check = () => {
      Promise.all(list.map((d) => client.getCrypto()!.getDeviceVerificationStatus(me(), d.device_id).catch(() => null)))
        .then((s) => setTrust(Object.fromEntries(list.map((d, i) => [d.device_id, s[i]]))));
    };
    check();
    client.on(CryptoEvent.DevicesUpdated, check);
    client.on(CryptoEvent.UserTrustStatusChanged, check);
    return () => { client.off(CryptoEvent.DevicesUpdated, check); client.off(CryptoEvent.UserTrustStatusChanged, check); };
  }, [list]);

  const manage = async (id?: string) => {
    const url = await deviceManageUrl(id).catch(() => null);
    setNoUrl(!url);
    if (url) window.open(url, "_blank", "noopener");
  };
  const remove = async (ids: string[], pw?: string) => {
    setBusy(true);
    setError("");
    try {
      await deleteDevices(ids, pw);
      setPending(null);
      setPassword("");
    } catch (e) {
      if (e instanceof NeedsPassword) setPending(ids); else setError(errText(e));
    }
    setBusy(false);
    refresh();
  };
  const verify = async (id: string) => {
    try { showVerification(await client.getCrypto()!.requestDeviceVerification(me(), id)); } catch (e) { setError(errText(e)); }
  };

  if (!list) return <p className="muted">{error || t("common.loading")}</p>;
  const mine = list.find((d) => d.device_id === client.getDeviceId());
  const others = list.filter((d) => d !== mine).sort((a, b) => (b.last_seen_ts ?? 0) - (a.last_seen_ts ?? 0));
  const row = (d: Device, out?: boolean) => {
    const tr = trust[d.device_id];
    const verified = !!tr?.crossSigningVerified;
    return (
      <div key={d.device_id} className="user-row">
        <span className="device-box"><Icon name="lock" /></span>
        <span>
          <b>{d.display_name ?? d.device_id}</b>
          {tr !== undefined && <small className={"trust" + (verified ? " ok" : "")}>{verified ? t("dev.verified") : tr ? t("dev.unverified") : t("dev.noE2ee")}</small>}
          <small>{d.last_seen_ts ? stamp(d.last_seen_ts) : ""} {d.last_seen_ip && <bdi dir="ltr">{d.last_seen_ip}</bdi>}</small>
          <small className="id" dir="ltr">{d.device_id}</small>
        </span>
        {out && tr && !verified && <button className="row-btn" disabled={busy} onClick={() => verify(d.device_id)}>{t("dev.verify")}</button>}
        {out && <button className="row-btn" disabled={busy} onClick={() => (oauth ? manage(d.device_id) : remove([d.device_id]))}>{t("common.signOut")}</button>}
      </div>
    );
  };

  return (
    <>
      {mine && <><h3>{t("dev.this")}</h3>{row(mine)}</>}
      {others.length > 0 && <h3>{t("dev.others")}</h3>}
      {others.map((d) => row(d, true))}
      {others.length > 0 && (oauth
        ? <button className="primary" onClick={() => manage()}>{t("dev.manage")}</button>
        : <button className="danger" disabled={busy} onClick={() => confirmDialog(t("dev.signOutOthersQ"), { danger: true }).then((y) => y && void remove(others.map((d) => d.device_id)))}>{t("dev.signOutOthers")}</button>)}
      {noUrl && <p className="muted">{t("dev.noUrl")}</p>}
      {pending && (
        <form className="card form" onSubmit={(e) => { e.preventDefault(); remove(pending, password); }}>
          <p>{t("dev.enterPassword")}</p>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("rec.accountPassword")} autoComplete="current-password" required autoFocus />
          <button className="primary" disabled={busy}>{t("common.confirm")}</button>
          <button type="button" onClick={() => { setPending(null); setPassword(""); }}>{t("common.cancel")}</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
    </>
  );
}

// ---------- account ----------

/** OAuth accounts change password / deactivate on the auth service's own page. */
function AccountPage({ action }: { action: string }) {
  const [noUrl, setNoUrl] = useState(false);
  const open = async () => {
    const url = await accountManageUrl(action).catch(() => null);
    setNoUrl(!url);
    if (url) window.open(url, "_blank", "noopener");
  };
  return (
    <>
      <p className="muted">{t("acc.onServer")}</p>
      <button className="primary" onClick={open}>{t("acc.open")}</button>
      {noUrl && <p className="muted">{t("acc.noUrl")}</p>}
    </>
  );
}

/** Shared busy/error/done state for the account forms. */
function useForm() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState("");
  const run = async (fn: () => Promise<string | void>) => {
    setBusy(true);
    setError("");
    setDone("");
    try { setDone((await fn()) || ""); } catch (e) { setError(e instanceof NeedsPassword ? t("acc.needPassword") : errText(e)); }
    setBusy(false);
  };
  return { busy, error, done, run };
}

function ChangePassword() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const { busy, error, done, run } = useForm();
  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(async () => {
      if (next !== again) throw new Error(t("acc.mismatch"));
      // false: the server would otherwise sign out every other device
      await withPassword((auth) => client.setPassword(auth!, next, false), current);
      setCurrent(""); setNext(""); setAgain("");
      return t("acc.changed");
    });
  };
  return (
    <form className="form" onSubmit={submit}>
      <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} placeholder={t("acc.current")} autoComplete="current-password" required />
      <input type="password" value={next} onChange={(e) => setNext(e.target.value)} placeholder={t("acc.new")} autoComplete="new-password" required minLength={8} />
      <input type="password" value={again} onChange={(e) => setAgain(e.target.value)} placeholder={t("acc.again")} autoComplete="new-password" required />
      {error && <p className="error">{error}</p>}
      {done && <p className="card ok"><Icon name="check" size={16} /> {done}</p>}
      <button className="primary" disabled={busy}>{busy ? t("common.working") : t("acc.change")}</button>
    </form>
  );
}

/** Room keys to/from an Element-compatible file, for moving history to a device without key backup. */
function Keys() {
  const [pass, setPass] = useState("");
  const [again, setAgain] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [inPass, setInPass] = useState("");
  const [progress, setProgress] = useState("");
  const ex = useForm(), im = useForm();
  const exportKeys = (e: FormEvent) => {
    e.preventDefault();
    ex.run(async () => {
      if (pass !== again) throw new Error(t("keys.mismatch"));
      const text = await encryptKeyFile(await client.getCrypto()!.exportRoomKeysAsJson(), pass);
      const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
      const saved = await saveFile(url, "element-keys.txt").finally(() => setTimeout(() => URL.revokeObjectURL(url), 1000));
      if (!saved) return;
      setPass(""); setAgain("");
      return saved.where === "downloads" ? t("keys.savedDownloads") : t("keys.saved");
    });
  };
  const importKeys = (e: FormEvent) => {
    e.preventDefault();
    im.run(async () => {
      const json = await decryptKeyFile(await file!.text(), inPass);
      await client.getCrypto()!.importRoomKeysAsJson(json, {
        progressCallback: (p) => { if (p.stage === "load_keys") setProgress(`${num(p.successes + p.failures)} / ${num(p.total)}`); },
      });
      setProgress(""); setInPass("");
      return t("keys.imported", { count: JSON.parse(json).length });
    });
  };
  return (
    <>
      <p className="muted">{t("keys.about")}</p>
      <form className="form" onSubmit={exportKeys}>
        <h3>{t("keys.export")}</h3>
        <input type="password" value={pass} onChange={(e) => setPass(e.target.value)} placeholder={t("keys.pass")} autoComplete="new-password" required />
        <input type="password" value={again} onChange={(e) => setAgain(e.target.value)} placeholder={t("keys.passAgain")} autoComplete="new-password" required />
        {ex.error && <p className="error">{ex.error}</p>}
        {ex.done && <p className="card ok"><Icon name="check" size={16} /> {ex.done}</p>}
        <button className="primary" disabled={ex.busy}>{ex.busy ? t("keys.encrypting") : t("keys.save")}</button>
      </form>
      <form className="form" onSubmit={importKeys}>
        <h3>{t("keys.importH")}</h3>
        <input type="file" accept=".txt,text/plain" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required aria-label={t("keys.file")} />
        <input type="password" value={inPass} onChange={(e) => setInPass(e.target.value)} placeholder={t("keys.filePass")} autoComplete="off" required />
        {im.error && <p className="error">{im.error}</p>}
        {im.done && <p className="card ok"><Icon name="check" size={16} /> {im.done}</p>}
        <button className="primary" disabled={im.busy}>{im.busy ? (progress ? t("keys.importing", { progress }) : t("preview.decrypting")) : t("keys.import")}</button>
      </form>
    </>
  );
}

function Blocked() {
  useTick(client, [ClientEvent.AccountData]);
  const [busy, setBusy] = useState(false);
  const ids = client.getIgnoredUsers();
  const unblock = (id: string) => { setBusy(true); setBlocked(id, false).catch((e) => alertDialog(errText(e))).finally(() => setBusy(false)); };
  if (!ids.length) return <p className="muted">{t("set.noBlocked")}</p>;
  return ids.map((id) => {
    const u = client.getUser(id);
    return (
      <div key={id} className="user-row">
        <Avatar mxc={u?.avatarUrl} name={u?.displayName ?? id} id={id} size={42} />
        <span><b>{u?.displayName ?? id}</b><small dir="ltr">{id}</small></span>
        <button className="row-btn" disabled={busy} onClick={() => unblock(id)}>{t("profile.unblock")}</button>
      </div>
    );
  });
}

function Deactivate() {
  const [password, setPassword] = useState("");
  const { busy, error, run } = useForm();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!(await confirmDialog(t("acc.deactivateQ"), { danger: true }))) return;
    run(async () => {
      await withPassword((auth) => client.deactivateAccount(auth, false), password);
      await logout(); // reloads into the login screen
    });
  };
  return (
    <form className="form" onSubmit={submit}>
      <p className="card">{t("acc.deactivateAbout")}</p>
      <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("rec.accountPassword")} autoComplete="current-password" required />
      {error && <p className="error">{error}</p>}
      <button className="danger" disabled={busy}>{busy ? t("common.working") : t("acc.deactivate")}</button>
    </form>
  );
}
