package com.snapclean.app;

public final class BackupRulesSelfTest {
    private static int count;
    private static void check(boolean ok,String name) {count++;if(!ok)throw new AssertionError(name);}
    private static void rejects(Runnable r,String name) {
        boolean failed=false;try {r.run();}catch(IllegalArgumentException expected){failed=true;}
        check(failed,name);
    }
    public static void main(String[] ignored) {
        check(BackupRules.MAX_BYTES==12*1024*1024,"input byte cap");
        check(BackupRules.MAX_ITEMS==15000,"item cap");
        check("video".equals(BackupRules.format("video","example.mp3")),"explicit format");
        check("audio".equals(BackupRules.format("", "file.mp3")),"v1 audio fallback");
        check("audio".equals(BackupRules.format("n/a", "file.m4a")),"m4a fallback");
        check("video".equals(BackupRules.format("","file.mp4")),"v1 video fallback");
        check("192".equals(BackupRules.quality("audio","")),"audio quality v1 fallback");
        check("best".equals(BackupRules.quality("video","edited")),"edited default re-download quality");
        check("720".equals(BackupRules.quality("video","720")),"preserve quality in v2");
        check("".equals(BackupRules.safeUrl(" ")),"empty original link");
        check("https://example.com/watch?a=1".equals(BackupRules.safeUrl("https://example.com/watch?a=1")),"safe public URL");
        rejects(()->BackupRules.safeUrl("file:///sdcard/Download/file.mp4"),"reject local filesystem URI");
        rejects(()->BackupRules.safeUrl("http://127.0.0.1:8000"),"reject internal addresses");
        check(BackupRules.timestamp(99,100)==99,"preserve original date");
        check(BackupRules.timestamp(99999999,100)==100,"reject impossible future timestamp");
        check(BackupRules.label(" a\tb ",80).length()>=3,"allow unicode titles");
        check(BackupRules.label("ABCDEFGHI",4).equals("ABCD"),"bound display labels");
        System.out.println("PASS: "+count+" backup restoration rules checks");
    }
}
