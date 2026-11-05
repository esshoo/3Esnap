package com.snapclean.app;
public final class CategoryRulesSelfTest {
    public static void main(String[] args) {
        if(!"تعليم".equals(CategoryRules.suggest("شرح الرسم", "video"))) throw new AssertionError();
        if(!"رياضة".equals(CategoryRules.suggest("Football highlights", "video"))) throw new AssertionError();
        if(!"صوتيات".equals(CategoryRules.suggest("voice note", "audio"))) throw new AssertionError();
        if(CategoryRules.clean("x".repeat(80)).length()!=48) throw new AssertionError();
        System.out.println("PASS: category suggestions and bounds");
    }
}
