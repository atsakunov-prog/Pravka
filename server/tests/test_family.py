"""Один вход на все сервисы Ковчега (22.10.2026, pravka_dela/family.py): пароль владельца, кука домена, перебор."""

import hashlib
import json

import pytest

from pravka_dela import family

SALT = "00112233445566778899aabbccddeeff"


def _key(password: str) -> str:
    return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(SALT), family.ITERATIONS).hex()


@pytest.fixture
def conf(tmp_path, monkeypatch):
    monkeypatch.setattr(family, "_fails", [])
    monkeypatch.setattr(family, "_blocked_until", 0.0)
    path = tmp_path / "family.json"
    seen = lambda p: hashlib.sha256(_key(p).encode()).hexdigest()  # noqa: E731
    path.write_text(json.dumps({"salt": SALT, "owner": seen("пароль-владельца"), "family": seen("пароль-семьи")}),
                    encoding="utf-8")
    return path


def test_пароль_владельца_даёт_ключ_куки(conf):
    assert family.configured(conf)
    assert family.owner_key(conf, "пароль-владельца") == _key("пароль-владельца")


def test_семейный_пароль_дела_не_открывает(conf):
    assert family.owner_key(conf, "пароль-семьи") is None
    assert not family.owner_valid(conf, {"kv_family": _key("пароль-семьи")})


def test_кука_владельца_пускает_чужая_нет(conf):
    assert family.owner_valid(conf, {"kv_owner": _key("пароль-владельца")})
    assert not family.owner_valid(conf, {"kv_owner": "x" * 64})
    assert not family.owner_valid(conf, {})


def test_без_файла_вход_паролем_выключен(tmp_path):
    missing = tmp_path / "нет.json"
    assert not family.configured(missing)
    assert family.owner_key(missing, "что угодно") is None
    assert not family.owner_valid(missing, {"kv_owner": "x"})


def test_пять_неверных_закрывают_вход_паролем(conf):
    for _ in range(family.MAX_FAILS):
        assert family.owner_key(conf, "мимо") is None
    assert family.blocked_min() > 0
    # Во время блокировки не пускает и верный пароль: перебор не ускоряется угадыванием на последней попытке.
    assert family.owner_key(conf, "пароль-владельца") is None


def test_домен_куки_родитель_адреса_веба():
    assert family.domain_of("https://dela.example.org") == "example.org"
    assert family.domain_of("https://example.org") == ""
    assert family.domain_of("http://127.0.0.1:8102") == ""
    assert family.domain_of("http://dela.localhost:8195") == ""


def test_куки_на_весь_домен_и_выход():
    c = family.set_cookie("abc", "example.org")
    assert c.startswith("kv_owner=abc; Domain=example.org;") and "HttpOnly" in c and "Secure" in c
    gone = family.clear_cookies("example.org")
    assert {g.split("=", 1)[0] for g in gone} == {"kv_owner", "kv_family"}
    assert all("Max-Age=0" in g for g in gone)
