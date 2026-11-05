# رفع تحديث 3Esnap v2.3.0 وتشغيل GitHub Actions

## قبل الرفع

- احتفظ بنسخة من ملف JSON وروابطك. **إذا خزّنت ملفات في تبويب الخزنة لاحقًا فلا تحذف التطبيق مطلقًا دون إخراج الملفات إلى المكتبة العامة أولًا.**
- لا تغيّر `applicationId` ولا مفاتيح التوقيع ولا تحذف مجلد `.git`.
- استخدم حزمة `3Esnap-v2.3.0-PATCH.zip` وليس حزمة المصدر الموسعة إذا كانت لديك نسخة المستودع محدثة بالفعل.

## باستخدام GitHub Desktop

1. افتح مستودع `esshoo/3Esnap` واستعمل `Fetch origin` ثم `Pull origin` للحصول على آخر نسخة.
2. افتح مجلد المستودع المحلي (`Repository → Show in Explorer`).
3. فك ضغط `3Esnap-v2.3.0-PATCH.zip` **داخل جذر المستودع** بجوار `app/` و`README.md`، ووافق على استبدال الملفات القديمة. لا تنشئ مجلدًا إضافيًا باسم PATCH.
4. راجع التغييرات ثم نفذ `Commit to main` و`Push origin`.
5. افتح GitHub Actions ← `Build 3Esnap APK` ← التشغيل الجديد؛ انتظر **نجاح** جميع خطوات Java/JUnit وLint و`assembleDebug` قبل تنزيل APK.

## باستخدام PowerShell

```powershell
git status
git add .
git commit -m "Add encrypted vault, auto retry, GIF and media categories"
git push origin main
```

بعد نجاح التشغيل: `Actions → Build 3Esnap APK → Artifacts → 3Esnap-debug-APK-<run_number>`. حمّل APK الـUniversal أو ملف arm64 المناسب لهاتفك.

## التأكد من قابلية التحديث

تحديث Debug لا ينجح فوق Release حتى لو الاسم نفسه، لأنهما معرفان مختلفان. فوق نفس نوع التطبيق، يحتاج Android نفس **مفتاح التوقيع**. إذا رفض التحديث **لا تحذف التطبيق إن كانت به ملفات مخفية**؛ اخرج الملفات من الخزنة أولًا باستخدام النسخة المثبتة، وانسخ ملفاتك العامة، وصدّر JSON. توقيع Debug المبني عبر GitHub Cache ليس ضمانًا دائمًا للتحديثات؛ يفضل إعداد Release keystore ثابت في GitHub Secrets، وحفظ نسخة آمنة من المفتاح في مكان منفصل.

## قبل اعتبار الإصدار جاهزًا

اختبر الخزنة والمصادقة والحفظ والإرجاع على هاتف حقيقي، والتنزيل التلقائي في الخلفية وقطع الشبكة، وGIF، والتصنيفات. CI وحده لا يختبر التصاريح أو مفاتيح Android Keystore أو نتيجة تقليل الموسيقى.
