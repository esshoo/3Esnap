package com.snapclean.app;

/** Pure-Java validation of portable library backups. Never restores device-specific URIs. */
public final class BackupRules {
    private BackupRules() {}
    public static final int MAX_BYTES=12*1024*1024;
    public static final int MAX_ITEMS=15000;
    public static String label(String value, int limit) {
        if(value==null) return "";
        String clean=value.trim().replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
        return clean.length()>limit?clean.substring(0,limit):clean;
    }
    public static String format(String value, String filename) {
        if("audio".equals(value) || "video".equals(value)) return value;
        String lower=filename==null?"":filename.toLowerCase(java.util.Locale.ROOT);
        if(lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav")) return "audio";
        return "video";
    }
    public static String quality(String format, String quality) {
        try {return DownloadPolicy.quality(format,quality);} catch(IllegalArgumentException ex) {
            return "audio".equals(format)?"192":"best";
        }
    }
    public static long timestamp(long value,long now) {
        return value>0 && value<=now+86400000L ? value : now;
    }
    public static String safeUrl(String value) {
        if(value==null || value.trim().isEmpty()) return "";
        return DownloadPolicy.normalizeUrl(value);
    }
}
