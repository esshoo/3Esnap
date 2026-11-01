package com.snapclean.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable, app-private library. Only UI preferences live in WebView localStorage. */
public final class DownloadStore extends SQLiteOpenHelper {
    public DownloadStore(Context c) {
        super(c.getApplicationContext(), "3esnap-library.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE downloads (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "source_url TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '',"
                + "format TEXT NOT NULL DEFAULT 'video', quality TEXT NOT NULL DEFAULT 'best',"
                + "uri TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL DEFAULT '',"
                + "filename TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'queued',"
                + "message TEXT NOT NULL DEFAULT '', progress INTEGER NOT NULL DEFAULT 0,"
                + "size INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,"
                + "imported INTEGER NOT NULL DEFAULT 0, missing INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX downloads_state ON downloads(status)");
        db.execSQL("CREATE INDEX downloads_source ON downloads(source_url,format,quality)");
        db.execSQL("CREATE UNIQUE INDEX downloads_uri ON downloads(uri) WHERE uri <> ''");
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Old installations already have schema 1. Add a safe, non-destructive
        // lookup index without replacing their data or requiring a new DB version.
        if(!db.isReadOnly()) db.execSQL("CREATE INDEX IF NOT EXISTS downloads_filename_format ON downloads(filename,format)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future versions must add explicit, non-destructive migrations here.
        throw new IllegalStateException("No migration from " + oldVersion + " to " + newVersion);
    }
    public synchronized long enqueue(String url, String type, String quality, String title) {
        if (findActive(url, type, quality) != null) return -1;
        ContentValues v = new ContentValues();
        v.put("source_url", url); v.put("format", type); v.put("quality", quality);
        v.put("title", title == null || title.trim().isEmpty() ? "تنزيل جديد" : title.substring(0, Math.min(512, title.length())));
        v.put("created_at", System.currentTimeMillis());
        v.put("message", "في انتظار التنزيل");
        return getWritableDatabase().insertOrThrow("downloads", null, v);
    }
    private DownloadRecord findActive(String url, String type, String quality) {
        try (Cursor c = getReadableDatabase().query("downloads", null,
                "source_url=? AND format=? AND quality=? AND status IN ('queued','downloading')",
                new String[]{url,type,quality}, null,null,null,"1")) {
            return c.moveToFirst() ? DownloadRecord.from(c) : null;
        }
    }
    public synchronized DownloadRecord get(long id) {
        try (Cursor c = getReadableDatabase().query("downloads", null,"_id=?",
                new String[]{Long.toString(id)}, null,null,null)) {
            return c.moveToFirst() ? DownloadRecord.from(c) : null;
        }
    }
    public synchronized List<DownloadRecord> all() {
        ArrayList<DownloadRecord> records = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("downloads",null,null,null,null,null,"created_at DESC, _id DESC")) {
            while(c.moveToNext()) records.add(DownloadRecord.from(c));
        }
        return records;
    }
    public synchronized boolean running(long id) {
        ContentValues v = new ContentValues(); v.put("status","downloading");
        v.put("message","جاري تجهيز محرك التنزيل…"); v.put("progress",0);
        return getWritableDatabase().update("downloads",v,"_id=? AND status='queued'",new String[]{Long.toString(id)}) == 1;
    }
    public synchronized void progress(long id, int percent, String text) {
        ContentValues v = new ContentValues(); v.put("progress",Math.max(0,Math.min(99,percent)));
        v.put("message",text);
        getWritableDatabase().update("downloads",v,"_id=? AND status='downloading'",new String[]{Long.toString(id)});
    }
    public synchronized boolean complete(long id, MediaFiles.Saved saved) {
        ContentValues v = new ContentValues(); v.put("status","completed"); v.put("progress",100);
        v.put("uri",saved.uri); v.put("filename",saved.name); v.put("mime",saved.mime);
        v.put("size",saved.size); v.put("message", "اكتمل التنزيل"); v.put("missing",0);
        return getWritableDatabase().update("downloads",v,"_id=? AND status='downloading'",new String[]{Long.toString(id)}) == 1;
    }
    public synchronized void fail(long id, String text) {
        ContentValues v = new ContentValues(); v.put("status","failed"); v.put("message",text);
        getWritableDatabase().update("downloads",v,"_id=? AND status IN ('queued','downloading')",new String[]{Long.toString(id)});
    }
    public synchronized void cancel(long id) {
        ContentValues v = new ContentValues(); v.put("status","cancelled"); v.put("message","تم إلغاء التنزيل");
        getWritableDatabase().update("downloads",v,"_id=? AND status IN ('queued','downloading')",new String[]{Long.toString(id)});
    }
    public synchronized boolean retry(long id) {
        DownloadRecord record = get(id);
        if (record == null || record.imported || !DownloadPolicy.canRetry(record.status)) return false;
        if (findActive(record.sourceUrl,record.format,record.quality) != null) return false;
        ContentValues v = new ContentValues(); v.put("status","queued"); v.put("progress",0);
        v.put("message","في انتظار إعادة المحاولة");
        return getWritableDatabase().update("downloads",v,"_id=?",new String[]{Long.toString(id)}) == 1;
    }
    public synchronized void markInterrupted(String reason) {
        ContentValues v = new ContentValues(); v.put("status","failed"); v.put("message",reason);
        getWritableDatabase().update("downloads",v,"status IN ('queued','downloading')",null);
    }
    public synchronized long importFile(String uri, String name, String mime, long size) {
        try (Cursor c = getReadableDatabase().query("downloads", new String[]{"_id"},"uri=?",new String[]{uri},null,null,null)) {
            if (c.moveToFirst()) return -1;
        }
        String type=mime.startsWith("audio/") ? "audio" : "video";
        // A JSON restore produces link-only placeholders; importing the matching real
        // file reattaches it to the same record, preserving the original source URL.
        try(Cursor c=getReadableDatabase().query("downloads",new String[]{"_id"},
                "filename=? AND format=? AND uri='' AND status='completed' AND missing=1",
                new String[]{name,type},null,null,"created_at DESC","1")) {
            if(c.moveToFirst()) {
                long restoredId=c.getLong(0);
                ContentValues attach=new ContentValues();attach.put("uri",uri);attach.put("mime",mime);
                attach.put("size",size);attach.put("missing",0);attach.put("imported",1);
                getWritableDatabase().update("downloads",attach,"_id=?",new String[]{Long.toString(restoredId)});
                return restoredId;
            }
        }
        ContentValues v = new ContentValues(); v.put("uri",uri); v.put("title",name); v.put("filename",name);
        v.put("mime",mime); v.put("size",size); v.put("format",type);
        v.put("status","completed"); v.put("imported",1); v.put("progress",100);
        v.put("created_at",System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("downloads",null,v);
    }
    public static final class RestoreResult {
        public int added, linked, skipped, invalid;
        public String summary() {return "تم استرجاع "+added+" رابط/سجل • ربط "+linked+" بملفات موجودة • مكرر "+skipped+" • تعذر "+invalid;}
    }

    /** Atomic, additive import of v1/v2 portable metadata. Never deletes existing rows. */
    public synchronized RestoreResult restore(JSONArray items) {
        if(items==null || items.length()>BackupRules.MAX_ITEMS)
            throw new IllegalArgumentException("عدد السجلات في النسخة الاحتياطية غير صالح.");
        RestoreResult result=new RestoreResult();
        SQLiteDatabase db=getWritableDatabase();
        long now=System.currentTimeMillis();
        db.beginTransaction();
        try {
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.optJSONObject(i);
                if(item==null) {result.invalid++;continue;}
                try {
                    String filename=BackupRules.label(item.optString("filename",""),256);
                    String title=BackupRules.label(item.optString("title",""),512);
                    if(title.isEmpty()) title=filename;
                    String url=BackupRules.safeUrl(item.optString("sourceUrl",""));
                    if(title.isEmpty() && url.isEmpty()) {result.invalid++;continue;}
                    if(title.isEmpty()) title="رابط محفوظ";
                    String format=BackupRules.format(item.optString("format",""),filename);
                    String quality=BackupRules.quality(format,item.optString("quality",""));
                    // First enrich the user's EXISTING downloaded media; never replace a
                    // non-empty source link with a different one.
                    boolean matched=false;
                    if(!filename.isEmpty()) {
                        try(Cursor c=db.query("downloads",new String[]{"_id","source_url","uri"},
                                "filename=? AND format=? AND status='completed'",new String[]{filename,format},null,null,null)) {
                            while(c.moveToNext()) {
                                String savedUrl=c.getString(1);
                                if(savedUrl==null) savedUrl="";
                                if(url.isEmpty() || savedUrl.isEmpty() || url.equals(savedUrl)) {
                                    if(savedUrl.isEmpty() && !url.isEmpty()) {
                                        ContentValues v=new ContentValues();v.put("source_url",url);
                                        db.update("downloads",v,"_id=?",new String[]{Long.toString(c.getLong(0))});
                                        result.linked++;
                                    } else result.skipped++;
                                    matched=true;break;
                                }
                            }
                        }
                    }
                    if(matched) continue;
                    if(!url.isEmpty()) {
                        // Re-importing the same JSON is idempotent, including entries
                        // without filenames. Distinct clips from one URL remain distinct.
                        String where=filename.isEmpty()?"source_url=? AND format=? AND filename=''":"source_url=? AND format=? AND filename=?";
                        try(Cursor c=db.query("downloads",new String[]{"_id"},where,
                                new String[]{url,format,filename},null,null,null,"1")) {
                            if(c.moveToFirst()) {result.skipped++;continue;}
                        }
                    } else if(!filename.isEmpty()) {
                        try(Cursor c=db.query("downloads",new String[]{"_id"},
                                "filename=? AND format=?",new String[]{filename,format},null,null,null,"1")) {
                            if(c.moveToFirst()) {result.skipped++;continue;}
                        }
                    }
                    // The old JSON contains metadata only. Never claim a file is
                    // playable, restore a stale URI or silently request storage access.
                    ContentValues v=new ContentValues();
                    v.put("source_url",url);v.put("title",title);v.put("filename",filename);
                    v.put("format",format);v.put("quality",quality);
                    v.put("mime","audio".equals(format)?"audio/mpeg":"video/mp4");
                    v.put("status","completed");v.put("message","رابط مستعاد — الملف يحتاج إعادة تنزيل أو ربط");
                    v.put("uri","");v.put("progress",100);v.put("size",0);
                    v.put("created_at",BackupRules.timestamp(item.optLong("createdAt",now),now));
                    v.put("imported",1);v.put("missing",1);
                    db.insertOrThrow("downloads",null,v);result.added++;
                } catch(IllegalArgumentException ex) {result.invalid++;}
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        return result;
    }

    /** Re-download a metadata-only record in place, without duplicating its link. */
    public synchronized boolean requeueRestored(long id) {
        DownloadRecord r=get(id);
        if(r==null || !"completed".equals(r.status) || !r.missing || r.sourceUrl==null || r.sourceUrl.isEmpty()) return false;
        if(findActive(r.sourceUrl,r.format,r.quality)!=null) return false;
        ContentValues v=new ContentValues();v.put("status","queued");v.put("missing",0);
        v.put("imported",0);v.put("uri","");v.put("progress",0);v.put("message","في انتظار إعادة التنزيل");
        return getWritableDatabase().update("downloads",v,"_id=? AND status='completed' AND missing=1",
                new String[]{Long.toString(id)})==1;
    }

    /** A trimmed/cropped export is a new owned file; it never overwrites the original. */
    public synchronized long addEdited(DownloadRecord source, String title, MediaFiles.Saved saved) {
        if(source==null || saved==null) throw new IllegalArgumentException("Missing source or export");
        ContentValues v=new ContentValues();
        v.put("source_url",source.sourceUrl==null?"":source.sourceUrl);
        v.put("title",title);
        v.put("format",saved.mime.startsWith("audio/")?"audio":"video");
        v.put("quality","edited");
        v.put("uri",saved.uri);
        v.put("mime",saved.mime);
        v.put("filename",saved.name);
        v.put("status","completed");
        v.put("message","مقطع مُحرر من المكتبة");
        v.put("progress",100);
        v.put("size",saved.size);
        v.put("created_at",System.currentTimeMillis());
        v.put("imported",0);
        return getWritableDatabase().insertOrThrow("downloads",null,v);
    }
    public synchronized void setSource(long id, String url) {
        ContentValues v = new ContentValues(); v.put("source_url",url);
        getWritableDatabase().update("downloads",v,"_id=? AND status='completed'",new String[]{Long.toString(id)});
    }
    public synchronized void setMissing(long id, boolean missing) {
        ContentValues v = new ContentValues(); v.put("missing",missing ? 1 : 0);
        getWritableDatabase().update("downloads",v,"_id=?",new String[]{Long.toString(id)});
    }
    public synchronized void remove(long id) {
        getWritableDatabase().delete("downloads","_id=? AND status NOT IN ('queued','downloading')",new String[]{Long.toString(id)});
    }
}
