package com.snapclean.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SharedPreferences prefs;
    private static final String PREF_SERVER_IP = "server_ip";
    // Default fallback: 127.0.0.1 for reverse-forwarding, or LAN IP
    private static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8000";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("SnapCleanPrefs", MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0a0e17"));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Bind On-device Standalone Engine
        NativeBridge bridge = new NativeBridge(this, webView);
        webView.addJavascriptInterface(bridge, "AndroidNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return true;
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // If failed to load remote server, load local embedded page
                showConnectionSettingsDialog();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // Support native downloads via Android DownloadManager
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    
                    // Fallback: If URL contains filename at the end, use it
                    Uri parsedUri = Uri.parse(url);
                    String lastPath = parsedUri.getLastPathSegment();
                    if (lastPath != null && (lastPath.endsWith(".mp4") || lastPath.endsWith(".mp3") || lastPath.endsWith(".m4a"))) {
                        fileName = lastPath;
                    }

                    // If URLUtil guessed .bin, check if it's audio or video from URL or disposition
                    // Sanitize fileName strictly for Android Filesystem (remove illegal characters)
                    fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                    if (fileName.isEmpty()) {
                        fileName = "video_" + System.currentTimeMillis() + ".mp4";
                    }

                    DownloadManager.Request request = new DownloadManager.Request(parsedUri);
                    if (mimeType != null && !mimeType.equals("application/octet-stream")) {
                        request.setMimeType(mimeType);
                    } else {
                        if (fileName.endsWith(".mp3")) {
                            request.setMimeType("audio/mpeg");
                        } else if (fileName.endsWith(".mp4")) {
                            request.setMimeType("video/mp4");
                        }
                    }

                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) request.addRequestHeader("cookie", cookies);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setDescription("جاري تنزيل: " + fileName);
                    request.setTitle(fileName);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(getApplicationContext(), "بدأ التنزيل: " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), "فشل التنزيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        String targetUrl = "file:///android_asset/www/index.html";

        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText == null) {
                CharSequence extraSubject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                if (extraSubject != null) sharedText = extraSubject.toString();
            }
            if (sharedText != null) {
                String extractedUrl = extractUrl(sharedText);
                if (extractedUrl != null) {
                    if (webView.getUrl() != null && webView.getUrl().contains("android_asset")) {
                        // Page is already open, directly invoke javascript
                        webView.evaluateJavascript("window.handleExternalUrl && window.handleExternalUrl('" + extractedUrl + "');", null);
                        return;
                    }
                    targetUrl += "?shared_url=" + Uri.encode(extractedUrl);
                }
            }
        }

        webView.loadUrl(targetUrl);
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+)");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private void showConnectionSettingsDialog() {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("الاتصال بالخادم");
            builder.setMessage("تعذر الاتصال بـ " + prefs.getString(PREF_SERVER_IP, DEFAULT_SERVER_URL) + "\n\nتأكد أن السيرفر يعمل على الكمبيوتر، أو أدخل عنوان IP الكمبيوتر (مثال: http://192.168.1.26:8000):");

            final EditText input = new EditText(this);
            input.setText(prefs.getString(PREF_SERVER_IP, "http://192.168.1.26:8000"));
            LinearLayout container = new LinearLayout(this);
            container.setPadding(40, 20, 40, 20);
            container.addView(input);
            builder.setView(container);

            builder.setPositiveButton("اتصال", (dialog, which) -> {
                String newUrl = input.getText().toString().trim();
                if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                    newUrl = "http://" + newUrl;
                }
                prefs.edit().putString(PREF_SERVER_IP, newUrl).apply();
                webView.loadUrl(newUrl);
            });

            builder.setNegativeButton("إعادة المحاولة", (dialog, which) -> {
                handleIncomingIntent(getIntent());
            });

            builder.setCancelable(false);
            builder.show();
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (webView.canGoBack()) {
                    webView.goBack();
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
