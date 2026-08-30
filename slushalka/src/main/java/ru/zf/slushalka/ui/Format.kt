package ru.zf.slushalka.ui

/** 3:24:11 - для позиции в книге; 24:11 - когда до часа не дотягивает. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** «3 ч 24 м» - для длительностей, где секунды только мешают. */
fun formatSpan(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h ч $m м"
        h > 0 -> "$h ч"
        m > 0 -> "$m мин"
        else -> "меньше минуты"
    }
}

/** Остаток с поправкой на скорость: на 1,4× книга кончится заметно раньше. */
fun formatLeft(leftMs: Long, speed: Float): String {
    val real = if (speed > 0.05f) (leftMs / speed).toLong() else leftMs
    return formatSpan(real)
}

/** 1× · 1,2× · 1,25× - хвостовые нули только мешают читать. */
fun formatSpeed(v: Float): String =
    "%.2f".format(v).replace('.', ',').trimEnd('0').trimEnd(',') + "×"

fun formatDate(at: Long): String {
    val fmt = java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale.forLanguageTag("ru"))
    return fmt.format(java.util.Date(at))
}

/** «вчера», «3 дня назад» - для «давно ли слушали». */
fun formatAgo(at: Long): String {
    if (at <= 0) return ""
    val diff = System.currentTimeMillis() - at
    val min = diff / 60_000
    val h = min / 60
    val d = h / 24
    return when {
        min < 2 -> "только что"
        min < 60 -> "$min мин назад"
        h < 24 -> "$h ч назад"
        d == 1L -> "вчера"
        d < 30 -> "$d дн назад"
        else -> formatDate(at)
    }
}
