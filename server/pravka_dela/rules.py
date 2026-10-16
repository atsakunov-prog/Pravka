"""Общие правила дел и выключатель «Только тебе» — Свод (16.10.2026, этап 6 аудита, №20; слово владельца «да. Делай»).

Правила, по которым любой разбор заводит, закрывает и уточняет дела (что дело и что нет, «Кто: действие», группы,
обещания обеих сторон, клиент и сделка, срок, заметка и основание, дубль, семья, «только Саше», когда спрашивать), жили
шестью разными текстами в шести промптах (сверка и разбор встреч, разноска дайджеста, правка словами, строка Claude Дел,
наговор) и расходились. Теперь они — одна запись Свода rules.dela: службы подставляют её вместо {RULES}, правило
меняется одной правкой Свода, без выката.

- Записи нет — заводской текст server/contract/prompts/rules-dela.txt (обезличенный: репозиторий публичный, №71). Вид
  svod?key=rules.dela отдаёт его сам записью с factory: true и rev 0 — службам компа (встречи, дайджест) не нужен
  клон репозитория, чтобы взять запас, и «записи нет» не выглядит пустыми правилами.
- Пишет владелец: claude по его слову (svod set, с base_rev), первым знакомством — телефон (seed). Службы с токеном
  владельца (встречи, дайджест, бот) правила читают, но не пишут: иначе разбор переписал бы правила, по которым
  разбирает; тюнер телефона — тоже нет (его семейство — prompt.*). Фигурных скобок в тексте нет: его вставляют в
  промпты, где {TEAM}, {RULES} и {DONE} — подстановки, и лишняя скобка сломала бы чужой формат.

Выключатель meetings.lens — какие виды «Только тебе» выключены: {"off": ["hedge", "digest:cooling"], "on": true}. Вид
без приставки выключен у всех источников (разбор встречи и сводка дайджеста), «meeting:<вид>» и «digest:<вид>» — у
одного; on: false — «Только тебе» выключено целиком. Нет записи — всё включено. Его пишет владелец после мерила
(вид lens в insights.py: через 4 недели вид с «неверно» больше 10 % или «пригодилось» меньше 30 % — подсказка
suggest_off), читают службы встреч и дайджеста раз в час, как people.team.
"""

from __future__ import annotations

import re
from pathlib import Path

KEY = "rules.dela"
LENS_KEY = "meetings.lens"
FACTORY = Path(__file__).resolve().parents[1] / "contract" / "prompts" / "rules-dela.txt"
BODY_MAX = 40_000          # ~13,4 тыс. знаков сейчас; втрое — запас на рост, а не на чужой текст по ошибке
LENS_MAX = 60              # видов в списке off: у встреч их 12, у дайджеста 7 — с приставками и запасом
LENS_ITEM = re.compile(r"^(?:(meeting|digest):)?[a-z][a-z0-9_]{0,23}$")  # вид — как crm.insights.kind
LENS_FIELDS = {"off", "on"}
# Кто не пишет правила и выключатель: тюнер телефона (его — prompt.*), автоматы Дел.
NOT_AUTHORS = {"tuner", "krug", "night"}


def factory_text() -> str:
    """Заводской текст правил (файл контракта). Нет файла — пусто: сервер собран без contract/prompts — службы тогда
    берут свой запас, а не падают."""
    try:
        return FACTORY.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def factory_record(owner: str) -> dict:
    """Запись Свода, которой ещё нет, — заводской текст в виде записи (вид svod?key=rules.dela): rev 0 — «записи не
    было», с ним же svod.set примет первую версию поверх заводской (base_rev 0)."""
    return {"owner_id": owner, "key": KEY, "body": factory_text(), "value": None, "author": "factory",
            "reason": "заводской текст: server/contract/prompts/rules-dela.txt", "updated_at": None, "rev": 0, "seq": 0,
            "factory": True}


def text(conn, owner: str) -> dict:
    """Правила владельца сейчас: {body, rev, factory} — запись Свода или заводской текст (rev 0)."""
    row = conn.execute("SELECT body, rev FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, KEY)).fetchone()
    if row and (row["body"] or "").strip():
        return {"body": row["body"], "rev": row["rev"], "factory": False}
    return {"body": factory_text(), "rev": 0, "factory": True}


def lens(conn, owner: str) -> dict | None:
    """Выключатель meetings.lens владельца или None (записи нет — всё включено)."""
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, LENS_KEY)).fetchone()
    return row["value"] if row and isinstance(row["value"], dict) else None


def is_off(value: dict | None, source: str, kind: str) -> bool:
    """Выключен ли вид у источника по значению meetings.lens."""
    if not value:
        return False
    if value.get("on") is False:
        return True
    off = set(value.get("off") or [])
    return kind in off or f"{source}:{kind}" in off


def _owner_hand(conn, owner: str, key: str, author: str, error) -> None:
    """Правила и выключатель пишет владелец — сам (веб, телефон) или claude по его слову; не служба и не автомат."""
    role = conn.execute("SELECT role FROM crm.users WHERE id = %s", (owner,)).fetchone()
    if not role or role["role"] != "owner":
        raise error(f"свод: {key} — правила владельца Дел, пишет только он")
    actor = str(conn.execute("SELECT crm.actor() AS a").fetchone()["a"] or "")
    if actor.startswith("svc:") or author in NOT_AUTHORS:
        raise error(f"свод: {key} пишет владелец (claude по его слову) — не служба и не автомат ({actor or author})")


def check_set(conn, owner: str, key: str, body, value, author: str, error):
    """Проверка записи rules.dela и meetings.lens до svod.set: (body, value) приведённые или отказ словами."""
    _owner_hand(conn, owner, key, author, error)
    if key == KEY:
        if body is None or value is not None:
            raise error("свод: rules.dela — текст (body), а не JSON")
        body = body.replace("\r\n", "\n").strip()
        if not body:
            raise error("свод: rules.dela пустым не бывает — без правил служба возьмёт заводской текст сама")
        if len(body) > BODY_MAX:
            raise error(f"свод: rules.dela длиннее {BODY_MAX} знаков — это уже не правила")
        if "{" in body or "}" in body:
            raise error("свод: в rules.dela нет фигурных скобок — текст вставляют в промпты, где {TEAM} и {RULES} — "
                        "подстановки")
        return body, None
    return None, check_lens(value, error, body)


def check_lens(value, error, body=None) -> dict:
    """meetings.lens — {"off": [вид…], "on": true|false}: виды латиницей, «meeting:» или «digest:» — у одного источника."""
    if body is not None or not isinstance(value, dict):
        raise error('свод: meetings.lens — JSON {"off": ["hedge", "digest:cooling"]} (выключенные виды «Только тебе»)')
    extra = set(value) - LENS_FIELDS
    if extra:
        raise error(f"свод: meetings.lens — нет таких полей: {', '.join(sorted(extra))} (есть off, on)")
    off = value.get("off", [])
    if not isinstance(off, list) or len(off) > LENS_MAX:
        raise error(f"свод: meetings.lens.off — список видов, до {LENS_MAX}")
    clean: list[str] = []
    for x in off:
        k = " ".join(str(x).split()).lower() if isinstance(x, str) else ""
        if not LENS_ITEM.match(k):
            raise error(f"свод: meetings.lens — вид «{x}» не годится: латиница (hedge), у одного источника — "
                        "meeting:hedge или digest:cooling")
        if k not in clean:
            clean.append(k)
    out: dict = {"off": clean}
    if "on" in value:
        if not isinstance(value["on"], bool):
            raise error("свод: meetings.lens.on — true или false")
        out["on"] = value["on"]
    return out
