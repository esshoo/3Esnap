## v2.3.0 — Encrypted Vault, Network-Aware Downloads, GIF and Categories

- Biometric-gated private vault for downloaded/imported MP3/MP4/GIF. Actual AES-256-GCM encryption in private app files with Android Keystore key. The public file is removed only after encrypted copy is complete; imported SAF files require provider deletion permission or hiding fails safely. Vault contents excluded from regular library snapshots and exported JSON.
- Durable WorkManager media jobs with network constraint, bounded exponential retry, foreground notification, optional auto-download ~3s after a successful inspect; supports running while app is not visible subject to OS limits. Interrupted or network-waiting jobs are recovered on app launch; automatic retry may re-download bytes from the start.
- GIF export (maximum 15 seconds) from trimmed/cropped video with palette optimized FFmpeg, saved/shareable as image/gif, original unaffected.
- Experimental speech-band filtering (highpass/lowpass/afftdn), NOT reliable instrumental/vocal stem separation; works on audio or video's audio track.
- Local heuristic category suggestions, manual category editing, filter and search for media files; DB migrated additively from schema v1 to v2.
- GIF library import, thumbnails, viewing and backup round-trip. Preserves common imported M4A/WebM/WAV media extensions on vault restore, verifies AES-GCM authentication tags before preview. Fixed pending-network cancellation/retry/duplicate checks and public metadata after vault restore.
- Android API 24 compatibility preserved, same app ID/build signing configuration; patch does not contain signing keys.
- JVM/source/Chromium mocked UI and synthetic FFmpeg tests passed; native Android APK compilation and device behavior remain unverified in this delivery.

## v2.2.0 — Restore & Crop UI
- Import exported v1/v2 JSON backups, merge records safely without deleting existing data, and preserve source links as re-downloadable placeholders.
- Automatically attach old media selected via Android picker to matching restored records by filename and format.
- Separate, interactive crop screen with a still-frame scrubber, four draggable handles, and Free / 1:1 / 9:16 / 16:9 presets.
- Keep video temporal trim + FFmpeg crop export together; source file and source URL are retained.
- Application versionCode increased for forwards updates; documented Android signing/package identity and stable Release signing.

# 3Esnap — إصدار 2.1.0 (تطوير المصدر)

## إصلاح تجربة الشاشات الصغيرة

- ضبط WebView واحترام `width=device-width`، وإصلاح min-width لقوائم وبطاقات Flex/Grid مع دعم أسطر وعناوين طويلة.
- شاشة نتائج التنزيل أصبحت مركزة: إخفاء مقدمة التطبيق ومربع الرابط أثناء عرض النتائج، مع زر **رابط جديد**. القائمة والتقدم لا يدفعان المحتوى خارج حدود الشاشة.
- إضافة اختبار تصفح عند 320/360/390/412px يتضمن عنوانًا عربيًا طويلًا وتنزيلًا نشطًا.

## محرر المقاطع (الميزة الجديدة)

- زر **قص** داخل مشغل الصوت/الفيديو وخيار **قص المقطع** من قائمة ملف المكتبة.
- تحديد بداية ونهاية القص، المعاينة، وتعيين المدى من موضع التشغيل الحالي.
- قص الفيديو مكانيًا عبر مستطيل متحرك قابل لتغيير الحجم، مع نسب حر و1:1 و9:16 و16:9.
- معالجة الوسائط محليًا باستخدام FFmpeg المرفق أصلًا مع التطبيق؛ تصدير MP3 أو MP4 دون تعديل الملف الأصلي.
- **قص وحفظ** ينشئ ملفًا جديدًا في Downloads/3Esnap وسجلًا منفصلًا في المكتبة، محتفظًا برابط المصدر. **قص ومشاركة** يحفظ ثم يفتح المشاركة بالملف الناتج وحده.
- تنظيف الملفات المؤقتة بعد النجاح أو الفشل أو الإلغاء؛ حذف ملفات عمليات القص المتروكة منذ أكثر من 24 ساعة في تشغيل لاحق.
- اختبارات خوارزمية القص والإحداثيات وأوامر FFmpeg بدون Android SDK، بالإضافة إلى اختبارات JUnit وقت بناء APK في GitHub Actions.

> **حالة التحقق:** هذه ميزات مصدر جديدة. نُفّذت اختبارات UI محاكاة وخوارزميات FFmpeg واختبار ترميز مصطنع على FFmpeg للحاسوب؛ لا يوجد تأكيد تشغيل Android الفعلي حتى نجاح CI وتجربة الهاتف.

---

# تغييرات 2.0.0 — حزمة المصدر

## المطلوب الأساسي

هوية 3Esnap وأيقونة 3E vector؛ تبويب ملفات محلية مع شبكة/قائمة؛ مشغل صوت وفيديو؛ مشاركة محتوى الملف؛ تسجيل رابط المصدر في SQLite مع نسخ/فتح/مشاركة/تعديل؛ workflow لبناء APK وتحميله من GitHub Actions.

## إضافات عملية

بحث وتصفية وفرز؛ استيراد ملفات قديمة؛ صور مصغرة محلية؛ طابور متتابع بإشعار تقدم وإلغاء وإعادة محاولة؛ منع التكرار النشط؛ كشف الملفات المفقودة؛ تأكيد الحذف؛ خيار إزالة السجل دون حذف الملف؛ تصدير روابط JSON؛ تحديث yt-dlp يدويًا؛ وضع فاتح/داكن/حسب النظام؛ حفظ تفضيلات العرض؛ شاشة فارغة وتلميحات عربية؛ وصول بلوحة المفاتيح للقوائم.

## إصلاحات المصدر القديم

- إزالة مسار Instagram الذي كان ينسخ الفيديو باسم MP3 دون تحويله؛ الاستخراج الصوتي الآن يمر عبر yt-dlp/FFmpeg.
- إزالة `--no-check-certificates` وعدم استخدام حيلة التفاف تسجيل الدخول في Instagram.
- إزالة إعدادات سيرفر الكمبيوتر وHTTP الاحتياطية غير اللازمة للتطبيق المستقل.
- تحميل الواجهة محليًا عبر WebViewAssetLoader، ومنع الصفحات الخارجية والجسور عليها، وقصر مشاركة الملفات على content URI وصلاحية قراءة مؤقتة.
- عدم استخدام `files[0]` لتحديد الناتج؛ انتظار الملف النهائي بالصيغة المطلوبة.
- حفظ آمن عبر MediaStore وIS_PENDING، مع تنظيف الناتج عند فشل الحفظ/الإلغاء.
- معالجة صلاحية التخزين على Android 7–9 وصلاحية الإشعارات على Android 13+؛ لا يوجد طلب للوصول إلى كل ملفات الهاتف.
- معالجة بدء المحرك المتزامن وفصل قفل تحديث المحرك عن استخدامه، وتحويل الحالات المعلقة بعد إعادة تشغيل العملية إلى إعادة محاولة واضحة.
- حماية الإلغاء/إعادة المحاولة من بدء محاولة جديدة لنفس السجل أثناء إغلاق المحاولة السابقة.
- عدم تقديم مدد أو جودات ثابتة على أنها بيانات فعلية للرابط.

## البناء

JDK17، Gradle8.11.1، AGP8.9.2، compile/target35، min24. الاحتفاظ بمحرك youtubedl-android0.18.1 وFFmpeg0.18.1 الموجودين في الأساس لتقليل تغيير واجهاتهما. مخرجات مستقلة حسب ABI وUniversal، توقيع Debug تلقائي، توقيع Release اختياري عبر Secrets، بصمات SHA-256، وتقارير اختبارات.

## ما لم يحدث

لم يُرفع commit أو يُشغّل workflow على حساب المستخدم، ولم تُبنَ APK داخل بيئة التسليم، ولم يحدث اختبار تنزيل حقيقي/تشغيل على هاتف. المصدر الجديد يحتاج هذا التحقق قبل اعتماده للإنتاج.
