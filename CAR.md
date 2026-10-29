# نسخة شاشة العربية (Android Head Unit)

نفس أبلكيشن الأندرويد، متظبط لشاشات العربيات الأندرويد الصيني (اتجرب على firmware `CMENZX80-U1_R8010`، معالج 6580، 2GB RAM، شاشة ~1024x600).

| | Production | نسخة العربية |
|---|---|---|
| Branch | `main` | `test/car-screen` |
| Package | `com.medicamall.scope` | `com.medicamall.scope.car` |
| اسم الأبلكيشن | Medica Mall Scope | Medica Mall Scope Car |
| Release | `app` | `car-test` (pre-release) |
| شاشة الكشف | `web/index.html` على main (بتتحدث أونلاين) | نفس الشاشة، متحوّلة لـ JS قديم ومتثبتة في الـ APK (بتتحدث مع كل build جديد بس) |

اللي متغير في النسخة دي بس (كله جوه `BuildConfig.CAR`):
- WebView الشاشات دي قديم (أقل من Chrome 80) ومبيفهمش JS الحديث، فالـ build بيحوّل شاشة الكشف لـ Chrome 58 (`tools/legacy_web.js`). أول ما الأبلكيشن يفتح بيظهر إصدار الـ WebView، وأي خطأ JS بيظهر كرسالة.
- فتح المنظار بدقة 640x480 الأول (أخف على المعالج)، ولو اتفتح ومبعتش صورة خلال 5 ثواني بيجرب الدقة الأقل لوحده.
- رسائل واضحة: لقيت المنظار / صلاحية USB اترفضت / مفيش جهاز USB / الأجهزة اللي الشاشة شايفاها.
- زرار «↻ الكاميرات» بيدوّر على المنظار تاني من أندرويد.
- ملء الشاشة (من غير شريط الحالة والأزرار).
- شاشة الكشف بتترسم بعرض ثابت 1024 مهما كانت كثافة الشاشة (density) وحجم الخط في الإعدادات، وبتصميم أفقي خاص (`app/src/main/assets/car.css`): البث الحي + صورة/فيديو في أول شاشة، وأدوات الكاميرا مقفولة في «⚙ أدوات»، والمريض والصور على الجنب.

التحميل: https://github.com/Mostafe16/medicamall-scope/releases/download/car-test/MedicaMall-Scope-Car.apk
