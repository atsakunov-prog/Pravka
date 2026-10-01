from __future__ import annotations

import dataclasses
import json

from pravka_archive.__main__ import PAIR_PREFIX, cmd_pair, pairing_payload


def test_payload_is_what_the_phone_parses(cfg):
    # Телефон (ArchiveSync.parsePairing) ждёт ровно «pravka-archive:{url, token}».
    p = pairing_payload(dataclasses.replace(cfg, phone_url="https://server.x.netcraze.pro:8443"))
    assert p.startswith(PAIR_PREFIX)
    o = json.loads(p[len(PAIR_PREFIX):])
    assert o == {"url": "https://server.x.netcraze.pro:8443", "token": "t" * 64}
    # Без адреса телефона — общий адрес.
    assert json.loads(pairing_payload(cfg)[len(PAIR_PREFIX):])["url"] == cfg.public_url


def test_pair_shows_png_and_removes_it(cfg, tmp_path, capsys):
    env = tmp_path / "server.env"
    seen = {}

    def ask(prompt):
        png = tmp_path / "pair-qr.png"
        seen["exists"] = png.is_file()
        seen["png"] = png.read_bytes()[:8]
        return ""

    assert cmd_pair(cfg, str(env), ask=ask) == 0
    assert seen["exists"]
    assert seen["png"] == b"\x89PNG\r\n\x1a\n"
    # В картинке токен — после сканирования её нет.
    assert not (tmp_path / "pair-qr.png").exists()
    out = capsys.readouterr().out
    assert "t" * 64 not in out  # токен текстом не печатается
