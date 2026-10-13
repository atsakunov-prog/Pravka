package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Kovcheg

// Меню Ковчега (Правка 5.0, план §3; холст «Ковчег на телефоне», классы .kmenu,
// .kbig, .kdirs, .kcol, .kh, .ksub, .kcreate, .kitems): значок Ковчега в шапке
// открывает все сервисы семьи поверх экрана — без нижней панели и колонки.
// Выбрал — экран целиком сервису. Что в меню и куда ведёт — `core/Kovcheg.kt`;
// здесь только вид.

/** Что показать в меню: большие кнопки, направления, «Творчество», где мы сейчас и есть ли новенькое. */
class KovchegMenuModel(
    val big: List<Kovcheg.Item>,
    val directions: List<Kovcheg.Direction>,
    val create: Kovcheg.Direction,
    /** Ключ текущего экрана (`Kovcheg.TODAY`…) — его пункт подсвечен. */
    val current: String?,
    /** Новый разбор — метка у «Настроек телефона» (они ведут туда же, куда раньше «С»). */
    val news: Boolean,
)

/**
 * Меню поверх экрана: затемнение (тап — закрыть) и панель под шапкой —
 * на сложенном во всю ширину с полями 10, на разложенном — 540 слева, как
 * на холсте. Направления — столбиками без дыр: 2 на сложенном (< 600 dp), 4
 * на разложенном.
 */
@Composable
fun KovchegMenu(
    model: KovchegMenuModel,
    onPick: (Kovcheg.Target) -> Unit,
    onPhone: () -> Unit,
    onTheme: () -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= TWO_PANE_DP.dp
        Box(
            Modifier
                .fillMaxSize()
                .background(Kv.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "закрыть меню", onClick = onDismiss),
        )
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 74.dp, start = if (wide) 16.dp else 10.dp, end = if (wide) 0.dp else 10.dp, bottom = 12.dp)
                .then(if (wide) Modifier.width(540.dp) else Modifier.fillMaxWidth())
                // Меню — единственное, что в v2 лежит над экраном: тень `--shadow` холста, а не свечение.
                .shadow(14.dp, shape, ambientColor = Color(0xFF3C321E), spotColor = Color(0xFF3C321E))
                .clip(shape)
                .background(Kv.panel)
                .border(1.dp, Kv.line, shape)
                // Тап по пустому месту панели — не мимо меню.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
        ) {
            BigRow(model, onPick)
            val n = if (wide) 4 else 2
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in Kovcheg.columns(model.directions, n)) {
                    Column(Modifier.weight(1f)) {
                        for (d in col) Direction(d, model.current, onPick)
                    }
                }
            }
            // «Творчество» — строкой ниже: надпись с подписью в первой колонке, Claude — во второй.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { DirectionHead(model.create.title, model.create.sub) }
                Column(Modifier.weight(1f)) {
                    for (x in model.create.items) MenuItem(x, on = false, onPick)
                }
                if (wide) {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Kv.lineSoft))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FootButton(Glyphs.Phone, "Настройки телефона", dot = model.news, onClick = onPhone)
                FootButton(if (Look.dark) Glyphs.Sunny else Glyphs.Moon, "Светлая или тёмная", onClick = onTheme)
            }
        }
    }
}

/** Три большие кнопки (`.kbig`): Сегодня, Засечка, Правка — с подписью мелко. */
@Composable
private fun BigRow(model: KovchegMenuModel, onPick: (Kovcheg.Target) -> Unit) {
    val t = LocalPravkaType.current
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in model.big) {
            val on = (b.target as? Kovcheg.Target.Screen)?.key == model.current
            val shape = RoundedCornerShape(KvShape.Panel)
            Row(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (on) Kv.accentSoft else Kv.panel)
                    .border(1.dp, if (on) Kv.accent else Kv.line, shape)
                    .clickable(role = Role.Button, onClick = { onPick(b.target) })
                    .semantics { contentDescription = b.name }
                    .padding(horizontal = 9.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(kvIcon(b.icon), null, tint = if (on) Kv.accent else Kv.icon, modifier = Modifier.size(20.dp))
                Column {
                    Text(b.name, style = t.valueS.copy(fontSize = 15.5.sp, lineHeight = 19.sp), color = if (on) Kv.accent else Kv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (b.sub.isNotEmpty()) {
                        Text(b.sub, style = t.caption.copy(fontSize = 11.5.sp, lineHeight = 14.sp), color = Kv.faint, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Направление (`.kdir`): название крупно, бледная подпись, сервисы строками. */
@Composable
private fun Direction(d: Kovcheg.Direction, current: String?, onPick: (Kovcheg.Target) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        DirectionHead(d.title, d.sub)
        for (x in d.items) {
            MenuItem(x, on = (x.target as? Kovcheg.Target.Screen)?.key == current, onPick)
        }
    }
}

/** `.kh` и `.ksub`: название 15,5/600, подпись 11,5 цветом `--check` — подпись, а не текст. */
@Composable
private fun DirectionHead(title: String, sub: String) {
    val t = LocalPravkaType.current
    Text(
        title,
        style = t.valueS.copy(fontSize = 15.5.sp, lineHeight = 19.sp),
        color = Kv.text,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (sub.isNotEmpty()) {
        Text(
            sub,
            style = t.caption.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
            color = Kv.check,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 1.dp, bottom = 3.dp),
        )
    }
}

/** Сервис (`.kitems a`): значок 18 и название 14; текущий — мягким акцентом. */
@Composable
private fun MenuItem(item: Kovcheg.Item, on: Boolean, onPick: (Kovcheg.Target) -> Unit) {
    val t = LocalPravkaType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .then(if (on) Modifier.background(Kv.accentSoft) else Modifier)
            .clickable(role = Role.Button, onClick = { onPick(item.target) })
            .semantics { contentDescription = item.name }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(kvIcon(item.icon), null, tint = if (on) Kv.accent else Kv.icon, modifier = Modifier.size(18.dp))
        Text(
            item.name,
            style = t.body.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
            color = if (on) Kv.accent else Kv.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Низ меню (`.kmenu-f`): «Настройки телефона» и тема — тихо, цветом `--muted`. */
@Composable
private fun FootButton(icon: ImageVector, label: String, dot: Boolean = false, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (dot) "$label, есть новый разбор" else label }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Kv.icon, modifier = Modifier.size(18.dp))
        Text(label, style = t.label.copy(fontSize = 14.sp), color = Kv.muted, maxLines = 1)
        if (dot) Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Kv.bad))
    }
}

/** Значок набора Ковчега по имени веба; незнакомое имя из настроек сервера — «список». */
private fun kvIcon(key: String): ImageVector = KvGlyphs.byKey(key) ?: KvGlyphs.byKey("list")!!
