# Voxarium Remote for Android

Voxarium Remote connects an Android device to the Documents service of a Voxarium Studio Remote server. It can browse projects, synchronize Markdown for offline reading, and open existing documents in a collaborative room. The native client uses Kotlin and Jetpack Compose Material 3. The editor runs locally in Android System WebView. English and Russian follow the system language, and the app follows the system light or dark theme.

**Version 0.2.0** (version code 2), package `com.voxarium.remote`. The default source build opens original documents read-only. Publishing edits requires a compatibility report for the exact sources used to build the APK.

## Requirements

- Android 6.0 (API 23) or later. The app compiles against and targets API 36.
- An up-to-date WebView with `WEB_MESSAGE_LISTENER` support for the secure room editor. The native Markdown reader remains available if the bridge is unavailable.
- A reachable Voxarium Studio Remote server with live protocol v2, a project catalog, explicit project creation and strict project selection. Obtain the Remote HTTPS base URL and a personal access key from its administrator. The server must support WSS at the same endpoint and present a trusted certificate for its hostname.

The APK includes the editor and its dependencies. The native client handles credentials and network access. The WebView loads bundled assets locally.

## Build and install a debug APK

Install JDK 21, Android SDK Platform 36, Android Build Tools and Node.js with npm. Set `JAVA_HOME` and configure the Android SDK through `ANDROID_HOME` or a local `local.properties`. The repository includes the Gradle 8.14.7 wrapper and a lockfile for the editor. Historical editor checks used Node 24.18.1 and npm 11.16.0.

From the repository root on Windows, run:

```powershell
Push-Location editor-web
try {
    npm ci --ignore-scripts
    if ($LASTEXITCODE) { throw 'Could not install editor dependencies' }
} finally { Pop-Location }
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Gradle type-checks and tests the editor, builds the local assets, and checks the resulting bundle. Packaging also runs the native unit tests. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Keep SDK paths, build outputs and signing credentials local.

If type-checking cannot find `tsc`, check that `editor-web/node_modules/.bin/tsc.cmd` exists on Windows. If an old directory junction points to a missing target, remove only the broken link. Run `npm ci --ignore-scripts` and `npm run typecheck` from `editor-web` to restore the dependencies.

To install on your own USB-debugging device, run the following with Android Platform Tools on your PATH. Add `-s <serial>` after `adb` if several devices are connected.

```powershell
adb devices
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -W -n com.voxarium.remote/.MainActivity
```

The APK uses a debug signing identity. If Android reports a signature mismatch, preserve important local copies before considering an uninstall. Uninstalling or clearing app data removes profiles, keys, cached documents and recovery data. The app has no full-storage export.

## Connect to a project

1. Open **Servers → Add server**. Enter a name and an HTTPS base URL such as `https://remote.example/proxy`. Keep any reverse-proxy prefix. Omit API routes, credentials, query strings and fragments.
2. Enter your personal access key without a `Bearer ` prefix. The field accepts 1–8192 printable ASCII characters with no whitespace. **Save server** stores the settings locally. Use **Verify server** to check the key and account.
3. Choose **Browse projects** or look up an exact project ID. Review the server, account, project ID and storage epoch before selecting it. **Create project** sends a separate request after confirmation. If its outcome is uncertain, look up the original ID before retrying.
4. Confirm the connection to the selected project. A missing project stays missing. If the server lacks catalog support, upgrade it. Project IDs contain 1–64 lowercase ASCII letters, digits, dots, hyphens or underscores and start with a letter or digit. The client preserves the exact ID.
5. Wait for **Connected — cache synchronized**, then expand folders and select a document. The tree reflects server paths. Paths differing only in case appear separately with a Windows compatibility warning.

After restarting, explicitly select and connect the project. **Open cached documents** shows previously synchronized Markdown and its revision offline. Recent remote edits and pending room changes may be newer than the cache.

## Edit and close a document

Opening a document online joins its room and obtains the initial state before mounting the editor. Offline, the native reader shows cached Markdown. Reading or opening an unchanged document sends no document write.

In a compatible write-enabled build, wait for the editor to become ready. The toolbar supports bold, italic, bullet lists, inline code, undo and redo. Undo tracks your own input while preserving other participants' edits. Edits propagate as CRDT updates. The designated room participant, the *scribe*, saves the Markdown on Remote. Autosave waits about 500 ms after input and attempts a save within three seconds during continuous typing. Queueing and acknowledgement can take longer.

Use **Close editor** or Back and wait for the result. The client requests an IME flush and confirmation for the latest Markdown, or retains a local recovery copy. Disconnecting or switching also goes through this step. Force-stopping the process can interrupt it. The document UI works with existing files. File creation, deletion, renaming, image uploads and publication of conflict copies are outside its scope.

| Status | What it confirms |
| --- | --- |
| Connected — cache synchronized | The mirror is current as of synchronization. Check editor status for pending edits. |
| Editor ready / in room | The editor joined the room. A Remote save may still be pending. |
| Waiting for save confirmation | A snapshot is queued or awaiting acknowledgement. |
| Markdown save confirmed / Closed — Markdown save confirmed | Remote acknowledged that specific Markdown snapshot. Later edits need their own acknowledgement. |
| Closed with local recovery | Remote saving is unconfirmed. Open recovery before discarding anything. |
| Incompatible schema / writes blocked | The editor stopped publication to protect the original document. |
| Read-only / writes disabled pending verification | This build lacks an accepted compatibility report for its sources. |
| Reconnect required / revision changed / document missing | Inspect recovery and synchronize again. A deleted document stays deleted. |

## Troubleshooting and local recovery

- **401 / 4401 or a revoked key.** Automatic retries stop. Obtain a valid key, replace it on the saved server and verify the account again.
- **4400 or an incompatible protocol.** Check the exact server URL and project ID. Use compatible client and server versions.
- **TLS failure.** Check the certificate chain, hostname and trust configuration. The client enforces TLS checks in debug builds as well.
- **Network timeout or full receive queue.** The client may retry after a delay. Disconnect cancels retries. Cached Markdown remains readable. Reconnecting starts a fresh handshake and sync.
- **Lost WebView or unconfirmed close.** Inspect recovery first. An acknowledgement for an older snapshot does not confirm newer input.
- **Storage full or unavailable.** Preserve readable copies before clearing anything. A failing disk can prevent the app from saving recovery data.

Open **Local recovery copies** from the document list, reader or editor. Select an entry and use **Copy Markdown** or select the text. Compare room snapshots, conflicts and unconfirmed drafts with the current Remote document manually. Keep both versions until you know which changes to retain.

Recovery never automatically replays edits into another room. After a protective refusal, readable Markdown may contain only the last valid text. A newer rejected change may survive as binary Yjs diagnostics that the UI cannot decode or export. The app scopes cached and recovered material by identity. Switching projects, accounts or storage epochs keeps old edits local. Manually copying Markdown places its contents on the system clipboard.

## Document support and storage limits

- Documents use relative POSIX paths ending in `.md`, `.markdown` or `.txt`, up to 512 Unicode code points. A document can contain up to 1,000,000 UTF-8 bytes. Large or fast replays can exhaust bounded queues.
- The schema supports headings 1–6, lists and tasks, tables, directives, colors and preserved envelope data. Unsupported Markdown can appear as an opaque node. An unknown CRDT schema or a lossy round trip blocks editing.
- Images are placeholders that retain links and attributes. The WebView does not execute document HTML, navigate external or wiki links, upload files or fetch remote resources.
- An upstream codec issue can remove visible spaces around adjacent or nested colors on save and reopen. Android checks the complete color round trip and conservatively rejects unsafe edge spaces, tabs and bare carriage returns. A refusal applies until a fresh safe open. The client cannot recover spaces already lost in a separate Studio write.
- Personal keys use AES-GCM with Android Keystore. Hardware-backed protection depends on the device. Cached documents and recovery data are app-private and excluded from backup. The Documents layer does not encrypt their contents.
- Document blobs have a 64 MiB limit. The room journal is limited to 128 entries and 48 MiB. Recovery entries are not automatically evicted. Disk failure, rollback, clearing app data or uninstalling can destroy local copies.

Remote persists **materialized Markdown**. Room Yjs state is ephemeral. The last participant leaving, a crash or an external write can discard or reset unmaterialized state. Checkpoints and acknowledgements reduce the window of loss, including during IME input. Keep independent backups of important documents.

## Verification and attribution

Historical interoperability checks recorded 23 passing browser scenarios and one upstream whitespace failure. Separate checks exercised HTTPS/WSS with the native coordinator and reference Studio editor, and exercised the WebView, keyboard and native bridge on an API 35 emulator with WebView 124 in English and Russian. Device tests used controlled transport. Coverage did not include a physical device matrix, TalkBack, every lifecycle transition or a single end-to-end run combining a write-enabled APK, a device WebView and a live Remote. The historical approval applies to the tested sources. An absent acceptance report keeps writes closed, and a stale report fails the build.

The editor includes code ported from Voxarium Studio and third-party components. See the [editor notice](editor-web/NOTICE) and [license index](editor-web/licenses/index.json).
