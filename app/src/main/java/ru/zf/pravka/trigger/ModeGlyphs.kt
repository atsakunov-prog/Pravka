package ru.zf.pravka.trigger

import ru.zf.pravka.R

// Тумблер «иконки вместо букв» на плавающих кнопках: буквы «П/З/Д/Т» хозяину
// «иногда непонятно» — тогда те же пиктограммы, что в нижней ленте: перо,
// часы, галочка, гантеля. Значение кэширует служба из настроек и просит
// кнопки перечитать глиф — переключается вживую, без перезапуска.
object ModeGlyphs {
    @Volatile var icons = false

    // На кнопках — уменьшенные (_btn, 52%) версии: во всю ширину кружка
    // пиктограммы выглядели ужасно, буквы-глифы держат такой же отступ.
    fun pravka() = if (icons) R.drawable.ic_mode_pravka_btn else R.drawable.ic_fab_glyph
    fun zasechka() = if (icons) R.drawable.ic_mode_zasechka_btn else R.drawable.ic_zfab_glyph
    fun raznoska() = if (icons) R.drawable.ic_mode_delo_btn else R.drawable.ic_razn_glyph

    // «Т» — одна кнопка на весь домен Тела: гантеля, спорт в нём главный.
    fun body() = if (icons) R.drawable.ic_mode_sport_btn else R.drawable.ic_body_glyph
}
