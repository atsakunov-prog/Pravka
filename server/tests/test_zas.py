"""Лента Засечки на сервере (dela_0038, zas.py): передача с телефона, правки веба сразу, мутаторы телефона, синк,
таймер, публикация суток в архив (docs/server-truth.md)."""

import time
import uuid

import pytest

from pravka_dela import db as dela_db, store, zas
from pravka_dela.ledger import day_of
from test_dela_schema import dela  # noqa: F401

H = 3_600_000


def now_ms() -> int:
    return int(time.time() * 1000)


def ops(url, user, *items, via="app"):
    got = store.apply_ops(url, user, [dict(op_id=str(uuid.uuid4()), **o) for o in items], via)
    return got["results"]


def entry(i, start, end, title="дело", source="voice", **kw):
    return {"id": i, "start": start, "end": end, "title": title, "category": kw.pop("category", "Работа: текущая"),
            "source": source, "created_at": start, **kw}


def handed(url, n=None):
    """Лента владельца на сервере: дело 3 часа назад закончилось час назад, идущее — с часа назад."""
    n = n or now_ms()
    a, b = n - 3 * H, n - H
    r = ops(url, "sasha", {"op": "zas.handover", "device": "pixel", "entries": [
        entry(a, a, b, title="работа", raw="начал работу"), entry(b, b, 0, title="чтение", category="Чтение")],
        "categories": [{"name": "Чтение", "hint": "", "base_min": 45, "value": 6}], "clients": ["Бета"]})
    assert r[0]["ok"], r
    return a, b, r[0]["zas"]


def archive_day(conn, day):
    r = conn.execute("SELECT data, device FROM core.records WHERE person = '' AND kind = 'zasechka.day' AND key = %s "
                     "AND NOT deleted", (day,)).fetchone()
    return {"data": r[0], "device": r[1]}


def test_before_handover_everything_as_before(dela):
    r = ops(dela, "sasha", {"op": "zas.op", "kind": "web_start", "args": {"title": "тизер"}})
    assert not r[0]["ok"] and "ещё на телефоне" in r[0]["error"]
    with dela_db.session(dela, "sasha") as c:
        assert zas.for_sync(c, "sasha") == {"mode": "phone"}
        assert zas.for_sync(c, "natasha") is None


def test_handover_publishes_days_and_sync_window(dela, conn):
    a, b, z = handed(dela)
    assert z["handed"] and z["entries"] == 2 and z["mode"] == "server"
    assert {e["id"] for e in z["window"]} == {a, b}
    rec = archive_day(conn, day_of(b))
    assert rec["device"] == "svc-zasechka"
    titles = [e["title"] for e in rec["data"]["entries"]]
    assert "чтение" in titles
    # Справочник — тоже в архив: категории с передачи (Свода нет), клиенты.
    ref = conn.execute("SELECT data FROM core.records WHERE kind = 'zasechka.reference' AND key = 'all'").fetchone()[0]
    assert ref["clients"] == ["Бета"] and ref["categories"][0]["name"] == "Чтение"
    # life.entries — тем же видом, что от телефона.
    rows = conn.execute("SELECT title, open FROM life.entries WHERE day = %s", (day_of(b),)).fetchall()
    assert ("чтение", True) in {(r[0], r[1]) for r in rows}
    with dela_db.session(dela, "sasha") as c:
        s = zas.for_sync(c, "sasha", None)
        assert s["mode"] == "server" and {e["id"] for e in s["entries"]} == {a, b}
        assert "entries" not in zas.for_sync(c, "sasha", s["lrev"])   # версия та же — окна нет
    # Повтор тем же телефоном (ответ потерялся) — безвреден; другой телефон — отказ словами.
    again = ops(dela, "sasha", {"op": "zas.handover", "device": "pixel", "entries": [entry(1, a, b)]})
    assert again[0]["ok"] and again[0]["zas"]["handed"] is False
    other = ops(dela, "sasha", {"op": "zas.handover", "device": "fold", "entries": [entry(1, a, b)]})
    assert not other[0]["ok"] and "уже на сервере" in other[0]["error"]


def test_empty_handover_refused(dela):
    r = ops(dela, "sasha", {"op": "zas.handover", "device": "pixel", "entries": []})
    assert not r[0]["ok"] and "пустая" in r[0]["error"]


def test_web_start_stop_edit_are_immediate(dela, conn):
    a, b, _ = handed(dela)
    r = ops(dela, "sasha", {"op": "zas.op", "kind": "web_start", "args": {"title": "Тизер", "category": "чтение"}})
    assert r[0]["ok"], r
    new = r[0]["zas"]["result"]
    assert new["title"] == "Тизер" and new["end"] == 0 and new["category"] == "Чтение"   # категория — как в справочнике
    with dela_db.session(dela, "sasha") as c:
        assert c.execute("SELECT end_ms FROM crm.zas_entries WHERE id = %s", (b,)).fetchone()["end_ms"] > 0
    # То же нажатие ещё раз (обрыв связи) — не второе дело.
    again = ops(dela, "sasha", {"op": "zas.op", "kind": "web_start", "args": {"title": "Тизер"},
                                "at": None})
    assert again[0]["ok"]
    stop = ops(dela, "sasha", {"op": "zas.op", "kind": "web_stop", "args": {"entry_id": new["id"]}})
    assert stop[0]["ok"] and stop[0]["zas"]["result"]["end"] > 0
    # «Поправить» работу: название; запись, как её видел веб (was), совпадает.
    with dela_db.session(dela, "sasha") as c:
        w = c.execute("SELECT start_ms, end_ms FROM crm.zas_entries WHERE id = %s", (a,)).fetchone()
    hm = zas._hm
    ed = ops(dela, "sasha", {"op": "zas.op", "kind": "web_edit", "args": {
        "entry_id": a, "day": day_of(a), "was": {"title": "работа", "start": hm(w["start_ms"]), "end": hm(w["end_ms"])},
        "set": {"title": "работа: отчёт"}}})
    assert ed[0]["ok"], ed
    assert ed[0]["zas"]["result"]["title"] == "работа: отчёт" and ed[0]["zas"]["result"]["source"] == "edit"
    rec = archive_day(conn, day_of(a))
    assert "работа: отчёт" in [e["title"] for e in rec["data"]["entries"]]
    # Тот же «Поправить» по устаревшему виду — отказ словами, а не затирание.
    stale = ops(dela, "sasha", {"op": "zas.op", "kind": "web_edit", "args": {
        "entry_id": a, "day": day_of(a), "was": {"title": "работа", "start": hm(w["start_ms"]), "end": hm(w["end_ms"])},
        "set": {"title": "другое"}}})
    assert not stale[0]["ok"] and "уже поправили" in stale[0]["error"]
    # Журнал операций — только дописывается.
    with dela_db.session(dela, "sasha") as c:
        kinds = [r["kind"] for r in c.execute("SELECT kind FROM crm.zas_ops ORDER BY n").fetchall()]
        assert kinds[0] == "handover" and "web_start" in kinds and "web_edit" in kinds
        with pytest.raises(Exception):
            c.execute("DELETE FROM crm.zas_ops")


def test_phone_mutators_and_was_lookup(dela):
    a, b, _ = handed(dela)
    n = now_ms()
    # Телефон завёл дело у себя с номером 7777 и шлёт его — номер берётся.
    r = ops(dela, "sasha", {"op": "zas.op", "kind": "start", "device": "pixel", "at": n,
                            "args": {"start": n - 10 * 60_000, "title": "звонок клиенту", "source": "voice", "id": 7777}})
    assert r[0]["ok"] and r[0]["zas"]["result"]["id"] == 7777
    assert any(e["id"] == 7777 for e in r[0]["zas"]["window"])
    # Правка по номеру, которого сервер не знает (кусок телефона), — по началу и названию.
    upd = ops(dela, "sasha", {"op": "zas.op", "kind": "update", "device": "pixel", "args": {
        "entry": {**entry(99, n - 10 * 60_000, 0, title="звонок Бете"), "category": "Работа: звонки"},
        "was": {"start": n - 10 * 60_000, "title": "звонок клиенту"}}})
    assert upd[0]["ok"], upd
    assert upd[0]["zas"]["result"]["id"] == 7777 and upd[0]["zas"]["result"]["title"] == "звонок Бете"
    miss = ops(dela, "sasha", {"op": "zas.op", "kind": "delete", "device": "pixel", "args": {"id": 12345}})
    assert not miss[0]["ok"] and "нет" in miss[0]["error"]
    cm = ops(dela, "sasha", {"op": "zas.op", "kind": "comment", "device": "pixel", "args": {"id": a, "comment": "отчёт сдан"}})
    assert cm[0]["ok"] and cm[0]["zas"]["result"] is True
    cl = ops(dela, "sasha", {"op": "zas.op", "kind": "close", "device": "pixel", "args": {"at": n}})
    assert cl[0]["ok"] and cl[0]["zas"]["result"]["end"] == n


def test_archive_refuses_phone_ledger_after_handover(dela, conn):
    from pravka_archive.ingest import ingest_batch

    a, b, _ = handed(dela)
    day = day_of(b)
    before = archive_day(conn, day)
    batch = {"device": "sasha-3f9a2c", "profile": "sasha", "app": "2.0.900", "schema": 1, "events": [
        {"eid": "sasha-3f9a2c:1", "seq": 1, "kind": "zasechka.day", "key": day, "op": "put",
         "at": "2030-01-01T00:00:00.000+03:00", "data": {"day": day, "entries": []}},
        {"eid": "sasha-3f9a2c:2", "seq": 2, "kind": "money.take", "key": "t1", "op": "put",
         "at": "2030-01-01T00:00:00.000+03:00", "data": {"text": "кофе 300"}}]}
    status, res = ingest_batch(conn, batch, "sasha")
    assert status == 200
    assert [r["eid"] for r in res["rejected"]] == ["sasha-3f9a2c:1"] and "на сервере" in res["rejected"][0]["why"]
    assert archive_day(conn, day) == before   # сутки сервера не затёрты


def test_tick_grows_live_filler_and_only_owner(dela):
    n = now_ms()
    a = n - 3 * H
    r = ops(dela, "sasha", {"op": "zas.handover", "device": "pixel", "entries": [entry(a, a, n - 2 * H, title="работа")]})
    assert r[0]["ok"]
    with dela_db.session(dela, "system", via="system") as c:
        got = zas.tick(c)
    assert got and got["days"]
    with dela_db.session(dela, "sasha") as c:
        live = c.execute("SELECT * FROM crm.zas_entries WHERE source = 'gap' AND end_ms = 0").fetchone()
        assert live and live["start_ms"] == n - 2 * H and live["title"] == "не размечено"
    nope = ops(dela, "natasha", {"op": "zas.op", "kind": "web_start", "args": {"title": "чужое"}})
    assert not nope[0]["ok"] and "только владельцу" in nope[0]["error"]
