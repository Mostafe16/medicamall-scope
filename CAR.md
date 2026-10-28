# نسخة شاشة العربية (Android Head Unit)

نفس أبلكيشن الأندرويد، متظبط لشاشات العربيات الأندرويد الصيني (اتجرب على firmware `CMENZX80-U1_R8010`، معالج 6580، 2GB RAM، شاشة ~1024x600).

| | Production | نسخة العربية |
|---|---|---|
| Branch | `main` | `test/car-screen` |
| Package | `com.medicamall.scope` | `com.medicamall.scope.car` |
| اسم الأبلكيشن | Medica Mall Scope | Medica Mall Scope Car |
| Release | `app` | `car-test` (pre-release) |
| شاشة الكشف | `web/index.html` على main | نفس الشاشة (بتتحدث من main) |

اللي متغير في النسخة دي بس (كله جوه `BuildConfig.CAR`):
- فتح المنظار بدقة لحد 720p، ولو اتفتح ومبعتش صورة خلال 5 ثواني بيجرب الدقة الأقل لوحده.
- رسائل واضحة: لقيت المنظار / صلاحية USB اترفضت / مفيش جهاز USB / الأجهزة اللي الشاشة شايفاها.
- زرار «↻ الكاميرات» بيدوّر على المنظار تاني من أندرويد.
- ملء الشاشة (من غير شريط الحالة والأزرار).
- تصغير الهيدر والمسافات عشان البث الحي وأزرار الصورة/الفيديو يبانوا على 1024x600.

التحميل: https://github.com/Mostafe16/medicamall-scope/releases/download/car-test/MedicaMall-Scope-Car.apk
