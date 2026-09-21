# Ugh — Android app

A standalone Android app that wraps **https://ugh.xo.je** in a fully-featured, hardened
WebView "custom browser". No browser chrome, no tabs, no address bar — it launches
straight into the site like a native app.

| | |
|---|---|
| App name | Ugh |
| Application ID | `com.bella.ugh` |
| Version | 1.5.7 (versionCode 10507) |
| Developer | Danica Lian |
| Start URL | https://ugh.xo.je |
| Min / target SDK | 24 (Android 7.0) / 35 (Android 15) |
| Language | Kotlin |
| Build | Gradle 8.11.1 · AGP 8.7.3 · JDK 17 |

---

## 1. Build it

### Android Studio (easiest)

1. **File → Open** and pick this folder (the one containing `settings.gradle.kts`).
2. Let Gradle sync (it downloads Gradle 8.11.1 + the Android SDK bits on first run).
3. Press **Run ▶** with a device/emulator attached, or **Build → Build APK(s)**.

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. It is signed with
the debug key, so it installs on any device — no Play Store needed.

### Command line

```bash
chmod +x gradlew          # once, on macOS/Linux
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # app/build/outputs/apk/release/app-release-unsigned.apk
```

### GitHub Actions (no local toolchain at all)

`.github/workflows/build.yml` builds both APKs on every push and on manual dispatch.
Push this repo to GitHub, open the **Actions** tab, run *Build APK*, and download the
`ugh-apks` artifact. That artifact contains an installable debug APK.

### No PC? Build in the cloud from your phone

Apktool M cannot build this project — it only rebuilds *already-compiled* APKs (smali
projects), and this is a Gradle/Kotlin source project. The equivalent one-tap route is
to let GitHub's servers run the build:

1. Create a (public) repository on github.com — public repos get unlimited free build
   minutes.
2. **Add file → Create new file**, name it `.github/workflows/build.yml`, and paste the
   ready-made workflow file supplied with this project (it downloads the project zip and
   builds it).
3. Commit, open the **Actions** tab, tap *Build Ugh APK → Run workflow*.
4. ~5 minutes later the APK is attached to a release (and as a run artifact) — download
   it straight to the phone and install it.

`NO-PC-BUILD.md` in this repo has the same instructions plus the workflow text.

To let CI also produce a **signed release APK**, add these four repository secrets
(*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 your-upload-key.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

When `KEYSTORE_BASE64` exists, the workflow writes `keystore.properties` and Gradle
signs the release build automatically. Without it you get `app-release-unsigned.apk`,
which you can sign with `apksigner` or Android Studio's *Generate Signed Bundle / APK*.

> **Updating an existing Play Store listing:** Android only accepts updates signed with
> a key belonging to the same signing lineage as the currently published build. If
> `com.bella.ugh` is already live, use that app's original upload key — either the
> `.jks` you published with, or Play App Signing's upload key. A fresh key will be
> rejected. Also make sure `versionCode` in `app/build.gradle.kts` is higher than the
> one currently on the store.

### Local release signing

Create `keystore.properties` in the repo root (it is git-ignored):

```properties
storeFile=/absolute/path/to/upload-key.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`app/build.gradle.kts` picks it up automatically; delete the file to build unsigned.

---

## 2. What the app does

**Browsing**

- Full-screen WebView, no URL bar, edge-to-edge layout that respects status bar,
  navigation bar, display cutout and the on-screen keyboard.
- JavaScript, DOM storage, localStorage, IndexedDB, third-party cookies (all required
  for sites behind Cloudflare's JS challenge, which `ugh.xo.je` is).
- Modern Chrome-like User-Agent with the WebView `wv` token stripped, because
  Cloudflare's bot detection often flags the plain WebView UA.
- Pinch-to-zoom, swipe-to-refresh (only when the page is scrolled to the top),
  thin loading progress bar.
- Links that point off-site open in the system browser / a Chrome Custom Tab;
  `tel:`, `mailto:`, `sms:`, `intent:` and `market:` links are handed to the OS.
- Clean in-app navigation: back goes through the WebView history, then double-press
  back to exit.
- App Links: tapping a `https://ugh.xo.je/...` link elsewhere opens inside the app.
  (Auto-verification is off — enabling it requires hosting an `assetlinks.json` on the
  domain; see `android:autoVerify` in `AndroidManifest.xml`.)

**Web-platform features**

- File uploads: gallery/document picker plus a camera shortcut for `image/*` inputs
  (via `FileProvider`).
- `getUserMedia` camera + microphone with runtime permission requests.
- Geolocation with runtime permission requests.
- Fullscreen HTML5 video.
- Downloads via `DownloadManager` (with the page's cookies attached), saved to the
  public Downloads folder with a completion notification.

**Robustness**

- Native offline screen with a Retry button that auto-retries the moment connectivity
  comes back.
- WebView renderer-crash recovery: the WebView is rebuilt instead of killing the app.
- WebView state is saved/restored, the activity survives rotation, dark mode and font
  scale changes without reloading.
- Adaptive dark/light theming, plus "Desktop site" toggle for pages that misbehave.

**Overflow menu** (small translucent button, top-right)

Refresh · Home · Share page · Open in browser · Desktop site (persisted) ·
Clear cookies & cache · About

**JS bridge** — the page can call into the app via `window.Android`:

```js
Android.platform()        // "android"
Android.versionName()     // "1.5.7"
Android.sdkInt()          // e.g. 34
Android.toast("hi")
Android.vibrate(40)       // 1–2000 ms
Android.share("text")
Android.openExternal(url) // opens in the system browser
Android.reload()
```

Every method except the read-only ones refuses to run unless the current page is on an
internal host, so third-party content loaded in the WebView can't drive the app.

---

## 3. Configuration — `Config.kt`

All site-specific knobs live in `app/src/main/java/com/bella/ugh/Config.kt`:

| Constant | Meaning |
|---|---|
| `START_URL` | page loaded on launch |
| `PRIMARY_HOST` | host(s) kept inside the app |
| `EXTRA_HOSTS` | extra in-app hosts (e.g. an auth domain or CDN-backed subdomain) |
| `INCLUDE_SUBDOMAINS` | treat every subdomain of the listed hosts as internal |
| `STRIP_WEBVIEW_UA_TOKEN` | remove `; wv` from the User-Agent (helps with Cloudflare) |
| `SWIPE_TO_REFRESH` | enable pull-to-refresh |
| `OPEN_EXTERNAL_LINKS_IN_CUSTOM_TAB` | external links in a Custom Tab (true) vs the browser app (false) |
| `DOUBLE_BACK_TO_EXIT` | require a double back-press to leave the app |
| `KEEP_SCREEN_ON_WHILE_LOADING` | don't dim the screen mid-load |

Anything not on an internal host is treated as external and opened in the browser.

To change the app name, edit `app_name` in `res/values/strings.xml`. To change the
package/application ID, edit `namespace` **and** `applicationId` in
`app/build.gradle.kts` plus the package folder under `src/main/java/` — or use Android
Studio's *Refactor → Rename* on the package.

---

## 4. Project layout

```
app/src/main/
├── AndroidManifest.xml            permissions, deep links, FileProvider
├── java/com/bella/ugh/
│   ├── MainActivity.kt            WebView host: chrome client, permissions, menus
│   ├── WebAppBridge.kt            window.Android JavaScript interface
│   ├── Config.kt                  site/behaviour configuration
│   └── DownloadHelper.kt          DownloadManager integration
└── res/
    ├── layout/activity_main.xml   SwipeRefreshLayout + WebView + offline screen
    ├── layout/dialog_about.xml
    ├── menu/main_menu.xml
    ├── drawable/                  ic_logo, ic_launcher_foreground/background, vectors
    ├── mipmap-*/                  legacy launcher icons (48→192 px)
    ├── mipmap-anydpi-v26/         adaptive launcher icons
    ├── values/ strings, colors, themes (+ values-night)
    └── xml/                       file paths, network security, backup rules
```

**Icon:** the launcher icon is a vector reconstruction of the Ugh mark (white U on the
`#FE8B3F → #EE3216` gradient squircle), so it stays crisp at any size. Adaptive icons
(`mipmap-anydpi-v26`) use the gradient as background and the U as foreground; legacy
`mipmap-*dpi` PNGs are pre-rendered squircles. To swap in new artwork, replace
`drawable/ic_launcher_background.xml` / `ic_launcher_foreground.xml` and regenerate the
PNG mipmaps (Android Studio: *New → Image Asset → Launcher Icons*).

---

## 5. Notes

- `usesCleartextTraffic` is **false**; the app only allows HTTPS.
- The debug build installs side by side with the release build (application ID
  `com.bella.ugh.debug`), so their cookies/storage are independent.
- The WebView is debuggable via `chrome://inspect` in debug builds only.
- Only the permissions the wrapper can actually need are declared: internet, network
  state, notifications, vibrate, camera, microphone, location, plus storage/media access
  for downloads and file picking. Remove any you don't want from `AndroidManifest.xml`.
  Note that web `<input type="file">` uploads go through the system picker, which grants
  access per-file and needs no permission at all — the `READ_MEDIA_*` entries are there
  only for completeness, and Google Play will ask you to justify them if you keep them.
- Web platform limits that matter for a social app: WebView cannot receive **web push
  notifications** (that needs native FCM + a server), cannot use **WebAuthn/passkeys**,
  and audio/video playback pauses when the app goes to the background (background
  playback needs a foreground service).
- There is no analytics, no ads and no network calls other than the site itself.
