package com.snapclean.app;

import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java policy, shared by the bridge and service and tested without Android. */
public final class DownloadPolicy {
    private DownloadPolicy() {}
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'\\p{Cntrl}]+", Pattern.CASE_INSENSITIVE);

    public static String extractUrl(String text) {
        if (text == null) throw new IllegalArgumentException("الصق رابط الفيديو أولاً.");
        Matcher match = URL.matcher(text.trim());
        if (!match.find()) throw new IllegalArgumentException("لم أجد رابطاً صالحاً في النص.");
        return normalizeUrl(match.group().replaceAll("[),.!،؛\\]]+$", ""));
    }

    public static String normalizeUrl(String value) {
        try {
            if (value == null || value.length() > 8192) throw new Exception();
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || host == null || uri.getUserInfo() != null) throw new Exception();
            host = host.toLowerCase(Locale.ROOT);
            // Download sources must be public DNS names, not local addresses or file URLs.
            if (!host.contains(".") || host.endsWith(".") || host.matches("[0-9.]+")
                    || host.contains(":") || host.endsWith(".local") || host.endsWith(".localhost")
                    || host.endsWith(".internal") || (uri.getPort() != -1 && uri.getPort() != 443)) throw new Exception();
            String result = uri.toASCIIString();
            // Social-network share URLs occasionally use HTTP. Never pass cleartext to the engine.
            return "https" + result.substring(result.indexOf(':'));
        } catch (Exception ex) {
            throw new IllegalArgumentException("استخدم رابط HTTPS عاماً، وليس ملفاً محلياً أو عنوان شبكة داخلية.");
        }
    }

    public static String format(String value) {
        if (!"video".equals(value) && !"audio".equals(value))
            throw new IllegalArgumentException("صيغة التنزيل غير صالحة.");
        return value;
    }

    public static String quality(String type, String value) {
        format(type);
        if ("audio".equals(type)) return "192";
        if ("best".equals(value)) return value;
        try {
            int height = Integer.parseInt(value);
            if (height < 144 || height > 4320) throw new NumberFormatException();
            return Integer.toString(height);
        } catch (Exception ex) { throw new IllegalArgumentException("جودة الفيديو غير صالحة."); }
    }

    public static String videoSelector(String quality) {
        String clean = quality("video", quality);
        String limit = "best".equals(clean) ? "" : "[height<=?" + clean + "]";
        // Prefer H.264 + AAC for the built-in Android player. Fallbacks remain height-limited.
        return "bestvideo[vcodec^=avc1][ext=mp4]" + limit + "+bestaudio[ext=m4a]/"
                + "best[ext=mp4]" + limit + "/bestvideo[ext=mp4]" + limit
                + "+bestaudio[ext=m4a]/best" + limit;
    }

    public static String fileName(String title, String extension, long id) {
        if (!"mp4".equals(extension) && !"mp3".equals(extension))
            throw new IllegalArgumentException("Unsupported file extension");
        String base = title == null ? "3Esnap" : Normalizer.normalize(title, Normalizer.Form.NFC);
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", "_")
                .replaceAll("\\s+", " ").replaceAll("^[. ]+|[. ]+$", "");
        if (base.isEmpty()) base = "3Esnap";
        if (base.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) base = "3Esnap_" + base;
        int count = base.codePointCount(0, base.length());
        if (count > 80) base = base.substring(0, base.offsetByCodePoints(0, 80));
        // Android filesystem limits are bytes, not characters (emoji can occupy four bytes).
        while (base.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 180)
            base = base.substring(0, base.offsetByCodePoints(base.length(), -1));
        return base + "_" + id + "." + extension;
    }

    public static boolean canRetry(String status) {
        return "failed".equals(status) || "cancelled".equals(status);
    }

    public static boolean isActive(String status) {
        return "queued".equals(status) || "downloading".equals(status);
    }
}
