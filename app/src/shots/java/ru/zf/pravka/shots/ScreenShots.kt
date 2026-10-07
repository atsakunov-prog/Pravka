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
 * outer, tall, inner, overlay.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = PravkaApp::class, qualifiers = ScreenShots.OUTER)
class ScreenShots {

    companion object {
        /** Внешний экран: 1080×2364, 420 dpi. Строка состояния дорисовывается потом — здесь окно под ней. */
        const val OUTER = "w411dp-h860dp-420dpi"
        /** Внутренний экран: 2076×2152, почти квадрат — колонка навигации слева. */
        const val INNER = "w791dp-h780dp-420dpi"
        /** Длинная лента внешнего экрана: вкладка целиком, без прокрутки. */
        const val OUTER_TALL = "w411dp-h2600dp-420dpi"
    }

    private val dir = File(System.getProperty("pravka.shotsDir") ?: "build/shots").also { it.mkdirs() }

    /** -Pshots=all или через запятую: outer, tall, inner, overlay. */
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
        val root = c.get().window.decorView.composeRoot() ?: return false
        val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
        val node = nodes.firstOrNull { n ->
            val l = n.label().trim()
            if (exact) l == what || l.split(" ").contains(what) else l.contains(what)
        } ?: return false.also { println("shots: не нашёл «$what»") }
        var n: SemanticsNode? = node
        while (n != null) {
            n.config.getOrNull(SemanticsActions.OnClick)?.action?.let { it(); settle(); return true }
            n = n.parent
        }
        println("shots: «$what» не нажимается")
        return false
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
        Seed.pravka(app)
        settle(20)

        if (want("overlay")) overlays()

        // ---- «Сегодня» (Правка 4.0): сложенный, целиком, разворот
        if (want("today")) {
            var t = launch(null); shot(t, "today-01-folded")
            close(t)
            RuntimeEnvironment.setQualifiers(OUTER_TALL)
            t = launch(null); shot(t, "today-03-tall"); close(t)
            RuntimeEnvironment.setQualifiers(INNER)
            t = launch(null); shot(t, "today-05-wide"); close(t)
            RuntimeEnvironment.setQualifiers(OUTER)
        }

        // ---- сложенный: внешний экран
        var c: ActivityController<MainActivity>
        if (want("outer")) {
        c = launch(MainActivity.TAB_PRAVKA); shot(c, "outer-01-pravka"); close(c)
        c = launch(MainActivity.TAB_ZASECHKA); shot(c, "outer-02-zasechka")
        if (tap(c, "статистика")) shot(c, "outer-09-report")
        close(c)
        c = launch(MainActivity.TAB_TODOIST); shot(c, "outer-03-dela-utro")
        if (tap(c, "Новое")) shot(c, "outer-03b-dela-novoe")
        if (tap(c, "Жду")) shot(c, "outer-03c-dela-zhdu")
        close(c)
        c = launch(MainActivity.TAB_SPORT); shot(c, "outer-04-sport")
        if (tap(c, "Путь", exact = true)) shot(c, "outer-04b-sport-put")
        close(c)
        c = launch(MainActivity.TAB_FOOD); shot(c, "outer-05-food"); close(c)
        c = launch(MainActivity.TAB_MONEY); shot(c, "outer-06-money")
        if (tap(c, "Журнал", exact = true)) shot(c, "outer-06b-money-zhurnal")
        close(c)
        c = launch(MainActivity.TAB_PRAVKA)
        if (tap(c, "Ещё", exact = true)) shot(c, "outer-07-more")
        close(c)
        c = launch(MainActivity.TAB_SETTINGS); shot(c, "outer-08-settings"); close(c)
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

        // Стопка вместо диска: все четыре кнопки разом (у дока видны только «П» и «З»).
        runBlocking { RuntimeEnvironment.getApplication().let { (it as PravkaApp).settings.setDiskMode(false) } }
        settle(30)
        overlay("overlay-09-stack")
        runBlocking { RuntimeEnvironment.getApplication().let { (it as PravkaApp).settings.setDiskMode(true) } }
        settle(30)
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
