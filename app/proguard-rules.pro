# Required if code shrinking is enabled in a later release.
-keepclassmembers class com.snapclean.app.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
