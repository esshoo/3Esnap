package com.snapclean.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CancellationException;

public final class MediaFiles {
    private final Context context;
    private final DownloadStore store;
    public MediaFiles(Context c, DownloadStore store) { this.context=c.getApplicationContext(); this.store=store; }
    public static final class Saved {
        public final String uri, name, mime;
        public final long size;
        public Saved(String uri,String name,String mime,long size) { this.uri=uri;this.name=name;this.mime=mime;this.size=size; }
    }
    public interface CancelCheck { boolean cancelled(); }

    public Saved publish(File source, String name, boolean audio, CancelCheck check) throws IOException {
        if (!source.isFile() || source.length()==0) throw new IOException("ملف التنزيل فارغ أو غير موجود.");
        if (context.getCacheDir().getUsableSpace() < source.length()+8L*1024*1024)
            throw new IOException("المساحة غير كافية لحفظ الملف النهائي.");
        String mime = audio ? "audio/mpeg" : "video/mp4";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/3Esnap/");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri target=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if (target==null) throw new IOException("تعذر إنشاء ملف في التنزيلات.");
            boolean committed=false;
            try {
                try (InputStream in=new FileInputStream(source); OutputStream out=context.getContentResolver().openOutputStream(target,"w")) {
                    if (out==null) throw new IOException("تعذر فتح الملف للحفظ.");
                    copy(in,out,check);
                }
                if (check.cancelled()) throw new CancellationException();
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING,0);
                if (context.getContentResolver().update(target,values,null,null)!=1) throw new IOException("تعذر إنهاء حفظ الملف.");
                committed=true;
                return new Saved(target.toString(),name,mime,source.length());
            } finally {
                if (!committed) { try { context.getContentResolver().delete(target,null,null); } catch (Exception ignored) {} }
            }
        }
        File folder = legacyFolder();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("تعذر إنشاء مجلد التنزيلات.");
        File target=new File(folder,name);
        // The database ID makes names unique; never silently overwrite an existing file.
        if (target.exists()) target=new File(folder,System.currentTimeMillis()+"_"+name);
        File pending=new File(folder,target.getName()+".pending");
        boolean done=false;
        try {
            try (InputStream in=new FileInputStream(source); OutputStream out=new FileOutputStream(pending)) { copy(in,out,check); }
            if (check.cancelled()) throw new CancellationException();
            if (!pending.renameTo(target)) throw new IOException("تعذر إنهاء حفظ الملف.");
            done=true;
            android.media.MediaScannerConnection.scanFile(context,new String[]{target.getAbsolutePath()},new String[]{mime},null);
            return new Saved(Uri.fromFile(target).toString(),target.getName(),mime,target.length());
        } finally { if (!done) pending.delete(); }
    }
    private static void copy(InputStream in, OutputStream out, CancelCheck check) throws IOException {
        byte[] b=new byte[65536]; int n;
        while((n=in.read(b))!=-1) { if(check.cancelled()) throw new CancellationException(); out.write(b,0,n); }
        out.flush();
    }
    @SuppressWarnings("deprecation") private File legacyFolder() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"3Esnap");
    }
    public boolean exists(DownloadRecord r) {
        if(r.uri==null || r.uri.isEmpty()) return false;
        try {
            Uri uri=Uri.parse(r.uri);
            if ("file".equals(uri.getScheme())) return safeLegacy(uri).isFile();
            try (ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"r")) { return fd!=null; }
        } catch(Exception ex) { return false; }
    }
    private File safeLegacy(Uri uri) throws IOException {
        if (uri.getPath()==null) throw new IOException("Invalid path");
        File f=new File(uri.getPath()).getCanonicalFile();
        if (!f.getPath().startsWith(legacyFolder().getCanonicalPath()+File.separator)) throw new IOException("Invalid file scope");
        return f;
    }
    public Uri shareUri(DownloadRecord r) throws IOException {
        Uri uri=Uri.parse(r.uri);
        if("file".equals(uri.getScheme())) return FileProvider.getUriForFile(context,BuildConfig.APPLICATION_ID+".files",safeLegacy(uri));
        if (!"content".equals(uri.getScheme())) throw new IOException("نوع الملف غير مدعوم.");
        return uri;
    }
    public void deleteOwned(DownloadRecord r) throws IOException {
        if(r.imported) throw new IOException("الملفات المستوردة تُزال من المكتبة فقط.");
        Uri uri=Uri.parse(r.uri);
        if ("file".equals(uri.getScheme())) {
            File file=safeLegacy(uri);
            if(file.exists() && !file.delete()) throw new IOException("تعذر حذف الملف.");
            android.media.MediaScannerConnection.scanFile(context,new String[]{file.getAbsolutePath()},null,null);
        } else if ("content".equals(uri.getScheme())) {
            try { if(context.getContentResolver().delete(uri,null,null)==0 && exists(r)) throw new IOException("تعذر حذف الملف."); }
            catch(SecurityException ex) { throw new IOException("لا توجد صلاحية لحذف الملف؛ يمكنك إزالته من المكتبة فقط.",ex); }
        } else throw new IOException("نوع الملف غير صالح.");
    }
    public void discard(Saved s) {
        DownloadRecord r=new DownloadRecord();r.uri=s.uri;
        try { deleteOwned(r); } catch(Exception ignored) {}
    }
    public long importUri(Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new IOException("اختر الملف من منتقي ملفات أندرويد.");
        String name="ملف مستورد";
        long size=0;
        try (Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
            if (c!=null && c.moveToFirst()) {
                int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s=c.getColumnIndex(OpenableColumns.SIZE);
                if(n>=0 && !c.isNull(n)) name=c.getString(n);
                if(s>=0 && !c.isNull(s)) size=c.getLong(s);
            }
        }
        String mime=context.getContentResolver().getType(uri);
        if (mime==null || !(mime.startsWith("audio/") || mime.startsWith("video/"))) {
            String lower=name.toLowerCase(java.util.Locale.ROOT);
            if(lower.endsWith(".mp3")) mime="audio/mpeg";
            else if(lower.endsWith(".mp4")) mime="video/mp4";
            else throw new IOException("الملف ليس صوتاً أو فيديو مدعوماً.");
        }
        return store.importFile(uri.toString(),name,mime,Math.max(0,size));
    }
    public File thumbnailFile(long id) { return new File(context.getCacheDir(),"thumbs/"+id+".jpg"); }
    public synchronized void createThumbnail(DownloadRecord r) {
        if (r==null || !"completed".equals(r.status) || r.uri.isEmpty()) return;
        File target=thumbnailFile(r.id);
        if(target.isFile()) return;
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        Bitmap original=null, scaled=null;
        File tmp=new File(target.getPath()+".tmp");
        try {
            retriever.setDataSource(context,Uri.parse(r.uri));
            if ("audio".equals(r.format)) {
                byte[] art=retriever.getEmbeddedPicture();
                if(art!=null) {
                    android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds=true;
                    android.graphics.BitmapFactory.decodeByteArray(art,0,art.length,bounds);
                    bounds.inJustDecodeBounds=false;
                    bounds.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/400);
                    original=android.graphics.BitmapFactory.decodeByteArray(art,0,art.length,bounds);
                }
            } else if(Build.VERSION.SDK_INT>=27) {
                original=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,270);
            } else original=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if(original==null) return;
            float factor=Math.min(1f,480f/Math.max(original.getWidth(),original.getHeight()));
            scaled=Bitmap.createScaledBitmap(original,Math.max(1,(int)(original.getWidth()*factor)),Math.max(1,(int)(original.getHeight()*factor)),true);
            File parent=target.getParentFile();
            if(parent!=null && !parent.isDirectory() && !parent.mkdirs()) return;
            try(OutputStream out=new FileOutputStream(tmp)) { scaled.compress(Bitmap.CompressFormat.JPEG,82,out); }
            if(!tmp.renameTo(target)) tmp.delete();
        } catch(Exception | OutOfMemoryError ignored) { tmp.delete(); }
        finally {
            try { retriever.release(); } catch(Exception ignored) {}
            if(scaled!=null && scaled!=original) scaled.recycle();
            if(original!=null) original.recycle();
        }
    }
    public static void deleteTree(File file) {
        if(file.isDirectory()) { File[] children=file.listFiles(); if(children!=null) for(File child:children) deleteTree(child); }
        file.delete();
    }
}
