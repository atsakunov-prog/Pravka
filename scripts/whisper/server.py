"""
Локальный распознаватель для Правки: одна модель Whisper, загруженная в GPU
навсегда, и OpenAI-совместимый HTTP-эндпоинт поверх неё.

Зачем сервер, а не запуск из командной строки: при запуске процессом модель
каждый раз заново читается с диска в видеопамять. Для часовой встречи это
терпимо, для диктовки на десять секунд - нет. Здесь модель грузится один раз
и живёт, поэтому короткая фраза распознаётся за доли секунды.

Движок - faster-whisper (CTranslate2). Веса те же самые, что у референсного
Whisper, но исполнение обычно в 3-5 раз быстрее и вдвое экономнее по
видеопамяти.

    POST /v1/audio/transcriptions   (multipart: file, model, language, prompt)
    GET  /health

Формат запроса намеренно OpenAI-совместимый: тем же сервером сможет
пользоваться любой другой инструмент, а Правка не привязана к реализации -
меняешь URL в настройках, код не трогаешь.

Настройки - в whisper.env рядом с этим файлом (переменные окружения главнее):
    PRAVKA_WHISPER_HOST      127.0.0.1   слушать только себя
    PRAVKA_WHISPER_PORT      8178
    PRAVKA_WHISPER_MODEL     large-v3-turbo    модель по умолчанию (диктовка)
    PRAVKA_WHISPER_PRELOAD   large-v3-turbo    что загрузить сразу при старте
    PRAVKA_WHISPER_DEVICE    cuda
    PRAVKA_WHISPER_COMPUTE   float16     int8_float16 - вдвое меньше VRAM
    PRAVKA_WHISPER_MAX_LOADED 2          сколько моделей держать в памяти
    PRAVKA_WHISPER_BEAM      5
    PRAVKA_WHISPER_LANG      ru
"""

from __future__ import annotations

import logging
import os
import tempfile
import threading
import time
from collections import OrderedDict
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("pravka-whisper")


# Настройки читаются из whisper.env рядом с сервером; переменные окружения,
# если они заданы, перекрывают файл. Файл нужен потому, что планировщик задач
# Windows запускает процесс без твоего окружения.
def _load_env_file() -> dict:
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "whisper.env")
    values = {}
    if not os.path.exists(path):
        return values
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip().strip('"')
    return values


_FILE_ENV = _load_env_file()


def _env(name: str, default: str) -> str:
    return os.environ.get(name, _FILE_ENV.get(name, default)).strip()


HOST = _env("PRAVKA_WHISPER_HOST", "127.0.0.1")
PORT = int(_env("PRAVKA_WHISPER_PORT", "8178"))
DEFAULT_MODEL = _env("PRAVKA_WHISPER_MODEL", "large-v3-turbo")
PRELOAD = [m for m in _env("PRAVKA_WHISPER_PRELOAD", DEFAULT_MODEL).split(",") if m.strip()]
DEVICE = _env("PRAVKA_WHISPER_DEVICE", "cuda")
COMPUTE = _env("PRAVKA_WHISPER_COMPUTE", "int8_float16")
# Куда отступать, если видеопамяти не хватило: на этой машине GPU уже занят
# разбором встреч, и диктовка обязана уступать ему, а не падать.
FALLBACK_DEVICE = _env("PRAVKA_WHISPER_FALLBACK_DEVICE", "cpu")
FALLBACK_COMPUTE = _env("PRAVKA_WHISPER_FALLBACK_COMPUTE", "int8")
MAX_LOADED = int(_env("PRAVKA_WHISPER_MAX_LOADED", "1"))
# Через сколько минут после нехватки видеопамяти пробовать GPU снова: бот
# дорасшифровал встречу и память вернулась - глупо сидеть на процессоре вечно.
RETRY_GPU_MIN = int(_env("PRAVKA_WHISPER_RETRY_GPU_MIN", "15"))
# Луч 1 для диктовки: на короткой чистой фразе разницы с лучом 5 почти нет,
# а памяти и времени он ест заметно меньше. Для встречи можно прислать beam
# в самом запросе.
BEAM = int(_env("PRAVKA_WHISPER_BEAM", "1"))
DEFAULT_LANG = _env("PRAVKA_WHISPER_LANG", "ru")

# Имена, которые faster-whisper знает не под тем названием, под которым их
# ищут люди. Всё, чего нет в списке, уезжает в faster-whisper как есть -
# туда же можно вписать и полный repo id с Hugging Face.
ALIASES = {
    "turbo": "large-v3-turbo",
    "large": "large-v3",
    "whisper-1": DEFAULT_MODEL,  # то, что шлют OpenAI-совместимые клиенты
}


def _is_out_of_memory(exc: Exception) -> bool:
    text = str(exc).lower()
    return "out of memory" in text or "cuda" in text and "alloc" in text


class ModelPool:
    """Загруженные модели с вытеснением самой давней.

    По умолчанию держим ровно одну (диктовочную): видеопамять на этой машине
    делится с ботом, который расшифровывает встречи, и вторая копия большой
    модели там просто не поместится."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._models: "OrderedDict[str, object]" = OrderedDict()
        self._devices: dict = {}
        # Когда снова пробовать GPU после того, как память кончилась.
        self._retry_gpu_at = 0.0

    def names(self) -> list:
        with self._lock:
            return [
                name + " (" + self._devices.get(name, DEVICE) + ")"
                for name in self._models
            ]

    def drop(self, name: str) -> None:
        """Убирает модель из памяти - чтобы освободить GPU боту."""
        with self._lock:
            self._models.pop(name, None)
            self._devices.pop(name, None)

    def note_out_of_memory(self) -> None:
        """Память кончилась: ближайшие RETRY_GPU_MIN минут работаем на CPU."""
        with self._lock:
            self._retry_gpu_at = time.monotonic() + RETRY_GPU_MIN * 60

    def device_of(self, name: str) -> str:
        return self._devices.get(name, DEVICE)

    @staticmethod
    def resolve(name: str) -> str:
        return ALIASES.get(name, name)

    def get(self, name: str, prefer_device: str = ""):
        name = self.resolve(name)
        with self._lock:
            model = self._models.get(name)
            if model is not None:
                on_fallback = self._devices.get(name) == FALLBACK_DEVICE
                # Отсиделись на процессоре - пробуем вернуться на GPU:
                # к этому времени бот обычно уже дорасшифровал встречу.
                if on_fallback and DEVICE != FALLBACK_DEVICE and time.monotonic() > self._retry_gpu_at:
                    log.info("пробую вернуть %s на %s", name, DEVICE)
                    self._models.pop(name, None)
                    self._devices.pop(name, None)
                else:
                    self._models.move_to_end(name)
                    return model

        # Загрузка идёт ВНЕ общего замка: первая большая модель читается
        # десятки секунд, и всё это время короткие запросы к уже загруженной
        # модели должны обслуживаться, а не ждать.
        from faster_whisper import WhisperModel

        # prefer_device - это "грузи прямо сюда": так возвращается запрос,
        # у которого память кончилась уже посреди распознавания.
        want_device = prefer_device or DEVICE
        want_compute = FALLBACK_COMPUTE if want_device == FALLBACK_DEVICE else COMPUTE

        started = time.monotonic()
        log.info("гружу модель %s (%s, %s)...", name, want_device, want_compute)
        try:
            loaded = WhisperModel(name, device=want_device, compute_type=want_compute)
            device = want_device
        except Exception as exc:  # noqa: BLE001
            if want_device == FALLBACK_DEVICE:
                raise
            # Чаще всего это "видеопамять кончилась": бот разбирает встречу и
            # держит свою модель. Уступаем: на процессоре медленнее, но
            # диктовка работает, а чужой разбор не ломается.
            log.warning("не влезли в %s (%s) - иду на %s: %s", want_device, want_compute, FALLBACK_DEVICE, exc)
            self.note_out_of_memory()
            loaded = WhisperModel(name, device=FALLBACK_DEVICE, compute_type=FALLBACK_COMPUTE)
            device = FALLBACK_DEVICE
        log.info("модель %s готова за %.1f с на %s", name, time.monotonic() - started, device)

        with self._lock:
            existing = self._models.get(name)
            if existing is not None:  # кто-то успел загрузить ту же модель
                return existing
            self._models[name] = loaded
            self._devices[name] = device
            while len(self._models) > MAX_LOADED:
                dropped, _ = self._models.popitem(last=False)
                self._devices.pop(dropped, None)
                log.info("выгружаю модель %s (держим не больше %d)", dropped, MAX_LOADED)
            return loaded


pool = ModelPool()


@asynccontextmanager
async def lifespan(_app: "FastAPI"):
    for name in PRELOAD:
        try:
            pool.get(name.strip())
        except Exception as exc:  # noqa: BLE001 - сервис должен подняться в любом случае
            log.error("не смог загрузить %s заранее: %s", name, exc)
    yield


app = FastAPI(title="Правка · Whisper", docs_url=None, redoc_url=None, lifespan=lifespan)


@app.get("/health")
def health() -> JSONResponse:
    return JSONResponse(
        {
            "status": "ok",
            "default": DEFAULT_MODEL,
            "loaded": pool.names(),
            "device": DEVICE,
            "compute": COMPUTE,
            "fallback": FALLBACK_DEVICE,
        }
    )


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    file: UploadFile = File(...),
    model: str = Form(DEFAULT_MODEL),
    language: Optional[str] = Form(None),
    prompt: Optional[str] = Form(None),
    temperature: float = Form(0.0),
    response_format: str = Form("json"),
    beam_size: Optional[int] = Form(None),
):
    payload = await file.read()
    if not payload:
        raise HTTPException(status_code=400, detail="пустой файл")

    suffix = os.path.splitext(file.filename or "audio.wav")[1] or ".wav"
    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    try:
        tmp.write(payload)
        tmp.close()

        resolved = pool.resolve(model or DEFAULT_MODEL)
        try:
            engine = pool.get(resolved)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=503, detail=f"модель не загрузилась: {exc}") from exc

        def run(model):
            segments, info = model.transcribe(
                tmp.name,
                language=(language or DEFAULT_LANG) or None,
                beam_size=beam_size or BEAM,
                temperature=temperature,
                # initial_prompt - это и есть словарь Правки: фамилии и термины
                # начинают распознаваться правильно ДО правки.
                initial_prompt=prompt or None,
                # Без этого large-v3 на тишине умеет зацикливаться и повторять
                # последнюю фразу до конца записи.
                condition_on_previous_text=False,
                vad_filter=True,
                vad_parameters={"min_silence_duration_ms": 500},
            )
            # Генератор ленивый: реальная работа (и возможная нехватка
            # памяти) случается здесь, а не на вызове transcribe.
            return list(segments), info

        started = time.monotonic()
        try:
            collected, info = run(engine)
        except Exception as exc:  # noqa: BLE001
            if not _is_out_of_memory(exc) or pool.device_of(resolved) == FALLBACK_DEVICE:
                raise HTTPException(status_code=500, detail=f"распознавание не удалось: {exc}") from exc
            # Память кончилась на середине - скорее всего бот взялся за
            # встречу. Освобождаем GPU ему и доделываем на процессоре.
            log.warning("кончилась видеопамять, ухожу на %s: %s", FALLBACK_DEVICE, exc)
            pool.drop(resolved)
            pool.note_out_of_memory()
            collected, info = run(pool.get(resolved, prefer_device=FALLBACK_DEVICE))
        text = "".join(s.text for s in collected).strip()
        elapsed = time.monotonic() - started
        audio_s = getattr(info, "duration", 0.0) or 0.0
        log.info(
            "%s (%s) · аудио %.1f с · распознано за %.1f с (x%.1f) · %d символов",
            resolved,
            pool.device_of(resolved),
            audio_s,
            elapsed,
            (audio_s / elapsed) if elapsed > 0 else 0.0,
            len(text),
        )

        if response_format == "text":
            return PlainTextResponse(text)
        if response_format == "verbose_json":
            return JSONResponse(
                {
                    "task": "transcribe",
                    "language": info.language,
                    "duration": audio_s,
                    "text": text,
                    "segments": [
                        {"id": i, "start": s.start, "end": s.end, "text": s.text}
                        for i, s in enumerate(collected)
                    ],
                }
            )
        return JSONResponse({"text": text})
    finally:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass


if __name__ == "__main__":
    import uvicorn

    log.info("Правка · Whisper на http://%s:%d (модель %s)", HOST, PORT, DEFAULT_MODEL)
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
