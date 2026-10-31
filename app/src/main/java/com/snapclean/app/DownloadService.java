package com.snapclean.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A user-initiated, sequential foreground download queue; never silently restarts on boot. */
public final class DownloadService extends Service {
    private static final String CHANNEL="3esnap-downloads";
    private static final int FOREGROUND_ID=30;
    private static final String CANCEL="cancel";
    private static final java.util.Set<Long> IN_FLIGHT=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean isInFlight(long id) { return IN_FLIGHT.contains(id); }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService guard=Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger outstanding=new AtomicInteger();
    private final Handler main=new Handler(Looper.getMainLooper());
    private SnapCleanApp app;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private volatile long currentId=-1;
    private volatile int lastStartId;
    private volatile boolean destroyed;
    private final java.util.Set<Long> accepted = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static void start(Context context,long id) {
        Intent intent=new Intent(context,DownloadService.class).putExtra("id",id);
        try { ContextCompat.startForegroundService(context,intent); }
        catch(Exception ex) {
            SnapCleanApp app=SnapCleanApp.of(context);
            app.store.fail(id,"تعذر بدء التنزيل في الخلفية. افتح التطبيق وأعد المحاولة.");
            app.changed();
        }
    }
    public static void cancel(Context context,long id) {
        SnapCleanApp app=SnapCleanApp.of(context);
        app.store.cancel(id);
        app.changed();
        // Never block the main thread while a native subprocess is being stopped.
        app.io.execute(() -> stopProcess(id));
    }
    private static String processId(long id) { return "3esnap-"+id; }
    private static synchronized void stopProcess(long id) {
        // The wrapper can finish/remove a process while cancellation is being requested.
        try { YoutubeDL.getInstance().destroyProcessById(processId(id)); }
        catch(RuntimeException ignored) { /* Already exiting; worker state remains authoritative. */ }
    }

    @Override public void onCreate() {
        super.onCreate();
        app=SnapCleanApp.of(this);
        notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,getString(R.string.download_channel),NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("تقدم التنزيلات واكتمال حفظ الملفات");
            notifications.createNotificationChannel(channel);
        }
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null) { wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"3Esnap:download"); wakeLock.setReferenceCounted(false); }
        // Covers cancellation during engine initialization and the small process-start race.
        guard.scheduleWithFixedDelay(() -> {
            long id=currentId;
            if(id>0 && cancelled(id)) stopProcess(id);
        },500,500,TimeUnit.MILLISECONDS);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        lastStartId=startId;
        if(intent==null) { stopSelfResult(startId);return START_NOT_STICKY; }
        if(CANCEL.equals(intent.getAction())) {
            cancel(this,intent.getLongExtra("id",-1));
            if(outstanding.get()==0) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        long id=intent.getLongExtra("id",-1);
        DownloadRecord record=app.store.get(id);
        if(record==null || !"queued".equals(record.status)) {
            if(outstanding.get()==0) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if(!IN_FLIGHT.add(id)) return START_NOT_STICKY;
        Notification n=notification("3Esnap", "جاري تجهيز قائمة التنزيلات…",0,-1);
        try {
            ServiceCompat.startForeground(this,FOREGROUND_ID,n,
                    Build.VERSION.SDK_INT>=29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        } catch(RuntimeException ex) {
            IN_FLIGHT.remove(id);
            app.store.fail(id,"رفض النظام تشغيل خدمة التنزيل الآن. افتح التطبيق وأعد المحاولة.");
            app.changed();
            if(outstanding.get()==0) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        outstanding.incrementAndGet();
        accepted.add(id);
        worker.execute(() -> {
            try { runDownload(id); }
            finally {
                accepted.remove(id);
                IN_FLIGHT.remove(id);
                outstanding.decrementAndGet();
                main.post(() -> {
                    if(!destroyed && outstanding.get()==0) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelfResult(lastStartId);
                    }
                });
            }
        });
        return START_NOT_STICKY;
    }
    private void runDownload(long id) {
        if(!app.store.running(id)) return;
        DownloadRecord record=app.store.get(id);
        if(record==null) return;
        currentId=id;
        app.changed();
        File directory=new File(getCacheDir(),"work/"+id);
        boolean engineLocked=false;
        MediaFiles.Saved published=null;
        boolean recorded=false;
        try {
            update(id,record.title,0,"جاري تجهيز محرك التنزيل…");
            acquireWakeLock();
            EngineRuntime.WORK.lockInterruptibly(); engineLocked=true;
            checkCancelled(id);
            EngineRuntime.ensureReady(this);
            checkCancelled(id);
            MediaFiles.deleteTree(directory);
            if(!directory.mkdirs()) throw new IOException("تعذر إنشاء ملفات التنزيل المؤقتة.");
            if(directory.getUsableSpace()<64L*1024*1024) throw new IOException("حرّر مساحة إضافية قبل بدء التنزيل.");
            YoutubeDLRequest request=EngineRuntime.request(record.sourceUrl);
            request.addOption("-o",new File(directory,"media.%(ext)s").getAbsolutePath());
            request.addOption("--no-simulate");
            request.addOption("--newline");
            request.addOption("--embed-metadata");
            boolean audio="audio".equals(record.format);
            if(audio) {
                request.addOption("-x");
                request.addOption("--audio-format","mp3");
                request.addOption("--audio-quality","192K");
            } else {
                request.addOption("-f",DownloadPolicy.videoSelector(record.quality));
                request.addOption("--merge-output-format","mp4");
                request.addOption("--remux-video","mp4");
            }
            final long[] lastEvent={0};
            YoutubeDL.getInstance().execute(request,processId(id),(percent,eta,line) -> {
                if(cancelled(id)) return kotlin.Unit.INSTANCE; // cancellation guard stops the process off this callback.
                long now=System.currentTimeMillis();
                if(now-lastEvent[0]>=700) {
                    lastEvent[0]=now;
                    int progress=percent==null ? 0 : Math.min(94,Math.max(0,Math.round(percent*0.94f)));
                    String message="جاري تنزيل الوسائط…";
                    if(line!=null && line.contains("[download]")) {
                        message=line.replace("[download]","").replaceAll("\\x1B\\[[0-9;]*m","").trim();
                        if(message.length()>180) message=message.substring(0,180);
                    } else if(line!=null && (line.contains("[Merger]") || line.contains("[ExtractAudio]") || line.contains("[VideoRemuxer]"))) {
                        message="جاري دمج / تحويل الوسائط…";
                    }
                    update(id,record.title,progress,message);
                }
                return kotlin.Unit.INSTANCE;
            });
            checkCancelled(id);
            // Never pick files[0]: it could be a fragment, metadata file, or temporary track.
            File output=new File(directory,audio ? "media.mp3" : "media.mp4");
            if(!output.isFile() || output.length()==0) throw new IOException("لم يُنتج المحرك ملفاً نهائياً صالحاً بالصيغة المطلوبة.");
            update(id,record.title,97,"جاري حفظ الملف في Download/3Esnap…");
            String name=DownloadPolicy.fileName(record.title,audio ? "mp3" : "mp4",id);
            published=app.files.publish(output,name,audio,() -> cancelled(id));
            checkCancelled(id);
            if(!app.store.complete(id,published)) throw new CancellationException();
            recorded=true;
            app.files.createThumbnail(app.store.get(id));
            app.changed();
            notifyCompleted(id,record.title);
        } catch(CancellationException | YoutubeDL.CanceledException ex) {
            app.store.cancel(id);
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            app.store.fail(id,"توقف التنزيل. يمكنك إعادة المحاولة من القائمة.");
        } catch(Exception ex) {
            app.store.fail(id,EngineRuntime.readableError(ex));
        } finally {
            if(published!=null && !recorded) app.files.discard(published);
            MediaFiles.deleteTree(directory);
            if(engineLocked) EngineRuntime.WORK.unlock();
            releaseWakeLock();
            currentId=-1;
            app.changed();
        }
    }
    private boolean cancelled(long id) {
        DownloadRecord r=app.store.get(id);
        return destroyed || Thread.currentThread().isInterrupted() || r==null || !"downloading".equals(r.status);
    }
    private void checkCancelled(long id) { if(cancelled(id)) throw new CancellationException(); }
    @android.annotation.SuppressLint("MissingPermission") // canNotify() explicitly checks POST_NOTIFICATIONS.
    private void update(long id,String title,int progress,String text) {
        app.store.progress(id,progress,text);
        app.changed();
        if(canNotify()) notifications.notify(FOREGROUND_ID,notification(title,text,progress,id));
    }
    private boolean canNotify() {
        return Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
    }
    private PendingIntent openApp(int requestCode) {
        Intent open=new Intent(this,MainActivity.class).putExtra("tab","library");
        return PendingIntent.getActivity(this,requestCode,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(String title,String message,int progress,long id) {
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.ic_download_icon).setContentTitle(title).setContentText(message)
                .setContentIntent(openApp(0)).setOnlyAlertOnce(true).setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setProgress(100,progress,progress==0);
        if(id>0) {
            PendingIntent cancel=PendingIntent.getService(this,1,
                    new Intent(this,DownloadService.class).setAction(CANCEL).putExtra("id",id),
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0,"إلغاء",cancel);
        }
        return b.build();
    }
    @android.annotation.SuppressLint("MissingPermission") // canNotify() explicitly checks POST_NOTIFICATIONS.
    private void notifyCompleted(long id,String title) {
        if(!canNotify()) return;
        int notificationId=1000+(int)(id%100000);
        notifications.notify(notificationId,new NotificationCompat.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.ic_download_icon).setContentTitle("اكتمل التنزيل • 3Esnap")
                .setContentText(title).setAutoCancel(true).setContentIntent(openApp(notificationId)).build());
    }
    private void acquireWakeLock() { if(wakeLock!=null) wakeLock.acquire(60*60*1000L); }
    private void releaseWakeLock() { try { if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release(); } catch(RuntimeException ignored) {} }
    @Override public void onTimeout(int startId,int fgsType) {
        app.store.markInterrupted("بلغ التنزيل حد التشغيل في الخلفية. افتح التطبيق وأعد المحاولة.");
        if(currentId>0) cancel(this,currentId);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy() {
        destroyed=true;
        final long stoppingId=currentId;
        if(stoppingId>0) app.io.execute(() -> stopProcess(stoppingId));
        worker.shutdownNow();guard.shutdownNow();releaseWakeLock();
        synchronized (accepted) {
            for (long id : accepted) {
                app.store.fail(id,"توقفت خدمة التنزيل. اضغط إعادة المحاولة.");
                if(id!=stoppingId) IN_FLIGHT.remove(id);
            }
        }
        app.changed();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
