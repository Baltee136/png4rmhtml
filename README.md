# HTML → PNG (Android, offline, real browser engine)

A native Android app that converts HTML (with CSS, JS, web fonts, emoji, and
images) into a pixel-perfect PNG — rendered by Android's real Chromium-based
WebView engine, **not** a canvas reconstruction like html2canvas. This avoids
the kerning, line-height, and element-overlap bugs that canvas-based tools have,
because the screenshot is the actual compositor output of a real browser engine.

## How it avoids the html2canvas problem

html2canvas (and similar libraries) re-implement CSS layout and text shaping
in JavaScript and draw shapes onto a `<canvas>`. They frequently get kerning,
line-height, flexbox/grid edge cases, and overlapping/clipping wrong because
they're reverse-engineering the browser instead of using it.

This app instead:
1. Serves your uploaded HTML/CSS/JS/fonts/images over a real local HTTP server
   (so all relative paths, `@font-face`, `<img>`, and `fetch()` work exactly
   like a live website).
2. Loads that URL into a real Android `WebView` (Chromium engine — the same
   family of engine as desktop Chrome).
3. Resizes the WebView to the exact CSS pixel width you want, lets the engine
   fully lay out and paint the page, then takes a screenshot of the WebView's
   *actual painted pixels* (`view.draw(canvas)`, which goes through the real
   paint pipeline — not a DOM-to-shapes reimplementation).
4. Optionally upscales 2x/3x for crisp/retina output, then saves as PNG.

## Features

- Upload a single `.html` file, or a `.zip` bundle containing HTML + CSS + JS
  + local images + local font files (woff/woff2/ttf/otf).
- "Fetch fonts & images online" step: scans your HTML/CSS for remote
  `@font-face` URLs (including Google Fonts `@import`) and remote `<img src>`
  URLs, downloads them, and rewrites references to local copies — so the page
  renders fully offline afterward, with no network dependency during capture.
- Live in-app preview using the same engine that performs the final capture.
- Adjustable render width (CSS px) and export scale (1x/2x/3x).
- "Full page" capture (entire scrollable content, not just one screen) or
  "visible viewport only".
- Saves PNG to `Pictures/Html2Png/` via MediaStore (works on Android 10+
  scoped storage, and legacy storage on older versions).
- 100% offline at capture time — no server, no cloud rendering, no per-export
  network dependency (only the optional online-asset-fetch step needs internet).

## Project structure

```
Html2Png/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/html2png/app/
│       │   ├── App.kt                       Application class
│       │   ├── MainActivity.kt              UI wiring, capture orchestration
│       │   ├── server/LocalSiteServer.kt    Embedded HTTP server (NanoHTTPD)
│       │   ├── importer/SiteImporter.kt     Unzips / stages uploaded files
│       │   ├── importer/FontAssetPrefetcher.kt  Downloads remote fonts/images
│       │   └── capture/
│       │       ├── WebViewCapture.kt        PixelCopy + software-draw capture
│       │       └── PngSaver.kt              MediaStore-safe PNG saving
│       ├── res/                             Layout, theme, icons
│       └── assets/sample/sample.html        Test file (fonts/emoji/gradients)
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Requirements to build

- **Android Studio** (Koala/2024.1 or newer recommended) — this is the
  standard, free IDE for Android, available for Windows/Mac/Linux:
  https://developer.android.com/studio
- A computer (the build toolchain cannot run on the phone itself).
- An Android phone with **Android 8.0 (API 26) or newer**, OR the Android
  Studio emulator if you just want to test on the computer first.

## Build & install steps

1. **Download/clone this project folder** (`Html2Png/`) onto your computer.
2. Open **Android Studio** → `File > Open` → select the `Html2Png` folder.
3. Let Gradle sync (first sync downloads dependencies — needs internet on the
   computer, one time only; the *app itself* runs offline on your phone after
   that).
4. Plug your Android phone in via USB:
   - On the phone: enable Developer Options (Settings → About phone → tap
     "Build number" 7 times), then enable "USB debugging" under Developer
     Options.
   - Accept the "Allow USB debugging?" prompt on the phone.
5. In Android Studio, select your phone from the device dropdown (top toolbar),
   then click the green ▶ Run button.
6. The app installs and launches on your phone automatically.

Alternative — build an installable APK without staying connected to Android
Studio:
- `Build > Build Bundle(s) / APK(s) > Build APK(s)` in Android Studio.
- It produces `app/build/outputs/apk/debug/app-debug.apk`.
- Copy that APK to your phone (e.g. via USB file transfer, or email/Drive to
  yourself) and tap it on the phone to install (you'll need to allow "install
  from unknown sources" for your file manager/browser the first time).

## Using the app

1. Open the app — a sample card (web font + emoji + gradient + shadow) is
   bundled so you can test the pipeline immediately; tap **Pick HTML** or
   **Pick ZIP** to load your own content.
   - **Single HTML file**: any `<img src="https://...">` or remote
     `@font-face` will work live if you're online, or you can use step 2.
   - **ZIP bundle**: zip your `index.html` together with a `css/`, `js/`,
     `images/`, `fonts/` folder structure exactly as you'd deploy it to a
     real web server — relative paths resolve identically.
2. *(Optional, needs internet)* Tap **Fetch fonts & images online** to pull
   down any remote Google Fonts / image URLs and bake them into the local
   bundle for fully offline, reliable rendering afterward.
3. Check the **live preview** — this is the exact engine used for capture, so
   what you see is what you'll get.
4. Set your **render width** (CSS px — e.g. 412 for a phone-width screenshot,
   1200 for a desktop-width screenshot) and **export scale** (2x/3x for sharp,
   high-DPI output).
5. Choose **Full page** (captures the entire scrollable content height) or
   **Visible viewport only**.
6. Tap **Export PNG**. The file saves to `Pictures/Html2Png/` on your phone,
   visible in your Gallery/Photos app and any file manager.

## Notes & honesty about limitations

- **NanoHTTPD API**: this project uses `nanohttpd:2.3.1`'s
  `start(timeout, daemon)` method and `SOCKET_READ_TIMEOUT` constant. This is
  a long-stable, unchanged library, but I was not able to verify the exact
  method signature against a live Maven repository while building this (no
  network access in my build environment). If Gradle reports an unresolved
  reference on that line in `LocalSiteServer.kt`, replace
  `server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)` with the
  no-arg `server.start()` — functionally equivalent for this use case.
- **JavaScript-driven content**: if your HTML uses JS to fetch/render content
  asynchronously, the 120ms settle delay before capture may need to be
  increased (in `MainActivity.kt`, `awaitNextFrame()`) for slower scripts —
  e.g. chart libraries or web fonts loading via the Font Loading API.
- **Custom local fonts**: drop `.ttf`/`.woff2` files into your zip and
  reference them via `@font-face { src: url('fonts/MyFont.woff2'); }` with a
  normal relative path — the local server serves them with correct MIME
  types automatically.
- **Why not Puppeteer/headless Chrome?** That approach needs a desktop-class
  Chromium binary and process model that doesn't run on Android/iOS — this
  app instead uses Android's built-in WebView (same Blink/Chromium rendering
  core) directly on-device, which is the on-phone equivalent.
- I have not been able to compile this project myself (no Android SDK/network
  access in my environment), so please treat first build as a "verify and
  report back" step — happy to fix anything Android Studio flags.

## Customizing

- Change default render width: edit the `android:text="412"` value in
  `activity_main.xml` (`inputWidth` field).
- Change PNG save folder name: edit `PngSaver.kt`'s
  `Environment.DIRECTORY_PICTURES + "/Html2Png"` line.
- Add JPEG/WebP export: `PngSaver.kt`'s `bitmap.compress(...)` call accepts
  `Bitmap.CompressFormat.JPEG` or `.WEBP_LOSSLESS` as alternatives.
