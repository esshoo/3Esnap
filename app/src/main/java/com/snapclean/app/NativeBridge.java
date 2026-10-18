package com.snapclean.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NativeBridge {

    private static final String TAG = "NativeBridge";
    private final Activity activity;
    private final WebView webView;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public NativeBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void inspectUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            sendErrorToWeb("الرابط مطلوب");
            return;
        }

        executor.execute(() -> {
            String trimmedUrl = url.trim();
            Log.d(TAG, "Inspecting URL on-device: " + trimmedUrl);

            // Special handling for Instagram to bypass Meta's login wall
            if (isInstagramUrl(trimmedUrl)) {
                JSONObject igResult = inspectInstagramEmbed(trimmedUrl);
                if (igResult != null) {
                    sendInspectResultToWeb(igResult.toString());
                    return;
                }
            }

            try {
                Log.d(TAG, "Inspecting URL via YoutubeDL engine: " + trimmedUrl);
                YoutubeDLRequest request = new YoutubeDLRequest(trimmedUrl);
                request.addOption("--no-warnings");
                request.addOption("--no-check-certificates");

                VideoInfo info = YoutubeDL.getInstance().getInfo(request);
                if (info == null) {
                    // Try Instagram fallback if not already tried
                    if (isInstagramUrl(trimmedUrl)) {
                        JSONObject igResult = inspectInstagramEmbed(trimmedUrl);
                        if (igResult != null) {
                            sendInspectResultToWeb(igResult.toString());
                            return;
                        }
                    }
                    sendErrorToWeb("تعذر استخراج معلومات الفيديو.");
                    return;
                }

                JSONObject result = new JSONObject();
                result.put("title", info.getTitle() != null ? info.getTitle() : "فيديو بدون عنوان");
                result.put("thumbnail", info.getThumbnail() != null ? info.getThumbnail() : "");
                result.put("duration", formatDuration(info.getDuration()));
                result.put("uploader", info.getUploader() != null ? info.getUploader() : "غير معروف");
                result.put("platform", info.getExtractor() != null ? info.getExtractor() : "منصة مدعومة");
                result.put("url", trimmedUrl);

                JSONArray options = new JSONArray();

                // MP3 Audio option
                JSONObject mp3Opt = new JSONObject();
                mp3Opt.put("id", "mp3-best");
                mp3Opt.put("type", "audio");
                mp3Opt.put("quality", "mp3");
                mp3Opt.put("label", "ملف صوتي MP3");
                mp3Opt.put("badge", "صوت عالي الجودة");
                mp3Opt.put("ext", "mp3");
                options.put(mp3Opt);

                // Video best option
                JSONObject bestOpt = new JSONObject();
                bestOpt.put("id", "video-best");
                bestOpt.put("type", "video");
                bestOpt.put("quality", "best");
                bestOpt.put("label", "أعلى جودة متوافقة (HD MP4)");
                bestOpt.put("badge", "موصى به للهاتف");
                bestOpt.put("ext", "mp4");
                options.put(bestOpt);

                // 720p option
                JSONObject hd720 = new JSONObject();
                hd720.put("id", "video-720");
                hd720.put("type", "video");
                hd720.put("quality", "720");
                hd720.put("label", "فيديو 720p HD");
                hd720.put("badge", "720p");
                hd720.put("ext", "mp4");
                options.put(hd720);

                // 480p option
                JSONObject sd480 = new JSONObject();
                sd480.put("id", "video-480");
                sd480.put("type", "video");
                sd480.put("quality", "480");
                sd480.put("label", "فيديو 480p SD");
                sd480.put("badge", "480p");
                sd480.put("ext", "mp4");
                options.put(sd480);

                result.put("options", options);

                sendInspectResultToWeb(result.toString());

            } catch (Exception e) {
                Log.e(TAG, "Inspect failed: " + e.getMessage(), e);
                // Instagram fallback on error
                if (isInstagramUrl(trimmedUrl)) {
                    JSONObject igResult = inspectInstagramEmbed(trimmedUrl);
                    if (igResult != null) {
                        sendInspectResultToWeb(igResult.toString());
                        return;
                    }
                }
                sendErrorToWeb("فشل فحص الفيديو: " + e.getMessage());
            }
        });
    }

    private boolean isInstagramUrl(String url) {
        return url != null && (url.contains("instagram.com") || url.contains("instagr.am"));
    }

    private JSONObject inspectInstagramEmbed(String url) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("/(?:reel|reels|p)/([A-Za-z0-9_-]+)");
            java.util.regex.Matcher m = p.matcher(url);
            if (!m.find()) return null;
            String shortcode = m.group(1);

            String embedUrl = "https://www.instagram.com/p/" + shortcode + "/embed/";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(embedUrl).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            String html = sb.toString();

            // Extract direct video CDN URL (clean escaped slashes and unicode)
            String cleanMp4 = null;
            int mp4Idx = html.indexOf(".mp4");
            if (mp4Idx != -1) {
                int start = html.lastIndexOf("https:", mp4Idx);
                if (start != -1) {
                    int end = html.indexOf("\\\"", mp4Idx);
                    if (end == -1) end = html.indexOf("\"", mp4Idx);
                    if (end == -1) end = html.indexOf("'", mp4Idx);
                    if (end != -1 && end > start) {
                        String raw = html.substring(start, end);
                        cleanMp4 = raw.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "").trim();
                    }
                }
            }

            if (cleanMp4 == null) {
                java.util.regex.Pattern mp4Pattern = java.util.regex.Pattern.compile("https[^\\s\"\'<>]+\\.mp4[^\\s\"\'<>]*");
                java.util.regex.Matcher mp4Matcher = mp4Pattern.matcher(html.replace("\\/", "/"));
                if (mp4Matcher.find()) {
                    cleanMp4 = mp4Matcher.group(0).replace("\\u0026", "&").replace("\\", "").trim();
                }
            }

            if (cleanMp4 == null) {
                Log.w(TAG, "No mp4 found in Instagram embed");
                return null;
            }

            // Extract thumbnail
            String thumb = "";
            java.util.regex.Pattern posterPattern = java.util.regex.Pattern.compile("class=\"EmbeddedMediaImage\"[^>]+src=\"([^\"]+)\"");
            java.util.regex.Matcher posterMatcher = posterPattern.matcher(html);
            if (posterMatcher.find()) {
                thumb = posterMatcher.group(1).replace("&amp;", "&");
            }

            JSONObject result = new JSONObject();
            result.put("title", "Instagram Reel (" + shortcode + ")");
            result.put("thumbnail", thumb);
            result.put("duration", "00:30");
            result.put("uploader", "Instagram");
            result.put("platform", "Instagram");
            result.put("url", url);

            JSONArray options = new JSONArray();

            JSONObject videoOpt = new JSONObject();
            videoOpt.put("id", "ig-video");
            videoOpt.put("type", "ig_direct");
            videoOpt.put("quality", cleanMp4);
            videoOpt.put("label", "فيديو عالي الجودة (HD MP4)");
            videoOpt.put("badge", "تحميل فوري");
            videoOpt.put("ext", "mp4");
            options.put(videoOpt);

            JSONObject audioOpt = new JSONObject();
            audioOpt.put("id", "ig-audio");
            audioOpt.put("type", "ig_audio");
            audioOpt.put("quality", cleanMp4);
            audioOpt.put("label", "ملف صوتي MP3");
            audioOpt.put("badge", "صوت نقي");
            audioOpt.put("ext", "mp3");
            options.put(audioOpt);

            result.put("options", options);
            Log.d(TAG, "Successfully extracted direct Instagram media URL!");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Instagram embed: " + e.getMessage(), e);
            return null;
        }
    }

    @JavascriptInterface
    public void startDownload(String url, String formatType, String quality) {
        executor.execute(() -> {
            try {
                Log.d(TAG, "Starting download on-device: " + url + " | " + formatType);

                // Handle direct Instagram stream download
                if ("ig_direct".equals(formatType) || "ig_audio".equals(formatType)) {
                    downloadDirectMedia(quality, "ig_audio".equals(formatType), "Instagram_Reel_" + System.currentTimeMillis());
                    return;
                }

                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }

                String taskId = "dl_" + System.currentTimeMillis();
                File tempDir = new File(activity.getCacheDir(), taskId);
                tempDir.mkdirs();

                updateDownloadProgress(10, "جاري تحضير المحرك المدمج في الهاتف...");

                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("-o", new File(tempDir, "%(title).50s.%(ext)s").getAbsolutePath());
                request.addOption("--no-check-certificates");

                if ("audio".equals(formatType)) {
                    request.addOption("-x");
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", "192K");
                } else {
                    if ("best".equals(quality)) {
                        request.addOption("-f", "bestvideo[vcodec^=avc1][height<=1080]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best");
                    } else {
                        try {
                            int h = Integer.parseInt(quality);
                            request.addOption("-f", "bestvideo[vcodec^=avc1][height<=" + h + "]+bestaudio[ext=m4a]/bestvideo[height<=" + h + "]+bestaudio/best[height<=" + h + "]/best");
                        } catch (Exception ex) {
                            request.addOption("-f", "bestvideo[vcodec^=avc1]+bestaudio/best");
                        }
                    }
                    request.addOption("--merge-output-format", "mp4");
                }

                YoutubeDL.getInstance().execute(request, taskId, (progress, etaInSeconds, line) -> {
                    if (progress != null) {
                        int p = progress.intValue();
                        if (p > 10 && p < 99) {
                            updateDownloadProgress(p, "السرعة: جاري التنزيل المباشر (" + p + "%)");
                        }
                    }
                    return kotlin.Unit.INSTANCE;
                });

                updateDownloadProgress(99, "جاري دمج الملف وحفظه في التنزيلات...");

                // Find saved file in tempDir
                File[] files = tempDir.listFiles();
                if (files != null && files.length > 0) {
                    File producedFile = files[0];
                    String targetName = producedFile.getName().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                    boolean saved = saveToPublicDownloads(producedFile, targetName, "audio".equals(formatType));
                    if (saved) {
                        try {
                            producedFile.delete();
                            tempDir.delete();
                        } catch (Exception ignored) {}
                        notifyDownloadComplete(targetName);
                    } else {
                        sendDownloadFailed("تعذر حفظ الملف في مجلد التنزيلات.");
                    }
                } else {
                    sendDownloadFailed("لم يتم العثور على الملف بعد التنزيل.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage(), e);
                sendDownloadFailed("فشل التنزيل: " + e.getMessage());
            }
        });
    }

    private void downloadDirectMedia(String directUrl, boolean isAudio, String baseName) {
        try {
            updateDownloadProgress(15, "الاتصال المباشر بسيرفرات الفيديو...");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(directUrl).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                sendDownloadFailed("فشل الاتصال برابط الفيديو (كود: " + responseCode + ")");
                return;
            }

            int contentLength = conn.getContentLength();
            String extension = isAudio ? ".mp3" : ".mp4";
            String finalFileName = baseName + extension;

            File tempFile = new File(activity.getCacheDir(), "ig_temp_" + System.currentTimeMillis() + extension);
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {

                byte[] buffer = new byte[16384];
                int bytesRead;
                long totalBytesRead = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    if (contentLength > 0) {
                        int progress = (int) ((totalBytesRead * 80) / contentLength) + 15;
                        updateDownloadProgress(progress, "جاري التنزيل المباشر (" + progress + "%)...");
                    }
                }
                out.flush();
            }

            updateDownloadProgress(98, "جاري حفظ الملف في مجلد التنزيلات...");
            boolean saved = saveToPublicDownloads(tempFile, finalFileName, isAudio);
            try { tempFile.delete(); } catch (Exception ignored) {}

            if (saved) {
                notifyDownloadComplete(finalFileName);
            } else {
                sendDownloadFailed("تعذر حفظ ملف إنستغرام في مجلد التنزيلات.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Direct download failed: " + e.getMessage(), e);
            sendDownloadFailed("فشل تنزيل إنستغرام: " + e.getMessage());
        }
    }

    private boolean saveToPublicDownloads(File sourceFile, String displayName, boolean isAudio) {
        // First try standard File move if direct write permitted
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dest = new File(downloadDir, displayName);
            if (sourceFile.renameTo(dest)) {
                notifyMediaScanner(dest);
                return true;
            }
        } catch (Exception ignored) {}

        // Use Android MediaStore API (Standard for Android 10, 11, 12, 13+)
        try {
            android.content.ContentResolver resolver = activity.getContentResolver();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, isAudio ? "audio/mpeg" : "video/mp4");
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            }

            Uri collectionUri;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                collectionUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                collectionUri = isAudio ? android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }

            Uri itemUri = resolver.insert(collectionUri, values);
            if (itemUri != null) {
                try (java.io.InputStream in = new java.io.FileInputStream(sourceFile);
                     java.io.OutputStream out = resolver.openOutputStream(itemUri)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    out.flush();
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(itemUri, values, null, null);
                }

                Log.d(TAG, "Successfully saved via MediaStore to: " + itemUri);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore insert error: " + e.getMessage(), e);
        }

        // Fallback: try raw file stream copy to Downloads directory
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dest = new File(downloadDir, displayName);
            try (java.io.InputStream in = new java.io.FileInputStream(sourceFile);
                 java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
                out.flush();
            }
            notifyMediaScanner(dest);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Raw file copy fallback error: " + e.getMessage(), e);
        }

        return false;
    }

    private void notifyMediaScanner(File file) {
        try {
            android.media.MediaScannerConnection.scanFile(
                    activity,
                    new String[]{file.getAbsolutePath()},
                    null,
                    (path, uri) -> Log.d(TAG, "Scanned into media gallery: " + path)
            );
        } catch (Exception ignored) {}
    }

    private void sendInspectResultToWeb(String jsonString) {
        activity.runOnUiThread(() -> {
            String script = "window.renderVideoData && window.renderVideoData(" + jsonString + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private void sendErrorToWeb(String msg) {
        activity.runOnUiThread(() -> {
            String script = "window.showAlert && window.showAlert('" + escapeJs(msg) + "');";
            webView.evaluateJavascript(script, null);
        });
    }

    private void updateDownloadProgress(int percent, String statusText) {
        activity.runOnUiThread(() -> {
            String script = "window.updateNativeProgress && window.updateNativeProgress(" + percent + ", '" + escapeJs(statusText) + "');";
            webView.evaluateJavascript(script, null);
        });
    }

    private void notifyDownloadComplete(String filename) {
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, "تم حفظ الملف بنجاح: " + filename, Toast.LENGTH_LONG).show();
            String script = "window.onNativeDownloadReady && window.onNativeDownloadReady('" + escapeJs(filename) + "');";
            webView.evaluateJavascript(script, null);
        });
    }

    private void sendDownloadFailed(String error) {
        activity.runOnUiThread(() -> {
            String script = "window.onNativeDownloadFailed && window.onNativeDownloadFailed('" + escapeJs(error) + "');";
            webView.evaluateJavascript(script, null);
        });
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) return "00:00";
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        long h = seconds / 3600;
        if (h > 0) {
            return String.format("%02d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private String escapeJs(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }
}
