package com.snapclean.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Legacy class name retained; the installed product is 3Esnap with a new application ID. */
public final class SnapCleanApp extends Application {
    public static final String ACTION_CHANGED = BuildConfig.APPLICATION_ID + ".LIBRARY_CHANGED";
    public DownloadStore store;
    public MediaFiles files;
    public final ExecutorService io = Executors.newSingleThreadExecutor();
    public final ExecutorService inspector = Executors.newSingleThreadExecutor();
    @Override public void onCreate() {
        super.onCreate();
        store = new DownloadStore(this);
        // A process restart must not leave a fake 'downloading' row forever.
        store.markInterrupted("توقف التطبيق قبل اكتمال التنزيل. اضغط إعادة المحاولة.");
        files = new MediaFiles(this,store);
        io.execute(() -> {
            MediaFiles.deleteTree(new java.io.File(getCacheDir(),"work"));
            // Incomplete edits can remain after a crash. Only clean stale temp folders.
            java.io.File edits=new java.io.File(getCacheDir(),"edits");
            java.io.File[] folders=edits.listFiles();
            long cutoff=System.currentTimeMillis()-24L*60*60*1000;
            if(folders!=null) for(java.io.File folder:folders)
                if(folder.isDirectory() && folder.lastModified()<cutoff) MediaFiles.deleteTree(folder);
        });
    }
    public static SnapCleanApp of(Context c) { return (SnapCleanApp)c.getApplicationContext(); }
    public void changed() { sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName())); }
}
