# WARP Connect

Android app (Kotlin) that connects to Cloudflare WARP over WireGuard.
On first connect it registers a new WARP device through Cloudflare's API and stores the
generated keys/endpoint locally - no config file needed.

## Build without Android Studio
1. Create a GitHub repo and push this folder to it (branch `main`).
2. Open the repo's **Actions** tab -> **Build Debug APK** (it also runs on every push).
3. Download the APK from either:
   - the finished run -> **Artifacts** -> `app-debug` (a zip containing `app-debug.apk`), or
   - **Releases** -> **Latest debug build** -> `app-debug.apk` (direct download link).
4. Copy it to your phone and install (allow "install unknown apps").

## Build locally (optional)
Open the folder in Android Studio (Ladybug or newer) and press Run, or run `gradle assembleDebug`
with Gradle 8.9 and JDK 17.
