package ru.zf.pravka.ui

import android.content.Context
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

// Сканер штрихкода в одну функцию.
//
// Взят готовый из Play Services (`play-services-code-scanner`), и это выбор в
// пользу малого: сам сканер живёт в сервисах Google и подтягивается на телефон
// сам, в APK едет только фасад на полтораста килобайт. Своей камеры мы не
// открываем вообще - значит и разрешение CAMERA приложению не нужно, и в
// манифесте его нет. Съёмку показывает сервис, нам возвращается строка цифр.
//
// Ошибка здесь не событие: базы штрихкодов дырявые, особенно на российских
// марках. Не отсканировалось или не нашлось - владелец снимает этикетку
// камерой, и КБЖУ читает модель.

/**
 * Показывает сканер и отдаёт распознанный код в [onCode]. Отмена - молча:
 * владелец передумал, говорить ему об этом незачем. [onFail] зовётся только
 * когда сканер правда не заработал (нет сервисов Google, отказали в модуле).
 */
fun scanBarcode(
    context: Context,
    onFail: (String) -> Unit,
    onCode: (String) -> Unit,
) {
    runCatching {
        GmsBarcodeScanning.getClient(context)
            .startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue?.filter { it.isDigit() }.orEmpty()
                if (code.length >= 6) onCode(code)
                else onFail("Это не штрихкод товара — сними этикетку камерой")
            }
            .addOnFailureListener { e ->
                onFail("Сканер не заработал: ${e.message ?: "нет сервисов Google"}")
            }
            .addOnCanceledListener { }
    }.onFailure { e ->
        onFail("Сканер недоступен: ${e.message ?: e.javaClass.simpleName}")
    }
}
