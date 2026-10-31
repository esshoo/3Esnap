package com.snapclean.app;

import android.content.Context;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Allows inspection during downloads; engine updates take an exclusive lock. */
public final class EngineRuntime {
    private static final ReentrantReadWriteLock GATE = new ReentrantReadWriteLock(true);
    public static final Lock WORK = GATE.readLock();
    public static final Lock UPDATE = GATE.writeLock();
    private static boolean ready;
    private EngineRuntime() {}
    public static synchronized void ensureReady(Context context) throws Exception {
        if (ready) return;
        YoutubeDL.getInstance().init(context.getApplicationContext());
        FFmpeg.getInstance().init(context.getApplicationContext());
        ready = true;
    }
    public static YoutubeDLRequest request(String url) {
        YoutubeDLRequest r = new YoutubeDLRequest(DownloadPolicy.normalizeUrl(url));
        r.addOption("--no-playlist");
        r.addOption("--playlist-items", "1");
        r.addOption("--no-warnings");
        r.addOption("--socket-timeout", "30");
        r.addOption("--retries", "3");
        r.addOption("--fragment-retries", "3");
        // Do not disable TLS verification or supply cookies/login bypasses.
        return r;
    }
    public static String readableError(Throwable ex) {
        String raw = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("sign in") || lower.contains("login") || lower.contains("cookies"))
            return "المنصة تطلب تسجيل الدخول أو تمنع الوصول لهذا الرابط. جرّب رابطاً عاماً أو حدّث محرك التنزيل.";
        if (lower.contains("no space") || lower.contains("enospc")) return "المساحة المتاحة غير كافية. حرّر مساحة وأعد المحاولة.";
        if (lower.contains("timed out") || lower.contains("resolve") || lower.contains("network"))
            return "تعذر الاتصال. تأكد من الإنترنت ثم أعد المحاولة.";
        if (lower.contains("requested format")) return "الجودة المطلوبة غير متاحة. اختر أفضل جودة أو جودة أقل.";
        if (lower.contains("unsupported url")) return "هذا الرابط غير مدعوم حالياً من محرك التنزيل.";
        // Useful diagnostics, but never render these as HTML or log full source URLs.
        return raw.replaceAll("\\x1B\\[[0-9;]*m", "").substring(0, Math.min(450, raw.replaceAll("\\x1B\\[[0-9;]*m", "").length()));
    }
}
