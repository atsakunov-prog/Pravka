package ru.zf.pravka.trigger

import ru.zf.pravka.R

// Тумблер «иконки вместо букв» на плавающих кнопках: буквы «П/З/Д/Т» хозяину
// «иногда непонятно» — тогда те же пиктограммы, что в нижней ленте: перо,
// часы, галочка, гантеля. Значение кэширует служба из настроек и просит
// кнопки перечитать глиф — переключается вживую, без перезапуска.
//
// Версия 3 (26.09.2026): у значков два почерка — наш штрих и «как у Gemini»
// (Material Symbols Rounded, `_g`-ресурсы). Почерк один на всё приложение
// (`Settings.iconsGeminiFlow`); буквы на кнопках от него не зависят.
object ModeGlyphs {
    @Volatile var icons = false
    @Volatile var gemini = ru.zf.pravka.data.Settings.ICONS_GEMINI_DEFAULT

    // На кнопках — уменьшенные (_btn, 52%) версии: во всю ширину кружка
    // пиктограммы выглядели ужасно, буквы-глифы держат такой же отступ.
    private fun btn(ours: Int, g: Int) = if (gemini) g else ours

    fun pravka() = if (icons) btn(R.drawable.ic_mode_pravka_btn, R.drawable.ic_mode_pravka_btn_g) else R.drawable.ic_fab_glyph
    fun zasechka() = if (icons) btn(R.drawable.ic_mode_zasechka_btn, R.drawable.ic_mode_zasechka_btn_g) else R.drawable.ic_zfab_glyph
    fun raznoska() = if (icons) btn(R.drawable.ic_mode_delo_btn, R.drawable.ic_mode_delo_btn_g) else R.drawable.ic_razn_glyph

    // Зелёная кнопка теперь ЕДА (владелец: «кнопка спорта не нужна, спорт
    // наговариваю во вкладке») — буква «Е», в режиме иконок — тарелка.
    fun body() = if (icons) btn(R.drawable.ic_mode_food_btn, R.drawable.ic_mode_food_btn_g) else R.drawable.ic_efab_glyph

    // Деньги (23.09.2026): брусковый «₽», в режиме иконок — тот же рубль штрихом.
    fun money() = if (icons) btn(R.drawable.ic_mode_money_btn, R.drawable.ic_mode_money_btn_g) else R.drawable.ic_money_glyph

    // Знак режима слева в пилюле диктовки — всегда значок, в текущем почерке.
    // Пилюля спрашивает его на каждом показе: переключение доходит со следующего.
    fun pillPravka() = btn(R.drawable.ic_mode_pravka, R.drawable.ic_mode_pravka_g)
    fun pillZasechka() = btn(R.drawable.ic_mode_zasechka, R.drawable.ic_mode_zasechka_g)
    fun pillDelo() = btn(R.drawable.ic_mode_delo, R.drawable.ic_mode_delo_g)
    fun pillFood() = btn(R.drawable.ic_mode_food, R.drawable.ic_mode_food_g)
    fun pillMoney() = btn(R.drawable.ic_mode_money, R.drawable.ic_mode_money_g)
}
