package ru.zf.slushalka.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.GuideEngine
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.OneShelf
import ru.zf.slushalka.library.documentUri

/**
 * Сверка полки с сервером: какая это книга на сервере.
 *
 * Телефон пишет `_Слушалка/полка-<имя>@<устройство>.json` - все свои папки
 * книг с файлами и размерами (при запуске и после перемен на полке, не чаще
 * раза в десять минут), сервер через минуту-две отвечает
 * `_Слушалка/сверка-<имя>@<устройство>.json`: для каждой папки - её папка в
 * `Книги/`, как узнал, уверен ли и чего у сервера нет из того, что есть у
 * телефона (`need`). По ответу:
 * - узнанная книга под другим именем - псевдоним «ключ телефона → папка
 *   сервера» ([OneShelf] берёт его первым), данные переезжают на ключ сервера,
 *   своя папка переименовывается в серверное имя (не вышло - живёт под
 *   псевдонимом);
 * - лишняя копия, у которой сервер всё уже имеет, удаляется - тихо, строкой
 *   в журнале; файлы, которых нет на сервере, не удаляются никогда;
 * - `need: audio/text` - недостающее докладывается во входящие сервера:
 *   папка в корне, первым `слушалка-куда.json`, дальше файлы;
 * - `need: book` - книга выгружается в корень целиком, сервер её разложит.
 * Докладка и выгрузка - только по Wi-Fi, в фоне, с докачкой по файлам.
 */
class ShelfSync(private val app: SlushalkaApp) {

    data class FileEntry(val path: String, val size: Long)

    /** Папка книги в полке: ключ телефона, имя папки, её файлы. */
    data class ShelfBook(val key: String, val folder: String, val files: List<FileEntry>)

    /** Что сервер сказал об одной папке. */
    data class Verdict(
        /** Папка в `Книги/`; null - на сервере её нет или сервер не уверен. */
        val folder: String?,
        /** «имя», «прежнее имя», «файлы», «название», «Claude». */
        val how: String,
        val sure: Boolean,
        /** Чего у сервера нет, а у телефона есть: book, audio, text. */
        val need: Set<String>,
        val note: String,
        /** Кандидат, когда сервер не уверен. */
        val maybe: String?,
    ) {
        /** Сервер не уверен, что это за книга: на полке - «только на телефоне». */
        val unknown: Boolean get() = folder == null && !sure
    }

    data class Answer(val at: Long, val shelfAt: Long, val books: Map<String, Verdict>)

    private val file = File(app.filesDir, "shelf-sync.json")

    private val _aliases = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Ключ папки телефона → папка книги на сервере: из ответов сверки. */
    val aliases: StateFlow<Map<String, String>> = _aliases

    private val _answer = MutableStateFlow<Answer?>(null)
    /** Последний ответ сервера на свою полку. */
    val answer: StateFlow<Answer?> = _answer

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Идущая докладка или выгрузка по ключу телефона: доля переданного. */
    val progress: StateFlow<Map<String, Float>> = _progress

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Журнал сверки: что переименовано, перенесено, удалено, выгружено. */
    val log: StateFlow<List<String>> = _log

    private val _status = MutableStateFlow("")
    /** Где сверка сейчас: «полка отправлена», «ответ получен». */
    val status: StateFlow<String> = _status

    /** `at` последней отправленной полки: ответ на другую - ждать. */
    @Volatile
    private var shelfAt = 0L
    @Volatile
    private var pushedAt = 0L
    /** Что было в отправленной полке: файлы по ключу телефона. */
    @Volatile
    private var sent: Map<String, List<FileEntry>> = emptyMap()
    /** Докладка закончена: «ключ|вид» → когда. Пока сервер не разложил - второй раз не льём. */
    private val uploaded = HashMap<String, Long>()

    init {
        load()
    }

    // ------------------------------------------------------------ полка

    private var pushJob: Job? = null

    /**
     * Полка поменялась (появилась папка, скачал, удалил, выгрузил) или
     * приложение запустилось - отправить. Не чаще раза в десять минут:
     * перемены за это время уедут одной полкой.
     */
    fun changed() {
        if (pushJob?.isActive == true) return
        pushJob = app.scope.launch {
            val wait = pushedAt + PUSH_EVERY_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            pushNow()
        }
    }

    /** «Сверить сейчас» из настроек: рукой - без ожидания десяти минут. */
    fun checkNow() {
        pushJob?.cancel()
        pushJob = app.scope.launch { pushNow() }
    }

    private suspend fun pushNow() {
        val p = app.settings.now()
        if (!p.cloudReady) return
        // При запуске оглавление ещё читается с диска: подождём его. Нет его и
        // через минуту - это не домашняя библиотека, сверять не с чем.
        withTimeoutOrNull(60_000) { app.server.index.first { it != null } } ?: return
        val copies = app.state.phoneCopies()
        val books = withContext(Dispatchers.IO) {
            copies.mapNotNull { b ->
                val files = filesOf(b) ?: return@mapNotNull null
                // Пустой обход - SAF не ответил: такую папку сверять не о чем.
                if (files.isEmpty()) null else ShelfBook(b.phoneKey, b.phoneFolder, files)
            }
        }
        val at = System.currentTimeMillis()
        pushedAt = at
        val name = "${Cloud.SYNC_DIR}/${shelfFileName(p.profile, p.device)}"
        app.cloud.putTextAtomic(name, shelfJson(p.profile, p.device, at, books))
            .onSuccess {
                shelfAt = at
                sent = books.associate { it.key to it.files }
                _status.value = "Полка отправлена ${clock(at)}: ${books.size} папок, жду сверку"
                save()
                poll()
            }
            .onFailure { _status.value = "Полка не отправилась: ${it.message}" }
    }

    /** Файлы своей папки книги без служебных; null - папки нет. */
    private fun filesOf(b: Book): List<FileEntry>? {
        if (!b.onPhone) return null
        val tree = app.state.treeOf(b) ?: return null
        return Saf.walk(app, tree, b.folderDocId)
            .filter { !isService(it.rel) }
            .map { FileEntry(it.rel, it.size) }
            .sortedBy { it.path }
    }

    private var pollJob: Job? = null

    /** Ответ сервера - через минуту-две; ждём его четверть часа, потом - до следующей полки. */
    private fun poll() {
        pollJob?.cancel()
        pollJob = app.scope.launch {
            repeat(POLL_TRIES) {
                delay(POLL_MS)
                if (readAnswer()) return@launch
            }
        }
    }

    /** Прочитать ответ; true - он на последнюю полку и применён. */
    suspend fun readAnswer(): Boolean {
        val p = app.settings.now()
        if (!p.cloudReady || shelfAt == 0L) return false
        val raw = app.cloud.getText("${Cloud.SYNC_DIR}/${answerFileName(p.profile, p.device)}").getOrNull()
            ?: return false
        val a = parseAnswer(raw) ?: return false
        // Ответ на прежнюю полку: сервер ещё не дошёл до новой - ждать.
        if (a.shelfAt != shelfAt) return false
        if (_answer.value?.let { it.at == a.at && it.shelfAt == a.shelfAt } != true) apply(a)
        return true
    }

    private fun apply(a: Answer) {
        val aliases = _aliases.value.toMutableMap()
        for ((key, v) in a.books) {
            if (v.folder != null) aliases[key] = v.folder
        }
        _answer.value = a
        _aliases.value = aliases
        val known = a.books.values.count { it.folder != null }
        val up = a.books.values.count { "book" in it.need }
        val unsure = a.books.values.count { it.unknown }
        _status.value = "Сверка ${clock(a.at)}: узнано $known" +
            (if (up > 0) ", выгрузить $up" else "") + (if (unsure > 0) ", не узнано $unsure" else "")
        save()
        // Псевдонимы поменялись - полка пересоберётся (AppState следит за ними),
        // а за ней пройдёт уборка. Если не поменялись - уборка всё равно нужна:
        // need мог опустеть, и лишняя копия ждёт удаления.
        tidy()
    }

    /** Что сказано о папке: по ключу телефона. */
    fun verdictOf(phoneKey: String): Verdict? = _answer.value?.books?.get(phoneKey)

    // ---------------------------------------------------------- уборка

    private val tidyLock = Mutex()
    private var tidyJob: Job? = null
    @Volatile
    private var tidyAgain = false
    /** Переименовать пробовали в этом запуске - второй раз не мучаем папку. */
    private val renameTried = HashSet<String>()
    private val kitTried = HashSet<String>()

    /**
     * После каждой пересборки полки: перенести данные копий на ключ сервера,
     * переименовать папки, убрать лишние копии, отдать серверу свой
     * справочник и разметку, доложить недостающее.
     */
    fun tidy() {
        if (tidyJob?.isActive == true) {
            tidyAgain = true
            return
        }
        // Уборка ходит по диску и SAF - не на главном потоке.
        tidyJob = app.scope.launch(Dispatchers.IO) {
            tidyLock.withLock {
                do {
                    tidyAgain = false
                    runCatching { tidyNow() }
                } while (tidyAgain)
            }
        }
    }

    private suspend fun tidyNow() {
        val st = app.state
        val adopted = st.adopted()
        // Данные - на ключ сервера. Открытую книгу не трогаем: плеер пишет её место
        // под тем ключом, с которым её открыли; доберёмся в следующий раз.
        var moved = false
        for ((phoneKey, id) in adopted.keyOf) {
            if (phoneKey == id || st.isBusy(phoneKey)) continue
            val primary = adopted.books.any { it.id == id && it.phoneKey == phoneKey }
            if (rekey(phoneKey, id, primary)) {
                moved = true
                note("Места, вопросы и пометки «${titleOf(adopted, id)}» перенесены на ключ сервера (были под «$phoneKey»)")
            }
        }
        // Места без папки: книгу когда-то удалили или слушали с сервера под
        // прежним именем, - сервер знает и такие имена (`former`).
        val orphans = app.positions.all().keys + app.askLog.all().keys + app.notes.all().keys
        for (old in orphans) {
            if (old in adopted.keyOf || st.isBusy(old)) continue
            val id = st.canonicalOf(old)
            if (id == old) continue
            if (rekey(old, id, primary = false)) {
                moved = true
                note("Места, вопросы и пометки «${titleOf(adopted, id)}» перенесены на ключ сервера (были под «$old»)")
            }
        }
        if (moved) withContext(Dispatchers.Main) { st.dataMoved() }

        val p = app.settings.now()
        val index = app.server.index.value
        if (!p.cloudReady || index == null) return

        var shelfMoved = false
        // Своя папка - в серверное имя. Регистр не в счёт: и так узнаётся.
        for (b in adopted.books) {
            if (!b.onPhone || b.phoneId.isBlank()) continue
            val target = b.folderName
            if (ServerLibrary.folderKey(b.phoneFolder) == ServerLibrary.folderKey(target)) continue
            if (b.phoneKey in renameTried || st.isBusy(b.phoneKey) || st.isBusy(b.id)) continue
            when (rename(b, target)) {
                Rename.DONE -> {
                    renameTried += b.phoneKey
                    shelfMoved = true
                }
                Rename.FAILED -> renameTried += b.phoneKey
                Rename.LATER -> {}
            }
        }

        // Лишние копии: у сервера есть всё, что в них, - убрать.
        val a = _answer.value
        if (a != null && a.shelfAt == shelfAt) {
            for (e in adopted.extras) {
                val v = a.books[e.phoneKey] ?: continue
                if (!v.sure || v.folder == null || v.need.isNotEmpty()) continue
                if (ServerLibrary.folderKey(v.folder) != ServerLibrary.folderKey(e.folderName)) continue
                if (st.isBusy(e.phoneKey) || st.isBusy(e.id)) continue
                if (dropExtra(e)) shelfMoved = true
            }
        }

        // Справочник и разметка, посчитанные на телефоне, - на сервер, если там их нет.
        for (b in adopted.books) shareKit(b, index)

        if (shelfMoved) {
            // Перечитывание трогает плеер (переезд открытой книги на свои файлы) - с главного потока.
            withContext(Dispatchers.Main) { st.rescanNow() }
            changed()
        }
        pumpUploads()
    }

    private fun titleOf(adopted: OneShelf.Adopted, id: String): String =
        adopted.books.firstOrNull { it.id == id }?.title ?: id.substringAfterLast('/')

    /** Все данные книги со старого ключа - на новый. */
    private fun rekey(old: String, new: String, primary: Boolean): Boolean {
        var any = app.positions.rekey(old, new, fromAnchors = primary)
        any = app.askLog.rekey(old, new) || any
        any = app.notes.rekey(old, new) || any
        any = app.bookmarks.rekey(old, new) || any
        any = app.journal.rekey(old, new) || any
        any = app.texts.rekey(old, new) || any
        any = app.guide.rekey(old, new) || any
        any = app.razbor.rekey(old, new) || any
        // Дата появления на полке - не данные чтения: местам ехать на сервер незачем.
        app.added.rekey(old, new)
        return any
    }

    /** Чем кончилось переименование: сделано, не выйдет (только чтение), или позже - мешает соседка. */
    private enum class Rename { DONE, FAILED, LATER }

    private suspend fun rename(b: Book, target: String): Rename = withContext(Dispatchers.IO) {
        val tree = app.state.treeOf(b) ?: return@withContext Rename.LATER
        val parent = Saf.dirByPath(app, tree, b.phoneKey.substringBeforeLast('/'))
        if (parent == null) {
            note("Папку «${b.phoneFolder}» не нашёл, чтобы переименовать: она остаётся под своим именем")
            return@withContext Rename.FAILED
        }
        // Рядом уже лежит папка с серверным именем (вторая копия): провайдер
        // назвал бы нашу «… (1)». Подождём, пока лишняя уйдёт.
        if (Saf.children(app, tree, parent).any { ServerLibrary.folderKey(it.second) == ServerLibrary.folderKey(target) }) {
            return@withContext Rename.LATER
        }
        val done = runCatching {
            DocumentsContract.renameDocument(app.contentResolver, documentUri(tree, b.folderDocId), target)
        }.getOrNull()
        if (done != null) {
            note("Папка «${b.phoneFolder}» переименована в «$target» - как на сервере")
            Rename.DONE
        } else {
            note("Папку «${b.phoneFolder}» переименовать не вышло (только чтение?) - помню, что это «$target»")
            Rename.FAILED
        }
    }

    /**
     * Лишняя копия уходит, только если её файлы - ровно те, что были в полке,
     * на которую сервер ответил «у меня есть всё»: папку могли дополнить
     * после отправки.
     */
    private suspend fun dropExtra(e: Book): Boolean = withContext(Dispatchers.IO) {
        val was = sent[e.phoneKey]?.takeIf { it.isNotEmpty() } ?: return@withContext false
        val now = filesOf(e) ?: return@withContext false
        if (now.toSet() != was.toSet()) return@withContext false
        val tree = app.state.treeOf(e) ?: return@withContext false
        val ok = runCatching {
            DocumentsContract.deleteDocument(app.contentResolver, documentUri(tree, e.folderDocId))
        }.getOrDefault(false)
        if (ok) note("Убрал лишнюю копию «${e.phoneFolder}» книги «${e.title}»: у сервера есть всё, что в ней")
        ok
    }

    /**
     * Справочник и разметка из своей папки - в папку книги на сервере, если
     * там их нет. Только когда это то же издание: справочник - к тексту того
     * же размера, разметка - ещё и к звуку того же объёма; к чужому изданию
     * они только навредили бы.
     */
    private suspend fun shareKit(b: Book, index: ServerLibrary.Index) {
        if (!b.onPhone || b.remoteDir.isBlank()) return
        val sb = index.byFolder(b.folderName) ?: return
        val files = sent[b.phoneKey] ?: return
        val text = files.firstOrNull { it.path == b.textName }?.size
        val sameText = text != null && text == sb.mainText?.size
        val sameAudio = b.ownAudioBytes > 0 && b.ownAudioBytes == sb.audioBytes && b.files.size == sb.audio.size
        for (name in listOf(GuideEngine.FILE, Markup.FILE)) {
            if (sb.other.any { it.path == name } || files.none { it.path == name }) continue
            if (!sameText || (name == Markup.FILE && !sameAudio)) continue
            if (!kitTried.add(b.phoneKey + "|" + name)) continue
            val body = withContext(Dispatchers.IO) {
                val tree = app.state.treeOf(b) ?: return@withContext null
                Saf.findChild(app, tree, b.folderDocId, name)?.let { Saf.readText(app, tree, it) }
            } ?: continue
            if (app.cloud.putTextAtomic("${b.remoteDir}/$name", body).isSuccess) {
                note("${if (name == Markup.FILE) "Разметка" else "Справочник"} «${b.title}» - с телефона на сервер")
            }
        }
    }

    // -------------------------------------------------------- докладка

    private val uploadLock = Mutex()

    /** Wi-Fi или провод: звук книги - сотни мегабайт, мобильную сеть он не ест. */
    private fun unmetered(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }.getOrDefault(false)

    /** Есть ли сеть вообще: нескачанной книге без неё неоткуда взяться. */
    fun online(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private var watching = false

    /** Появился Wi-Fi - доложить отложенное. */
    fun watchNetwork() {
        if (watching) return
        watching = true
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    app.scope.launch { pumpUploads() }
                }
            })
        }
    }

    /** Что докладываем: папка телефона, куда на сервере, что именно. */
    private data class Task(val copy: Book, val folder: String?, val kinds: Set<String>)

    /**
     * Доложить на сервер то, чего у него нет, - по одному делу за раз. По
     * мобильной сети не идёт: дождётся Wi-Fi.
     */
    fun pumpUploads() {
        app.scope.launch { pump() }
    }

    private suspend fun pump() {
        if (uploadLock.isLocked) return
        uploadLock.withLock {
            while (true) {
                if (!app.settings.now().cloudReady || app.server.index.value == null || !unmetered()) return
                val task = nextTask() ?: return
                val ok = runTask(task)
                if (!ok) return
            }
        }
    }

    private fun nextTask(): Task? {
        val a = _answer.value ?: return null
        val now = System.currentTimeMillis()
        val copies = app.state.phoneCopies().associateBy { it.phoneKey }
        for ((key, v) in a.books) {
            if (!v.sure) continue
            val copy = copies[key] ?: continue
            val kinds = when {
                v.folder != null -> v.need.intersect(setOf(NEED_AUDIO, NEED_TEXT))
                NEED_BOOK in v.need -> setOf(NEED_BOOK)
                else -> emptySet()
            }.filter { k -> (uploaded["$key|$k"] ?: 0L) < now - RETRY_MS }.toSet()
            if (kinds.isNotEmpty()) return Task(copy, v.folder, kinds)
        }
        return null
    }

    /** Одно дело: папка во входящих, указатель первым, файлы по одному с докачкой. */
    private suspend fun runTask(t: Task): Boolean {
        val b = t.copy
        val tree = app.state.treeOf(b) ?: return false
        val whole = NEED_BOOK in t.kinds
        // Целиком - папкой своего имени в корень, как прежняя «Выгрузить в облако»;
        // докладка - отдельной папкой с указателем, куда её положить.
        val base = if (whole) b.phoneFolder else "${b.phoneFolder} (докладка)"
        val files = withContext(Dispatchers.IO) { Saf.walk(app, tree, b.folderDocId) }
            .filter { f ->
                !isService(f.rel) && when {
                    whole -> true
                    else -> (NEED_AUDIO in t.kinds && isAudio(f.rel)) || (NEED_TEXT in t.kinds && isText(f.rel))
                }
            }
        val key = b.phoneKey
        val what = when {
            whole -> "книгу"
            t.kinds == setOf(NEED_TEXT) -> "текст"
            NEED_TEXT in t.kinds -> "звук и текст"
            else -> "звук"
        }
        if (files.isEmpty()) {
            t.kinds.forEach { uploaded["$key|$it"] = System.currentTimeMillis() }
            save()
            return true
        }
        val total = files.sumOf { it.size }.coerceAtLeast(1L)
        var done = 0L
        _progress.value = _progress.value + (key to 0f)
        val result = runCatching {
            if (!whole) {
                val p = app.settings.now()
                app.cloud.putTextAtomic("$base/$ROUTE", routeJson(t.folder.orEmpty(), p.profile, p.device)).getOrThrow()
            }
            val there = remoteSizes(base)
            for (f in files) {
                if (!unmetered()) throw Cloud.CloudException("Wi-Fi пропал - продолжу, когда появится")
                if (there[f.rel] != f.size) {
                    val open = { app.contentResolver.openInputStream(documentUri(tree, f.docId)) }
                    app.cloud.uploadAtomic("$base/${f.rel}", f.size, open).getOrThrow()
                }
                done += f.size
                _progress.value = _progress.value + (key to (done.toFloat() / total))
            }
        }
        _progress.value = _progress.value - key
        return result.fold(
            onSuccess = {
                t.kinds.forEach { uploaded["$key|$it"] = System.currentTimeMillis() }
                note("Выгрузил на сервер $what «${b.title}»" + if (whole) "" else " - в «${t.folder}»")
                save()
                changed()
                true
            },
            onFailure = { e ->
                note("Выгрузка «${b.title}» прервалась: ${e.message} - докачаю")
                false
            },
        )
    }

    /** Что уже лежит в папке на сервере: путь от неё и размер. Две ступени, как у книги. */
    private suspend fun remoteSizes(base: String, prefix: String = "", depth: Int = 0): Map<String, Long> {
        val items = app.cloud.list(if (prefix.isEmpty()) base else "$base/${prefix.trimEnd('/')}").getOrNull().orEmpty()
        val out = HashMap<String, Long>()
        for (i in items) {
            if (i.name.endsWith(Cloud.PARTIAL)) continue
            if (i.dir) {
                if (depth < 2) out += remoteSizes(base, "$prefix${i.name}/", depth + 1)
            } else out["$prefix${i.name}"] = i.size
        }
        return out
    }

    // ---------------------------------------------------------- журнал

    private fun note(line: String) {
        val stamped = stamp(System.currentTimeMillis()) + " " + line
        _log.value = (_log.value + stamped).takeLast(LOG_KEEP)
        save()
    }

    private fun clock(at: Long) = SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(at))

    private fun stamp(at: Long) = SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru")).format(Date(at))

    // ------------------------------------------------------- на диске

    private fun load() {
        Store.readOrQuarantine(file) { text ->
            val o = JSONObject(text)
            shelfAt = o.optLong("shelfAt")
            pushedAt = o.optLong("pushedAt")
            o.optJSONObject("aliases")?.let { a ->
                _aliases.value = a.keys().asSequence().associateWith { a.getString(it) }
            }
            o.optJSONObject("sent")?.let { s ->
                sent = s.keys().asSequence().associateWith { k -> filesFrom(s.getJSONArray(k)) }
            }
            o.optString("answer").takeIf { it.isNotBlank() }?.let { _answer.value = parseAnswer(it) }
            o.optJSONObject("uploaded")?.let { u -> u.keys().forEach { uploaded[it] = u.getLong(it) } }
            o.optJSONArray("log")?.let { l -> _log.value = (0 until l.length()).map { l.getString(it) } }
            o.optString("status").takeIf { it.isNotBlank() }?.let { _status.value = it }
        }
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
            .put("shelfAt", shelfAt)
            .put("pushedAt", pushedAt)
            .put("aliases", JSONObject(_aliases.value))
            .put("sent", JSONObject().apply { sent.forEach { (k, v) -> put(k, filesJson(v)) } })
            .put("answer", _answer.value?.let(::answerJson) ?: "")
            .put("uploaded", JSONObject(uploaded.toMap()))
            .put("log", JSONArray(_log.value))
            .put("status", _status.value)
            .toString()
        Store.post { Store.writeAtomic(file, o) }
    }

    companion object {
        /** Полка - не чаще раза в десять минут. */
        const val PUSH_EVERY_MS = 10 * 60_000L
        private const val POLL_MS = 60_000L
        private const val POLL_TRIES = 15
        /** Выгрузили, а сервер так и не разложил - через полсуток ещё раз. */
        private const val RETRY_MS = 12 * 3600_000L
        private const val LOG_KEEP = 200

        const val NEED_BOOK = "book"
        const val NEED_AUDIO = "audio"
        const val NEED_TEXT = "text"

        /** Указатель докладки во входящих: в какую книгу положить файлы рядом. */
        const val ROUTE = "слушалка-куда.json"

        private fun clean(s: String) = s.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()

        private fun suffix(profile: String, device: String) =
            clean(profile).ifBlank { "без-имени" } + "@" + clean(device).ifBlank { "устройство" } + ".json"

        /** «полка-Саша@Pixel.json» - как файл мест, один писатель на файл. */
        fun shelfFileName(profile: String, device: String) = "полка-" + suffix(profile, device)

        fun answerFileName(profile: String, device: String) = "сверка-" + suffix(profile, device)

        /** Служебное Слушалки в папке книги: в полку не идёт и на сервер не льётся. */
        fun isService(rel: String): Boolean {
            val parts = rel.split('/')
            val name = parts.last()
            return name.endsWith(Cloud.PARTIAL) ||
                parts.dropLast(1).any { it == PositionSync.DIR } ||
                (name.startsWith(ServerLibrary.OUR_PREFIX) && name.endsWith(".json"))
        }

        private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "oga", "flac", "wav", "mp4")

        fun isAudio(rel: String) = rel.substringAfterLast('.', "").lowercase() in AUDIO_EXT

        fun isText(rel: String): Boolean {
            val n = rel.substringAfterLast('/').lowercase()
            return n.endsWith(".fb2") || n.endsWith(".epub") || n.endsWith(".fb2.zip")
        }

        fun shelfJson(profile: String, device: String, at: Long, books: List<ShelfBook>): String = JSONObject()
            .put("v", 1)
            .put("profile", profile)
            .put("device", device)
            .put("at", at)
            .put("books", JSONArray().apply {
                books.forEach { b ->
                    put(JSONObject().put("key", b.key).put("folder", b.folder).put("files", filesJson(b.files)))
                }
            })
            .toString()

        fun parseShelf(text: String): List<ShelfBook> = runCatching {
            val arr = JSONObject(text).optJSONArray("books") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ShelfBook(o.getString("key"), o.optString("folder"), filesFrom(o.optJSONArray("files") ?: JSONArray()))
            }
        }.getOrDefault(emptyList())

        private fun filesJson(files: List<FileEntry>) = JSONArray().apply {
            files.forEach { put(JSONObject().put("path", it.path).put("size", it.size)) }
        }

        private fun filesFrom(arr: JSONArray): List<FileEntry> = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            FileEntry(o.optString("path"), o.optLong("size"))
        }

        /**
         * Ответ сервера. Разбирается терпимо: `need` - список строк, `maybe` -
         * строкой или объектом с `folder`, лишние поля не мешают.
         */
        fun parseAnswer(text: String): Answer? = runCatching {
            val o = JSONObject(text)
            val books = o.optJSONObject("books") ?: JSONObject()
            Answer(
                at = o.optLong("at"),
                shelfAt = o.optLong("shelf_at"),
                books = books.keys().asSequence().associateWith { key ->
                    val b = books.getJSONObject(key)
                    val needArr = b.optJSONArray("need") ?: JSONArray()
                    Verdict(
                        folder = b.optString("folder").takeIf { !b.isNull("folder") && it.isNotBlank() },
                        how = b.optString("how"),
                        sure = b.optBoolean("sure", true),
                        need = (0 until needArr.length()).map { needArr.optString(it).trim().lowercase() }
                            .filter { it.isNotBlank() }.toSet(),
                        note = b.optString("note"),
                        maybe = when (val m = b.opt("maybe")) {
                            is String -> m.takeIf { it.isNotBlank() }
                            is JSONObject -> m.optString("folder").takeIf { it.isNotBlank() }
                            else -> null
                        },
                    )
                }.toMap(),
            )
        }.getOrNull()

        fun answerJson(a: Answer): String = JSONObject()
            .put("v", 1)
            .put("at", a.at)
            .put("shelf_at", a.shelfAt)
            .put("books", JSONObject().apply {
                a.books.forEach { (k, v) ->
                    put(k, JSONObject()
                        .put("folder", v.folder ?: JSONObject.NULL)
                        .put("how", v.how)
                        .put("sure", v.sure)
                        .put("need", JSONArray(v.need.toList()))
                        .put("note", v.note)
                        .apply { v.maybe?.let { put("maybe", it) } })
                }
            })
            .toString()

        /** `слушалка-куда.json`: в какую книгу сервера доложить файлы этой папки. */
        fun routeJson(folder: String, by: String, device: String): String = JSONObject()
            .put("folder", folder)
            .put("by", by)
            .put("device", device)
            .toString()
    }
}
