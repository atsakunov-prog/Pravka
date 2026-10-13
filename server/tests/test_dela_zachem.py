"""«Зачем» по-человечески (zachem.py, 13.10.2026): что берём, как спрашиваем Claude (подменён), как применяем, где
помним старые названия и как возвращаем. Имена выдуманные."""

from __future__ import annotations

import json
from types import SimpleNamespace as NS

from pravka_dela import parse, store, zachem
from test_dela_krug import kr, mk, ok  # noqa: F401  (фикстура)
from test_dela_schema import dela, project  # noqa: F401


def _deal(url, client, name, stage="active"):
    with parse.db.session(url, "system", "test") as c:
        return c.execute("INSERT INTO crm.deals (project_id, name, stage) VALUES (%s, %s, %s) RETURNING id",
                         (client, name, stage)).fetchone()["id"]


class FakeBatches:
    def __init__(self, answer):
        self.answer, self.sent = answer, []

    def create(self, requests):
        self.sent.append(requests)
        return NS(id="msgbatch_z")

    def retrieve(self, bid):
        return NS(processing_status="ended")

    def results(self, bid):
        msg = NS(stop_reason="end_turn", model="claude-opus-5-5", content=[NS(type="text", text=json.dumps({"items": self.answer}))],
                 usage=NS(input_tokens=3000, output_tokens=400, cache_creation_input_tokens=0, cache_read_input_tokens=0))
        return iter([NS(custom_id="zachem", result=NS(type="succeeded", message=msg))])


def test_propose_apply_and_rollback(kr):
    sea = project(kr, "sasha", "Морская доставка")
    fx = _deal(kr, sea, "Морская доставка: валютная модель")
    _deal(kr, sea, "Морская доставка: старое", stage="archive")  # архив — не трогаем
    mk(kr, title="Собрать курсы валют", deal_id=str(fx), project_id=str(sea))
    goal = mk(kr, title="Сын занимается музыкой", goal=True, directions=["Дети"])
    mk(kr, title="Выучить с сыном паровоз к концерту", why_id=goal["id"])
    items = zachem.collect(kr, "sasha")
    assert [x["name"] for x in items] == ["Морская доставка: валютная модель", "Сын занимается музыкой"]
    text = zachem.prompt(items)
    assert f"d:{fx} · проект клиента «Морская доставка»" in text and "дела: Собрать курсы валют" in text
    assert f"g:{goal['num']} · цель дерева: «Сын занимается музыкой»" in text and "паровоз" in text
    answer = [{"key": f"d:{fx}", "name": "Сделать валютную модель"}, {"key": f"g:{goal['num']}", "name": ""}]
    b = FakeBatches(answer)
    out = zachem.propose(kr, "sasha", "key", None, client=NS(messages=NS(batches=b)), wait=0)
    assert out["seen"] == 2 and [(x["was"], x["now"]) for x in out["items"]] == [("Морская доставка: валютная модель", "Сделать валютную модель")]
    assert out["usd"] > 0 and b.sent[0][0]["params"]["output_config"]["effort"] == zachem.EFFORT
    # Название успели поменять руками — предложение не применяем.
    stale = {**out["items"][0], "id": str(_deal(kr, sea, "Другое имя")), "was": "Не так"}
    res = zachem.apply(kr, "sasha", out["items"] + [stale])
    assert [x["now"] for x in res["done"]] == ["Сделать валютную модель"] and not res["errors"]
    with parse.db.session(kr, "sasha", "t") as c:
        assert c.execute("SELECT name FROM crm.deals WHERE id = %s", (fx,)).fetchone()["name"] == "Сделать валютную модель"
    with parse.db.session(kr, "system", "t") as c:
        h = c.execute("SELECT actor FROM crm.history WHERE entity_id = %s ORDER BY id DESC LIMIT 1", (str(fx),)).fetchone()
    assert h["actor"] == zachem.ACTOR
    svod = {r["key"]: r for r in store.sync(kr, "sasha", 0)["svod"]}
    assert svod[zachem.RENAMED_KEY]["value"][0]["was"] == "Морская доставка: валютная модель"
    # Цели — тем же путём.
    res = zachem.apply(kr, "sasha", [{"kind": "goal", "id": goal["id"], "was": "Сын занимается музыкой", "now": "Сын играет на пианино"}])
    assert len(res["done"]) == 1
    back = zachem.rollback(kr, "sasha")
    assert len(back["done"]) == 2
    with parse.db.session(kr, "sasha", "t") as c:
        assert c.execute("SELECT name FROM crm.deals WHERE id = %s", (fx,)).fetchone()["name"] == "Морская доставка: валютная модель"
        assert c.execute("SELECT title FROM tasks.tasks WHERE id = %s", (goal["id"],)).fetchone()["title"] == "Сын занимается музыкой"


def test_schema_is_strict():
    s = zachem.SCHEMA
    item = s["properties"]["items"]["items"]
    assert s["additionalProperties"] is False and item["additionalProperties"] is False
    assert set(item["required"]) == set(item["properties"])
