package ru.zf.pravka.core

import java.net.URI
import org.json.JSONObject

/**
 * Веб Дел внутри вкладки (05.10.2026, владелец: «давай сделаем как веб версию,
 * чтобы не мучаться»): вкладка показывает сам веб (`server/pravka_dela/static`),
 * и всё, что там появляется, — сразу и на телефоне, без второй копии экранов.
 *
 * Здесь — скрипт, который Правка кладёт в страницу ДО её собственного кода
 * (`addDocumentStartJavaScript`, только на адрес Дел). Зачем он:
 *
 * - **вход.** Веб входит кукой через бота, а у телефона есть токен устройства
 *   из QR. Сервер на любом запросе сперва смотрит `Authorization: Bearer`
 *   (`api.auth`) — поэтому скрипт добавляет токен в `fetch` страницы к своему
 *   адресу, и веб открывается сразу, без бота. Чужим адресам токен не уходит;
 * - **голос.** Веб слушает `SpeechRecognition` браузера, а во встроенном
 *   браузере Android его нет («Этот браузер не распознаёт речь»). Скрипт
 *   ставит на его место тот же интерфейс поверх распознавателя Правки (мост
 *   `PravkaDela`): микрофон у дела и в поле наверху работают как в Chrome,
 *   только слышит Правка — тем же движком, что «Д», с её наушниками и пилюлей.
 *
 * Свой `<script>` в HTML вставить нельзя: у страницы Content-Security-Policy
 * `script-src 'self'`. Скрипт встраивающего приложения политике не подчиняется.
 */
object DelaWebScript {

    /** Имя моста в странице: `window.PravkaDela`. */
    const val BRIDGE = "PravkaDela"

    /**
     * Источник адреса Дел для правила «только этот адрес»: схема, хост и порт,
     * если он не по умолчанию («https://dela.example.netcraze.pro:8443»).
     * Не адрес — null: скрипт тогда не ставится вовсе.
     */
    fun origin(url: String): String? = runCatching {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val port = u.port.takeIf { it > 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }.getOrNull()

    /**
     * Скрипт до начала страницы. Токен — строкой JSON (`JSONObject.quote`):
     * кавычка или перевод строки в нём не превратятся в код.
     */
    fun bootstrap(token: String): String = """
(function () {
  if (window.__pravkaDela) return;
  window.__pravkaDela = true;
  var token = ${JSONObject.quote(token)};
  // Токен — только своему адресу: относительный путь или тот же origin.
  var ownFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    init = init || {};
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var own = url.charAt(0) === '/' || url.indexOf(location.origin + '/') === 0;
    if (own) {
      var h = new Headers(init.headers || (typeof input !== 'string' && input && input.headers) || {});
      h.set('Authorization', 'Bearer ' + token);
      init.headers = h;
    }
    return ownFetch(input, init);
  };
  // Голос Правки вместо SpeechRecognition браузера: один тейк, итог — одним результатом.
  var bridge = window.$BRIDGE;
  if (!bridge) return;
  function Rec() { this.lang = 'ru-RU'; this.interimResults = false; this.continuous = false;
    this.onresult = null; this.onerror = null; this.onend = null; this._on = false; }
  Rec.prototype.start = function () {
    var self = this;
    window.__pravkaRec = self;
    self._on = true;
    if (!bridge.listen()) {
      self._on = false;
      setTimeout(function () { if (self.onerror) self.onerror({ error: 'aborted' }); if (self.onend) self.onend(); }, 0);
    }
  };
  Rec.prototype.stop = function () { if (this._on) bridge.finish(true); };
  Rec.prototype.abort = function () { if (this._on) bridge.finish(false); };
  window.__pravkaSaid = function (text) {
    var r = window.__pravkaRec;
    if (!r || !r._on) return;
    r._on = false;
    if (text) {
      var alt = { transcript: text, confidence: 1 };
      var result = { 0: alt, length: 1, isFinal: true, item: function () { return alt; } };
      var results = { 0: result, length: 1, item: function () { return result; } };
      if (r.onresult) r.onresult({ resultIndex: 0, results: results });
    }
    if (r.onend) r.onend();
  };
  window.SpeechRecognition = Rec;
  window.webkitSpeechRecognition = Rec;
})();
""".trimStart()

    /** Строка JS: отдать странице сказанное (пустое — «тейк кончился без слов»). */
    fun said(text: String): String = "window.__pravkaSaid && window.__pravkaSaid(${JSONObject.quote(text)})"

    /** Толкнуть веб обновиться: он сам синкается по `focus` (и раз в 30 с). */
    const val REFRESH = "window.dispatchEvent(new Event('focus'))"
}
