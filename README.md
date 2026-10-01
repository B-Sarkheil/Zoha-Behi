# Behi&Zoha

An Android app (Kotlin) that connects to your **Google Drive** and lets you browse folders,
view pictures full-screen, upload new pictures and delete them.

## Features

- Google Sign-In (your own Google account, full `drive` scope)
- Folder list — each folder shows a **cover thumbnail of its first picture** + photo count
- Open any folder **by pasting its link/ID** (menu → "Open folder by link")
- Image list (ordered by date, oldest first) with thumbnails
- Full-screen picture viewer
- **Add pictures** — FAB, pick one or many from the device (uploads to Drive)
- **Delete pictures** — long-press to select (multi-select) → delete, or delete from the viewer
- Pull-to-refresh, dark mode (DayNight theme)

## Requirements

- minSdk **21** (Android 5.0+), targetSdk 30, compileSdk 30
- Google Play Services on the device (for Sign-In)

## Google Cloud setup (one time, required before the app can sign in)

1. Go to <https://console.cloud.google.com/> and create (or select) a project.
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen**
   - User type: **External**, fill app name (`Behi&Zoha`), contact email.
   - Scopes: add `.../auth/drive` (or skip and let the first sign-in request it).
   - **Publish the app**, then under **Test users** add your Google account address.
     (Without this, Google will block sign-in for "unverified" apps.)
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**
   - Application type: **Android**
   - Package name: `com.behi.zoha`
   - SHA-1 (debug keystore on this PC):
     `2E:8B:EA:56:34:69:5D:24:FD:35:E8:7A:FB:9D:4B:53:B6:B1:7C:F8`
5. Wait a few minutes after saving, then install the app and sign in.

> For a **release** build you must add a second Android OAuth client with the release
> keystore's SHA-1 and the same package name.

## Building

### Android Studio

Open the `BehiZoha` folder. Studio will use the Gradle wrapper (Gradle 7.6) and JDK 11
(configured via `org.gradle.java.home` in `gradle.properties`). Then **Build → Build APK**.

### Command line

```powershell
cd "D:\Behnam\Behi&Zoha\BehiZoha"
.\gradlew.bat assembleDebug
# output: app\build\outputs\apk\debug\app-debug.apk
```

Notes for this machine:
- `gradlew.bat` was patched to tolerate `&` in the folder path.
- Build uses the Aliyun Maven mirrors + Maven Central (Google's maven is blocked here).

## Usage

1. Launch the app → **Sign in with Google** → accept the Drive permission.
2. The first screen lists the folders in *My Drive* — tap one to see its pictures.
   - If your folder is not at the root, use **menu → Open folder by link** and paste
     `https://drive.google.com/drive/folders/<ID>`.
3. Tap a picture to view it full-screen (delete button at the bottom).
4. Tap the **＋** button to upload pictures; long-press list items to multi-select and delete.

## Project structure

```
app/src/main/java/com/behizoha/
├── SignInActivity.kt      # Google sign-in
├── FolderActivity.kt      # folder list with first-picture covers
├── FolderAdapter.kt
├── ImageListActivity.kt   # pictures list, upload, multi-select delete
├── ImageAdapter.kt
├── ViewerActivity.kt      # full-screen viewer + delete
├── AuthImageLoader.kt     # Glide loading with OAuth header
├── Ui.kt                  # dialogs/toasts helpers
└── drive/
    ├── DriveApi.kt         # Retrofit interface (files.list / upload / delete)
    ├── DriveRepo.kt        # queries, pagination, upload, delete
    └── TokenManager.kt     # OAuth token cache
```

## Notes & limits

- The app requests the full `drive` scope so it can read/delete your existing pictures.
- Lists load up to 3 000 pictures per folder; folder covers/counts load per visible row.
- Thumbnails are loaded by downloading the image bytes (Glide caches them locally).
