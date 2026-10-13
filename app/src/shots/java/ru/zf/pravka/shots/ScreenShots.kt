package ru.zf.pravka.shots

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.zf.pravka.MainActivity
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.data.Profile
import ru.zf.pravka.trigger.showZasechkaMenu
import java.io.File
import java.time.Duration
import java.util.TimeZone

/**
 * Снимки экранов приложения для дизайна — как на Pixel 10 Pro Fold:
 * внешний экран (сложенный) и внутренний (разложенный). Настоящие экраны,
 * нарисованные Robolectric на JVM (эмулятора в облаке нет), на выдуманных
 * данных из [Seed]. Папка `app/src/shots` подключается только с -Pshots:
 * обычный прогон тестов не видит ни её, ни Robolectric. Снять и довести:
 *
 *     ./gradlew testDebugUnitTest -PbuildNumber=999 -Pshots=all --tests "ru.zf.pravka.shots.ScreenShots"
 *     python3 tools/design_shots.py
 *
 * Сырьё — в `app/build/shots`, готовое — в `docs/design/screens` (не в git:
 * десятки мегабайт, пересъёмка — одна команда). Группы через запятую:
 * outer, tall, inner, overlay; t5 — только «Сегодня» вида v2 (быстро); z5 — только Засечка v2;
 * svod — меню и вид из Свода (этап 7).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = PravkaApp::class, qualifiers = ScreenShots.OUTER)
class ScreenShots {

    companion object {
        /** Внешний экран: 1080×2364, 420 dpi (Правка 4.0: снимки 411×900, как в TASK). */
        const val OUTER = "w411dp-h900dp-420dpi"
        /** Внутренний экран: 2076×2152, почти квадрат — разворот в две колонки (Правка 4.0: 791×820). */
        const val INNER = "w791dp-h820dp-420dpi"
        /** Длинная лента внешнего экрана: вкладка целиком, без прокрутки. */
        const val OUTER_TALL = "w411dp-h2600dp-420dpi"
        /** Разворот высоким окном — колонки целиком (Засечка v2: итог, автопилот, телефон слева). */
        const val INNER_TALL = "w791dp-h1700dp-420dpi"
    }

    private val dir = File(System.getProperty("pravka.shotsDir") ?: "build/shots").also { it.mkdirs() }

    /** -Pshots=all или через запятую: outer, tall, inner, overlay, t5, z5. */
    private fun want(group: String): Boolean {
        val v = System.getProperty("pravka.shots").orEmpty()
        return v == "1" || v == "all" || v.split(',').map { it.trim() }.contains(group)
    }

    private val looper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun only() {
        Assume.assumeTrue(System.getProperty("pravka.shots").orEmpty().isNotBlank())
        // Свет режима плывёт бесконечно: без паузы Robolectric крутит кадры,
        // двигая часы, и idle() не кончается никогда. Часы стоят, кадр — по idleFor.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    /** Местное время снимка — без десяти семь вечера: лента за день почти полная, ужин идёт. */
    private fun pinEvening() {
        val utcMin = (System.currentTimeMillis() / 60_000L % 1440L).toInt()
        var off = (18 * 60 + 50 - utcMin + 1440) % 1440
        if (off > 14 * 60) off -= 1440
        val sign = if (off < 0) "-" else "+"
        val a = kotlin.math.abs(off)
        TimeZone.setDefault(TimeZone.getTimeZone("GMT%s%02d:%02d".format(sign, a / 60, a % 60)))
    }

    private fun settle(rounds: Int = 40) {
        repeat(rounds) {
            Thread.sleep(25) // сторы грузятся на IO и Default — им нужно настоящее время
            looper.idleFor(Duration.ofMillis(100))
        }
    }

    private fun launch(tab: String?): ActivityController<MainActivity> {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(app, MainActivity::class.java)
        if (tab != null) intent.putExtra(MainActivity.EXTRA_TAB, tab)
        val c = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        settle()
        return c
    }

    private fun shot(c: ActivityController<MainActivity>, name: String) {
        settle(8)
        c.get().window.decorView.captureRoboImage(File(dir, "$name.png").absolutePath)
    }

    /** Все окна разом: листы (`PaperSheet`) живут своим окном поверх вкладки. */
    private fun screen(name: String) {
        settle(8)
        com.github.takahirom.roborazzi.captureScreenRoboImage(File(dir, "$name.png").absolutePath)
    }

    /** Все окна Compose приложения, верхнее — первым (`WindowManagerGlobal.mViews`). */
    private fun roots(c: ActivityController<MainActivity>): List<ViewRootForTest> {
        val views = runCatching {
            val g = Class.forName("android.view.WindowManagerGlobal")
            val inst = g.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            (g.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<View>).toList()
        }.getOrDefault(emptyList())
        return (views.reversed() + c.get().window.decorView).mapNotNull { it.composeRoot() }.distinct()
    }

    private fun View.composeRoot(): ViewRootForTest? = when (this) {
        is ViewRootForTest -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).composeRoot() }
        else -> null
    }

    private fun SemanticsNode.label(): String =
        (config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "") + " " +
            (config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "")

    /** Тап по тому, что видно на экране, — по тексту или подписи значка. */
    private fun tap(c: ActivityController<MainActivity>, what: String, exact: Boolean = false): Boolean {
        // Листы и боковая панель — своими окнами поверх вкладки: верхнее окно — первым.
        val nodes = roots(c).flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        val node = nodes.firstOrNull { n ->
            val l = n.label().trim()
            if (exact) l == what || l.split(" ").contains(what) else l.contains(what)
        } ?: return false.also { println("shots: не нашёл «$what» среди: " + nodes.map { it.label().trim() }.filter { it.isNotBlank() }.take(60).joinToString(" | ")) }
        var n: SemanticsNode? = node
        while (n != null) {
            n.config.getOrNull(SemanticsActions.OnClick)?.action?.let { it(); settle(); return true }
            n = n.parent
        }
        println("shots: «$what» не нажимается")
        return false
    }

    /** Набрать текст в первое поле ввода на экране — как пальцем. */
    private fun type(c: ActivityController<MainActivity>, text: String): Boolean {
        val root = c.get().window.decorView.composeRoot() ?: return false
        val node = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            .firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null } ?: return false.also { println("shots: поля нет") }
        node.config[SemanticsActions.SetText].action?.invoke(androidx.compose.ui.text.AnnotatedString(text))
        settle()
        return true
    }

    /** CRM на телефоне — как веб: боковина (язычок у края) с проектами под клиентами, клиент, сделка, «откуда он», «Люди», поиск. */
    private fun crm() {
        // Первый запуск вкладки в прогоне рисует её без копии — прогреть и закрыть.
        close(launch(MainActivity.TAB_TODOIST))
        var c = launch(MainActivity.TAB_TODOIST)
        if (tap(c, "Показать разделы")) {
            tap(c, "раскрыть все")
            screen("crm-1-nav-clients")
            if (tap(c, "Бета Групп")) shot(c, "crm-2-client")
            // Задание 13: «Переименовать» клиента — лист с именем; имя другого клиента — отказ словами.
            if (tap(c, "Переименовать", exact = true)) {
                screen("crm-11-rename")
                if (typeTop(c, "орион логистик")) screen("crm-12-rename-clash")
                if (typeTop(c, "Бета Холдинг") && tap(c, "Переименовать", exact = true)) shot(c, "crm-13-renamed")
            }
            if (tap(c, "финдиректор")) screen("crm-3-person-where")
        }
        close(c)
        RuntimeEnvironment.setQualifiers(OUTER_TALL)
        c = launch(MainActivity.TAB_TODOIST)
        settle()
        // Раскрытое помнится: клиенты уже раскрыты с прошлого запуска.
        if (tap(c, "Показать разделы") && tap(c, "финмодель 2027")) shot(c, "crm-4-deal")
        close(c)
        c = launch(MainActivity.TAB_TODOIST)
        if (tap(c, "Показать разделы") && tap(c, "Люди", exact = true)) shot(c, "crm-5-people")
        close(c)
        // Задание 6: «Новое» — «Подскажи», «Поставил», «Сделано само» свёрнуто и развёрнуто.
        c = launch(MainActivity.TAB_TODOIST)
        if (tap(c, "Новое")) {
            shot(c, "crm-7-novoe")
            if (tap(c, "Показать", exact = true)) shot(c, "crm-8-sdelano-samo")
        }
        close(c)
        // Задание 8: на Воронке строка Claude видит сделки экрана.
        c = launch(MainActivity.TAB_TODOIST)
        if (tap(c, "Показать разделы") && tap(c, "Воронка", exact = true)) shot(c, "crm-10-voronka")
        close(c)
        RuntimeEnvironment.setQualifiers(OUTER)
        c = launch(MainActivity.TAB_TODOIST)
        if (tap(c, "поиск: клиенты") && type(c, "бета")) shot(c, "crm-6-search")
        close(c)
    }

    /** Набрать текст в поле верхнего окна (лист живёт своим окном поверх вкладки). */
    private fun typeTop(c: ActivityController<MainActivity>, text: String): Boolean {
        val node = roots(c).firstNotNullOfOrNull { r ->
            r.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null }
        } ?: return false.also { println("shots: поля нет") }
        node.config[SemanticsActions.SetText].action?.invoke(androidx.compose.ui.text.AnnotatedString(text))
        settle()
        return true
    }

    private fun close(c: ActivityController<MainActivity>) {
        c.pause().stop().destroy()
        settle(4)
    }

    /**
     * Витрина набора Правки 4.0 — без данных и без вкладок: экран деталей
     * в отдельной активити, высокой, чтобы влезли все состояния.
     */
    @Test
    fun kit() {
        if (!want("kit")) return
        pinEvening()
        RuntimeEnvironment.setQualifiers("w411dp-h5200dp-420dpi")
        val c = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        c.get().setContent {
            ru.zf.pravka.ui.PravkaTheme { ru.zf.pravka.ui.ShowcaseScreen(onBack = {}) }
        }
        settle(20)
        c.get().window.decorView.captureRoboImage(File(dir, "kit-showcase.png").absolutePath)
        c.pause().stop().destroy()
    }

    @Test
    fun shots() {
        pinEvening()
        val app = RuntimeEnvironment.getApplication() as PravkaApp
        // Не владелец: у профиля владельца Деньги подмешивают настоящие остатки
        // по счетам из заводских файлов, а снимки уходят наружу.
        app.profileStore.save(Profile("sasha-shots", "Саша", false, Profile.Mode.entries.toSet()))
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO)
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        runBlocking {
            app.settings.setUpdAuto(false)
            app.settings.setFoodToIcu(false)
            app.settings.setFoodToRibbon(false)
            Seed.ribbon(app)
            Seed.phone(app)
            Seed.sport(app)
            Seed.dela(app)
            Seed.food(app)
            Seed.money(app)
        }
        Seed.weather(app)
        Seed.pravka(app)
        settle(20)

        if (want("overlay")) overlays()

        // ---- Правка 5.0, этап 6: Спорт, Еда, Деньги, Телефон в виде v2 — обе темы, оба экрана Fold
        if (want("s6")) screens6()

        // ---- режимы по одному (Правка 4.0): сложенный, целиком, разворот
        for ((g, tab) in listOf(
            "zasechka" to MainActivity.TAB_ZASECHKA,
            "dela" to MainActivity.TAB_TODOIST,
            "sport" to MainActivity.TAB_SPORT,
            "food" to MainActivity.TAB_FOOD,
            "money" to MainActivity.TAB_MONEY,
            "pravka" to MainActivity.TAB_PRAVKA,
        )) {
            if (!want(g)) continue
            var m = launch(tab); shot(m, "$g-1-folded")
            // Второй раздел режима — тем же окном (макеты 07, 09, 11).
            when (g) {
                "dela" -> {
                    // Задание 11: карточка «Отбить» с «Сегодня», «Все дела» уровнями, фильтр с завода.
                    if (tap(m, "Ольга: ответить про сроки")) screen("$g-7-card")
                    close(m); m = launch(tab)
                    if (tap(m, "Новое")) shot(m, "$g-4-novoe")
                    if (tap(m, "Все дела")) shot(m, "$g-6-vse")
                    // Этап 4 «Правки 5.0»: «Сегодня» отдельно от «Сейчас» — срок сегодня, «Моё» ниже.
                    if (tap(m, "Сегодня")) shot(m, "$g-11-segodnya")
                    close(m); m = launch(tab)
                    // Фильтр с завода: «Отбить — по людям».
                    if (tap(m, "Показать разделы") && tap(m, "Отбить — по людям")) {
                        shot(m, "$g-8-filter")
                        // «Только одно»: список людей с поиском, выбор — одна ветка.
                        if (tap(m, "Все люди")) {
                            screen("$g-9-pick")
                            if (tap(m, "Ольга", exact = true)) shot(m, "$g-10-only")
                        }
                    }
                    close(m); m = launch(tab)
                    // Боковина — шторкой поверх по язычку у края (13.10.2026; раньше ☰, баг №1).
                    if (tap(m, "Показать разделы")) screen("$g-5-nav")
                }
                "sport" -> if (tap(m, "Путь", exact = true)) shot(m, "$g-4-put")
                "money" -> if (tap(m, "Журнал", exact = true)) shot(m, "$g-4-zhurnal")
            }
            close(m)
            RuntimeEnvironment.setQualifiers(OUTER_TALL)
            m = launch(tab); shot(m, "$g-2-tall"); close(m)
            RuntimeEnvironment.setQualifiers(INNER)
            m = launch(tab); shot(m, "$g-3-wide"); close(m)
            RuntimeEnvironment.setQualifiers(OUTER)
        }

        // ---- Дела, задание 4 (docs/dela-phone-4.md): проекты под клиентом, карточки, люди, поиск
        if (want("crm")) crm()

        // ---- Круг и дерево жизни (задание 14, docs/dela-phone-14.md): обзор, три вида дерева, место дела в карточке
        if (want("krug")) {
            // Вкладка дорисовывает список не сразу: сначала снимок «Сегодня» (он ждёт кадры), потом нажатия.
            var k = launch(MainActivity.TAB_TODOIST)
            shot(k, "krug-0-today")
            if (tap(k, "Круг жизни")) shot(k, "krug-1-krug")
            if (tap(k, "Дерево жизни")) {
                shot(k, "krug-2-tree")
                if (tap(k, "Колонки", exact = true)) shot(k, "krug-3-cols")
                if (tap(k, "Список", exact = true)) shot(k, "krug-4-list")
            }
            // Вид дерева помнит устройство — следующему снимку снова «Дерево».
            runBlocking { app.delaStore.setGroup("tree:mode", "tree") }
            close(k)
            // Высоким окном: список рисует только видимое, а шаг к цели — ниже первого экрана.
            RuntimeEnvironment.setQualifiers(OUTER_TALL)
            k = launch(MainActivity.TAB_TODOIST)
            settle(8)
            if (tap(k, "Круг жизни")) shot(k, "krug-6-tall")
            if (tap(k, "Дерево жизни")) {
                shot(k, "krug-7-tree-tall")
                // Карточка шага: «Место в дереве» под названием.
                if (tap(k, "Купить протеин")) screen("krug-5-card")
            }
            close(k)
            RuntimeEnvironment.setQualifiers(INNER)
            k = launch(MainActivity.TAB_TODOIST)
            settle(8)
            if (tap(k, "Дерево жизни")) {
                shot(k, "krug-8-wide")
                // Цель справа: её место, «Под целью …» и шаги.
                if (tap(k, "Сильное тело к лету")) shot(k, "krug-9-wide-goal")
            }
            close(k)
            RuntimeEnvironment.setQualifiers(OUTER)
        }

        // ---- «Сегодня» (Правка 4.0): сложенный, целиком, разворот
        if (want("today")) {
            var t = launch(null); shot(t, "today-01-folded")
            // Тап по погоде — лист: часы и десять дней (баг №4).
            if (tap(t, "утро")) screen("today-02-weather")
            close(t)
            RuntimeEnvironment.setQualifiers(OUTER_TALL)
            t = launch(null); shot(t, "today-03-tall"); close(t)
            RuntimeEnvironment.setQualifiers(INNER)
            t = launch(null); shot(t, "today-05-wide"); close(t)
            RuntimeEnvironment.setQualifiers(OUTER)
        }

        // ---- Засечка v2 (Правка 5.0, этап 3) отдельно — быстрый прогон без остальных экранов
        if (want("z5")) zasechka5()

        // ---- «Сегодня» v2 (Правка 5.0, этап 2) отдельно — быстрый прогон без остальных экранов
        if (want("t5")) {
            for ((prefix, q) in listOf("outer" to OUTER, "inner" to INNER)) {
                RuntimeEnvironment.setQualifiers(q)
                for (theme in listOf("light", "dark")) {
                    runBlocking { app.settings.setLookTheme(theme) }
                    settle(4)
                    val t = launch(null); shot(t, "$prefix-v2-$theme-1-today"); close(t)
                    today5(prefix, theme)
                }
            }
            runBlocking { app.settings.setLookTheme("system") }
            RuntimeEnvironment.setQualifiers(OUTER)
        }

        // ---- сложенный: внешний экран
        var c: ActivityController<MainActivity>
        if (want("outer")) {
        kovcheg("outer")
        c = launch(MainActivity.TAB_PRAVKA); shot(c, "outer-01-pravka"); close(c)
        c = launch(MainActivity.TAB_ZASECHKA); shot(c, "outer-02-zasechka")
        if (tap(c, "статистика")) shot(c, "outer-09-report")
        close(c)
        c = launch(MainActivity.TAB_TODOIST); shot(c, "outer-03-dela-utro")
        if (tap(c, "Новое")) shot(c, "outer-03b-dela-novoe")
        if (tap(c, "Все дела")) shot(c, "outer-03c-dela-vse")
        close(c)
        c = launch(MainActivity.TAB_SPORT); shot(c, "outer-04-sport")
        if (tap(c, "Путь", exact = true)) shot(c, "outer-04b-sport-put")
        close(c)
        c = launch(MainActivity.TAB_FOOD); shot(c, "outer-05-food"); close(c)
        c = launch(MainActivity.TAB_MONEY); shot(c, "outer-06-money")
        if (tap(c, "Журнал", exact = true)) shot(c, "outer-06b-money-zhurnal")
        close(c)
        // «Ещё» — в меню Ковчега строкой «Настройки телефона» (Правка 5.0; в 4.0 — аватар «С»).
        c = launch(null)
        if (tap(c, "Все сервисы Ковчега") && tap(c, "Настройки телефона")) shot(c, "outer-07-more")
        close(c)
        c = launch(MainActivity.TAB_SETTINGS); shot(c, "outer-08-settings"); close(c)
        // Баги и предложения (07.10.2026): две записи — новая и сделанная сборкой.
        kotlinx.coroutines.runBlocking {
            val a = app.feedbackStore.add("подпись на плитке пульс покоя обрезается сделай чтобы влезала", "Д", "3.0.787")
            app.feedbackStore.setText(a.num, "Подпись на плитке «Пульс покоя» обрезается — сделай, чтобы влезала.")
            val b = app.feedbackStore.add("в деньгах хочу видеть траты за вчера сверху", "₽", "3.0.787")
            app.feedbackStore.setStatus(b.num, ru.zf.pravka.data.FeedbackStore.DONE, "Деньги: «вчера» — первой строкой")
        }
        c = launch(MainActivity.TAB_FEEDBACK); shot(c, "outer-11-feedback"); close(c)
        // «Настройки → Баги» — история починок (07.10.2026).
        c = launch(MainActivity.TAB_SETTINGS)
        if (tap(c, "Баги", exact = true)) shot(c, "outer-12-bugs")
        close(c)
        c = launch(MainActivity.TAB_ZASECHKA)
        if (tap(c, "Созвон: бюджет")) screen("outer-10-sheet-entry")
        close(c)
        }

        // ---- длинные ленты: вкладка целиком
        if (want("tall")) {
        RuntimeEnvironment.setQualifiers(OUTER_TALL)
        for ((tab, name) in listOf(
            MainActivity.TAB_ZASECHKA to "tall-02-zasechka",
            MainActivity.TAB_TODOIST to "tall-03-dela",
            MainActivity.TAB_SPORT to "tall-04-sport",
            MainActivity.TAB_FOOD to "tall-05-food",
            MainActivity.TAB_MONEY to "tall-06-money",
        )) {
            c = launch(tab); shot(c, name); close(c)
        }
        c = launch(MainActivity.TAB_ZASECHKA)
        if (tap(c, "статистика")) shot(c, "tall-09-report")
        close(c)
        }

        // ---- разложенный: внутренний экран
        if (want("inner")) {
        RuntimeEnvironment.setQualifiers(INNER)
        kovcheg("inner")
        for ((tab, name) in listOf(
            MainActivity.TAB_PRAVKA to "inner-01-pravka",
            MainActivity.TAB_ZASECHKA to "inner-02-zasechka",
            MainActivity.TAB_TODOIST to "inner-03-dela",
            MainActivity.TAB_SPORT to "inner-04-sport",
            MainActivity.TAB_FOOD to "inner-05-food",
            MainActivity.TAB_MONEY to "inner-06-money",
        )) {
            c = launch(tab); shot(c, name); close(c)
        }
        c = launch(MainActivity.TAB_ZASECHKA)
        if (tap(c, "статистика")) shot(c, "inner-09-report")
        close(c)
        }

        // ---- «Правка» 5.0 (этап 5): история диктовок телефона и компа — последней, её данные перекрывают «Правку» 4.0
        if (want("history")) history()

        // ---- Этап 7: меню и вид из Свода — после всех, записи Свода не стираются, а остальным кадрам нужно заводское
        if (want("svod")) svod()
    }

    /**
     * Этап 7 (Свод `ui.menu` и `ui.look`): выдуманная запись Свода — «Пара» переставлена первой с новой подписью,
     * «Кино» стало «Фильмами», «Быт» поднялся выше «Родительства», «Творчества» нет; акцент обеих тем — зелёный.
     * «Сегодня», открытое меню и Дела — обе темы, оба экрана Fold.
     */
    private fun svod() {
        val app = RuntimeEnvironment.getApplication() as PravkaApp
        val menu = org.json.JSONObject(
            """{"v": 1, "directions": [
                 {"name": "Пара", "sub": "вдвоём хорошо", "items": [{"key": "kino", "name": "Фильмы"}]},
                 {"name": "Здоровье", "sub": "сильное тело, ясная голова", "items": ["sport", "eda"]},
                 {"name": "Ум", "sub": "много знаю и узнаю", "items": ["knigi"]},
                 {"name": "Дело", "sub": "растёт вглубь и вширь", "items": ["dela", "meetings"]},
                 {"name": "Деньги", "sub": "деньги — не ограничение", "items": ["dengi"]},
                 {"name": "Круг", "sub": "знаю многих", "items": ["crm"]},
                 {"name": "Быт", "sub": "дома хорошо всем пятерым", "items": ["dom", "vpn"]},
                 {"name": "Родительство", "sub": "сыновья растут сильными", "items": ["deti", "shabbat"]}],
               "create": null}""",
        )
        val look = org.json.JSONObject(
            """{"v": 1, "light": {"accent": "#2f7d4f", "accent-soft": "#eaf2ed", "accent-ink": "#ffffff", "info": "#2f7d4f"},
               "dark": {"accent": "#7cc59a", "accent-soft": "#24352b", "accent-ink": "#141414", "info": "#7cc59a"}}""",
        )
        runBlocking {
            app.svodStore.applyAll(
                listOf(
                    ru.zf.pravka.core.Svod.Entry(ru.zf.pravka.core.Kovcheg.MENU_KEY, value = menu.toString(), rev = 2, author = "web"),
                    ru.zf.pravka.core.Svod.Entry(ru.zf.pravka.core.Kovcheg.LOOK_KEY, value = look.toString(), rev = 1, author = "web"),
                ),
                System.currentTimeMillis(), fresh = false,
            )
        }
        settle(8)
        for ((prefix, q) in listOf("outer" to OUTER, "inner" to INNER)) {
            RuntimeEnvironment.setQualifiers(q)
            for (theme in listOf("light", "dark")) {
                runBlocking { app.settings.setLookTheme(theme) }
                settle(4)
                val c = launch(null)
                shot(c, "svod-$prefix-$theme-1-today")
                if (tap(c, "Все сервисы Ковчега")) shot(c, "svod-$prefix-$theme-2-menu")
                close(c)
                val d = launch(MainActivity.TAB_TODOIST); shot(d, "svod-$prefix-$theme-3-dela"); close(d)
            }
        }
        runBlocking { app.settings.setLookTheme("system") }
        RuntimeEnvironment.setQualifiers(OUTER)
    }

    /**
     * Экран «Правка» (Правка 5.0, этап 5): сложенный — список, фильтр «Компьютер», лист диктовки;
     * разложенный — список и диктовка целиком справа (телефонная с гарнитуры и компьютерная). Обе темы.
     */
    private fun history() {
        val app = RuntimeEnvironment.getApplication() as PravkaApp
        Seed.pravkaHistory(app)
        settle(8)
        for (theme in listOf("light", "dark")) {
            runBlocking { app.settings.setLookTheme(theme) }
            settle(4)
            RuntimeEnvironment.setQualifiers(OUTER)
            var c = launch(MainActivity.TAB_PRAVKA)
            shot(c, "history-$theme-1-folded")
            if (tap(c, "Компьютер", exact = true)) {
                shot(c, "history-$theme-2-pc")
                // Лист диктовки — тап по строке, тем же окном.
                if (tap(c, "Так, погоди")) screen("history-$theme-3-sheet")
            }
            close(c)
            RuntimeEnvironment.setQualifiers(INNER)
            c = launch(MainActivity.TAB_PRAVKA)
            shot(c, "history-$theme-4-wide")
            if (tap(c, "Созвон с Ольгой")) shot(c, "history-$theme-5-wide-headset")
            if (tap(c, "Коллеги, добрый день")) shot(c, "history-$theme-6-wide-pc")
            close(c)
            RuntimeEnvironment.setQualifiers(OUTER)
        }
        runBlocking { app.settings.setLookTheme("system") }
        settle(4)
    }

    /**
     * Вид v2 Ковчега (Правка 5.0, этап 1): «Сегодня», открытое меню значка и
     * два режима — в светлой и тёмной теме, тем размером, что стоит сейчас.
     */
    private fun kovcheg(prefix: String) {
        val app = RuntimeEnvironment.getApplication() as PravkaApp
        for (theme in listOf("light", "dark")) {
            runBlocking { app.settings.setLookTheme(theme) }
            settle(4)
            var c = launch(null)
            shot(c, "$prefix-v2-$theme-1-today")
            if (tap(c, "Все сервисы Ковчега")) shot(c, "$prefix-v2-$theme-2-menu")
            close(c)
            c = launch(MainActivity.TAB_TODOIST); shot(c, "$prefix-v2-$theme-3-dela")
            // Этап 4: карточка дела с молнией «В «Сейчас»» — на сложенном листом, на разложенном справа
            // (и лист «Карточка» поверх).
            if (tap(c, "Ольга: ответить про сроки")) {
                if (prefix == "inner") {
                    shot(c, "$prefix-v2-$theme-5-dela-pane")
                    if (tap(c, "Карточка", exact = true)) screen("$prefix-v2-$theme-6-dela-card")
                } else screen("$prefix-v2-$theme-5-dela-card")
            }
            close(c)
            // Боковина Дел (13.10.2026): на сложенном — шторка поверх по язычку у края; на разложенном — открыта с
            // завода, язычок прячет её (выбор помнит устройство — после снимка вернуть как было).
            c = launch(MainActivity.TAB_TODOIST)
            if (prefix == "inner") {
                if (tap(c, "Спрятать разделы")) {
                    shot(c, "$prefix-v2-$theme-7-dela-side-off")
                    tap(c, "Показать разделы")
                }
            } else if (tap(c, "Показать разделы")) shot(c, "$prefix-v2-$theme-7-dela-side")
            close(c)
            c = launch(MainActivity.TAB_ZASECHKA); shot(c, "$prefix-v2-$theme-4-zasechka"); close(c)
            today5(prefix, theme)
        }
        runBlocking { app.settings.setLookTheme("system") }
        settle(4)
        // «Вид 4.0» — откат на время переезда: прежнее стекло и шапки целиком.
        runBlocking { app.settings.setLookV4(true) }
        settle(4)
        var c = launch(null); shot(c, "$prefix-v4-1-today"); close(c)
        // Дела в виде 4.0 — после этапа 4 («Сейчас» первым разделом) прежние сегменты и стекло на месте.
        c = launch(MainActivity.TAB_TODOIST); shot(c, "$prefix-v4-3-dela"); close(c)
        runBlocking { app.settings.setLookV4(false) }
        settle(4)
    }

    /**
     * Этап 6 (docs/redesign-5-plan.md): экраны Спорта («Тело»), Еды, Денег и
     * Телефона по холсту — сложенный (и он же целиком, высоким окном) и
     * разложенный, в светлой и тёмной теме. Разделы — нажатием, как пальцем.
     */
    private fun screens6() {
        val app = RuntimeEnvironment.getApplication() as PravkaApp
        // Экран → вкладка и разделы, которые снять кроме первого (подпись вкладки → имя кадра).
        val screens = listOf(
            Triple("sport", MainActivity.TAB_SPORT, listOf("Путь" to "put", "Журнал" to "zhurnal")),
            Triple("food", MainActivity.TAB_FOOD, listOf("Неделя" to "nedelya", "Рацион" to "racion")),
            Triple("money", MainActivity.TAB_MONEY, listOf("Разобрать" to "razobrat", "ДДС" to "dds", "Баланс" to "balans", "Журнал" to "zhurnal")),
            Triple("phone", MainActivity.TAB_SETTINGS, emptyList()),
        )
        for (theme in listOf("light", "dark")) {
            runBlocking { app.settings.setLookTheme(theme) }
            settle(4)
            for ((size, q) in listOf("folded" to OUTER, "tall" to OUTER_TALL, "wide" to INNER)) {
                RuntimeEnvironment.setQualifiers(q)
                for ((name, tab, parts) in screens) {
                    var c = launch(tab)
                    shot(c, "s6-$name-$size-$theme")
                    // Разделы — на сложенном и развороте; высокое окно — только первый, целиком.
                    if (size != "tall") {
                        for ((label, key) in parts) {
                            if (tap(c, label, exact = true)) shot(c, "s6-$name-$key-$size-$theme")
                        }
                    }
                    close(c)
                }
            }
        }
        RuntimeEnvironment.setQualifiers(OUTER)
        runBlocking { app.settings.setLookTheme("system") }
        settle(4)
        // «Вид 4.0» — откат: те же экраны должны остаться прежними (стекло, части, шапки 4.0).
        runBlocking { app.settings.setLookV4(true) }
        settle(4)
        for ((name, tab, _) in screens) {
            val c = launch(tab)
            shot(c, "s6-$name-v4")
            close(c)
        }
        runBlocking { app.settings.setLookV4(false) }
        settle(4)
    }

    /**
     * Засечка вида v2 (Правка 5.0, этап 3; холст `Zasechka.dc.html`):
     * сложенный, высокий сложенный (вкладка целиком) и разложенный — в светлой
     * и тёмной теме; на сложенном и высоком — ещё лента «Весь день» раскрытой,
     * на сложенном светлом — редактор «Поправить». Плюс «Вид 4.0» — прежняя вкладка.
     */
    private fun zasechka5() {
        val app = RuntimeEnvironment.getApplication() as PravkaApp
        for (theme in listOf("light", "dark")) {
            runBlocking { app.settings.setLookTheme(theme) }
            settle(4)
            // «wide-tall» — разворот высоким окном: левая колонка целиком (итог, автопилот, телефон).
            for ((size, q) in listOf("folded" to OUTER, "tall" to OUTER_TALL, "wide" to INNER, "wide-tall" to INNER_TALL)) {
                RuntimeEnvironment.setQualifiers(q)
                var c = launch(MainActivity.TAB_ZASECHKA)
                shot(c, "z5-$size-$theme")
                if (!size.startsWith("wide") && tap(c, "Весь день", exact = true)) shot(c, "z5-$size-all-$theme")
                close(c)
                if ((size == "folded" || size == "wide") && theme == "light") {
                    if (size == "folded") {
                        c = launch(MainActivity.TAB_ZASECHKA)
                        if (tap(c, "Поправить", exact = true)) screen("z5-$size-edit-$theme")
                        close(c)
                    }
                    // Вчера: тап по названию — навигатор дня, «‹» — день назад; «Сейчас» и строки «сказать» нет.
                    c = launch(MainActivity.TAB_ZASECHKA)
                    if (tap(c, "Засечка", exact = true) && tap(c, "день назад")) shot(c, "z5-$size-yesterday-$theme")
                    close(c)
                }
            }
        }
        RuntimeEnvironment.setQualifiers(OUTER)
        runBlocking { app.settings.setLookTheme("system") }
        settle(4)
        // «Вид 4.0» — откат: прежняя вкладка с циферблатом, лентой хроники и строкой «стоп».
        runBlocking { app.settings.setLookV4(true) }
        settle(4)
        var c = launch(MainActivity.TAB_ZASECHKA); shot(c, "z5-v4-folded"); close(c)
        RuntimeEnvironment.setQualifiers(INNER)
        c = launch(MainActivity.TAB_ZASECHKA); shot(c, "z5-v4-wide"); close(c)
        RuntimeEnvironment.setQualifiers(OUTER)
        runBlocking { app.settings.setLookV4(false) }
        settle(4)
    }

    /**
     * «Сегодня» вида v2 (Правка 5.0, этап 2): раскрытая погода и раскрытый
     * дайджест; на сложенном — ещё вся лента карточек высоким окном.
     */
    private fun today5(prefix: String, theme: String) {
        var c = launch(null)
        if (tap(c, "ощ.")) shot(c, "$prefix-t5-$theme-1-weather")
        close(c)
        c = launch(null)
        if (tap(c, "раскрыть")) shot(c, "$prefix-t5-$theme-2-digest")
        close(c)
        if (prefix == "outer") {
            RuntimeEnvironment.setQualifiers(OUTER_TALL)
            c = launch(null); shot(c, "$prefix-t5-$theme-3-tall"); close(c)
            RuntimeEnvironment.setQualifiers(OUTER)
        }
    }

    // ------------------------------------------------------------ кнопки на стекле

    /**
     * Окна службы доступности — кнопки, пилюля, плашки — поверх чужих
     * приложений. Снимаются на прозрачном фоне каждое на своём месте; фон
     * «чужого приложения» подкладывается потом (`docs/design/tools`).
     */
    private fun overlays() {
        val ctl = Robolectric.buildService(ru.zf.pravka.trigger.PravkaAccessibilityService::class.java).create()
        val svc = ctl.get()
        android.accessibilityservice.AccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(svc)
        settle()
        overlay("overlay-01-dock")

        svc.stackSettings?.toggleFan(); settle()
        overlay("overlay-02-fan")
        svc.stackSettings?.hideFan(); settle()

        svc.floatingButton?.let { b ->
            b.setRecording(true)
            b.showTicker(); settle(10)
            b.updateTicker("созвон с ольгой перенести на четверг на одиннадцать и напомнить ивану про выгрузку", force = true)
            b.setLevel(0.6f); settle(10)
            overlay("overlay-03-pill-listening")
            b.hideTicker(); b.setRecording(false); settle()
        }

        svc.zButton?.let { z ->
            z.showResult(
                "Записал в ленту · 2",
                rows = listOf(
                    ru.zf.pravka.trigger.DictationPill.ResultRow("Созвон: бюджет на IV квартал", "09:50–10:40 · Работа: звонки · Бета Групп", onEdit = {}),
                    ru.zf.pravka.trigger.DictationPill.ResultRow("Залип в ленте", "10:40–10:55 · Потери", onEdit = {}),
                ),
                holdMs = 600_000L,
            ); settle(10)
            overlay("overlay-04-pill-result")
            z.hideNote(); settle()
            z.showAsk("«Обед» с 13:25 · идёт 45 мин", "всё ещё обедаешь?", onYes = {}, onSay = {}); settle(10)
            overlay("overlay-05-pill-ask")
            z.hideNote(); settle()
        }

        svc.rButton?.let { r ->
            r.showTasks(
                header = "Дела из текста · 3",
                rows = listOf(
                    ru.zf.pravka.trigger.RaznoskaButtonController.PlateRow(1, "Иван: прислать выгрузку из 1С", "жду Ивана · напомнить в среду", ""),
                    ru.zf.pravka.trigger.RaznoskaButtonController.PlateRow(2, "Бета: пересчитать ковенанты", "Бета Групп · до 7 окт.", ""),
                    ru.zf.pravka.trigger.RaznoskaButtonController.PlateRow(3, "Позвонить в автосервис", "быстрое · 10 мин", "похоже на #59"),
                ),
                onEdit = {}, onOpen = {}, onSend = { _, _ -> },
            ); settle(10)
            overlay("overlay-06-plate-ok")
            r.hidePlate(); settle()
        }

        with(svc) { showZasechkaMenu() }; settle(10)
        overlay("overlay-07-menu-z")
        svc.zButton?.hideMenu(); settle()

        svc.showFabMenu(); settle(10)
        overlay("overlay-08-menu-p")
        svc.floatingButton?.hideMenu(); settle()

        widgets()

        // Стопка вместо диска: все четыре кнопки разом (у дока видны только «П» и «З»).
        runBlocking { RuntimeEnvironment.getApplication().let { (it as PravkaApp).settings.setDiskMode(false) } }
        settle(30)
        overlay("overlay-09-stack")
        runBlocking { RuntimeEnvironment.getApplication().let { (it as PravkaApp).settings.setDiskMode(true) } }
        settle(30)
    }

    /**
     * Виджет Засечки (11.10.2026): три размера на тёмных обоях — строка 4×1,
     * 4×2 без «ещё раз» и высокий 4×3. Данные — посев снимков (идёт
     * «Друзья в гостях»).
     */
    private fun widgets() {
        val app = RuntimeEnvironment.getApplication() as PravkaApp
        val model = runBlocking { ru.zf.pravka.core.ZWidget.model(app.zasechkaStore.all(), System.currentTimeMillis()) }
        val d = app.resources.displayMetrics.density
        for ((name, size, dims) in listOf(
            Triple("widget-1-row", ru.zf.pravka.core.ZWidget.Size.ROW, 320 to 76),
            Triple("widget-2-actions", ru.zf.pravka.core.ZWidget.Size.ACTIONS, 320 to 150),
            Triple("widget-3-full", ru.zf.pravka.core.ZWidget.Size.FULL, 320 to 200),
            Triple("widget-4-narrow", ru.zf.pravka.core.ZWidget.Size.ACTIONS, 170 to 150),
        )) {
            val (wDp, hDp) = dims
            val rv = ru.zf.pravka.trigger.ZasechkaWidget.views(app, model, size, ru.zf.pravka.core.ZWidget.wide(wDp), true)
            val host = android.widget.FrameLayout(app)
            val v = rv.apply(app, host)
            val w = (wDp * d).toInt()
            val h = (hDp * d).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val pad = (16 * d).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(0xFF3B4A5C.toInt())
            c.translate(pad.toFloat(), pad.toFloat())
            v.draw(c)
            File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Все окна процесса — на своих местах, прозрачный фон: чужое приложение подложат потом. */
    private fun overlay(name: String) {
        settle(8)
        val global = Class.forName("android.view.WindowManagerGlobal")
        val g = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = (global.getDeclaredField("mViews").apply { isAccessible = true }.get(g) as List<View>).toList()
        @Suppress("UNCHECKED_CAST")
        val params = (global.getDeclaredField("mParams").apply { isAccessible = true }.get(g) as List<android.view.WindowManager.LayoutParams>).toList()
        val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
        val bmp = android.graphics.Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        for ((v, p) in views.zip(params)) {
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) continue
            canvas.save()
            canvas.translate(p.x.toFloat() + v.translationX, p.y.toFloat() + v.translationY)
            if (v.alpha < 1f) canvas.saveLayerAlpha(0f, 0f, v.width.toFloat(), v.height.toFloat(), (v.alpha * 255).toInt())
            v.draw(canvas)
            canvas.restoreToCount(1)
            println("shots: окно ${v.javaClass.simpleName} ${p.x},${p.y} ${v.width}×${v.height} a=${v.alpha}")
        }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
