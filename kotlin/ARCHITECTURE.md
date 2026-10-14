# Architecture

Three Gradle modules. Dependencies only point downwards: `app → client → core`.

| Module | Kind | What lives there |
| --- | --- | --- |
| `core` | Kotlin/JVM | Pure logic ported from the web app's `src/logic.ts`, `uri.ts` and `keyfile.ts`: Persian/Jalali formatting, timeline row building, folders, markdown, links, polls, packs, admin levels, call helpers, blurhash, PIN hashing, the Megolm key file format. No Matrix SDK, no Android. |
| `client` | Kotlin/JVM | The Matrix layer on top of [Trixnity](https://gitlab.com/connect2x/trixnity): accounts and login (password, OAuth/MAS), and services the UI calls (rooms, timeline, sending, crypto, delayed events, packs, push rules, admin, MatrixRTC signalling). It talks to the platform only through `Platform` (key-value store, Trixnity stores, HTTP engine). |
| `app` | Android | Compose UI, view models, the `SyncService` foreground service, notifications, calls (LiveKit, Telecom), media, the Room/okio stores for Trixnity. |

`core` and `client` build and test without the Android SDK:

```sh
PANBEH_NO_ANDROID=1 ./gradlew :core:test :client:test
# client integration tests need a homeserver with open registration (e.g. a local Synapse):
PANBEH_TEST_HS=http://127.0.0.1:8008 PANBEH_NO_ANDROID=1 ./gradlew :client:test
```

## Conventions

- One running `MatrixClient` at a time (`Accounts`). Switching accounts closes it and opens the other one from its store.
- UI strings are Persian literals, as in the web app. Numbers and dates go through `core` (Persian digits, Jalali calendar).
- The layout is forced right-to-left (`PanbehTheme`).
- Protocol strings (event types, MSC prefixes, account-data keys) are the same as the web app's, so both clients can share an account. See `PARITY.md`.
