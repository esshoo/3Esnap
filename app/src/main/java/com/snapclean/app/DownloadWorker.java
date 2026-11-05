package com.snapclean.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Durable, network-constrained foreground transfer. Android may defer its start. */
public final class DownloadWorker extends Worker {
    private static final String CHANNEL="3esnap-downloads";
    private static final Set<Long> RUNNING=ConcurrentHashMap.newKeySet();
    private final SnapCleanApp app;
    private volatile long jobId=-1;
    private volatile boolean processStarted;

    public DownloadWorker(@NonNull Context c,@NonNull WorkerParameters p) {
        super(c,p);app=SnapCleanApp.of(c);
    }
    public static boolean isInFlight(long id) {return RUNNING.contains(id);}
    public static String workName(long id){return "3esnap-media-"+id;}
    public static void schedule(Context c,long id,long delayMillis) {
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(DownloadWorker.class)
                .setInputData(new Data.Builder().putLong("id",id).build())
                .setConstraints(constraints)
                .setInitialDelay(Math.max(0,delayMillis),TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                .addTag("3esnap-download").build();
        WorkManager.getInstance(c).enqueueUniqueWork(workName(id),ExistingWorkPolicy.REPLACE,request);
    }
    public static void cancel(Context c,long id) {
        WorkManager.getInstance(c).cancelUniqueWork(workName(id));
        // The worker's onStopped() also terminates its native process.
    }
    public static void stopProcess(long id) {
        try {YoutubeDL.getInstance().destroyProcessById("3esnap-"+id);}catch(RuntimeException ignored){}
    }
    @NonNull @Override public Result doWork() {
        jobId=getInputData().getLong("id",-1);
        if(jobId<=0)return Result.failure();
        if(!RUNNING.add(jobId))return Result.retry(); // Replacement may overlap a stopping worker.
        DownloadRecord record=app.store.get(jobId);
        if(record==null || !("queued".equals(record.status)||"waiting_network".equals(record.status))) {
            RUNNING.remove(jobId);return Result.success();
        }
        String title=record.title==null?"3Esnap":record.title;
        File dir=new File(getApplicationContext().getCacheDir(),"work/"+jobId);
        boolean locked=false;
        MediaFiles.Saved published=null;
        boolean completed=false;
        ScheduledExecutorService cancellationGuard=Executors.newSingleThreadScheduledExecutor();
        try {
            // WorkManager's foreground service can execute when an Activity is no longer visible.
            setForegroundAsync(foregroundInfo(title,"جاري تجهيز التنزيل…",0) ).get();
            if(!app.store.running(jobId)) return Result.success();
            app.changed();
            cancellationGuard.scheduleWithFixedDelay(()->{
                DownloadRecord r=app.store.get(jobId);
                if(isStopped() || r==null || !"downloading".equals(r.status)) stopProcess(jobId);
            },500,500,TimeUnit.MILLISECONDS);
            EngineRuntime.WORK.lockInterruptibly();locked=true;
            checkActive();EngineRuntime.ensureReady(getApplicationContext());checkActive();
            MediaFiles.deleteTree(dir);
            if(!dir.mkdirs())throw new IOException("تعذر إنشاء مجلد التنزيل المؤقت.");
            if(dir.getUsableSpace()<64L*1024*1024)throw new IOException("المساحة غير كافية للتنزيل.");
            YoutubeDLRequest request=EngineRuntime.request(record.sourceUrl);
            request.addOption("-o",new File(dir,"media.%(ext)s").getAbsolutePath());
            request.addOption("--no-simulate");request.addOption("--newline");request.addOption("--embed-metadata");
            boolean audio="audio".equals(record.format);
            if(audio){request.addOption("-x");request.addOption("--audio-format","mp3");request.addOption("--audio-quality","192K");}
            else {request.addOption("-f",DownloadPolicy.videoSelector(record.quality));request.addOption("--merge-output-format","mp4");request.addOption("--remux-video","mp4");}
            final long[] tick={0};
            processStarted=true;
            YoutubeDL.getInstance().execute(request,"3esnap-"+jobId,(percent,eta,line)->{
                if(isStopped())stopProcess(jobId);
                long now=SystemClock.elapsedRealtime();
                if(now-tick[0]>800){tick[0]=now;
                    int progress=percent==null?0:Math.max(0,Math.min(94,Math.round(percent*.94f)));
                    app.store.progress(jobId,progress,"جاري تنزيل الوسائط…");
                    app.changed();
                    setForegroundAsync(foregroundInfo(title,"جاري التنزيل…",progress));
                }
                return kotlin.Unit.INSTANCE;
            });
            processStarted=false;
            checkActive();
            File output=new File(dir,audio?"media.mp3":"media.mp4");
            if(!output.isFile() || output.length()==0)throw new IOException("لم يتم إنتاج ملف نهائي صالح.");
            app.store.progress(jobId,97,"جاري حفظ الملف…");app.changed();
            String filename=DownloadPolicy.fileName(title,audio?"mp3":"mp4",jobId);
            published=app.files.publish(output,filename,audio,()->isStopped() || wasCancelled());
            checkActive();
            if(!app.store.complete(jobId,published))throw new CancellationException();
            completed=true;
            DownloadRecord saved=app.store.get(jobId);
            if(saved!=null)app.files.createThumbnail(saved);
            app.changed();
            postCompleted(title);
            return Result.success();
        }catch(InterruptedException ex){Thread.currentThread().interrupt();return resumeOrStop("توقف التنزيل في الخلفية.",true);}
        catch(CancellationException | YoutubeDL.CanceledException ex){return resumeOrStop("توقف التنزيل مؤقتاً.",true);}
        catch(Exception ex){String msg=EngineRuntime.readableError(ex);return resumeOrStop(msg,isTransient(ex,msg));}
        finally{
            cancellationGuard.shutdownNow();processStarted=false;
            if(published!=null && !completed)app.files.discard(published);
            MediaFiles.deleteTree(dir);
            if(locked)EngineRuntime.WORK.unlock();
            RUNNING.remove(jobId);app.changed();
        }
    }
    private void checkActive(){
        DownloadRecord r=app.store.get(jobId);
        if(isStopped() || r==null || !"downloading".equals(r.status))throw new CancellationException();
    }
    private boolean wasCancelled(){DownloadRecord r=app.store.get(jobId);return r==null || !"downloading".equals(r.status);}
    private Result resumeOrStop(String error,boolean transientFailure) {
        DownloadRecord r=app.store.get(jobId);
        if(r==null || "cancelled".equals(r.status))return Result.success();
        if(transientFailure && getRunAttemptCount()<5) {
            app.store.deferForNetwork(jobId,"في انتظار إعادة المحاولة / الشبكة: "+error);
            app.changed();return Result.retry();
        }
        app.store.fail(jobId,"فشل التنزيل: "+error+" — يمكنك إعادة المحاولة يدوياً.");app.changed();
        return Result.failure();
    }
    private boolean isTransient(Exception ex,String text) {
        if(isStopped() || ex instanceof IOException || !hasInternet())return true;
        String s=(text==null?"":text).toLowerCase(Locale.ROOT);
        return s.contains("timeout") || s.contains("connection") || s.contains("network")
                || s.contains("temporary") || s.contains("socket") || s.contains("dns")
                || s.contains("http error 5") || s.contains("timed out");
    }
    private boolean hasInternet() {
        ConnectivityManager cm=(ConnectivityManager)getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        if(Build.VERSION.SDK_INT>=23) {
            NetworkCapabilities c=cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c!=null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return true;
    }
    private int notifyId(){return 4000+(int)(jobId%100000);}
    private ForegroundInfo foregroundInfo(String title,String status,int progress) {
        Notification alert=notification(title,status,progress);
        // Android 14+ requires the appropriate foreground-service type. WorkManager
        // merges the dataSync declaration from AndroidManifest.xml.
        if(Build.VERSION.SDK_INT>=29)
            return new ForegroundInfo(notifyId(),alert,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return new ForegroundInfo(notifyId(),alert);
    }
    private Notification notification(String title,String state,int progress) {
        NotificationManager nm=(NotificationManager)getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26 && nm!=null) nm.createNotificationChannel(
            new NotificationChannel(CHANNEL,"3Esnap • التنزيلات",NotificationManager.IMPORTANCE_LOW));
        return new NotificationCompat.Builder(getApplicationContext(),CHANNEL)
                .setSmallIcon(R.drawable.ic_download_icon).setContentTitle(title).setContentText(state)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setOngoing(true)
                .setOnlyAlertOnce(true).setProgress(100,progress,progress==0).build();
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void postCompleted(String title) {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(getApplicationContext(),Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager nm=(NotificationManager)getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm!=null)nm.notify(notifyId()+100000,new NotificationCompat.Builder(getApplicationContext(),CHANNEL)
            .setSmallIcon(R.drawable.ic_download_icon).setContentTitle("3Esnap • اكتمل التنزيل")
            .setContentText(title).setAutoCancel(true).build());
    }
    @Override public void onStopped() {
        super.onStopped();
        if(processStarted && jobId>0) {
            Thread stopper=new Thread(()->stopProcess(jobId),"3esnap-stop-download");stopper.setDaemon(true);stopper.start();
        }
        DownloadRecord r=app.store.get(jobId);
        if(r!=null && "downloading".equals(r.status))app.store.deferForNetwork(jobId,"توقف الاتصال أو أجّل النظام التنزيل. سيُستأنف تلقائياً.");
    }
}
