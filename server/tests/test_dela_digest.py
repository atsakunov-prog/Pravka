"""Дайджест Telegram на телефоне (dela_0014, digest.py): приём от службы, выдача владельцу, больше никому.

Текст сводок выдуманный: репозиторий публичный, а настоящий дайджест — личная переписка владельца.
"""

from __future__ import annotations

import copy
import datetime as dt
import json
from pathlib import Path

import psycopg
import pytest
from starlette.testclient import TestClient

from pravka_dela import api, digest, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import dela  # noqa: F401  (фикстура)

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-digest.json").read_text(encoding="utf-8"))
EXAMPLE = CONTRACT["op_put"]["example"]


def put(url, d, user="sasha", actor="svc:digest", via="digest"):
    """Как шлёт служба дайджеста: её токен на владельца, actor svc:digest."""
    return store.apply_ops(url, user, [{"op": "digest.put", "digest": d}], via, actor)["results"][0]


def fresh(hours_ago: float = 1, ref: str | None = None, **kw) -> dict:
    """Сводка, окно которой кончилось hours_ago часов назад: вид digest с завода берёт двое суток от «сейчас»."""
    end = dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=hours_ago)
    d = copy.deepcopy(EXAMPLE["digest"])
    d.update(ref=ref or f"digest:{end:%Y-%m-%dT%H%M%S}", ended_at=end.isoformat(),
             started_at=(end - dt.timedelta(hours=5)).isoformat(), **kw)
    return d


@pytest.fixture()
def family(dela):
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.users SET family = (id IN ('sasha', 'marianna'))")
    return dela


def test_contract_example_goes_through_and_text_is_one_rule(dela):
    """Пример контракта — то, что пошлёт служба, — сервер принимает; текст и поля ответа — ровно как в контракте."""
    assert CONTRACT["features"]["flag"] in store.FEATURES
    r = put(dela, EXAMPLE["digest"])
    assert r["ok"] and set(r["digest"]) == set(CONTRACT["op_put"]["result"]["digest"]), r
    got = store.view(dela, "sasha", "digest", ref="digest:2026-10-13T2000")["digest"]
    want = CONTRACT["digest_object"]["example"]
    assert set(got) == set(want)
    assert got["text"] == want["text"] and got["summary"] == want["summary"] and got["dela"] == want["dela"]
    assert (got["label"], got["chats"], got["msgs"]) == ("вт 13.10, 20:00", 12, 148)


def test_repeat_is_harmless_and_new_content_moves_rev(dela):
    one = put(dela, EXAMPLE["digest"])["digest"]
    two = put(dela, EXAMPLE["digest"])["digest"]
    assert (two["id"], two["rev"], two["seq"]) == (one["id"], 1, one["seq"])  # та же сводка — синку нечего везти
    d = copy.deepcopy(EXAMPLE["digest"])
    d["summary"]["other"] = "Тишина."
    three = put(dela, d)["digest"]
    assert three["id"] == one["id"] and three["rev"] == 2 and three["seq"] > one["seq"]
    with dela_db.session(dela, "system", "test") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.digests").fetchone()["n"] == 1
        # Журнала нет сознательно: текст переписки не копится в общем crm.history.
        assert c.execute("SELECT count(*) AS n FROM crm.history WHERE entity = 'crm.digests'").fetchone()["n"] == 0


def test_only_digest_service_writes(dela):
    """Телефон и веб владельца, служба встреч — не служба дайджеста: отказ словами, строки нет."""
    for actor, via in (("sasha", "app"), ("sasha", "web"), ("svc:meetings", "meetings")):
        r = put(dela, EXAMPLE["digest"], actor=actor, via=via)
        assert not r["ok"] and "только служба дайджеста" in r["error"], (actor, r)
    # Служебный токен «digest», выданный по ошибке на Наташу, тоже не пишет.
    r = put(dela, EXAMPLE["digest"], user="natasha")
    assert not r["ok"] and "только владельцу" in r["error"], r
    with dela_db.session(dela, "system", "test") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.digests").fetchone()["n"] == 0
        # Правило базы, а не только кода: мимо операции строка на не-владельца тоже не ляжет.
        with pytest.raises(psycopg.errors.RaiseException, match="только владельцу"):
            c.execute("INSERT INTO crm.digests (owner_id, ref, ended_at, label, summary, text) "
                      "VALUES ('natasha', 'digest:x', now(), 'x', '{}', 'x')")


def test_only_owner_reads_not_family_not_team(family):
    url = family
    assert put(url, fresh())["ok"]
    assert store.view(url, "sasha", "digest")["items"]
    for user in ("marianna", "natasha"):  # семья и команда
        with pytest.raises(store.OpError, match="только владельцу"):
            store.view(url, user, "digest")
        s = store.sync(url, user, 0)
        assert "digest" not in s, user
        # И сама база не покажет: RLS «только своё».
        with dela_db.session(url, user, via="app") as c:
            assert c.execute("SELECT count(*) AS n FROM crm.digests").fetchone()["n"] == 0


def test_sync_carries_only_the_last_and_only_when_new(dela):
    s0 = store.sync(dela, "sasha", 0)
    assert s0["full"] and "digest" in s0 and s0["digest"] is None  # сводок нет — кэш очистить
    old = put(dela, fresh(hours_ago=8, ref="digest:old"))["digest"]
    s1 = store.sync(dela, "sasha", 0)
    assert s1["digest"]["ref"] == "digest:old" and s1["seq"] >= old["seq"]
    # Ничего нового — поля нет: телефон кэш не трогает. Запас синка — сдвигаем since дальше него.
    far = s1["seq"] + store.OVERLAP + 1
    assert "digest" not in store.sync(dela, "sasha", far)
    since = s1["seq"]
    new = put(dela, fresh(hours_ago=1, ref="digest:new"))["digest"]
    s2 = store.sync(dela, "sasha", since)
    assert s2["digest"]["ref"] == "digest:new" and s2["seq"] >= new["seq"]
    assert s2["digest"]["text"].startswith("Ждут тебя")
    # Догрузка старого окна позже свежего не подменяет последнюю: в синке — по-прежнему самая поздняя.
    put(dela, fresh(hours_ago=20, ref="digest:older"))
    s3 = store.sync(dela, "sasha", s2["seq"])
    assert s3.get("digest") is None or s3["digest"]["ref"] == "digest:new"
    assert store.sync(dela, "sasha", 0)["digest"]["ref"] == "digest:new"


def test_view_window_single_and_words(dela):
    put(dela, fresh(hours_ago=2, ref="digest:a"))
    put(dela, fresh(hours_ago=30, ref="digest:b"))
    put(dela, fresh(hours_ago=24 * 5, ref="digest:c"))
    v = store.view(dela, "sasha", "digest")
    assert v["days"] == 2 and [x["ref"] for x in v["items"]] == ["digest:a", "digest:b"] and v["last"]["ref"] == "digest:a"
    assert [x["ref"] for x in store.view(dela, "sasha", "digest", days="7")["items"]] == ["digest:a", "digest:b", "digest:c"]
    assert [x["ref"] for x in store.view(dela, "sasha", "digest", days="7", limit="1")["items"]] == ["digest:a"]
    one = store.view(dela, "sasha", "digest", ref="digest:c")["digest"]
    assert store.view(dela, "sasha", "digest", id=one["id"])["digest"]["ref"] == "digest:c"
    with pytest.raises(store.OpError, match="нет такого"):
        store.view(dela, "sasha", "digest", ref="digest:nope")
    with pytest.raises(store.OpError, match="не uuid"):
        store.view(dela, "sasha", "digest", id="x")
    with pytest.raises(store.OpError, match="числа"):
        store.view(dela, "sasha", "digest", days="два")


def test_last_is_there_even_when_window_is_empty(dela):
    """Выходные или комп спал: в окне двух суток пусто, а карточке «Сегодня» всё равно есть что показать."""
    put(dela, fresh(hours_ago=24 * 4, ref="digest:old"))
    v = store.view(dela, "sasha", "digest")
    assert v["items"] == [] and v["last"]["ref"] == "digest:old"


def test_put_refuses_in_words_and_cleans(dela):
    def err(**kw):
        d = {**fresh(), **kw}
        r = put(dela, {k: v for k, v in d.items() if v is not ...})
        assert not r["ok"], r
        return r["error"]

    assert "ref" in err(ref=...)
    assert "ended_at" in err(ended_at=...)
    assert "ISO" in err(ended_at="вчера")
    assert "summary" in err(summary="текст")
    assert "summary.work" in err(summary={"work": "текст"})
    assert "не раньше" in err(started_at=dt.datetime.now(dt.timezone.utc).isoformat(),
                              ended_at=(dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)).isoformat())
    assert "dela.created" in err(dela={"created": "много"})
    # Мусор вокруг пунктов сервер чистит: чужой раздел, пустые пункты, переносы строк; подпись — из конца окна.
    r = put(dela, {"ref": "digest:clean", "ended_at": "2026-10-12T22:00:00+03:00", "dela": None,
                   "summary": {"waiting": [{"who": "Иван", "what": "ждёт\nответа", "chat": ""}, {}, "строка"],
                               "family": ["", "Купить молоко"], "secret": ["чужое"]}})
    assert r["ok"], r
    got = store.view(dela, "sasha", "digest", ref="digest:clean")["digest"]
    assert got["summary"] == {"waiting": [{"chat": "", "who": "Иван", "what": "ждёт ответа"}], "work": [],
                              "family": ["Купить молоко"], "other": ""}
    assert got["label"] == "пн 12.10, 22:00" and got["dela"] is None
    assert got["text"] == "Ждут тебя\n• Иван: ждёт ответа\n\nДом\n• Купить молоко"


def test_text_of_empty_and_dela_line():
    empty = digest.norm_summary({}, store.OpError)
    assert digest.text_of(empty) == "Ничего, что ждёт тебя."
    assert digest.dela_line({k: 0 for k in digest.COUNTS}) == "В Дела: новых дел нет."
    assert digest.dela_line(None) == ""


def test_api_service_writes_phone_reads_others_refused(family):
    """Весь путь через HTTP: служба дайджеста пишет, телефон владельца читает синком и видом; узкий токен службы
    и телефоны семьи и команды — нет."""
    url = family
    client = TestClient(api.build(Config(db_url=url)))
    svc = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'digest')}"}
    phone = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'device', 'телефон')}"}
    dengi = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'dengi')}"}
    meet = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'meetings')}"}
    wife = {"Authorization": f"Bearer {tokens.issue(url, 'marianna', 'device', 'телефон М')}"}
    team = {"Authorization": f"Bearer {tokens.issue(url, 'natasha', 'device', 'телефон Н')}"}

    body = {"ops": [{"op": "digest.put", "digest": fresh()}]}
    assert client.post("/api/ops", json=body).status_code == 401
    r = client.post("/api/ops", headers=svc, json=body).json()
    assert r["results"][0]["ok"], r
    assert "digest" in client.get("/api/me", headers=phone).json()["features"]

    s = client.get("/api/sync?since=0", headers=phone).json()
    assert s["digest"]["text"].startswith("Ждут тебя") and "digest" in s["features"]
    v = client.get("/api/view/digest?days=2", headers=phone).json()
    assert v["ok"] and len(v["items"]) == 1 and v["last"]["id"] == s["digest"]["id"]

    # Узкий токен «Денег» до операции не доходит; служба встреч и телефон владельца — доходят, но отказ словами.
    assert client.post("/api/ops", headers=dengi, json=body).status_code == 403
    assert client.get("/api/view/digest", headers=dengi).status_code == 403
    for h in (meet, phone):
        res = client.post("/api/ops", headers=h, json=body).json()["results"][0]
        assert not res["ok"] and "только служба дайджеста" in res["error"]
    # Семья и команда: в синке поля нет, вид — отказ.
    for h in (wife, team):
        assert "digest" not in client.get("/api/sync?since=0", headers=h).json()
        bad = client.get("/api/view/digest", headers=h)
        assert bad.status_code == 400 and "только владельцу" in bad.json()["error"]
