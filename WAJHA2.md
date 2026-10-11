# واجهة 2 — نسخة تجريبية منفصلة (Android)

نسخة بنجرب عليها التعديلات من غير ما المستخدمين يتأثروا.

| | Production | واجهة 2 |
|---|---|---|
| Branch | `main` | `claude/exciting-hopper-itydb1` |
| Package | `com.medicamall.scope` | `com.medicamall.scope.wajha2` |
| اسم الأبلكيشن | Medica Mall Scope | Medica Mall Scope واجهة 2 |
| Release | `app` | `wajha-2` (pre-release) |
| التحديث التلقائي | من `app` | من `wajha-2` بس |
| البيانات (مرضى/صور) | منفصلة | منفصلة |

- بتتسطب جنب النسخة الأصلية على نفس الموبايل، وكل واحدة ليها مرضاها وصورها.
- أي push على الـ branch ده بيعمل build وبينشر على `wajha-2` بس. الـ workflow الأصلي (`build.yml`) شغال على `main` بس.
- التحميل: https://github.com/Mostafe16/medicamall-scope/releases/download/wajha-2/MedicaMall-Scope-Wajha2.apk
- لما المنظار يتوصل وفيه النسختين، أندرويد هيسألك تفتح أنهي واحدة.

## شاشة الكشف (الويب)
شاشة الكشف نفسها جاية من موقع Magento. لحد ما نعمل صفحة منفصلة، واجهة 2 بتسحب من الصفحة الأصلية `medicamall.com/ent-scope`.
لتجربة تعديلات على الويب: اعمل CMS page منفصلة (مثلاً `ent-scope-2`) وحط رابطها في repo variable اسمه `WAJHA2_WEB_URL`.

## من غير MM_VARIANT
الـ build بيطلع production بالظبط زي ما هو (نفس الـ package والاسم وقناة التحديث).
