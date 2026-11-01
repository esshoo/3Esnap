package com.snapclean.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends AppCompatActivity {
    private static final String HOME="https://appassets.androidplatform.net/assets/www/index.html";
    private static final int STORAGE_PERMISSION=101, NOTIFICATION_PERMISSION=102, IMPORT_FILES=201, EXPORT_LINKS=202, IMPORT_BACKUP=203;
    private WebView webView;
    private NativeBridge bridge;
    private SnapCleanApp app;
    private FrameLayout root;
    private boolean pageReady, receiverRegistered;
    private String pendingUrl, pendingTab;
    private Runnable permissionAction;
    private final BroadcastReceiver changes=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) { if(bridge!=null) bridge.snapshot(false); }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app=SnapCleanApp.of(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11,18,32));
        webView=new WebView(this);
        webView.setBackgroundColor(Color.rgb(11,18,32));
        root.addView(webView,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets) -> {
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            Insets ime=insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));
            return insets;
        });
        WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(getWindow(),root);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
        WebSettings settings=webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Respect the device-width viewport instead of an oversized desktop layout.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView,false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        bridge=new NativeBridge(this,webView);
        webView.addJavascriptInterface(bridge,"AndroidNative");
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/thumbs/",path -> {
                    if(!path.matches("[0-9]+\\.jpg")) return blocked();
                    try {
                        long id=Long.parseLong(path.substring(0,path.length()-4));
                        File file=app.files.thumbnailFile(id);
                        WebResourceResponse response;
                        if(file.isFile()) response=new WebResourceResponse("image/jpeg",null,new FileInputStream(file));
                        else response=new WebResourceResponse("image/svg+xml","UTF-8",getAssets().open("www/media-placeholder.svg"));
                        response.setResponseHeaders(java.util.Collections.singletonMap("Cache-Control","no-store"));
                        return response;
                    } catch(Exception ex) { return blocked(); }
                }).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                if(isHome(request.getUrl())) return false;
                if(request.isForMainFrame()) openExternal(request.getUrl().toString());
                return true;
            }
            @Override @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView view,String url) {
                if(isHome(Uri.parse(url))) return false;
                openExternal(url);return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                Uri uri=request.getUrl();
                if(request.isForMainFrame() && !isHome(uri)) return blocked();
                WebResourceResponse local=loader.shouldInterceptRequest(uri);
                if(local!=null) return local;
                if("appassets.androidplatform.net".equals(uri.getHost())) return blocked();
                // CSP restricts remote content to HTTPS images only; never remote HTML/scripts.
                if(!"https".equals(uri.getScheme())) return blocked();
                return null;
            }
            @Override public void onPageFinished(WebView view,String url) {
                pageReady=isHome(Uri.parse(url));
                if(pageReady) { bridge.snapshot(false);deliverPending(); }
            }
        });
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                webView.evaluateJavascript("Boolean(window.AppUI && window.AppUI.handleBack())",value -> {
                    if(!"true".equals(value)) finish();
                });
            }
        });
        if(state!=null) { pendingUrl=state.getString("pendingUrl"); pendingTab=state.getString("pendingTab"); }
        else readIntent(getIntent());
        webView.loadUrl(HOME);
    }
    private static boolean isHome(Uri uri) {
        return "https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                && "/assets/www/index.html".equals(uri.getPath()) && (uri.getPort()==-1 || uri.getPort()==443);
    }
    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
    }
    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this,changes,new IntentFilter(SnapCleanApp.ACTION_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered=true;
        if(bridge!=null) bridge.snapshot(false);
    }
    @Override protected void onStop() {
        if(receiverRegistered) { unregisterReceiver(changes);receiverRegistered=false; }
        super.onStop();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent);setIntent(intent);readIntent(intent);deliverPending(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putString("pendingUrl",pendingUrl);out.putString("pendingTab",pendingTab);super.onSaveInstanceState(out);
    }
    private void readIntent(Intent intent) {
        if(intent==null) return;
        if(Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if(text==null) text=intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            try { pendingUrl=DownloadPolicy.extractUrl(text==null ? null : text.toString()); }
            catch(IllegalArgumentException ex) { toast(ex.getMessage()); }
        }
        if("library".equals(intent.getStringExtra("tab"))) pendingTab="library";
    }
    private void deliverPending() {
        if(!pageReady || bridge==null) return;
        if(pendingUrl!=null) { bridge.emit("sharedUrl",Json.object("url",pendingUrl));pendingUrl=null; }
        if(pendingTab!=null) { bridge.emit("tab",Json.object("tab",pendingTab));pendingTab=null; }
    }
    public boolean isPageReady() { return pageReady && !isFinishing() && !isDestroyed(); }
    public void toast(String text) { runOnUiThread(() -> Toast.makeText(this,text,Toast.LENGTH_LONG).show()); }
    public void openExternal(String source) {
        try {
            String valid=DownloadPolicy.normalizeUrl(source);
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(valid)));
        } catch(Exception ex) { toast("تعذر فتح الرابط خارج التطبيق."); }
    }
    public void setLightTheme(boolean light) {
        runOnUiThread(() -> {
            int color=light ? Color.rgb(244,247,251) : Color.rgb(11,18,32);
            root.setBackgroundColor(color);webView.setBackgroundColor(color);
            WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),root);
            bars.setAppearanceLightStatusBars(light);bars.setAppearanceLightNavigationBars(light);
        });
    }
    public void withDownloadPermissions(Runnable action) {
        if(permissionAction!=null) { toast("أكمل طلب الصلاحية الحالي أولاً.");return; }
        if(Build.VERSION.SDK_INT<=28 && ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            permissionAction=action;requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_PERMISSION);return;
        }
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications",true).apply();
            permissionAction=action;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);return;
        }
        action.run();
    }
    @Override public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode!=STORAGE_PERMISSION && requestCode!=NOTIFICATION_PERMISSION) return;
        Runnable action=permissionAction;permissionAction=null;
        if(action==null) return;
        if(requestCode==STORAGE_PERMISSION && (results.length==0 || results[0]!=PackageManager.PERMISSION_GRANTED)) {
            bridge.error("يلزم السماح بحفظ الملفات على Android 7–9 قبل التنزيل.");return;
        }
        // Notifications are optional; denied permission must not block a user-requested download.
        withDownloadPermissions(action);
    }
    public void chooseImports() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","audio/*"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i,IMPORT_FILES); }
        catch(Exception ex) { bridge.error("لم أجد منتقي ملفات على الجهاز."); }
    }
    public void chooseImportBackup() {
        // Separate from media import: a JSON backup contains records, not video bytes.
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(i,IMPORT_BACKUP); }
        catch(Exception ex) { bridge.error("تعذر فتح منتقي ملف النسخة الاحتياطية JSON."); }
    }
    private void restoreBackup(Uri document) {
        app.io.execute(() -> {
            try {
                byte[] data;
                try(InputStream input=getContentResolver().openInputStream(document);
                    ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    if(input==null) throw new IOException("تعذر فتح ملف النسخة الاحتياطية.");
                    byte[] chunk=new byte[8192];int count;
                    while((count=input.read(chunk))!=-1) {
                        if(output.size()+count>BackupRules.MAX_BYTES)
                            throw new IOException("ملف النسخة الاحتياطية أكبر من الحد المسموح (12 MB).");
                        output.write(chunk,0,count);
                    }
                    data=output.toByteArray();
                }
                String json=new String(data,StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
                JSONObject backup=new JSONObject(json);
                if(!"3Esnap".equalsIgnoreCase(backup.optString("app","")))
                    throw new IllegalArgumentException("هذا ليس ملف نسخ احتياطي من 3Esnap.");
                int schema=backup.optInt("schemaVersion",-1);
                if(schema!=1 && schema!=2)
                    throw new IllegalArgumentException("تنسيق النسخة الاحتياطية غير مدعوم (الإصدارات 1 و2 فقط).");
                JSONArray rows=backup.optJSONArray("items");
                if(rows==null) throw new IllegalArgumentException("لا يحتوي الملف على قائمة items صالحة.");
                DownloadStore.RestoreResult result=app.store.restore(rows);
                bridge.snapshot(false);
                bridge.emit("tab",Json.object("tab","library"));
                bridge.message(result.summary()+". ملف JSON لا يحتوي على الفيديوهات نفسها.");
            } catch(Exception ex) {
                bridge.error("تعذر استرجاع النسخة: "+(ex.getMessage()==null?"ملف JSON غير صالح":ex.getMessage()));
            }
        });
    }
    public void chooseExport() {
        String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE,"3Esnap-links-"+date+".json");
        try { startActivityForResult(i,EXPORT_LINKS); }
        catch(Exception ex) { bridge.error("تعذر فتح نافذة حفظ الروابط."); }
    }
    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null) return;
        if(requestCode==IMPORT_FILES) {
            ArrayList<Uri> uris=new ArrayList<>();
            if(data.getClipData()!=null) {
                for(int i=0;i<data.getClipData().getItemCount();i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if(data.getData()!=null) uris.add(data.getData());
            app.io.execute(() -> {
                int added=0,skipped=0,failed=0;
                for(Uri uri:uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        long id=app.files.importUri(uri);
                        if(id>0) { app.files.createThumbnail(app.store.get(id));added++; }
                        else skipped++;
                    } catch(Exception ex) { failed++; }
                }
                bridge.snapshot(false);
                bridge.emit("tab",Json.object("tab","library"));
                bridge.message("أُضيف "+added+" ملف • موجود مسبقاً: "+skipped+" • تعذر استيراده: "+failed);
            });
        } else if(requestCode==IMPORT_BACKUP && data.getData()!=null) {
            restoreBackup(data.getData());
        } else if(requestCode==EXPORT_LINKS && data.getData()!=null) {
            Uri destination=data.getData();
            app.io.execute(() -> {
                try {
                    JSONArray items=new JSONArray();
                    for(DownloadRecord r:app.store.all()) items.put(Json.object("title",r.title,"filename",r.filename,
                            "sourceUrl",r.sourceUrl,"format",r.format,"quality",r.quality,
                            "createdAt",r.createdAt,"size",r.size,"status",r.status));
                    String json=Json.object("app","3Esnap","schemaVersion",2,"exportedAt",System.currentTimeMillis(),"items",items).toString(2);
                    try(OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
                        if(out==null) throw new java.io.IOException("لا يمكن الكتابة إلى الوجهة.");
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    bridge.message("تم تصدير الروابط والبيانات. ملف JSON لا يحتوي على الفيديوهات نفسها.");
                } catch(Exception ex) { bridge.error("تعذر تصدير الروابط: "+ex.getMessage()); }
            });
        }
    }
    @Override protected void onDestroy() {
        pageReady=false;
        if(bridge!=null) bridge.close();
        if(webView!=null) { webView.removeJavascriptInterface("AndroidNative");root.removeView(webView);webView.destroy(); }
        super.onDestroy();
    }
}
