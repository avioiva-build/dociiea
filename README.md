# OmniDoc 📄

**Your private document workspace — 100% offline, no account, no ads, no telemetry.**

OmniDoc is a native Android app that opens and edits documents on your phone.
Your files never leave your device: the app does not even have the INTERNET permission.

---

## What it can do (v1.0)

| Area | Features |
|---|---|
| **Word (.docx)** | Open, edit and save real .docx files: bold / italic / underline / strikethrough, headings (Title + H1–H6), font family & size, text colour, highlight, left / centre / right / justify alignment, bullet & numbered lists, insert images, insert simple tables, undo / redo, find & replace, word count |
| **PDF (view)** | Continuous scrolling with pinch-zoom (double-tap to zoom), full-text search, outline/bookmarks panel, jump-to-page, night mode, remembers where you stopped |
| **PDF (edit pages)** | Rotate pages, delete pages, reorder pages (move up/down), extract selected pages into a new PDF, append/merge another PDF, save (with automatic backup of the original) |
| **Text & Markdown** | Edit .txt/.md/log/code files, Markdown preview, find & replace, word count |
| **Safety** | Auto recovery draft for Word documents (crash protection), automatic `.bak` backup copy before every save |
| **Comfort** | Material You theme (follows your wallpaper, dark/light), recent files list, opens files from other apps ("Open with → OmniDoc") |
| **Large files** | PDFs render page-by-page (thousands of pages OK). Word documents are comfortable up to several hundred pages; 1,000+ pages open slower |

**Known simplifications (v1):** complex Word layouts (text boxes, SmartArt, floating images, columns) are simplified to plain content when editing. Old pre-2007 `.doc` files are not supported yet (only `.docx`). This is normal for every mobile office app.

---

## How to get the APK (no coding needed) — 5 minutes

You build the app with the free robot (GitHub Actions) on GitHub.

### Step 1 — Put the project on GitHub
1. Sign in at **github.com** (free account).
2. Click the **+** (top right) → **New repository** → name it `OmniDoc` → choose **Public** (free builds) or Private → **Create repository**.
3. On the new repo page click the link **"uploading an existing file"**.
4. Unzip this project. Open the unzipped `OmniDoc` folder, select **everything inside it** (the `app` folder, `.github` folder, `build.gradle`, `gradlew`, etc. — all of it) and **drag it into the GitHub upload area**.
   - ⚠️ Upload the *contents* of the folder, not the folder itself. The `app` folder must be at the top level of the repo.
5. Click **Commit changes** and wait for the upload to finish.

### Step 2 — Let the robot build
1. Open the **Actions** tab of your repo.
2. If you see a button "I understand my workflows, go ahead and enable them" — click it.
3. A run named **"Build OmniDoc APK"** starts automatically after every upload. If it did not, click it in the left list → **Run workflow** → **Run workflow**.
4. Wait ~5–10 minutes until the dot turns into a green ✔.

### Step 3 — Download & install
1. Click the finished run → scroll to the **Artifacts** section → download **OmniDoc-APK**.
2. Unzip it → you get `app-release.apk`.
3. Copy it to your phone (or download directly on the phone) → tap it → allow "install unknown apps" when asked → install.
4. Open **OmniDoc** and enjoy. 🎉

### Updating later
Change any file on GitHub (pencil icon on the file page → edit → commit), or upload a new version. Actions rebuilds automatically → download the new APK → install over the old one. Your data stays.

---

## Where your backups are

Before every save, OmniDoc copies the previous version of the file to its private folder:
`Android/data/com.omnidoc.app/files/backups/` (visible with any file manager).
Recovery drafts for Word documents live in `Android/data/com.omnidoc.app/files/drafts/`.

## Change the app name or icon later

- **Name:** edit `app/src/main/res/values/strings.xml` → `<string name="app_name">OmniDoc</string>` → commit → rebuild.
- **Icon:** replace the `ic_launcher*.png` files inside `app/src/main/res/mipmap-*dpi/` folders with same-size PNGs → commit → rebuild.

## Privacy

The manifest contains **no INTERNET permission**. OmniDoc physically cannot send your documents anywhere. All engines (Word converter, PDF renderer) run on-device.

## Roadmap (ask for these next)

- RTF / ODT support · CSV & XLSX grid editor · export to PDF · share & print · PDF annotation layer · version snapshots · tabs · Bangla UI
