# Implementation references

Read/reviewed during preparation. These are technical references, not proof of a successful Android build or device test.

- Upstream repository: https://github.com/Eng-Harraz/snapclear
- User fork: https://github.com/esshoo/3Esnap
- Retrieved source tree identifier (a **Git tree SHA, not a commit SHA**): `5bd31e1910d6f35570c46aebb98ad4786fa38c6c`.
- yt-dlp Android wrapper API pinned to0.18.1: https://github.com/yausername/youtubedl-android/blob/0.18.1/library/src/main/java/com/yausername/youtubedl_android/YoutubeDL.kt
- VideoInfo mapper: https://github.com/yausername/youtubedl-android/blob/0.18.1/library/src/main/java/com/yausername/youtubedl_android/mapper/VideoInfo.kt
- VideoFormat mapper: https://github.com/yausername/youtubedl-android/blob/0.18.1/library/src/main/java/com/yausername/youtubedl_android/mapper/VideoFormat.kt
- WebView local content / WebViewAssetLoader: https://developer.android.com/develop/ui/views/layout/webapps/load-local-content
- WebView native bridge security: https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges
- MediaStore shared media: https://developer.android.com/training/data-storage/shared/media
- Secure file sharing: https://developer.android.com/training/secure-file-sharing/share-file
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android Gradle Plugin8.9 compatibility: https://developer.android.com/build/releases/agp-8-9-0-release-notes
- APK ABI outputs: https://developer.android.com/build/configure-apk-splits
- Android app signing: https://developer.android.com/studio/publish/app-signing
- GitHub Actions artifacts: https://docs.github.com/en/actions/using-workflows/storing-workflow-data-as-artifacts

The engine version is deliberately not upgraded speculatively. The in-app stable engine updater is explicit and user-initiated. Platform download behavior changes independently of the Java/HTML UI.
