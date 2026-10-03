# Zoha&Behi

An Android app (Kotlin) for a private shared photo album. Pictures are stored in a
**Backblaze B2** bucket. You can browse albums, view pictures full-screen, upload new
pictures, delete them, comment on them and reorder both albums and pictures.

The title bar shows the app name followed by a smaller version label, e.g. `Zoha&Behi ver.1.5`.

## Features

- Album list with a **cover picture** (the first picture of the album), album name in large
  type, **date (Jalali) and location** below it, and the photo count. The text sits on a dark
  gradient with bold white text so it stays readable over any picture
- **New album** dialog with name, date (Gregorian date picker, shown as Jalali in the app) and
  optional location. A new album is placed at the **top** of the list
- **Edit album** - change name, date and location (select one album -> Edit)
- Picture list with thumbnails (oldest first until you reorder)
- Full-screen picture viewer with delete button
- **Add pictures** - FAB, pick one or many from the device (uploaded to B2)
- **Delete** - long-press to multi-select, then delete. Deleting an album deletes all of
  its pictures
- **Reorder albums and pictures** - menu -> Reorder, drag with the handle (or long-press),
  then Save. The order is shared by every phone using the same bucket. The first picture
  in the order becomes the album cover
- **Comments on pictures** - a "Comments (n)" bar at the bottom of the viewer opens a
  sheet where you can add comments. Each comment shows the author, a Jalali date and time.
  Long-press a comment to delete it. Each phone has its own author name (asked on the first
  comment, changeable from the viewer menu -> Change my name)
- **Reliable uploads on mobile data** - longer network timeouts and up to 3 attempts per file
- Pull-to-refresh, dark mode (DayNight theme), purple theme
- Version shown in the title, read from `versionName` in `app/build.gradle`

## Requirements

- minSdk **21** (Android 5.0+), targetSdk 30, compileSdk 30
- A Backblaze B2 account with one **private** bucket
- Internet access on the device

## Backblaze B2 setup (one time)

1. Create a **private** bucket in the Backblaze console (B2 Cloud Storage -> Buckets).
2. Create an **Application Key** (App Keys -> Add a New Application Key):
   - Restrict it to your bucket
   - Type of access: **Read and Write**
   - The key needs these capabilities: `listFiles`, `readFiles`, `writeFiles`,
     `deleteFiles`
3. Copy the **keyID** and the **applicationKey** (the secret is shown only once).
4. Put the key ID, application key and bucket name in `B2Config.kt`, then build the app.

> The credentials are compiled into the APK. Only share the APK with people you trust,
> and use a key restricted to this one bucket. If the key leaks, delete it in the
> Backblaze console and create a new one.

## How data is stored in B2

B2 has no real folders, so the app uses name prefixes:

| What | Stored as |
|---|---|
| Album | A prefix such as `Trip/` |
| Picture | `Trip/<timestamp>_<original name>` (the timestamp avoids name clashes between phones) |
| Empty album marker | `Trip/.bzEmpty` - also holds the album **date** (`yyyy-MM-dd`) and **location** as file info |
| Picture comments | `Trip/.comments/<timestamp>_<original name>.json` (JSON list of comments with id, author, text, time). The comment count is also saved as file info. Deleted together with the picture or the album |
| Album order | `.order` in the bucket root (JSON list of album prefixes) |
| Picture order | `Trip/.order` (JSON list of picture file IDs) |

Albums or pictures that are not in an `.order` file are shown at the end of the list.
Exception: when an album is created, the app writes the root `.order` file with the new
album first, so new albums appear at the top.

## Building

### Android Studio

Open the `BehiZoha` folder. Studio will use the Gradle wrapper (Gradle 7.6) and JDK 11
(configured via `org.gradle.java.home` in `gradle.properties`). Then **Build -> Build APK**.

### Command line

```powershell
cd "D:\Behnam\Behi&Zoha\BehiZoha"
.\gradlew.bat assembleDebug
# output: app\build\outputs\apk\debug\app-debug.apk
```

Notes for this machine:
- `gradlew.bat` was patched to tolerate `&` in the folder path.
- Build uses the Aliyun Maven mirrors + Maven Central (Google's maven is blocked here).

## Versioning

The version is managed in one place, `app/build.gradle`:

```groovy
versionCode 5        // must increase with every release
versionName '1.5'    // shown in the title as "Zoha&Behi ver.1.5" (smaller text)
```

## App icon

Convert the `.ico` file to a PNG (512x512 or larger), then in Android Studio use
**New -> Image Asset** (Launcher Icons) to generate the `mipmap-*` files. The manifest
uses `@mipmap/ic_launcher`.

## Usage

1. Launch the app. The album list loads from your bucket.
2. Menu -> **New album** to create an album (name, date, location). It appears at the top.
3. Tap an album to see its pictures; tap a picture to view it full-screen.
4. Tap the **+** button to upload pictures.
   In the full-screen viewer, tap the **Comments** bar to read and add comments.
5. Long-press items to multi-select and delete. Select one album and tap **Edit** to change
   its name, date or location. In the comments sheet, long-press a comment to delete it.
   Menu -> **Change my name** changes the author name used on this phone.
6. Menu -> **Reorder** to change the order of albums (album list) or pictures (inside an
   album). Drag, then **Save**. **Cancel** or Back discards the changes.

## Project structure

```
app/src/main/java/com/behi/zoha/
├── SignInActivity.kt         # launcher screen, continues to the album list
├── FolderActivity.kt         # album list, new/edit album dialogs, album reorder mode
├── FolderAdapter.kt          # album cards (cover, name, date/location, count, drag handle)
├── ImageListActivity.kt      # picture list, upload, multi-select delete, picture reorder
├── ImageAdapter.kt
├── ViewerActivity.kt         # full-screen viewer + delete + comments sheet
├── AuthImageLoader.kt        # Glide loading with the B2 authorization header
├── JalaliDate.kt             # Gregorian <-> Jalali conversion and formatting
├── JalaliDatePickerDialog.kt # Jalali date picker (currently unused; dialogs use the Gregorian picker)
├── Ui.kt                     # dialogs/toasts helpers
└── drive/
    ├── B2Config.kt           # bucket credentials (do not publish)
    ├── DriveApi.kt           # Retrofit interface + models + shared OkHttp client (timeouts)
    ├── DriveRepo.kt          # albums, pictures, upload (with retry), delete, order files, comments
    └── TokenManager.kt       # B2 authorization/session cache
```

## Notes & limits

- Lists load up to 5 000 files per album; album covers and counts load per visible row.
- Thumbnails are loaded by downloading the full picture (Glide caches it locally).
- Each upload reads the whole picture into memory, so very large files may be slow.
- Network timeouts: connect 30 s, read 120 s, write 300 s, no overall call limit.
  Each file is tried up to 3 times (1 s and 2 s pause between attempts) on network errors.
- The B2 authorization token expires after 24 hours; the app re-authorizes automatically
  when a request returns HTTP 401.
- Deleting an album permanently removes every file under its prefix.
- Comments are stored in one JSON file per picture. If two phones post a comment at exactly
  the same moment, one of them may be lost (B2 has no locking).
- Comment operations fail with a message (and change nothing) when the network is down.

## Building Release (Signed)

A release keystore is configured in `keystore.properties` (gitignored).

```powershell
# Build signed APK
.\gradlew.bat assembleRelease
# output: app\build\outputs\apk\release\app-release.apk

# Build signed AAB (for Play Store)
.\gradlew.bat bundleRelease
# output: app\build\outputs\bundle\release\app-release.aab
```

**First-time setup** (already done):
1. Generated `release.keystore` (2048-bit RSA, valid 10,000 days)
2. Created `keystore.properties` with credentials
3. Configured `signingConfigs.release` in `app/build.gradle`

**Credentials** (keep secure, do not commit):
```
storeFile=release.keystore
storePassword=<your-store-password>
keyAlias=zoha-release
keyPassword=<your-key-password>
```

## Changelog

- **1.5** - New albums are added at the top of the list, smaller version label in the title
  (`Zoha&Behi ver.x.y`), longer network timeouts and upload retry (up to 3 attempts) for
  unstable mobile data, more readable album card text (darker gradient, bold white text),
  pin icon (📌) for the album location, new B2 key and security fixes
- **1.4** - Gregorian calendar selection for album dates, new B2 key and security fixes,
  new logo
- **1.3** - Comments rewrite: visible comments bar in the viewer, author name per phone,
  comments keyed per picture (no clash between same-named pictures), delete by id, comments
  removed together with their picture, no crash or data loss on network errors, Jalali date
  on comments, providing edit album date and location ability
- **1.2** - Image comments: add/view/delete comments on photos (stored in B2)
- **1.1** - Jalali calendar added, provide rename album capability,
  back button deselects all when albums are selected, new release keystore
- **1.0** - Backblaze B2 storage, albums with date/location, cover from the first picture,
  album and picture reordering, themed dialogs, version in the title, custom app icon.