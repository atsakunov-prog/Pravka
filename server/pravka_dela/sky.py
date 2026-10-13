"""Небо над «Сегодня» веба Дел (страницы-двойники, 14.10.2026): погода и время Шаббата.

Погода — та же, что у телефона (`data/WeatherStore.kt`, правила — `core/WeatherDay.kt`): Open-Meteo без ключа,
почасовой прогноз на десять дней, кэш на час. Строка — сейчас и три ближайшие части суток от текущего часа
(вечер, утро, день), мелко «ощущается»; раскрытие — сутки по часам вбок и неделя: утро, день, вечер, осадки. Сервер
ходит сам и помнит ответ: браузер в чужие адреса не ходит (CSP Дел пускает только свой), а страница не ждёт
прогноза — у него свой вид (`pogoda`), отдельно от дня. Сеть упала — последний прогноз с пометкой «старый»; не было
ни одного — погоды нет, без выдумок.

Где — Свод `today.weather` владельца ({name, lat, lon}); записи нет — Москва, как у телефона с завода.

Шаббат — как плитка телефона (`core/ShabbatTimes.kt`): свечи — за 18 минут до заката пятницы, исход — солнце на
8,5° под горизонтом в субботу (Хабад), закат — «Almanac for Computers» (точность — минута-две). Точное время и глава
— у сервиса «Шаббат»; плитка ведёт туда.
"""

from __future__ import annotations

import datetime as dt
import logging
import math
import threading
import time
from zoneinfo import ZoneInfo

import httpx

log = logging.getLogger("dela.sky")

MSK = ZoneInfo("Europe/Moscow")
DEFAULT_PLACE = {"name": "Москва", "lat": 55.7558, "lon": 37.6173}  # как LAT_DEFAULT/LON_DEFAULT телефона
CACHE_SECONDS = 3600
TIMEOUT = 8.0
DAYS = 10
URL = ("https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}"
       "&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code"
       "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max"
       "&timezone=auto&forecast_days={days}")
MINUS = "−"
WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]

_lock = threading.Lock()
_cache: dict[tuple[float, float], tuple[float, dict]] = {}


def place(conn, user: str) -> dict:
    """Где смотреть погоду: Свод today.weather владельца, иначе Москва."""
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = 'today.weather'", (user,)).fetchone()
    v = row["value"] if row else None
    try:
        if isinstance(v, dict) and -90 <= float(v["lat"]) <= 90 and -180 <= float(v["lon"]) <= 180:
            return {"name": str(v.get("name") or ""), "lat": float(v["lat"]), "lon": float(v["lon"])}
    except (KeyError, TypeError, ValueError):
        pass
    return dict(DEFAULT_PLACE)


def fetch(lat: float, lon: float) -> dict:
    """Прогноз Open-Meteo как есть. Прокси окружения не берём: Open-Meteo доступен напрямую, а прокси службы — для
    Claude, через него погоде ходить незачем."""
    with httpx.Client(timeout=TIMEOUT, trust_env=False) as c:
        r = c.get(URL.format(lat=lat, lon=lon, days=DAYS))
        r.raise_for_status()
        return r.json()


def forecast(where: dict, now: float | None = None) -> dict:
    """Погода для страницы: сейчас, три части суток, сутки по часам, неделя. Кэш на час; сбой — прежний ответ."""
    key = (round(where["lat"], 3), round(where["lon"], 3))
    now = time.time() if now is None else now
    with _lock:
        got = _cache.get(key)
    error = ""
    if not got or now - got[0] > CACHE_SECONDS:
        try:
            data = fetch(where["lat"], where["lon"])
            got = (now, data)
            with _lock:
                _cache[key] = got
        except Exception as e:  # noqa: BLE001 — сеть, DNS, ответ не JSON: странице — словами, служба живёт
            error = f"прогноз не обновился: {type(e).__name__}"
            log.warning("погода %s: %s", where.get("name"), e)
    if not got:
        return {"ok": False, "place": where.get("name", ""), "error": error or "прогноза нет"}
    try:
        out = build(got[1], now)
    except (KeyError, TypeError, ValueError, IndexError) as e:
        return {"ok": False, "place": where.get("name", ""), "error": f"прогноз не разобрался: {type(e).__name__}"}
    return {"ok": True, "place": where.get("name", ""), "stale": bool(error), "error": error,
            "at": dt.datetime.fromtimestamp(got[0], dt.timezone.utc).isoformat(), **out}


# ── правила — как core/WeatherDay.kt ────────────────────────────────────────

def temp(t: float) -> str:
    """«+4°», «−2°», «0°»."""
    r = round(t)
    return f"+{r}°" if r > 0 else f"{MINUS}{-r}°" if r < 0 else "0°"


def sky_of(code: int, hour: int) -> str:
    """Код погоды WMO → небо: clear, night, partly, cloud, fog, rain, snow, storm."""
    if code in (0, 1):
        return "night" if hour >= 21 or hour < 6 else "clear"
    if code == 2:
        return "partly"
    if code == 3:
        return "cloud"
    if code in (45, 48):
        return "fog"
    if 51 <= code <= 67 or 80 <= code <= 82:
        return "rain"
    if 71 <= code <= 77 or code in (85, 86):
        return "snow"
    if 95 <= code <= 99:
        return "storm"
    return "cloud"


def part_of(hour: int) -> str | None:
    """Утро 5–11, день 12–16, вечер 17–22; ночь — None (в строке её нет)."""
    if 5 <= hour <= 11:
        return "утро"
    if 12 <= hour <= 16:
        return "день"
    if 17 <= hour <= 22:
        return "вечер"
    return None


PART_HOUR = {"утро": 9, "день": 14, "вечер": 19}


def week_rain(prob: int, mm: float) -> tuple[str, bool]:
    """«5,5 мм · 73%» акцентом, если вероятность от 30 %; иначе «сухо» или «0,6 мм» бледно."""
    amount = "сухо" if mm < 0.1 else (f"{mm:.1f}".replace(".", ",").removesuffix(",0") + " мм")
    if prob >= 30 and mm >= 0.1:
        return f"{amount} · {prob}%", True
    if prob >= 30:
        return f"{prob}%", True
    return amount, False


def _hours(data: dict) -> list[dict]:
    h = data["hourly"]
    out = []
    for i, t in enumerate(h["time"]):
        at = dt.datetime.fromisoformat(t)
        out.append({"at": at, "temp": float(h["temperature_2m"][i]),
                    "feels": _num(h.get("apparent_temperature"), i), "mm": _num(h.get("precipitation"), i) or 0.0,
                    "prob": int(_num(h.get("precipitation_probability"), i) or 0), "code": int(h["weather_code"][i] or 0)})
    return out


def _num(arr, i):
    try:
        v = arr[i]
        return None if v is None else float(v)
    except (TypeError, IndexError):
        return None


def build(data: dict, now: float) -> dict:
    """Ответ Open-Meteo → строки страницы. Часы — местные места прогноза (timezone=auto)."""
    offset = dt.timedelta(seconds=int(data.get("utc_offset_seconds") or 0))
    local = (dt.datetime.fromtimestamp(now, dt.timezone.utc) + offset).replace(tzinfo=None)
    start = local.replace(minute=0, second=0, microsecond=0)
    hours = [x for x in _hours(data) if x["at"] >= start][:24]
    if not hours:
        raise ValueError("нет часов вперёд")
    cur = hours[0]
    # Три ближайшие части суток от текущего часа: у идущей — ближайший оставшийся час, у следующих — их обычный.
    groups: list[tuple[str, list[dict]]] = []
    for x in hours:
        p = part_of(x["at"].hour)
        if not p:
            continue
        if groups and groups[-1][0] == p and x["at"] - groups[-1][1][-1]["at"] == dt.timedelta(hours=1):
            groups[-1][1].append(x)
        else:
            groups.append((p, [x]))
    parts = []
    for label, hs in groups[:3]:
        x = next((y for y in hs if y["at"].hour == PART_HOUR[label]), hs[0])
        parts.append({"label": label, "temp": temp(x["temp"]), "feels": temp(x["feels"]) if x["feels"] is not None else ""})
    week = []
    all_hours = _hours(data)
    d = data["daily"]
    for i, day in enumerate(d["time"][:7]):
        date = dt.date.fromisoformat(day)
        of_day = [x for x in all_hours if x["at"].date() == date]

        def at(h: int):
            return min(of_day, key=lambda y: abs(y["at"].hour - h)) if of_day else None
        m, a, e = at(9), at(14), at(19)
        rain, wet = week_rain(int(_num(d.get("precipitation_probability_max"), i) or 0),
                              _num(d.get("precipitation_sum"), i) or 0.0)
        week.append({"d": WEEKDAYS[date.weekday()].capitalize() + " " + str(date.day),
                     "m": temp(m["temp"]).removesuffix("°") if m else "",
                     "a": temp(a["temp"]).removesuffix("°") if a else temp(float(d["temperature_2m_max"][i])).removesuffix("°"),
                     "e": temp(e["temp"]).removesuffix("°") if e else "",
                     "sky": sky_of(int(d["weather_code"][i] or 0), 12), "rain": rain, "wet": wet})
    return {
        "now": {"temp": temp(cur["temp"]), "feels": temp(cur["feels"]) if cur["feels"] is not None else "",
                "sky": sky_of(cur["code"], cur["at"].hour)},
        "parts": parts,
        "hours": [{"t": f"{x['at'].hour:02d}", "temp": temp(x["temp"]), "sky": sky_of(x["code"], x["at"].hour),
                   "rain": f"{x['prob']}%" if x["prob"] >= 20 else ""} for x in hours],
        "week": week,
    }


# ── Шаббат — как core/ShabbatTimes.kt ───────────────────────────────────────

CANDLES_BEFORE_MIN = 18
SUNSET_ZENITH = 90.833
EXIT_ZENITH = 98.5


def sun_time(date: dt.date, lat: float, lon: float, zenith: float, rising: bool) -> dt.datetime | None:
    """Восход или заход в день date для зенита (UTC); None — солнце до такого зенита не доходит."""
    rad = math.pi / 180
    n = date.timetuple().tm_yday
    lng_hour = lon / 15
    t = n + ((6.0 if rising else 18.0) - lng_hour) / 24
    m = 0.9856 * t - 3.289
    lng = (m + 1.916 * math.sin(m * rad) + 0.020 * math.sin(2 * m * rad) + 282.634) % 360
    ra = (math.atan(0.91764 * math.tan(lng * rad)) / rad) % 360
    ra += math.floor(lng / 90) * 90 - math.floor(ra / 90) * 90
    ra /= 15
    sin_dec = 0.39782 * math.sin(lng * rad)
    cos_dec = math.cos(math.asin(sin_dec))
    cos_h = (math.cos(zenith * rad) - sin_dec * math.sin(lat * rad)) / (cos_dec * math.cos(lat * rad))
    if cos_h > 1 or cos_h < -1:
        return None
    h = ((360 - math.acos(cos_h) / rad) if rising else (math.acos(cos_h) / rad)) / 15
    local = h + ra - 0.06571 * t - 6.622
    ut = ((local - lng_hour) % 24 + 24) % 24
    return dt.datetime.combine(date, dt.time(), dt.timezone.utc) + dt.timedelta(hours=ut)


def shabbat(now: dt.datetime, lat: float, lon: float, zone: dt.tzinfo = MSK) -> dict | None:
    """Шаббат, который идёт или будет следующим: свечи и исход местным «ЧЧ:ММ», now — внутри ли он."""
    today = now.astimezone(zone).date()
    friday = today - dt.timedelta(days=(today.weekday() - 4) % 7)
    for _ in range(2):
        sunset = sun_time(friday, lat, lon, SUNSET_ZENITH, rising=False)
        exit_ = sun_time(friday + dt.timedelta(days=1), lat, lon, EXIT_ZENITH, rising=False)
        if not sunset or not exit_:
            return None
        candles = sunset - dt.timedelta(minutes=CANDLES_BEFORE_MIN)
        if now < exit_:
            c, x = candles.astimezone(zone), exit_.astimezone(zone)
            return {"friday": friday.isoformat(), "candles": c.strftime("%H:%M"), "exit": x.strftime("%H:%M"),
                    "candles_at": c.isoformat(), "exit_at": x.isoformat(), "now": now >= candles,
                    "today": today == friday}
        friday += dt.timedelta(days=7)
    return None
