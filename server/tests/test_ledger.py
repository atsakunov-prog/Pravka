"""Движок ленты на сервере (ledger.py) — те же правила, что у ZasechkaStore телефона (docs/server-truth.md)."""

import datetime as dt

from pravka_dela.ledger import GAP_SOURCE, TZ, Entry, Ledger, day_of, day_snapshot


def ms(day: str, hm: str) -> int:
    """Московский миг «ГГГГ-ММ-ДД ЧЧ:ММ» в мс."""
    return int(dt.datetime.fromisoformat(f"{day}T{hm}").replace(tzinfo=TZ).timestamp() * 1000)


D = "2026-10-20"
D2 = "2026-10-21"


def e(i, s, en, title="дело", source="voice", **kw):
    return Entry(id=i, start=s, end=en, title=title, category=kw.pop("category", "Работа: текущая"), source=source, **kw)


def spans(lg: Ledger, now=None):
    return [(x.title, x.start, x.end, x.source) for x in sorted(lg.entries, key=lambda x: x.start)]


def test_start_closes_running_at_its_start():
    lg = Ledger([e(1, ms(D, "10:00"), 0)], now=ms(D, "11:00"))
    opened = lg.start_entry(ms(D, "11:00"), title="обед", category="Еда", source="voice", id=777)
    assert opened.id == 777 and opened.open
    assert lg.by_id(1).end == ms(D, "11:00")
    assert [x.id for x in lg.entries if x.open] == [777]


def test_retro_start_before_running_clamps_to_zero_length():
    lg = Ledger([e(1, ms(D, "10:00"), 0)], now=ms(D, "10:30"))
    lg.start_entry(ms(D, "09:50"), title="раньше")
    # Начато раньше идущего: идущее закрыто в своём начале, не в минус — и как крошка нулевой длины уходит
    # (так и у телефона: closeOpenLocked, потом dropCrumbsLocked).
    assert lg.by_id(1) is None
    assert lg.open_entry().title == "раньше" and lg.open_entry().start == ms(D, "09:50")


def test_midnight_splits_running_entry():
    lg = Ledger([e(1, ms(D, "23:00"), 0, raw="сказал", comment="коммент")], now=ms(D2, "01:00"))
    assert lg.normalize()
    head = lg.by_id(1)
    assert head.end == ms(D2, "00:00") and head.raw == "сказал" and head.comment == "коммент"
    tail = lg.open_entry()
    assert tail.id != 1 and tail.start == ms(D2, "00:00") and tail.raw == "" and tail.comment == ""


def test_gap_fills_only_after_quarantine_and_live_filler():
    a = e(1, ms(D, "10:00"), ms(D, "11:00"))
    b = e(2, ms(D, "11:30"), ms(D, "12:00"))
    c = e(3, ms(D, "12:30"), ms(D, "12:40"))
    lg = Ledger([a, b, c], now=ms(D, "13:00"))
    lg.normalize()
    gaps = [(x.start, x.end) for x in lg.entries if x.source == GAP_SOURCE]
    # 11:00–11:30 — правый край стоит 1,5 часа: заполнена; 12:00–12:30 — правому краю полчаса: ещё ждёт;
    # после 12:40 ничего не идёт — открытый живой заполнитель.
    assert (ms(D, "11:00"), ms(D, "11:30")) in gaps
    assert (ms(D, "12:00"), ms(D, "12:30")) not in gaps
    assert (ms(D, "12:40"), 0) in gaps


def test_real_open_entry_kills_live_filler_and_retro_start_trims_closed_filler():
    lg = Ledger([e(1, ms(D, "10:00"), ms(D, "11:00"))], now=ms(D, "13:00"))
    lg.normalize()
    assert any(x.source == GAP_SOURCE and x.open for x in lg.entries)
    lg.start_entry(ms(D, "12:00"), title="звонки разбирал")
    assert not any(x.source == GAP_SOURCE and x.open for x in lg.entries)
    # 11:00–12:00 так и стоит неразмеченным, а не съедено: правый край — начало нового дела.
    lg.now = ms(D, "13:00")
    lg.normalize()
    assert any(x.source == GAP_SOURCE and x.start == ms(D, "11:00") and x.end == ms(D, "12:00") for x in lg.entries)


def test_manual_entry_splices_around_auto_sleep():
    m = e(1, ms(D, "10:00"), ms(D, "12:00"), raw="работал", title="работа")
    s = e(2, ms(D, "10:30"), ms(D, "11:00"), title="сон", source="auto", category="Сон")
    lg = Ledger([m, s], now=ms(D, "14:00"))
    lg.normalize()
    work = sorted((x for x in lg.entries if x.title == "работа"), key=lambda x: x.start)
    assert [(x.start, x.end) for x in work] == [(ms(D, "10:00"), ms(D, "10:30")), (ms(D, "11:00"), ms(D, "12:00"))]
    assert work[0].id == 1 and work[0].raw == "работал" and work[1].raw == ""


def test_insert_closed_frames_with_covering_manual():
    lg = Ledger([e(1, ms(D, "18:00"), ms(D, "19:00"), title="быт", raw="быт")], now=ms(D, "20:00"))
    ins = lg.insert_closed(ms(D, "18:30"), ms(D, "18:50"), title="туалет", category="Быт")
    assert ins is not None and ins.source == "voice"
    byt = sorted((x for x in lg.entries if x.title == "быт"), key=lambda x: x.start)
    assert [(x.start, x.end) for x in byt] == [(ms(D, "18:00"), ms(D, "18:30")), (ms(D, "18:50"), ms(D, "19:00"))]
    assert byt[0].raw == "быт" and byt[1].raw == ""


def test_insert_closed_yields_to_manual_entry_fully_inside():
    lg = Ledger([e(1, ms(D, "18:30"), ms(D, "18:59") + 30_000, title="своё")], now=ms(D, "20:00"))
    # Своя запись целиком внутри: вставка ужимается к большему краю — полминуты, меньше минуты: не пишется.
    assert lg.insert_closed(ms(D, "18:29") + 30_000, ms(D, "19:00"), title="вставка") is None
    # Ровно минута ещё помещается (как у телефона: отказ — только «меньше минуты»).
    lg2 = Ledger([e(1, ms(D, "18:30"), ms(D, "18:59"), title="своё")], now=ms(D, "20:00"))
    got = lg2.insert_closed(ms(D, "18:29"), ms(D, "19:00"), title="вставка")
    assert (got.start, got.end) == (ms(D, "18:29"), ms(D, "18:30"))


def test_call_interrupts_and_work_resumes_then_dissolves_back():
    lg = Ledger([e(1, ms(D, "10:00"), 0, title="работа", raw="начал работу")], now=ms(D, "10:40"))
    call = lg.insert_interruption(ms(D, "10:20"), ms(D, "10:30"), "звонок Маме", "Семья", resume_previous=True)
    assert call is not None and call.source == "auto"
    work = sorted((x for x in lg.entries if x.title == "работа"), key=lambda x: x.start)
    assert work[0].end == ms(D, "10:20") and work[1].start == ms(D, "10:30") and work[1].open and work[1].raw == ""
    # Тот же звонок из журнала ещё раз — дубль, не факт.
    assert lg.insert_interruption(ms(D, "10:20"), ms(D, "10:30"), "звонок Маме", "Семья", True) is None
    kept = lg.dissolve_interruption(call.id)
    assert kept.id == 1 and kept.open and kept.start == ms(D, "10:00")
    assert [x.title for x in lg.entries] == ["работа"]


def test_interruption_yields_to_entry_started_inside_it():
    lg = Ledger([e(1, ms(D, "23:10"), 0, title="читаю")], now=ms(D, "23:30"))
    got = lg.insert_interruption(ms(D, "23:00"), ms(D, "23:20"), "сон", "Сон", resume_previous=False)
    assert got.end == ms(D, "23:10")   # человек сказал — человек прав
    assert lg.by_id(1).open


def test_hairline_gap_closes_and_crumbs_drop():
    a = e(1, ms(D, "10:00"), ms(D, "10:30"))
    b = e(2, ms(D, "10:30") + 40_000, ms(D, "11:00"))
    crumb = e(3, ms(D, "11:00"), ms(D, "11:00") + 10_000, title="крошка")
    lg = Ledger([a, b, crumb], now=ms(D, "11:05"))
    lg.normalize()
    assert lg.by_id(1).end == ms(D, "10:30") + 40_000
    assert lg.by_id(3) is None


def test_covered_by_owner():
    lg = Ledger([e(1, ms(D, "00:00"), ms(D, "04:00"), title="не спал")], now=ms(D, "09:00"))
    assert lg.covered_by_owner(ms(D, "01:00"), ms(D, "07:00"))      # 3 из 6 часов — половина: человек сильнее
    assert not lg.covered_by_owner(ms(D, "00:00"), ms(D, "09:00"))


def test_revert_reopen_absorb():
    a = e(1, ms(D, "09:00"), ms(D, "10:00"), title="работа")
    car = e(2, ms(D, "10:00"), 0, title="машина", category="Передвижение: транспорт")
    lg = Ledger([a, car], now=ms(D, "10:05"))
    back = lg.revert_auto_start({2}, 1)
    assert back.id == 1 and back.open and lg.by_id(2) is None
    lg.close_open(ms(D, "10:05"))
    assert lg.reopen(1).open
    guess = e(3, ms(D, "11:00"), ms(D, "11:07"), title="встреча", source="calendar")
    said = e(4, ms(D, "11:07"), 0, title="созвон с птицами")
    lg2 = Ledger([guess, said], now=ms(D, "11:10"))
    m = lg2.absorb(3, 4)
    assert m.start == ms(D, "11:00") and lg2.by_id(3) is None


def test_day_snapshot_sums_to_1440():
    xs = [e(1, ms(D, "00:00"), ms(D, "07:30"), title="сон", source="auto", category="Сон"),
          e(2, ms(D, "07:30"), ms(D, "13:15")), e(3, ms(D, "13:15"), ms(D2, "00:00"), title="вечер")]
    snap = day_snapshot(xs, D, now=ms(D2, "09:00"))
    assert sum(r["minutes"] for r in snap["entries"]) == 1440
    assert snap["entries"][0]["start"].endswith("+03:00") and snap["entries"][0]["start"].startswith(D + "T00:00:00.000")
    assert day_of(ms(D2, "00:00")) == D2


def test_day_snapshot_overlap_counts_once_and_open_has_no_minutes():
    xs = [e(1, ms(D, "10:00"), ms(D, "11:00")), e(2, ms(D, "10:00"), ms(D, "10:30"), title="дубль"),
          e(3, ms(D, "11:00"), 0, title="идёт")]
    snap = day_snapshot(xs, D, now=ms(D, "12:00"))
    by = {r["id"]: r["minutes"] for r in snap["entries"]}
    assert by == {1: 60, 2: 0, 3: None}
