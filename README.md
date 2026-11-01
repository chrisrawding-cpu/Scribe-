# Scribe: an AI voice recorder backed by your own Google Cloud

Scribe is a standalone Android app with the core features of Plaud and Pocket-style AI recorders:
recording, transcription, summaries, mind maps, tasks and "ask your recordings". The difference is
where the data goes. Audio, transcripts and notes are stored in **your own Google Cloud Storage
bucket**, and the AI work runs on **Vertex AI Gemini in your own Cloud project**. No third-party
servers, no subscription and no vendor cloud are involved.

## Features

| | |
|---|---|
| 🎙️ **One-tap recording** | Background recording with the screen off. Pause/resume, and flag important moments, from the notification as well as the app. Also a Quick Settings tile ("Scribe record") and a launcher shortcut (long-press the icon). |
| 📥 **Import / share in** | Import any audio or video file, or *Share → Scribe* from WhatsApp voice notes, call-recorder apps, Zoom/Teams downloads and similar. |
| 📝 **Transcription** | Gemini transcription with **speaker diarization** and timestamps. Rename speakers, tap a line to play from that point, long-press to correct text, search the transcript. Long recordings are processed in 20-minute windows. |
| ✨ **AI summaries with templates** | Auto, Meeting minutes, 1:1, Sales call (BANT + draft email), Interview, Lecture (study notes + quiz), Brainstorm, Stand-up, Doctor visit and Voice memo, plus **your own custom templates**. Re-summarise with a different template at any time. |
| 🧠 **Mind maps** | Interactive mind map for every recording: pinch-zoom, pan, tap to fold branches, share as an outline. |
| ✅ **Action items → Tasks** | Tasks extracted with owner, due date and priority. A Tasks tab collects open to-dos across all recordings, and you can add any of them to Google Calendar. |
| 📚 **Key points, decisions, open questions, chapters** | Chapters are clickable and jump the player. |
| 💬 **Ask AI** | Chat with one recording, or with your **whole library** ("what did I promise the client last week?"). |
| 📅 **Daily digest** | Pocket-style daily journal: highlights, people, commitments and follow-ups for any day. |
| 🌍 **Translate** | Translate a summary into any language. |
| 📤 **Export** | Share the summary or full notes, save as Markdown, export subtitles (.srt), share the audio, or have the AI draft a follow-up email. |
| ☁️ **Sync / restore** | Everything is mirrored to your bucket, so a new phone restores the full library. |
| 🔎 **Search & organise** | Full-text search across titles, summaries and transcripts, plus tags (auto-suggested by the AI) and stars. |

### What goes where in your bucket

```
gs://YOUR-BUCKET/scribe/recordings/<id>/audio.m4a
gs://YOUR-BUCKET/scribe/recordings/<id>/record.json   ← metadata, transcript, notes, mind map, tasks
gs://YOUR-BUCKET/scribe/templates.json
gs://YOUR-BUCKET/scribe/digests/<yyyy-mm-dd>.md
```

---

## 1. Install the app on your phone

Every push to this repository builds an APK with GitHub Actions.

1. On your phone, open this repository's **Releases** page (`github.com/chrisrawding-cpu/Scribe-/releases`).
2. Open the newest **Scribe build N** and tap **Scribe.apk**.
3. Open the downloaded file. When Android asks, allow *Install unknown apps* for your browser.

### Stable signing key (recommended, one-time)

Google sign-in identifies the app by its signing certificate (SHA-1), so every build needs to be signed
with the same key. Add these four **repository secrets** under *Settings → Secrets and variables →
Actions → New repository secret*:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | base64 of your `.jks` keystore |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password (can be omitted if it is the same as the store password) |

To create a keystore yourself:

```bash
keytool -genkeypair -keystore scribe-release.jks -storetype PKCS12 -alias scribe \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 scribe-release.jks   # → SIGNING_KEYSTORE_BASE64
```

Then re-run the workflow (Actions → Build Android APK → Run workflow). Without these secrets the build
still works, but it is signed with a throwaway key, so the SHA-1 changes on every build.

## 2. Prepare your Google Cloud project (about 10 minutes, or ask your Cloud admin)

In the [Google Cloud console](https://console.cloud.google.com), in your work project:

1. **Enable APIs**: *Vertex AI API* and *Cloud Storage* (APIs & Services → Library).
2. **Create a bucket** (Cloud Storage → Buckets → Create), e.g. `yourcompany-scribe`, in a region you're
   happy with (e.g. `europe-west2` London). Keep *uniform access* and *public access prevention* on.
   The app can also create the bucket for you from Settings.
3. **Permissions** for your account (or the service account):
   - `Vertex AI User` on the project
   - `Storage Object Admin` on the bucket
4. **Choose how the app signs in**:

   **Option A: your work Google account (recommended)**
   - APIs & Services → **OAuth consent screen**: set *User type = Internal* (Workspace orgs), add the
     scope `.../auth/cloud-platform`.
   - APIs & Services → **Credentials → Create credentials → OAuth client ID → Android**:
     - Package name: `com.scribe.app`
     - SHA-1: shown in the app under **Settings → For your Cloud admin** (and in `signing-cert.txt` on each release).

   **Option B: service account key**
   - IAM → Service accounts → create one with the roles above → Keys → Add key → JSON.
   - Copy the JSON file to your phone and import it in the app. (Many organisations block key creation
     by policy. If so, use option A.)

## 3. Configure the app

Open **Settings** in Scribe:

1. Enter the **Project ID** and **Bucket**.
2. Pick the **Vertex AI region**. `global` gives access to the newest Gemini models. Pick a specific
   region such as `europe-west2` if your organisation requires data residency.
3. Tap **Sign in with Google** (or import the service account key).
4. Tap **Detect models**. The app finds the newest Gemini Flash/Pro models your project can use. Model
   names change over time; you can also type a model ID yourself.
5. Tap **Test connection**. You should see three ✓ lines.

Then go back to the Library and tap **Record**.

---

## Building locally

Requires Android Studio (Ladybug or newer) or JDK 17 plus the Android SDK (platform 35):

```bash
./gradlew assembleDebug
```

## Architecture

- Kotlin, Jetpack Compose (Material 3), Room, WorkManager, OkHttp, kotlinx.serialization.
- `recording/`: foreground microphone service (AAC/M4A), Quick Settings tile.
- `cloud/`: OAuth via Google Play services `AuthorizationClient` or a service-account JWT; a minimal Cloud
  Storage JSON API client; a Vertex AI `generateContent` client using structured JSON output.
- `ai/`: prompts and JSON schemas for transcription (diarization, chunking), notes, mind maps, chat,
  digest and translation, plus the built-in templates.
- `work/`: background pipeline (upload → transcribe → summarise → sync) that survives the app closing.
- `sync/`: bucket mirror with restore on a new device.

## Limitations

- Android does not let third-party apps record both sides of a normal phone call. Use speakerphone, or
  share a call recording from your phone's built-in recorder into Scribe.
- Very long recordings (3 h+) are transcribed in sequential chunks and take a few minutes.
- Vertex AI usage is billed to your Cloud project at standard Gemini rates. A one-hour meeting typically
  costs a few pence with Flash models.
