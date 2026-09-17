#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Правка на компе — ядро без окон, клавиш и сети.

Здесь лежит ровно то, что телефон делает с надиктованным текстом ПОСЛЕ
распознавания и до вставки: словарь (HARD-замены до модели, блок {DICT} из
совпавших HINT/PROTECT), голосовые команды абзаца, сборка промпта с точкой
кэша на стабильном префиксе, форма запроса по модели и усилию, чистка ответа
и цена. Каждая функция — перенос одноимённого куска Kotlin из приложения:

  prepare_dictionary   <- core/DictionaryApplier.kt
  apply_voice_commands <- core/VoiceCommands.kt
  assemble             <- core/Prompts.kt (assemble)
  clean_response       <- core/ResponseCleaner.kt
  thinking_off/headroom<- provider/RequestPolicy.kt
  build_request        <- provider/ClaudeProvider.kt (request)
  cost_usd             <- provider/Pricing.kt

Почему перенос, а не общий код: словарь и правила на телефоне лежат под
Android Context и DataStore, а выносить модуль core ради утилиты на триста
строк — рефакторинг, который проверяется только на Fold. Паритет держат тесты
(test_pravka_comp_core.py): те же случаи, что и в JVM-тестах приложения.

Источник словаря, правил и промптов — файл, который приложение собирает
кнопкой «Файл для компа» (вкладка Промпты, data/CompBundle.kt). Телефон —
хозяин данных, комп только читает; обучение остаётся на телефоне.

Без сторонних зависимостей: это ядро гоняется тестами где угодно.
"""
from __future__ import annotations

import glob
import json
import os
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

MODEL_SONNET = "claude-sonnet-5"
MODEL_OPUS = "claude-opus-5"
MODEL_FABLE = "claude-fable-5-1"

PLACEHOLDER_INPUT = "{INPUT}"
PLACEHOLDER_DICT = "{DICT}"

BUNDLE_FORMAT = "pravka-comp"
# Как на телефоне: короче этого — случайный тап, а не текст для правки.
MIN_INPUT_LENGTH = 15


# ---------------------------------------------------------------- словарь

@dataclass
class DictEntry:
    id: int
    from_: str
    to: str
    mode: str          # HARD | HINT | PROTECT
    note: str = ""
    enabled: bool = True


@dataclass
class Prepared:
    text: str
    dict_block: str
    fired_ids: List[int]


def _is_cyrillic(ch: str) -> bool:
    return "CYRILLIC" in unicodedata.name(ch, "")


def boundary_regex(from_: str, with_russian_endings: bool) -> Optional[re.Pattern]:
    """
    Границы слова через lookaround: \\b ненадёжен для кириллицы. В Kotlin —
    [\\p{L}\\p{N}]; в Python это «буква или цифра» = \\w без подчёркивания.
    Допуск окончаний — только для ПОИСКА (HINT/PROTECT), никогда для
    HARD-замены: «осу» -> «осуди» с проглоченным суффиксом было бы катастрофой.
    """
    trimmed = from_.strip()
    if not trimmed:
        return None
    suffix = "[а-яёА-ЯЁ]{0,3}" if with_russian_endings and _is_cyrillic(trimmed[-1]) else ""
    try:
        return re.compile(
            r"(?<![^\W_])" + re.escape(trimmed) + suffix + r"(?![^\W_])",
            re.IGNORECASE | re.UNICODE,
        )
    except re.error:
        return None


def preserve_first_case(matched: str, replacement: str) -> str:
    if not replacement:
        return replacement
    if matched[0].isupper() and replacement[0].islower():
        return replacement[0].upper() + replacement[1:]
    return replacement


def prepare_dictionary(text_in: str, entries: Sequence[DictEntry]) -> Prepared:
    """HARD — заменить до модели; HINT/PROTECT, встретившиеся в тексте, — в блок {DICT}."""
    active = [e for e in entries if e.enabled]
    text = text_in
    fired: List[int] = []

    # Длинные первыми: многословный вариант побеждает свою же подстроку.
    for e in sorted((e for e in active if e.mode == "HARD"), key=lambda e: len(e.from_), reverse=True):
        rx = boundary_regex(e.from_, with_russian_endings=False)
        if rx is None:
            continue
        if rx.search(text):
            text = rx.sub(lambda m, to=e.to: preserve_first_case(m.group(0), to), text)
            fired.append(e.id)

    hints = [
        e for e in active
        if e.mode == "HINT" and (boundary_regex(e.from_, True) or _NEVER).search(text)
    ]
    protects = [
        e for e in active
        if e.mode == "PROTECT" and (boundary_regex(e.from_, True) or _NEVER).search(text)
    ]
    fired += [e.id for e in hints]
    fired += [e.id for e in protects]
    return Prepared(text, _build_dict_block(hints, protects), fired)


_NEVER = re.compile(r"(?!x)x")  # регэксп, который не совпадает ни с чем


def _build_dict_block(hints: Sequence[DictEntry], protects: Sequence[DictEntry]) -> str:
    if not hints and not protects:
        return ""
    out: List[str] = []
    if hints:
        out.append("Учти при правке:\n")
        for h in hints:
            line = '- "' + h.from_ + '"'
            if h.note.strip():
                line += " (" + h.note + ")"
            if h.to.strip():
                line += " — это " + h.to
            out.append(line + "\n")
    if protects:
        out.append("Не исправляй эти слова, они написаны верно:\n")
        out.append(", ".join(p.from_ for p in protects) + "\n")
    return "".join(out).strip()


# ------------------------------------------------------- голосовые команды

_NEW_PARAGRAPH = re.compile(
    r"\s*[,.]?\s*\b(с новой строки|новая строка|новый абзац|абзац)\b[,.]?\s*",
    re.IGNORECASE,
)


def apply_voice_commands(text: str) -> str:
    """«С новой строки», «абзац» — настоящие переносы до модели: мгновенно и бесплатно."""
    out = _NEW_PARAGRAPH.sub("\n", text)
    return out.strip("\n ")


# ----------------------------------------------------------- сборка промпта

@dataclass
class PromptParts:
    stable_prefix: str
    dict_part: str
    after_input: str
    cache_stable_always: bool = False

    @property
    def before_input(self) -> str:
        return self.stable_prefix + self.dict_part


_TAGGED_DICT = re.compile(r"<словарь>\n*\{DICT\}\n*</словарь>\n*")
_BARE_DICT = re.compile(r"\n*\{DICT\}\n*")


def assemble(
    template: str,
    dict_block: str,
    directive: str = "",
    context: str = "",
    conversation: str = "",
) -> PromptParts:
    """
    Делит шаблон по {DICT} и {INPUT}. Всё до {DICT} байт в байт одинаково между
    запросами — туда встаёт точка кэша; словарь, задание и контекст едут в
    переменном хвосте. Теги <словарь> уезжают ВМЕСТЕ с содержимым в переменную
    часть, иначе задание оказывалось внутри тегов словаря.
    """
    input_idx = template.find(PLACEHOLDER_INPUT)
    before = template[:input_idx] if input_idx >= 0 else template
    after = template[input_idx + len(PLACEHOLDER_INPUT):].rstrip() if input_idx >= 0 else ""

    dict_ = "" if not dict_block.strip() else dict_block.strip() + "\n\n"
    extras = ""
    if directive.strip():
        extras += "ДОПОЛНИТЕЛЬНОЕ ЗАДАНИЕ ПОВЕРХ ПРАВКИ:\n" + directive.strip() + "\n\n"
    if conversation.strip():
        extras += (
            "Ниже в тегах <разговор> — предыдущие сообщения автора в этом же "
            "чате. Используй их, чтобы понять, о чём идёт речь, выдержать тон и "
            "правильно согласовать род и имена (автор — мужчина, «говорил», а не "
            "«говорила»). Сами сообщения не правь и в ответ не включай.\n"
            "<разговор>\n" + conversation.strip() + "\n</разговор>\n\n"
        )
    if context.strip():
        extras += (
            "Перед текстом для правки в поле уже стоит текст (ниже "
            "в тегах <контекст>). Используй его ТОЛЬКО чтобы правильно "
            "выбрать заглавную или строчную букву и пунктуацию на стыке. "
            "Контекст не правь и в ответ не включай.\n"
            "<контекст>\n" + context + "\n</контекст>\n\n"
        )

    tagged = _TAGGED_DICT.search(before)
    if tagged:
        stable = before[:tagged.start()].strip() + "\n\n"
        rest = before[tagged.end():].lstrip()
        tail = rest if input_idx >= 0 else rest.rstrip() + "\n\n"
        tagged_dict = "<словарь>\n" + dict_block.strip() + "\n</словарь>\n\n"
        return PromptParts(stable, tagged_dict + extras + tail, after)
    bare = _BARE_DICT.search(before)
    if bare:
        stable = before[:bare.start()].strip() + "\n\n"
        rest = before[bare.end():].lstrip()
        tail = rest if input_idx >= 0 else rest.rstrip() + "\n\n"
        return PromptParts(stable, dict_ + extras + tail, after)
    # Без {DICT} подсказки в промпт не попадают (как предупреждает редактор
    # промпта на телефоне), но задание и контекст не теряются никогда.
    stable = before if input_idx >= 0 else before.rstrip() + "\n\n"
    return PromptParts(stable, extras, after)


# ---------------------------------------------------------- чистка ответа

_PREAMBLE = re.compile(
    r"^\s*(вот исправленный текст|исправленный вариант|вот исправленный вариант|исправленный текст)\s*:?\s*",
    re.IGNORECASE,
)
_QUOTE_PAIRS = [('"', '"'), ("«", "»"), ("“", "”")]


def clean_response(raw: str, original: str, lenient: bool = False) -> Optional[str]:
    """None — ответ испорчен (модель съела абзац), текст трогать нельзя."""
    text = raw.strip()
    if text.startswith("```") and text.endswith("```") and len(text) > 6:
        text = text[3:-3]
        nl = text.find("\n")
        text = (text[nl + 1:] if nl >= 0 else text).strip()
    text = _PREAMBLE.sub("", text, count=1).strip()
    orig = original.strip()
    for open_, close in _QUOTE_PAIRS:
        if len(text) > 2 and text[0] == open_ and text[-1] == close and not (
            orig.startswith(open_) and orig.endswith(close)
        ):
            text = text[1:-1].strip()
            break
    if not text:
        return None
    if lenient:
        return text
    n = len(orig)
    if len(text) < n // 2 - 40 or len(text) > n * 2 + 40:
        return None
    return text


# ----------------------------------------------------------- форма запроса

_DEEP_EFFORTS = {"xhigh", "max"}


def thinking_off(model: str, effort: str) -> bool:
    """Сонету до high — без размышлений. Опусу и Fable явное disabled — это 400."""
    return model == MODEL_SONNET and effort.strip() not in _DEEP_EFFORTS


def thinking_headroom(model: str, effort: str) -> int:
    return 0 if thinking_off(model, effort) else 8000


def max_tokens_for(parts: PromptParts, input_text: str, model: str, effort: str) -> int:
    estimated = (len(parts.dict_part) + len(input_text)) // 2 + 1
    return max(1024, min(16384, estimated * 13 // 10 + 300 + thinking_headroom(model, effort)))


def build_request(model: str, effort: str, parts: PromptParts, input_text: str) -> Dict:
    """
    Аргументы для client.messages.create — та же форма, что шлёт телефон:
    два текстовых блока в одном сообщении, точка кэша на час на стабильном
    префиксе, усилие через output_config, размышления по RequestPolicy.
    Без stream: результат вставляется одним куском, когда готов.
    """
    content: List[Dict] = []
    if parts.stable_prefix.strip():
        block: Dict = {"type": "text", "text": parts.stable_prefix}
        if parts.cache_stable_always:
            block["cache_control"] = {"type": "ephemeral", "ttl": "1h"}
        content.append(block)
    content.append({"type": "text", "text": parts.dict_part + input_text + parts.after_input})

    req: Dict = {
        "model": model,
        "max_tokens": max_tokens_for(parts, input_text, model, effort),
        "messages": [{"role": "user", "content": content}],
    }
    if effort.strip():
        req["output_config"] = {"effort": effort.strip()}
    if thinking_off(model, effort):
        req["thinking"] = {"type": "disabled"}
    return req


# ------------------------------------------------------------------- цена

_PRICES = {
    MODEL_SONNET: (2.0, 10.0, 0.2),
    MODEL_OPUS: (5.0, 25.0, 0.5),
    MODEL_FABLE: (10.0, 50.0, 0.25),
}


def cost_usd(model: str, input_tokens: int, output_tokens: int,
             cache_write_tokens: int = 0, cache_read_tokens: int = 0) -> float:
    p = _PRICES.get(model)
    if not p:
        return 0.0
    inp, out, cache_read = p
    return ((input_tokens + 2.0 * cache_write_tokens) / 1e6 * inp
            + cache_read_tokens / 1e6 * cache_read
            + output_tokens / 1e6 * out)


# ------------------------------------------------------------------ файл

@dataclass
class Route:
    model: str
    effort: str = ""


@dataclass
class Bundle:
    path: str
    exported_at: int
    routes: Dict[str, Route]
    prompts: Dict[str, str]
    rules_block: str
    rules_in_prose: bool
    entries: List[DictEntry] = field(default_factory=list)

    def route(self, key: str) -> Route:
        r = self.routes.get(key)
        if r is None:
            # Заводские дороги приложения (data/ModelRoutes.kt): без них файл
            # старой сборки всё равно работает.
            r = Route(MODEL_OPUS) if key == "pravka_strong" else Route(MODEL_SONNET)
        return r


class BundleError(Exception):
    pass


def parse_bundle(text: str, path: str = "") -> Bundle:
    try:
        root = json.loads(text)
    except ValueError as e:
        raise BundleError("Файл для компа не читается как JSON: %s" % e)
    if root.get("format") != BUNDLE_FORMAT:
        raise BundleError(
            "Это не файл для компа (format=%r). Нужен файл с кнопки «Файл для компа» "
            "во вкладке Промпты." % root.get("format")
        )
    prompts = root.get("prompts") or {}
    if not prompts.get("clean", "").strip():
        raise BundleError("В файле нет промпта чистки (prompts.clean).")
    if PLACEHOLDER_INPUT not in prompts["clean"]:
        # Как на телефоне: без {INPUT} текст дописывается в конец, но владелец
        # должен об этом знать — молча это выглядело бы как поломка.
        print("Внимание: в промпте чистки нет {INPUT}, текст будет дописан в конец промпта.")
    routes = {
        k: Route(str(v.get("model", "")), str(v.get("effort", "")))
        for k, v in (root.get("routes") or {}).items()
        if isinstance(v, dict) and v.get("model")
    }
    entries: List[DictEntry] = []
    for o in (root.get("dictionary") or {}).get("entries", []):
        from_ = str(o.get("from", "")).strip()
        mode = str(o.get("mode", "HARD"))
        if not from_ or mode not in ("HARD", "HINT", "PROTECT"):
            continue
        entries.append(DictEntry(
            id=int(o.get("id", 0)),
            from_=from_,
            to=str(o.get("to", "")).strip(),
            mode=mode,
            note=str(o.get("note", "")).strip(),
            enabled=bool(o.get("enabled", True)),
        ))
    return Bundle(
        path=path,
        exported_at=int(root.get("exportedAt", 0)),
        routes=routes,
        prompts={k: str(v) for k, v in prompts.items()},
        rules_block=str(root.get("rulesBlock", "")),
        rules_in_prose=bool(root.get("rulesInProse", False)),
        entries=entries,
    )


def find_bundle(candidates: Sequence[str]) -> Optional[str]:
    """
    Самый свежий файл среди кандидатов: явный путь, папка утилиты, Загрузки.
    Владелец скидывает файл себе в Telegram и качает — утилита находит его
    сама, без переноса в нужную папку.
    """
    found: List[str] = []
    for c in candidates:
        c = os.path.expanduser(c)
        if os.path.isdir(c):
            found += glob.glob(os.path.join(c, "pravka-comp*.json"))
        elif os.path.isfile(c):
            found.append(c)
    if not found:
        return None
    return max(found, key=os.path.getmtime)


# ------------------------------------------------------------------ режимы

# Режим -> (ключ дороги, ключ промпта-задания или None для чистой чистки).
# Те же дороги и директивы, что у меню долгого нажатия «П».
MODES = {
    "clean":    ("pravka", None),
    "strong":   ("pravka_strong", None),
    "shorter":  ("pravka", "shorter"),
    "longer":   ("pravka", "longer"),
    "polish":   ("pravka", "polish"),
    "business": ("pravka", "business"),
    "soften":   ("pravka", "soften"),
    "prose":    ("pravka", "prose"),
}

MODE_TITLES = {
    "clean": "Причесать", "strong": "Причесать сильнее", "shorter": "Короче",
    "longer": "Длиннее", "polish": "Отшлифовать", "business": "Деловой",
    "soften": "Мягче", "prose": "Проза",
}


@dataclass
class Plan:
    model: str
    effort: str
    request: Dict
    prepared: Prepared
    lenient: bool


def plan_request(bundle: Bundle, mode: str, raw_text: str) -> Plan:
    """
    Весь путь телефона от сырого текста до аргументов запроса, без сети:
    голосовые команды -> словарь -> промпт (задание, правила) -> форма запроса.
    """
    if mode not in MODES:
        raise ValueError("Неизвестный режим: %s" % mode)
    route_key, directive_key = MODES[mode]
    everyday = bundle.route("pravka")
    route = bundle.route(route_key)

    text = apply_voice_commands(raw_text)
    prepared = prepare_dictionary(text, bundle.entries)

    directive = bundle.prompts.get(directive_key, "") if directive_key else ""
    # Проза: правила — советы про деловые сообщения, они спорят с директивой
    # прозы; едут только если владелец включил это на телефоне.
    rules = "" if (mode == "prose" and not bundle.rules_in_prose) else bundle.rules_block
    dict_and_rules = "\n\n".join(s for s in (prepared.dict_block, rules) if s.strip())
    parts = assemble(bundle.prompts["clean"], dict_and_rules, directive)
    # Точка кэша — на повседневной модели: там она читается с каждой диктовки.
    parts.cache_stable_always = route.model == everyday.model

    return Plan(
        model=route.model,
        effort=route.effort,
        request=build_request(route.model, route.effort, parts, prepared.text),
        prepared=prepared,
        lenient=bool(directive.strip()),
    )
