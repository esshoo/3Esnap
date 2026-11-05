package com.snapclean.app;

import java.nio.charset.StandardCharsets;

public final class PolicySelfTest {
    private static int checks;
    private static void check(boolean value,String name) { checks++;if(!value) throw new AssertionError(name); }
    private static void rejects(Runnable code,String name) {
        checks++;
        try { code.run();throw new AssertionError("Accepted invalid input: "+name); }
        catch(IllegalArgumentException expected) { /* expected */ }
    }
    public static void main(String[] args) {
        check(DownloadPolicy.normalizeUrl("https://youtu.be/abc").equals("https://youtu.be/abc"),"valid HTTPS");
        check(DownloadPolicy.normalizeUrl("http://example.com/video").equals("https://example.com/video"),"upgrade HTTP");
        check(DownloadPolicy.extractUrl("شوف الفيديو https://example.com/v?a=1&b=2 ").endsWith("?a=1&b=2"),"share URL extraction");
        check(DownloadPolicy.extractUrl("(https://example.com/abc).").equals("https://example.com/abc"),"share punctuation");
        check(DownloadPolicy.normalizeUrl("HTTPS://EXAMPLE.COM/path").equals("https://EXAMPLE.COM/path"),"case insensitive scheme");
        for(String value:new String[]{"file:///sdcard/a.mp4","javascript:alert(1)","https://localhost/a","https://127.0.0.1/a",
                "https://10.0.0.1/a","https://192.168.0.5/a","https://router.local/a","https://service.internal/a",
                "https://foo.localhost/a","https://[::1]/a","https://user:pass@example.com/a","https://example.com:8443/a",
                "https://example.com/\nmalicious", "example.com/no-scheme", "", "https://example.com./a"})
            rejects(()->DownloadPolicy.normalizeUrl(value),value);
        rejects(()->DownloadPolicy.normalizeUrl(null),"null URL");
        rejects(()->DownloadPolicy.extractUrl("no URL"),"no shared URL");
        check(DownloadPolicy.quality("audio","garbage").equals("192"),"fixed audio bitrate");
        check(DownloadPolicy.quality("video","720").equals("720"),"video height");
        rejects(()->DownloadPolicy.quality("video","99999"),"unbounded height");
        rejects(()->DownloadPolicy.quality("video","720; rm -rf /"),"format injection");
        rejects(()->DownloadPolicy.format("ig_audio"),"legacy fake MP3 path");
        String selector=DownloadPolicy.videoSelector("720");
        check(selector.endsWith("best[height<=?720]"),"no unlimited fallback");
        check(!selector.endsWith("/best"),"height enforced at fallback");
        check(DownloadPolicy.videoSelector("best").contains("avc1"),"prefer H264");
        String name=DownloadPolicy.fileName("../درس: جميل/فيديو","mp4",42);
        check(!name.contains("/")&&!name.contains(":")&&!name.contains(".."),"safe filename");
        check(name.endsWith("_42.mp4"),"stable unique suffix");
        check(DownloadPolicy.fileName("CON","mp3",4).startsWith("3Esnap_CON"),"reserved filename");
        check(DownloadPolicy.fileName("😀".repeat(200),"mp4",1).getBytes(StandardCharsets.UTF_8).length<=210,"byte-aware filename bound");
        rejects(()->DownloadPolicy.fileName("x","exe",1),"unknown extension");
        check(DownloadPolicy.fileName("صوت مستورد","m4a",21).endsWith("_21.m4a"),"vault preserves imported audio formats");
        check(DownloadPolicy.fileName("مقطع","webm",22).endsWith("_22.webm"),"vault preserves imported video formats");
        check(DownloadPolicy.canRetry("failed")&&DownloadPolicy.canRetry("cancelled"),"retry terminal errors");
        check(!DownloadPolicy.canRetry("completed")&&!DownloadPolicy.canRetry("downloading"),"no duplicate retry");
        check(DownloadPolicy.isActive("queued")&&DownloadPolicy.isActive("downloading"),"active state");
        check(!DownloadPolicy.isActive("completed"),"completed state");
        System.out.println("PASS: "+checks+" Java policy assertions (real compilation, no Android dependencies).");
    }
}
