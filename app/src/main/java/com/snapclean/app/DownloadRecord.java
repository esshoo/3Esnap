package com.snapclean.app;

import android.database.Cursor;
import org.json.JSONException;
import org.json.JSONObject;

public final class DownloadRecord {
    public long id, size, createdAt;
    public String sourceUrl, title, format, quality, uri, mime, filename, status, message;
    public int progress;
    public boolean imported, missing, hidden;
    public String category;

    static DownloadRecord from(Cursor c) {
        DownloadRecord r = new DownloadRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        r.size = c.getLong(c.getColumnIndexOrThrow("size"));
        r.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        r.sourceUrl = c.getString(c.getColumnIndexOrThrow("source_url"));
        r.title = c.getString(c.getColumnIndexOrThrow("title"));
        r.format = c.getString(c.getColumnIndexOrThrow("format"));
        r.quality = c.getString(c.getColumnIndexOrThrow("quality"));
        r.uri = c.getString(c.getColumnIndexOrThrow("uri"));
        r.mime = c.getString(c.getColumnIndexOrThrow("mime"));
        r.filename = c.getString(c.getColumnIndexOrThrow("filename"));
        r.status = c.getString(c.getColumnIndexOrThrow("status"));
        r.message = c.getString(c.getColumnIndexOrThrow("message"));
        r.progress = c.getInt(c.getColumnIndexOrThrow("progress"));
        r.imported = c.getInt(c.getColumnIndexOrThrow("imported")) == 1;
        r.missing = c.getInt(c.getColumnIndexOrThrow("missing")) == 1;
        r.hidden = c.getInt(c.getColumnIndexOrThrow("hidden")) == 1;
        r.category = c.getString(c.getColumnIndexOrThrow("category"));
        return r;
    }

    public JSONObject toJson() throws JSONException {
        // Content URIs / filesystem paths are deliberately not exposed to JavaScript.
        return new JSONObject().put("id", id).put("size", size).put("createdAt", createdAt)
                .put("sourceUrl", sourceUrl).put("title", title).put("format", format)
                .put("quality", quality).put("mime", mime).put("filename", filename)
                .put("status", status).put("message", message).put("progress", progress)
                .put("imported", imported).put("missing", missing).put("category", category)
                .put("thumbnail", "https://appassets.androidplatform.net/thumbs/" + id + ".jpg");
    }
}
