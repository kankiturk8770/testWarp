# Kanki Warp — راهنمای Claude

کلاینت شخصی اندروید برای Cloudflare WARP (Kotlin + Compose، پکیج `cafe.kankipro.warp`). هسته‌ی Aether (Rust) و hev-socks5-tunnel
داخل ریپو نیستند: CI از سورس می‌سازد (`.github/workflows/build.yml`، نسخه‌ی ثابت `AETHER_REF`). مسیر کامل اتصال و نقشه‌ی فایل‌ها در `README.md`.

## قانون‌ها
- فقط برای استفاده‌ی شخصی صاحب پروژه؛ پخش عمومی نه. کلید امضا (`app/kankiwarp.jks`) عمداً داخل ریپوست تا هر نسخه روی قبلی نصب شود.
- هسته‌ی Aether تنظیمات را در متغیرهای محیطی `AETHER_*` می‌گذارد و پاک نمی‌کند؛ `Engine.clearCoreEnv` قبل از هر شروع لازم است.
- hev: `stop` فقط وقتی یک `run` در جریان است (وگرنه اجرای بعدی فوراً تمام می‌شود). `fd` تونل را خود ما بعد از توقف hev می‌بندیم.
- «وصل» فقط بعد از جواب واقعی `Probe.trace`. عددهای ترافیک روی flow جدا (`Engine.traffic`) تا کل صفحه هر ثانیه دوباره کشیده نشود.
- متن‌ها فارسی؛ `T.*` جهت راست‌به‌چپ دارد، آی‌پی و آدرس با `TextDirection.Ltr`.

## تست
- `gradle :app:testDebugUnitTest` (تست‌های `core` و عکس صفحه‌ها `ShotsTest` در `app/build/shots`، Robolectric).
- ساخت کامل با هسته فقط در CI.
- هر تغییر: `versionCode`/`versionName` در `app/build.gradle.kts` بالا برود و یک بخش بالای `CHANGES.md`.

## زبان
صاحب پروژه فارسی‌زبان است و دارد برنامه‌نویسی یاد می‌گیرد: جواب‌ها فارسی، کوتاه و ساده.
