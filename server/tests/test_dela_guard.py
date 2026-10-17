"""Защита от петли Claude на сервере Дел (аудит 15.10.2026, №12): рубильник, сообщения владельцу ботом Ковчега,
сторож суммы дня, суточный предел не-владельцу. Claude не зовём — клиент Anthropic подменён."""

from __future__ import annotations

import re
import time
from pathlib import Path
from types import SimpleNamespace as NS

import pytest

from pravka_dela import guard, parse, remind, spend, store, tokens
from pravka_dela import db as dela_db
from test_dela_schema import dela  # noqa: F401  (фикстура)


@pytest.fixture(autouse=True)
def fresh_caps():
    """Пороги — заводские в начале каждого теста (кэш Свода в процессе)."""
    guard._caps.update(at=-guard.CAPS_EVERY, value=None)
    guard._errors.clear()
    yield
    guard._caps.update(at=-guard.CAPS_EVERY, value=None)
    guard._errors.clear()


class FakeAnthropic:
    """Как SDK: messages.create/stream, beta.messages.create/stream, messages.batches.create/retrieve/results/cancel."""

    def __init__(self):
        self.calls: list[str] = []
        log = self.calls

        class Batches:
            def create(self, **k):
                log.append("batches.create")
                return NS(id="msgbatch_1")

            def retrieve(self, bid):
                log.append("batches.retrieve")
                return NS(processing_status="ended")

            def results(self, bid):
                log.append("batches.results")
                return iter(())

            def cancel(self, bid):
                log.append("batches.cancel")

        class Messages:
            def __init__(self, where):
                self.where, self.batches = where, Batches()

            def create(self, **k):
                log.append(f"{self.where}.create")
                return NS(content=[])

            def stream(self, **k):
                log.append(f"{self.where}.stream")
                return NS()

        self.messages = Messages("messages")
        self.beta = NS(messages=Messages("beta"))
        self.api_key = "sk-test"


def ops(url, user, *items, actor=None):
    return store.apply_ops(url, user, list(items), "web", actor)["results"]


def test_wrapper_counts_paid_calls_only(dela):
    f = FakeAnthropic()
    c = guard.wrap(f, dela, "ask")
    c.messages.create(model="x")
    c.messages.stream(model="x")
    c.beta.messages.create(model="x")
    c.beta.messages.stream(model="x")
    c.messages.batches.create(requests=[])
    c.messages.batches.retrieve("b")
    list(c.messages.batches.results("b"))
    c.messages.batches.cancel("b")
    assert c.api_key == "sk-test"  # всё прочее — насквозь
    assert f.calls == ["messages.create", "messages.stream", "beta.create", "beta.stream", "batches.create",
                       "batches.retrieve", "batches.results", "batches.cancel"]
    assert guard.status(dela)["hits_hour"] == 5


def test_task_threshold_stops_that_task_once(dela):
    now = time.time()
    for i in range(30):
        guard.hit(dela, "ask", now + i)
    with pytest.raises(guard.Stopped) as e:
        guard.hit(dela, "ask", now + 30)
    assert "строка Claude и разговор: 31 вызов за 10 минут, порог 30" in str(e.value) and "Продолжать" in str(e.value)
    with pytest.raises(guard.Stopped):
        guard.hit(dela, "ask", now + 31)
    guard.hit(dela, "parse", now + 32)  # другая задача работает
    alerts = guard.alerts_due(dela, "https://dela.example.org", now + 33)
    assert [(a["kind"], a["task"]) for a in alerts] == [("guard", "ask")]
    assert [b["data"] for b in alerts[0]["buttons"]] == ["guard:dela:ask:on", "guard:dela:ask:keep"]
    assert "защита от петли" in alerts[0]["text"]
    # Пороги за час — свои: 100 у ask за час, а не за 10 минут.
    for i in range(29):
        guard.hit(dela, "when", now + i * 60)  # 29 вызовов за полчаса — меньше 40 за 10 минут
    assert not guard.blocked(dela, "when")


class Refused(Exception):
    def __init__(self, text: str, code: int | None = None):
        super().__init__(text)
        if code is not None:
            self.status_code = code


class APIConnectionError(Exception):
    """Имя — как у SDK: reason узнаёт отказ по имени класса, SDK не импортирует."""


def test_refusals_named_in_stop_message_and_counting_unchanged(dela):
    """17.10.2026: кончились деньги — «разборщик встреч: 7 вызовов за 10 минут» без слова о причине. Считать попытки
    защита продолжает (это защита), но сообщение говорит, что Anthropic отказывал и чем."""
    f = FakeAnthropic()

    def no_money(**k):
        f.calls.append("batches.create")
        raise Refused("Error code: 400 - Your credit balance is too low to access the Anthropic API.", 400)

    f.messages.batches.create = no_money
    c = guard.wrap(f, dela, "meetings")
    for _ in range(6):
        with pytest.raises(Refused):
            c.messages.batches.create(requests=[])
    with pytest.raises(guard.Stopped) as e:
        c.messages.batches.create(requests=[])
    assert "разборщик встреч: 7 вызовов за 10 минут, порог 6" in str(e.value) and len(f.calls) == 6
    [al] = guard.alerts_due(dela)
    assert ("Из них Anthropic отказал 6 раз: на счёте Anthropic нет денег — это не петля в коде, а повторы после "
            "отказа.") in al["text"] and "Задача стоит, деньги не тратятся." in al["text"]
    [st] = guard.status(dela)["stopped"]
    assert st["cause"].startswith("Из них Anthropic отказал 6 раз")
    # Повтор вопроса через 6 часов — с той же причиной.
    again = guard.alerts_due(dela, "", time.time() + 6 * 3600 + 10)
    assert any("на счёте Anthropic нет денег" in a["text"] and "всё ещё стоит" in a["text"] for a in again)
    # Без отказов — без строки о них: петля так петля.
    now = time.time()
    for i in range(4):
        guard.hit(dela, "zachem", now + i)
    with pytest.raises(guard.Stopped):
        guard.hit(dela, "zachem", now + 4)
    z = [a for a in guard.alerts_due(dela) if a["task"] == "zachem"][0]
    assert "Anthropic отказал" not in z["text"]


def test_reason_in_words():
    assert guard.reason(Refused("Your credit balance is too low", 400)) == "на счёте Anthropic нет денег"
    assert guard.reason(Refused("invalid x-api-key", 401)) == "ключ Anthropic не принят"
    assert guard.reason(Refused("rate_limit_error", 429)) == "Anthropic просит реже (лимит запросов)"
    assert guard.reason(Refused("overloaded_error", 529)) == "Anthropic перегружен"
    assert guard.reason(Refused("api_error", 500)) == "сбой на стороне Anthropic (500)"
    assert guard.reason(APIConnectionError("Connection error.")) == "нет связи с Anthropic"
    assert guard.reason(ValueError("что-то\n  своё")) == "ValueError: что-то своё"


def test_total_stops_every_task(dela):
    now = time.time()
    for i in range(40):
        guard.hit(dela, "when", now + i)
    for i in range(30):
        guard.hit(dela, "ask", now + 40 + i)
    for i in range(10):
        guard.hit(dela, "parse", now + 70 + i)
    with pytest.raises(guard.Stopped) as e:
        guard.hit(dela, "zachem", now + 81)
    assert "все задачи Дел" in str(e.value) and e.value.task == "*"
    assert guard.blocked(dela, "krug") and guard.blocked(dela, "ask")


def test_resume_triples_for_an_hour_and_who_may_answer(dela):
    now = time.time()
    for i in range(31):
        try:
            guard.hit(dela, "ask", now + i)
        except guard.Stopped:
            pass
    assert guard.blocked(dela, "ask")
    bad = ops(dela, "natasha", {"op": "claude.guard", "action": "resume", "task": "ask"})[0]
    assert not bad["ok"] and "только владелец" in bad["error"]
    svc = ops(dela, "sasha", {"op": "claude.guard", "action": "resume", "task": "ask"}, actor="svc:meetings")[0]
    assert not svc["ok"]  # служба с токеном владельца — не владелец
    ok = ops(dela, "sasha", {"op": "claude.guard", "action": "resume", "task": "ask"})[0]
    assert ok["ok"] and ok["action"] == "resume" and not guard.blocked(dela, "ask")
    later = time.time()
    for i in range(90):  # втрое: 30 × 3
        guard.hit(dela, "ask", later + i)
    with pytest.raises(guard.Stopped):
        guard.hit(dela, "ask", later + 90)
    # Кнопку в Telegram передаёт бот Ковчега (svc:kovcheg) — можно; «Оставить» — стоит, вопрос отвечен.
    keep = ops(dela, "sasha", {"op": "claude.guard", "action": "keep", "task": "ask"}, actor="svc:kovcheg")[0]
    assert keep["ok"] and guard.blocked(dela, "ask")
    assert not [a for a in guard.alerts_due(dela) if a["task"] == "ask"]


def test_state_survives_restart_and_alerts_go_through_due(dela):
    now = time.time()
    for i in range(7):
        try:
            guard.hit(dela, "krug", now + i)
        except guard.Stopped:
            pass
    guard._caps.update(at=-guard.CAPS_EVERY, value=None)  # «перезапуск» процесса: кэша нет, состояние — в базе
    assert guard.blocked(dela, "krug") and guard.status(dela)["stopped"][0]["task"] == "krug"
    due = remind.due(dela, "https://dela.example.org")
    [al] = due["alerts"]
    assert al["task"] == "krug" and al["kind"] == "guard"
    bad = ops(dela, "sasha", {"op": "claude.alert_sent", "id": al["id"], "message_id": 5})[0]
    assert not bad["ok"]  # отмечает только бот Ковчега
    ok = ops(dela, "sasha", {"op": "claude.alert_sent", "id": al["id"], "message_id": 5}, actor="svc:kovcheg")[0]
    assert ok["ok"] and remind.due(dela, "https://dela.example.org")["alerts"] == []
    # Стоит без ответа 6 часов — ещё одно сообщение, не чаще раза в сутки.
    again = guard.alerts_due(dela, "", now + 6 * 3600 + 10)
    assert [a["task"] for a in again] == ["krug"] and "всё ещё стоит" in again[0]["text"]
    assert len(guard.alerts_due(dela, "", now + 13 * 3600)) == 1  # тот же, не новый


def test_caps_from_svod_and_crooked_falls_back(dela):
    ops(dela, "sasha", {"op": "svod.set", "key": "claude.caps", "value": {"rate": {"dela": {"ask": [2, 5]}}}, "author": "t"})
    now = time.time()
    guard.hit(dela, "ask", now)
    guard.hit(dela, "ask", now + 1)
    with pytest.raises(guard.Stopped):
        guard.hit(dela, "ask", now + 2)
    crooked = guard.merge_caps({"rate": {"dela": {"ask": "много"}}, "relax": -1})
    assert guard.limits(crooked, "ask") == ([30, 100], [80, 250]) and crooked["relax"] == 3
    assert guard.merge_caps("кривое") == guard.FACTORY


def test_parse_and_when_stopped_in_words(dela, monkeypatch):
    from pravka_dela import when

    monkeypatch.setattr(parse, "client", lambda key, proxy: FakeAnthropic())
    now = time.time()
    for i in range(31):
        try:
            guard.hit(dela, "parse", now + i)
        except guard.Stopped:
            pass
    with pytest.raises(guard.Stopped) as e:
        parse.run(dela, "sasha", "позвонить в банк", {}, "sk-test", None)
    assert "защитой от петли" in str(e.value)
    for i in range(41):
        try:
            guard.hit(dela, "when", now + i)
        except guard.Stopped:
            pass
    with pytest.raises(when.WhenError, match="защитой от петли"):
        when.understand(dela, "sasha", "когда-нибудь после дождичка", "sk-test", None)


def test_api_parse_stopped_and_user_quota(dela, monkeypatch):
    from starlette.testclient import TestClient

    from pravka_dela import api
    from pravka_dela.config import Config
    from test_dela_ask import _wait

    monkeypatch.setattr(parse, "client", lambda key, proxy: FakeAnthropic())
    now = time.time()
    for i in range(31):
        try:
            guard.hit(dela, "parse", now + i)
        except guard.Stopped:
            pass
    owner = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон-петля')}"}
    team = {"Authorization": f"Bearer {tokens.issue(dela, 'natasha', 'device', 'телефон-наташи')}"}
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        jid = client.post("/api/parse", headers=owner, json={"text": "позвонить в банк"}).json()["job"]
        r = _wait(client, owner, jid)
        assert r["status"] == "error" and "защитой от петли" in r["error"] and "недоступен" not in r["error"]
        # Не-владельцу — 20 разборов в сутки; 21-й — 429 словами.
        monkeypatch.setattr(parse, "run", lambda *a, **k: {"tasks": [], "notes": [], "errors": [], "usage": None})
        for _ in range(20):
            assert client.post("/api/parse", headers=team, json={"text": "x"}).status_code == 202
        r = client.post("/api/parse", headers=team, json={"text": "x"})
        assert r.status_code == 429 and "20 из 20" in r.json()["error"] and "Саша" in r.json()["error"]
        for _ in range(25):  # владелец — без суточного предела
            assert client.post("/api/parse", headers=owner, json={"text": "x"}).status_code == 202
    # Новый день — счёт с нуля.
    with dela_db.session(dela, "system", "t") as c:
        c.execute("UPDATE crm.state SET value = jsonb_set(value, '{day}', '\"2000-01-01\"') WHERE key = %s", (guard.QUOTA,))
    guard.quota(dela, "natasha", "parse")


def test_quota_ask_forty(dela):
    for _ in range(40):
        guard.quota(dela, "natasha", "ask")
    with pytest.raises(guard.OverQuota, match="40 из 40"):
        guard.quota(dela, "natasha", "ask")
    for _ in range(50):
        guard.quota(dela, "sasha", "ask")


def test_day_watch_alerts_once_per_day_and_threshold(dela):
    spend.add(dela, "ask", 2.0, "claude-opus-5-5", None, False)
    assert not [a for a in guard.alerts_due(dela) if a["kind"] == "cap"]
    spend.add(dela, "ask", 1.5, "claude-opus-5-5", None, False)  # Дела за день $3,5 > $3
    caps = [a for a in guard.alerts_due(dela, "https://dela.example.org") if a["kind"] == "cap"]
    assert len(caps) == 1 and "Дела" in caps[0]["text"] and "$3" in caps[0]["text"]
    assert caps[0]["buttons"] == [{"text": "Траты", "url": "https://dela.example.org/#/spend"}]
    spend.add(dela, "ask", 0.5, "claude-opus-5-5", None, False)
    assert len([a for a in guard.alerts_due(dela) if a["kind"] == "cap"]) == 1  # тот же порог за день — один раз
    # Другой день — снова (память сторожа — по дню: вчерашние пороги не в счёт).
    with dela_db.session(dela, "system", "t") as c:
        c.execute("UPDATE crm.state SET value = jsonb_set(value, '{day}', '\"2000-01-01\"') WHERE key = %s", (guard.SEEN,))
        c.execute("UPDATE crm.state SET value = jsonb_set(value, '{items}', '[]') WHERE key = %s", (guard.ALERTS,))
    spend.add(dela, "ask", 0.1, "claude-opus-5-5", None, False)
    assert len([a for a in guard.alerts_due(dela) if a["kind"] == "cap"]) == 1


def test_spend_view_shows_guard_to_owner_only(dela):
    now = time.time()
    for i in range(7):
        try:
            guard.hit(dela, "krug", now + i)
        except guard.Stopped:
            pass
    v = store.view(dela, "sasha", "spend")
    assert [s["task"] for s in v["guard"]["stopped"]] == ["krug"]
    with pytest.raises(store.OpError):
        store.view(dela, "natasha", "spend")


def test_every_claude_road_goes_through_guard():
    """Правило 24 карты: новая дорога к Anthropic мимо guard.client — нельзя. parse.client зовёт только guard."""
    root = Path(guard.__file__).parent
    bad = []
    for f in root.glob("*.py"):
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if line.lstrip().startswith(("def ", "#")):
                continue
            # Клиент Anthropic строится только в parse.client; зовут его guard.client или guard.wrap(…) на той же строке.
            if re.search(r"\bclient\(key, proxy\)", line) and f.name != "guard.py" and "guard.wrap(" not in line:
                bad.append(f"{f.name}:{n}")
            if "anthropic.Anthropic(" in line and f.name != "parse.py":
                bad.append(f"{f.name}:{n} (anthropic.Anthropic)")
    assert not bad, bad


def test_day_watch_after_phone_put(dela):
    """Итог дня телефона (spend.put) за порогом службы ($5) — то же сообщение, что у вызовов самих Дел."""
    from test_dela_schema import db_today

    r = store.apply_ops(dela, "sasha", [{"op": "spend.put", "src": "pixel-test", "rows": [
        {"day": db_today(dela).isoformat(), "service": "phone", "task": "pravka", "model": "claude-sonnet-5-5",
         "calls": 3, "usd": 5.5}]}], "app")["results"][0]
    assert r["ok"], r
    caps = [a for a in guard.alerts_due(dela) if a["kind"] == "cap"]
    assert len(caps) == 1 and "$5,50 (порог $5)" in caps[0]["text"], caps


def test_cli_says_stopped_in_words(dela, tmp_path, monkeypatch, capsys):
    """Команды zachem propose и spend advise при сработавшей защите — ошибка словами и код 1, а не трейсбек (контракт
    §4.3). Claude не зовём: обе дороги подменены остановкой."""
    from pravka_dela import __main__ as cli
    from pravka_dela import spend_advice, zachem

    env = tmp_path / "dela.env"
    env.write_text(f"DELA_DB_URL={dela}\nANTHROPIC_API_KEY=sk-test\n", encoding="utf-8")
    monkeypatch.delenv("DELA_DB_URL", raising=False)

    def stop(*_a, **_k):
        raise guard.Stopped("*", "все задачи Дел: 81 вызов за 10 минут, порог 80")

    monkeypatch.setattr(zachem, "propose", stop)
    monkeypatch.setattr(spend_advice, "send", stop)
    assert cli.main(["--env", str(env), "zachem", "propose", "--out", str(tmp_path / "z.json")]) == 1
    assert cli.main(["--env", str(env), "spend", "advise", "--day", "2026-10-11"]) == 1
    said = capsys.readouterr().out
    assert said.count("Claude остановлен защитой от петли (все задачи Дел") == 2 and "Traceback" not in said, said
    assert not (tmp_path / "z.json").exists()
