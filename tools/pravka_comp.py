#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Правка на компе — «Причесать» для любого текстового поля на компьютере.

Распознавание на компе уже есть (владелец диктует своим Whisper-приложением).
Эта утилита делает то, что телефон делает ПОСЛЕ распознавания: берёт текст,
гоняет его через Claude с тем же промптом, словарём и правилами, что и кнопка
«П», и вставляет результат обратно. Данные — файл с кнопки «Файл для компа»
(вкладка Промпты на телефоне): телефон хозяин словаря и правил, комп читает.

Как работает нажатие горячей клавиши:
  1. ждём, пока владелец отпустит модификаторы (иначе наш Ctrl+C уйдёт как
     Ctrl+Alt+C и ничего не скопирует);
  2. кладём в буфер метку, шлём Ctrl+C и смотрим, сменился ли буфер:
     сменился — работаем с выделением; нет — с тем, что лежало в буфере
     (как на телефоне: «по выделению, иначе по буферу»);
  3. ядро (pravka_comp_core) собирает запрос точно как телефон, ходим в
     Claude официальным SDK, чистим ответ;
  4. результат — в буфер и Ctrl+V; копия остаётся в буфере (страховка, как
     на телефоне). Ctrl+Z в самом приложении возвращает исходник.

Плашка внизу справа называет состояние словами («причёсываю», «готово»,
ошибка целиком): молчаливая механика читается как поломка.

Клавиши сравниваются по виртуальным кодам, а не по символам: в русской
раскладке «p» — это «з», и библиотеки, которые ловят клавиши по символу,
в ней глохнут. Поэтому pynput, а не keyboard.

Запуск:  python pravka_comp.py            (или pythonw — без консоли)
         python pravka_comp.py --check    проверить файл, ключ, клавиши
         python pravka_comp.py --text "…" [--mode clean]   причесать без клавиш
Настройки — pravka_comp.json рядом (образец: pravka_comp.example.json).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
import queue
import sys
import threading
import time
from typing import Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pravka_comp_core as core  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "pravka_comp.json")

DEFAULTS: Dict = {
    # клавиша -> режим (см. core.MODES). Буквы — по физической клавише,
    # раскладка не важна. Свободные режимы привязываются здесь же.
    "hotkeys": {"ctrl+alt+p": "clean"},
    "quit_hotkey": "ctrl+alt+shift+q",
    # Явный путь к файлу с телефона; пусто — самый свежий pravka-comp*.json
    # из search_dirs (папка утилиты и Загрузки).
    "bundle": "",
    "search_dirs": [HERE, "~/Downloads", "~/Загрузки"],
    # Пусто — переменная окружения ANTHROPIC_API_KEY.
    "api_key": "",
    "copy_wait_ms": 400,
    "paste_delay_ms": 80,
    "history": os.path.join(HERE, "pravka_comp_history.jsonl"),
    "pill": True,
}


def _resolve(path: str) -> str:
    """Относительные пути в конфиге — от папки утилиты, а не от того, откуда её запустили."""
    p = os.path.expanduser(path)
    return p if os.path.isabs(p) else os.path.normpath(os.path.join(HERE, p))


def load_config() -> Dict:
    cfg = dict(DEFAULTS)
    if os.path.isfile(CONFIG_PATH):
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            user = json.load(f)
        for k, v in user.items():
            if not k.startswith("_"):
                cfg[k] = v
    cfg["search_dirs"] = [_resolve(d) for d in cfg.get("search_dirs", [])]
    cfg["history"] = _resolve(cfg["history"])
    if cfg.get("bundle"):
        cfg["bundle"] = _resolve(cfg["bundle"])
    return cfg


def log(msg: str) -> None:
    print(dt.datetime.now().strftime("%H:%M:%S") + "  " + msg, flush=True)


# ------------------------------------------------------------ клавиши

MODIFIERS = {
    "ctrl": "ctrl", "ctrl_l": "ctrl", "ctrl_r": "ctrl",
    "alt": "alt", "alt_l": "alt", "alt_r": "alt", "alt_gr": "alt",
    "shift": "shift", "shift_l": "shift", "shift_r": "shift",
    "cmd": "cmd", "cmd_l": "cmd", "cmd_r": "cmd",
}


class Hotkey:
    """«ctrl+alt+p»: набор модификаторов плюс главная клавиша (vk или Key)."""

    def __init__(self, spec: str):
        from pynput.keyboard import Key
        self.spec = spec
        parts = [p.strip().lower() for p in spec.split("+") if p.strip()]
        if not parts:
            raise ValueError("Пустая клавиша")
        self.mods = frozenset(MODIFIERS[p] for p in parts[:-1] if p in MODIFIERS)
        if len(self.mods) != len(parts) - 1:
            raise ValueError("Непонятный модификатор в «%s»" % spec)
        main = parts[-1]
        self.vk: Optional[int] = None
        self.key = None
        if len(main) == 1 and (main.isascii() and main.isalnum()):
            # Виртуальные коды букв и цифр совпадают с ASCII заглавных.
            self.vk = ord(main.upper())
        elif hasattr(Key, main):
            self.key = getattr(Key, main)
        else:
            raise ValueError("Неизвестная клавиша «%s» в «%s»" % (main, spec))

    def matches(self, key, mods: frozenset) -> bool:
        if mods != self.mods:
            return False
        if self.key is not None:
            return key == self.key
        return getattr(key, "vk", None) == self.vk


class Keys:
    """Слушатель клавиш и отправка Ctrl+C / Ctrl+V виртуальными кодами."""

    def __init__(self, bindings: List[Tuple[Hotkey, str]], on_fire, on_quit):
        from pynput import keyboard
        self._kb = keyboard
        self.bindings = bindings
        self.on_fire = on_fire
        self.on_quit = on_quit
        self.mods: set = set()
        self.controller = keyboard.Controller()
        self.listener = keyboard.Listener(on_press=self._press, on_release=self._release)
        self.paste_mod = keyboard.Key.cmd if platform.system() == "Darwin" else keyboard.Key.ctrl

    def start(self) -> None:
        self.listener.start()

    def stop(self) -> None:
        self.listener.stop()

    def _mod_name(self, key) -> Optional[str]:
        name = getattr(key, "name", None)
        return MODIFIERS.get(name) if name else None

    def _press(self, key) -> None:
        m = self._mod_name(key)
        if m:
            self.mods.add(m)
            return
        snapshot = frozenset(self.mods)
        for hk, mode in self.bindings:
            if hk.matches(key, snapshot):
                if mode == "__quit__":
                    self.on_quit()
                else:
                    self.on_fire(mode)
                return

    def _release(self, key) -> None:
        m = self._mod_name(key)
        if m:
            self.mods.discard(m)

    def wait_modifiers_released(self, timeout: float = 1.5) -> None:
        deadline = time.time() + timeout
        while self.mods and time.time() < deadline:
            time.sleep(0.02)

    def send_combo(self, letter: str) -> None:
        code = self._kb.KeyCode.from_vk(ord(letter.upper()))
        with self.controller.pressed(self.paste_mod):
            self.controller.press(code)
            self.controller.release(code)


# ------------------------------------------------------------- буфер

def clipboard_get() -> str:
    import pyperclip
    # Буфер Windows бывает занят другим процессом на десятки миллисекунд;
    # одна неудача — не повод терять диктовку.
    for _ in range(5):
        try:
            return pyperclip.paste() or ""
        except Exception:
            time.sleep(0.05)
    return ""


def clipboard_set(text: str) -> None:
    import pyperclip
    for _ in range(5):
        try:
            pyperclip.copy(text)
            return
        except Exception:
            time.sleep(0.05)


# ------------------------------------------------------------- плашка

class Pill:
    """Маленькое окно поверх всех внизу справа; живёт в главном потоке Tk."""

    def __init__(self, root):
        import tkinter as tk
        self.root = root
        root.overrideredirect(True)
        root.attributes("-topmost", True)
        try:
            root.attributes("-alpha", 0.94)
        except Exception:
            pass
        self.label = tk.Label(
            root, text="", bg="#1f1f24", fg="#f2f2f2",
            font=("Segoe UI", 11), padx=18, pady=9, justify="left", wraplength=520,
        )
        self.label.pack()
        self._hide_job = None
        root.withdraw()

    def show(self, text: str, ms: Optional[int] = None) -> None:
        self.label.config(text=text)
        self.root.update_idletasks()
        w, h = self.root.winfo_reqwidth(), self.root.winfo_reqheight()
        x = self.root.winfo_screenwidth() - w - 24
        y = self.root.winfo_screenheight() - h - 72
        self.root.geometry("+%d+%d" % (x, y))
        self.root.deiconify()
        if self._hide_job:
            self.root.after_cancel(self._hide_job)
            self._hide_job = None
        if ms:
            self._hide_job = self.root.after(ms, self.hide)

    def hide(self) -> None:
        self._hide_job = None
        self.root.withdraw()


# --------------------------------------------------------------- работа

class Failure(Exception):
    """Ошибка, которую владелец должен увидеть словами, целиком."""


class Pravka:
    def __init__(self, cfg: Dict, status):
        self.cfg = cfg
        self.status = status          # (text, ms or None) -> None, потокобезопасно
        self.bundle: Optional[core.Bundle] = None
        self._bundle_mtime = 0.0
        self.busy = False
        self.keys: Optional[Keys] = None
        self._client = None

    # --- данные с телефона

    def ensure_bundle(self) -> core.Bundle:
        candidates = ([self.cfg["bundle"]] if self.cfg.get("bundle") else []) + list(self.cfg["search_dirs"])
        path = core.find_bundle(candidates)
        if not path:
            raise Failure(
                "Не найден файл с телефона (pravka-comp*.json). Нажми «Файл для компа» "
                "во вкладке Промпты и положи файл в Загрузки или рядом с утилитой."
            )
        mtime = os.path.getmtime(path)
        if self.bundle is None or path != self.bundle.path or mtime != self._bundle_mtime:
            with open(path, "r", encoding="utf-8") as f:
                self.bundle = core.parse_bundle(f.read(), path)
            self._bundle_mtime = mtime
            when = dt.datetime.fromtimestamp(self.bundle.exported_at / 1000).strftime("%d.%m %H:%M") \
                if self.bundle.exported_at else "?"
            log("Файл для компа: %s (снят %s), словарь %d, правила %d зн., чистка на %s%s" % (
                os.path.basename(path), when, len(self.bundle.entries), len(self.bundle.rules_block),
                self.bundle.route("pravka").model,
                (" / " + self.bundle.route("pravka").effort) if self.bundle.route("pravka").effort else "",
            ))
        return self.bundle

    # --- Claude

    def client(self):
        if self._client is None:
            try:
                import anthropic
            except ImportError:
                raise Failure("Не установлен пакет anthropic: pip install -r requirements-pravka-comp.txt")
            key = (self.cfg.get("api_key") or os.environ.get("ANTHROPIC_API_KEY") or "").strip()
            if not key:
                raise Failure("Не задан ключ: переменная окружения ANTHROPIC_API_KEY или api_key в pravka_comp.json.")
            # Один повтор на сетевую ошибку и 5xx — как на телефоне; таймаут с
            # запасом под Опус с размышлениями на «сильнее».
            self._client = anthropic.Anthropic(api_key=key, timeout=180.0, max_retries=1)
        return self._client

    def call(self, plan: core.Plan) -> Tuple[str, Dict]:
        import anthropic
        client = self.client()
        try:
            resp = client.messages.create(**plan.request)
        except anthropic.APIStatusError as e:
            # HTTP-причина доходит до владельца целой: код и тело ответа.
            raise Failure("Claude ответил %s: %s" % (e.status_code, getattr(e, "message", str(e))))
        except anthropic.APIConnectionError as e:
            raise Failure("Нет связи с api.anthropic.com: %s" % e)
        if resp.stop_reason == "max_tokens":
            raise Failure("Ответ обрезан по длине (max_tokens); текст не тронут.")
        if resp.stop_reason == "refusal":
            raise Failure("Модель отказалась править этот текст (refusal); текст не тронут.")
        text = "".join(b.text for b in resp.content if getattr(b, "type", "") == "text")
        u = resp.usage
        usage = {
            "input": u.input_tokens,
            "output": u.output_tokens,
            "cache_write": getattr(u, "cache_creation_input_tokens", 0) or 0,
            "cache_read": getattr(u, "cache_read_input_tokens", 0) or 0,
        }
        return text, usage

    # --- один проход: текст -> причёсанный текст

    def proofread(self, mode: str, raw: str, explicit_fragment: bool) -> Tuple[str, Dict]:
        bundle = self.ensure_bundle()
        source = raw.strip()
        if not source:
            raise Failure("Пусто: ни выделения, ни текста в буфере.")
        if len(source) < core.MIN_INPUT_LENGTH and not explicit_fragment:
            raise Failure("В буфере меньше %d знаков — похоже на случайное нажатие, не трогаю." % core.MIN_INPUT_LENGTH)
        plan = core.plan_request(bundle, mode, source)
        started = time.time()
        reply, usage = self.call(plan)
        latency = time.time() - started
        cleaned = core.clean_response(reply, plan.prepared.text, lenient=plan.lenient)
        if cleaned is None:
            self.journal(mode, plan, source, reply, usage, latency, error="response corrupted")
            raise Failure("Модель вернула испорченный ответ (не та длина или пусто), текст не тронут.")
        cost = core.cost_usd(plan.model, usage["input"], usage["output"], usage["cache_write"], usage["cache_read"])
        self.journal(mode, plan, source, cleaned, usage, latency, cost=cost)
        return cleaned, {"latency": latency, "cost": cost, "model": plan.model, "usage": usage,
                         "changed": cleaned != source}

    def journal(self, mode, plan, source, output, usage, latency, cost=0.0, error=None) -> None:
        rec = {
            "ts": dt.datetime.now().isoformat(timespec="seconds"),
            "mode": mode, "model": plan.model, "effort": plan.effort,
            "latency_ms": int(latency * 1000), "usage": usage, "cost_usd": round(cost, 6),
            "fired": plan.prepared.fired_ids, "input": source, "output": output, "error": error,
        }
        try:
            with open(os.path.expanduser(self.cfg["history"]), "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        except OSError as e:
            log("журнал не записан: %s" % e)

    # --- по горячей клавише

    def fire(self, mode: str) -> None:
        if self.busy:
            self.status("Ещё причёсываю прошлое, подожди", 1500)
            return
        self.busy = True
        threading.Thread(target=self._run, args=(mode,), daemon=True).start()

    def _run(self, mode: str) -> None:
        title = core.MODE_TITLES.get(mode, mode)
        try:
            assert self.keys is not None
            self.keys.wait_modifiers_released()
            previous = clipboard_get()
            sentinel = "⁣pravka-comp %f" % time.time()
            clipboard_set(sentinel)
            self.keys.send_combo("c")
            deadline = time.time() + self.cfg["copy_wait_ms"] / 1000.0
            grabbed = None
            while time.time() < deadline:
                cur = clipboard_get()
                if cur and cur != sentinel:
                    grabbed = cur
                    break
                time.sleep(0.03)
            if grabbed is None:
                # Ничего не выделено: Ctrl+C не тронул буфер. Работаем с тем,
                # что там лежало, и возвращаем это в буфер на случай ошибки.
                clipboard_set(previous)
                text, explicit = previous, False
                where = "буфер"
            else:
                text, explicit = grabbed, True
                where = "выделение"
            self.status("%s: %s, %d зн. Причёсываю…" % (title, where, len(text.strip())), None)
            log("%s (%s, %d зн.)" % (title, where, len(text.strip())))

            cleaned, info = self.proofread(mode, text, explicit)
            if not info["changed"]:
                self.status("Текст уже чистый, ничего не менял", 2500)
                log("без изменений, %.1f с, $%.4f" % (info["latency"], info["cost"]))
                return
            clipboard_set(cleaned)
            time.sleep(self.cfg["paste_delay_ms"] / 1000.0)
            self.keys.send_combo("v")
            self.status("Готово · %.1f с · $%.4f · %s%s" % (
                info["latency"], info["cost"], info["model"],
                " · кэш" if info["usage"]["cache_read"] else ""), 3000)
            log("готово, %.1f с, $%.4f, токены %s" % (info["latency"], info["cost"], info["usage"]))
        except Failure as e:
            self.status("Не вышло: %s" % e, 8000)
            log("ошибка: %s" % e)
        except Exception as e:  # noqa: BLE001 — любая другая тоже должна быть видна словами
            self.status("Сбой утилиты: %s: %s" % (type(e).__name__, e), 8000)
            log("сбой: %s: %s" % (type(e).__name__, e))
        finally:
            self.busy = False


# ------------------------------------------------------------------ main

def build_bindings(cfg: Dict) -> List[Tuple[Hotkey, str]]:
    bindings: List[Tuple[Hotkey, str]] = []
    for spec, mode in cfg["hotkeys"].items():
        if mode not in core.MODES:
            raise SystemExit("В pravka_comp.json неизвестный режим «%s» для «%s». Есть: %s" % (
                mode, spec, ", ".join(core.MODES)))
        bindings.append((Hotkey(spec), mode))
    if cfg.get("quit_hotkey"):
        bindings.append((Hotkey(cfg["quit_hotkey"]), "__quit__"))
    return bindings


def check(cfg: Dict) -> int:
    """--check: всё, что может сломать первый запуск, одним списком."""
    ok = True
    p = Pravka(cfg, lambda t, ms=None: None)
    try:
        b = p.ensure_bundle()
        print("Файл: %s" % b.path)
        print("  словарь: %d записей, правила: %d знаков, промпты: %s" % (
            len(b.entries), len(b.rules_block), ", ".join(sorted(b.prompts))))
        for key in ("pravka", "pravka_strong"):
            r = b.route(key)
            print("  дорога %s: %s%s" % (key, r.model, (" / " + r.effort) if r.effort else ""))
    except (Failure, core.BundleError) as e:
        ok = False
        print("Файл: %s" % e)
    key = (cfg.get("api_key") or os.environ.get("ANTHROPIC_API_KEY") or "").strip()
    print("Ключ Anthropic: %s" % ("есть" if key else "НЕТ (ANTHROPIC_API_KEY или api_key в pravka_comp.json)"))
    ok = ok and bool(key)
    for name in ("anthropic", "pynput", "pyperclip"):
        try:
            __import__(name)
            print("Пакет %s: есть" % name)
        except ImportError:
            ok = False
            print("Пакет %s: НЕТ — pip install -r requirements-pravka-comp.txt" % name)
    try:
        for hk, mode in build_bindings(cfg):
            print("Клавиша %s -> %s" % (hk.spec, "выход" if mode == "__quit__" else core.MODE_TITLES.get(mode, mode)))
    except Exception as e:  # noqa: BLE001
        ok = False
        print("Клавиши: %s" % e)
    print("Итог: %s" % ("всё готово" if ok else "есть что поправить"))
    return 0 if ok else 1


def run_text(cfg: Dict, text: str, mode: str) -> int:
    """--text: причесать строку из командной строки, без клавиш и буфера."""
    p = Pravka(cfg, lambda t, ms=None: log(t))
    try:
        cleaned, info = p.proofread(mode, text, explicit_fragment=True)
    except (Failure, core.BundleError) as e:
        print("Не вышло: %s" % e, file=sys.stderr)
        return 1
    print(cleaned)
    log("%.1f с, $%.4f, %s" % (info["latency"], info["cost"], info["model"]))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Правка на компе: причесать текст как кнопка «П».")
    ap.add_argument("--check", action="store_true", help="проверить файл, ключ, пакеты и клавиши")
    ap.add_argument("--text", help="причесать этот текст и напечатать результат")
    ap.add_argument("--mode", default="clean", choices=sorted(core.MODES), help="режим для --text")
    args = ap.parse_args()
    cfg = load_config()

    if args.check:
        return check(cfg)
    if args.text is not None:
        return run_text(cfg, args.text, args.mode)

    bindings = build_bindings(cfg)
    events: "queue.Queue[Tuple[str, Optional[int]]]" = queue.Queue()

    def status(text: str, ms: Optional[int] = None) -> None:
        events.put((text, ms))

    pravka = Pravka(cfg, status)
    try:
        pravka.ensure_bundle()
    except (Failure, core.BundleError) as e:
        log(str(e))   # не смертельно: файл может появиться позже, проверим при нажатии

    stop = threading.Event()
    keys = Keys(bindings, pravka.fire, stop.set)
    pravka.keys = keys
    keys.start()
    for hk, mode in bindings:
        log("клавиша %s -> %s" % (hk.spec, "выход" if mode == "__quit__" else core.MODE_TITLES.get(mode, mode)))

    pill = None
    root = None
    if cfg.get("pill", True):
        try:
            import tkinter as tk
            root = tk.Tk()
            pill = Pill(root)
        except Exception as e:  # noqa: BLE001 — без плашки тоже работаем, только в консоль
            log("плашка недоступна (%s), состояние только в консоли" % e)

    if root is not None and pill is not None:
        def poll():
            while True:
                try:
                    text, ms = events.get_nowait()
                except queue.Empty:
                    break
                pill.show(text, ms)
            if stop.is_set():
                root.quit()
                return
            root.after(60, poll)
        root.after(60, poll)
        status("Правка на компе слушает: %s" % ", ".join(hk.spec for hk, m in bindings if m != "__quit__"), 3000)
        root.mainloop()
    else:
        while not stop.is_set():
            try:
                text, _ = events.get(timeout=0.5)
                log(text)
            except queue.Empty:
                pass
    keys.stop()
    log("выход")
    return 0


if __name__ == "__main__":
    sys.exit(main())
