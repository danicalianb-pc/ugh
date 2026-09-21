# Building the APK without a PC

You do **not** need Android Studio, a laptop, or Apktool M. GitHub will compile the
project on its own servers and hand you an installable APK.

(Apktool M can't help here: it rebuilds *already-compiled* APKs from smali, while this is
a Gradle + Kotlin source project that needs a real compiler.)

## Steps (all doable from the phone browser or the GitHub app)

1. Sign in to github.com and create a new **public** repository, e.g. `ugh-app`.
   Public repositories get unlimited free GitHub Actions minutes.
2. In that repo tap **Add file → Create new file**.
3. In the filename box type exactly: `.github/workflows/build.yml`
   (typing the `/` characters creates the folders.)
4. Paste the workflow below, replacing `PROJECT_ZIP` with the URL of the project zip you
   were given, then **Commit changes**.
5. Go to the **Actions** tab → *Build Ugh APK* → **Run workflow**.
6. Wait ~5 minutes. The finished APK appears two ways:
   - **Releases** (right-hand sidebar of the repo) → `Ugh APK build N` → tap the `.apk`
     to download it, then tap it in your notifications to install it. You may need to
     allow "install unknown apps" for your browser the first time.
   - Or *Actions → the finished run → Artifacts → ugh-debug-apk* (a zip containing the
     APK).

Re-running the workflow always rebuilds from the current project zip, so ask for a fresh
zip after any change.

## Workflow

```yaml
name: Build Ugh APK

on:
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    env:
      PROJECT_ZIP: "PASTE_THE_PROJECT_ZIP_URL_HERE"
    steps:
      - name: Fetch project source
        run: |
          curl -L --fail -o project.zip "$PROJECT_ZIP"
          unzip -q project.zip

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          build-root-directory: ugh-android

      - name: Build debug APK
        working-directory: ugh-android
        run: |
          chmod +x ./gradlew
          ./gradlew --no-daemon assembleDebug

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: ugh-debug-apk
          path: ugh-android/app/build/outputs/apk/debug/*.apk
          if-no-files-found: error

      - name: Attach APK to a release
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "apk-${{ github.run_number }}" \
            "ugh-android/app/build/outputs/apk/debug/app-debug.apk" \
            -R "${{ github.repository }}" \
            -t "Ugh APK build ${{ github.run_number }}" \
            -n "Debug APK built automatically from source."
```

The debug APK is signed with the standard debug key, so it installs on any device. It is
fine for testing and sideloading, but it is **not** something you can upload to Google
Play — for that you need a release build signed with your own keystore (see the signing
section of `README.md`).
