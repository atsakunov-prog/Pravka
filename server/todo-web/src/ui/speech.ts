// Голос браузера (Chrome): нажал — говоришь сколько нужно, хоть с паузами, — нажал ещё раз, текст в поле. Там, где
// распознавания нет (WebView Правки, Firefox), кнопки микрофона нет: на компе диктует Диктовка (Ctrl+Shift+Space) прямо
// в поле, на телефоне — родная диктовка Правки.
import { useRef, useState } from 'react';

type Rec = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start: () => void;
  stop: () => void;
  onresult: ((e: any) => void) | null;
  onend: (() => void) | null;
  onerror: ((e: any) => void) | null;
};

const Ctor = (): (new () => Rec) | null => (globalThis as any).SpeechRecognition || (globalThis as any).webkitSpeechRecognition || null;
export const canSpeak = () => !!Ctor();

/** Микрофон: on — пишет; onText(весь сказанный текст до сих пор). */
export function useSpeech(onText: (said: string, final: boolean) => void) {
  const [on, setOn] = useState(false);
  const rec = useRef<Rec | null>(null);
  const start = () => {
    const C = Ctor();
    if (!C || rec.current) return;
    const r = new C();
    r.lang = 'ru-RU';
    r.continuous = true;
    r.interimResults = true;
    let done = '';
    r.onresult = (e: any) => {
      let live = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const x = e.results[i];
        if (x.isFinal) done += (done ? ' ' : '') + x[0].transcript.trim();
        else live += x[0].transcript;
      }
      onText((done + (live ? ' ' + live.trim() : '')).trim(), !live);
    };
    // Chrome сам гасит распознавание после паузы — пока кнопка нажата, поднимаем снова.
    r.onend = () => {
      if (rec.current === r) {
        try {
          r.start();
        } catch {
          rec.current = null;
          setOn(false);
        }
      }
    };
    r.onerror = (e: any) => {
      if (e?.error === 'not-allowed' || e?.error === 'service-not-allowed') {
        rec.current = null;
        setOn(false);
      }
    };
    rec.current = r;
    try {
      r.start();
      setOn(true);
    } catch {
      rec.current = null;
    }
  };
  const stop = () => {
    const r = rec.current;
    rec.current = null;
    setOn(false);
    try {
      r?.stop();
    } catch {
      /* уже остановлен */
    }
  };
  return { on, start, stop, toggle: () => (rec.current ? stop() : start()) };
}
