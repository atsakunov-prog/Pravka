"""Логика Дел поверх базы: синк, операции, виды.

Один код на всех клиентов: API службы (телефон, веб, бот), инструменты Claude
в архиве и импорт. Все ходят ролью службы, поэтому права решает база (RLS),
а журнал пишет триггер — здесь только разбор, проверки и форма ответа.

Операция — словарь {"op": "task.set", "op_id": "<uuid>", ...}. op_id делает
повтор безвредным: телефон без сети копит очередь и шлёт её, пока не получит
ответ, а сервер на повтор отдаёт прежний результат (crm.ops_seen).
"""

from __future__ import annotations

import datetime as dt
import decimal
import logging
import re
import uuid
from collections.abc import Callable
from typing import Any

import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb

from . import db, digest, focus, guard, insights, krug, meetings, people, phone, place, spend, twins, where, zasechka

log = logging.getLogger("dela.store")

# Сколько номеров изменений синк захватывает назад. Номер берётся в начале
# транзакции, а видна она после фиксации: изменение с меньшим номером может
# стать видно позже большего. Окно с запасом перекрывает такие опоздания;
# повтор строки телефону безвреден (он сверяет rev).
OVERLAP = 200

TASK_FIELDS = {
    "title", "notes", "project_id", "deal_id", "owner_id", "ball", "owed", "person_id", "nudge_on", "requested_by",
    "due_date", "due_time", "estimate_min", "money", "want", "focus_on", "labels", "status",
    "source", "source_ref", "remind_at", "remind_place", "pos",
}
# reminded_at в TASK_FIELDS нет: его ставит только сервер (task.reminded от бота).

# Что этот сервер умеет сверх части 1 контракта — в каждом ответе синка и /api/me. Без «remind»
# телефон полей remind_* не шлёт: _pick отверг бы операцию целиком, а с ней и дело.
FEATURES = ["remind", "svod", "people", "dictations", "groups", "where", "krug", "meetings", "tree", "place", "options",
            "digest", "zasechka", "phone", "spend", "digest_crm", "people_new", "people_link", "meeting_touch", "deal_steps",
            "privacy", "context_index", "claude_guard", "nest"]
# «nest» — дела внутри дела (15.10.2026, dela_0030): поле pos у дела (место среди соседей с тем же «Зачем», от 1) в
# task.create и task.set (сменил «Зачем», места не назвал — место снимается), task.done последнего открытого дела
# внутри поднимает родителя (ответ — raised, только хозяину родителя), task.reopen дела внутри возвращает родителю
# прежний срок, если его не правили (ответ — fell); /api/ask знает scope.step («Для этого»), у дела в синке — why_goal
# («Зачем» — цель или дело). Без флага телефон pos не шлёт: старый сервер отверг бы операцию целиком.
# «claude_guard» — защита от петли Claude на сервере (15.10.2026, аудит №12, guard.py): операции claude.guard {action:
# resume|keep, task} (владелец — веб, телефон; бот Ковчега — кнопка в Telegram) и claude.alert_sent (бот Ковчега), поле
# alerts у /api/reminders/due (сообщения владельцу: «защита от петли», сумма дня за порогом), поле guard у вида spend
# (что стоит). Без флага служба встреч сообщений Дел не ждёт и кнопок guard:dela не шлёт.
# «context_index» — контекст по делу (15.10.2026, аудит №13, №67; focus.py): вид context_index — кто связан с делами
# (related) и ключи имён людей и проектов с таблицами матчера (контракт dela-context.json). По флагу служба встреч,
# дайджест и телефон кладут в промпт связанных и названных, а не весь справочник; без флага — как раньше (все люди).
# «privacy» — закон приватности (15.10.2026, аудит №8–11, правило 26; dela_0024–0028): comment.add и interaction.add/set
# принимают private (комментарий «только мне», запись «скрыть от команды»); операции insight.add/set/delete и вид insights
# («Только тебе»), поля insights у видов client, deal, dossier, meeting; строки хронологии — team_sees, итог встречи
# владельцу — с карточки встречи; люди и компании синка — поля по ярусу смотрящего (tier); Свод privacy.team (что команда
# видит из встреч) — веб «Настройки». Без флага клиенты новых полей не шлют: старый сервер отверг бы операцию целиком и
# запомнил отказ по op_id.
# «deal_steps» — шаги и контакт сделки одним правилом (15.10.2026, аудит №2, №3, №7, №28; dela_0022): пока сделка у
# клиента одна живая, его открытые дела без сделки — её шаги (next_task.via_client), записи без сделки — её хронология,
# последний контакт — crm.deal_last_touch (упоминания не в счёт); payload.deal_name в предложениях встреч и дайджеста —
# сделка по точному имени (_names), автоматике у единственной живой сделки клиента сделка ставится сама; interaction.add
# без ключа deal_id — «сделка касания» (crm.touch_deal). По флагу дайджест показывает «CRM на неделю»: до него «нет
# следующего дела» у сделок было наполовину ложным. Старый сервер deal_name молча хранит в payload.
# «people_link» — назвал человека во встрече — он в CRM и сверен с Telegram (15.10.2026, аудит №25/№60): предложение kind
# person, source meeting решает сервер (people.new_person: завести сам, вопрос кнопками, «уже убран»), повтор той же
# находки (source_ref) — прежнее предложение в любом статусе, вариант ответа может нести person (что дописать: telegram_id,
# ник, имена) — так «Люди сами» спрашивают «это он в Telegram?»; meeting.upsert сразу, без переразбора, ставит в
# участники карточки узнанных службой спикеров (голос, ответ владельца). Без флага служба встреч людей не предлагает (старый
# сервер завёл бы «Марию» из одного слова сам), а «Люди сами» не ищут карточки в Telegram.
# «meeting_touch» — запись встречи в хронологии ведёт сервер по карточке встречи (dela_0021, №6): служба встреч свою
# запись (crm_note) не шлёт; клиент, сделка, люди и название в записи — правда Дел, правки владельца доходят.
# «svod» — Свод (crm.svod) в синке и операция svod.set; «people» — person.add / person.merge и вид who
# (одна карточка человека, 06.10.2026). Без них телефон не шлёт этих операций.
# «people_new» — люди сами (dela_0019, 14.10.2026): предложение kind = person — новый человек из Telegram или телефонной
# книжки (вопрос в «Подскажи» или «Сделано само» с «Вернуть»), источник contacts. Без флага служба дайджеста людей не
# предлагает: старый сервер отверг бы и вид, и источник.
# «dictations» — операция dictation.add: текст наговорки, из которой вышли дела (их source_ref = её id),
# для «Нового» веба (06.10.2026).
# «where» — «Где мы» (10.10.2026, where.py): операции where.set / where.off / where.ask и виды where,
# where_avatar — точки семьи для карты Правки. Без флага телефон говорит «сервер Дел не обновлён».
# «groups» — поле owed (группа «Отбить» — ждут от меня, dela_0007, 10.10.2026) в task.create и task.set; без него телефон
# owed не шлёт, а «ждут от меня» из разноски передаёт как ball «owed» — сервер поймёт и так (store._ball_norm).
# «meetings» — встречи в базе (dela_0010, 12.10.2026, meetings.py): операции meeting.upsert (служба встреч) и meeting.set
# (владелец), виды meetings и meeting. Служба встреч без флага шлёт только прежнее — сводку в хронологию и «Новое».
# «krug» — Круг жизни (dela_0009, 12.10.2026, krug.py): поля directions, why_id, why_title в task.create, task.set и
# у проекта, список krug в синке (направления проектов), у дела в синке — directions, why_id, dirs, steps_open.
# «tree» — дерево целей (Круг 7.2, dela_0013, 13.10.2026): поля goal, rhythm, no_steps в task.create и task.set, why_id
# у проекта (проект вешается на цель), в списке krug синка — why_id проекта, у дела — goal, rhythm, no_steps.
# «place» — место в дереве при разборе (13.10.2026, place.py): поле why_auto в task.create и в предложении «завести» —
# номер цели, которую делу выбрала модель разбора (блок «дерево жизни» — Свод krug.place). Без флага телефон его не шлёт.
# «options» — ответы кнопками в «Подскажи» (13.10.2026): payload.options у предложения — 2–4 варианта {label, поля
# payload или reject}, suggestion.decide с option — номер выбранного. Клиент без флага показывает «Да / Не надо».
# «digest» — дайджест Telegram на телефоне (dela_0014, 13.10.2026, digest.py): последняя сводка — полем digest синка
# (только владельцу), список — вид digest, приём — операция digest.put (только служба дайджеста). Без флага телефон
# карточку дайджеста не показывает: сервер не обновлён.
# «zasechka» — команды Засечки из веба (dela_0016, 14.10.2026, zasechka.py): поле zasechka синка (только владельцу) —
# команды, ждущие телефона; операции zasechka.take и zasechka.done — телефон берёт и отмечает. Без флага телефон
# поля не ищет. Страницы-двойники «Сегодня», «Засечка», «Правка» (twins.py) — виды day, zasechka, pravka, pogoda.
# «phone» — настройки телефона на сервере (dela_0015, 14.10.2026, phone.py): операции phone.snapshot (телефон), phone.set,
# phone.cancel, phone.hide (веб), вид phone; правки, которые ждут телефон, — полем phone синка. Без флага телефон снимков
# не шлёт, а веб страницу «Телефон» не показывает: сервер не обновлён.
# «spend» — одна книга трат Claude (dela_0017, 14.10.2026, spend.py): операции spend.put (телефон — свои дороги,
# сборщик «Дома» — службы компа; итог дня, повтор безвреден) и spend.seen (владелец открыл советы), вид spend
# (экран «Claude: траты»), поле spend синка — значок на «Сегодня»: $ за 7 дней и красная точка непрочитанных советов
# (только владельцу). Без флага телефон трат не шлёт и значка не рисует.
# «digest_crm» — дайджест по карточкам CRM (dela_0018, 14.10.2026, digest_crm.py): digest.put принимает sources (чаты окна)
# и разносит пункты сводки в хронологию людей и клиентов (запись private — только владельцу, digest_ref у записи в видах
# client, deal, dossier); вид digest — поиск q, даты since/until, person_id, project_id и crm у одной сводки. Догрузка
# прошлых сводок (C:\Bot\Digest\backfill_dela.py на компе) без флага не идёт: старый сервер сводки сохранил бы, но не разнёс.
# Токен службы бота Ковчега (python -m pravka_dela token --name kovcheg): только он забирает
# «что пора» и отмечает отправку (remind.py, task.reminded).
REMIND_BOT = "kovcheg"
PROJECT_FIELDS = {"name", "aliases", "sphere", "kind", "org_id", "money_default", "note", "archived_at", "status", "folder_url", "files"}
DEAL_FIELDS = {
    "project_id", "name", "stage", "deal_type", "lead_person_id", "person_ids", "fee_kop", "deadline",
    "wheel", "ball", "next_step", "my_view", "ideas", "log",
    "outcome", "lost_reason", "closed_on", "fee_kind", "retainer_kop", "success_pct", "probability", "expected_on",
    "source_person_id", "team_ids", "description", "folder_url", "files",
}
PERSON_FIELDS = {
    "name", "short", "aliases", "org_id", "role", "phones", "emails", "telegram_id", "telegram_username",
    "birth_day", "birth_month", "birth_year", "source", "seeks", "offers", "traits", "cadence", "hub",
    "note", "archived_at",
}
ORG_FIELDS = {"name", "aliases", "kind", "note", "archived_at"}
# private (dela_0018, с 15.10.2026 и в операциях — аудит №9): interaction.add — запись сразу «только мне», interaction.set
# private: true — «скрыть от команды» (только хозяин записи, снять нельзя — правило базы). Без флага privacy клиенты поле
# не шлют: старый сервер отверг бы операцию целиком.
INTERACTION_FIELDS = {"at", "kind", "summary", "next_step", "project_id", "deal_id", "person_ids", "source", "source_ref",
                      "duration_min", "private"}
PAYMENT_FIELDS = {"deal_id", "kind", "title", "amount_kop", "due_on", "invoiced_on", "paid_on", "cancelled_at", "note", "sent_to", "sent_via"}
SUGGESTION_FIELDS = {"kind", "task_id", "payload", "source", "source_ref", "quote", "batch_ref", "batch_title", "dup_of", "for_user", "expires_at"}

SUGGESTION_TTL = dt.timedelta(days=7)
# Что вариант ответа «Подскажи» (payload.options) может поменять — словарь payload: поля дела, имена, место в дереве.
OPTION_FIELDS = (TASK_FIELDS | {"project_name", "person_name", "why_id", "directions", "why_auto", "note"}
                 | {"new", "org_name", "rename", "person"}) - {  # у вопроса о человеке (kind person): «это он», «новый»,
    "status", "source", "source_ref", "owner_id", "requested_by"}       # компания, что дописать (person — словарь)
OPTIONS_MAX = 4

# «Сейчас» — дела, которые Саша обязан сделать сегодня (focus_on = сегодня), не больше пяти. До 06.10.2026
# предел держали только веб, ask.py и телефон, а коннектор claude.ai, реплай в Telegram и любой клиент API
# ставили шестое молча (LOGIC.md §11). Теперь его держит сервер — одно место для всех; клиенты считают
# сами лишь ради подсказки до запроса.
NOW_MAX = 5
NOW_LOCK = 0x5E4A  # первый ключ замка «Сейчас» (второй — владелец): чужим замкам базы не мешает
_NOW_WORD = {5: "пять", 6: "шесть", 7: "семь", 8: "восемь", 9: "девять"}


# Три группы дел (владелец 10.10.2026: «есть дела, которые ждут меня… второе — что я сам придумал… третье —
# проверить, что мне люди должны»; вечером назвал: «отбить, запустить, мониторить»). Порядок — приоритет: отбить
# первым делом. Группа — из owed и ball
# (dela_0007), тем же правилом в tasks.v_tasks.grp, app.js и на телефоне.
GROUPS = (("owed", "Отбить"), ("self", "Запустить"), ("check", "Мониторить"))


def group_of(t: dict) -> str:
    return "owed" if t.get("owed") else "check" if t.get("ball") == "waiting" else "self"


def _ball_norm(data: dict) -> dict:
    """ball = «owed» — «ждут от меня»: в базе это ball mine и флаг owed (dela_0007).

    Так автоматика (встречи, дайджест, разбор надиктовки, Claude) называет группу одним полем ball, а
    телефон, который о флаге ещё не знает, видит такое дело своим «моё». Мяч ушёл к человеку (waiting) —
    флаг гаснет; mine и agenda без owed флаг не трогают: уточнение срока «ждут от меня» не роняет.
    """
    if data.get("ball") == "owed":
        data["ball"] = "mine"
        data["owed"] = True
    elif data.get("ball") == "waiting":
        data["owed"] = False
    if "owed" in data and data["owed"] is None:
        data["owed"] = False
    return data


class OpError(Exception):
    """Ошибка операции, которую повтор не исправит: её запоминаем вместе с op_id."""


# Круг жизни (dela_0009, 12.10.2026): у дела — направления и «Зачем», у проекта — направления (их наследуют дела).
# Живут не в tasks.tasks, а в tasks.krug под политикой «только хозяину» — поэтому из операции они вынимаются
# до _pick и пишутся отдельно (_krug_write). why_title — «Зачем» новой целью: такая цель заводится тут же.
# Дерево целей (7.2, dela_0013): goal — дело стало узлом дерева (цель-состояние), rhythm — ритм практики,
# no_steps — под целью дел не заводят; у проекта — why_id (цель, на которой он висит).
# why_auto (13.10.2026, place.py) — номер цели, которую делу при разборе выбрала модель, а не владелец: сервер проверяет
# его сам и ставит «Зачем» от службы; не подошло — дело заводится без места, без ошибки.
KRUG_FIELDS = {"directions", "why_id", "why_title", "goal", "rhythm", "no_steps", "why_auto"}


def _krug_split(data: dict) -> dict:
    """Поля Круга из операции — отдельно; в data остаются поля самого дела."""
    return {k: data.pop(k) for k in list(data) if k in KRUG_FIELDS}


def _krug_write(conn, user: str, kind: str, obj: dict, kr: dict) -> None:
    """Направления и «Зачем» дела (kind task) или проекта (kind project), пометки узла дерева — в строку Круга.

    Ставит только хозяин (или служба от его имени — разборщик идёт пользователем-хозяином): Круг личный, и
    правка Наташи в общем проекте не должна размечать дела Саши. Направления — ключами или названиями из
    Свода хозяина (krug.directions); пустой список — «снять свои», дело снова наследует.

    Узел дерева (goal) — всегда без проекта: текст цели видит только хозяин (бриф 7.2, §12: узлы — личные формулировки
    владельца, общее дело видно без своего «зачем»), а дело без проекта база показывает только ему.
    """
    auto = kr.pop("why_auto", None)
    if kr:
        _krug_own(conn, user, kind, obj, kr)
    # Место от модели разбора — после своих полей владельца и только если «Зачем» он не назвал сам: журнал должен
    # видеть, что поставила служба, а что он (place.learned учится только на его правках).
    if auto not in (None, "", 0) and kind == "task" and not {"why_id", "why_title", "goal"} & set(kr):
        place.write_auto(conn, user, obj, auto)


def _krug_own(conn, user: str, kind: str, obj: dict, kr: dict) -> None:
    owner = obj["owner_id"]
    if user not in (owner, "system"):
        raise OpError("направления и «зачем» ставит только хозяин")
    data: dict = {}
    if "directions" in kr:
        data["directions"] = krug.norm_directions(conn, owner, kr["directions"], OpError)
        if len(data["directions"]) > krug.DIRS_MAX:
            raise OpError(f"направлений у дела не больше {krug.DIRS_MAX}: главное и две метки")
    if kr.get("why_title"):
        # Новая цель словами: узел дерева без проекта, на том же месте, где было дело, — в его главном направлении.
        here = conn.execute("SELECT dirs FROM tasks.v_tasks WHERE id = %s", (obj["id"],)).fetchone() if kind == "task" else None
        main = list((here or {}).get("dirs") or [])[:1] or list(data.get("directions") or [])[:1]
        goal = op_task_create(conn, user, {"task": {
            "title": str(kr["why_title"]).strip(), "owner_id": owner, "source": obj.get("source") or "manual",
            "goal": True, **({"directions": main} if main else {})}})["task"]
        data["why_id"] = goal["id"]
    elif "why_id" in kr:
        ref = kr["why_id"]
        if ref in (None, ""):
            data["why_id"] = None
        else:
            goal = task_by(conn, ref)
            if not goal:
                raise OpError("«зачем»: нет такого дела или оно не видно")
            data["why_id"] = goal["id"]
    for k in ("goal", "no_steps"):
        if k in kr:
            data[k] = bool(kr[k])
    if "rhythm" in kr:
        data["rhythm"] = (str(kr["rhythm"] or "").strip() or None)
        if data["rhythm"]:
            data["goal"] = True  # ритм делает практику (бриф 7.2, §3): практика — цель, которая повторяется
    if kind == "project" and {"goal", "no_steps", "rhythm"} & set(data):
        raise OpError("целью и практикой бывает дело, а не проект")
    if not data:
        return
    # Направление идёт за «Зачем» (владелец 13.10.2026: «выбрал Зачем — направление ставится само по ветке цели»).
    # Главное и так берётся у корня цепочки (tasks.krug_dirs), но прежнее своё главное (дело висело прямо на
    # направлении) оставалось у дела меткой из чужой ветки. Новая цель с направлением — прежнее главное из своих
    # уходит, метки, поставленные руками, остаются; «Зачем» убрали — прежнее главное остаётся своим, если иначе дело
    # выпало бы из дерева. Направления в той же операции — решение человека: их не трогаем.
    keep_main = None
    if kind == "task" and "why_id" in data and "directions" not in kr:
        was = conn.execute("SELECT tasks.krug_dirs(%s) AS dirs, (SELECT directions FROM tasks.krug WHERE id = %s) AS own",
                           (obj["id"], obj["id"])).fetchone()
        old_main, own = (was["dirs"] or [None])[0], list(was["own"] or [])
        if old_main and data["why_id"]:
            new_main = (conn.execute("SELECT tasks.krug_dirs(%s) AS d", (data["why_id"],)).fetchone()["d"] or [None])[0]
            if new_main and new_main != old_main and old_main in own:
                data["directions"] = [k for k in own if k != old_main]
        elif old_main:
            keep_main = (old_main, own)
    if data.get("goal") and kind == "task" and obj.get("project_id"):
        conn.execute("UPDATE tasks.tasks SET project_id = NULL, deal_id = NULL WHERE id = %s", (obj["id"],))
    cols = ["id", "kind", "owner_id", *data]
    q = sql.SQL("INSERT INTO tasks.krug ({}) VALUES ({}) ON CONFLICT (id) DO UPDATE SET {}").format(
        sql.SQL(", ").join(map(sql.Identifier, cols)), sql.SQL(", ").join(sql.Placeholder() * len(cols)),
        sql.SQL(", ").join(sql.SQL("{0} = EXCLUDED.{0}").format(sql.Identifier(c)) for c in data))
    conn.execute(q, [obj["id"], kind, owner, *data.values()])
    if keep_main and not conn.execute("SELECT tasks.krug_dirs(%s) AS d", (obj["id"],)).fetchone()["d"]:
        main, own = keep_main
        conn.execute("UPDATE tasks.krug SET directions = %s WHERE id = %s",
                     ([main, *[k for k in own if k != main]][:krug.DIRS_MAX], obj["id"]))


# ── Форма данных ────────────────────────────────────────────────────────


def jsonable(v: Any) -> Any:
    if isinstance(v, dict):
        return {k: jsonable(x) for k, x in v.items() if k != "search"}
    if isinstance(v, (list, tuple)):
        return [jsonable(x) for x in v]
    if isinstance(v, uuid.UUID):
        return str(v)
    if isinstance(v, dt.datetime):
        return v.isoformat(timespec="seconds")
    if isinstance(v, dt.date):
        return v.isoformat()
    if isinstance(v, dt.time):
        return v.strftime("%H:%M")
    if isinstance(v, decimal.Decimal):
        return int(v) if v == v.to_integral_value() else float(v)
    return v


def _pick(data: dict, allowed: set[str], what: str) -> dict:
    extra = set(data) - allowed
    if extra:
        raise OpError(f"{what}: нет таких полей — {', '.join(sorted(extra))}")
    out = {}
    for k, v in data.items():
        if isinstance(v, str) and k not in {"notes", "note", "summary", "title"}:
            v = v.strip() or None
        if k == "files":
            v = _files(v, what)
        out[k] = Jsonb(v) if k in ("payload", "files") else v
    return out


FILE_KINDS = {"contract", "invoice", "nda", "act", "other"}


def _files(v, what: str) -> list[dict]:
    """Документы сделки или клиента ссылками (dela_0006): [{kind, title, url, at}]. Сами файлы — на диске
    или в почте, тут только ссылка; кривую запись отвергаем целиком, а не кладём молча."""
    if v is None:
        return []
    if not isinstance(v, list):
        raise OpError(f"{what}: files — список")
    out = []
    for x in v:
        url = str((x or {}).get("url") or "").strip() if isinstance(x, dict) else ""
        if not url.lower().startswith(("http://", "https://")):
            raise OpError(f"{what}: у документа нужна ссылка http(s)")
        kind = x.get("kind") if x.get("kind") in FILE_KINDS else "other"
        out.append({"kind": kind, "title": str(x.get("title") or "").strip()[:200] or None, "url": url[:2000],
                    "at": str(x.get("at") or "")[:10] or None})
    return out


def _insert(conn: psycopg.Connection, table: str, data: dict) -> dict:
    cols = list(data)
    q = sql.SQL("INSERT INTO {} ({}) VALUES ({}) RETURNING *").format(
        sql.SQL(table), sql.SQL(", ").join(map(sql.Identifier, cols)), sql.SQL(", ").join(sql.Placeholder() * len(cols))
    )
    return conn.execute(q, [data[c] for c in cols]).fetchone()


def _update(conn: psycopg.Connection, table: str, where: dict, data: dict) -> dict | None:
    if not data:
        return conn.execute(
            sql.SQL("SELECT * FROM {} WHERE {}").format(
                sql.SQL(table), sql.SQL(" AND ").join(sql.SQL("{} = %s").format(sql.Identifier(k)) for k in where)
            ),
            list(where.values()),
        ).fetchone()
    q = sql.SQL("UPDATE {} SET {} WHERE {} RETURNING *").format(
        sql.SQL(table),
        sql.SQL(", ").join(sql.SQL("{} = %s").format(sql.Identifier(k)) for k in data),
        sql.SQL(" AND ").join(sql.SQL("{} = %s").format(sql.Identifier(k)) for k in where),
    )
    return conn.execute(q, [*data.values(), *where.values()]).fetchone()


def _same(a: Any, b: Any) -> bool:
    return jsonable(a) == jsonable(b) or (a in (None, "", []) and b in (None, "", []))


def _ensure_labels(conn: psycopg.Connection, labels: list[str] | None) -> None:
    for name in labels or []:
        conn.execute("INSERT INTO tasks.labels (name) VALUES (%s) ON CONFLICT DO NOTHING", (name,))


def task_by(conn: psycopg.Connection, ref: Any) -> dict | None:
    """Дело по id или по короткому номеру («57», «#57»)."""
    s = str(ref).strip().lstrip("#")
    if s.isdigit():
        return conn.execute("SELECT * FROM tasks.v_tasks WHERE num = %s", (int(s),)).fetchone()
    try:
        uuid.UUID(s)
    except ValueError:
        return None
    return conn.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (s,)).fetchone()


def task_origin(conn: psycopg.Connection, t: dict) -> dict | None:
    """Откуда дело — для карточки: наговорка дословно или встреча и Telegram (пачка, цитата, ссылка).

    С 08.10.2026 «Новое» — короткий список «поставил» со значком источника; что именно было сказано,
    раньше занимало полэкрана в самом «Новом» (владелец: «ужасно всё засоряет»), теперь — в карточке.
    """
    ref = t.get("source_ref") or ""
    if ref.startswith(("raznoska:", "parse:")):
        d = conn.execute("SELECT at, text, source FROM tasks.dictations WHERE id = %s", (ref,)).fetchone()
        if d:
            return {"kind": "dictation", **d}
    s = conn.execute(
        "SELECT source, source_ref, batch_title, quote, reason, created_at AS at FROM tasks.suggestions "
        "WHERE result_task_id = %s AND kind = 'create' ORDER BY decided_at DESC LIMIT 1", (t["id"],)).fetchone()
    if s:
        url = s.pop("source_ref")
        return {"kind": "suggestion", **s, "url": url if str(url or "").startswith("https://") else None,
                "auto": s["reason"] == AUTO_CREATE_REASON}
    return None


def _full(conn: psycopg.Connection, tid: Any) -> dict:
    return conn.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (tid,)).fetchone()


def _now_guard(conn: psycopg.Connection, before: dict | None, after: dict) -> None:
    """Дело встаёт в «Сейчас» владельца, а там уже NOW_MAX — отказ словами, а не шестое молча.

    before — дело до операции (None — новое), after — каким оно станет. В «Сейчас» — открытое дело
    с focus_on на сегодня (crm.today(), Москва). Предел проверяется, только когда дело туда ВХОДИТ:
    правка уже стоящего (срок, заметка), снятие и закрытие его не трогают. Счёт — до записи и под
    замком на владельца: телефон, веб и реплай в Telegram в одну секунду шестого не поставят.
    Считается видимое работающему (RLS): чужие приватные дела владельца в счёт не попадут.
    """
    if not after.get("focus_on") or (after.get("status") or "open") != "open":
        return
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"].isoformat()

    def in_now(t: dict | None) -> bool:
        return bool(t) and (t.get("status") or "open") == "open" and str(t.get("focus_on") or "")[:10] == today

    owner = after.get("owner_id")
    if not in_now(after) or (in_now(before) and before.get("owner_id") == owner):
        return
    conn.execute("SELECT pg_advisory_xact_lock(%s, hashtext(%s::text))", (NOW_LOCK, owner))
    rows = conn.execute(
        "SELECT num, title FROM tasks.tasks WHERE owner_id = %s AND status = 'open' AND focus_on = crm.today() "
        "AND id IS DISTINCT FROM %s::uuid ORDER BY num",
        (owner, str(before["id"]) if before else after.get("id")),
    ).fetchall()
    if len(rows) >= NOW_MAX:
        names = "; ".join(f"#{r['num']} {r['title'] if len(r['title']) <= 40 else r['title'][:39] + '…'}" for r in rows)
        raise OpError(f"в «Сейчас» уже {_NOW_WORD.get(len(rows), len(rows))} дел: {names} — сначала убери одно")


# ── Дела внутри дела (15.10.2026) ───────────────────────────────────────
# Владелец: «Пишу „до этого надо из расшифровки встречи набросать драфт“ в „Что поправить в этом деле“ — он добавляет
# в комментарии вместо того, чтобы сделать новое дело… Под делом должно быть „до этого“ / „для этого“ / „поправить это
# дело“ — и в карточке, и в списке. И тогда дело ставится на сегодня и т. д. — оно связывается с этим делом»; вечером —
# «вложенность одного в другое и показывать тоже в дереве», «это не отдельная сущность, а обычные дела, вложенные в
# дело». Вложенное дело — обычное дело, у которого «Зачем» (why_id) — другое дело (не цель): свои поля, карточка, свои
# вложенные, «Сейчас», напоминание. Порядок среди соседей — pos (dela_0030), петли и чужие дела не пускает база
# (tasks.krug_rules). Одно правило в трёх местах: здесь (ask.py — строка Claude «Для этого», и она же без ключа Claude;
# подъём родителя и его «Вернуть» — функции базы tasks.nest_rise / nest_fall через _nest_rise), веб (stepDue, stepChips,
# stepsFirst, nestForest в app.js) и телефон (core/DelaSteps.kt). «Шаг к цели» — только дело, у которого вверх по
# «Зачем» есть цель (krug.toward_goal): дело внутри клиентского дела — не «Моё» и не цель для движения Круга.


def _day(v) -> dt.date | None:
    if isinstance(v, dt.datetime):
        return v.date()
    if isinstance(v, dt.date):
        return v
    try:
        return dt.date.fromisoformat(str(v)[:10]) if v else None
    except ValueError:
        return None


def step_due(parent: dict, today: dt.date) -> dt.date | None:
    """Срок шага, когда его не назвали: дело в «Сейчас», просрочено или на сегодня — сегодня (до него надо успеть);
    на будущую дату — тот же срок; без срока — без срока. Названный срок сильнее; «Сейчас» шагу — только если сказал."""
    due = _day(parent.get("due_date"))
    if _day(parent.get("focus_on")) == today or (due and due <= today):
        return today
    return due


def step_of(parent: dict, user: str, today: dt.date) -> dict:
    """Что новый шаг берёт от дела [parent]: «Зачем» — само дело (только хозяину: правило базы «„зачем“ — только своё
    дело», Круг личный), проект, сделку и человека (у цели их нет — она живёт без проекта) и срок (step_due)."""
    out: dict = {}
    if not parent.get("goal"):
        for k in ("project_id", "deal_id", "person_id"):
            if parent.get(k):
                out[k] = str(parent[k])
    if parent.get("owner_id") == user:
        out["why_id"] = str(parent["id"])
    due = step_due(parent, today)
    if due:
        out["due_date"] = due.isoformat()
    return out


def _nest_rise(conn, user: str, before: dict, after: dict) -> tuple[dict | None, dict | None]:
    """Подъём родителя и его «Вернуть» — функциями базы (dela_0030), для любого, кто закрыл или открыл дело.

    Сделано последнее открытое дело внутри — родитель НЕ закрывается сам (владелец 15.10.2026), а поднимается: без
    срока или со сроком позже — на сегодня (просроченное и сегодняшнее остаются как есть); родитель — открытое дело, не
    цель (у цели свои правила, она живёт в дереве). Дело снова открыто («Вернуть», повторный тап, «Вернуть» у
    «Закрыто само») — родитель возвращается к прежнему сроку, если его с тех пор не правили (tasks.nest_fall).
    Раньше подъём смотрел why_id правами того, кто закрыл, и у члена команды (Круг ему не виден) молча не срабатывал.

    Ответ — (поднялось, вернулось) для тоста «всё внутри сделано — теперь: …»; только хозяину родителя: что внутри
    чего, команда не знает (Круг личный, правило 19), её синк просто привезёт новый срок родителя, если он ей виден."""
    was, now = before.get("status") or "open", after.get("status")
    if now == "done" and was != "done":
        fn = "tasks.nest_rise"
    elif now == "open" and was != "open":
        fn = "tasks.nest_fall"
    else:
        return None, None
    got = conn.execute(f"SELECT {fn}(%s) AS r", (after["id"],)).fetchone()["r"]
    if not got or got.get("owner_id") != user:
        return None, None
    out = {k: got[k] for k in ("id", "num", "title", "moved", "due_date") if k in got}
    return (out, None) if fn == "tasks.nest_rise" else (None, out)


def _why_moves(conn, cur: dict, kr: dict) -> bool:
    """Меняет ли операция «Зачем» дела (вложить в другое, вынуть, новая цель словами)."""
    if "why_title" in kr:
        return True
    if "why_id" not in kr:
        return False
    ref = kr["why_id"]
    if ref in (None, ""):
        return cur.get("why_id") is not None
    t = task_by(conn, ref)
    return not t or str(t["id"]) != str(cur.get("why_id") or "")


def _nest_last(conn, t: dict) -> bool:
    """Вложили без места — дело встаёт в конец соседей (дел внутри того же дела). Пустое место ставит дело после
    занумерованных, но среди незанумерованных — по времени заведения: старое дело, вложенное к более новым, встало бы
    первым, и «сначала: …» у родителя показало бы его (сквозной тест 15.10.2026, п. 5б). Тогда соседи нумеруются 1, 2,
    3… в нынешнем порядке (как «Раньше» / «Позже» веба и телефона), а это дело — следующим номером. Ответ — нумеровали ли.
    Соседи — дела того же хозяина: «Зачем» только своё (krug_rules), RLS их правку пропускает."""
    kids = conn.execute(
        "SELECT t.id, t.pos, t.created_at, t.num FROM tasks.krug k JOIN tasks.tasks t ON t.id = k.id "
        "WHERE k.why_id = %s AND k.kind = 'task' AND NOT k.goal AND t.status = 'open' AND t.id <> %s "
        "ORDER BY t.pos NULLS LAST, t.created_at, t.num", (t["why_id"], t["id"])).fetchall()
    me = (t["created_at"], t["num"])
    if not any(k["pos"] is None and (k["created_at"], k["num"]) > me for k in kids):
        return False  # и так последнее: после занумерованных и новее незанумерованных
    for n, k in enumerate(kids, 1):
        if k["pos"] != n:
            _update(conn, "tasks.tasks", {"id": k["id"]}, {"pos": n})
    _update(conn, "tasks.tasks", {"id": t["id"]}, {"pos": len(kids) + 1})
    return True


def _pos(v) -> int | None:
    """Место среди соседей (dela_0030): 1, 2, 3… или пусто. Ноль и минус веб считал бы местом «первым», а телефон и
    дерево — «не задано»: порядок на устройствах разошёлся бы. Кривое — отказ словами, а не ошибка SQL."""
    if v is None:
        return None
    if isinstance(v, str) and v.strip().isdigit():
        v = int(v.strip())
    if isinstance(v, bool) or not isinstance(v, int) or v < 1:
        raise OpError("место среди соседей (pos) — целое число от 1 или пусто")
    return v


# ── Операции ────────────────────────────────────────────────────────────


def op_task_create(conn, user, op):
    raw = dict(op.get("task") or {})
    kr = _krug_split(raw)
    data = _ball_norm(_pick(raw, TASK_FIELDS | {"id", "import_ref"}, "дело"))
    if "pos" in data:
        data["pos"] = _pos(data["pos"])
    if not data.get("title"):
        raise OpError("у дела нет названия")
    if data.get("id"):
        have = conn.execute("SELECT id FROM tasks.tasks WHERE id = %s", (data["id"],)).fetchone()
        if have:  # тот же id уже создан: телефон повторил без op_id
            return {"task": _full(conn, have["id"])}
    data.setdefault("owner_id", user)
    data["created_by"] = user
    _now_guard(conn, None, data)
    _ensure_labels(conn, data.get("labels"))
    row = _insert(conn, "tasks.tasks", data)
    if kr:
        _krug_write(conn, user, "task", row, kr)
    return {"task": _full(conn, row["id"])}


def op_task_set(conn, user, op):
    cur = task_by(conn, op.get("id"))
    if not cur:
        raise OpError("нет такого дела или оно не видно")
    raw = dict(op.get("set") or {})
    kr = _krug_split(raw)
    changes = _ball_norm(_pick(raw, TASK_FIELDS, "дело"))
    if "pos" in changes:
        changes["pos"] = _pos(changes["pos"])
    # Вложили в другое дело или вынули (why_id сменился), а места не назвали — прежнее место было среди прежних
    # соседей: оно уходит, дело встаёт в конец новых соседей (иначе «Вложить в…» ставило его первым, и «сначала: …»
    # у нового родителя показывало его; _nest_last ниже). Одно правило для веба, телефона, Claude и коннектора — здесь.
    moved = "pos" not in changes and _why_moves(conn, cur, kr)
    if moved and cur.get("pos") is not None:
        changes["pos"] = None
    was = op.get("was") or {}
    # Поле поменяли с двух сторон: побеждает пришедшее последним, прежнее
    # значение остаётся в журнале, а клиент узнаёт, что спорил не один.
    conflicts = sorted(k for k in changes if k in was and not _same(cur.get(k), was[k]) and not _same(cur.get(k), changes[k]))
    if changes.get("project_id") and cur.get("goal") and kr.get("goal") is not False:
        raise OpError("цель живёт без проекта: её текст видит только хозяин (сначала сделай её обычным делом)")
    _now_guard(conn, cur, {**cur, **changes})
    _ensure_labels(conn, changes.get("labels"))
    row = _update(conn, "tasks.tasks", {"id": cur["id"]}, changes)
    if row is None:
        raise OpError("это дело можно только смотреть")
    if kr:
        _krug_write(conn, user, "task", row, kr)
    full = _full(conn, row["id"])
    if moved and full.get("why_id") and not full.get("why_goal") and full.get("status") == "open" and _nest_last(conn, full):
        full = _full(conn, row["id"])
    raised, fell = _nest_rise(conn, user, cur, full)
    return {"task": full, "conflicts": conflicts, **({"raised": raised} if raised else {}), **({"fell": fell} if fell else {})}


def _status(status):
    def handler(conn, user, op):
        return op_task_set(conn, user, {"id": op.get("id"), "set": {"status": status}})

    return handler


def _instant(v: Any) -> dt.datetime | None:
    if isinstance(v, dt.datetime):
        return v
    try:
        got = dt.datetime.fromisoformat(str(v or ""))
    except ValueError:
        return None
    return got if got.tzinfo else None


def op_task_reminded(conn, user, op):
    """Бот Ковчега отправил напоминание в Telegram: reminded_at — больше не слать.

    Только токен бота: телефон и веб reminded_at не ставят (его нет в TASK_FIELDS). Бот
    напоминает всем, у кого есть Telegram, а токен у него на одного человека — поэтому отметка
    идёт от имени system (как и «что пора»), а в журнал — бот. remind_at — то время, о котором
    бот напомнил: если его успели переставить («через час» с телефона, пока бот слал), отметка
    не ставится — новое напоминание уйдёт в своё время. message_id — в ответе (crm.ops_seen).
    """
    actor = conn.execute("SELECT crm.actor() AS a").fetchone()["a"]
    if actor != f"svc:{REMIND_BOT}":
        raise OpError("task.reminded шлёт только бот напоминаний")
    at = _instant(op.get("at")) or dt.datetime.now(dt.timezone.utc)
    conn.execute("SELECT set_config('dela.user', 'system', true)")
    try:
        cur = task_by(conn, op.get("id"))
        if not cur:
            raise OpError("нет такого дела")
        sent_for = _instant(op.get("remind_at"))
        if op.get("remind_at") and sent_for != cur["remind_at"]:
            return {"task": cur, "stale": True, "message_id": op.get("message_id")}
        conn.execute("UPDATE tasks.tasks SET reminded_at = %s WHERE id = %s AND reminded_at IS NULL", (at, cur["id"]))
        return {"task": _full(conn, cur["id"]), "message_id": op.get("message_id")}
    finally:
        conn.execute("SELECT set_config('dela.user', %s, true)", (user,))


def op_comment_add(conn, user, op):
    """Комментарий к делу. private (аудит №8, dela_0024) — «только мне»: видит только автор, журнала нет, приватность
    не снимается. Старый сервер поле отверг бы целиком («нет таких полей») — клиенты шлют его только при флаге privacy."""
    data = _pick(op.get("comment") or {}, {"id", "task_id", "text", "private"}, "комментарий")
    task = task_by(conn, data.get("task_id"))
    if not task:
        raise OpError("нет такого дела или оно не видно")
    data["task_id"] = task["id"]
    data["author_id"] = user
    data["private"] = bool(data.get("private"))
    if data.get("id") and conn.execute("SELECT 1 FROM tasks.comments WHERE id = %s", (data["id"],)).fetchone():
        return {"comment": conn.execute("SELECT * FROM tasks.comments WHERE id = %s", (data["id"],)).fetchone()}
    return {"comment": _insert(conn, "tasks.comments", data)}


# ── Закон приватности (аудит 15.10.2026, №8–11, правило 26 карты) ───────────────────
# Личные каналы владельца: переписка Telegram (сверка дайджеста — telegram, юзербот — userbot), встречи (расшифровка и
# разбор) и телефонная книжка. Машина из них пишет только в приватное: основание «закрыть»/«уточнить», заметку дела
# («завести» — при «только факт» у встреч), «Человек не поставлен: …» — приватным комментарием; в общее переносит только
# рука владельца (его set.notes, отличный от заметки машины).
PRIVATE_SOURCES = ("meeting", "telegram", "userbot", "contacts")
SOURCE_HEAD = {"meeting": "Встреча", "telegram": "Telegram", "userbot": "Telegram", "contacts": "Телефонная книжка"}


def team_sees_meetings(conn) -> str:
    """Что команда видит из встреч владельца Дел: fact — только факт (название «Клиент: тема», когда, с кем), summary — и
    итог с договорённостями. Правило одно — в базе (crm.team_sees_meetings, Свод privacy.team, аудит №9): по нему же
    crm.meeting_touch пишет запись встречи."""
    return conn.execute("SELECT crm.team_sees_meetings(crm.owner_id()) AS m").fetchone()["m"]


def _same_text(a: Any, b: Any) -> bool:
    """Тот же текст с точностью до пробелов: «Поправить → Принять» шлёт заметку предложения без правки — это не слово
    владельца."""
    return " ".join(str(a or "").split()) == " ".join(str(b or "").split())


def _notes_private(conn, source: str | None) -> bool:
    """Заметка машины при «завести» — в приватный комментарий, а не в дело: Telegram — всегда, встреча — пока команда видит
    из встреч только факт."""
    if source in ("telegram", "userbot"):
        return True
    return source == "meeting" and team_sees_meetings(conn) == "fact"


def _sug_head(s: dict) -> str:
    return s.get("batch_title") or SOURCE_HEAD.get(s.get("source"), s.get("source") or "")


def _comment(conn, user, task_id, text: str, private: bool) -> str | None:
    """Комментарий автоматики к делу; тот же живой текст у дела уже есть — второй не пишем (повторное «уточнить» без
    заметки в деле — частое следствие приватной заметки), ответ — None."""
    if conn.execute("SELECT 1 FROM tasks.comments WHERE task_id = %s AND deleted_at IS NULL AND text = %s",
                    (task_id, text)).fetchone():
        return None
    return op_comment_add(conn, user, {"comment": {"task_id": task_id, "text": text, "private": private}})["comment"]["id"]


def task_history(url: str, task: dict, viewer: str, limit: int | None = None, newest_first: bool = False,
                 krug: bool = False) -> list[dict]:
    """Журнал дела (crm.history) — тому, кому база уже показала дело. Читает system (журнал целиком роли службы не виден);
    не владельцу Дел у дел из Telegram и встреч — без значений заметки (аудит №8): заметку машины прежних дел приватной
    сделает privacy-fix, а журнал хранит её текст навсегда. Одна функция для карточки дела (/api/task) и Claude
    карточки дела (ask._task_card).

    krug — и журнал строки Круга (tasks.krug: «Вложить в…», «Вынуть», «Зачем», направления; 15.10.2026, дела внутри
    дела), но только хозяину дела: Круг личный (правило 19), команде его строк нет. У строки — entity (tasks.tasks или
    tasks.krug)."""
    entities = ["tasks.tasks", "tasks.krug"] if krug and task.get("owner_id") == viewer else ["tasks.tasks"]
    q = ("SELECT at, actor, via, op, before, after, entity FROM crm.history WHERE entity = ANY(%s) AND entity_id = %s "
         f"ORDER BY id{' DESC' if newest_first else ''}{' LIMIT %s' if limit else ''}")
    with db.session(url, "system", "svc:view") as conn:
        args = (entities, str(task["id"])) + ((limit,) if limit else ())
        rows = conn.execute(q, args).fetchall()
        owner = conn.execute("SELECT crm.owner_id() AS o").fetchone()["o"]
    if viewer in (owner, "system") or task.get("source") not in ("telegram", "meeting"):
        return rows
    out = []
    for h in rows:
        before = {k: v for k, v in (h["before"] or {}).items() if k != "notes"} if h["before"] is not None else None
        after = {k: v for k, v in (h["after"] or {}).items() if k != "notes"} if h["after"] is not None else None
        if h["op"] == "update" and not after:
            continue  # правка была только заметкой — строке нечего сказать
        out.append({**h, "before": before, "after": after})
    return out


def op_comment_delete(conn, user, op):
    row = conn.execute(
        "UPDATE tasks.comments SET deleted_at = now() WHERE id = %s AND deleted_at IS NULL RETURNING *", (op.get("id"),)
    ).fetchone()
    if not row:
        raise OpError("нет такого комментария или он не твой")
    return {"comment": row}


def _entity(table: str, fields: set[str], what: str, owner_col: str | None = "owner_id", extra: tuple = ("id", "notion_id")):
    def create(conn, user, op):
        data = _pick(op.get("data") or {}, fields | set(extra), what)
        if owner_col:
            data.setdefault(owner_col, user)
        return {"row": _insert(conn, table, data)}

    def update(conn, user, op):
        data = _pick(op.get("set") or {}, fields, what)
        row = _update(conn, table, {"id": op.get("id")}, data)
        if row is None:
            raise OpError(f"{what}: нет такого или нет прав")
        return {"row": row}

    return create, update


def op_user_settings(conn, user, op):
    """Свои настройки (вид, скрытые клиенты Засечки) — слиянием. Роль и доступ так не поменять."""
    data = op.get("settings")
    if not isinstance(data, dict) or not data:
        raise OpError("настройки — словарь")
    row = conn.execute(
        "UPDATE crm.users SET settings = settings || %s WHERE id = %s RETURNING id, settings", (Jsonb(data), user)
    ).fetchone()
    return {"row": row}


def op_access_set(conn, user, op):
    pid, uid, role = op.get("project_id"), op.get("user_id"), op.get("role")
    if role is None:
        n = conn.execute("DELETE FROM crm.project_access WHERE project_id = %s AND user_id = %s", (pid, uid)).rowcount
        return {"removed": n}
    if role not in ("view", "edit"):
        raise OpError("доступ: view или edit")
    row = conn.execute(
        "INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, %s, %s, %s) "
        "ON CONFLICT (project_id, user_id) DO UPDATE SET role = EXCLUDED.role RETURNING *",
        (pid, uid, role, user),
    ).fetchone()
    return {"row": row}


def _options(payload) -> None:
    """Варианты ответа «Подскажи» — от 2 до OPTIONS_MAX: подпись кнопки и что вариант меняет. Кривое — выкидываем;
    меньше двух — вариантов нет вовсе (тогда «Да / Не надо», как раньше)."""
    if not isinstance(payload, dict) or "options" not in payload:
        return
    out = []
    for o in payload.get("options") if isinstance(payload.get("options"), list) else []:
        label = " ".join(str((o or {}).get("label") or "").split())[:80] if isinstance(o, dict) else ""
        if not label or label in {x["label"] for x in out}:
            continue
        x = {"label": label, **{k: v for k, v in o.items() if k in OPTION_FIELDS and v != ""}}  # None и [] — «снять»
        if "person" in x and not isinstance(x["person"], dict):
            x.pop("person")  # что дописать человеку — только словарём («это он в Telegram?»: telegram_id, ник, имена)
        if o.get("reject"):
            x = {"label": label, "reject": True, **({"reason": str(o["reason"])[:200]} if o.get("reason") else {})}
        out.append(x)
    if 2 <= len(out):
        payload["options"] = out[:OPTIONS_MAX]
    else:
        payload.pop("options", None)


def op_suggestion_create(conn, user, op):
    raw = dict(op.get("suggestion") or {})
    payload = raw.get("payload")
    if isinstance(payload, dict):
        payload = raw["payload"] = dict(payload)
        _options(payload)
    data = _pick(raw, SUGGESTION_FIELDS, "предложение")
    data.setdefault("for_user", db_owner(conn))
    data.setdefault("expires_at", dt.datetime.now(dt.timezone.utc) + SUGGESTION_TTL)
    data["created_by"] = user
    _meeting_batch(conn, data)
    if data.get("source_ref") and data.get("kind") == "person":
        # О человеке служба спрашивает один раз: повтор (служба перезапустилась до записи памяти) вернёт прежнее — в
        # любом статусе (15.10.2026, №25): ответ «Не для CRM» — тоже ответ, иначе вопрос о том же имени вставал бы
        # заново после каждого «нет». Кроме погасшего без ответа при новом поводе (_new_occasion).
        same = conn.execute(
            "SELECT * FROM tasks.suggestions WHERE source_ref = %s AND kind = 'person' AND for_user = %s "
            "ORDER BY created_at DESC LIMIT 1",
            (data["source_ref"], data["for_user"]),
        ).fetchone()
        if same and not _new_occasion(same, data, payload):
            return {"suggestion": same}
    if data.get("kind") == "person" and data.get("source") == "meeting" and isinstance(payload, dict) \
            and not payload.get("person_id"):
        decided = _meeting_person(conn, user, data, payload)
        if decided is not None:
            return decided
    if data.get("source_ref") and data.get("kind", "create") == "create":
        # Повтор той же находки (встреча разобрана заново) не плодит дубль.
        same = conn.execute(
            "SELECT * FROM tasks.suggestions WHERE source_ref = %s AND kind = 'create' AND payload ->> 'title' = %s",
            (data["source_ref"], (op.get("suggestion") or {}).get("payload", {}).get("title")),
        ).fetchone()
        if same:
            return {"suggestion": same}
    # Почему не встанет само — до записи (аудит 15.10.2026, №1): с 09.10 почти все вопросы «Подскажи» ждали без вопроса,
    # без вариантов и без причины — одно имя совпало с двумя карточками, а сервер молчал. Теперь причина — в
    # payload.held («двое с именем «Ваня»: …», «не знаю проект «Х»», «встреча старше 3 дней»), а у двусмысленного
    # человека — вопрос «Кто это — X или Y?» кнопками (_name_hold).
    kind = data.get("kind", "create")
    cache: dict = {}
    problems = _name_hold(conn, kind, payload, cache) if isinstance(payload, dict) else None
    reason, held = _auto(conn, user, {**data, "kind": kind, "payload": payload if isinstance(payload, dict) else {}},
                         problems, cache)
    if isinstance(payload, dict):
        if held:
            payload["held"] = held
        elif kind in ("create", "update"):
            # Пустая строка — «сервер посмотрел имена, всё узнано»: веб берёт вердикт сервера, а не своё точное сравнение
            # («Женя Соколов» сервер узнал уверенным who — веб не должен спрашивать «Кто это»). Нет ключа — предложение
            # старого сервера, веб судит сам, как раньше.
            payload["held"] = ""
        data["payload"] = Jsonb(payload)
    row = _insert(conn, "tasks.suggestions", data)
    if reason:
        try:
            with conn.transaction():
                return op_suggestion_decide(conn, user, {"id": row["id"], "decision": "accept", "reason": reason})
        except OpError as e:  # не вышло само (дело поменяли, «Сейчас» полно) — ждёт человека, и видно почему
            held = f"само не встало: {e}"
        except PERMANENT as e:
            held = f"само не встало: {_db_error(e)}"
        p = {**(row["payload"] or {}), "held": held}
        row = _update(conn, "tasks.suggestions", {"id": row["id"]}, {"payload": Jsonb(p)}) or row
    if held:
        title = (row["payload"] or {}).get("title") or row.get("quote") or row.get("kind")
        log.info("Новое: «%s» ждёт — %s", title, held)
    return {"suggestion": row}


# ── Назвал человека во встрече — он в CRM (аудит 15.10.2026, №25/№60) ─────────

def _new_occasion(prev: dict, data: dict, payload) -> bool:
    """Погасший без ответа вопрос о человеке — не «нет навсегда» (этап 3 аудита, вопрос 1, ответ по умолчанию «а»):
    при новом поводе его можно задать снова. Новый повод — другая встреча (payload.speaker.meeting_ref). Отказ кнопкой
    («Не для CRM», «Нет») и ответ «да» запоминаются навсегда — их повтор возвращает прежнее."""
    if prev["status"] != "expired" or data.get("source") != "meeting" or not isinstance(payload, dict):
        return False
    now = str((payload.get("speaker") or {}).get("meeting_ref") or "")
    was = str(((prev["payload"] or {}).get("speaker") or {}).get("meeting_ref") or "")
    return bool(now) and now != was


def _meeting_batch(conn, data: dict) -> None:
    """Пачка «Нового» встречи (batch_ref meeting:<ref>) — названием Дел, если встреча уже названа (аудит №43): служба
    шлёт своё название, а разборщик Дел мог назвать её раньше, чем пришла сверка. Правило то же, что у
    crm.meeting_batches_retitle (dela_0021): «<название Дел> · дд.мм»."""
    ref = str(data.get("batch_ref") or "")
    if not ref.startswith("meeting:") or not ref[8:]:
        return
    m = conn.execute(
        "SELECT btrim(title) AS t, started_at FROM crm.meetings WHERE ref = %s AND nullif(btrim(title), '') IS NOT NULL "
        "ORDER BY started_at DESC LIMIT 1", (ref[8:],)).fetchone()
    if m:
        data["batch_title"] = f"{m['t']} · {m['started_at'].astimezone(meetings.MSK):%d.%m}"


def _meeting_of(conn, payload: dict, batch_title: str | None) -> tuple[str, str, str]:
    """Встреча вопроса о человеке: (название, «дд.мм», «дд.мм.гггг») — название Дел и день по номеру встречи (№43),
    иначе — из пачки службы («<название> · дд.мм»)."""
    ref = str((payload.get("speaker") or {}).get("meeting_ref") or "").strip()
    m = conn.execute(
        "SELECT coalesce(nullif(btrim(title), ''), source_title) AS t, started_at FROM crm.meetings WHERE ref = %s "
        "ORDER BY started_at DESC LIMIT 1", (ref,)).fetchone() if ref else None
    if m and m["t"]:
        day = m["started_at"].astimezone(meetings.MSK)
        return m["t"], f"{day:%d.%m}", f"{day:%d.%m.%Y}"
    head, _, dm = str(batch_title or "").rpartition(" · ")
    return (head, dm.strip(), "") if head else (str(batch_title or ""), "", "")


def _meeting_person(conn, user, data: dict, payload: dict) -> dict | None:
    """Человек, которого служба встреч не узнала (kind person, source meeting, контракт §2.3 и §3.3): сервер сам решает
    (people.new_person) — завести, спросить кнопками или ответить «уже убран». Ответ — готовый результат операции (уже
    в CRM, уже убран) или None: дальше обычный путь — заведёт само (_auto) или будет ждать с вопросом.

    Решает сервер, а не служба: узнавание людей одно (закон 3), и справочника имён у службы нет. Поэтому и вопрос
    (ask, options, pick, held) строит сервер — свой вопрос службы здесь не нужен."""
    d = dict(payload.get("person")) if isinstance(payload.get("person"), dict) else {}
    raw_name = str(d.get("name") or payload.get("title") or "").strip()
    if not raw_name:
        raise OpError("человек со встречи: нужно имя")
    v = people.new_person(conn, raw_name)
    if v["verdict"] in ("known", "removed"):
        known = v["known"]
        res = {"person_id": known["id"], "created": False} if known else None
        # Решил сервер, а не человек: decided_by пуст — очков за «разобрал Новое» (stats.py) это не даёт.
        row = _insert(conn, "tasks.suggestions", {
            **data, "payload": Jsonb(payload), "status": "accepted" if known else "rejected",
            "reason": f"уже в CRM: {known['name']}" if known else f"уже убран в архив: {v['removed']['name']}",
            "decided_by": None, "decided_at": dt.datetime.now(dt.timezone.utc),
            **({"result": Jsonb(jsonable(res))} if res else {})})
        log.info("Новое: человек со встречи «%s» — %s", raw_name, row["reason"])
        return {"suggestion": row, **({"person": res} if res else {})}
    title, dm, dmy = _meeting_of(conn, payload, data.get("batch_title"))
    where = f"«{title}»{' ' + dm if dm else ''}" if title else ""
    # Карточке — голое имя и откуда она; подпись в скобках — компания, если такая уже есть, иначе — заметкой (новую
    # компанию из подписи спикера не заводим: «(экспо)» и «(терапевт)» — не компании).
    d["name"] = v["name"]
    if not d.get("source"):
        d["source"] = f"встреча {dmy}: {title}" if dmy else (f"встреча: {title}" if title else "встреча")
    if v["org_name"]:
        payload["org_name"] = v["org_name"]
    if v["note"]:
        d["note"] = "; ".join(x for x in (d.get("note"), v["note"]) if x)
    payload["person"] = d
    payload.setdefault("where", f"встреча {where}" if where else "встреча")
    for k in ("ask", "options", "pick", "held"):
        payload.pop(k, None)
    if v["verdict"] == "auto" and payload.get("auto") is True and user == data.get("for_user"):
        if not data.get("quote"):
            data["quote"] = f"Встреча {where}: «{raw_name}»" if where else f"Встреча: «{raw_name}»"
        return None  # _auto заведёт сам: «человек заведён сам», в «Сделано само» с «Вернуть»
    # Неясно — один вопрос кнопками. auto службы снимаем: вопрос — всегда вопрос.
    payload.pop("auto", None)
    at = f" на встрече {where}" if where else ""
    cands = v["candidates"]
    if len(cands) > people.NEW_ASK_MAX:
        # Похожих больше, чем кнопок «Это X»: две случайные кнопки и pick не дали бы выбрать третьего, а «Новый человек»
        # завёл бы дубль (проверка 15.10.2026). Спрашиваем словами — всех по имени; ответ «это X» — Claude
        # (suggestion.decide с set.person_id), кнопками — только «Новый человек» и «Не для CRM».
        labels = [_cand_label(c) for c in cands]
        payload["ask"] = f"«{v['name']}»{at} — кто это: {_or_words(labels)}? Скажи Claude, кто из них, или заведи нового."
        payload["options"] = [{"label": "Новый человек", "new": True},
                              {"label": "Не для CRM", "reject": True, "reason": "не для CRM"}]
    elif cands:
        labels = [_cand_label(c) for c in cands]
        payload["ask"] = f"«{v['name']}»{at} — это {_or_words(labels)}?"
        names = [str(c["name"]) for c in cands]
        opts, seen = [], set()
        for c, lab in zip(cands, labels):
            # «Это Иван Петров»; два с одним именем — с компанией; и с ней одинаковые — номером.
            text = "Это " + (str(c["name"]) if names.count(str(c["name"])) == 1 else lab)
            if text in seen:
                text = f"{text} · {len(opts) + 1}"
            seen.add(text)
            opts.append({"label": text[:80], "person_id": str(c["id"])})
        payload["options"] = [*opts, {"label": "Новый человек", "new": True},
                              {"label": "Не для CRM", "reject": True, "reason": "не для CRM"}]
        payload["pick"] = True  # «Да» без выбора завело бы третьего «Ивана» — ответ только кнопкой
    else:
        payload["ask"] = f"Кто это{at} — «{v['name']}»?"
        payload["options"] = [{"label": f"Завести «{v['name']}»"[:80], "new": True},
                              {"label": "Не для CRM", "reject": True, "reason": "не для CRM"}]
    payload["held"] = "; ".join(v["why"]) or "служба не просила завести само"
    _options(payload)
    return None


# ── «Кто это — X или Y?» и почему ждёт (аудит 15.10.2026, №1) ───────────────

_HOW_MANY = {2: "двое", 3: "трое", 4: "четверо", 5: "пятеро", 6: "шестеро", 7: "семеро"}
WHO_PICK_MAX = 3  # кандидатов кнопками; четвёртая — «Без человека» (OPTIONS_MAX)
# Кандидат вопроса «Кто это — X или Y?» — только с настоящим совпадением: своё или другое имя, короткое имя, имя и
# фамилия, одна фамилия (0,6 и выше — как порог вопроса о человеке со встречи, контракт §3.3). «Маша» против «Марии
# Петровой» (0,35 — совпало одно имя с полным) — не выбор, а «не знаю человека»: иначе любое имя одним словом при двух
# Мариях в справочнике становилось вопросом со случайными кнопками, а «Да» — отказом (проверка 15.10.2026).
WHO_ASK_MIN = 0.6
WHO_ALL = 1000  # соперников — всех: «8 с именем …» при обрезке limit врало


def _person_who(conn, name: str, cache: dict | None = None) -> dict:
    """people.who по имени — один раз на операцию: на сотнях карточек вызов стоит около секунды, а предложение спрашивает
    одно имя в _names, _person_rivals и _who_guard."""
    if cache is not None and name in cache:
        return cache[name]
    w = people.who(conn, q=name, limit=WHO_ALL)
    if cache is not None:
        cache[name] = w
    return w


def _person_rivals(conn, name: str, cache: dict | None = None) -> list[dict]:
    """Двусмысленное имя: кандидаты people.who не слабее WHO_ASK_MIN и рядом с лучшим (ближе GAP), когда уверенного нет.
    Меньше двух — []: одно совпадение или только слабые — это «не знаю», а не выбор."""
    w = _person_who(conn, name, cache)
    c = [x for x in w["candidates"] if x["score"] >= WHO_ASK_MIN]
    if w["sure"] or len(c) < 2:
        return []
    close = [x for x in c if c[0]["score"] - x["score"] < people.GAP]
    return close if len(close) >= 2 else []


def _cand_label(c: dict) -> str:
    return f"{c['name']} ({c['org']})" if c.get("org") else str(c["name"])


def _or_words(names: list[str]) -> str:
    """«A или B», «A, B или C»."""
    return names[0] if len(names) == 1 else ", ".join(names[:-1]) + " или " + names[-1]


def _who_ask(name: str, rivals: list[dict]) -> str:
    names = [str(c["name"]) for c in rivals[:WHO_PICK_MAX]]
    more = len(rivals) - len(names)
    if more > 0:
        return f"Кто это — «{name}»: {', '.join(names)} или ещё {more}?"
    return f"Кто это — «{name}»: {_or_words(names)}?" if names else f"Кто это — «{name}»?"


def _many_held(name: str, rivals: list[dict]) -> str:
    labels = [_cand_label(c) for c in rivals[:WHO_PICK_MAX]]
    more = len(rivals) - len(labels)
    tail = ", ".join(labels) + (f" и ещё {more}" if more > 0 else "")
    if all(c.get("how") in ("имя", "другое имя") for c in rivals):
        return f"{_HOW_MANY.get(len(rivals), str(len(rivals)))} с именем «{name}»: {tail}"
    return f"на «{name}» похожи: {tail}"


def _name_state(conn, payload: dict, cache: dict | None = None) -> tuple[list[str], list[dict]]:
    """Что с именами предложения: узнан — молчим; не знаю, двое и больше — строкой. Ответ — (строки, кандидаты
    двусмысленного человека). Узнаёт тем же _names, что и принятие: что здесь «узнан», то при «Да» и встанет."""
    ids = _names(conn, payload, cache)
    out: list[str] = []
    rivals: list[dict] = []
    pn = payload.get("project_name")
    if pn and not payload.get("project_id") and "project_id" not in ids:
        same = conn.execute(
            "SELECT name FROM crm.projects WHERE archived_at IS NULL AND crm.named(crm.norm(%s), name, aliases) "
            "ORDER BY name", (pn,)).fetchall()
        out.append(f"не один проект с именем «{pn}»: {', '.join(r['name'] for r in same[:3])}" if len(same) >= 2
                   else f"не знаю проект «{pn}»")
    name = payload.get("person_name")
    if name and not payload.get("person_id") and "person_id" not in ids:
        rivals = _person_rivals(conn, name, cache)
        out.append(_many_held(name, rivals) if rivals else f"не знаю человека «{name}»")
    return out, rivals


def _person_options(kind: str, rivals: list[dict]) -> list[dict]:
    """Кандидаты кнопками и «Без человека». У уточнения «без человека» — не трогать человека дела: снять его значило бы
    ответить на вопрос, которого не задавали."""
    out, seen = [], set()
    for c in rivals[:WHO_PICK_MAX]:
        label = _cand_label(c)
        if label in seen:  # двое с одним именем и компанией — различить хоть должностью
            label = f"{label} · {c['role']}" if c.get("role") and f"{label} · {c['role']}" not in seen else f"{label} · {len(out) + 1}"
        seen.add(label)
        out.append({"label": label[:80], "person_id": str(c["id"])})
    if kind == "update":
        out.append({"label": "Человека не менять", "person_name": None})
    else:
        out.append({"label": "Без человека", "person_id": None, "person_name": None})
    return out


def _name_hold(conn, kind: str, payload: dict, cache: dict | None = None) -> list[str]:
    """Имена предложения «завести» или «уточнить» до записи: что не узналось — строками (уйдут в held), а у
    двусмысленного человека, если модель сама не спросила, — вопрос «Кто это — X или Y?» с кандидатами кнопками и
    pick (ответ — только кнопкой: «Да» оставило бы дело без человека). Модель спросила своё (ask или options) — её
    вопрос не трогаем. Кандидатов больше, чем кнопок (WHO_PICK_MAX), — только вопрос словами, без кнопок: нужного
    человека среди трёх случайных могло не оказаться, а «Поправить» у вопроса с кнопками веб прячет."""
    if kind not in ("create", "update"):
        return []
    problems, rivals = _name_state(conn, payload, cache)
    if rivals and not payload.get("ask") and not payload.get("options"):
        payload["ask"] = _who_ask(payload["person_name"], rivals)
        if len(rivals) <= WHO_PICK_MAX:
            payload["options"] = _person_options(kind, rivals)
            payload["pick"] = True
    return problems


AUTO_DAYS = 3
AUTO_CLOSE_REASON = "закрыто само"
AUTO_UPDATE_REASON = "уточнено само"
AUTO_CREATE_REASON = "заведено само"
AUTO_PERSON_REASON = "человек заведён сам"
AUTO_REASONS = (AUTO_CLOSE_REASON, AUTO_UPDATE_REASON, AUTO_CREATE_REASON, AUTO_PERSON_REASON)


def _auto(conn, user, s: dict, problems: list[str] | None = None, cache: dict | None = None) -> tuple[str | None, str | None]:
    """Завести, закрыть и уточнить дело по свежей встрече или переписке автоматика может сама.
    Возвращает (причина решения, None) или (None, почему ждёт) — ждёт человека в «Новом» («Подскажи»). Почему —
    строкой held (аудит 15.10.2026, №1: до того сервер молчал, и вопрос висел без причины): имена, которые не узнались
    (problems — уже посчитанные _name_hold), и, если автоматика просила сама, что помешало — старая встреча, нет
    основания, дело заведено после встречи. Автоматика сама спрашивает (без auto) и имена узнались — (None, None).

    Владелец 05.10.2026 разрешил очевидное закрытие, 08.10.2026 — любое закрытие и уточнение:
    «спокойно закрывай и спокойно уточняй… я доверяю». До того из закрытий без пометки «бесспорно»
    он принял 18 из 19, из уточнений — 15 из 19 (два отказа — прошедшие сроки старых встреч).
    08.10.2026 вечером — и новые дела: «Новое» делится на «уверен — поставил» и «не уверен —
    подскажи». С 05.10 по 08.10 он принял 65 из 66 предложений «завести» — разбор был лишней
    работой. Не уверена автоматика сама — шлёт вопрос (payload.ask) вместо auto.

    Само — когда автоматика просит (payload.auto), основание названо (quote), встреча или переписка
    свежая (AUTO_DAYS), а человек и проект по имени узнаются однозначно: иначе мяч ушёл бы «никому».
    Закрыть и уточнить — ещё и только открытое дело самого владельца токена, не старше встречи:
    разбор архива и чужие дела по-прежнему ждут решения человека.
    Основание ложится комментарием к делу (_trace), «Вернуть» — в «Новом», «Сделано само».
    """
    p = s.get("payload") or {}
    kind = s.get("kind")
    if kind == "person":
        # Новый человек (14.10.2026, владелец согласился: «уверен — заводит сам»): только новая карточка и с основанием;
        # «это тот же человек?» — всегда вопрос: склеивает людей только владелец (LOGIC.md, закон 3). Свежесть не нужна —
        # человек не протухает, как дело из старой встречи.
        # Вопрос — всегда вопрос (15.10.2026): со встречи сервер сам превращает неясное имя в «Кто это…?» (new_person), и
        # auto службы его не перебивает.
        ok = (p.get("auto") is True and user == s.get("for_user") and s.get("quote") and not p.get("person_id")
              and not p.get("ask"))
        return (AUTO_PERSON_REASON, None) if ok else (None, None)
    if kind not in ("create", "close", "update"):
        return None, None
    # Закрытию имена не нужны (дело уже есть); «завести» и «уточнить» без однозначного человека и проекта — мяч ушёл бы
    # «никому».
    why = [] if kind == "close" else (problems if problems is not None else _name_state(conn, p, cache)[0])
    why = list(why)
    if p.get("auto") is True:
        where, of = ("встреча", "встречи") if s.get("source") == "meeting" else ("переписка", "переписки")
        if user != s.get("for_user"):
            why.append("само ставится только своим токеном")
        if not s.get("quote"):
            why.append("нет основания — цитаты")
        try:
            at = dt.date.fromisoformat(str(p.get("meeting_at") or "")[:10])
        except ValueError:
            at = None
            why.append(f"нет даты {of}")
        if at and not conn.execute("SELECT %s >= crm.today() - %s AS ok", (at, AUTO_DAYS)).fetchone()["ok"]:
            why.append(f"{where} старше {AUTO_DAYS} дней")
        if kind != "create":
            # goal — из v_tasks (строка Круга хозяина): цель дерева закрывает и правит только владелец (аудит №19).
            row = conn.execute(
                "SELECT status, owner_id, goal, (created_at AT TIME ZONE 'Europe/Moscow')::date AS made "
                "FROM tasks.v_tasks WHERE id = %s",
                (s.get("task_id"),),
            ).fetchone()
            if row is None:
                why.append("дело не видно")
            elif row["status"] != "open":
                why.append("дело уже не открыто")
            elif row["owner_id"] != user:
                why.append("дело чужое — решает его хозяин")
            elif row["goal"]:
                # Номера целей дерева — во входе разноски рядом с номерами дел («#N название»): одно неверное «close #N»
                # молча закрыло бы цель владельца. Ждёт его; сам он закрывает цель как обычно (op_task_set не запрещает).
                why.append("это цель дерева — " + ("закрывает" if kind == "close" else "правит") + " владелец")
            elif at and at < row["made"]:
                why.append(f"дело заведено позже {of}")
    elif not why:
        return None, None  # автоматика спрашивает сама — её вопрос и есть причина
    if why:
        return None, "; ".join(why)
    if kind == "close":
        return AUTO_CLOSE_REASON, None
    return (AUTO_CREATE_REASON if kind == "create" else AUTO_UPDATE_REASON), None


def op_suggestion_decide(conn, user, op):
    s = conn.execute(
        "SELECT * FROM tasks.suggestions WHERE id = %s AND status = 'pending' FOR UPDATE", (op.get("id"),)
    ).fetchone()
    if not s:
        raise OpError("предложение уже разобрано или не твоё")
    decision = op.get("decision")
    payload = dict(s["payload"] or {})
    # Ответ кнопкой (13.10.2026, владелец: «Claude должен давать свои ответы кнопками, а не „Да / Не надо“»):
    # payload.options — 2–4 варианта; option — номер выбранного. Вариант — те же поля, что у payload (имена проекта и
    # человека, мяч, место в дереве), или reject — «не дело». Ответ — причиной: из неё учатся разборы (place.py).
    if op.get("option") is not None:
        opts = payload.get("options") if isinstance(payload.get("options"), list) else []
        i = op.get("option")
        if not isinstance(i, int) or isinstance(i, bool) or not 0 <= i < len(opts) or not isinstance(opts[i], dict):
            raise OpError("нет такого варианта ответа")
        opt = opts[i]
        label = str(opt.get("label") or "").strip()
        if opt.get("reject"):
            decision = "reject"
            op = {**op, "reason": op.get("reason") or opt.get("reason") or label or None}
        else:
            decision = "accept"
            payload.update({k: v for k, v in opt.items() if k in OPTION_FIELDS})
            op = {**op, "reason": op.get("reason") or (f"ответ: {label}" if label else None)}
    if decision == "reject":
        row = _update(conn, "tasks.suggestions", {"id": s["id"]},
                      {"status": "rejected", "reason": op.get("reason"), "decided_by": user, "decided_at": dt.datetime.now(dt.timezone.utc)})
        return {"suggestion": row}
    if decision != "accept":
        raise OpError("решение: accept или reject")
    if s["kind"] == "person":
        named = (op.get("set") or {}).get("person_id") if isinstance(op.get("set"), dict) else None
        if named:
            # «Это Иван Третьяков» словами (Claude: set.person_id; проверка 15.10.2026): похожих больше, чем кнопок, —
            # нужного не было среди «Это X», а «Новый человек» завёл бы дубль. Выбор человека сильнее варианта и pick.
            try:
                named = str(uuid.UUID(str(named)))
            except ValueError:
                raise OpError("человек: person_id — не id карточки") from None
            if not conn.execute("SELECT 1 FROM crm.people WHERE id = %s AND archived_at IS NULL AND merged_into IS NULL",
                                (named,)).fetchone():
                raise OpError("человек: такой живой карточки нет или она не видна")
            payload["person_id"] = named
            payload.pop("new", None)
        elif payload.get("pick") and op.get("option") is None:
            # «Это X или Y?» («Иван» со встречи, два-три контакта в Telegram): «Да» без выбора завело бы нового человека
            # или привязало не того — ответ только кнопкой.
            raise OpError(f"{payload.get('ask') or 'Кто это?'} Ответь кнопкой в вебе или скажи Claude.")
        res = _person_sug(conn, user, s, payload)
        row = _update(conn, "tasks.suggestions", {"id": s["id"]}, {
            "status": "accepted", "reason": op.get("reason"), "decided_by": user,
            "decided_at": dt.datetime.now(dt.timezone.utc), "result": Jsonb(jsonable(res))})
        return {"suggestion": row, "person": res}
    fix_raw = dict(op.get("set") or {})
    fix_kr = _krug_split(fix_raw)  # «Подскажи» разборщика Круга: направления и «Зачем» — поправкой при «Да»
    fix = {**_pick(fix_raw, TASK_FIELDS, "дело"), **fix_kr}
    cache: dict = {}
    held = _who_guard(conn, s, payload, fix, op.get("option") is not None, cache)
    task, back = None, {}
    private = s["source"] in PRIVATE_SOURCES
    # Заметка машины (payload.notes — пересказ переписки или строка разбора встречи) из личного канала в дело не идёт
    # (аудит №8): дело видит команда клиента. Владелец написал в «Поправить» свою заметку — это его слово, она в деле.
    machine = str(payload.get("notes") or "").strip()
    owners_notes = "notes" in fix and not _same_text(fix.get("notes"), machine)
    if s["kind"] == "create":
        fields = {k: v for k, v in payload.items() if k in TASK_FIELDS | KRUG_FIELDS}
        fields.update(_names(conn, payload, cache))
        fields.update(fix)
        hide = bool(machine) and not owners_notes and _notes_private(conn, s["source"])
        if hide:
            fields.pop("notes", None)
        if (s["source"] in AUTO_DEAL_SOURCES and fields.get("project_id") and not fields.get("deal_id")
                and "deal_id" not in fix):
            # Дело автоматики у клиента с одной живой сделкой — её шаг (аудит №2): с 05.10 автоматика не поставила
            # сделку ни одному делу, и сделки горели «застывшими». Только у предложений встреч и переписки: дело,
            # сказанное самим владельцем (task.create), молча к сделке не пристёгиваем; «Поправить» без сделки — его слово.
            only = conn.execute("SELECT crm.only_deal(%s::uuid) AS d", (str(fields["project_id"]),)).fetchone()["d"]
            if only:
                fields["deal_id"] = only
        fields.setdefault("source", _task_source(s["source"]))
        fields.setdefault("source_ref", s["source_ref"])
        task = op_task_create(conn, user, {"task": fields})["task"]
        if hide:
            head = _sug_head(s)
            back["notes_comment_id"] = _comment(conn, user, task["id"], f"{head}: {machine}" if head else machine, True)
        if held:
            back.update(held=held, comment_id=_comment(conn, user, task["id"], f"Человек не поставлен: {held}", private))
    elif s["kind"] in ("close", "update"):
        fields = {k: v for k, v in payload.items() if k in TASK_FIELDS | KRUG_FIELDS}
        fields.update(_names(conn, payload, cache))  # «уточнить»: мяч перешёл к другому человеку — по имени
        if s["kind"] == "close":
            fields.setdefault("status", "done")
        fields.update(fix)
        hidden = None
        if private and machine and not owners_notes:
            fields.pop("notes", None)  # заметка машины из личного канала — в тот же приватный комментарий-основание
            hidden = machine
        _ball_norm(fields)  # «как было» — и про флаг «ждут от меня», иначе «Вернуть» его не снимет
        cur = task_by(conn, s["task_id"]) or {}
        task = op_task_set(conn, user, {"id": s["task_id"], "set": fields})["task"]
        # «Вернуть» у сделанного само: как было (только то, что поменялось) и чем это объяснили.
        back = {"was": {k: cur.get(k) for k in fields if k in cur and not _same(cur.get(k), task.get(k))},
                "comment_id": _trace(conn, user, s, task, f"человек не поставлен: {held}" if held else None, hidden)}
        if held:
            back["held"] = held
    elif s["kind"] == "assign":
        fields = {"owner_id": user, **fix}
        task = op_task_set(conn, user, {"id": s["task_id"], "set": fields})["task"]
    edited = bool(fix) and any(not _same(payload.get(k), v) for k, v in fix.items())
    row = _update(conn, "tasks.suggestions", {"id": s["id"]}, {
        "status": "edited" if edited else "accepted",
        "reason": op.get("reason"),
        "decided_by": user,
        "decided_at": dt.datetime.now(dt.timezone.utc),
        "result_task_id": task["id"] if task else None,
        "result": Jsonb(jsonable({**{k: task[k] for k in TASK_FIELDS | {"directions", "why_id"} if task and k in task}, **back})),
    })
    return {"suggestion": row, "task": task}


PERSON_SUG_ADD = ("aliases", "phones", "emails", "telegram_id", "telegram_username")
PERSON_SUG_NEW = ("name", "short", "aliases", "phones", "emails", "telegram_id", "telegram_username", "role", "note", "source")
# Новую компанию автоматика заводит любым видом, кроме клиента: клиентом её делает владелец (сверка 08.10.2026).
ORG_AUTO_KINDS = {"partner", "fund", "bank", "other"}


_org_key = people.org_key  # «Альфа-Банк» = «Альфа Банк», «Ромашка (группа)» = «Ромашка» — одно правило с people


def _org_for(conn, user, name: Any, kind: Any, note: str) -> tuple[Any, bool]:
    """Компания по имени: есть такая (имя или другое имя) — она, нет — новая. Ответ — (id, заведена ли сейчас)."""
    name = " ".join(str(name or "").split())[:120]
    if not _org_key(name):
        return None, False
    o = people.org_named(conn, name)
    if o:
        return o["id"], False
    row = org_create(conn, user, {"data": {"name": name, "kind": kind if kind in ORG_AUTO_KINDS else "other", "note": note}})["row"]
    return row["id"], True


def _person_sug(conn, user, s: dict, payload: dict) -> dict:
    """Принятый «новый человек» (kind person, dela_0019): дописать в карточку или завести новую.

    payload.person — что известно (имя, другие имена, номера, почта, Telegram, должность, заметка); person_id — «это
    он» (вариант ответа или вопрос «это X?»), new — «новый человек» поверх предложенного; rename — имя из person
    становится именем карточки (старое — другим именем). Пока вопрос ждал, тот же номер, Telegram или почта могли
    появиться в чьей-то карточке — тогда дописываем туда, а не плодим дубль. Ответ — в result: person_id, created и
    org_created (заведена ли компания) — по ним «Вернуть» уберёт ровно сделанное.
    """
    d = payload.get("person") if isinstance(payload.get("person"), dict) else {}
    pid = None if payload.get("new") else payload.get("person_id")
    if not pid:
        keys = [{"telegram_id": d.get("telegram_id")}, {"telegram": d.get("telegram_username")},
                *({"phone": x} for x in d.get("phones") or []), *({"email": x} for x in d.get("emails") or [])]
        for kw in keys:
            if not any(kw.values()):
                continue
            w = people.who(conn, **kw)
            if w["sure"] and w["best"]["how"] in people.KEY_HOWS:  # и ключ слитого дубля — его живая карточка (№23)
                pid = w["best"]["id"]
                break
    if pid:
        add = {k: d[k] for k in PERSON_SUG_ADD if d.get(k)}
        if d.get("name") and not payload.get("rename"):
            add["aliases"] = [*(add.get("aliases") or []), d["name"]]  # как его зовут в Telegram или книжке — другим именем
        res = people.op_person_add(conn, user, {"id": pid, "add": add}, OpError)
        out = {"person_id": pid, "created": False, "changed": res["changed"]}
        if res.get("skipped"):
            out["skipped"] = res["skipped"]  # чужое имя не дописано (№1): в result — кто так зовётся
        old = res["row"]["name"]
        if payload.get("rename") and d.get("name") and d["name"] != old:
            person_set(conn, user, {"id": pid, "set": {"name": d["name"]}})
            more = people.op_person_add(conn, user, {"id": pid, "add": {"aliases": [old]}}, OpError)["skipped"]
            if more:
                out["skipped"] = [*(out.get("skipped") or []), *more]
            out["was_name"] = old
        return out
    if not d.get("name"):
        raise OpError("человек: нужно имя")
    if s.get("source") in ("telegram", "userbot", "contacts"):
        # Пометка новой карточки из Telegram и телефонной книжки — только откуда (аудит №10): старая служба «Людей сами»
        # клала туда «<откуда>. <почему>» — пересказ переписки, который команда видела бы в карточке. Почему — в цитате
        # и вопросе предложения.
        d = {**d, "note": str(payload.get("where") or "").strip() or None}
    where = {"telegram": "Telegram", "contacts": "телефонной книжки", "meeting": "встречи"}.get(s.get("source"), s.get("source"))
    org_id, org_made = _org_for(conn, user, payload.get("org_name"), payload.get("org_kind"), f"Заведена сама из {where}")
    data = {k: d[k] for k in PERSON_SUG_NEW if d.get(k) not in (None, "", [])}
    skipped: list = []
    if data.get("aliases"):
        # Новая карточка — то же правило, что person.add (№1): имя или короткое имя другой живой карточки другим именем
        # не пишем, иначе это имя перестанет узнаваться у обоих.
        keep, skipped = people.own_aliases(conn, None, data["aliases"] if isinstance(data["aliases"], list) else [data["aliases"]])
        if keep:
            data["aliases"] = keep
        else:
            data.pop("aliases")
    if org_id:
        data["org_id"] = org_id
    row = person_create(conn, user, {"data": data})["row"]
    out = {"person_id": row["id"], "created": True, "org_id": org_id, "org_created": org_made}
    if skipped:
        out["skipped"] = skipped
    return out


def _trace(conn, user, s: dict, task: dict | None, extra: str | None = None, notes: str | None = None) -> str | None:
    """Принятое «закрыть» или «поправить» оставляет при деле комментарий: откуда и почему.

    Уточнение из встречи или переписки (payload.note — новые подробности) иначе осталось бы
    только в «Новом», а через неделю там его уже не найти; у закрытого — основание (quote).
    extra — что не вышло (человек двусмыслен после ответа на другой вопрос) — в тот же комментарий.
    notes — заметка машины из личного канала, которая не легла в дело (аудит №8), — туда же.
    Основание из личного канала (PRIVATE_SOURCES) — приватным комментарием: это цитата переписки или расшифровки.
    Тот же текст у дела уже есть — второго не пишем (ответ None).
    Возвращает id комментария: «Вернуть» у сделанного само убирает и его.
    """
    text = ((s.get("payload") or {}).get("note") or s.get("quote") or "").strip()
    if notes:
        text = f"{text}\n{notes}" if text else notes
    if extra:
        text = f"{text} — {extra}" if text else extra
    if not text or not task:
        return None
    head = _sug_head(s)
    return _comment(conn, user, task["id"], f"{head}: {text}" if head else text, s.get("source") in PRIVATE_SOURCES)


def _names(conn, payload: dict, cache: dict | None = None) -> dict:
    """Имена из автоматики («Алфавит», «Дмитрий Шерстобитов») — в id по справочнику.

    Встречи и юзербот не знают id: шлют project_name и person_name. Не нашлось
    или проект не виден принимающему — поле остаётся пустым, человек поправит.
    cache — «кто это» по имени на одну операцию (_person_who).
    С 15.10.2026 (аудит №2) и deal_name — сделка по точному имени (_deal_named).
    """
    out: dict = {}
    for key, col in (("project_name", "project_id"), ("person_name", "person_id")):
        name = payload.get(key)
        if not name or payload.get(col):
            continue
        r = conn.execute(f"SELECT {col} FROM crm.match_name(%s)", (name,)).fetchone()
        if r and r[col]:
            table = "crm.projects" if col == "project_id" else "crm.people"
            if conn.execute(f"SELECT 1 FROM {table} WHERE id = %s", (r[col],)).fetchone():
                out[col] = r[col]
        elif col == "person_id":
            # Точного имени нет — «Женя Соколов» из встречи: то же узнавание, что у встреч и звонков,
            # но только уверенное (people.who видит лишь доступных человеку — RLS).
            w = _person_who(conn, name, cache)
            if w["sure"]:
                out[col] = w["best"]["id"]
    if payload.get("deal_name") and not payload.get("deal_id"):
        out.update(_deal_named(conn, payload["deal_name"], out.get("project_id") or payload.get("project_id")))
    return out


def _deal_named(conn, name: Any, project: Any) -> dict:
    """Сделка дела из встречи или переписки (аудит 15.10.2026, №2): модель называет её точным именем из блока
    <сделки> (payload.deal_name). Ищем точное имя (crm.norm) среди живых сделок клиента; клиента не назвали или не
    узнали — среди всех живых, если такая одна (тогда и клиент — её). Не нашлось или двое — пусто: дело встанет без
    сделки, самопринятию это не мешает (а у единственной живой сделки клиента её подставит op_suggestion_decide).
    Видимость — RLS принимающего."""
    name = " ".join(str(name or "").split())
    if not name:
        return {}
    if project:
        rows = conn.execute(
            "SELECT id, project_id FROM crm.deals WHERE project_id::text = %s AND stage <> 'archive' "
            "AND crm.norm(name) = crm.norm(%s) LIMIT 2", (str(project), name)).fetchall()
    else:
        rows = conn.execute(
            "SELECT id, project_id FROM crm.deals WHERE stage <> 'archive' AND crm.norm(name) = crm.norm(%s) LIMIT 2",
            (name,)).fetchall()
    if len(rows) != 1:
        return {}
    return {"deal_id": rows[0]["id"]} if project else {"deal_id": rows[0]["id"], "project_id": rows[0]["project_id"]}


def _who_guard(conn, s: dict, payload: dict, fix: dict, by_option: bool, cache: dict | None = None) -> str | None:
    """«Да» без выбора не теряет человека (аудит 15.10.2026, №1): до того «Да» у предложения с двусмысленным именем
    заводило дело без человека — 14 дел из 14 с 08.10. Принять «завести» или «уточнить» без варианта и без
    set.person_id, когда имя двусмысленно (двое и больше) или вопрос ждёт выбора (pick), — отказ словами с кандидатами.
    Ответ вариантом модели (она спрашивала о другом), после которого имя всё ещё двусмысленно, проходит: дело — без
    человека, а почему — строкой (её кладут в result.held и комментарий к делу)."""
    if s["kind"] not in ("create", "update") or "person_id" in fix or payload.get("person_id"):
        return None
    name = payload.get("person_name")
    if not name or "person_id" in _names(conn, {"person_name": name}, cache):
        return None
    rivals = _person_rivals(conn, name, cache)
    if not by_option and (rivals or payload.get("pick")):
        # «в вебе»: старый телефон кнопок-вариантов не умеет (option в decide не шлёт) — пусть знает, где ответить.
        how = ("Ответь кнопкой в вебе или скажи Claude." if payload.get("options")
               else "Выбери человека в «Поправить» или скажи Claude." if s["kind"] == "create" else "Скажи Claude, кто это.")
        raise OpError(f"{_who_ask(name, rivals)} {how}")
    return _many_held(name, rivals) if rivals else None


def _task_source(s: str) -> str:
    return {"meeting": "meeting", "telegram": "telegram", "userbot": "telegram", "bot": "bot", "mcp": "mcp", "import": "import"}.get(s, "manual")


def db_owner(conn) -> str:
    return conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]


_org_create, _org_set = _entity("crm.orgs", ORG_FIELDS, "организация")
_person_create, _person_set = _entity("crm.people", PERSON_FIELDS, "человек")


def _shown(view: str, fn):
    """Ответ операции с человеком или компанией — строкой из вида (crm.v_people, crm.v_orgs; аудит №10), а не RETURNING *:
    пометки владельца о сотруднике (черты, заметка) не должны уйти ему в ответе на правку его же телефона."""
    def handler(conn, user, op):
        res = fn(conn, user, op)
        row = conn.execute(f"SELECT * FROM {view} WHERE id = %s", (res["row"]["id"],)).fetchone()
        return {**res, "row": row or res["row"]}
    return handler


org_create, org_set = _shown("crm.v_orgs", _org_create), _shown("crm.v_orgs", _org_set)
person_create, person_set = _shown("crm.v_people", _person_create), _shown("crm.v_people", _person_set)
_project_create, _project_set = _entity("crm.projects", PROJECT_FIELDS, "проект", extra=("id", "import_ref"))


def _project_krug(fn, key: str):
    """Проект в Круге (dela_0009, 0013): направления и цель, на которой он висит (why_id), — в строку Круга хозяина
    проекта, остальное — как было."""
    def handler(conn, user, op):
        raw = dict(op.get(key) or {})
        kr = _krug_split(raw)
        res = fn(conn, user, {**op, key: raw})
        if kr:
            _krug_write(conn, user, "project", res["row"], kr)
        return res
    return handler


project_create = _project_krug(_project_create, "data")
project_set = _project_krug(_project_set, "set")
_deal_create, _deal_set = _entity("crm.deals", DEAL_FIELDS, "сделка", owner_col=None)
interaction_create, interaction_set = _entity("crm.interactions", INTERACTION_FIELDS, "взаимодействие")
payment_create, payment_set = _entity("crm.payments", PAYMENT_FIELDS, "оплата", owner_col=None, extra=("id",))


def _shown_deal(conn, did) -> dict:
    """Сделка в ответе — из вида, а не из RETURNING: деньги и личная оценка
    владельца не должны уйти тому, кому они закрыты."""
    return conn.execute("SELECT * FROM crm.v_deals WHERE id = %s", (did,)).fetchone()


def deal_create(conn, user, op):
    return {"row": _shown_deal(conn, _deal_create(conn, user, op)["row"]["id"])}


def deal_set(conn, user, op):
    return {"row": _shown_deal(conn, _deal_set(conn, user, op)["row"]["id"])}


# Чьим предложениям «завести» сделка клиента ставится сама, когда она у него одна живая (аудит №2).
AUTO_DEAL_SOURCES = ("meeting", "telegram", "userbot")


def _touch_deal(conn, d: dict) -> Any:
    """«Сделка касания» записи без ключа deal_id (crm.touch_deal, аудит №3). Кривой id или дата — None: отказ словами
    даст сама запись (interaction_create), а не этот вспомогательный вопрос."""
    ppl = d.get("person_ids") if isinstance(d.get("person_ids"), list) else []
    try:
        with conn.transaction():
            return conn.execute("SELECT crm.touch_deal(%s::uuid, %s::uuid[], %s::timestamptz) AS d",
                                (str(d["project_id"]), [str(x) for x in ppl], d.get("at") or None)).fetchone()["d"]
    except psycopg.Error:
        return None


def op_interaction_add(conn, user, op):
    """Запись хронологии. Запись встречи (source meeting) ведёт сервер по её карточке (meetings.touch, аудит №6,
    флаг meeting_touch): старая служба встреч, которая шлёт её сама (crm_note), получает запись сервера — её карточка
    уже в базе: клиент, сделка, люди и название оттуда, а не из голосования службы, — или отказ словами, если по
    карточке записи нет (личная, без клиента, двойник, убрана владельцем). Карточки нет — как раньше, но вторую живую
    запись той же встречи не заводим (одна на встречу — индекс dela_0021)."""
    d = op.get("data") or {}
    if isinstance(d, dict) and d.get("source") == "meeting" and d.get("project_id"):
        # Встреча в личный проект (семья, терапия) — не запись хронологии клиента (аудит №9: «независимо от ответа»).
        pr = conn.execute("SELECT sphere FROM crm.projects WHERE id::text = %s", (str(d["project_id"]),)).fetchone()
        if pr and pr["sphere"] == "home":
            raise OpError("запись встречи в личный проект не ложится: личные встречи в хронологии не живут")
    ref = d.get("source_ref") if isinstance(d, dict) and d.get("source") == "meeting" else None
    if ref:
        m = conn.execute(
            "SELECT id FROM crm.meetings WHERE owner_id = %s AND crm.meeting_ref(coalesce(url, '/m/' || ref)) = "
            "crm.meeting_ref(%s) ORDER BY chars_n DESC LIMIT 1", (user, ref)).fetchone()
        if m:
            meetings.touch(conn, m["id"])
        have = conn.execute(
            "SELECT * FROM crm.interactions WHERE source = 'meeting' AND deleted_at IS NULL AND owner_id = %s "
            "AND crm.meeting_ref(source_ref) = crm.meeting_ref(%s)", (user, ref)).fetchone()
        if have:
            return {"row": have, "existing": True}
        if m:
            raise OpError("запись этой встречи в хронологии ведёт сервер по карточке встречи: по ней записи нет "
                          "(ещё не разобрана, личная, без клиента, двойник или убрана владельцем)")
    if isinstance(d, dict) and "deal_id" not in d and d.get("project_id"):
        # Сделку не назвали (ключа нет) — «сделка касания»: названная у клиента, одна живая, по людям записи (аудит №3).
        # Ключ есть, хоть null, — решение человека: не трогаем.
        deal = _touch_deal(conn, d)
        if deal:
            op = {**op, "data": {**d, "deal_id": str(deal)}}
    return interaction_create(conn, user, op)


def op_interaction_set(conn, user, op):
    """Правка записи хронологии. Запись встречи поправил её хозяин — его слово сильнее карточки: встрече замок touch,
    сервер эту запись больше не переписывает (meeting.set unlock touch — снова по карточке)."""
    res = interaction_set(conn, user, op)
    # «Скрыть от команды» (только private) — не правка текста: замка нет, запись и дальше следует карточке, и владелец
    # видит итог с неё (вид crm.v_interactions подменяет только не под замком). С замком он видел бы в хронологии и в
    # life.interactions одно «Клиент: тема» при «только факт» (проверка пакета «Приватность»); crm.meeting_touch поле
    # private не трогает, команда запись уже не видит.
    if res["row"]["source"] == "meeting" and not set(op.get("set") or {}) <= {"private"}:
        meetings.lock_touch(conn, res["row"])
    return res


def op_interaction_delete(conn, user, op):
    row = conn.execute(
        "UPDATE crm.interactions SET deleted_at = now() WHERE id = %s AND deleted_at IS NULL RETURNING *", (op.get("id"),)
    ).fetchone()
    if not row:
        raise OpError("нет такой записи хронологии или её нельзя убрать")
    if row["source"] == "meeting":
        # Владелец убрал запись встречи — его слово сильнее автоматики: замок touch, сервер её больше не вернёт
        # (meeting.set unlock touch — вернуть). Встречу видит только её владелец (RLS): чужая правка замка не ставит.
        meetings.lock_touch(conn, row)
    return {"row": row}


DICTATION_SOURCES = {"phone", "web", "bot", "mcp"}


def op_dictation_add(conn, user, op):
    """Наговорка, из которой вышли дела: текст дословно, id — source_ref её дел (06.10.2026).

    Строка не переписывается: телефон повторяет операцию из очереди, и второй раз — тот же ответ.
    """
    data = _pick(op.get("dictation") or {}, {"id", "text", "at", "source"}, "наговорка")
    if not data.get("id") or not data.get("text"):
        raise OpError("наговорка: нужны id и текст")
    if data.get("source", "phone") not in DICTATION_SOURCES:
        raise OpError("наговорка: откуда — phone, web, bot или mcp")
    data["owner_id"] = user
    if not data.get("at"):
        data.pop("at", None)
    cols = list(data)
    q = sql.SQL("INSERT INTO tasks.dictations ({}) VALUES ({}) ON CONFLICT (id) DO NOTHING RETURNING *").format(
        sql.SQL(", ").join(map(sql.Identifier, cols)), sql.SQL(", ").join(sql.Placeholder() * len(cols)))
    row = conn.execute(q, [data[c] for c in cols]).fetchone() or conn.execute(
        "SELECT * FROM tasks.dictations WHERE id = %s", (data["id"],)).fetchone()
    if not row:
        raise OpError("наговорка с таким id — чужая")
    return {"dictation": row}


def op_suggestion_seen(conn, user, op):
    """«Понятно» у решённого без человека («Закрыто само»): видел, согласен — из «Нового» уходит.

    Владелец 06.10.2026: «закрыто дело 268, так и висит… никуда не уходит».
    """
    ids = [str(x) for x in (op.get("ids") or ([op["id"]] if op.get("id") else []))]
    if not ids:
        raise OpError("понятно: нужны id предложений")
    rows = conn.execute(
        "UPDATE tasks.suggestions SET seen_at = now() WHERE id = ANY(%s::uuid[]) AND for_user = %s "
        "AND status <> 'pending' AND seen_at IS NULL RETURNING id", (ids, user)).fetchall()
    return {"seen": [r["id"] for r in rows]}


HANDLERS: dict[str, Callable] = {
    "task.create": op_task_create,
    "task.set": op_task_set,
    "task.done": _status("done"),
    "task.reopen": _status("open"),
    "task.cancel": _status("cancelled"),
    "task.reminded": op_task_reminded,
    "comment.add": op_comment_add,
    "comment.delete": op_comment_delete,
    "org.create": org_create,
    "org.set": org_set,
    "person.create": person_create,
    "person.set": person_set,
    "person.add": lambda conn, user, op: people.op_person_add(conn, user, op, OpError),
    "person.merge": lambda conn, user, op: people.op_person_merge(conn, user, op, OpError),
    "svod.set": lambda conn, user, op: people.op_svod_set(conn, user, op, OpError),
    "project.create": project_create,
    "project.set": project_set,
    "deal.create": deal_create,
    "deal.set": deal_set,
    "access.set": op_access_set,
    "user.settings": op_user_settings,
    "interaction.add": op_interaction_add,
    "interaction.set": op_interaction_set,
    "interaction.delete": op_interaction_delete,
    "payment.create": payment_create,
    "payment.set": payment_set,
    "suggestion.create": op_suggestion_create,
    "suggestion.decide": op_suggestion_decide,
    "suggestion.seen": op_suggestion_seen,
    "dictation.add": op_dictation_add,
}

# Ошибки базы, которые повтор не исправит: формат, права, правила.
PERMANENT = (
    psycopg.errors.IntegrityError,
    psycopg.errors.RaiseException,
    psycopg.errors.InsufficientPrivilege,
    psycopg.errors.DataError,
    psycopg.errors.InvalidTextRepresentation,
)


# Слова базы, которые человеку читать неудобно, — его словами (текст в базе тот же: на него опираются тесты и старые
# клиенты). «Шаг к самому себе» — слова прежнего «Зачем»; с 15.10.2026 так же отказывает «Вложить в…» (правило 27).
DB_WORDS = {
    "«зачем» по кругу — дело не может быть шагом к самому себе":
        "Нельзя: это дело уже внутри того, куда вкладываешь («зачем» по кругу)",
}


def _db_error(e: psycopg.Error) -> str:
    d = e.diag
    msg = (d.message_primary or str(e)).strip()
    for k, v in DB_WORDS.items():
        if k in msg:
            return v
    return msg + (f" ({d.message_detail})" if d.message_detail else "")


def apply_ops(url: str, user: str, ops: list[dict], via: str, actor: str | None = None) -> dict:
    """Пачка операций одной транзакцией, каждая — в своей точке сохранения.

    Упавшая операция не роняет остальные. Ответ на каждую ложится в
    crm.ops_seen под её op_id: повтор пачки отдаёт те же ответы.
    """
    results = []
    with db.session(url, user, actor, via) as conn:
        for op in ops:
            op_id = op.get("op_id")
            if op_id:
                # op_id — uuid (crm.ops_seen.op_id): кривой ронял транзакцию всей пачки (InvalidTextRepresentation до try,
                # HTTP 500). Теперь — отказ этой одной операции словами; в ops_seen его не кладём — ключ не ляжет.
                try:
                    uuid.UUID(str(op_id))
                    if not isinstance(op_id, str) or op_id.lower().startswith(("urn", "uuid")):
                        raise ValueError  # питон эти формы берёт, PostgreSQL — нет
                except ValueError:
                    results.append({"op_id": op_id, "ok": False, "error": "op_id — не uuid"})
                    continue
                seen = conn.execute("SELECT result FROM crm.ops_seen WHERE op_id = %s", (op_id,)).fetchone()
                if seen:
                    results.append(seen["result"])
                    continue
            handler = HANDLERS.get(op.get("op", ""))
            try:
                if handler is None:
                    raise OpError(f"нет такой операции: {op.get('op')!r}")
                with conn.transaction():
                    res = {"op_id": op_id, "ok": True, **jsonable(handler(conn, user, op))}
            except OpError as e:
                res = {"op_id": op_id, "ok": False, "error": str(e)}
            except PERMANENT as e:
                res = {"op_id": op_id, "ok": False, "error": _db_error(e)}
            if op_id:
                conn.execute("INSERT INTO crm.ops_seen (op_id, user_id, result) VALUES (%s, %s, %s)", (op_id, user, Jsonb(res)))
            results.append(res)
        seq = conn.execute("SELECT max(seq) AS s FROM tasks.tasks").fetchone()["s"] or 0
    return {"results": results, "seq": seq}


# ── Синк ────────────────────────────────────────────────────────────────

SYNC_TABLES = {
    # Дело приходит заново и когда поменялась только его строка Круга (krug_seq): направления и «Зачем»
    # живут в своей таблице (dela_0009), а клиенту они — поля дела.
    "tasks": "SELECT * FROM tasks.v_tasks WHERE greatest(seq, coalesce(krug_seq, 0)) > %s",
    # Направления проектов и цель, на которой проект висит, — их наследуют дела (у задач свои строки Круга едут в
    # самом деле). Только свои (RLS).
    "krug": "SELECT id, kind, directions, why_id, seq FROM tasks.krug WHERE kind = 'project' AND seq > %s",
    "projects": "SELECT * FROM crm.projects WHERE seq > %s",
    "deals": "SELECT * FROM crm.v_deals WHERE seq > %s",
    "payments": "SELECT * FROM crm.payments WHERE seq > %s",
    # Люди и компании — поля по тому, кто смотрит (аудит №10, dela_0026): пометки владельца, черты, «ищет / предлагает»,
    # день рождения и пометка компании команде не едут; коллегам (пользователям Дел) — только имя и должность.
    "people": "SELECT * FROM crm.v_people WHERE seq > %s",
    "orgs": "SELECT * FROM crm.v_orgs WHERE seq > %s",
    "comments": "SELECT * FROM tasks.comments WHERE seq > %s",
    "suggestions": "SELECT * FROM tasks.suggestions WHERE seq > %s",
    "access": "SELECT * FROM crm.project_access WHERE seq > %s",
    "users": "SELECT id, name, person_id, role, clients, sees_money, seq FROM crm.users WHERE seq > %s",
    # Свод — только свой (RLS): промпты, словарь, правила денег. Тексты едут, только когда поменялись.
    "svod": "SELECT owner_id, key, body, value, author, reason, updated_at, rev, seq FROM crm.svod WHERE seq > %s",
}


def sync(url: str, user: str, since: int) -> dict:
    """Всё изменившееся после since, что видит user.

    full — клиент выбрасывает кэш и берёт ответ целиком: первый синк, снятый
    доступ (что-то перестало быть видно) или новый доступ (старые дела
    открытого проекта имеют номера меньше since).
    """
    with db.session(url, user, via="sync") as conn:
        full = since <= 0 or bool(conn.execute(
            "SELECT 1 FROM crm.access_revoked WHERE user_id = %s AND seq > %s "
            "UNION ALL SELECT 1 FROM crm.project_access WHERE user_id = %s AND seq > %s LIMIT 1",
            (user, since, user, since),
        ).fetchone())
        lo = 0 if full else max(0, since - OVERLAP)
        out: dict[str, Any] = {"full": full}
        top = since
        for name, q in SYNC_TABLES.items():
            rows = conn.execute(q, (lo,)).fetchall()
            out[name] = jsonable(rows)
            top = max([top, *(max(r["seq"], r.get("krug_seq") or 0) for r in rows)])
        out["labels"] = jsonable(conn.execute("SELECT name, color FROM tasks.labels ORDER BY name").fetchall())
        # Последний дайджест Telegram (dela_0014): одной записью, а не таблицей — телефону на «Сегодня» нужна только
        # последняя сводка, список за дни — видом digest. Только владельцу; поля нет — кэш не трогать.
        put, last = digest.for_sync(conn, user, lo, full)
        if put:
            out["digest"] = jsonable(last)
            top = max(top, last["seq"] if last else 0)
        # Команды Засечки из веба (dela_0016): не по seq, а всё, что ждёт телефона, — их мало и они короткоживущие.
        # Только владельцу; не владельцу поля нет.
        cmds = zasechka.for_sync(conn, user)
        if cmds is not None:
            out["zasechka"] = jsonable(cmds)
        # Настройки телефона (dela_0015): правки с компьютера, которые ждут телефоны этого человека. Не таблицей «после
        # seq», а списком ждущих: их единицы, а взятые телефон помнит сам. Нет ждущих — поля нет.
        edits = phone.for_sync(conn, user)
        if edits:
            out["phone"] = {"edits": jsonable(edits)}
        # Траты Claude для значка на «Сегодня» (dela_0017): $ за 7 дней, сегодня и непрочитанные советы — на каждом
        # синке (это одна сумма, а значок должен жить без лишнего запроса). Только владельцу; не владельцу поля нет.
        badge = spend.badge(conn, user)
        if badge is not None:
            out["spend"] = jsonable(badge)
        # Режим отладки (debug.py): включён ли, сколько записок ждут разбора — только владельцу; не владельцу поля нет.
        if (dbg := debug.for_sync(conn, user)) is not None:
            out["debug"] = jsonable(dbg)
        out["seq"] = top
        out["today"] = conn.execute("SELECT crm.today() AS d").fetchone()["d"].isoformat()
    out["features"] = FEATURES
    return out


# ── Виды ────────────────────────────────────────────────────────────────

def _sphere(sphere: str | None) -> tuple[str, list]:
    if sphere in ("work", "home"):
        return " AND sphere IN (%s, 'inbox')", [sphere]
    return "", []


# С 05.10.2026 — как в вебе и на телефоне: без «оплаченное выше» и без раздела «оплачено, без даты».
# Деньги дела владелец убрал из интерфейса («тяжело смотреть»), а сервер их показывал дальше — Claude
# в коннекторе видел раздел, которого у владельца нет.
ORDER = " ORDER BY due_date NULLS LAST, due_time NULLS LAST, created_at"


def _list(conn, where: str, params: list, sphere: str | None = None, order: str = ORDER) -> list[dict]:
    # Узлы дерева целей (Круг 7.2) — не дела: в списках их нет, они живут в дереве и в цепочке «Зачем».
    s, sp = _sphere(sphere)
    return conn.execute(f"SELECT * FROM tasks.v_tasks WHERE ({where}) AND NOT goal{s}{order}", [*params, *sp]).fetchall()


def view_morning(conn, user, sphere=None, **_):
    open_mine = "status = 'open' AND owner_id = %s"
    return {
        "now": _list(conn, f"{open_mine} AND focus_on = crm.today()", [user], sphere),
        "today": _list(conn, f"{open_mine} AND ball = 'mine' AND due_date <= crm.today()", [user], sphere),
        "nudge": _list(conn, f"{open_mine} AND ball = 'waiting' AND (nudge_on <= crm.today() OR due_date <= crm.today())", [user], sphere),
        "from_others": _list(conn, f"{open_mine} AND created_by <> owner_id AND created_at > now() - interval '3 days'", [user], sphere),
        "new_count": conn.execute(
            "SELECT count(*) AS n FROM tasks.suggestions WHERE for_user = %s AND status = 'pending'", (user,)
        ).fetchone()["n"],
    }


# «Сейчас», «Сегодня» и просроченное (13.10.2026, владелец: «В делах давай разделим: Сейчас (то, что отметил) и
# Сегодня (с датой сегодня)»; порядок на экране — Сейчас → Новое → Сегодня). С 10.10 «Сегодня» было одним котлом:
# срок сегодня или прошёл, «Сейчас» телефона, «отбить» без срока, «мониторить», где пора напомнить, — и в нём тонуло
# то, что владелец сам взял на день. Теперь три правила, одно на сервер, веб (app.js: isNow, inToday, isLate) и
# телефон (этап 4 «Правки 5.0», задание отдельно):
# - «Сейчас» (NOW_IF) — только то, что владелец отметил сам: focus_on = сегодня, не больше NOW_MAX (_now_guard);
# - «Сегодня» (TODAY_IF) — свои открытые дела со сроком ровно сегодня: без просроченного и без «ждут» без срока;
# - просроченное (LATE_IF) само ни туда, ни туда не попадает — оно во «Всех делах» (view_all), группой сверху.
# В «Сегодня» — три группы по приоритету (GROUPS), в группе выше срок со временем, потом кто дольше ждёт.
NOW_IF = "focus_on = crm.today()"
TODAY_IF = "due_date = crm.today()"
LATE_IF = "due_date < crm.today()"
# «Ждут от тебя без срока» и «жду больше недели» (аудит 15.10.2026, №5) — числами в шапке «Сейчас», рядом с
# «просрочено N»: «Отбить» без срока и «Мониторить» без движения ни в «Сейчас», ни в «Сегодня» не попадают (решение
# 13.10), а забыть о них нельзя — 10.10 владелец назвал «Отбить» «мега важными… первым делом». Сроков не выдумываем
# (правило разбора «срок не назван — не придумывай»), только показываем. Одно правило в трёх местах: здесь, app.js
# (owedNoDue, waitingStale), телефон (DelaViews, docs/dela-phone-21.md).
OWED_NODUE_IF = "grp = 'owed' AND due_date IS NULL"
WAITING_STALE_DAYS = 7
WAITING_STALE_IF = (f"ball = 'waiting' AND waiting_since < crm.today() - {WAITING_STALE_DAYS} "
                    "AND (nudge_on IS NULL OR nudge_on < crm.today())")
OPEN_MINE = "status = 'open' AND owner_id = %s"
TODAY_WHERE = f"{OPEN_MINE} AND {TODAY_IF}"
TODAY_ORDER = " ORDER BY due_time NULLS LAST, created_at, num"


def _by_group(rows: list[dict]) -> list[dict]:
    return [{"key": k, "title": title, "items": [r for r in rows if group_of(r) == k]} for k, title in GROUPS]


def view_today(conn, user, sphere=None, **_):
    rows = _list(conn, TODAY_WHERE, [user], sphere, TODAY_ORDER)
    now = _list(conn, f"{OPEN_MINE} AND {NOW_IF}", [user])  # «Сейчас» — пять на день во всех сферах сразу
    # «Моё» (Круг жизни, 12.10.2026) — подсказка, что ещё взять: дела, которые двигают жизнь, а срока на сегодня не
    # имеют. В сам список «Сегодня» оно не входит; что уже в «Сегодня» или в «Сейчас», в «Моё» не повторяется.
    extra = krug.today_extra(conn, user, skip={r["id"] for r in rows} | {r["id"] for r in now})
    return {
        **extra,
        "groups": _by_group(rows),
        "now_count": len(now),
        "now_max": NOW_MAX,
        "overdue_count": len(_list(conn, f"{OPEN_MINE} AND {LATE_IF}", [user], sphere)),
        # №5: ждут от тебя без срока и жду больше недели — числами (цели не в счёт: _list их не берёт).
        "owed_nodue_count": len(_list(conn, f"{OPEN_MINE} AND {OWED_NODUE_IF}", [user], sphere)),
        "waiting_stale_count": len(_list(conn, f"{OPEN_MINE} AND {WAITING_STALE_IF}", [user], sphere)),
        "done_today": conn.execute(
            "SELECT count(*) AS n FROM tasks.tasks WHERE owner_id = %s AND status = 'done' "
            "AND (completed_at AT TIME ZONE 'Europe/Moscow')::date = crm.today()", (user,)).fetchone()["n"],
        "new_count": conn.execute(
            "SELECT count(*) AS n FROM tasks.suggestions WHERE for_user = %s AND status = 'pending'", (user,)
        ).fetchone()["n"],
    }


def view_all(conn, user, sphere=None, **_):
    """«Все дела»: всё своё открытое. Сверху — «Просрочено» (срок прошёл: само оно ни в «Сейчас», ни в «Сегодня» не
    попадает и живёт здесь), дальше — остальное тремя группами по сроку."""
    return {
        "overdue": _list(conn, f"{OPEN_MINE} AND {LATE_IF}", [user], sphere),
        # coalesce — не ради красоты: у дела без срока условие даёт NULL, и NOT (NULL) потерял бы его.
        "groups": _by_group(_list(conn, f"{OPEN_MINE} AND NOT coalesce({LATE_IF}, false)", [user], sphere)),
    }


def view_new(conn, user, **_):
    # Пачки — по дате встречи, свежие сверху; у «закрыть» и «поправить» — номер и название дела.
    rows = conn.execute(
        "SELECT s.*, t.num AS task_num, t.title AS task_title FROM tasks.suggestions s "
        "LEFT JOIN tasks.tasks t ON t.id = s.task_id "
        "WHERE s.for_user = %s AND s.status = 'pending' "
        "ORDER BY max(coalesce(s.payload ->> 'meeting_at', s.created_at::date::text)) "
        "         OVER (PARTITION BY coalesce(s.batch_ref, s.id::text)) DESC, s.batch_ref, s.created_at",
        (user,),
    ).fetchall()
    batches: dict[str, dict] = {}
    for r in rows:
        key = r["batch_ref"] or f"one:{r['id']}"
        b = batches.setdefault(key, {"batch_ref": r["batch_ref"], "title": r["batch_title"], "items": []})
        b["items"].append(r)
    return {"batches": list(batches.values()), "count": len(rows)}


DICTATIONS_SHOWN = 10


def view_dictations(conn, user, **_):
    """Последние наговорки и что из них вышло: дела любого статуса, заметки в хронологию и что
    потом про эти дела сказала автоматика (встреча, Telegram) — «сращивание» (06.10.2026).

    Наговорка без строки в tasks.dictations (телефон старый или текст не дошёл) — всё равно видна:
    по source_ref её дел, только без слов.
    """
    heads = conn.execute(
        "SELECT id AS ref, at, text, source FROM tasks.dictations WHERE owner_id = %s "
        "UNION ALL "
        "SELECT t.source_ref, min(t.created_at), NULL, CASE WHEN bool_or(t.source = 'voice') THEN 'phone' ELSE 'web' END "
        "FROM tasks.tasks t WHERE t.created_by = %s AND t.source_ref ~ '^(raznoska|parse):[^:]+$' "
        "AND NOT EXISTS (SELECT 1 FROM tasks.dictations x WHERE x.id = t.source_ref) GROUP BY t.source_ref "
        "ORDER BY at DESC LIMIT %s", (user, user, DICTATIONS_SHOWN)).fetchall()
    refs = [h["ref"] for h in heads]
    if not refs:
        return {"items": []}
    tasks = conn.execute("SELECT * FROM tasks.v_tasks WHERE source_ref = ANY(%s) ORDER BY created_at, num", (refs,)).fetchall()
    notes = conn.execute(
        "SELECT id, at, summary, project_id, person_ids, source_ref FROM crm.interactions WHERE deleted_at IS NULL "
        "AND split_part(source_ref, ':', 1) || ':' || split_part(source_ref, ':', 2) = ANY(%s) ORDER BY source_ref",
        (refs,)).fetchall()
    touches = conn.execute(
        "SELECT id, task_id, kind, status, source, batch_title, quote, payload, created_at, decided_at, reason "
        "FROM tasks.suggestions WHERE task_id = ANY(%s) AND kind IN ('update', 'close') ORDER BY created_at",
        ([t["id"] for t in tasks],)).fetchall() if tasks else []
    out = []
    for h in heads:
        mine = [t for t in tasks if t["source_ref"] == h["ref"]]
        ids = {t["id"] for t in mine}
        out.append({**h, "tasks": mine,
                    "notes": [n for n in notes if ":".join(str(n["source_ref"]).split(":")[:2]) == h["ref"]],
                    "touches": [s for s in touches if s["task_id"] in ids]})
    return {"items": out}


def view_waiting(conn, user, sphere=None, **_):
    rows = _list(conn, "status = 'open' AND owner_id = %s AND ball = 'waiting'", [user], sphere,
                 " ORDER BY person_short NULLS LAST, waiting_since NULLS LAST")
    people: dict[str, dict] = {}
    for r in rows:
        key = str(r["person_id"]) if r["person_id"] else "-"
        p = people.setdefault(key, {"person_id": r["person_id"], "person": r["person_short"] or "без человека", "items": []})
        p["items"].append(r)
    return {"people": list(people.values())}


def view_person(conn, user, person_id=None, **_):
    if not person_id:
        raise OpError("нужен person_id")
    open_ = "status = 'open' AND "
    return {
        "person": conn.execute("SELECT * FROM crm.v_people WHERE id = %s", (person_id,)).fetchone(),  # аудит №10
        "agenda": _list(conn, open_ + "ball = 'agenda' AND person_id = %s", [person_id]),
        "waiting": _list(conn, open_ + "ball = 'waiting' AND person_id = %s", [person_id]),
        "asked": _list(conn, open_ + "requested_by = %s", [person_id]),
        "mine_about": _list(conn, open_ + "ball = 'mine' AND person_id = %s", [person_id]),
    }


def view_quick(conn, user, sphere=None, **_):
    return {"items": _list(conn, "status = 'open' AND owner_id = %s AND ball = 'mine' AND estimate_min <= 10", [user], sphere)}


def view_now(conn, user, sphere=None, **_):
    """«Сейчас» — только то, что владелец отметил сам на сегодня (focus_on), не больше NOW_MAX. Срок тут ни при чём:
    дело со сроком сегодня, но без отметки, — в «Сегодня»."""
    return {"items": _list(conn, f"{OPEN_MINE} AND {NOW_IF}", [user], sphere), "max": NOW_MAX}


def view_project(conn, user, project_id=None, **_):
    if not project_id:
        raise OpError("нужен project_id")
    project = conn.execute("SELECT * FROM crm.projects WHERE id = %s", (project_id,)).fetchone()
    if not project:
        raise OpError("нет такого проекта или он не виден")
    hours = None
    if project["owner_id"] == user:
        hours = conn.execute("SELECT * FROM crm.project_minutes(%s)", (project_id,)).fetchall()
    return {
        "project": project,
        "deals": conn.execute("SELECT * FROM crm.v_deals WHERE project_id = %s ORDER BY stage = 'archive', name", (project_id,)).fetchall(),
        "open": _list(conn, "status = 'open' AND project_id = %s", [project_id]),
        "done": _list(conn, "status <> 'open' AND project_id = %s", [project_id], order=" ORDER BY completed_at DESC LIMIT 30"),
        "minutes": hours,
    }


def view_week(conn, user, sphere=None, **_):
    open_mine = "status = 'open' AND owner_id = %s"
    s, sp = _sphere(sphere)
    return {
        "stale": _list(conn, f"{open_mine} AND (due_date < crm.today() - 14 OR updated_at < now() - interval '21 days')", [user], sphere),
        "waiting_stale": _list(conn, f"{open_mine} AND {WAITING_STALE_IF}", [user], sphere),
        "no_next_step": conn.execute(
            "SELECT p.* FROM crm.projects p WHERE p.archived_at IS NULL AND p.owner_id = %s "
            "AND p.money_default IN ('paid', 'potential') AND NOT EXISTS (SELECT 1 FROM tasks.tasks t "
            "WHERE t.project_id = p.id AND t.status = 'open' AND t.ball = 'mine') ORDER BY p.money_default, p.name",
            (user,),
        ).fetchall(),
        "expired": conn.execute(
            "SELECT * FROM tasks.suggestions WHERE for_user = %s AND status = 'expired' AND decided_at > now() - interval '7 days'",
            (user,),
        ).fetchall(),
        "automation": automation(conn, user),
        "sphere": s and sp[0],
    }


# ── Счётчик откатов автоматики (аудит 15.10.2026, №29) ──────────────────────
# Промпты обещают модели «ошибку вернёт одной кнопкой», а сколько раз владелец отменил, открыл снова или вернул
# сделанное само, никто не считал: через месяц на вопрос «не слишком ли смело автоматика ставит и закрывает» не было бы
# ответа. Считаем за неделю по источникам — из «Нового» (tasks.suggestions) и того, что с его делами и людьми сейчас,
# без журнала crm.history. Уроки в Свод и кнопка «Не дело» (пп. 2–4 находки) — потом, когда счётчик покажет откаты.
AUTO_DAYS_WEEK = 7
AUTO_SOURCES = ("meeting", "telegram", "contacts", "krug")  # userbot — тот же Telegram; остальное — «other»
AUTO_NO_TOP = 3


def _auto_bucket() -> dict:
    return {"created": 0, "created_undone": 0, "closed": 0, "closed_undone": 0, "updated": 0, "updated_undone": 0,
            "people": 0, "people_undone": 0, "asked": 0, "yes": 0, "edited": 0, "no": 0, "expired": 0, "no_reasons": {}}


def automation(conn, user, days: int = AUTO_DAYS_WEEK) -> dict:
    """Что автоматика сделала сама за days дней и сколько из этого владелец вернул, и как он ответил на её вопросы.

    - поставлено само (`заведено само`) / из них отменено — дело теперь cancelled;
    - закрыто само / открыто снова — дело снова open («Вернуть»);
    - уточнено само / «Вернуть» — комментарий-основание (result.comment_id) убран: «Вернуть» веба и телефона убирает его
      вместе с полями (у уточнения самого основание есть всегда — без цитаты само не встаёт);
    - людей заведено само / в архиве — карточка из result.person_id убрана («Вернуть» или слита); дописанное само в
      живую карточку (result.created = false) — не новый человек, не считается;
    - вопросы (решил человек или погасли): да, с правкой, нет (три частые причины), погасло. Решённое сервером без
      человека («уже в CRM», «уже убран») — не вопрос.
    Ответ — {days, all: {…}, sources: {meeting: {…}, telegram: {…}, …}} (только источники, где что-то было)."""
    rows = conn.execute(
        "SELECT s.kind, s.status, s.reason, s.decided_by, s.source, rt.status AS made_status, t.status AS task_status, "
        "c.deleted_at IS NOT NULL AS comment_gone, pe.archived_at IS NOT NULL AS person_gone, "
        "CASE WHEN s.kind = 'person' THEN coalesce((s.result ->> 'created')::boolean, true) ELSE true END AS person_made "
        "FROM tasks.suggestions s "
        "LEFT JOIN tasks.tasks rt ON rt.id = s.result_task_id "
        "LEFT JOIN tasks.tasks t ON t.id = s.task_id "
        "LEFT JOIN tasks.comments c ON c.id::text = s.result ->> 'comment_id' "
        "LEFT JOIN crm.people pe ON pe.id::text = s.result ->> 'person_id' "
        "WHERE s.for_user = %s AND s.status <> 'pending' AND s.decided_at > now() - make_interval(days => %s)",
        (user, days)).fetchall()
    out = {"days": days, "all": _auto_bucket(), "sources": {}}
    for r in rows:
        src = "telegram" if r["source"] == "userbot" else r["source"] if r["source"] in AUTO_SOURCES else "other"
        b2 = out["sources"].setdefault(src, _auto_bucket())
        for b in (out["all"], b2):
            if r["status"] == "accepted" and r["reason"] == AUTO_CREATE_REASON:
                b["created"] += 1
                b["created_undone"] += r["made_status"] == "cancelled"
            elif r["status"] == "accepted" and r["reason"] == AUTO_CLOSE_REASON:
                b["closed"] += 1
                b["closed_undone"] += r["task_status"] == "open"
            elif r["status"] == "accepted" and r["reason"] == AUTO_UPDATE_REASON:
                b["updated"] += 1
                b["updated_undone"] += bool(r["comment_gone"])
            elif r["status"] == "accepted" and r["reason"] == AUTO_PERSON_REASON:
                # created: false — дописано в живую карточку (ключ нашёлся у неё), человека не заводили: не считаем.
                if r["person_made"]:
                    b["people"] += 1
                    b["people_undone"] += bool(r["person_gone"])
            elif r["status"] == "expired":
                b["asked"] += 1
                b["expired"] += 1
            elif r["decided_by"] is not None:
                b["asked"] += 1
                key = {"accepted": "yes", "edited": "edited", "rejected": "no"}.get(r["status"])
                if key:
                    b[key] += 1
                if key == "no":
                    why = " ".join(str(r["reason"] or "").split()) or "без причины"
                    b["no_reasons"][why] = b["no_reasons"].get(why, 0) + 1
    for b in [out["all"], *out["sources"].values()]:
        b["no_reasons"] = [{"reason": k, "n": n} for k, n in
                           sorted(b["no_reasons"].items(), key=lambda kv: (-kv[1], kv[0]))[:AUTO_NO_TOP]]
    return out


def view_search(conn, user, q=None, status="open", **_):
    if not q:
        raise OpError("нужен текст поиска")
    st = "" if status == "all" else " AND status = 'open'"
    rows = conn.execute(
        "SELECT *, ts_rank(search, websearch_to_tsquery('russian', %s)) AS rank FROM tasks.v_tasks "
        f"WHERE (search @@ websearch_to_tsquery('russian', %s) OR lower(title) LIKE '%%' || lower(%s) || '%%'){st} "
        "ORDER BY rank DESC, updated_at DESC LIMIT 50",
        (q, q, q),
    ).fetchall()
    return {"items": rows}


VIEWS: dict[str, Callable] = {
    "svod": people.view_svod,
    "who": people.view_who,
    "today": view_today,
    "all": view_all,
    # «Дальше» (10.10–13.10.2026: всё, что не на сегодня) — старое имя «Всех дел»: коннектор Claude мог его запомнить.
    "later": view_all,
    "morning": view_morning,
    "new": view_new,
    "dictations": view_dictations,
    "waiting": view_waiting,
    "person": view_person,
    "quick": view_quick,
    "now": view_now,
    "project": view_project,
    "week": view_week,
    "search": view_search,
}

# «Где мы»: операции и виды точек семьи — свой модуль (where.py), как люди и статистика.
where.register(HANDLERS, VIEWS, OpError)
# Круг жизни: вид krug — направления, движение, дерево «Зачем» (krug.py).
krug.register(VIEWS)
# Встречи (dela_0010): приём от службы встреч, правка владельцем, список и карточка (meetings.py).
meetings.register(HANDLERS, VIEWS, OpError)
# Дайджест Telegram (dela_0014): приём от службы дайджеста и выдача владельцу (digest.py).
digest.register(HANDLERS, VIEWS, OpError)
# Страницы-двойники экранов телефона (dela_0016): команды Засечки телефону и виды day, zasechka, pravka, pogoda.
zasechka.register(HANDLERS, OpError)
twins.register(VIEWS, OpError)
# Настройки телефона (dela_0015): снимок от телефона, правка с компьютера, вид phone (phone.py).
phone.register(HANDLERS, VIEWS, OpError)
# Книга трат Claude (dela_0017): итоги дней от телефона и сборщика «Дома», «советы прочитаны», вид spend (spend.py).
spend.register(HANDLERS, VIEWS, OpError)
# «Только тебе» (dela_0027, аудит №8–11; место этапа 6): наблюдения только владельцу — insight.*, вид insights.
insights.register(HANDLERS, VIEWS, OpError)
# Защита от петли Claude (аудит №12, guard.py): «Продолжать» / «Оставить» и «сообщение отправлено».
guard.register(HANDLERS, VIEWS, OpError)
# Контекст по делу (аудит №13): вид context_index — связанные люди и ключи имён (focus.py).
focus.register(VIEWS)
# Режим отладки (dela_0031, 15.10.2026): записки о багах с экраном данными — debug.add / debug.set, вид debug (debug.py).
from . import debug  # noqa: E402  (свой модуль; импорт здесь — крючок одной строкой, ветку собирают в поезд с другими)
debug.register(HANDLERS, VIEWS, FEATURES, OpError)


def view(url: str, user: str, name: str, **params) -> dict:
    from . import crm, stats  # noqa: F401  CRM-виды и статистика (свои модули) встают в VIEWS при импорте
    fn = VIEWS.get(name)
    if fn is None:
        raise OpError(f"нет такого вида: {name}")
    with db.session(url, user, via="view") as conn:
        return jsonable(fn(conn, user, **params))


def expire_suggestions(url: str) -> int:
    """Неразобранное за неделю гаснет: решение «не важно» тоже решение, и оно в журнале."""
    with db.session(url, "system", "svc:expire") as conn:
        return conn.execute(
            "UPDATE tasks.suggestions SET status = 'expired', decided_at = now() "
            "WHERE status = 'pending' AND expires_at < now()"
        ).rowcount
