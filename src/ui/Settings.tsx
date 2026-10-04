import { useEffect, useState, type FormEvent } from "react";
import { ClientEvent, PushRuleActionName, PushRuleKind, RuleId, TweakName, type PushRuleAction } from "matrix-js-sdk";
import { CryptoEvent, type DeviceVerificationStatus } from "matrix-js-sdk/lib/crypto-api/index.js";
import { accountManageUrl, addAccount, client, sessions, switchAccount, deleteDevices, deviceManageUrl, isOAuth, logout, NeedsPassword, recoveryState, setBlocked, setMyAvatar, setShareLastSeen, setupRecovery, unlock, withPassword } from "../matrix.ts";
import { useTick } from "../hooks.ts";
import { Icon, type IconName } from "../icons.tsx";
import { Avatar, errText, me, Sheet } from "./common.tsx";
import { num, stamp } from "../logic.ts";
import { decryptKeyFile, encryptKeyFile } from "../keyfile.ts";
import { showVerification } from "./Verify.tsx";
import { desktopVersion, getAutostart, isDesktop, setAutostart } from "../desktop.ts";
import { isNative, nativeCancelAll, nativeStatus, requestBatteryExemption, requestFullScreen, requestNotifyPermission, saveFile, setBackgroundService } from "../native.ts";
import { alertDialog, confirmDialog } from "./dialog.tsx";

type Prefs = { theme: "system" | "light" | "dark"; accent: string; wallpaper: string; notify: boolean; notifyDMs: boolean; notifyGroups: boolean; previews: boolean; shareLastSeen: boolean; dev: boolean; legacyCalls: boolean; enterSends: boolean };
const ACCENTS = ["#3390ec", "#8774e1", "#40a7a0", "#e5864a", "#e0578b", "#4fae4e"];
const WALLPAPERS = { doodle: "طرح‌دار", gradient: "گرادیان", plain: "ساده" };
const THEMES = { system: "سیستم", light: "روشن", dark: "تیره" };

// the Android app defaults to notifying: it asks for permission on first start. The desktop app needs no permission.
export const loadPrefs = (): Prefs => ({ theme: "system", accent: ACCENTS[0], wallpaper: "doodle", notify: isNative || isDesktop, notifyDMs: true, notifyGroups: true, previews: true, shareLastSeen: true, dev: false, legacyCalls: false, enterSends: true, ...JSON.parse(localStorage.getItem("panbeh.prefs") ?? "{}") });

/** Enter (or Ctrl/⌘+Enter when Enter is set to a new line) sends. */
export const isSendKey = (e: { key: string; shiftKey: boolean; ctrlKey: boolean; metaKey: boolean; nativeEvent: { isComposing: boolean } }) =>
  e.key === "Enter" && !e.nativeEvent.isComposing && (loadPrefs().enterSends ? !e.shiftKey : e.ctrlKey || e.metaKey);

/** Answering legacy m.call.* calls: developer options only. */
export const legacyCallsOn = () => { const p = loadPrefs(); return p.dev && p.legacyCalls; };

export function applyPrefs(p = loadPrefs()) {
  const root = document.documentElement;
  if (p.theme === "system") delete root.dataset.theme; else root.dataset.theme = p.theme;
  root.dataset.wallpaper = p.wallpaper;
  root.style.setProperty("--accent", p.accent);
}

type View = "main" | "devices" | "password" | "keys" | "blocked" | "deactivate";
const ACCOUNT: [View, IconName, string, string][] = [
  ["password", "lock", "تغییر رمز عبور", ""],
  ["keys", "file", "کلیدهای رمزنگاری", "ذخیره در فایل یا وارد کردن از فایل"],
  ["blocked", "user", "کاربران مسدود", ""],
  ["deactivate", "trash", "غیرفعال‌سازی حساب", "حذف همیشگی حساب"],
];

export function Settings({ onClose, onSecurityChange }: { onClose: () => void; onSecurityChange: () => void }) {
  const [prefs, setPrefs] = useState(loadPrefs);
  const set = (patch: Partial<Prefs>) => {
    const next = { ...prefs, ...patch };
    setPrefs(next);
    localStorage.setItem("panbeh.prefs", JSON.stringify(next));
    applyPrefs(next);
  };

  const [view, setView] = useState<View>("main");
  const back = () => setView("main");
  if (view === "devices") return <Sheet title="دستگاه‌ها" onClose={back}><Devices /></Sheet>;
  if (view === "password") return <Sheet title="تغییر رمز عبور" onClose={back}>{isOAuth() ? <AccountPage action="org.matrix.profile" /> : <ChangePassword />}</Sheet>;
  if (view === "keys") return <Sheet title="کلیدهای رمزنگاری" onClose={back}><Keys /></Sheet>;
  if (view === "blocked") return <Sheet title="کاربران مسدود" onClose={back}><Blocked /></Sheet>;
  if (view === "deactivate") return <Sheet title="غیرفعال‌سازی حساب" onClose={back}>{isOAuth() ? <AccountPage action="org.matrix.account_deactivate" /> : <Deactivate />}</Sheet>;

  return (
    <Sheet title="تنظیمات" onClose={onClose}>
      <MyProfile />

      <h3>حساب‌ها</h3>
      {sessions().map((a) => (
        <button key={a.userId} className="user-row" disabled={a.userId === client.getUserId()} onClick={() => switchAccount(a.userId)}>
          <span className="device-box"><Icon name={a.userId === client.getUserId() ? "check" : "user"} /></span>
          <span><b dir="ltr">{a.userId}</b><small dir="ltr">{new URL(a.baseUrl).host}</small></span>
        </button>
      ))}
      <button className="user-row" onClick={addAccount}>
        <span className="device-box"><Icon name="plus" /></span><span><b>افزودن حساب</b></span>
      </button>

      <h3>ظاهر</h3>
      <div className="segmented">
        {(Object.keys(THEMES) as Prefs["theme"][]).map((t) => (
          <button key={t} className={prefs.theme === t ? "on" : ""} onClick={() => set({ theme: t })}>{THEMES[t]}</button>
        ))}
      </div>
      <div className="swatches" role="radiogroup" aria-label="رنگ اصلی">
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
      <label className="switch-row"><span>پیش‌نمایش پیوندها<small>سرور شما پیوندها را برای ساختن پیش‌نمایش باز می‌کند</small></span>
        <input type="checkbox" role="switch" checked={prefs.previews} onChange={(e) => set({ previews: e.target.checked })} /></label>
      <label className="switch-row"><span>ارسال با Enter<small>خاموش: Enter خط جدید می‌زند و Ctrl+Enter ارسال می‌کند</small></span>
        <input type="checkbox" role="switch" checked={prefs.enterSends} onChange={(e) => set({ enterSends: e.target.checked })} /></label>

      <h3>حریم خصوصی</h3>
      <label className="switch-row"><span>نمایش آخرین بازدید<small>فقط وضعیت خودتان پنهان می‌شود؛ وضعیت دیگران را همچنان می‌بینید</small></span>
        <input type="checkbox" role="switch" checked={prefs.shareLastSeen} onChange={(e) => { set({ shareLastSeen: e.target.checked }); void setShareLastSeen(e.target.checked); }} /></label>

      <h3>اعلان‌ها</h3>
      <Notifications prefs={prefs} set={set} />
      <PushRules />

      {isDesktop && <DesktopApp />}

      <h3>حساب</h3>
      {ACCOUNT.map(([v, icon, label, hint]) => (
        <button key={v} className="user-row" onClick={() => setView(v)}>
          <span className="device-box"><Icon name={icon} /></span><span><b>{label}</b>{hint && <small>{hint}</small>}</span>
        </button>
      ))}

      <h3>دستگاه‌ها</h3>
      <button className="user-row" onClick={() => setView("devices")}>
        <span className="device-box"><Icon name="lock" /></span><span><b>دستگاه‌های وارد‌شده</b><small>مشاهده، تأیید و خروج از دستگاه‌های دیگر</small></span>
      </button>

      <h3>رمزنگاری</h3>
      <Encryption onChange={onSecurityChange} />

      <h3>گزینه‌های توسعه‌دهنده</h3>
      <label className="switch-row"><span>گزینه‌های توسعه‌دهنده<small>امکانات آزمایشی و قدیمی</small></span>
        <input type="checkbox" role="switch" checked={prefs.dev} onChange={(e) => set({ dev: e.target.checked })} /></label>
      {prefs.dev && (
        <label className="switch-row"><span>دریافت تماس‌های قدیمی<small>تماس از FluffyChat، Nheko و Element قدیمی</small></span>
          <input type="checkbox" role="switch" checked={prefs.legacyCalls} onChange={(e) => set({ legacyCalls: e.target.checked })} /></label>
      )}

      <button className="danger" onClick={() => confirmDialog("از این دستگاه خارج می‌شوید؟", { danger: true }).then((y) => y && void logout())}>خروج از این حساب</button>
    </Sheet>
  );
}

function Notifications(props: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) {
  return isNative ? <AndroidNotifications {...props} /> : <WebNotifications {...props} />;
}

const KindSwitches = ({ prefs, set }: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) => (
  <>
    <label className="switch-row"><span>گفتگوهای شخصی</span>
      <input type="checkbox" role="switch" checked={prefs.notifyDMs} onChange={(e) => set({ notifyDMs: e.target.checked })} /></label>
    <label className="switch-row"><span>گروه‌ها<small>نام‌بردن از شما همیشه اعلان می‌شود</small></span>
      <input type="checkbox" role="switch" checked={prefs.notifyGroups} onChange={(e) => set({ notifyGroups: e.target.checked })} /></label>
  </>
);

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
      <label className="switch-row"><span>اعلان پیام‌های تازه<small>پنبه در پس‌زمینه متصل می‌ماند، حتی وقتی بسته است</small></span>
        <input type="checkbox" role="switch" checked={on} onChange={(e) => toggle(e.target.checked)} /></label>
      {on && <KindSwitches prefs={prefs} set={set} />}
      {on && st.batteryOptimized && (
        <button className="user-row" onClick={() => requestBatteryExemption()}>
          <span className="device-box"><Icon name="bell" /></span>
          <span><b>اجرای بدون محدودیت در پس‌زمینه</b><small>بهینه‌سازی باتری اندروید ممکن است اعلان‌ها را دیر برساند</small></span>
        </button>
      )}
      {on && !st.fullScreen && (
        <button className="user-row" onClick={() => requestFullScreen()}>
          <span className="device-box"><Icon name="phone" /></span>
          <span><b>زنگ تماس روی صفحه‌ی قفل</b><small>اجازه دهید تماس‌های ورودی تمام‌صفحه نشان داده شوند</small></span>
        </button>
      )}
      {st.permission === "denied" && <p className="muted">اجازه‌ی اعلان رد شده است؛ از تنظیمات اندروید، بخش برنامه‌ها، آن را برای پنبه باز کنید.</p>}
    </>
  );
}

function WebNotifications({ prefs, set }: { prefs: Prefs; set: (p: Partial<Prefs>) => void }) {
  const supported = "Notification" in window;
  const [perm, setPerm] = useState(supported ? Notification.permission : "denied");
  if (!supported) return <p className="muted">این مرورگر اعلان را پشتیبانی نمی‌کند.</p>;
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
      <label className="switch-row"><span>اعلان پیام‌های تازه<small>{isDesktop ? "با بستن پنجره، پنبه در سینی سیستم متصل می‌ماند" : "تا وقتی پنبه در یک زبانه باز است"}</small></span>
        <input type="checkbox" role="switch" checked={on} onChange={(e) => toggle(e.target.checked)} /></label>
      {/* local only: turning the server's message rules off would also zero the unread badges */}
      {on && <KindSwitches prefs={prefs} set={set} />}
      {perm === "denied" && <p className="muted">اجازه‌ی اعلان در مرورگر رد شده است؛ از تنظیمات سایت در مرورگر آن را باز کنید.</p>}
    </>
  );
}

/** The desktop app: start with the system (into the tray), and its version. */
function DesktopApp() {
  const [autostart, setOn] = useState<boolean>();
  const [version, setVersion] = useState<string>();
  useEffect(() => { getAutostart().then(setOn, () => {}); desktopVersion()?.then(setVersion, () => {}); }, []);
  return (
    <>
      <h3>برنامه</h3>
      {autostart !== undefined && (
        <label className="switch-row"><span>اجرا هنگام ورود به سیستم<small>پنبه در سینی سیستم باز می‌شود تا اعلان‌ها برسند</small></span>
          <input type="checkbox" role="switch" checked={autostart} onChange={(e) => setAutostart(e.target.checked).then(setOn, (err) => alertDialog(errText(err)))} /></label>
      )}
      {version && <p className="muted">نسخه‌ی <bdi dir="ltr">{version}</bdi></p>}
    </>
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
        <label className="switch-row"><span>نام‌بردن از همه (@room)<small>پیام‌هایی که همه‌ی اعضای گروه را صدا می‌زنند</small></span>
          <input type="checkbox" role="switch" checked={roomRules.some((r) => r.enabled)} disabled={busy}
            onChange={(e) => act(() => Promise.all(roomRules.map((r) => client.setPushRuleEnabled("global", PushRuleKind.Override, r.rule_id, e.target.checked))))} /></label>
      )}
      <form className="form keywords" onSubmit={add}>
        <small className="muted">کلیدواژه‌ها: پیام‌های شامل این کلمه‌ها مثل نام‌بردن از شما اعلان می‌شوند</small>
        {keywords.length > 0 && (
          <div className="chips">
            {keywords.map((r) => (
              <span key={r.rule_id} className="chip">
                <button type="button" className="plain" disabled={busy} aria-label={`حذف ${r.pattern}`}
                  onClick={() => act(() => client.deletePushRule("global", PushRuleKind.ContentSpecific, r.rule_id))}><Icon name="close" size={16} /></button>
                {r.pattern}
              </span>
            ))}
          </div>
        )}
        <div className="form-head">
          <input value={word} onChange={(e) => setWord(e.target.value)} placeholder="کلیدواژه‌ی تازه" aria-label="کلیدواژه‌ی تازه" />
          <button className="primary" disabled={busy || !word.trim()}>افزودن</button>
        </div>
      </form>
    </>
  );
}

/** My name + photo, read from the server (client.getUser can be missing or stale). */
function MyProfile() {
  const [profile, setProfile] = useState<{ displayname?: string; avatar_url?: string }>({});
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    client.getProfileInfo(me()).then((p) => { setProfile(p); setName(p.displayname ?? ""); }, () => {});
  }, []);
  const act = async (fn: () => Promise<typeof profile>) => {
    setBusy(true);
    try { const patch = await fn(); setProfile((p) => ({ ...p, ...patch })); } catch (e) { alertDialog(errText(e)); }
    setBusy(false);
  };
  const dirty = name.trim() !== "" && name.trim() !== (profile.displayname ?? "");

  return (
    <div className="profile">
      <div className="avatar-col">
      <label className="avatar-edit" title="تغییر عکس">
        <input type="file" accept="image/*" hidden disabled={busy} onChange={(e) => { const f = e.target.files?.[0]; if (f) act(async () => ({ avatar_url: await setMyAvatar(f) })); }} />
        <Avatar mxc={profile.avatar_url} name={profile.displayname || me()} id={me()} size={64} />
      </label>
      {profile.avatar_url && <button className="photo-remove" disabled={busy}
        onClick={() => confirmDialog("عکس پروفایل حذف شود؟", { danger: true }).then((y) => y && void act(async () => (await client.setAvatarUrl(""), { avatar_url: undefined })))}>حذف عکس</button>}
      </div>
      <form className="form" onSubmit={(e) => { e.preventDefault(); const n = name.trim(); act(async () => (await client.setDisplayName(n), { displayname: n })); }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="نام" aria-label="نام" required />
        <span dir="ltr">{me()}</span>
        {dirty && <button className="primary" disabled={busy}>ذخیره</button>}
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
      <p>این کلید بازیابی را جای امنی نگه دارید. برای خواندن پیام‌هایتان در دستگاه جدید به آن نیاز دارید.</p>
      <code className="recovery-key" dir="ltr">{newKey}</code>
      <button onClick={() => navigator.clipboard.writeText(newKey)}>کپی</button>
    </div>
  );
  if (!state) return <p className="muted">در حال بررسی…</p>;
  if (state === "ok") return <p className="card ok"><Icon name="lock" size={16} /> این دستگاه تأییدشده است و پشتیبان کلیدها روشن است.</p>;
  return (
    <form className="card" onSubmit={submit}>
      <p>{state === "unlock"
        ? "کلید بازیابی یا عبارت امنیتی خود را وارد کنید تا این دستگاه تأیید شود و پیام‌های رمزنگاری‌شده‌ی قبلی را بخوانید."
        : "بازیابی را راه‌اندازی کنید تا پیام‌های رمزنگاری‌شده را در دستگاه‌های دیگر هم بخوانید." + (oauth ? "" : " با رمز عبور حسابتان تأیید کنید.")}</p>
      {!(oauth && state === "setup") && <input type={state === "setup" ? "password" : "text"} value={input} onChange={(e) => setInput(e.target.value)}
        placeholder={state === "unlock" ? "کلید بازیابی یا عبارت امنیتی" : "رمز عبور حساب"} required autoComplete="off" />}
      {error && <p className="error">{error}</p>}
      <button className="primary" disabled={busy}>{busy ? "در حال انجام…" : state === "unlock" ? "باز کردن قفل" : "راه‌اندازی بازیابی"}</button>
      {state === "unlock" && <button type="button" onClick={otherDevice}>تأیید با دستگاه دیگر</button>}
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

  if (!list) return <p className="muted">{error || "در حال بارگذاری…"}</p>;
  const mine = list.find((d) => d.device_id === client.getDeviceId());
  const others = list.filter((d) => d !== mine).sort((a, b) => (b.last_seen_ts ?? 0) - (a.last_seen_ts ?? 0));
  const row = (d: Device, out?: boolean) => {
    const t = trust[d.device_id];
    const verified = !!t?.crossSigningVerified;
    return (
      <div key={d.device_id} className="user-row">
        <span className="device-box"><Icon name="lock" /></span>
        <span>
          <b>{d.display_name ?? d.device_id}</b>
          {t !== undefined && <small className={"trust" + (verified ? " ok" : "")}>{verified ? "تأییدشده" : t ? "تأییدنشده" : "بدون رمزنگاری"}</small>}
          <small>{d.last_seen_ts ? stamp(d.last_seen_ts) : ""} {d.last_seen_ip && <bdi dir="ltr">{d.last_seen_ip}</bdi>}</small>
          <small className="id" dir="ltr">{d.device_id}</small>
        </span>
        {out && t && !verified && <button className="row-btn" disabled={busy} onClick={() => verify(d.device_id)}>تأیید</button>}
        {out && <button className="row-btn" disabled={busy} onClick={() => (oauth ? manage(d.device_id) : remove([d.device_id]))}>خروج</button>}
      </div>
    );
  };

  return (
    <>
      {mine && <><h3>این دستگاه</h3>{row(mine)}</>}
      {others.length > 0 && <h3>دستگاه‌های دیگر</h3>}
      {others.map((d) => row(d, true))}
      {others.length > 0 && (oauth
        ? <button className="primary" onClick={() => manage()}>مدیریت دستگاه‌ها</button>
        : <button className="danger" disabled={busy} onClick={() => confirmDialog("از همه‌ی دستگاه‌های دیگر خارج می‌شوید؟", { danger: true }).then((y) => y && void remove(others.map((d) => d.device_id)))}>خروج از همه‌ی دستگاه‌های دیگر</button>)}
      {noUrl && <p className="muted">سرور صفحه‌ی مدیریت دستگاه‌ها را ارائه نمی‌کند.</p>}
      {pending && (
        <form className="card form" onSubmit={(e) => { e.preventDefault(); remove(pending, password); }}>
          <p>برای تأیید، رمز عبور حسابتان را وارد کنید.</p>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="رمز عبور حساب" autoComplete="current-password" required autoFocus />
          <button className="primary" disabled={busy}>تأیید</button>
          <button type="button" onClick={() => { setPending(null); setPassword(""); }}>لغو</button>
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
      <p className="muted">این کار در صفحه‌ی حساب کاربری سرور انجام می‌شود.</p>
      <button className="primary" onClick={open}>باز کردن صفحه‌ی حساب</button>
      {noUrl && <p className="muted">سرور صفحه‌ی مدیریت حساب را ارائه نمی‌کند.</p>}
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
    try { setDone((await fn()) || ""); } catch (e) { setError(e instanceof NeedsPassword ? "رمز عبور لازم است" : errText(e)); }
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
      if (next !== again) throw new Error("تکرار رمز با رمز تازه یکی نیست");
      // false: the server would otherwise sign out every other device
      await withPassword((auth) => client.setPassword(auth!, next, false), current);
      setCurrent(""); setNext(""); setAgain("");
      return "رمز عبور تغییر کرد.";
    });
  };
  return (
    <form className="form" onSubmit={submit}>
      <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} placeholder="رمز عبور فعلی" autoComplete="current-password" required />
      <input type="password" value={next} onChange={(e) => setNext(e.target.value)} placeholder="رمز عبور تازه" autoComplete="new-password" required minLength={8} />
      <input type="password" value={again} onChange={(e) => setAgain(e.target.value)} placeholder="تکرار رمز عبور تازه" autoComplete="new-password" required />
      {error && <p className="error">{error}</p>}
      {done && <p className="card ok"><Icon name="check" size={16} /> {done}</p>}
      <button className="primary" disabled={busy}>{busy ? "در حال انجام…" : "تغییر رمز"}</button>
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
      if (pass !== again) throw new Error("تکرار عبارت عبور یکی نیست");
      const text = await encryptKeyFile(await client.getCrypto()!.exportRoomKeysAsJson(), pass);
      const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
      const downloads = await saveFile(url, "element-keys.txt").finally(() => setTimeout(() => URL.revokeObjectURL(url), 1000));
      setPass(""); setAgain("");
      return downloads ? "فایل کلیدها در پوشه‌ی دانلودها ذخیره شد." : "فایل کلیدها ذخیره شد.";
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
      return `${num(JSON.parse(json).length)} کلید وارد شد.`;
    });
  };
  return (
    <>
      <p className="muted">کلیدهای پیام‌های رمزنگاری‌شده را در یک فایل رمزدار ذخیره کنید تا در برنامه‌ی دیگری (مثل Element) وارد شوند.</p>
      <form className="form" onSubmit={exportKeys}>
        <h3>ذخیره در فایل</h3>
        <input type="password" value={pass} onChange={(e) => setPass(e.target.value)} placeholder="عبارت عبور برای فایل" autoComplete="new-password" required />
        <input type="password" value={again} onChange={(e) => setAgain(e.target.value)} placeholder="تکرار عبارت عبور" autoComplete="new-password" required />
        {ex.error && <p className="error">{ex.error}</p>}
        {ex.done && <p className="card ok"><Icon name="check" size={16} /> {ex.done}</p>}
        <button className="primary" disabled={ex.busy}>{ex.busy ? "در حال رمزنگاری…" : "ذخیره‌ی کلیدها"}</button>
      </form>
      <form className="form" onSubmit={importKeys}>
        <h3>وارد کردن از فایل</h3>
        <input type="file" accept=".txt,text/plain" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required aria-label="فایل کلیدها" />
        <input type="password" value={inPass} onChange={(e) => setInPass(e.target.value)} placeholder="عبارت عبور فایل" autoComplete="off" required />
        {im.error && <p className="error">{im.error}</p>}
        {im.done && <p className="card ok"><Icon name="check" size={16} /> {im.done}</p>}
        <button className="primary" disabled={im.busy}>{im.busy ? (progress ? `در حال وارد کردن ${progress}` : "در حال رمزگشایی…") : "وارد کردن کلیدها"}</button>
      </form>
    </>
  );
}

function Blocked() {
  useTick(client, [ClientEvent.AccountData]);
  const [busy, setBusy] = useState(false);
  const ids = client.getIgnoredUsers();
  const unblock = (id: string) => { setBusy(true); setBlocked(id, false).catch((e) => alertDialog(errText(e))).finally(() => setBusy(false)); };
  if (!ids.length) return <p className="muted">کسی را مسدود نکرده‌اید.</p>;
  return ids.map((id) => {
    const u = client.getUser(id);
    return (
      <div key={id} className="user-row">
        <Avatar mxc={u?.avatarUrl} name={u?.displayName ?? id} id={id} size={42} />
        <span><b>{u?.displayName ?? id}</b><small dir="ltr">{id}</small></span>
        <button className="row-btn" disabled={busy} onClick={() => unblock(id)}>رفع مسدودیت</button>
      </div>
    );
  });
}

function Deactivate() {
  const [password, setPassword] = useState("");
  const { busy, error, run } = useForm();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!(await confirmDialog("حساب برای همیشه غیرفعال شود؟ این کار برگشت‌پذیر نیست.", { danger: true }))) return;
    run(async () => {
      await withPassword((auth) => client.deactivateAccount(auth, false), password);
      await logout(); // reloads into the login screen
    });
  };
  return (
    <form className="form" onSubmit={submit}>
      <p className="card">با غیرفعال‌سازی، از همه‌ی گفتگوها خارج می‌شوید، از همه‌ی دستگاه‌ها خارج می‌شوید و دیگر نمی‌توانید با این حساب وارد شوید. نام کاربری شما هم دوباره قابل ثبت نیست.</p>
      <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="رمز عبور حساب" autoComplete="current-password" required />
      {error && <p className="error">{error}</p>}
      <button className="danger" disabled={busy}>{busy ? "در حال انجام…" : "غیرفعال‌سازی همیشگی حساب"}</button>
    </form>
  );
}
