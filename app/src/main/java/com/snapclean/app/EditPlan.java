package com.snapclean.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Platform-free validation, crop coordinate math, and FFmpeg arguments. */
public final class EditPlan {
    private EditPlan() {}
    public static final int MIN_CLIP_MS = 500;

    public static final class Clip {
        public final int startMs, endMs;
        private Clip(int startMs, int endMs) { this.startMs=startMs; this.endMs=endMs; }
        public int lengthMs() { return endMs-startMs; }
    }
    public static Clip clip(int startMs, int endMs, int durationMs) {
        if (durationMs<=0 || startMs<0 || endMs>durationMs || endMs-startMs<MIN_CLIP_MS)
            throw new IllegalArgumentException("حدد بداية ونهاية بفارق نصف ثانية على الأقل داخل مدة الملف.");
        return new Clip(startMs,endMs);
    }

    public static final class Crop {
        public final int x,y,width,height;
        public Crop(int x,int y,int width,int height) {
            if (x<0 || y<0 || width<2 || height<2 || (x|y|width|height)%2!=0)
                throw new IllegalArgumentException("أبعاد القص غير صحيحة.");
            this.x=x;this.y=y;this.width=width;this.height=height;
        }
        public String filter() { return "crop="+width+":"+height+":"+x+":"+y+",setsar=1"; }
    }
    /** Normalised crop in DISPLAY orientation, rounded down to even YUV420 pixels. */
    public static Crop crop(float x,float y,float width,float height,int videoW,int videoH) {
        if(videoW<16 || videoH<16 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)
                || x<0 || y<0 || width<=0 || height<=0 || x+width>1.0001f || y+height>1.0001f)
            throw new IllegalArgumentException("حدد مساحة صحيحة داخل إطار الفيديو.");
        int px=Math.max(0,Math.min(even((int)Math.floor(x*videoW)),even(videoW)-2));
        int py=Math.max(0,Math.min(even((int)Math.floor(y*videoH)),even(videoH)-2));
        int w=Math.min(even((int)Math.floor(width*videoW)),even(videoW)-px);
        int h=Math.min(even((int)Math.floor(height*videoH)),even(videoH)-py);
        if(w<16 || h<16) throw new IllegalArgumentException("مساحة القص صغيرة جداً.");
        return new Crop(px,py,w,h);
    }
    private static int even(int value) { return value & ~1; }
    private static String seconds(int ms) { return String.format(Locale.US,"%.3f",ms/1000.0); }

    /** Lists are passed directly to ProcessBuilder, never to a shell. */
    public static List<String> ffmpeg(String binary,String source,String destination,Clip clip,Crop crop,
                                       boolean audio,boolean fallbackEncoder) {
        if(binary==null || binary.isEmpty() || source==null || source.isEmpty() || destination==null || destination.isEmpty() || clip==null)
            throw new IllegalArgumentException("بيانات القص غير مكتملة.");
        if(audio && crop!=null) throw new IllegalArgumentException("قص الإطار متاح للفيديو فقط.");
        List<String> args=new ArrayList<>();
        Collections.addAll(args,binary,"-hide_banner","-nostdin","-loglevel","error","-y",
                "-ss",seconds(clip.startMs),"-i",source,"-t",seconds(clip.lengthMs()));
        if(audio) {
            Collections.addAll(args,"-map","0:a:0","-vn","-c:a","libmp3lame","-b:a","192k","-id3v2_version","3");
        } else {
            Collections.addAll(args,"-map","0:v:0","-map","0:a:0?");
            if(crop!=null) Collections.addAll(args,"-vf",crop.filter());
            if(fallbackEncoder) Collections.addAll(args,"-c:v","mpeg4","-q:v","4");
            else Collections.addAll(args,"-c:v","libx264","-preset","veryfast","-crf","23");
            Collections.addAll(args,"-pix_fmt","yuv420p","-c:a","aac","-b:a","160k","-movflags","+faststart","-threads","2");
        }
        args.add(destination);
        return Collections.unmodifiableList(args);
    }
}
