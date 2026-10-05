#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Снимки экранов для дизайна -> готовые картинки в docs/design/screens.

Сырьё снимает Robolectric (`app/src/test/.../shots/ScreenShots.kt`) в
app/build/shots: окно приложения без системных полос и окна кнопок на
прозрачном фоне. Здесь — то, чего на JVM нет:
  * строка состояния сверху (40 dp: время, сеть, батарея) — кадр читается
    как снимок с телефона, а не как макет;
  * «чужое приложение» под плавающими кнопками и пилюлей — они живут
    поверх мессенджера, а не на пустоте;
  * обзорные листы: все вкладки одним кадром.

Как перегенерировать:
    ./gradlew testDebugUnitTest -PbuildNumber=999 -Pshots=all --tests "ru.zf.pravka.shots.ScreenShots"
    python3 tools/design_shots.py
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "app", "build", "shots")
OUT = os.path.join(ROOT, "docs", "design", "screens")

DENSITY = 2.625          # 420 dpi, как у Pixel 10 Pro Fold
BAR = round(40 * DENSITY)  # строка состояния
TIME = "18:50"

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, size)


def status_icons(d, w, y, color):
    """Сеть, Wi-Fi и батарея справа, время слева — как у Pixel."""
    s = DENSITY
    d.text((round(24 * s), y), TIME, font=font(round(15 * s)), fill=color, anchor="lm")
    x = w - round(24 * s)
    # батарея
    bw, bh = round(22 * s), round(11 * s)
    d.rounded_rectangle((x - bw, y - bh // 2, x, y + bh // 2), radius=round(3 * s), outline=color, width=max(2, round(1.4 * s)))
    d.rounded_rectangle((x - bw + round(2.5 * s), y - bh // 2 + round(2.5 * s), x - bw + round(15 * s), y + bh // 2 - round(2.5 * s)), radius=round(1.5 * s), fill=color)
    d.text((x - bw - round(6 * s), y), "78%", font=font(round(12 * s)), fill=color, anchor="rm")
    x -= bw + round(40 * s)
    # сеть: четыре столбика
    for i in range(4):
        h = round((4 + i * 3) * s)
        bx = x - round((3 - i) * 4.5 * s)
        d.rectangle((bx - round(3 * s), y + round(6 * s) - h, bx, y + round(6 * s)), fill=color)
    x -= round(22 * s)
    # Wi-Fi: веер дуг
    cx, cy = x - round(8 * s), y + round(6 * s)
    for i, r in enumerate((14, 9.5, 5)):
        rr = round(r * s)
        d.pieslice((cx - rr, cy - rr, cx + rr, cy + rr), 225, 315, fill=color if i == 2 else None,
                   outline=color, width=max(2, round(1.6 * s)))


def with_bar(im, dark_text=False):
    """Над окном — строка состояния того же цвета, что верх вкладки (свет режима идёт под неё)."""
    im = im.convert("RGB")
    w, h = im.size
    out = Image.new("RGB", (w, h + BAR))
    top = im.crop((0, 0, w, 1)).resize((w, BAR))
    out.paste(top, (0, 0))
    out.paste(im, (0, BAR))
    d = ImageDraw.Draw(out)
    status_icons(d, w, BAR // 2, (20, 20, 20) if dark_text else (245, 240, 232))
    return out


def messenger(w, h):
    """Светлый мессенджер — то, поверх чего обычно висят кнопки и пилюля."""
    s = DENSITY
    im = Image.new("RGB", (w, h), (231, 235, 240))
    d = ImageDraw.Draw(im)
    # шапка чата
    d.rectangle((0, 0, w, round(64 * s)), fill=(255, 255, 255))
    d.ellipse((round(56 * s), round(12 * s), round(96 * s), round(52 * s)), fill=(120, 160, 210))
    d.text((round(76 * s), round(32 * s)), "И", font=font(round(18 * s), True), fill="white", anchor="mm")
    d.text((round(108 * s), round(24 * s)), "Иван Петров", font=font(round(17 * s), True), fill=(25, 25, 25), anchor="lm")
    d.text((round(108 * s), round(44 * s)), "был(а) недавно", font=font(round(13 * s)), fill=(130, 135, 140), anchor="lm")
    d.text((round(24 * s), round(32 * s)), "‹", font=font(round(28 * s)), fill=(60, 120, 200), anchor="mm")
    msgs = [
        (False, "Саша, добрый вечер! Посмотрели модель по Бете?"),
        (True, "Да, смотрю. Сценарий без кредита соберу к четвергу"),
        (False, "Отлично. И пришлите, пожалуйста, структуру долга"),
        (False, "Банк просит до среды"),
        (True, "Ок, напомню себе"),
        (False, "Спасибо!"),
    ]
    y = round(84 * s)
    f = font(round(15 * s))
    for mine, text in msgs:
        tw = d.textlength(text, font=f)
        maxw = w * 0.72
        lines = [text]
        if tw > maxw:
            words, lines, cur = text.split(), [], ""
            for word in words:
                t = (cur + " " + word).strip()
                if d.textlength(t, font=f) > maxw and cur:
                    lines.append(cur)
                    cur = word
                else:
                    cur = t
            lines.append(cur)
        bw = max(d.textlength(l, font=f) for l in lines) + round(28 * s)
        bh = len(lines) * round(21 * s) + round(18 * s)
        x0 = w - round(16 * s) - bw if mine else round(16 * s)
        d.rounded_rectangle((x0, y, x0 + bw, y + bh), radius=round(16 * s),
                            fill=(220, 248, 198) if mine else (255, 255, 255))
        for i, l in enumerate(lines):
            d.text((x0 + round(14 * s), y + round(9 * s) + i * round(21 * s)), l, font=f, fill=(25, 25, 25))
        y += bh + round(10 * s)
    # строка ввода
    d.rectangle((0, h - round(72 * s), w, h), fill=(255, 255, 255))
    d.rounded_rectangle((round(16 * s), h - round(60 * s), w - round(72 * s), h - round(14 * s)), radius=round(23 * s), fill=(240, 242, 245))
    d.text((round(36 * s), h - round(37 * s)), "Сообщение", font=f, fill=(150, 155, 160), anchor="lm")
    d.ellipse((w - round(62 * s), h - round(60 * s), w - round(16 * s), h - round(14 * s)), fill=(60, 140, 220))
    return im


def overview(names, title, out, width=520):
    ims = [Image.open(os.path.join(OUT, n + ".png")).convert("RGB") for n in names if os.path.exists(os.path.join(OUT, n + ".png"))]
    if not ims:
        return
    gap = 24
    hs = [int(im.size[1] * width / im.size[0]) for im in ims]
    head = 90
    sheet = Image.new("RGB", (len(ims) * width + (len(ims) + 1) * gap, max(hs) + head + gap), (16, 15, 13))
    d = ImageDraw.Draw(sheet)
    d.text((gap, head // 2), title, font=font(40, True), fill=(240, 234, 223), anchor="lm")
    x = gap
    for im, h in zip(ims, hs):
        sheet.paste(im.resize((width, h), Image.LANCZOS), (x, head))
        x += width + gap
    sheet.save(os.path.join(OUT, out), optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    raws = sorted(f for f in os.listdir(RAW) if f.endswith(".png"))
    if not raws:
        sys.exit(f"нет сырья в {RAW} — сначала сними: ./gradlew testDebugUnitTest -Pshots=all …")
    backdrop = None
    for f in raws:
        im = Image.open(os.path.join(RAW, f))
        if f.startswith("overlay-"):
            if backdrop is None or backdrop.size != im.size:
                backdrop = messenger(*im.size)
            layer = im.convert("RGBA")
            base = backdrop.convert("RGBA")
            # мягкая тень под окнами — у системы она есть, на JVM её нет
            shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
            shadow.putalpha(layer.getchannel("A").point(lambda a: int(a * 0.35)))
            shadow = shadow.filter(ImageFilter.GaussianBlur(10))
            done = Image.alpha_composite(Image.alpha_composite(base, shadow), layer)
            done = with_bar(done, dark_text=True)
            # шапка мессенджера белая — строка состояния у него белая тоже
        else:
            done = with_bar(im)
        done.save(os.path.join(OUT, f), optimize=True)
        print("готово:", f, done.size)
    overview(["outer-01-pravka", "outer-02-zasechka", "outer-03-dela-utro", "outer-04-sport",
              "outer-05-food", "outer-06-money", "outer-07-more"],
             "Правка · сложенный Pixel 10 Pro Fold · все вкладки", "overview-outer.png")
    overview(["inner-02-zasechka", "inner-03-dela", "inner-06-money"],
             "Правка · разложенный · колонка навигации слева", "overview-inner.png", width=900)
    overview(["overlay-01-dock", "overlay-03-pill-listening", "overlay-04-pill-result",
              "overlay-05-pill-ask", "overlay-06-plate-ok", "overlay-07-menu-z", "overlay-09-stack"],
             "Кнопки и плашки поверх других приложений — НЕ МЕНЯТЬ, это образец", "overview-overlays.png")


if __name__ == "__main__":
    main()
