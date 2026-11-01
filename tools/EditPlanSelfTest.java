package com.snapclean.app;
import java.util.List;
public final class EditPlanSelfTest {
    private static int n;
    private static void test(boolean ok,String name) { n++; if(!ok) throw new AssertionError(name); }
    private static void bad(Runnable r) { boolean failed=false;try{r.run();}catch(IllegalArgumentException ex){failed=true;}test(failed,"expected rejection"); }
    public static void main(String[] args) {
        EditPlan.Clip clip=EditPlan.clip(1250,10250,20000);
        test(clip.lengthMs()==9000,"length");
        bad(()->EditPlan.clip(5000,5001,10000));
        bad(()->EditPlan.clip(-5,5000,10000));
        bad(()->EditPlan.clip(0,11000,10000));
        EditPlan.Crop crop=EditPlan.crop(.12f,.2f,.51f,.6f,1920,1080);
        test(crop.x%2==0 && crop.y%2==0 && crop.width%2==0 && crop.height%2==0,"even yuv dimensions");
        test(crop.x+crop.width<=1920 && crop.y+crop.height<=1080,"within frame");
        test(crop.filter().startsWith("crop=") && crop.filter().endsWith(",setsar=1"),"filter");
        bad(()->EditPlan.crop(.8f,.2f,.5f,.2f,1920,1080));
        bad(()->EditPlan.crop(Float.NaN,0,.5f,.5f,1920,1080));
        bad(()->EditPlan.crop(0,0,.001f,.001f,1920,1080));
        List<String> video=EditPlan.ffmpeg("/binary","/my; file.mp4","/out.mp4",clip,crop,false,false);
        test(video.contains("libx264") && video.contains(crop.filter()),"video and crop");
        test(video.contains("1.250") && video.contains("9.000"),"accurate timestamps");
        test(video.contains("/my; file.mp4") && !video.contains("sh"),"command array no shell");
        List<String> fallback=EditPlan.ffmpeg("/binary","/in","/out",clip,crop,false,true);
        test(fallback.contains("mpeg4") && !fallback.contains("libx264"),"fallback encoder");
        List<String> audio=EditPlan.ffmpeg("/binary","/in","/out.mp3",clip,null,true,false);
        test(audio.contains("libmp3lame") && audio.contains("-vn"),"audio re-encode");
        bad(()->EditPlan.ffmpeg("/ffmpeg","/in","/out.mp3",clip,crop,true,false));
        System.out.println("PASS: "+n+" media edit policy checks");
    }
}
