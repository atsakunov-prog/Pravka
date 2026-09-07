package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Чем занимались: слушали запись, читали глазами или слушали синтез. */
enum class Mode(val code: String) {
    LISTEN("L"), READ("R"), ALOUD("A");

    companion object {
        fun of(code: String): Mode = entries.firstOrNull { it.code == code } ?: LISTEN
    }
}

/**
 * Один подход к книге: сел, послушал или почитал, отложил.
 *
 * Внутри подхода бывают паузы (позвонили, отвлёкся) - они не рвут его, пока
 * короче четверти часа, но и в [activeMs] не входят. Так «подход» - это то, что
 * человек сам назвал бы одним разом, а не каждое нажатие на пуск.
 */
data class Session(
    val bookId: String,
    val mode: Mode,
    /** Когда начали и когда в последний раз что-то происходило. */
    val startAt: Long,
    val endAt: Long,
    /** Сколько из этого времени действительно слушали или читали. */
    val activeMs: Long = 0,
    /** Слушание: сколько записи прошло. На 1,5× за час слушания проходит полтора. */
    val coveredMs: Long = 0,
    /** Чтение и озвучка: сколько знаков книги прошло вперёд. */
    val chars: Long = 0,
    /** Где были в начале и в конце: миллисекунды записи или знак текста. */
    val fromPos: Long = 0,
    val toPos: Long = 0,
) {
    val spanMs: Long get() = (endAt - startAt).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject()
        .put("b", bookId).put("m", mode.code)
        .put("s", startAt).put("e", endAt).put("a", activeMs)
        .put("c", coveredMs).put("k", chars)
        .put("p0", fromPos).put("p1", toPos)

    companion object {
        fun fromJson(o: JSONObject) = Session(
            bookId = o.optString("b"),
            mode = Mode.of(o.optString("m")),
            startAt = o.optLong("s"),
            endAt = o.optLong("e"),
            activeMs = o.optLong("a"),
            coveredMs = o.optLong("c"),
            chars = o.optLong("k"),
            fromPos = o.optLong("p0"),
            toPos = o.optLong("p1"),
        )
    }
}

/**
 * Журнал подходов к книгам - сырьё для статистики.
 *
 * Пишутся не «сколько всего», а сами подходы с временем начала и конца: из них
 * потом считается что угодно - по дням, по часам суток, по книгам, темп чтения
 * - и любую новую сводку можно посчитать задним числом, не потеряв старое.
 *
 * Откуда события:
 * - плеер: каждый тик, пока играет (`listening`), и пауза (`stopped`);
 * - читалка: каждая перелистнутая страница (`reading`), уход с экрана (`stopped`);
 * - озвучка: каждый абзац (`aloud`), пауза и стоп (`stopped`).
 *
 * Время между событиями идёт в зачёт, пока «дело идёт»: у плеера и озвучки -
 * от пуска до паузы целиком, у читалки - только если следующая страница
 * перелистнута не позже четырёх минут. Иначе экран, оставленный открытым на
 * ночь (читалка не даёт ему гаснуть), записался бы как восемь часов чтения.
 *
 * Перемотки и переходы по главам в «пройдено» не идут: прыжок дальше десяти
 * секунд записи или трёх страниц текста за один шаг - это не чтение, а поиск.
 *
 * Пишется на диск при закрытии подхода и не реже раза в минуту, пока он идёт:
 * убитый системой процесс теряет самое большее минуту. Дисциплина записи - та
 * же, что у позиций (атомарно, с `.prev`).
 */
class Journal(context: Context) {

    private val file = File(context.filesDir, "journal.json")
    private val closed = ArrayList<Session>()
    private var open: Session? = null
    /** Дело идёт: между этим и следующим событием время засчитывается. */
    private var engaged = false
    private var lastEventAt = 0L
    private var lastPersistAt = 0L
    private var dirty = false

    private val _rev = MutableStateFlow(0)
    /** Растёт с каждым закрытым подходом - экран статистики пересчитывается. */
    val rev: StateFlow<Int> = _rev

    init {
        Store.readOrQuarantine(file) { text ->
            val root = JSONObject(text)
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until arr.length()) closed.add(Session.fromJson(arr.getJSONObject(i)))
            root.optJSONObject("open")?.let { open = Session.fromJson(it) }
        }
        // Процесс поднялся заново - значит, в момент его смерти ничто не играло
        // и не листалось. Незакрытый подход ждёт продолжения, но время не идёт.
        engaged = false
        lastEventAt = open?.endAt ?: 0L
    }

    // --------------------------------------------------------------- события

    /** Плеер играет: вызывается на пуске и каждым тиком, [absMs] - место в записи. */
    @Synchronized
    fun listening(bookId: String, absMs: Long, now: Long = System.currentTimeMillis()) =
        touch(bookId, Mode.LISTEN, absMs, now)

    /** Читалка показала страницу с этого знака. */
    @Synchronized
    fun reading(bookId: String, charOffset: Int, now: Long = System.currentTimeMillis()) =
        touch(bookId, Mode.READ, charOffset.toLong(), now)

    /** Озвучка начала абзац с этого знака. */
    @Synchronized
    fun aloud(bookId: String, charOffset: Int, now: Long = System.currentTimeMillis()) =
        touch(bookId, Mode.ALOUD, charOffset.toLong(), now)

    /**
     * Дело остановилось: пауза, стоп, уход с экрана. Подход не закрывается -
     * вернутся через пять минут, и он продолжится, - но время до возвращения
     * в зачёт не идёт. [pos] - где остановились, если известно.
     */
    @Synchronized
    fun stopped(pos: Long? = null, now: Long = System.currentTimeMillis()) {
        val s = open
        if (s != null && engaged) {
            open = advance(s, pos ?: s.toPos, now)
            dirty = true
        }
        engaged = false
        lastEventAt = now
        persistMaybe(now, force = true)
    }

    private fun touch(bookId: String, mode: Mode, pos: Long, now: Long) {
        var s = open
        if (s != null && (s.bookId != bookId || s.mode != mode || now - lastEventAt > SITTING_GAP_MS)) {
            close(s)
            s = null
        }
        if (s == null) {
            open = Session(bookId, mode, startAt = now, endAt = now, fromPos = pos, toPos = pos)
            engaged = true
            lastEventAt = now
            dirty = true
            persistMaybe(now, force = true)
            return
        }
        open = advance(s, pos, now)
        engaged = true
        lastEventAt = now
        dirty = true
        persistMaybe(now)
    }

    /** Довести подход до [now]: зачесть время, если дело шло, и пройденное, если это не прыжок. */
    private fun advance(s: Session, pos: Long, now: Long): Session {
        val gap = (now - lastEventAt).coerceAtLeast(0)
        val counted = when {
            !engaged -> 0L
            // Страница не листалась дольше четырёх минут - читатель ушёл, а не задумался.
            s.mode == Mode.READ && gap > READ_IDLE_MS -> 0L
            else -> gap
        }
        val delta = pos - s.toPos
        var covered = s.coveredMs
        var chars = s.chars
        when (s.mode) {
            Mode.LISTEN -> if (delta in 1 until COVER_JUMP_MS) covered += delta
            Mode.READ, Mode.ALOUD -> if (delta in 1 until CHARS_JUMP) chars += delta
        }
        return s.copy(
            endAt = maxOf(now, s.endAt),
            activeMs = s.activeMs + counted,
            coveredMs = covered,
            chars = chars,
            toPos = pos,
        )
    }

    private fun close(s: Session) {
        open = null
        // Случайный тап по пуску - не подход.
        if (s.activeMs >= MIN_SESSION_MS) {
            closed.add(s)
            while (closed.size > MAX_SESSIONS) closed.removeAt(0)
        }
        dirty = true
        _rev.value = _rev.value + 1
    }

    // ---------------------------------------------------------------- чтение

    /** Все подходы, включая незакрытый - статистике он нужен как «сегодня». */
    @Synchronized
    fun all(): List<Session> {
        val o = open
        return if (o == null) ArrayList(closed) else ArrayList(closed).also { it.add(o) }
    }

    // ---------------------------------------------------------------- запись

    /** Уход из приложения - записать, что накопилось. */
    @Synchronized
    fun saveNow() = persistMaybe(System.currentTimeMillis(), force = true)

    private fun persistMaybe(now: Long, force: Boolean = false) {
        if (!dirty) return
        if (!force && now - lastPersistAt < PERSIST_EVERY_MS) return
        lastPersistAt = now
        dirty = false
        val snapshot = JSONObject().apply {
            put("v", 1)
            put("open", open?.toJson() ?: JSONObject.NULL)
            put("sessions", JSONArray().apply { closed.forEach { put(it.toJson()) } })
        }.toString()
        Store.post { Store.writeAtomic(file, snapshot) }
    }

    companion object {
        /** Пауза дольше этого - уже другой подход. */
        const val SITTING_GAP_MS = 15 * 60_000L
        /** Страница не листалась дольше - читатель ушёл, время не в зачёт. */
        const val READ_IDLE_MS = 4 * 60_000L
        /** Шаг записи больше этого за один тик - перемотка, а не слушание. */
        private const val COVER_JUMP_MS = 10_000L
        /** Шаг текста больше трёх страниц за раз - переход, а не чтение. */
        private const val CHARS_JUMP = 3L * Settings.PAGE_CHARS
        private const val MIN_SESSION_MS = 5_000L
        private const val PERSIST_EVERY_MS = 60_000L
        /** Лет на десять при пяти подходах в день; дальше старое уступает новому. */
        private const val MAX_SESSIONS = 20_000
    }
}
