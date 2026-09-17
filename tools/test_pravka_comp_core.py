#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Паритет ядра утилиты с телефоном. Случаи — те же, что закреплены в Kotlin
(RequestPolicyTest, комментарии в DictionaryApplier/Prompts/ResponseCleaner):
расхождение здесь значит, что комп причёсывает не так, как телефон.

Запуск: python3 -m unittest tools/test_pravka_comp_core.py
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pravka_comp_core as core  # noqa: E402


def entry(id_, from_, to="", mode="HARD", note="", enabled=True):
    return core.DictEntry(id=id_, from_=from_, to=to, mode=mode, note=note, enabled=enabled)


class DictionaryTest(unittest.TestCase):

    def test_hard_replaces_whole_words_and_keeps_first_case(self):
        p = core.prepare_dictionary(
            "Сейфа сказал, что сейфа не будет. Сейфам не звонил.",
            [entry(1, "сейфа", "CFO")],
        )
        # Целое слово, регистр первой буквы сохранён; «Сейфам» — другое слово,
        # HARD окончания не глотает (иначе «осу» -> «осуди»).
        self.assertEqual("CFO сказал, что CFO не будет. Сейфам не звонил.", p.text)
        self.assertEqual([1], p.fired_ids)

    def test_hard_longest_first(self):
        p = core.prepare_dictionary(
            "дудлайнг стрик и дудлайнг",
            [entry(1, "дудлайнг", "Duolingo"), entry(2, "дудлайнг стрик", "стрик в Duolingo")],
        )
        self.assertEqual("стрик в Duolingo и Duolingo", p.text)
        self.assertEqual([2, 1], p.fired_ids)

    def test_hint_and_protect_tolerate_endings_and_build_block(self):
        # Допуск окончаний — до трёх букв ПОСЛЕ словарной формы («сейфа»
        # ловит «сейфам»), а не замена окончания: «ебиду» словарное «ебида»
        # не поймает — так же, как на телефоне.
        p = core.prepare_dictionary(
            "Шепелина обсудила ебидами, а ебиду нет.",
            [
                entry(1, "ебида", "EBITDA", mode="HINT", note="прибыль до вычетов"),
                entry(2, "Шепелина", mode="PROTECT"),
                entry(3, "кэшфлоу", "cash flow", mode="HINT"),
            ],
        )
        self.assertEqual("Шепелина обсудила ебидами, а ебиду нет.", p.text)
        self.assertEqual(
            'Учти при правке:\n- "ебида" (прибыль до вычетов) — это EBITDA\n'
            "Не исправляй эти слова, они написаны верно:\nШепелина",
            p.dict_block,
        )
        self.assertEqual([1, 2], p.fired_ids)

    def test_disabled_entries_are_skipped(self):
        p = core.prepare_dictionary("сейфа", [entry(1, "сейфа", "CFO", enabled=False)])
        self.assertEqual("сейфа", p.text)
        self.assertEqual("", p.dict_block)

    def test_word_boundaries_are_unicode(self):
        p = core.prepare_dictionary("несейфа сейфа-то", [entry(1, "сейфа", "CFO")])
        self.assertEqual("несейфа CFO-то", p.text)


class VoiceCommandsTest(unittest.TestCase):

    def test_paragraph_commands_become_breaks(self):
        # Точка и запятые вокруг команды уходят в перенос, как на телефоне;
        # заглавную букву новой строке поставит уже модель.
        self.assertEqual(
            "Первое\nвторое\nтретье",
            core.apply_voice_commands("Первое. С новой строки второе, абзац, третье"),
        )

    def test_command_at_edge_leaves_no_dangling_break(self):
        self.assertEqual("текст", core.apply_voice_commands("Абзац текст с новой строки"))


TEMPLATE = (
    "Ты корректор.\n\nПравила.\n\n<словарь>\n{DICT}\n</словарь>\n\n"
    "Слово в тексте.\n\n<текст>\n{INPUT}\n</текст>\n\nТолько текст."
)


class AssembleTest(unittest.TestCase):

    def test_tagged_dict_travels_with_content_into_variable_part(self):
        parts = core.assemble(TEMPLATE, "Учти: X", directive="Короче.")
        self.assertEqual("Ты корректор.\n\nПравила.\n\n", parts.stable_prefix)
        self.assertEqual(
            "<словарь>\nУчти: X\n</словарь>\n\n"
            "ДОПОЛНИТЕЛЬНОЕ ЗАДАНИЕ ПОВЕРХ ПРАВКИ:\nКороче.\n\n"
            "Слово в тексте.\n\n<текст>\n",
            parts.dict_part,
        )
        self.assertEqual("\n</текст>\n\nТолько текст.", parts.after_input)

    def test_stable_prefix_is_byte_identical_across_requests(self):
        a = core.assemble(TEMPLATE, "словарь один", directive="")
        b = core.assemble(TEMPLATE, "совсем другой словарь", directive="Мягче")
        self.assertEqual(a.stable_prefix, b.stable_prefix)

    def test_bare_dict_placeholder(self):
        parts = core.assemble("Шапка\n\n{DICT}\n\nХвост {INPUT} конец", "Блок")
        self.assertEqual("Шапка\n\n", parts.stable_prefix)
        self.assertEqual("Блок\n\nХвост ", parts.dict_part)
        self.assertEqual(" конец", parts.after_input)

    def test_without_input_placeholder_text_is_appended(self):
        parts = core.assemble("Шапка\n\n{DICT}\n\nХвост", "")
        self.assertEqual("Шапка\n\n", parts.stable_prefix)
        self.assertEqual("Хвост\n\n", parts.dict_part)
        self.assertEqual("", parts.after_input)

    def test_without_dict_placeholder_directive_survives(self):
        parts = core.assemble("Шапка {INPUT}", "Блок", directive="Короче")
        self.assertEqual("Шапка ", parts.stable_prefix)
        self.assertEqual("ДОПОЛНИТЕЛЬНОЕ ЗАДАНИЕ ПОВЕРХ ПРАВКИ:\nКороче\n\n", parts.dict_part)


class ResponseCleanerTest(unittest.TestCase):

    def test_strips_fences_preamble_and_quotes(self):
        original = "привет как дела у тебя сегодня вечером"
        self.assertEqual(
            "Привет, как дела у тебя сегодня вечером?",
            core.clean_response('```text\nВот исправленный текст: «Привет, как дела у тебя сегодня вечером?»\n```', original),
        )

    def test_keeps_quotes_when_original_was_quoted(self):
        self.assertEqual('"цитата целиком тут"', core.clean_response('"цитата целиком тут"', '"цитата целиком тут"'))

    def test_length_gate_catches_eaten_paragraph(self):
        original = "а" * 400
        self.assertIsNone(core.clean_response("а" * 100, original))
        self.assertEqual("а" * 100, core.clean_response("а" * 100, original, lenient=True))

    def test_short_fragment_has_absolute_slack(self):
        self.assertEqual("в 17:00", core.clean_response("в 17:00", "в 5"))

    def test_empty_reply_is_corrupted(self):
        self.assertIsNone(core.clean_response("   ", "текст"))


class RequestPolicyTest(unittest.TestCase):

    def test_sonnet_without_effort_no_thinking(self):
        self.assertTrue(core.thinking_off(core.MODEL_SONNET, ""))
        self.assertEqual(0, core.thinking_headroom(core.MODEL_SONNET, ""))

    def test_sonnet_up_to_high_no_thinking(self):
        for e in ("low", "medium", "high"):
            self.assertTrue(core.thinking_off(core.MODEL_SONNET, e), e)

    def test_xhigh_and_max_turn_thinking_on_for_sonnet(self):
        for e in ("xhigh", "max"):
            self.assertFalse(core.thinking_off(core.MODEL_SONNET, e), e)
            self.assertEqual(8000, core.thinking_headroom(core.MODEL_SONNET, e))

    def test_opus_and_fable_never_get_disabled(self):
        for m in (core.MODEL_OPUS, core.MODEL_FABLE):
            for e in ("", "low", "medium", "high", "xhigh", "max"):
                self.assertFalse(core.thinking_off(m, e), m + "/" + e)
                self.assertEqual(8000, core.thinking_headroom(m, e))


class BuildRequestTest(unittest.TestCase):

    def test_sonnet_request_shape_matches_phone(self):
        parts = core.assemble(TEMPLATE, "Учти: X")
        parts.cache_stable_always = True
        req = core.build_request(core.MODEL_SONNET, "", parts, "текст для правки")
        self.assertEqual(core.MODEL_SONNET, req["model"])
        self.assertEqual({"type": "disabled"}, req["thinking"])
        self.assertNotIn("output_config", req)
        self.assertNotIn("stream", req)
        content = req["messages"][0]["content"]
        self.assertEqual(2, len(content))
        self.assertEqual(parts.stable_prefix, content[0]["text"])
        self.assertEqual({"type": "ephemeral", "ttl": "1h"}, content[0]["cache_control"])
        self.assertEqual(parts.dict_part + "текст для правки" + parts.after_input, content[1]["text"])
        self.assertNotIn("cache_control", content[1])
        self.assertEqual(1024, req["max_tokens"])

    def test_opus_with_effort_has_no_thinking_param_and_headroom(self):
        parts = core.assemble(TEMPLATE, "")
        req = core.build_request(core.MODEL_OPUS, "high", parts, "х" * 2000)
        self.assertNotIn("thinking", req)
        self.assertEqual({"effort": "high"}, req["output_config"])
        estimated = (len(parts.dict_part) + 2000) // 2 + 1
        self.assertEqual(estimated * 13 // 10 + 300 + 8000, req["max_tokens"])
        self.assertNotIn("cache_control", req["messages"][0]["content"][0])

    def test_max_tokens_is_capped(self):
        parts = core.assemble(TEMPLATE, "")
        req = core.build_request(core.MODEL_SONNET, "", parts, "х" * 60000)
        self.assertEqual(16384, req["max_tokens"])

    def test_empty_stable_prefix_is_not_sent(self):
        parts = core.PromptParts("", "задание ", "")
        req = core.build_request(core.MODEL_SONNET, "", parts, "текст")
        self.assertEqual(1, len(req["messages"][0]["content"]))


class PricingTest(unittest.TestCase):

    def test_cache_pricing_derives_from_input(self):
        # 1 млн входа + 1 млн записи в кэш (2x) + 1 млн чтения (0.1x) + 1 млн выхода
        self.assertAlmostEqual(2 + 4 + 0.2 + 10, core.cost_usd(core.MODEL_SONNET, 1_000_000, 1_000_000, 1_000_000, 1_000_000))
        self.assertAlmostEqual(0.25, core.cost_usd(core.MODEL_FABLE, 0, 0, 0, 1_000_000))
        self.assertEqual(0.0, core.cost_usd("неизвестная", 1000, 1000))


BUNDLE = {
    "format": "pravka-comp",
    "version": 1,
    "exportedAt": 1726560000000,
    "routes": {
        "pravka": {"model": "claude-sonnet-5", "effort": ""},
        "pravka_strong": {"model": "claude-opus-5", "effort": "high"},
    },
    "prompts": {"clean": TEMPLATE, "shorter": "Сожми.", "prose": "Проза."},
    "rulesBlock": "Постоянные правила владельца (соблюдай):\n1. Без смайлов.",
    "rulesInProse": False,
    "dictionary": {"entries": [
        {"id": 1, "from": "сейфа", "to": "CFO", "mode": "HARD", "note": "", "enabled": True},
        {"id": 2, "from": "ебида", "to": "EBITDA", "mode": "HINT", "note": "", "enabled": True},
        {"id": 3, "from": "мусор", "to": "", "mode": "ЧТО-ТО", "enabled": True},
    ]},
}


class BundleTest(unittest.TestCase):

    def test_parse(self):
        b = core.parse_bundle(json.dumps(BUNDLE, ensure_ascii=False), "x.json")
        self.assertEqual(2, len(b.entries))
        self.assertEqual("claude-opus-5", b.route("pravka_strong").model)
        self.assertEqual("high", b.route("pravka_strong").effort)
        self.assertEqual("claude-sonnet-5", b.route("pravka").model)

    def test_rejects_foreign_json(self):
        with self.assertRaises(core.BundleError):
            core.parse_bundle('{"format": "dictionary"}')
        with self.assertRaises(core.BundleError):
            core.parse_bundle("не json")

    def test_plan_clean_runs_the_whole_phone_path(self):
        b = core.parse_bundle(json.dumps(BUNDLE, ensure_ascii=False))
        plan = core.plan_request(b, "clean", "Сейфа считает ебидами, с новой строки итог")
        self.assertEqual("CFO считает ебидами\nитог", plan.prepared.text)
        self.assertEqual("claude-sonnet-5", plan.model)
        self.assertFalse(plan.lenient)
        req = plan.request
        second = req["messages"][0]["content"][1]["text"]
        self.assertIn('- "ебида" — это EBITDA', second)
        self.assertIn("Постоянные правила владельца", second)
        self.assertIn("CFO считает ебидами\nитог", second)
        self.assertNotIn("ДОПОЛНИТЕЛЬНОЕ ЗАДАНИЕ", second)
        self.assertIn("cache_control", req["messages"][0]["content"][0])

    def test_plan_directive_mode_is_lenient_and_rides_uncached(self):
        b = core.parse_bundle(json.dumps(BUNDLE, ensure_ascii=False))
        plan = core.plan_request(b, "shorter", "длинный текст" * 5)
        self.assertTrue(plan.lenient)
        self.assertIn("ДОПОЛНИТЕЛЬНОЕ ЗАДАНИЕ ПОВЕРХ ПРАВКИ:\nСожми.", plan.request["messages"][0]["content"][1]["text"])

    def test_plan_strong_uses_other_route_without_cache_point(self):
        b = core.parse_bundle(json.dumps(BUNDLE, ensure_ascii=False))
        plan = core.plan_request(b, "strong", "текст текст текст текст")
        self.assertEqual("claude-opus-5", plan.model)
        self.assertEqual({"effort": "high"}, plan.request["output_config"])
        self.assertNotIn("thinking", plan.request)
        # Другая модель — другое пространство кэша: запись за 2x впустую.
        self.assertNotIn("cache_control", plan.request["messages"][0]["content"][0])

    def test_prose_skips_rules_unless_enabled(self):
        b = core.parse_bundle(json.dumps(BUNDLE, ensure_ascii=False))
        plan = core.plan_request(b, "prose", "текст текст текст текст")
        self.assertNotIn("Постоянные правила", plan.request["messages"][0]["content"][1]["text"])
        b.rules_in_prose = True
        plan = core.plan_request(b, "prose", "текст текст текст текст")
        self.assertIn("Постоянные правила", plan.request["messages"][0]["content"][1]["text"])


if __name__ == "__main__":
    unittest.main()
