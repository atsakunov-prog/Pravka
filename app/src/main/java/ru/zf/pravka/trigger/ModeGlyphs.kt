package ru.zf.pravka.trigger

import ru.zf.pravka.R

// Тумблер «иконки вместо букв» на плавающих кнопках: буквы «П/З/Д/Т» хозяину
// «иногда непонятно» — тогда те же пиктограммы, что в нижней ленте: перо,
// часы, галочка, гантеля. Значение кэширует служба из настроек и просит
// кнопки перечитать глиф — переключается вживую, без перезапуска.
object ModeGlyphs {
    @Volatile var icons = false

    fun pravka() = if (icons) R.drawable.ic_mode_pravka else R.drawable.ic_fab_glyph
    fun zasechka() = if (icons) R.drawable.ic_mode_zasechka else R.drawable.ic_zfab_glyph
    fun raznoska() = if (icons) R.drawable.ic_mode_delo else R.drawable.ic_razn_glyph

    // «Т» — одна кнопка на весь домен Тела: гантеля, спорт в нём главный.
    fun body() = if (icons) R.drawable.ic_mode_sport else R.drawable.ic_body_glyph
}
