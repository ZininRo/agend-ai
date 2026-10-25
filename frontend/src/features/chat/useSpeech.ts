import { useCallback, useEffect, useRef, useState } from 'react';
import { hasNoRussianVoice, isSpeechSupported, pickRussianVoice, toSpokenParts } from './speech';

const STORAGE_KEY = 'agendai.tts.enabled';

/** Настройка «озвучивать ответы» хранится в браузере; по умолчанию включено. */
function readEnabled(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) !== '0';
  } catch {
    return true;
  }
}

/**
 * Озвучивание ответов и ошибок ассистента через встроенный в браузер синтез речи (speechSynthesis).
 * Ничего не знает о чате: получает готовый текст в speak() и читает его вслух по-русски.
 *
 * - speak(text) заменяет то, что говорится сейчас; новая реплика не накладывается на прежнюю;
 * - cancel() останавливает речь (перед записью голоса, иначе микрофон услышит ассистента);
 * - unlock() вызывается из обработчика клика: Safari и iOS разрешают речь только после действия пользователя.
 */
export function useSpeech() {
  const supported = isSpeechSupported();
  const [enabled, setEnabledState] = useState(readEnabled);
  const [speaking, setSpeaking] = useState(false);
  const [voiceMissing, setVoiceMissing] = useState(false);

  const enabledRef = useRef(enabled);
  const voiceMissingRef = useRef(voiceMissing);
  const unlockedRef = useRef(false);
  // «Поколение» речи: события отменённых фраз приходят позже и не должны менять состояние
  const generationRef = useRef(0);
  const startTimerRef = useRef<number | undefined>(undefined);
  // Chrome теряет событие onend, если фразу собрал сборщик мусора, поэтому держим ссылки
  const utterancesRef = useRef<SpeechSynthesisUtterance[]>([]);

  useEffect(() => {
    enabledRef.current = enabled;
    voiceMissingRef.current = voiceMissing;
  });

  // Голоса в Chrome подгружаются асинхронно: следим за событием voiceschanged
  useEffect(() => {
    if (!supported) return;
    const synth = window.speechSynthesis;
    const update = () => setVoiceMissing(hasNoRussianVoice(synth.getVoices()));
    update();
    synth.addEventListener?.('voiceschanged', update);
    return () => synth.removeEventListener?.('voiceschanged', update);
  }, [supported]);

  const cancel = useCallback(() => {
    if (!supported) return;
    generationRef.current += 1;
    window.clearTimeout(startTimerRef.current);
    utterancesRef.current = [];
    window.speechSynthesis.cancel();
    setSpeaking(false);
  }, [supported]);

  const speak = useCallback(
    (text: string) => {
      if (!supported || !enabledRef.current || voiceMissingRef.current) return;
      const parts = toSpokenParts(text);
      if (parts.length === 0) return;

      cancel();
      const generation = generationRef.current;
      // Короткая пауза после cancel(): в части версий Chrome фраза, начатая в тот же такт, пропадает
      startTimerRef.current = window.setTimeout(() => {
        const synth = window.speechSynthesis;
        const voice = pickRussianVoice(synth.getVoices());
        const utterances = parts.map((part, index) => {
          const utterance = new SpeechSynthesisUtterance(part);
          utterance.lang = 'ru-RU';
          if (voice) utterance.voice = voice;
          if (index === 0) {
            utterance.onstart = () => {
              if (generationRef.current === generation) setSpeaking(true);
            };
          }
          const finished = () => {
            if (generationRef.current === generation) setSpeaking(false);
          };
          if (index === parts.length - 1) utterance.onend = finished;
          utterance.onerror = finished;
          return utterance;
        });
        utterancesRef.current = utterances;
        utterances.forEach((utterance) => synth.speak(utterance));
      }, 30);
    },
    [supported, cancel],
  );

  /** Первая «тихая» фраза внутри клика открывает речь в Safari и на iOS; повторять не нужно. */
  const unlock = useCallback(() => {
    if (!supported || !enabledRef.current || unlockedRef.current) return;
    unlockedRef.current = true;
    const silent = new SpeechSynthesisUtterance('');
    silent.volume = 0;
    window.speechSynthesis.speak(silent);
  }, [supported]);

  const setEnabled = useCallback(
    (value: boolean) => {
      setEnabledState(value);
      enabledRef.current = value;
      try {
        localStorage.setItem(STORAGE_KEY, value ? '1' : '0');
      } catch {
        /* настройка не сохранится, это не критично */
      }
      if (value) unlock(); // клик по переключателю — как раз действие пользователя
      else cancel();
    },
    [unlock, cancel],
  );

  // Уходим со страницы: не продолжаем говорить
  useEffect(() => cancel, [cancel]);

  return { supported, enabled, setEnabled, speaking, voiceMissing, speak, cancel, unlock };
}
