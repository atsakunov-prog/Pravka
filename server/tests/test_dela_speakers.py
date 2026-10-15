"""Люди встречи (этап 6 аудита, 15.10.2026): модель предлагает, решает код; учится только «да» владельца.

Саша: «Два независимых признака сошлись — привязать и показать в „Сделано само“; один — один вопрос с основаниями и
кнопками; признаки спорят — вопрос». «Обучение после подтверждения: email — в карточку, … подпись — в aliases,
addressed_as — в карточку». «Одна правда: людей встречи определяет разбор + сверка кодом; промпт названия Дел берёт
готовый список, не угадывает заново». Признаки собирает служба встреч; здесь — правило сервера: привязка само, вопрос из
кандидатов службы, обучение карточки «не чужим», люди встречи без угадывания. Люди и компании — выдуманные.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from starlette.testclient import TestClient

from pravka_dela import api, meetings, meetings_auto, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import dela, project  # noqa: F401  (фикстура и помощник)


def ops(url, user, *items):
    return store.apply_ops(url, user, list(items), "app")["results"]


def one(url, item, user="sasha"):
    r = ops(url, user, item)[0]
    assert r["ok"], r
    return r


def card(url, pid):
    with dela_db.session(url, "sasha", "t") as c:
        return c.execute("SELECT p.*, o.name AS org_name FROM crm.people p LEFT JOIN crm.orgs o ON o.id = p.org_id "
                         "WHERE p.id = %s", (pid,)).fetchone()


@pytest.fixture()
def sp(dela):
    """Компании «Альфа Банк» (у карточки Ольги) и «Бета» (клиент встречи), команда со своим доменом почты, владелец."""
    with dela_db.session(dela, "sasha", "t") as c:
        org = {n: c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES (%s, 'other', 'sasha') RETURNING id",
                            (n,)).fetchone()["id"] for n in ("Альфа Банк", "Бета", "ЗФ Тест")}
        mk = lambda name, **kw: c.execute(  # noqa: E731
            f"INSERT INTO crm.people (name, owner_id{''.join(', ' + k for k in kw)}) VALUES (%s, 'sasha'"
            f"{', %s' * len(kw)}) RETURNING id", (name, *kw.values())).fetchone()["id"]
        ids = {
            "olga": mk("Ольга Тестова", short="Ольга", org_id=org["Альфа Банк"]),
            "denis": mk("Денис Образцов", short="Денис", org_id=org["Бета"], emails=["denis@beta-test.ru"]),
            "timur": mk("Тимур Беталов", org_id=org["Бета"], role="генеральный директор"),
            "me": mk("Александр Владов", short="Саша", org_id=org["ЗФ Тест"]),
            "nata": mk("Наталья Командная", user_id="natasha", emails=["nata@zf-test.ru"]),
            "pavel": mk("Павел Орлов", org_id=org["Бета"]),
        }
    with dela_db.session(dela, "system", "t") as c:
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'sasha'", (ids["me"],))
    return {"url": dela, "org": org, **ids}


def link_sug(pid, signs, key="meeting-speaker:101:SPEAKER_02", auto=True, others=(), learn=None, **ident):
    """Привязка службы встреч по контракту dela-meetings.json, раздел speakers: карточка, признаки по группам."""
    return {"op": "suggestion.create", "suggestion": {
        "kind": "person", "source": "meeting", "source_ref": key, "batch_ref": "meeting:101",
        "batch_title": "Бета: аудит · 14.10", "quote": "Встреча «Бета: аудит» 14.10: SPEAKER_02 — Денис О. (голос + разговор)",
        "payload": {"title": "SPEAKER_02", "link": True, "person_id": str(pid),
                    "speaker": {"meeting_ref": "101", "label": "SPEAKER_02", "seconds": 300,
                                "url": "https://meetings.example.org/m/101",
                                "clip_url": "https://meetings.example.org/m/101/clip/SPEAKER_02"},
                    "ident": {"candidates": [{"person_id": str(pid), "signs": signs}, *others], **ident,
                              **({"learn": learn} if learn else {})},
                    **({"auto": True} if auto else {})}}}


def ask_sug(cands, key="meeting-speaker:101:SPEAKER_03", seconds=130, learn=None, **ident):
    """Вопрос службы встреч: кандидаты со своими признаками (ident), без привязки."""
    return {"op": "suggestion.create", "suggestion": {
        "kind": "person", "source": "meeting", "source_ref": key, "batch_ref": "meeting:101",
        "batch_title": "Бета: аудит · 14.10", "quote": "Встреча «Бета: аудит» 14.10: SPEAKER_03",
        "payload": {"speaker": {"meeting_ref": "101", "label": "SPEAKER_03", "seconds": seconds,
                                "url": "https://meetings.example.org/m/101",
                                "clip_url": "https://meetings.example.org/m/101/clip/SPEAKER_03"},
                    "ident": {"candidates": cands, **ident, **({"learn": learn} if learn else {})}}}}


VOICE = {"group": "voice", "strong": True, "text": "голос 0,91 · связан тобой 06.10"}
OWNER = {"group": "owner", "strong": True, "text": "голос назвал ты 06.10"}
SPEECH = {"group": "speech", "strong": True, "text": "обращение [3:45] «Денис, …»"}


def test_two_signs_link_by_itself_teach_nothing_and_undo(sp):
    """Голос + твоё слово — два признака разных групп: привязка встаёт сама («Сделано само» с «Вернуть»), карточку она не
    учит (ни имя, ни почту — только ответ владельца). «Вернуть» — отметка у предложения: её служба прочтёт из синка."""
    url = sp["url"]
    r = one(url, link_sug(sp["denis"], [VOICE, OWNER], learn={str(sp["denis"]): {"address": "ты, Денис"}}))
    s = r["suggestion"]
    assert (s["status"], s["reason"]) == ("accepted", store.AUTO_LINK_REASON), s
    assert s["result"] == {"person_id": str(sp["denis"]), "created": False, "changed": False, "link": True,
                           "by_owner": False}                                # решил сервер, не владелец
    p = card(url, sp["denis"])
    assert (p["aliases"], p["address"]) == ([], None)  # «Сделано само» не учит
    assert s["payload"]["ident"]["candidates"][0]["signs"][0]["text"] == VOICE["text"]
    week = store.view(url, "sasha", "week")["automation"]["all"]
    assert (week["linked"], week["linked_undone"], week["asked"]) == (1, 0, 0)
    # «Вернуть» — undone и «Понятно»; повтор — то же; другое «Сделано само» так не вернуть.
    seq = store.sync(url, "sasha", 0)["seq"]
    u = one(url, {"op": "suggestion.undo", "id": s["id"]})["suggestion"]
    assert u["result"]["undone"] is True and u["result"]["undone_by"] == "sasha" and u["seen_at"]
    # Служба встреч узнаёт о «Вернуть» из синка: предложение приходит заново с result.undone.
    again = [x for x in store.sync(url, "sasha", seq)["suggestions"] if x["id"] == s["id"]]
    assert again and again[0]["result"]["undone"] is True
    assert one(url, {"op": "suggestion.undo", "id": s["id"]})["suggestion"]["result"]["undone_at"] == u["result"]["undone_at"]
    assert store.view(url, "sasha", "week")["automation"]["all"]["linked_undone"] == 1
    other = one(url, {"op": "suggestion.create", "suggestion": {
        "kind": "person", "source": "meeting", "source_ref": "meeting-person:глеб тихонов", "batch_ref": "meeting:101",
        "quote": "Встреча: «Глеб Тихонов»", "payload": {"title": "Глеб Тихонов", "person": {"name": "Глеб Тихонов"},
                                                         "auto": True}}})["suggestion"]
    assert other["reason"] == store.AUTO_PERSON_REASON
    bad = ops(url, "sasha", {"op": "suggestion.undo", "id": other["id"]})[0]
    assert not bad["ok"] and "привязку спикера" in bad["error"]
    # Повтор той же находки — прежнее предложение (служба после «Вернуть» спрашивает новым кругом source_ref).
    assert one(url, link_sug(sp["denis"], [VOICE, OWNER]))["suggestion"]["id"] == s["id"]
    assert {"speaker_link", "people_final"} <= set(store.FEATURES)


def test_one_sign_is_a_question_with_reasons_buttons_and_learning(sp):
    """Один сильный признак — вопрос «Это X?» с основаниями у кнопки и pick; «да» владельца учит карточку: почта учётки,
    обращение, подпись Zoom полным именем."""
    url = sp["url"]
    learn = {str(sp["denis"]): {"emails": ["Denis.O@beta-test.ru"], "address": "вы, Денис Ильич",
                                "zoom_name": "Denis Obraztsov"}}
    s = one(url, link_sug(sp["denis"], [VOICE, {"group": "speech", "strong": False, "text": "слабое"}], learn=learn,
                          why="Голос 0,91, а к нему обращаются «Денис Ильич» [3:45]."))["suggestion"]
    p = s["payload"]
    assert s["status"] == "pending" and "auto" not in p and "link" not in p and "person_id" not in p, s
    assert p["ask"] == ("SPEAKER_02 (5 мин) на встрече «Бета: аудит» 14.10 — это Денис Образцов (Бета)? "
                        "Голос 0,91, а к нему обращаются «Денис Ильич» [3:45].")
    assert p["options"] == [
        {"label": "Это Денис Образцов", "person_id": str(sp["denis"]), "why": "голос 0,91 · связан тобой 06.10; слабое"},
        {"label": "Нет, не он", "reject": True, "reason": "не он"},
        {"label": "Не для CRM", "reject": True, "reason": "не для CRM"}]
    assert p["pick"] is True and p["held"] == "один сильный признак: голос"
    assert p["speaker"]["clip_url"].endswith("/clip/SPEAKER_02")
    # «Да» без кнопки — отказ; «Это Денис Образцов» — он, и карточка учится.
    assert not ops(url, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]["ok"]
    r = one(url, {"op": "suggestion.decide", "id": s["id"], "option": 0})
    res = r["suggestion"]["result"]
    assert res["person_id"] == str(sp["denis"]) and not res["created"] and res["by_owner"] is True
    assert res["learned"]["wrote"] == {"emails": ["denis.o@beta-test.ru"], "address": "вы, Денис Ильич",
                                       "aliases": ["Denis Obraztsov"]}, res
    p = card(url, sp["denis"])
    assert p["emails"] == ["denis@beta-test.ru", "denis.o@beta-test.ru"] and p["address"] == "вы, Денис Ильич"
    assert p["aliases"] == ["Denis Obraztsov"]  # подпись Zoom, а не имя, услышанное на встрече
    # Ответ человека — вопрос, а не «Сделано само».
    week = store.view(url, "sasha", "week")["automation"]["all"]
    assert (week["linked"], week["asked"], week["yes"]) == (0, 1, 1)


def test_dispute_of_voice_guess_and_speech_is_a_question_p6(sp):
    """п. 6 этапа 6 на выдуманных именах: голос связан с «Ольгой (Альфа Банк)» догадкой, а по разговору это Ольга из
    «Беты» (клиент встречи). Спор — вопрос всегда; «Завести «Ольга (Бета)»» — новая карточка в «Бете», обращение — ей."""
    url = sp["url"]
    guess = {"group": "voice", "strong": False, "text": "голос 0,75 — связан догадкой «кто это» 08.10"}
    s = one(url, ask_sug([{"person_id": str(sp["olga"]), "signs": [guess]}], name="Ольга", company="Бета",
                         dispute=True, why="Голос связан с «Ольгой (Альфа Банк)» догадкой; по разговору — из «Беты»: "
                                           "[3:21] «Ольга с нашей стороны тоже стучится».",
                         learn={"new": {"address": "ты, Оля"}}))["suggestion"]
    p = s["payload"]
    assert s["status"] == "pending" and p["held"] == "признаки спорят", s
    assert p["ask"].startswith("SPEAKER_03 (2 мин) на встрече «Бета: аудит» 14.10 — это Ольга Тестова (Альфа Банк)? "
                               "Голос связан с «Ольгой (Альфа Банк)» догадкой")
    assert [o["label"] for o in p["options"]] == ["Это Ольга Тестова", "Завести «Ольга (Бета)»", "Не для CRM"]
    assert p["pick"] is True
    r = one(url, {"op": "suggestion.decide", "id": s["id"], "option": 1})
    res = r["suggestion"]["result"]
    assert res["created"] and res["person_id"] != str(sp["olga"]), res
    new = card(url, res["person_id"])
    assert (new["name"], new["org_name"], new["address"]) == ("Ольга", "Бета", "ты, Оля")
    assert res["learned"]["wrote"]["address"] == "ты, Оля"
    old = card(url, sp["olga"])
    assert (old["aliases"], old["address"]) == ([], None)  # карточку из «Альфа Банка» не тронули


def test_two_signs_but_rival_or_dead_card_is_a_question(sp):
    """Два сильных признака у одной карточки, а у другой — хоть слабый: спор, вопрос с двумя кандидатами. Приглашение с
    учёткой Zoom — не два признака (это одна учётка). Карточка привязки в архиве — вопроса без кандидата не бывает: если
    имени нет — отказ словами."""
    url = sp["url"]
    rival = {"person_id": str(sp["pavel"]), "signs": [{"group": "account", "strong": False, "text": "подпись «Pavel O»"}]}
    s = one(url, link_sug(sp["timur"], [VOICE, SPEECH], others=[rival]))["suggestion"]
    p = s["payload"]
    assert s["status"] == "pending" and p["held"].startswith("признаки спорят: Тимур Беталов (Бета), Павел Орлов (Бета)")
    assert [o["label"] for o in p["options"]] == ["Это Тимур Беталов", "Это Павел Орлов", "Никто из них", "Не для CRM"]
    s = one(url, link_sug(sp["timur"], [{"group": "account", "strong": True}, {"group": "invite", "strong": True}],
                          key="meeting-speaker:101:SPEAKER_05"))["suggestion"]
    assert s["status"] == "pending" and s["payload"]["pick"]
    assert s["payload"]["held"] == "без голоса и разговора само не ставится: приглашение, учётка Zoom"
    # Учётка и твоё слово о её подписи — та же учётка (проверка 15.10): без голоса и разговора само не ставится.
    s = one(url, link_sug(sp["timur"], [{"group": "account", "strong": True}, {"group": "owner", "strong": True}],
                          key="meeting-speaker:101:SPEAKER_08"))["suggestion"]
    assert s["status"] == "pending" and s["payload"]["held"].startswith("без голоса и разговора")
    # Соперник только с подсказкой (подпись-инициал чужой учётки) привязке само не мешает — как у службы встреч.
    hint = {"person_id": str(sp["pavel"]), "signs": [{"group": "account", "strong": False, "hint": True,
                                                      "text": "подпись «P. Orlov»"}]}
    s = one(url, link_sug(sp["timur"], [VOICE, SPEECH], others=[hint], key="meeting-speaker:101:SPEAKER_09"))["suggestion"]
    assert (s["status"], s["reason"]) == ("accepted", store.AUTO_LINK_REASON), s
    assert s["payload"]["ident"]["candidates"][1]["signs"][0]["hint"] is True
    with dela_db.session(url, "sasha", "t") as c:
        c.execute("UPDATE crm.people SET archived_at = now() WHERE id = %s", (sp["pavel"],))
    r = ops(url, "sasha", link_sug(sp["pavel"], [VOICE, OWNER], key="meeting-speaker:101:SPEAKER_06"))[0]
    assert not r["ok"] and "спрашивать не о чем" in r["error"], r
    s = one(url, link_sug(sp["pavel"], [VOICE, OWNER], key="meeting-speaker:101:SPEAKER_07", name="Павел"))["suggestion"]
    assert s["status"] == "pending" and s["payload"]["held"] == "карточка привязки в архиве или не видна"
    assert [o["label"] for o in s["payload"]["options"]] == ["Завести «Павел»", "Не для CRM"]


def test_learn_is_not_foreign(sp):
    """Обучение «не чужое»: почта другой карточки, общий ящик, почта команды у клиента; обращение — только в пустое и не
    своим; подпись — полным именем, не инициалом, не шумом и не общей учётки. Только после «да» владельца."""
    url = sp["url"]
    with dela_db.session(url, "sasha", "t") as c:
        c.execute("UPDATE crm.people SET aliases = '{Pavel Orlov-Office}' WHERE id = %s", (sp["pavel"],))
    r = one(url, {"op": "person.learn", "id": str(sp["timur"]),
                  "emails": ["denis@beta-test.ru", "info@beta-test.ru", "ivan@zf-test.ru", "not-an-email", "timur@beta-test.ru"],
                  "address": "вы, Тимур Ильдарович", "zoom_name": "Timur B"})
    why = {(x["field"], x["value"]): x["why"] for x in r["refused"]}
    assert why == {
        ("emails", "denis@beta-test.ru"): "почта карточки «Денис Образцов»",
        ("emails", "info@beta-test.ru"): "общий ящик, не человек",
        ("emails", "ivan@zf-test.ru"): "почта команды",
        ("emails", "not-an-email"): "не адрес почты",
        ("zoom_name", "Timur B"): "подпись из одного слова или с инициалом — по ней одной человека не узнать"}, why
    assert r["wrote"] == {"emails": ["timur@beta-test.ru"], "address": "вы, Тимур Ильдарович", "aliases": []}
    # Обращение уже есть — меняет владелец; подпись общей учётки и шум — мимо, полное имя — другим именем.
    r = one(url, {"op": "person.learn", "id": str(sp["timur"]), "address": "ты, Тимур", "zoom_name": "Pavel Orlov-Office"})
    assert [x["why"] for x in r["refused"]] == ["общая подпись: так подписана и карточка «Павел Орлов»",
                                                "уже записано «вы, Тимур Ильдарович» — меняет владелец"]
    r = one(url, {"op": "person.learn", "id": str(sp["timur"]), "zoom_name": "iPhone (2)"})
    assert r["refused"][0]["why"].startswith("не имя человека")
    r = one(url, {"op": "person.learn", "id": str(sp["timur"]), "zoom_name": "Timur Betalov"})
    assert r["wrote"]["aliases"] == ["Timur Betalov"] and not r["refused"]
    # Подпись чужой учётки (проверка 15.10, 211): с учётки «Pavel Orlov» говорил Тимур — подтвердили Тимура, но подпись
    # не его имя; другим именем она вела бы к нему каждого с этой учётки.
    r = one(url, {"op": "person.learn", "id": str(sp["timur"]), "zoom_name": "Pavel Orlov"})
    assert [x["why"] for x in r["refused"]] == ["подпись чужой учётки: имя в ней — не его"] and not r["wrote"]["aliases"]
    r = one(url, {"op": "person.learn", "id": str(sp["timur"]), "zoom_name": "Timur Petrov"})       # фамилия другая
    assert [x["why"] for x in r["refused"]] == ["подпись чужой учётки: имя в ней — не его"]
    # Своим — обращение не пишется: команда, владелец.
    for who, word in (("nata", "команда"), ("me", "владелец")):
        r = one(url, {"op": "person.learn", "id": str(sp[who]), "address": "ты, коллега"})
        assert r["refused"][0]["why"] == f"{word} — обращение не записываем", (who, r)
    # Почта другой карточки и через person.add — не дописывается (refused), своя — да.
    r = one(url, {"op": "person.add", "id": str(sp["pavel"]), "add": {"emails": ["denis@beta-test.ru", "pavel@beta-test.ru"]}})
    assert card(url, sp["pavel"])["emails"] == ["pavel@beta-test.ru"]
    assert r["refused"] == [{"field": "emails", "value": "denis@beta-test.ru", "why": "почта карточки «Денис Образцов»",
                             "person_id": str(sp["denis"]), "name": "Денис Образцов"}]


def test_learn_only_after_owner_yes(sp):
    """suggestion_id — на какой ответ опирается обучение: «Сделано само» и ответ о другой карточке не учат."""
    url = sp["url"]
    auto = one(url, link_sug(sp["denis"], [VOICE, OWNER]))["suggestion"]
    r = ops(url, "sasha", {"op": "person.learn", "id": str(sp["denis"]), "suggestion_id": auto["id"], "address": "ты"})[0]
    assert not r["ok"] and "только после «да» владельца" in r["error"], r
    q = one(url, ask_sug([{"person_id": str(sp["timur"]), "signs": [SPEECH]}]))["suggestion"]
    one(url, {"op": "suggestion.decide", "id": q["id"], "option": 0})
    r = ops(url, "sasha", {"op": "person.learn", "id": str(sp["denis"]), "suggestion_id": q["id"], "address": "ты"})[0]
    assert not r["ok"] and "о другой карточке" in r["error"], r
    r = one(url, {"op": "person.learn", "id": str(sp["timur"]), "suggestion_id": q["id"], "address": "вы, Тимур"})
    assert r["wrote"]["address"] == "вы, Тимур"
    # Слитая карточка учится живой; в архиве — отказ словами.
    with dela_db.session(url, "sasha", "t") as c:
        dup = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Денис О.', 'sasha') RETURNING id").fetchone()["id"]
    one(url, {"op": "person.merge", "id": str(dup), "into": str(sp["denis"])})
    r = one(url, {"op": "person.learn", "id": str(dup), "address": "ты, Денис"})
    assert str(r["row"]["id"]) == str(sp["denis"]) and r["wrote"]["address"] == "ты, Денис"
    with dela_db.session(url, "sasha", "t") as c:
        c.execute("UPDATE crm.people SET archived_at = now() WHERE id = %s", (sp["pavel"],))
    r = ops(url, "sasha", {"op": "person.learn", "id": str(sp["pavel"]), "address": "ты"})[0]
    assert not r["ok"] and "в архиве" in r["error"]


def test_address_field_set_sync_and_merge(sp):
    """Обращение — поле карточки: владелец правит его person.set, синк отдаёт, слияние берёт пустое у живой из дубля."""
    url = sp["url"]
    one(url, {"op": "person.set", "id": str(sp["olga"]), "set": {"address": "вы, Ольга Сергеевна"}})
    assert next(p for p in store.sync(url, "sasha", 0)["people"] if p["id"] == str(sp["olga"]))["address"] == "вы, Ольга Сергеевна"
    with dela_db.session(url, "sasha", "t") as c:
        dup = c.execute("INSERT INTO crm.people (name, owner_id, address) VALUES ('Тимур Б.', 'sasha', 'вы, Тимур') "
                        "RETURNING id").fetchone()["id"]
    one(url, {"op": "person.merge", "id": str(dup), "into": str(sp["timur"])})
    assert card(url, sp["timur"])["address"] == "вы, Тимур"


def test_clip_url_only_https_on_both_speaker_questions(sp):
    """«▶ послушать» (добавка 15.10: 5–8 с голоса спикера на странице Встреч за её паролем): payload.speaker.clip_url
    доходит до вопроса из кандидатов службы и до вопроса по имени (new_person); веб и телефон кладут её в ссылку как есть
    — поэтому только https://, иначе поле уходит, а вопрос остаётся."""
    url = sp["url"]
    q = one(url, ask_sug([{"person_id": str(sp["timur"]), "signs": [SPEECH]}], key="meeting-speaker:101:SPEAKER_05"))
    assert q["suggestion"]["payload"]["speaker"]["clip_url"] == "https://meetings.example.org/m/101/clip/SPEAKER_03"
    for bad in ("javascript:alert(1)", "http://meetings.example.org/m/101/clip/S", "https://x.example/a b",
                "https://x.example/" + "a" * store.CLIP_URL_MAX, 42):
        op = ask_sug([{"person_id": str(sp["timur"]), "signs": [SPEECH]}], key=f"meeting-speaker:102:S{abs(hash(bad))}")
        op["suggestion"]["payload"]["speaker"]["clip_url"] = bad
        s = one(url, op)["suggestion"]
        assert "clip_url" not in s["payload"]["speaker"] and s["payload"]["ask"], bad
        assert s["payload"]["speaker"]["url"] == "https://meetings.example.org/m/101"
    # Вопрос по имени («Кто это — «Мария»?», служба: people._person_op) — ссылка та же.
    s = one(url, {"op": "suggestion.create", "suggestion": {
        "kind": "person", "source": "meeting", "source_ref": "meeting-person:мария", "batch_ref": "meeting:101",
        "batch_title": "Бета: аудит · 14.10", "quote": "Встреча «Бета: аудит» 14.10: «Мария» — 7 мин речи (по голосу)",
        "payload": {"title": "Мария", "person": {"name": "Мария"}, "auto": True,
                    "speaker": {"meeting_ref": "101", "label": "SPEAKER_09", "seconds": 420, "name_source": "voice",
                                "url": "https://meetings.example.org/m/101",
                                "clip_url": "https://meetings.example.org/m/101/clip/SPEAKER_09"}}}})["suggestion"]
    assert s["status"] == "pending" and s["payload"]["ask"]
    assert s["payload"]["speaker"]["clip_url"].endswith("/clip/SPEAKER_09")


def test_people_day_is_contacts_of_the_day_for_owner_only(sp):
    """С кем была переписка в день встречи — основание вопроса службы (не признак): пункты дайджеста и звонки, без
    упоминаний и без самих встреч; только владельцу."""
    url = sp["url"]
    with dela_db.session(url, "sasha", "t") as c:
        add = lambda kind, source, pids, mention=False, private=False: c.execute(  # noqa: E731
            "INSERT INTO crm.interactions (at, kind, summary, person_ids, source, owner_id, mention, private) "
            "VALUES ('2026-10-09 12:00+03', %s, 'текст', %s, %s, 'sasha', %s, %s)", (kind, pids, source, mention, private))
        add("telegram", "digest", [sp["denis"]], private=True)
        add("call", "phone", [sp["denis"], sp["olga"]])
        add("telegram", "digest", [sp["timur"]], mention=True, private=True)
        add("meeting", "meeting", [sp["pavel"]])
    v = store.view(url, "sasha", "people_day", day="2026-10-09")
    got = {r["name"]: (r["n"], sorted(r["sources"])) for r in v["people"]}
    assert got == {"Денис Образцов": (2, ["digest", "phone"]), "Ольга Тестова": (1, ["phone"])}, got
    assert store.view(url, "sasha", "people_day", day="2026-10-10")["people"] == []
    with pytest.raises(store.OpError):
        store.view(url, "natasha", "people_day", day="2026-10-09")


SAMPLE = Path(__file__).resolve().parents[1] / "contract" / "dela-meetings-speakers.json"


def test_service_sample_runs_through_ops(sp):
    """Образец нагрузки службы встреч (contract/dela-meetings-speakers.json — его же собирает своим кодом и сверяет тест
    Встреч test_speakers_contract.py): что шлёт служба, то сервер и понимает (проверка 15.10: стороны не понимали друг
    друга — служба слала cands, conflict, признаки строками и группу zoom, а сервер ждал candidates, dispute, account)."""
    url = sp["url"]
    sample = json.loads(SAMPLE.read_text(encoding="utf-8"))
    with dela_db.session(url, "sasha", "t") as c:
        orgs = {n: c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES (%s, 'other', 'sasha') RETURNING id",
                             (n,)).fetchone()["id"] for n in ("Пицца", "Бета")}
        orgs["Альфа Банк"] = sp["org"]["Альфа Банк"]
        ids = {}
        for key, p in sample["people"].items():
            ids[key] = str(c.execute(
                "INSERT INTO crm.people (name, short, org_id, emails, owner_id) VALUES (%s, %s, %s, %s, 'sasha') "
                "RETURNING id", (p["name"], p.get("short"), orgs.get(p.get("org")), p.get("emails") or [])
            ).fetchone()["id"])
    text = json.dumps(sample["cases"], ensure_ascii=False).replace("{dmy}", "14.10.2026").replace("{dm}", "14.10")
    text = text.replace("{url}", "https://meetings.example.org")
    for key, pid in ids.items():
        text = text.replace(key, pid)
    cases = {c["name"]: c["op"] for c in json.loads(text)}
    # «Сделано само» (речь + учётка Zoom) — принято само; карточку не учит.
    s = one(url, cases["link"])["suggestion"]
    assert (s["status"], s["reason"]) == ("accepted", store.AUTO_LINK_REASON), s
    assert s["result"]["person_id"] == ids["{vlad}"] and s["result"]["by_owner"] is False
    assert card(url, ids["{vlad}"])["address"] is None
    # Спор: вопрос с кандидатом и «Завести «Ольга (Бета)»»; «Завести» — карточка в «Бете», обращение — ей.
    s = one(url, cases["dispute"])["suggestion"]
    assert s["status"] == "pending" and s["payload"]["held"] == "признаки спорят", s
    assert [o["label"] for o in s["payload"]["options"]] == ["Это Ольга Альфова", "Завести «Ольга (Бета)»", "Не для CRM"]
    assert s["payload"]["ask"].startswith("S3 (34 с) на встрече «Бета: знакомство» 14.10 — это Ольга Альфова (Альфа Банк)?")
    res = one(url, {"op": "suggestion.decide", "id": s["id"], "option": 1})["suggestion"]["result"]
    assert res["created"] and res["by_owner"] is True and res["learned"]["wrote"]["address"] == "ты, Оля"
    assert (card(url, res["person_id"])["name"], card(url, res["person_id"])["org_name"]) == ("Ольга", "Бета")
    # Один признак (учётка): вопрос «Это Глеб Сорокин?»; «да» — почта учётки в карточку.
    s = one(url, cases["one_sign"])["suggestion"]
    assert s["status"] == "pending" and s["payload"]["held"] == "один признак — нужен твой ответ" and s["payload"]["pick"]
    res = one(url, {"op": "suggestion.decide", "id": s["id"], "option": 0})["suggestion"]["result"]
    assert res["learned"]["wrote"]["emails"] == ["gleb@sorokin.test"], res
    assert card(url, ids["{gleb}"])["emails"] == ["gleb@sorokin.test"]


TRANSCRIPT = """[00:00:05] SPEAKER_00: Добрый день, коллеги.
[00:00:12] SPEAKER_03: Ребят, кто у нас с этой информацией?
[00:00:20] SPEAKER_01: Тимур Ильдарович, я вообще ни разу не давал такую информацию."""


def push(url, raw, ref="101"):
    m = {"ref": ref, "url": f"https://meetings.example.org/m/{ref}", "started_at": "2026-10-14T11:00:00+03:00",
         "duration_min": 40, "kind": "meeting", "title": "Бета: аудит", "summary": "Аудит.", "transcript": TRANSCRIPT,
         "raw": raw}
    return one(url, {"op": "meeting.upsert", "meeting": m})["meeting"]


def test_people_final_is_one_truth_for_naming(sp):
    """Служба определила людей (people_final): участники — привязанные, гости — неподтверждённые с именем, роль без
    имени — никто; разбор их не угадывает (строка «ЛЮДИ ВСТРЕЧИ», ответ о людях не читается); снятая привязка уходит."""
    url = sp["url"]
    final = {"people_final": True, "speakers": [
        {"label": "SPEAKER_00", "name": "Саша", "person_id": str(sp["me"]), "state": "owner", "show": "Саша"},
        {"label": "SPEAKER_01", "name": "Денис Образцов", "person_id": str(sp["denis"]), "state": "voice", "show": "Денис К."},
        {"label": "SPEAKER_03", "name": "Тимур", "state": "ask", "show": "Тимур?", "role": "генеральный директор",
         "company": "Бета"},
        {"label": "SPEAKER_02", "state": "none", "role": "юрист клиента"}]}
    m = push(url, final)
    got = store.view(url, "sasha", "meeting", id=m["id"], lines="none")
    assert (got["person_ids"], got["guests"]) == ([str(sp["denis"])], ["Тимур"])
    with dela_db.session(url, "sasha", "t") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (m["id"],)).fetchone()
        prompt = meetings_auto.user_prompt(c, row)
        assert ("ЛЮДИ ВСТРЕЧИ (определила служба встреч — не угадывай): Денис Образцов (Бета); Тимур? (генеральный "
                "директор, Бета) — не подтверждён; юрист клиента — имя не прозвучало.") in prompt, prompt
        assert "Говорящие у службы" not in prompt and "по голосу" not in prompt
        # Ответ модели о людях (старый текст Свода) не читается: «Ольга» не встаёт ни участником, ни гостем.
        out = meetings.apply_names(c, row, {"sphere": "work", "client": "", "label": "Бета", "topic": "аудит",
                                            "people": [{"name": "Ольга", "person": "Ольга Тестова"}], "sure": True}, "t")
        assert ([str(x) for x in out["person_ids"]], out["guests"]) == ([str(sp["denis"])], ["Тимур"])
    assert "people" in meetings_auto.RULES and "ЛЮДИ ВСТРЕЧИ" in meetings_auto.RULES
    # «Вернуть» привязку — служба шлёт встречу без неё: Денис уходит из участников (список — правда целиком).
    final["speakers"][1].pop("person_id")
    final["speakers"][1]["state"] = "guess"
    push(url, final)
    got = store.view(url, "sasha", "meeting", id=m["id"], lines="none")
    assert (got["person_ids"], got["guests"]) == ([], ["Денис Образцов", "Тимур"])
    # Без people_final — как раньше: узнанный голосом прибавляется, ответ разбора — в гости.
    old = push(url, {"speakers": [{"label": "SPEAKER_01", "name": "Денис", "person_id": str(sp["denis"])}]}, ref="102")
    with dela_db.session(url, "sasha", "t") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (old["id"],)).fetchone()
        assert "ЛЮДИ ВСТРЕЧИ" not in meetings_auto.user_prompt(c, row)
        out = meetings.apply_names(c, row, {"sphere": "work", "client": "", "label": "Бета", "topic": "аудит",
                                            "people": [{"name": "Пётр из банка", "person": ""}], "sure": True}, "t")
        assert ([str(x) for x in out["person_ids"]], out["guests"]) == ([str(sp["denis"])], ["Пётр из банка"])


def test_service_sees_flags_learns_and_reads_people_day_over_api(sp):
    """Служба встреч своим токеном: флаги в /api/me (без них она новое не шлёт), person.learn операцией, people_day видом —
    параметр day доходит до вида."""
    url = sp["url"]
    client = TestClient(api.build(Config(db_url=url)))
    h = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'meetings')}"}
    assert {"speaker_link", "people_final"} <= set(client.get("/api/me", headers=h).json()["features"])
    r = client.post("/api/ops", headers=h, json={"ops": [
        {"op": "person.learn", "id": str(sp["timur"]), "address": "вы, Тимур Ильдарович"}]}).json()["results"][0]
    assert r["ok"] and r["wrote"]["address"] == "вы, Тимур Ильдарович", r
    with dela_db.session(url, "sasha", "t") as c:
        c.execute("INSERT INTO crm.interactions (at, kind, summary, person_ids, source, owner_id) "
                  "VALUES ('2026-10-09 12:00+03', 'call', 'созвон', %s, 'phone', 'sasha')", ([sp["denis"]],))
    v = client.get("/api/view/people_day?day=2026-10-09", headers=h).json()
    assert [x["name"] for x in v["people"]] == ["Денис Образцов"], v
