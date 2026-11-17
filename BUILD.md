# Tide & Tool Voice Log — Android APK

Native Kotlin app for the S23 Ultra. Not a WebView, not a wrapper around the Pages site.

- **APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **Built:** 2026-11-17 07:56:56 (local), 47.26 MB
- **versionCode:** 1 · **versionName:** 1.0
- **package:** `com.chase.tidetoolvoicelog`
- **minSdk 29 · targetSdk 35** (device runs Android 17 / SDK 37)
- installed and run on `R5CWA1EPTML` (SM-S918U, dm3q)

## How it was built

No Android Studio on this machine, so the toolchain is portable and lives outside the project:

```
D:\Users\chase\android-sdk\jdk-17.0.21+9        Temurin 17
D:\Users\chase\android-sdk\sdk                   platform-tools, platforms;android-35, build-tools;35.0.0
D:\Users\chase\android-sdk\gradle-8.9            Gradle 8.9
```

```powershell
$env:JAVA_HOME='D:\Users\chase\android-sdk\jdk-17.0.21+9'
cd D:\Users\chase\tide-tool-voice-log-apk
D:\Users\chase\android-sdk\gradle-8.9\bin\gradle.bat assembleDebug
```

`local.properties` points at the SDK. AGP 8.7.3, Kotlin 2.0.21, Material 3, one native dependency (Vosk).

## What is in it

| Screen | What it does |
|---|---|
| Main log | grouped by client/job, newest first, search over job name + transcript + notes + tasks. Export a job to `Download/TideTool/*.txt` |
| Voice note | MediaRecorder (AAC/M4A) into app-private storage, live transcript via `SpeechRecognizer`, then the Save screen |
| Save | client/job with recent-job chips, date (from the file name for calls), line tag, editable transcript, notes, "Add as client" with the number pre-filled |
| Detail | playback, editable transcript and notes, action-item checklist, Summarize, Export, Transcribe, Delete |
| Phone book | name + number, add/edit/remove, tolerant matching |
| Settings | AI endpoint (base URL / key / model), auto-import switch, JSON backup export/import, storage counts |
| Setup | call-screening role, the four permissions, full-screen-intent access, and the business-line picker |
| Diagnostics | every field the last incoming call exposed, and the verdict the app reached |
| Prompt | full-screen prompt for a locked phone, "Open Phone" and "Dismiss" |

Storage: SQLite (`SQLiteOpenHelper`, four tables) for metadata, `filesDir/audio` for audio, SharedPreferences for settings and the pinned line. No account, no backend.

### 2nd-line detection

`CallScreeningService` (`onScreenCall`) answers `respondToCall()` first and always allows the call, then decides:

- `Call.Details.getAccountHandle().id` is compared with the account the user pinned in Setup.
- On this phone the handle ids are `1` and `2`, and they map straight onto subscription ids 1/2 and SIM slots 1/2 — so the pinned id is a reliable key here, which is exactly why Setup shows id/sub/slot for every account.
- No pinned account → no prompt, and Diagnostics says so.
- Every call writes account id, label, subscription id, slot, all accounts, and the verdict to Diagnostics.

The prompt is a high-priority notification with a full-screen intent, `ongoing`, dismissed when the call state goes idle (never on answer — Samsung's Record button only exists in the in-call screen).

### Importing Samsung's recording

`MediaStore` query on `display_name LIKE 'Call%'`, never a hard-coded folder. `Call_YYYYMMDD_HHMMSS_<number or name>.m4a` is parsed for the timestamp, the number (tolerant: spaces, dashes, brackets, leading +1) or the contact name, and matched against the phone book. The file is copied into app-private storage before anything else, so a later permission change cannot lose it.

The line tag is `2nd line (Verizon business)` for anything the prompt saw, and for manual imports too (changeable on the save screen and later).

### On-device transcription

Vosk + `vosk-model-small-en-us-0.15` (downloaded once, ~40 MB, unpacked into `filesDir`). Chosen because it builds without the NDK and runs on the CPU.

`MediaExtractor` + `MediaCodec` decode to PCM, then a box-average resample to 16 kHz mono. **Codecs Android's decoder crashes on are refused up front** (`audio/opus`, `audio/vorbis` in an MP4 container): the platform decoder takes the process down with a SIGSEGV that no try/catch can catch, so the app checks the track mime and keeps the audio with a clear message instead.

## What was verified on the device

| # | Check | Result |
|---|---|---|
| 1 | Install + launch | 47.26 MB debug APK installed, app runs, no crash |
| 2 | Permissions/role | role granted, `dumpsys telecom` shows `DefaultCallScreeningApp: com.chase.tidetoolvoicelog`; Setup shows all five items "ready" |
| 3 | Account enumeration | Setup lists `1 · sub=1 · SIM 1` and `2 · sub=2 · SIM 2`; pinned "2" as the business line |
| 4 | Voice note | 9.4 s recorded (AAC/M4A, 76 KB), saved under a typed job name, appears in the log |
| 5 | On-device transcription | the voice note transcribed on the phone: **"hi there hi there"**, `transcript_source=vosk` |
| 6 | Samsung-style import | two `Call_2026…m4a` files pushed to `Recordings/Call`, found through MediaStore, offered as "New recording found" |
| 7 | Unknown number | save screen showed "(808) 555-9999 is not in the phone book" + **Add as client**, number pre-filled; adding it filled the client/job and matched the card |
| 8 | Line tag | saved call carries `2nd line (Verizon business)`, shown in the list ("· 2nd line") and in the text export (`Line: 2nd line (Verizon business)`) |
| 9 | Date from file name | `Call_20261116_090512_…` filed as Nov 16, 9:05 AM |
| 10 | Search | two jobs; searching `hi` returns only the job whose transcript contains it |
| 11 | Action item | added, checked, **force-stopped**, reopened — still checked |
| 12 | Export | `Download/TideTool/tide-tool-<job>-<date>.txt` written and read back for both jobs |
| 13 | Diagnostics | renders the pinned line and "no incoming call seen yet" |
| 14 | Crash handling | Opus-in-MP4 import now fails cleanly (status `failed`, audio kept) instead of killing the app |

## What was NOT tested

- **A real incoming call on the 2nd line.** No call was placed to the phone during this session, so the prompt, the full-screen intent on a locked screen, and the post-call import have never actually run. The pieces are wired and the role is held, but the headline behaviour is unverified.
- **A real Samsung call recording.** The test files are MP4 files named the way Samsung names them; none came from One UI 7's recorder. They turned out to be Opus-in-MP4, which is why the AAC path was proven with the app's own recording instead.
- **Live transcription of real speech.** The speech service started and returned "no words recognised" on a silent room; nothing was spoken into the phone, so the recogniser was never actually heard working.
- **AI summary** with a real provider — no key was configured on the device.
- Anything on a different phone; this was one device, Android 17, page size 4 KB.

## Known limits

- Android prints a "not 16 KB compatible" warning for debug builds because Vosk's `libvosk.so` has 4 KB LOAD alignment. It loads fine on this 4 KB-page phone; on a 16 KB-page device it would not.
- Opus/Vorbis-in-MP4 cannot be transcribed (see above).
- The app cannot record calls, cannot tap Samsung's Record button, and does not try. The tap is the user's.
- No background worker: a new recording is offered when the app is opened (or when you ask it to check), not the instant the call ends.
