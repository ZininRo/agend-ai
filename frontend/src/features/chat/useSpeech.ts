import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchSpeech } from './api';
import { hasNoRussianVoice, isSpeechSupported, pickRussianVoice, toSpokenParts } from './speech';

const STORAGE_KEY = 'agendai.tts.enabled';

function readEnabled(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) !== '0';
  } catch {
    return true;
  }
}

function isAudioSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof Audio !== 'undefined' &&
    typeof URL !== 'undefined' &&
    typeof URL.createObjectURL === 'function'
  );
}

/**
 * Озвучивание ответов:
 * 1) для сохранённого assistant-message запрашивает красивый серверный TTS;
 * 2) если TTS недоступен/таймаут/браузер запретил autoplay, использует speechSynthesis как fallback;
 * 3) локальные ошибки UI сразу читаются browser fallback, потому что у них нет message_id в БД.
 */
export function useSpeech() {
  const browserSupported = isSpeechSupported();
  const audioSupported = isAudioSupported();
  const supported = audioSupported || browserSupported;

  const [enabled, setEnabledState] = useState(readEnabled);
  const [speaking, setSpeaking] = useState(false);
  const [browserVoiceMissing, setBrowserVoiceMissing] = useState(false);

  const enabledRef = useRef(enabled);
  const browserVoiceMissingRef = useRef(browserVoiceMissing);
  const unlockedRef = useRef(false);
  const generationRef = useRef(0);
  const startTimerRef = useRef<number | undefined>(undefined);
  const utterancesRef = useRef<SpeechSynthesisUtterance[]>([]);
  const requestRef = useRef<AbortController | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => {
    enabledRef.current = enabled;
    browserVoiceMissingRef.current = browserVoiceMissing;
  });

  useEffect(() => {
    if (!browserSupported) return;
    const synth = window.speechSynthesis;
    const update = () => setBrowserVoiceMissing(hasNoRussianVoice(synth.getVoices()));
    update();
    synth.addEventListener?.('voiceschanged', update);
    return () => synth.removeEventListener?.('voiceschanged', update);
  }, [browserSupported]);

  const releaseAudio = useCallback(() => {
    const audio = audioRef.current;
    audioRef.current = null;
    if (audio) {
      audio.onplay = null;
      audio.onended = null;
      audio.onerror = null;
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
    }
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }
  }, []);

  const cancel = useCallback(() => {
    generationRef.current += 1;
    window.clearTimeout(startTimerRef.current);
    requestRef.current?.abort();
    requestRef.current = null;
    releaseAudio();
    utterancesRef.current = [];
    if (browserSupported) window.speechSynthesis.cancel();
    setSpeaking(false);
  }, [browserSupported, releaseAudio]);

  const speakWithBrowser = useCallback(
    (text: string, generation: number) => {
      if (!browserSupported || browserVoiceMissingRef.current || !enabledRef.current) return;
      const parts = toSpokenParts(text);
      if (parts.length === 0) return;

      window.clearTimeout(startTimerRef.current);
      startTimerRef.current = window.setTimeout(() => {
        if (generationRef.current !== generation || !enabledRef.current) return;
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
    [browserSupported],
  );

  const speak = useCallback(
    (messageId: string | null, text: string) => {
      if (!supported || !enabledRef.current || !text.trim()) return;

      cancel();
      const generation = generationRef.current;
      const fallback = () => {
        if (generationRef.current !== generation || !enabledRef.current) return;
        releaseAudio();
        setSpeaking(false);
        speakWithBrowser(text, generation);
      };

      if (!messageId || !audioSupported) {
        fallback();
        return;
      }

      const controller = new AbortController();
      requestRef.current = controller;
      void fetchSpeech(messageId, controller.signal)
        .then((blob) => {
          if (controller.signal.aborted || generationRef.current !== generation || !enabledRef.current) return;
          requestRef.current = null;
          if (blob.size === 0) {
            fallback();
            return;
          }

          const objectUrl = URL.createObjectURL(blob);
          objectUrlRef.current = objectUrl;
          const audio = new Audio(objectUrl);
          audio.preload = 'auto';
          audioRef.current = audio;

          let fellBack = false;
          const fail = () => {
            if (fellBack) return;
            fellBack = true;
            fallback();
          };
          audio.onplay = () => {
            if (generationRef.current === generation) setSpeaking(true);
          };
          audio.onended = () => {
            if (generationRef.current !== generation) return;
            releaseAudio();
            setSpeaking(false);
          };
          audio.onerror = fail;
          void audio.play().catch(fail);
        })
        .catch(() => {
          if (requestRef.current === controller) requestRef.current = null;
          if (!controller.signal.aborted) fallback();
        });
    },
    [audioSupported, cancel, releaseAudio, speakWithBrowser, supported],
  );

  /** Открывает browser fallback в Safari/iOS; server TTS при этом остаётся основным. */
  const unlock = useCallback(() => {
    if (!browserSupported || !enabledRef.current || unlockedRef.current) return;
    unlockedRef.current = true;
    const silent = new SpeechSynthesisUtterance('');
    silent.volume = 0;
    window.speechSynthesis.speak(silent);
  }, [browserSupported]);

  const setEnabled = useCallback(
    (value: boolean) => {
      setEnabledState(value);
      enabledRef.current = value;
      try {
        localStorage.setItem(STORAGE_KEY, value ? '1' : '0');
      } catch {
        /* настройка не сохранится, это не критично */
      }
      if (value) unlock();
      else cancel();
    },
    [unlock, cancel],
  );

  useEffect(() => cancel, [cancel]);

  // Отсутствие системного русского голоса не выключает переключатель: server TTS работает независимо.
  const voiceMissing = !audioSupported && browserVoiceMissing;
  return { supported, enabled, setEnabled, speaking, voiceMissing, speak, cancel, unlock };
}
