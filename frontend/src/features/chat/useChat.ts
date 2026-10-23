import { useRef, useState } from 'react';
import { sendMessage, transcribeAudio } from './api';
import { MOCK_MESSAGES } from './mocks';
import type { Message, Recording, VoiceStatus } from './types';
import { useRecorder } from './useRecorder';

const ERRORS = {
  noSpeech: 'Не расслышал. Попробуйте ещё раз или введите сообщение текстом.',
  transcribe: 'Не удалось распознать речь. Попробуйте ещё раз или введите сообщение текстом.',
  reply: 'Не удалось получить ответ. Попробуйте ещё раз.',
};

let nextId = 0;
// crypto.randomUUID недоступен на http (кроме localhost), поэтому простой счётчик
const makeId = () => `m${Date.now()}-${nextId++}`;

/**
 * Состояние и действия чата.
 * Голос: микрофон → запись → распознавание → текст попадает в поле ввода (draft),
 * пользователь правит его и сам отправляет. Автоматически голос не отправляется.
 *
 * Сейчас сообщения в начале берутся из моков, а по адресу `/?chat=empty`
 * чат открывается пустым (экран с большим микрофоном).
 */
export function useChat() {
  const [messages, setMessages] = useState<Message[]>(() =>
    new URLSearchParams(window.location.search).get('chat') === 'empty' ? [] : MOCK_MESSAGES,
  );
  const [draft, setDraft] = useState('');
  const [isThinking, setIsThinking] = useState(false);
  const [isTranscribing, setIsTranscribing] = useState(false);
  const [chatError, setChatError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const focusInputAtEnd = () => {
    // Ждём кадр: к этому времени React уже вставил распознанный текст в поле
    requestAnimationFrame(() => {
      const input = inputRef.current;
      if (!input) return;
      input.focus();
      input.setSelectionRange(input.value.length, input.value.length);
    });
  };

  const handleRecording = async (recording: Recording) => {
    setIsTranscribing(true);
    try {
      const text = (await transcribeAudio(recording)).trim();
      if (!text) {
        setChatError(ERRORS.noSpeech);
        return;
      }
      // Если в поле уже что-то набрано, распознанный текст дописывается через пробел
      setDraft((prev) => (prev.trim() ? `${prev.trim()} ${text}` : text));
      focusInputAtEnd();
    } catch {
      setChatError(ERRORS.transcribe);
    } finally {
      setIsTranscribing(false);
    }
  };

  const recorder = useRecorder({ onFinish: handleRecording });

  const voiceStatus: VoiceStatus = isTranscribing ? 'transcribing' : recorder.status;

  const clearErrors = () => {
    setChatError(null);
    recorder.clearError();
  };

  /** Кнопка микрофона: первый клик начинает запись, второй заканчивает. */
  const toggleRecording = () => {
    if (voiceStatus === 'recording') {
      recorder.stop();
    } else if (voiceStatus === 'idle') {
      clearErrors();
      void recorder.start();
    }
  };

  /** Отправка. Без аргумента отправляет текст из поля ввода, с аргументом — готовую фразу (подсказки). */
  const send = async (override?: string) => {
    const text = (override ?? draft).trim();
    if (!text || isThinking || voiceStatus !== 'idle') return;

    clearErrors();
    if (override === undefined) setDraft('');
    setMessages((prev) => [...prev, { id: makeId(), role: 'user', text }]);
    setIsThinking(true);
    try {
      const reply = await sendMessage(text);
      setMessages((prev) => [...prev, { id: makeId(), role: 'assistant', text: reply }]);
    } catch {
      setChatError(ERRORS.reply);
    } finally {
      setIsThinking(false);
    }
  };

  return {
    messages,
    draft,
    setDraft,
    isThinking,
    voiceStatus,
    elapsedSec: recorder.elapsedSec,
    error: recorder.error ?? chatError,
    inputRef,
    toggleRecording,
    send,
  };
}
