# Jenix Stream — Native Android APK
## Complete Build Guide

**App:** Jenix Stream  
**Package:** com.jenix.stream  
**Version:** 1.0.0 (Build 100)  
**Developer:** Manoj Jain  

---

## ✅ WHAT THIS APK DOES (No PC needed!)

- ✅ Streams RTSP camera → YouTube Live (native FFmpegKit)
- ✅ Streams RTSP camera → Facebook Live simultaneously
- ✅ ONVIF camera discovery (UDP multicast + port scan)
- ✅ RTSP stream probe (codec, resolution, fps detection)
- ✅ YouTube compatibility checker with fix suggestions
- ✅ Schedule auto-start/stop (survives phone reboot)
- ✅ Background streaming (phone screen can be off)
- ✅ Email login + profile (name, mobile, city)
- ✅ Settings saved permanently on device
- ✅ Export/import settings
- ✅ Live stats (duration, kbps, fps)
- ✅ Stream log

---

## 📱 REQUIREMENTS

| Item | Requirement |
|------|-------------|
| Android version | 8.0 (Oreo) or higher |
| RAM | Minimum 2GB (3GB+ recommended) |
| Network | Same WiFi as IP camera |
| APK size | ~60-80MB (FFmpegKit included) |
| Play Store | Ready for submission |

---

## 🛠 BUILD STEPS

### STEP 1: Install Android Studio
Download from: https://developer.android.com/studio
- Version: Hedgehog (2023.1.1) or newer
- During install: check "Android SDK" and "Android Virtual Device"

### STEP 2: Open Project
1. Launch Android Studio
2. Click "Open" (NOT "New Project")
3. Navigate to this `JenixStream` folder
4. Click "OK" and wait for Gradle sync (~5-10 minutes first time)

### STEP 3: Fix local.properties
Edit `local.properties` in the root folder:
```
sdk.dir=C\:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
```
Replace `YOUR_USERNAME` with your Windows username.

### STEP 4: Wait for Gradle Sync
- Android Studio will download all dependencies automatically
- FFmpegKit (~50MB) will download from Maven
- This takes 5-15 minutes on first build
- Watch "Build" tab at bottom for progress

### STEP 5: Build Debug APK
**Menu → Build → Build Bundle(s)/APK(s) → Build APK(s)**

OR press: `Ctrl + F9`

APK location:
```
JenixStream\app\build\outputs\apk\debug\app-debug.apk
```

### STEP 6: Install on Phone
Option A — USB:
- Enable Developer Options on phone (Settings → About → tap Build Number 7 times)
- Enable USB Debugging
- Connect phone → Android Studio → Run button ▶

Option B — WhatsApp/Copy:
- Copy `app-debug.apk` to phone
- On phone: Settings → Install Unknown Apps → allow your browser/file manager
- Tap the APK file to install

---

## 🏪 PLAY STORE RELEASE BUILD

### Step 1: Generate Signing Key (once only)
```
Menu → Build → Generate Signed Bundle/APK
→ APK → Next
→ "Create new..." keystore
→ Fill in your details:
   Key store path: C:\Keys\jenix-release.jks
   Password: (choose strong password)
   Key alias: jenix
   Validity: 25 years
   Your name and organization details
→ Next → Release → Finish
```

### Step 2: Upload to Play Console
1. Go to https://play.google.com/console
2. Sign in as manoj020218@gmail.com  
3. Create app → "Jenix Stream"
4. Package name: com.jenix.stream
5. Upload the signed APK
6. Fill store listing:
   - Short description: "Stream your RTSP cameras to YouTube & Facebook Live"
   - Full description: (see below)
   - Category: Tools
   - Screenshots: take from your phone
7. Content rating: fill questionnaire
8. Privacy policy URL: (host the legal text from the app)
9. Submit for review (3-7 days)

### Play Store Description (ready to paste):
```
Jenix Stream - Professional RTSP Live Streaming

Stream your IP security cameras directly to YouTube Live and 
Facebook Live — no PC required!

FEATURES:
• RTSP → YouTube Live streaming (built-in FFmpeg)
• Stream to YouTube + Facebook simultaneously  
• Auto-discover cameras on your WiFi (ONVIF)
• Stream probe - check compatibility before going live
• Schedule automatic start/stop times
• Background streaming (screen can be off)
• Zero CPU mode - stream H.264 cameras with no re-encoding
• Hardware acceleration support

SUPPORTED CAMERAS:
• Any ONVIF-compatible IP camera
• Hikvision, Dahua, Axis, Reolink, Foscam and more
• Any camera with RTSP output

PRIVACY:
• No data collected
• No internet account required
• All settings stored locally on your device only

FREE - No ads, no subscriptions, no limits.

By Jenix / Manoj Jain
```

---

## 📁 PROJECT STRUCTURE

```
JenixStream/
├── app/src/main/
│   ├── AndroidManifest.xml          ← Permissions & services
│   └── java/com/jenix/stream/
│       ├── MainActivity.kt          ← App entry + navigation
│       ├── data/
│       │   ├── model/Models.kt      ← Data classes
│       │   ├── preferences/         ← DataStore settings
│       │   └── repository/          ← Room database (schedules)
│       ├── service/
│       │   └── StreamingService.kt  ← FFmpegKit background service ⭐
│       ├── onvif/
│       │   └── OnvifDiscovery.kt    ← UDP multicast + port scan ⭐
│       ├── scheduler/
│       │   └── StreamScheduler.kt  ← AlarmManager scheduling ⭐
│       ├── viewmodel/
│       │   └── StreamViewModel.kt  ← Central state management ⭐
│       └── ui/
│           ├── theme/Theme.kt       ← Jenix dark theme
│           ├── components/          ← Reusable Compose widgets
│           └── screens/Screens.kt  ← All 6 screens ⭐
```

---

## 🔧 COMMON BUILD ISSUES

**"SDK not found"**
→ Edit `local.properties`, set correct `sdk.dir` path

**"Gradle sync failed"**
→ Check internet connection (downloads dependencies)
→ File → Invalidate Caches → Restart

**"FFmpegKit not found"**
→ Check `settings.gradle.kts` has mavenCentral()
→ Gradle sync again

**"minSdk mismatch"**
→ Ensure phone runs Android 8.0+
→ `minSdk = 26` in build.gradle.kts

**Build succeeds but app crashes on start**
→ Check Logcat in Android Studio (bottom panel)
→ Most likely permission issue - grant in phone settings

---

## 📊 HOW STREAMING WORKS (No PC!)

```
[IP Camera RTSP Stream]
         ↓ WiFi
[Android Phone]
  FFmpegKit native library
    - Decodes RTSP
    - Re-encodes if needed (or copies H.264 directly)
    - Pushes RTMP
         ↓ Internet
[YouTube Live / Facebook Live]
```

FFmpegKit is a full native FFmpeg compiled for ARM Android.
It runs in a background foreground service, so streaming
continues even when app is minimized or screen is off.

---

## 🔄 VERSION UPDATES

To release an update:
1. Increment `versionCode` and `versionName` in `app/build.gradle.kts`
2. Update `AppConstants.APP_VERSION` in `Models.kt`
3. Build new signed APK/Bundle
4. Upload to Play Console → Production → Release

Version convention:
- `1.0.x` — Bug fixes
- `1.x.0` — New features  
- `x.0.0` — Major changes

---

**Developer:** Manoj Jain  
**Email:** manoj020218@gmail.com  
**Brand:** Jenix  
