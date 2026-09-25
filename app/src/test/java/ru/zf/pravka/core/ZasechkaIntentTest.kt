package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.zf.pravka.core.ZasechkaIntent.Kind

// Развилка сказанного в Засечку (владелец, 25.09.2026): «запиши мысль, я тут
// подумал, запиши коммент — коммент к делу; запиши еду, запиши блюдо — Едой;
// запиши дело, надо не забыть, не забыть, запиши важное — запись дел».
class ZasechkaIntentTest {

    private fun route(text: String) = ZasechkaIntent.route(text)

    private fun assertRoute(kind: Kind, text: String, said: String) {
        val r = route(said)
        assertEquals("вид для «$said»", kind, r.kind)
        assertEquals("текст для «$said»", text, r.text)
    }

    // ---- мысль к текущему делу ----

    @Test
    fun `запиши мысль - коммент к делу`() {
        assertRoute(Kind.COMMENT, "Надо переписать модель", "запиши мысль надо переписать модель")
        assertRoute(Kind.COMMENT, "Надо переписать модель.", "Запиши мысль. Надо переписать модель.")
        assertRoute(Kind.COMMENT, "Обсудили бюджет", "запиши коммент к делу обсудили бюджет")
        assertRoute(Kind.COMMENT, "Обсудили бюджет", "Запиши комментарий: обсудили бюджет")
        assertRoute(Kind.COMMENT, "Взять Борю на бокс", "запиши идею взять Борю на бокс")
    }

    @Test
    fun `я тут подумал - коммент, и «что» после него не тащим`() {
        assertRoute(Kind.COMMENT, "Илья прав", "Я тут подумал, что Илья прав")
        assertRoute(Kind.COMMENT, "Стоит позвонить", "Так, я вот подумал: стоит позвонить")
        assertRoute(Kind.COMMENT, "Сделка закроется", "думаю что сделка закроется")
        assertRoute(Kind.COMMENT, "Сделку надо дожимать", "Ещё мысль: сделку надо дожимать")
        assertRoute(Kind.COMMENT, "Подход с таблицей лишний", "подумалось подход с таблицей лишний")
    }

    @Test
    fun `думать как занятие - это дело в ленту, а не мысль`() {
        // «Думаю над» — работа, её время и пишет лента.
        assertRoute(Kind.ENTRY, "думаю над презентацией", "думаю над презентацией")
        assertRoute(Kind.ENTRY, "Работаю над мыслью Ильи", "Работаю над мыслью Ильи")
        // Посреди рассказа «думаю» — не команда.
        assertRoute(
            Kind.ENTRY,
            "Поехал к клиенту, думаю по дороге про сделку",
            "Поехал к клиенту, думаю по дороге про сделку",
        )
        assertRoute(Kind.ENTRY, "Мыслительный марафон с Серёжей", "Мыслительный марафон с Серёжей")
    }

    // ---- Еда ----

    @Test
    fun `запиши еду - слушает Еда`() {
        assertRoute(Kind.FOOD, "Борщ и две котлеты", "запиши еду борщ и две котлеты")
        assertRoute(Kind.FOOD, "Овсянка с ягодами", "Запиши блюдо: овсянка с ягодами")
        assertRoute(Kind.FOOD, "Творог двести граммов", "запиши, что я съел творог двести граммов")
        assertRoute(Kind.FOOD, "Витамин D", "запиши что выпил витамин D")
        assertRoute(Kind.FOOD, "Яблоко", "запиши перекус яблоко")
    }

    @Test
    fun `еду без команды - это дорога, а обед - дело ленты`() {
        assertRoute(Kind.ENTRY, "Еду к Илье", "Еду к Илье")
        assertRoute(Kind.ENTRY, "Обед с Марианной", "Обед с Марианной")
        // «Запиши обед с часу до двух» — вставка в ленту, её Засечка умеет сама.
        assertRoute(Kind.ENTRY, "запиши обед с часу до двух", "запиши обед с часу до двух")
    }

    // ---- Дела ----

    @Test
    fun `запиши дело и не забыть - запись дел`() {
        assertRoute(Kind.TASKS, "Позвонить Илье завтра", "запиши дело позвонить Илье завтра")
        assertRoute(Kind.TASKS, "Купить молоко и забрать Серёжу", "Надо не забыть купить молоко и забрать Серёжу")
        assertRoute(Kind.TASKS, "Паспорт", "не забыть паспорт")
        assertRoute(Kind.TASKS, "Продлить страховку", "запиши важное: продлить страховку")
        assertRoute(Kind.TASKS, "Завтра в десять позвонить в банк", "напомни завтра в десять позвонить в банк")
        assertRoute(Kind.TASKS, "Отчёт Тэйсти и счёт Стаффджет", "запиши дела отчёт Тэйсти и счёт Стаффджет")
        assertRoute(Kind.TASKS, "Созвон с Наташей", "Слушай, запиши задачу созвон с Наташей")
    }

    @Test
    fun `команда в конце фразы тоже считается`() {
        assertRoute(Kind.TASKS, "Купить молоко", "купить молоко, запиши дело")
        assertRoute(Kind.TASKS, "Купить молоко", "купить молоко запиши в дела")
        assertRoute(Kind.COMMENT, "Илья тянет со сделкой", "Илья тянет со сделкой, запиши мысль.")
        assertRoute(Kind.FOOD, "Гречка с курицей", "гречка с курицей запиши еду")
    }

    @Test
    fun `добавь дело - вставка в ленту, а не Todoist`() {
        assertRoute(Kind.ENTRY, "добавь дело обед с часу до двух", "добавь дело обед с часу до двух")
        // А «добавь задачу» — однозначно Todoist.
        assertRoute(Kind.TASKS, "Позвонить маме", "добавь задачу позвонить маме")
    }

    // ---- обычная лента ----

    @Test
    fun `обычная засечка остаётся засечкой`() {
        assertRoute(Kind.ENTRY, "Начал созвон с Ильёй", "Начал созвон с Ильёй")
        assertRoute(Kind.ENTRY, "Закончил", "Закончил")
        assertRoute(Kind.ENTRY, "Запиши, что я поехал к клиенту", "Запиши, что я поехал к клиенту")
        assertRoute(Kind.ENTRY, "", "   ")
    }

    @Test
    fun `одна команда без содержания - вид есть, текста нет`() {
        assertRoute(Kind.COMMENT, "", "запиши мысль")
        assertRoute(Kind.TASKS, "", "Запиши дело.")
    }

    @Test
    fun `что сказать в конце`() {
        assertEquals("записал коммент", ZasechkaIntent.said(Kind.COMMENT))
        assertEquals("записал еду", ZasechkaIntent.said(Kind.FOOD))
        assertEquals("записал дело", ZasechkaIntent.said(Kind.TASKS, count = 1))
        assertEquals("записал дела", ZasechkaIntent.said(Kind.TASKS, count = 3))
    }
}
