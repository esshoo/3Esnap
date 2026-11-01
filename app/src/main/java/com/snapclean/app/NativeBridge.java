package com.snapclean.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only exposed to the packaged, CSP-protected appassets page. Never accepts filesystem paths. */
public final class NativeBridge {
    private final MainActivity activity;
    private final WebView web;
    private final SnapCleanApp app;
    private volatile boolean closed;
    private final AtomicBoolean updating=new AtomicBoolean();
    NativeBridge(MainActivity activity,WebView web) {
        this.activity=activity;this.web=web;this.app=SnapCleanApp.of(activity);
    }
    public void close() { closed=true; }
    public void emit(String type,JSONObject payload) {
        String json=Json.object("type",type,"data",payload).toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029");
        activity.runOnUiThread(() -> {
            if(!closed && activity.isPageReady()) web.evaluateJavascript("window.AppNative && window.AppNative.receive("+json+");",null);
        });
    }
    public void error(String message) { emit("error",Json.object("message",message)); }
    public void message(String message) { emit("message",Json.object("message",message)); }
    @JavascriptInterface public void ready() { snapshot(false); }
    @JavascriptInterface public void refreshLibrary() { snapshot(true); }
    public void snapshot(boolean checkFiles) {
        if(closed) return;
        app.io.execute(() -> {
            try {
                JSONArray records=new JSONArray();
                for(DownloadRecord r:app.store.all()) {
                    if(checkFiles && "completed".equals(r.status)) {
                        r.missing=!app.files.exists(r);app.store.setMissing(r.id,r.missing);
                        if(!r.missing) app.files.createThumbnail(r);
                    }
                    records.put(r.toJson());
                }
                String version=YoutubeDL.getInstance().versionName(activity.getApplicationContext());
                emit("snapshot",Json.object("items",records,"version",BuildConfig.VERSION_NAME,
                        "buildType",BuildConfig.BUILD_TYPE,"engineVersion",version==null ? "المحرك المرفق" : version));
            } catch(Exception ex) { error("تعذر قراءة مكتبة الملفات."); }
        });
    }
    @JavascriptInterface public void inspectUrl(String raw,String requestId) {
        final String url;
        try { url=DownloadPolicy.extractUrl(raw); }
        catch(Exception ex) { emit("inspectError",Json.object("requestId",requestId,"message",ex.getMessage()));return; }
        if(requestId==null || requestId.length()>64) return;
        app.inspector.execute(() -> {
            boolean locked=false;
            try {
                EngineRuntime.WORK.lockInterruptibly();locked=true;
                EngineRuntime.ensureReady(activity.getApplicationContext());
                YoutubeDLRequest request=EngineRuntime.request(url);
                request.addOption("--skip-download");
                VideoInfo info=YoutubeDL.getInstance().getInfo(request);
                if(info==null) throw new IllegalArgumentException("تعذر استخراج بيانات هذا الرابط.");
                TreeSet<Integer> heights=new TreeSet<>(Collections.reverseOrder());
                if(info.getFormats()!=null) for(VideoFormat format:info.getFormats()) {
                    if(format.getHeight()>=144 && format.getHeight()<=4320 && !"none".equals(format.getVcodec())) heights.add(format.getHeight());
                }
                JSONArray qualities=new JSONArray();
                for(int h:heights) qualities.put(h);
                String thumbnail=info.getThumbnail();
                if(thumbnail==null || !thumbnail.startsWith("https://")) thumbnail="";
                emit("inspect",Json.object("requestId",requestId,"url",url,
                        "title",info.getTitle()==null ? "فيديو بدون عنوان" : info.getTitle(),
                        "thumbnail",thumbnail,"duration",info.getDuration(),"uploader",info.getUploader(),
                        "platform",info.getExtractor(),"qualities",qualities));
            } catch(Exception ex) {
                if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                emit("inspectError",Json.object("requestId",requestId,"message",EngineRuntime.readableError(ex)));
            } finally { if(locked) EngineRuntime.WORK.unlock(); }
        });
    }
    @JavascriptInterface public void startDownload(String raw,String type,String quality,String title) {
        try {
            String url=DownloadPolicy.normalizeUrl(raw);
            String format=DownloadPolicy.format(type), selected=DownloadPolicy.quality(format,quality);
            activity.runOnUiThread(() -> activity.withDownloadPermissions(() -> app.io.execute(() -> {
                try {
                    long id=app.store.enqueue(url,format,selected,title);
                    if(id<0) { error("هذا الرابط بنفس الجودة موجود بالفعل في طابور التنزيل.");return; }
                    activity.runOnUiThread(() -> DownloadService.start(activity,id));
                    snapshot(false);message("أُضيف إلى طابور التنزيل.");
                } catch(Exception ex) { error("تعذر إضافة التنزيل: "+ex.getMessage()); }
            })));
        } catch(Exception ex) { error(ex.getMessage()); }
    }
    @JavascriptInterface public void retry(long id) {
        if(DownloadService.isInFlight(id)) { error("انتظر انتهاء إغلاق عملية التنزيل السابقة ثم أعد المحاولة.");return; }
        activity.runOnUiThread(() -> activity.withDownloadPermissions(() -> app.io.execute(() -> {
            if(DownloadService.isInFlight(id)) { error("عملية التنزيل السابقة ما زالت تُغلق.");return; }
            if(app.store.retry(id)) activity.runOnUiThread(() -> DownloadService.start(activity,id));
            else error("لا يمكن إعادة المحاولة الآن، أو أن التنزيل نفسه نشط بالفعل.");
            snapshot(false);
        })));
    }
    @JavascriptInterface public void cancel(long id) { DownloadService.cancel(activity,id); }
    @JavascriptInterface public void importFiles() { activity.runOnUiThread(activity::chooseImports); }
    @JavascriptInterface public void exportLibrary() { activity.runOnUiThread(activity::chooseExport); }
    @JavascriptInterface public void setTheme(boolean light) { activity.setLightTheme(light); }
    @JavascriptInterface public void paste() {
        activity.runOnUiThread(() -> {
            try {
                ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if(clipboard==null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip()==null) { error("الحافظة فارغة.");return; }
                CharSequence text=clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity);
                emit("paste",Json.object("text",text==null ? "" : text.toString()));
            } catch(Exception ex) { error("تعذر قراءة الحافظة. استخدم اللصق من لوحة المفاتيح."); }
        });
    }
    @JavascriptInterface public void play(long id) {
        app.io.execute(() -> {
            DownloadRecord record=available(id);
            if(record==null) return;
            activity.runOnUiThread(() -> {
                try { activity.startActivity(new Intent(activity,PlayerActivity.class).putExtra("id",id)); }
                catch(Exception ex) { error("تعذر فتح المشغل."); }
            });
        });
    }
    @JavascriptInterface public void edit(long id) {
        app.io.execute(() -> {
            DownloadRecord record=available(id);
            if(record==null) return;
            activity.runOnUiThread(() -> {
                try { activity.startActivity(new Intent(activity,EditorActivity.class).putExtra("id",id)); }
                catch(Exception ex) { error("تعذر فتح محرر المقاطع."); }
            });
        });
    }
    @JavascriptInterface public void share(long id) {
        app.io.execute(() -> {
            DownloadRecord record=available(id);
            if(record==null) return;
            try {
                Uri uri=app.files.shareUri(record);
                Intent send=new Intent(Intent.ACTION_SEND).setType(record.mime)
                        .putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TITLE,record.title);
                send.setClipData(ClipData.newRawUri(record.title,uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.runOnUiThread(() -> {
                    try { activity.startActivity(Intent.createChooser(send,"مشاركة الملف")); }
                    catch(Exception ex) { error("لم أجد تطبيقاً مناسباً لمشاركة الملف."); }
                });
            } catch(Exception ex) { error(ex.getMessage()); }
        });
    }
    private DownloadRecord available(long id) {
        DownloadRecord record=app.store.get(id);
        if(record==null || !"completed".equals(record.status)) { error("هذا الملف غير موجود في المكتبة.");return null; }
        if(!app.files.exists(record)) {
            app.store.setMissing(id,true);snapshot(false);
            error("الملف نُقل أو حُذف خارج التطبيق، أو انتهت صلاحية الوصول إليه. استورده مجدداً أو أعد تنزيله.");return null;
        }
        if(record.missing) app.store.setMissing(id,false);
        return record;
    }
    @JavascriptInterface public void sourceAction(long id,String action) {
        app.io.execute(() -> {
            DownloadRecord record=app.store.get(id);
            if(record==null) return;
            activity.runOnUiThread(() -> {
                if("edit".equals(action)) { editSource(record);return; }
                if(record.sourceUrl.isEmpty()) { error("لا يوجد رابط محفوظ لهذا الملف. يمكنك إضافته من قائمة الملف.");return; }
                if("copy".equals(action)) {
                    ClipboardManager clip=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if(clip!=null) { clip.setPrimaryClip(ClipData.newPlainText("رابط المصدر",record.sourceUrl));message("تم نسخ رابط المصدر."); }
                } else if("open".equals(action)) activity.openExternal(record.sourceUrl);
                else if("share".equals(action)) {
                    try { activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,record.sourceUrl),"مشاركة الرابط")); }
                    catch(Exception ex) { error("تعذر مشاركة الرابط."); }
                }
            });
        });
    }
    private void editSource(DownloadRecord r) {
        EditText input=new EditText(activity);input.setSingleLine(true);input.setText(r.sourceUrl);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://…");input.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        LinearLayout box=new LinearLayout(activity);int pad=(int)(20*activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad,pad,pad,0);box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("رابط المصدر")
                .setMessage("يمكنك إضافة الرابط الأصلي أو تعديله. اتركه فارغاً لإزالته من السجل.")
                .setView(box).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String value=input.getText().toString().trim();
                String url=value.isEmpty() ? "" : DownloadPolicy.normalizeUrl(value);
                app.io.execute(() -> { app.store.setSource(r.id,url);snapshot(false); });dialog.dismiss();
            } catch(Exception ex) { input.setError(ex.getMessage()); }
        }));
        dialog.show();
    }
    @JavascriptInterface public void requestDelete(long id) {
        app.io.execute(() -> {
            DownloadRecord r=app.store.get(id);
            if(r==null || DownloadPolicy.isActive(r.status)) return;
            activity.runOnUiThread(() -> {
                AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(r.title).setNegativeButton("إلغاء",null);
                if(r.imported || !"completed".equals(r.status)) {
                    dialog.setMessage("إزالة السجل من المكتبة؟ لن نحذف أي ملف من جهازك.")
                            .setPositiveButton("إزالة السجل",(d,w) -> remove(r,false));
                } else {
                    dialog.setMessage("حذف الملف يمسحه من الجهاز. إزالة السجل فقط تُبقي الملف وتزيل بياناته ورابطه من المكتبة.")
                            .setNeutralButton("إزالة السجل فقط",(d,w) -> remove(r,false))
                            .setPositiveButton("حذف الملف",(d,w) -> remove(r,true));
                }
                dialog.show();
            });
        });
    }
    private void remove(DownloadRecord record,boolean deleteFile) {
        app.io.execute(() -> {
            try {
                if(deleteFile) app.files.deleteOwned(record);
                app.store.remove(record.id);app.files.thumbnailFile(record.id).delete();
                if(record.imported) {
                    try { activity.getContentResolver().releasePersistableUriPermission(Uri.parse(record.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch(Exception ignored) {}
                }
                snapshot(false);message(deleteFile ? "تم حذف الملف." : "تمت إزالة السجل؛ الملف نفسه لم يُحذف.");
            } catch(Exception ex) { error(ex.getMessage()); }
        });
    }
    @JavascriptInterface public void updateEngine() {
        if(!updating.compareAndSet(false,true)) return;
        emit("engineUpdating",Json.object("busy",true));
        app.inspector.execute(() -> {
            boolean locked=false;
            try {
                for(DownloadRecord r:app.store.all()) if(DownloadPolicy.isActive(r.status))
                    throw new IllegalStateException("انتظر اكتمال طابور التنزيلات قبل تحديث المحرك.");
                EngineRuntime.UPDATE.lockInterruptibly();locked=true;
                EngineRuntime.ensureReady(activity.getApplicationContext());
                YoutubeDL.getInstance().updateYoutubeDL(activity.getApplicationContext(),YoutubeDL.UpdateChannel._STABLE);
                message("محرك yt-dlp محدّث إلى الإصدار المستقر المتاح.");snapshot(false);
            } catch(Exception ex) {
                if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                error(EngineRuntime.readableError(ex));
            } finally {
                if(locked) EngineRuntime.UPDATE.unlock();
                updating.set(false);emit("engineUpdating",Json.object("busy",false));
            }
        });
    }
}
