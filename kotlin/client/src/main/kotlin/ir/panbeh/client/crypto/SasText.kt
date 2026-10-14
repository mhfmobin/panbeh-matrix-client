package ir.panbeh.client.crypto

/** Persian names of the 64 SAS emoji, in the spec's order (index = the number in the verification). */
val SAS_EMOJI_FA = listOf(
    "سگ", "گربه", "شیر", "اسب", "تک‌شاخ", "خوک", "فیل", "خرگوش",
    "پاندا", "خروس", "پنگوئن", "لاک‌پشت", "ماهی", "هشت‌پا", "پروانه", "گل",
    "درخت", "کاکتوس", "قارچ", "کره‌ی زمین", "ماه", "ابر", "آتش", "موز",
    "سیب", "توت‌فرنگی", "ذرت", "پیتزا", "کیک", "قلب", "لبخند", "ربات",
    "کلاه", "عینک", "آچار", "بابانوئل", "لایک", "چتر", "ساعت شنی", "ساعت",
    "هدیه", "لامپ", "کتاب", "مداد", "گیره", "قیچی", "قفل", "کلید",
    "چکش", "تلفن", "پرچم", "قطار", "دوچرخه", "هواپیما", "موشک", "جام",
    "توپ", "گیتار", "شیپور", "زنگ", "لنگر", "هدفون", "پوشه", "سنجاق",
)

/** Persian text for a verification cancel code (`m.mismatched_sas`, …); anything else is the generic "cancelled". */
fun cancelReason(code: String?): String = CANCEL_REASONS[code] ?: "تأیید لغو شد."

private val CANCEL_REASONS = mapOf(
    "m.mismatched_sas" to "شکلک‌ها مطابقت نداشتند؛ تأیید لغو شد.",
    "m.user" to "تأیید لغو شد.",
    "m.timeout" to "مهلت تأیید تمام شد.",
    "m.accepted" to "درخواست در دستگاه دیگری پاسخ داده شد.",
)
