package com.snapclean.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadPolicyTest {
    @Test public void acceptsPublicUrl() { assertEquals("https://example.com/video?a=1",DownloadPolicy.normalizeUrl("https://example.com/video?a=1")); }
    @Test public void upgradesPlainHttp() { assertEquals("https://example.com/a",DownloadPolicy.normalizeUrl("http://example.com/a")); }
    @Test(expected=IllegalArgumentException.class) public void rejectsJavascript() { DownloadPolicy.normalizeUrl("javascript:alert(1)"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsPrivateAddress() { DownloadPolicy.normalizeUrl("https://192.168.1.1/a"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsCredentials() { DownloadPolicy.normalizeUrl("https://admin:pass@example.com/a"); }
    @Test public void extractsSharedUrl() { assertEquals("https://example.com/a",DownloadPolicy.extractUrl("عنوان الفيديو https://example.com/a")); }
    @Test public void filenameCannotEscapeDirectory() { String n=DownloadPolicy.fileName("../../title:x","mp4",7);assertFalse(n.contains("/"));assertFalse(n.contains(":"));assertTrue(n.endsWith("_7.mp4")); }
    @Test public void qualityFallbackRemainsBounded() { assertTrue(DownloadPolicy.videoSelector("480").endsWith("best[height<=?480]")); }
    @Test public void cannotRetryCompleted() { assertFalse(DownloadPolicy.canRetry("completed"));assertTrue(DownloadPolicy.canRetry("failed")); }
    @Test(expected=IllegalArgumentException.class) public void rejectsFormatInjection() { DownloadPolicy.quality("video","720 --exec bad"); }
}
