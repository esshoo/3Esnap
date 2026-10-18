# SnapClean - Clean, Fast & Ad-Free Media Downloader

<p align="center">
  <img src="app/src/main/res/drawable/ic_download_icon.xml" width="96" height="96" alt="SnapClean Logo" />
</p>

<p align="center">
  <strong>A standalone, open-source Android media downloader built for privacy and performance.</strong>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#supported-platforms">Platforms</a> •
  <a href="#installation">Installation</a> •
  <a href="#building-from-source">Building</a> •
  <a href="#disclaimer">Disclaimer</a> •
  <a href="#license">License</a>
</p>

---

## 🌟 Features

- **100% Standalone On-Device Engine**: Runs entirely on the Android device using native embedded binaries (`youtubedl-android` + `FFmpeg`). No active computer or external server required!
- **Ad-Free & Tracker-Free**: Clean, modern dark-mode user interface without annoying ads, pop-ups, or analytics.
- **No Account Required**: Does not require user registration or logins.
- **Android Share Sheet Integration**: Share any video link directly from the YouTube, Facebook, TikTok, or Instagram app into SnapClean for instant downloading.
- **Direct Public Storage**: Automatically saves media to your phone's public `/sdcard/Download` folder and registers them with the Android **MediaStore** gallery so they appear immediately in your files and photos apps.
- **Multiple Formats**: Download as **MP4 video** (original / 1080p / 720p / 480p) or extract clean **MP3 audio**.

---

## 📱 Supported Platforms

- **YouTube**: Videos, Shorts, and Audio.
- **Facebook**: Public Videos and Reels.
- **Instagram**: Public Reels and Posts via Direct CDN bypass.
- **TikTok**: Public Videos.

---

## 🚀 Installation

You can download the pre-compiled APK directly from the repository:
1. Navigate to [`release/SnapClean-v1.0.apk`](release/SnapClean-v1.0.apk).
2. Install the APK on your Android device (Android 7.0+ / API 24+ recommended).

---

## 🛠️ Building from Source

### Prerequisites
- JDK 17
- Android SDK (API 34, Build Tools 34.0.0)

### Build Steps
```bash
git clone https://github.com/YOUR_USERNAME/SnapClean.git
cd SnapClean
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## ⚖️ Legal Disclaimer

> [!IMPORTANT]
> **This software is provided for personal backup and educational purposes only.**
>
> 1. This application is an open-source client utility designed to interface with public web content.
> 2. The author does not host, control, or distribute any media files. All downloads are fetched directly from third-party public Content Delivery Networks (CDNs).
> 3. Users are responsible for complying with the Terms of Service of respective platforms and applicable copyright laws in their jurisdictions. Do not use this tool to download copyrighted material without permission.
> 4. All platform names and logos (YouTube, Facebook, Instagram, TikTok) are trademarks of their respective owners and are used here solely for descriptive and identification purposes.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.
