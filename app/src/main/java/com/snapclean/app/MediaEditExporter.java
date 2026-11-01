package com.snapclean.app;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-device clipping/cropping with the already bundled FFmpeg executable. No shell or network. */
public final class MediaEditExporter {
    private final AtomicBoolean cancelled=new AtomicBoolean(false);
    private volatile Process process;

    public void cancel() {
        cancelled.set(true);
        Process active=process;
        if(active!=null) active.destroy();
    }
    private void ensureActive() { if(cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException(); }

    public long export(Context context,DownloadRecord original,EditPlan.Clip clip,EditPlan.Crop crop,boolean audio) throws Exception {
        Context appContext=context.getApplicationContext();
        SnapCleanApp app=SnapCleanApp.of(appContext);
        File root=new File(appContext.getCacheDir(),"edits");
        if(!root.isDirectory() && !root.mkdirs()) throw new IOException("تعذر إنشاء مجلد مؤقت للتحرير.");
        File work=new File(root,UUID.randomUUID().toString());
        if(!work.mkdirs()) throw new IOException("تعذر إنشاء مجلد مؤقت للتحرير.");
        File source=new File(work,"source.media");
        File output=new File(work,audio?"clip.mp3":"clip.mp4");
        MediaFiles.Saved published=null;
        try {
            ensureActive();
            if(original.size>0 && work.getUsableSpace()<original.size+24L*1024*1024)
                throw new IOException("المساحة المتاحة لا تكفي لنسخ الملف مؤقتاً. حرّر مساحة أولاً.");
            // SAF and MediaStore URIs cannot be handed to an external process as raw paths.
            try(InputStream in=appContext.getContentResolver().openInputStream(Uri.parse(original.uri));
                OutputStream out=new FileOutputStream(source)) {
                if(in==null) throw new IOException("تعذر فتح الملف الأصلي.");
                byte[] buffer=new byte[65536]; int n;
                while((n=in.read(buffer))!=-1) { ensureActive(); out.write(buffer,0,n); }
                out.flush();
            }
            if(source.length()==0) throw new IOException("الملف الأصلي فارغ.");
            ensureActive();
            // yt-dlp-android packages FFmpeg as libffmpeg.so, the executable also used by yt-dlp.
            File binary=new File(appContext.getApplicationInfo().nativeLibraryDir,"libffmpeg.so");
            if(!binary.isFile()) throw new IOException("محرك FFmpeg غير موجود داخل التطبيق.");
            EngineRuntime.ensureReady(appContext);
            String diagnostic;
            // Keep the shared yt-dlp/FFmpeg package stable while editing.
            EngineRuntime.WORK.lock();
            try {
                diagnostic=run(appContext,EditPlan.ffmpeg(binary.getAbsolutePath(),source.getAbsolutePath(),output.getAbsolutePath(),clip,crop,audio,false));
                if(diagnostic!=null && !audio && (diagnostic.toLowerCase(Locale.ROOT).contains("unknown encoder")
                        || diagnostic.toLowerCase(Locale.ROOT).contains("encoder not found"))) {
                    ensureActive();
                    if(output.exists()) output.delete();
                    diagnostic=run(appContext,EditPlan.ffmpeg(binary.getAbsolutePath(),source.getAbsolutePath(),output.getAbsolutePath(),clip,crop,false,true));
                }
            } finally { EngineRuntime.WORK.unlock(); }
            if(diagnostic!=null) throw new IOException("تعذّر تحويل المقطع: "+diagnostic);
            ensureActive();
            if(!output.isFile() || output.length()<128) throw new IOException("لم ينتج ملف صالح من القص.");
            String extension=audio?"mp3":"mp4";
            String title="مقطع — "+(original.title==null?"3Esnap":original.title);
            String filename=DownloadPolicy.fileName(title,extension,System.currentTimeMillis());
            published=app.files.publish(output,filename,audio,cancelled::get);
            ensureActive();
            long id=app.store.addEdited(original,title,published);
            if(id<0) throw new IOException("تعذر حفظ المقطع في المكتبة.");
            published=null; // the saved file now belongs to the library
            DownloadRecord created=app.store.get(id);
            if(created!=null) app.files.createThumbnail(created);
            app.changed();
            return id;
        } catch(CancellationException ex) { throw new IOException("تم إلغاء القص.",ex); }
        finally {
            if(published!=null) app.files.discard(published);
            MediaFiles.deleteTree(work);
        }
    }
    /** Null on exit code zero, otherwise a bounded diagnostic string for UI. */
    private String run(Context context,List<String> command) throws IOException,InterruptedException {
        ensureActive();
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        // Match yt-dlp-android's executable environment; FFmpeg loads bundled shared libraries.
        File packages=new File(context.getNoBackupFilesDir(),"youtubedl-android/packages");
        String extra=new File(packages,"python/usr/lib").getAbsolutePath()+":"
                +new File(packages,"ffmpeg/usr/lib").getAbsolutePath()+":"
                +new File(packages,"aria2c/usr/lib").getAbsolutePath();
        String inherited=builder.environment().get("LD_LIBRARY_PATH");
        builder.environment().put("LD_LIBRARY_PATH",extra+(inherited==null||inherited.isEmpty()?"":":"+inherited));
        builder.environment().put("TMPDIR",context.getCacheDir().getAbsolutePath());
        Process p=builder.start();
        process=p;
        ByteArrayOutputStream tail=new ByteArrayOutputStream();
        try(InputStream stream=p.getInputStream()) {
            byte[] buf=new byte[4096];int n;
            while((n=stream.read(buf))!=-1) {
                if(cancelled.get()) { p.destroy(); throw new CancellationException(); }
                tail.write(buf,0,n);
                if(tail.size()>8192) { byte[] all=tail.toByteArray();tail.reset();tail.write(all,all.length-4096,4096); }
            }
            int result=p.waitFor();
            ensureActive();
            if(result==0) return null;
            String text=new String(tail.toByteArray(),StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\\n]]"," ").trim();
            if(text.isEmpty()) text="رمز الخطأ "+result;
            return text.substring(0,Math.min(350,text.length()));
        } finally {process=null;if(p.isAlive()) p.destroy();}
    }
}
