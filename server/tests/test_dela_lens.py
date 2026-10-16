"""Этап 6 аудита (16.10.2026, слово владельца «да. Делай»): общие правила дел в Своде (rules.dela) с заводским текстом,
выключатель «Только тебе» (meetings.lens), «Только тебе» сводки дайджеста — since, team, only_you в сводке и строками
crm.insights, кнопки «Пригодилось / Мимо / Неверно» (insight.rate) и мерило (вид lens).

Люди, клиенты и переписка выдуманные: репозиторий публичный.
"""

from __future__ import annotations

import copy
import datetime as dt
import json
import re
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from pravka_dela import api, digest, insights, mcp_tools, rules, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from pravka_dela.meetings import MSK
from test_dela_digest_crm import crm, put, window  # noqa: F401  (фикстура и помощники разноса)
from test_dela_schema import dela  # noqa: F401  (фикстура)

CONTRACT = Path(__file__).resolve().parents[1] / "contract"
SVOD = json.loads((CONTRACT / "svod.json").read_text(encoding="utf-8"))
DIGEST = json.loads((CONTRACT / "dela-digest.json").read_text(encoding="utf-8"))


def svod_set(url, key, actor="cli:claude", user="sasha", **op):
    """Как пишет сессия Claude (CLI svod set: actor cli:<author>) или служба своим токеном (svc:…)."""
    return store.apply_ops(url, user, [{"op": "svod.set", "key": key, "author": "claude", **op}], "cli", actor)["results"][0]


def ops(url, *items, user="sasha", via="web", actor=None):
    return store.apply_ops(url, user, list(items), via, actor)["results"]


# ── rules.dela: заводской текст, вид svod, кто пишет ─────────────────────────────


def test_factory_rules_text_is_whole_and_safe():
    """Заводской текст — 15 разделов §2 этапа 6, без фигурных скобок (его вставляют в промпты с {TEAM} и {RULES}) и
    без имён команды и семьи, которые уже знает публичный код (их не дописываем и сюда)."""
    text = rules.factory_text()
    assert text.startswith("ПРАВИЛА ДЕЛ САШИ — одни для всех разборов")
    assert re.findall(r"^(\d+)\. [А-ЯЁ]", text, re.M) == [str(n) for n in range(1, 16)]
    assert "{" not in text and "}" not in text
    for name in ("Наташ", "Марианн", "Серёж", "Боря", "Борей"):
        assert name not in text, name
    assert 10_000 < len(text) < rules.BODY_MAX


def test_contract_names_new_keys():
    keys = SVOD["svod"]["keys"]
    assert {"rules.dela", "meetings.lens"} <= set(keys)
    assert "rules-dela.txt" in keys["rules.dela"] and "factory" in keys["rules.dela"]
    assert "«Семья: …»" in keys["people.team"]
    assert (CONTRACT / "prompts" / "rules-dela.txt").read_text(encoding="utf-8").strip() == rules.factory_text()


def test_view_gives_factory_until_written_then_the_record(dela):
    """Записи нет — вид svod?key=rules.dela отдаёт заводской текст записью rev 0; первая запись идёт поверх него с
    base_rev 0, дальше — обычная запись Свода; rules.text — то же для сервера."""
    got = store.view(dela, "sasha", "svod", key="rules.dela")["items"]
    assert len(got) == 1 and got[0]["factory"] and got[0]["rev"] == 0 and got[0]["author"] == "factory"
    assert got[0]["body"] == rules.factory_text()
    # Префиксом и всем списком заводской не подмешивается: это не запись.
    assert store.view(dela, "sasha", "svod", prefix="rules.")["items"] == []
    with dela_db.session(dela, "sasha", via="test") as c:
        assert rules.text(c, "sasha") == {"body": rules.factory_text(), "rev": 0, "factory": True}

    r = svod_set(dela, "rules.dela", body="ПРАВИЛА\r\n1. Дело — действие.\n", base_rev=0, reason="первая версия")
    assert r["ok"] and r["row"]["rev"] == 1 and r["row"]["body"] == "ПРАВИЛА\n1. Дело — действие.", r
    one = store.view(dela, "sasha", "svod", key="rules.dela")["items"]
    assert len(one) == 1 and one[0]["rev"] == 1 and "factory" not in one[0]
    with dela_db.session(dela, "sasha", via="test") as c:
        assert rules.text(c, "sasha") == {"body": "ПРАВИЛА\n1. Дело — действие.", "rev": 1, "factory": False}
    stale = svod_set(dela, "rules.dela", body="другое", base_rev=0)
    assert not stale["ok"] and "уже версии 1" in stale["error"]


def test_only_owner_hand_writes_rules(dela):
    """Правила пишет владелец (claude по его слову, телефон первым знакомством); служба с его токеном, тюнер — нет."""
    for actor, author in (("svc:meetings", "meetings"), ("svc:digest", "digest"), ("cli:tuner", "tuner")):
        r = store.apply_ops(dela, "sasha", [{"op": "svod.set", "key": "rules.dela", "body": "x", "author": author}],
                            "x", actor)["results"][0]
        assert not r["ok"] and "пишет владелец" in r["error"], (actor, r)
    team = svod_set(dela, "rules.dela", user="natasha", actor="natasha", body="свои правила")
    assert not team["ok"] and "только он" in team["error"]
    for kw, word in (({"value": {"a": 1}}, "текст"), ({"body": "   "}, "пустым"), ({"body": "дело {RULES}"}, "фигурных"),
                     ({"body": "я" * (rules.BODY_MAX + 1)}, "длиннее")):
        r = svod_set(dela, "rules.dela", **kw)
        assert not r["ok"] and word in r["error"], (kw.keys(), r)
    seed = store.apply_ops(dela, "sasha", [{"op": "svod.set", "key": "rules.dela", "body": "с телефона", "author": "seed"}],
                           "app", "sasha")["results"][0]
    assert seed["ok"], seed


def test_services_read_rules_narrow_tokens_do_not(dela):
    """Встречи и дайджест ходят токеном владельца — читают rules.dela видом svod (и заводской текст, пока записи нет);
    узким токенам («Деньги», «Еда», «Спорт», «Дом») правила дел не положены."""
    client = TestClient(api.build(Config(db_url=dela)))
    for name in ("meetings", "digest"):
        h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', name)}"}
        r = client.get("/api/view/svod?key=rules.dela", headers=h).json()
        assert r["ok"] and r["items"][0]["factory"] and r["items"][0]["body"] == rules.factory_text(), name
        assert {"lens", "rules"} <= set(client.get("/api/me", headers=h).json()["features"])
    for name in ("dengi", "eda", "sport", "dom"):
        h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', name)}"}
        assert client.get("/api/view/svod?key=rules.dela", headers=h).status_code == 403, name
        assert client.get("/api/view/lens", headers=h).status_code == 403, name


# ── meetings.lens: выключатель «Только тебе» ─────────────────────────────────────


def test_lens_switch_checked_and_normalized(dela):
    r = svod_set(dela, "meetings.lens", value={"off": ["Hedge", "digest:cooling", "hedge", " meeting:table "]})
    assert r["ok"] and r["row"]["value"] == {"off": ["hedge", "digest:cooling", "meeting:table"]}, r
    for value, word in (({"off": ["мимо"]}, "не годится"), ({"off": "hedge"}, "список"), ({"off": [], "x": 1}, "нет таких"),
                        ({"on": "yes"}, "true или false"), ({"off": ["chat:hedge"]}, "не годится")):
        bad = svod_set(dela, "meetings.lens", value=value)
        assert not bad["ok"] and word in bad["error"], (value, bad)
    assert not svod_set(dela, "meetings.lens", body="hedge")["ok"]
    svc = svod_set(dela, "meetings.lens", actor="svc:meetings", value={"off": []})
    assert not svc["ok"] and "пишет владелец" in svc["error"]
    with dela_db.session(dela, "sasha", via="test") as c:
        lens = rules.lens(c, "sasha")
    assert rules.is_off(lens, "meeting", "hedge") and rules.is_off(lens, "digest", "hedge")
    assert rules.is_off(lens, "digest", "cooling") and not rules.is_off(lens, "meeting", "cooling")
    assert rules.is_off({"on": False}, "digest", "signal") and not rules.is_off(None, "digest", "signal")


# ── Сводка дайджеста: since, team, only_you ──────────────────────────────────────


def test_only_you_line_parsed_even_with_dots_in_chat():
    """Строка модели «вид · кто · чат · когда · «цитата» · чем важно · что сделать · сделка · услуга»: название чата и
    цитата сами бывают с « · »; объект службы — как есть; непонятная строка — целиком в why, а не в мусор."""
    p = digest.parse_only_you(
        "objection · Олег · Бета Групп · сделка · 13.10 16:40 · «банк смотрит · только на залог» · условие может не "
        "устроить собственника · обсудить до ответа банку · Бета: кредит · —")
    assert (p["kind"], p["who"], p["chat"], p["at"], p["quote"]) == (
        "objection", "Олег", "Бета Групп · сделка", "13.10 16:40", "банк смотрит · только на залог")
    assert (p["why"], p["do"], p["deal"], p["service"]) == (
        "условие может не устроить собственника", "обсудить до ответа банку", "Бета: кредит", "")
    assert p["key"].startswith("oy-") and len(p["key"]) == 15
    again = digest.parse_only_you("objection · олег · Бета Групп · сделка · 13.10 16:40 · «банк смотрит · только на залог» · "
                                  "другими словами · — · — · —")
    assert again["key"] == p["key"]  # ключ — по виду, кто, когда и цитате: та же находка, иначе сказанная
    obj = digest.parse_only_you({"kind": "Reason", "who": "Лайли", "at": "06.10 12:00", "quote": "«Свисти»", "key": "k-1"})
    assert (obj["kind"], obj["quote"], obj["key"]) == ("reason", "Свисти", "k-1")
    raw = digest.parse_only_you("просто фраза без полей")
    assert raw["why"] == "просто фраза без полей" and raw["kind"] == ""
    assert digest.parse_only_you("") is None and digest.parse_only_you(["x"]) is None


def test_summary_keeps_new_fields_text_never_carries_only_you():
    s = digest.norm_summary({
        "waiting": [{"chat": "Иван", "who": "Иван", "what": "ждёт модель", "since": "06.10"}],
        "team": [{"who": "Аня", "what": "КП Ромашке", "due": "17.10"}, {"who": "", "what": "", "due": ""}],
        "only_you": ["cooling · Иван · Иван · 15.10 10:00 · «вернусь на следующей неделе» · сдвигает срок · позвонить · — · —",
                     "cooling · Иван · Иван · 15.10 10:00 · «вернусь на следующей неделе» · то же · — · — · —", None, 7],
    }, store.OpError)
    assert s["waiting"] == [{"chat": "Иван", "who": "Иван", "what": "ждёт модель", "since": "06.10"}]
    assert s["team"] == [{"who": "Аня", "what": "КП Ромашке", "due": "17.10"}]
    assert len(s["only_you"]) == 1 and s["only_you"][0]["quote"] == "вернусь на следующей неделе"
    text = digest.text_of(s)
    assert text == "Ждут тебя\n• Иван: ждёт модель — ждёт с 06.10\n\nКоманда\n• Аня — КП Ромашке, 17.10"
    assert "вернусь" not in text and "сдвигает" not in text
    with pytest.raises(store.OpError, match="only_you"):
        digest.norm_summary({"only_you": "строка"}, store.OpError)


def test_contract_example_only_you_lands_in_insights_and_view(dela):
    """Пример контракта: пункт «Только тебе» лёг строкой crm.insights (ключ — как в контракте), вид одной сводки отдаёт
    её с состоянием; ответ службе — ключ и id для кнопок."""
    ex = DIGEST["op_put"]["example"]["digest"]
    r = put(dela, copy.deepcopy(ex))
    assert r["ok"], r
    key = DIGEST["digest_object"]["example"]["summary"]["only_you"][0]["key"]
    assert [i["key"] for i in r["insights"]["items"]] == [key] and r["insights"]["retired"] == 0
    one = store.view(dela, "sasha", "digest", ref=ex["ref"])
    (ins,) = one["insights"]
    assert (ins["source"], ins["source_ref"], ins["item_key"], ins["kind"], ins["state"]) == (
        "digest", ex["ref"], key, "objection", "new")
    assert ins["quote"] == "банк смотрит только на залог долей"
    assert ins["text"].startswith("Олег (Бета Групп · сделка): условие банка") and "Сделать: обсудить" in ins["text"]
    assert dt.datetime.fromisoformat(str(ins["at"])) == dt.datetime(2026, 10, 13, 16, 40, tzinfo=MSK)
    # Коннектор claude.ai: текст сводки — без «Только тебе», пункт — своим блоком с пометкой «людям не цитировать».
    assert ops(dela, {"op": "insight.rate", "id": str(ins["id"]), "rate": "useful"})[0]["ok"]
    card = mcp_tools.digest_card(dela, ex["ref"])
    head, _, mine = card.partition("Только тебе (видит только Саша; людям не цитировать):")
    assert "залог долей" not in head and "objection Олег 13.10 16:40: «банк смотрит только на залог долей»" in mine
    assert "оценка: useful" in mine


def test_sync_only_owner_gets_only_you_and_text_never(dela):
    """Синк: «Только тебе» — полем summary сводки только владельцу (семье и команде поля digest нет вовсе), в text — нет."""
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.users SET family = (id IN ('sasha', 'marianna'))")
    ex = copy.deepcopy(DIGEST["op_put"]["example"]["digest"])
    assert put(dela, ex)["ok"]
    own = store.sync(dela, "sasha", 0)["digest"]
    assert own["summary"]["only_you"][0]["quote"] == "банк смотрит только на залог долей"
    assert "залог" not in own["text"] and "Команда\n• Аня — КП для Бета Групп, 15.10" in own["text"]
    for user in ("natasha", "marianna"):
        assert "digest" not in store.sync(dela, user, 0), user
        with dela_db.session(dela, user, via="app") as c:
            assert c.execute("SELECT count(*) AS n FROM crm.insights").fetchone()["n"] == 0, user


def test_insights_link_work_people_never_team_and_never_timeline(crm):
    """Пункт о рабочем человеке — к его карточке и клиенту (как «Ждут тебя»), о команде — ни к кому, сделка — по имени;
    в хронологию клиента «Только тебе» не идёт никогда; команда строк не видит."""
    url, ppl = crm["url"], crm["people"]
    summary = {"waiting": [], "work": [], "only_you": [
        "cooling · Иван · Иван Соколов (Ромашка) · 13.10 11:20 · «вернусь позже» · сдвигает срок · позвонить · Модель · —",
        "unhappy · Наталья · Наталья Ветрова (Финбюро) · 13.10 12:00 · «устала» · про команду · — · — · —",
        "signal · Ольга · Ольга Лебедева · 13.10 13:00 · «ищем финансиста» · боль под услугу · созвон · — · финансирование",
    ]}
    r = put(url, window(summary=summary))
    assert r["ok"] and len(r["insights"]["items"]) == 3, r
    with dela_db.session(url, "sasha", via="test") as c:
        got = {x["kind"]: x for x in c.execute("SELECT * FROM crm.insights WHERE source = 'digest'").fetchall()}
        assert c.execute("SELECT count(*) AS n FROM crm.interactions WHERE source = 'digest'").fetchone()["n"] == 0
    assert got["cooling"]["person_ids"] == [ppl["ivan"]] and got["cooling"]["project_id"] == crm["projects"]["Ромашка"]
    assert got["cooling"]["deal_id"] == crm["deal"]  # «Модель» — живая сделка Ромашки
    assert got["unhappy"]["person_ids"] == [] and got["unhappy"]["project_id"] is None  # команда — не к карточке
    assert got["signal"]["person_ids"] == [] and got["signal"]["text"].endswith("(услуга: финансирование)")  # личная
    # Карточка клиента — блок «Только тебе» владельцу; Наташе Ромашка открыта, но строк она не видит.
    card = store.view(url, "sasha", "client", project_id=str(crm["projects"]["Ромашка"]))
    assert [x["kind"] for x in card["insights"]] == ["cooling"]
    assert store.view(url, "natasha", "client", project_id=str(crm["projects"]["Ромашка"]))["insights"] == []
    with dela_db.session(url, "natasha", via="test") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.insights").fetchone()["n"] == 0


def test_repeat_keeps_rating_retires_only_unrated(crm):
    url = crm["url"]
    one = ["cooling · Иван · Иван Соколов (Ромашка) · 13.10 11:20 · «вернусь позже» · сдвигает срок · позвонить · — · —",
           "pressure · Михаил Орлов · (Бета Групп) сделка · 13.10 12:30 · «срочно, финально» · давит · ответить · — · —",
           "reason · Ольга · Ольга Лебедева · 13.10 13:00 · «свисти, когда собираемся» · тёплое · ответить · — · —"]
    first = put(url, window(summary={"only_you": one}))["insights"]["items"]
    keys = [x["key"] for x in first]
    assert ops(url, {"op": "insight.rate", "source": "digest", "source_ref": "digest:2026-10-13T2000",
                     "item_key": keys[0], "rate": "useful"})[0]["ok"]
    assert ops(url, {"op": "insight.delete", "id": first[2]["id"]})[0]["ok"]
    # Та же сводка заново — строк столько же, оценка цела, убранное не вернулось.
    again = put(url, window(summary={"only_you": one}))["insights"]
    assert [x["id"] for x in again["items"]] == [x["id"] for x in first] and again["retired"] == 0
    assert [x["state"] for x in again["items"]][0] == "useful" and again["items"][2]["gone"]
    # Сводка без пунктов: неоценённый убран, оценённый остался (слово владельца для мерила).
    gone = put(url, window(summary={"only_you": []}))["insights"]
    assert gone == {"items": [], "retired": 1}
    with dela_db.session(url, "sasha", via="test") as c:
        live = c.execute("SELECT item_key, state FROM crm.insights WHERE deleted_at IS NULL").fetchall()
    assert [(x["item_key"], x["state"]) for x in live] == [(keys[0], "useful")]


# ── Кнопки «Пригодилось / Мимо / Неверно» и мерило ───────────────────────────────


def test_rate_by_key_or_id_and_refusals(crm):
    url = crm["url"]
    put(url, window(summary={"only_you": ["cooling · Иван · Иван Соколов (Ромашка) · 13.10 11:20 · «вернусь позже» · "
                                          "сдвигает срок · позвонить · — · —"]}))
    with dela_db.session(url, "sasha", via="test") as c:
        row = c.execute("SELECT id, item_key FROM crm.insights").fetchone()
    by_key = {"op": "insight.rate", "source": "digest", "source_ref": "digest:2026-10-13T2000", "item_key": row["item_key"]}
    r = ops(url, {**by_key, "rate": "dismissed"})[0]
    assert r["ok"] and r["changed"] and r["insight"]["state"] == "dismissed"
    assert ops(url, {**by_key, "rate": "dismissed"})[0]["changed"] is False
    assert ops(url, {"op": "insight.rate", "id": str(row["id"]), "rate": "new"})[0]["insight"]["state"] == "new"
    for bad, word in (({**by_key, "rate": "ok"}, "оценка"), ({"op": "insight.rate", "rate": "useful"}, "нужен id"),
                      ({**by_key, "item_key": "oy-нет", "rate": "useful"}, "нет такого"),
                      ({**by_key, "source": "chat", "rate": "useful"}, "нужен id")):
        res = ops(url, bad)[0]
        assert not res["ok"] and word in res["error"], (bad, res)
    # Команда чужой пункт не находит (RLS «своё»), убранный — не оценивается.
    assert not ops(url, {**by_key, "rate": "useful"}, user="natasha", actor="natasha")[0]["ok"]
    ops(url, {"op": "insight.delete", "id": str(row["id"])})
    assert "убран" in ops(url, {**by_key, "rate": "useful"})[0]["error"]


def test_rate_through_api_with_meetings_token(crm):
    """Кнопку в Telegram ловит служба встреч (replies.py) — её токен владельца ставит оценку операцией."""
    url = crm["url"]
    put(url, window(summary={"only_you": ["signal · Ольга · Ольга Лебедева · 13.10 13:00 · «ищем финансиста» · боль · "
                                          "созвон · — · финансирование"]}))
    with dela_db.session(url, "sasha", via="test") as c:
        key = c.execute("SELECT item_key FROM crm.insights").fetchone()["item_key"]
    client = TestClient(api.build(Config(db_url=url)))
    meet = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'meetings')}"}
    body = {"ops": [{"op": "insight.rate", "source": "digest", "source_ref": "digest:2026-10-13T2000", "item_key": key,
                     "rate": "useful"}]}
    res = client.post("/api/ops", headers=meet, json=body).json()["results"][0]
    assert res["ok"] and res["insight"]["state"] == "useful", res
    lens = client.get("/api/view/lens", headers=meet).json()
    assert lens["ok"] and lens["kinds"][0]["useful"] == 1


def test_lens_counts_and_verdicts(crm):
    """Мерило: первая неделя — тень, до четырёх — копим; через четыре недели вид с «неверно» больше 10 % или
    «пригодилось» меньше 30 % оценённых — «выключить» и подсказка в форме meetings.lens; мало оценок — не судим."""
    url = crm["url"]
    with dela_db.session(url, "sasha", via="test", actor="svc:digest") as c:
        for i in range(10):
            kind = "cooling" if i < 6 else "signal"
            state = ("wrong" if i < 2 else "useful" if i < 4 else "dismissed") if kind == "cooling" else "useful"
            c.execute("INSERT INTO crm.insights (owner_id, kind, text, source, source_ref, item_key, state, author) "
                      "VALUES ('sasha', %s, 'пункт', 'digest', 'digest:x', %s, %s, 'svc:digest')", (kind, f"k{i}", state))
        c.execute("INSERT INTO crm.insights (owner_id, kind, text, source, source_ref, item_key, author) "
                  "VALUES ('sasha', 'hedge', 'пункт', 'meeting', '212', 'm1', 'svc:meetings')")
    v = store.view(url, "sasha", "lens")
    by = {(k["source"], k["kind"]): k for k in v["kinds"]}
    assert all(k["verdict"] == "shadow" for k in v["kinds"]) and v["suggest_off"] == [] and v["lens"] is None
    cool = by[("digest", "cooling")]
    assert (cool["n"], cool["rated"], cool["useful"], cool["dismissed"], cool["wrong"]) == (6, 6, 2, 2, 2)
    assert (cool["useful_pct"], cool["wrong_pct"]) == (33, 33)
    with dela_db.session(url, "system", "test") as c:
        c.execute("UPDATE crm.insights SET created_at = now() - interval '29 days' WHERE source = 'digest'")
        c.execute("UPDATE crm.insights SET created_at = now() - interval '10 days' WHERE source = 'meeting'")
    svod_set(url, "meetings.lens", value={"off": ["digest:cooling"]})
    v = store.view(url, "sasha", "lens")
    by = {(k["source"], k["kind"]): k for k in v["kinds"]}
    assert by[("digest", "cooling")]["verdict"] == "off" and by[("digest", "cooling")]["is_off"]
    assert by[("digest", "signal")]["verdict"] == "few"  # 4 оценки — рано судить
    assert by[("meeting", "hedge")]["verdict"] == "collect"
    assert v["suggest_off"] == ["digest:cooling"] and v["lens"] == {"off": ["digest:cooling"]}
    assert v["rule"] == {"shadow_days": 7, "measure_days": 28, "rated_min": 5, "wrong_max_pct": 10, "useful_min_pct": 30}
    with pytest.raises(store.OpError, match="только владельцу"):
        store.view(url, "natasha", "lens")


def test_features_and_contract_parts():
    assert {"lens", "rules"} <= set(store.FEATURES)
    assert "insight.rate" in store.HANDLERS and "lens" in store.VIEWS
    rate = DIGEST["only_you"]["rate"]["example"]
    assert rate["op"] == "insight.rate" and rate["rate"] in insights.RATES
    assert DIGEST["only_you"]["rate"]["example"]["item_key"] == DIGEST["digest_object"]["example"]["summary"]["only_you"][0]["key"]
