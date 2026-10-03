# Panbeh for Windows, macOS and Linux

The desktop app is the same web app inside [Electron](https://www.electronjs.org), which ships its own
Chromium, so it behaves exactly like the web version in Chrome. The code in `src/` serves the web, Android and
desktop; `src/desktop.ts` is the only desktop-specific part on the web side, and `desktop/` holds the
Electron side (`main.cjs`, plus `preload.cjs`, the only bridge between the two).

- The app is served from `app://panbeh/`, a private secure origin, so its storage (sessions, encryption keys)
  is separate from any browser and survives updates.
- Only one copy runs at a time: opening it again brings the running window to the front.

## Notifications and the tray

Closing the window doesn't quit Panbeh: it moves to the system tray (the menu bar on macOS), keeps syncing,
and shows new messages as system notifications. Quit from the tray icon's menu (or ⌘Q on macOS).

- The tray icon gets a red dot while there are unread chats. The count also appears on the dock icon
  (macOS), the launcher (Ubuntu/KDE) or as a dot on the taskbar button (Windows).
- While the window is hidden, you show as "away", not "online", like the Android app in the background.
- Settings → برنامه → «اجرا هنگام ورود به سیستم» starts Panbeh with the system, straight into the tray.
- On GNOME, tray icons need the "AppIndicator and KStatusNotifierItem Support" extension (Ubuntu has it built in).

## Getting it

Every push builds the installers (Actions tab → *Desktop apps* run → artifacts). Every build of `main` also
goes to the rolling [latest](../../releases/tag/latest) pre-release, next to the APK. Pushing a tag `v1.0.0`
attaches them to that release:

| System | File |
| --- | --- |
| Windows 10/11 | `Panbeh-windows-setup.exe`: installs for the current user, no admin needed |
| macOS 11+ (Intel and Apple Silicon) | `Panbeh-mac.dmg` |
| Linux | `Panbeh-linux-x86_64.AppImage` (any distro: `chmod +x` and run) or `Panbeh-linux-amd64.deb` |

### First launch of unsigned builds

The apps aren't code-signed yet, so the first launch shows a warning:

- **Windows**: SmartScreen says "Windows protected your PC" → *More info* → *Run anyway*.
- **macOS**: "Apple could not verify…" → System Settings → Privacy & Security → *Open Anyway* (once).
  Or, in Terminal: `xattr -dr com.apple.quarantine /Applications/Panbeh.app`.

## Updates

The app checks for a new version at start and every 6 hours, against GitHub releases made from `v*` tags
(the rolling `latest` builds are for manual download and never auto-update anyone).

- **Windows, Linux AppImage/deb**: the update downloads in the background and installs when you restart
  Panbeh (it asks; if you say later, it installs the next time Panbeh quits).
- **macOS**: macOS refuses to auto-install updates of unsigned apps, so Panbeh only says a new version is out
  and opens the download page. Once the app is signed (below), set `autoDownload` in
  `desktop/main.cjs` to `true` on macOS too.

To release: bump nothing, just push a tag, e.g. `git tag v1.0.0 && git push origin v1.0.0`. The version
comes from the tag.

## Signing (optional, later)

CI signs automatically once these secrets exist (GitHub → Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `MAC_CSC_LINK` | Developer ID Application certificate, `.p12` as base64 (`base64 -i cert.p12`) |
| `MAC_CSC_KEY_PASSWORD` | the `.p12` password |
| `APPLE_ID`, `APPLE_APP_SPECIFIC_PASSWORD`, `APPLE_TEAM_ID` | for notarization (an app-specific password from appleid.apple.com) |
| `WIN_CSC_LINK` | Windows code-signing certificate, `.pfx` as base64 |
| `WIN_CSC_KEY_PASSWORD` | the `.pfx` password |

macOS needs an Apple Developer account ($99/year). Without the Apple secrets, macOS builds get an ad-hoc
signature, the minimum Apple Silicon needs to run them at all.

## OAuth login (MAS)

As on Android, login opens in your browser and comes back to the app through the `ir.panbeh.app:/oauth`
URL scheme, which the installers register. With the bare AppImage, the scheme is registered only once the
AppImage has been integrated into the system (e.g. by AppImageLauncher); otherwise use the `.deb`, or a
password login. See the end of [ANDROID.md](ANDROID.md) to change the scheme.

## Building locally

You need Node 22.

```sh
npm ci
npm run desktop        # build the web app and open it in Electron
npm run desktop:dev    # with `npm run dev` running in another terminal: live reload from vite
npm run desktop:dist   # installers for the current OS → release/
```

A Mac can build all three (`npx electron-builder --mac --win --linux`); Linux and Windows can't build the
macOS one.

All the libraries the web app uses are `devDependencies`, because vite bundles them into `dist/`: whatever
is in `dependencies` gets packed into the desktop app as is. Only `electron-updater` belongs there.

## Links

Panbeh registers the `matrix:` scheme (see `protocols` in `electron-builder.yml`), so `matrix:r/room:server`, `matrix:u/user:server` and `matrix:roomid/…/e/…` open in the app. Clicking a `matrix.to` link inside a message opens it in the app too. `matrix.to` links clicked in other apps stay with the browser: a desktop app can't claim an https host it doesn't own.
