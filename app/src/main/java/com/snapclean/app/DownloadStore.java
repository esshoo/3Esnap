package com.snapclean.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

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
        ContentValues v = new ContentValues(); v.put("uri",uri); v.put("title",name); v.put("filename",name);
        v.put("mime",mime); v.put("size",size); v.put("format",mime.startsWith("audio/") ? "audio" : "video");
        v.put("status","completed"); v.put("imported",1); v.put("progress",100);
        v.put("created_at",System.currentTimeMillis());
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
