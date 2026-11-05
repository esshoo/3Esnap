package com.snapclean.app;

import java.util.Locale;

/** Local, deterministic category suggestions. No data leaves the phone. */
public final class CategoryRules {
    private CategoryRules() {}
    public static String clean(String value) {
        if(value==null) return "";
        String result=value.trim().replaceAll("[\\p{Cntrl}]", "");
        if(result.length()>48) result=result.substring(0,48);
        return result;
    }
    public static String suggest(String title,String format) {
        String t=(title==null?"":title).toLowerCase(Locale.ROOT);
        if(t.matches("(?s).*(tutorial|course|learn|شرح|درس|تعليم|lecture|محاضرة).*")) return "تعليم";
        if(t.matches("(?s).*(gaming|gameplay|game|ألعاب|لعبة|قيمينج).*")) return "ألعاب";
        if(t.matches("(?s).*(football|sport|match|رياضة|مباراة|كرة قدم).*")) return "رياضة";
        if(t.matches("(?s).*(news|breaking|أخبار|خبر|نشرة).*")) return "أخبار";
        if(t.matches("(?s).*(song|music|أغنية|اغنيه|موسيقى|music video).*")) return "موسيقى";
        return "audio".equals(format)?"صوتيات":("gif".equals(format)?"صور متحركة":"عام");
    }
}
