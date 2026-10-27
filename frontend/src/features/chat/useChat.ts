import { useEffect, useRef, useState } from 'react';
import { toApiError, type ApiError } from '../../api/ApiError';
import { uuid } from '../../api/session';
import { notifyRecordsChanged } from '../records/recordsEvents';
import { sendMessage, transcribeAudio } from './api';
import { MAX_TEXT_CHARS } from './constants';
import type { Message, MessageAction, Recording, SendRequest, VoiceStatus } from './types';
import { useRecorder } from './useRecorder';
import { useSpeech } from './useSpeech';

/** Запасная фраза, если бэкенд не прислал свою (FR-5). */
const NOT_HEARD = 'Не расслышал вас, повторите, пожалуйста.';

/** После этих ошибок виновата не запись, поэтому её можно отправить на распознавание ещё раз. */
const RESEND_RECORDING = new Set([
  'STT_TIMEOUT',
  'NETWORK_ERROR',
  'CLIENT_TIMEOUT',
  'SERVER_UNAVAILABLE',
  'INTERNAL_ERROR',
  'BAD_RESPONSE',
  'RATE_LIMITED',
]);

let nextId = 0;
// crypto.randomUUID недоступен на http (кроме localhost), поэтому простой счётчик
const makeId = () => `m${Date.now()}-${nextId++}`;

/** «Подождать и повторить»: если сервер не назвал срок, ждём 5 секунд. */
const waitFor = (error: ApiError) => (error.fallback === 'wait' ? (error.retryAfterSec ?? 5) : undefined);

/**
 * Состояние и действия чата.
 * Голос: микрофон → запись → POST /api/voice → текст попадает в поле ввода (draft),
 * пользователь правит его и сам отправляет → POST /api/message → ответ в чат.
 * Автоматически голос не отправляется.
 *
 * Ошибки: любой сбой превращается в обычное сообщение ассистента в чате (текст приходит от бэкенда)
 * с кнопками («Повторить», «Записать снова», «Ввести текстом»), чтобы пользователь мог сразу исправить ситуацию.
 * Когда пользователь повторяет действие, сообщение об ошибке убирается из чата.
 *
 * Озвучивание (useSpeech): вслух читаются ответы ассистента (speech_text, а если его нет — reply_text),
 * его просьба повторить («Не расслышал вас…») и все ошибки под полем ввода. Не читается «Думаю…». Речь замолкает, когда пользователь начинает запись
 * или отправляет новое сообщение.
 */
export function useChat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState('');
  const [isThinking, setIsThinking] = useState(false);
  const [isTranscribing, setIsTranscribing] = useState(false);
  const [quickReplies, setQuickReplies] = useState<string[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  // Текст в поле появился из голоса: при отправке сообщим бэкенду source = "voice"
  const voiceUsedRef = useRef(false);
  // Последняя запись, которую не удалось распознать из-за сбоя (чтобы повторить без новой записи)
  const lastRecordingRef = useRef<Recording | null>(null);
  const startRecordingRef = useRef<() => void>(() => undefined);
  const speech = useSpeech();

  const focusInputAtEnd = () => {
    // Ждём кадр: к этому времени React уже вставил текст в поле
    requestAnimationFrame(() => {
      const input = inputRef.current;
      if (!input) return;
      input.focus();
      input.setSelectionRange(input.value.length, input.value.length);
    });
  };

  const changeDraft = (value: string) => {
    setDraft(value);
    if (!value.trim()) voiceUsedRef.current = false;
  };

  // ---------- Голос ----------

  /** Убирает из чата прежние сообщения-ошибки: пользователь пробует снова, старая ошибка уже не нужна. */
  const dropErrors = () => setMessages((prev) => (prev.some((m) => m.isError) ? prev.filter((m) => !m.isError) : prev));

  /** Ошибка = сообщение ассистента в чате (с кнопками исправления) + озвучивание. */
  const postError = (text: string, actions: MessageAction[] = []) => {
    setMessages((prev) => [...prev, { id: makeId(), role: 'assistant', text, isError: true, actions }]);
    speech.speak(text);
  };

  const typeInstead: MessageAction = {
    label: 'Ввести текстом',
    onClick: () => {
      dropErrors();
      focusInputAtEnd();
    },
  };

  const voiceError = (error: ApiError) => {
    const actions: MessageAction[] = [];
    if (error.fallback === 'reload') {
      actions.push({ label: 'Обновить страницу', onClick: () => window.location.reload() });
    } else if (RESEND_RECORDING.has(error.code) && lastRecordingRef.current) {
      actions.push({ label: 'Повторить', waitSec: waitFor(error), onClick: () => void transcribe(lastRecordingRef.current!) });
    } else if (error.fallback === 'retry' || error.fallback === 'wait') {
      actions.push({ label: 'Записать снова', onClick: () => startRecordingRef.current() });
    }
    actions.push(typeInstead);
    postError(error.message, actions);
  };

  const transcribe = async (recording: Recording) => {
    lastRecordingRef.current = recording;
    speech.cancel();
    dropErrors();
    setIsTranscribing(true);
    try {
      const result = await transcribeAudio(recording);
      lastRecordingRef.current = null;
      const text = result.text.trim();
      if (!result.recognized || !text) {
        // Речь не распознана: сообщение от ассистента в чат, поле ввода не трогаем (S2, FR-5)
        const notHeard = result.assistantMessage ?? NOT_HEARD;
        setMessages((prev) => [...prev, { id: makeId(), role: 'assistant', text: notHeard }]);
        speech.speak(notHeard);
        return;
      }
      // Если в поле уже что-то набрано, распознанный текст дописывается через пробел
      voiceUsedRef.current = true;
      setDraft((prev) => (prev.trim() ? `${prev.trim()} ${text}` : text));
      focusInputAtEnd();
    } catch (error) {
      voiceError(toApiError(error));
    } finally {
      setIsTranscribing(false);
    }
  };

  const recorder = useRecorder({ onFinish: (recording) => void transcribe(recording) });

  useEffect(() => {
    startRecordingRef.current = () => {
      speech.cancel(); // иначе микрофон запишет голос ассистента
      dropErrors();
      recorder.clearError();
      void recorder.start();
    };
  });

  const voiceStatus: VoiceStatus = isTranscribing ? 'transcribing' : recorder.status;

  /** Кнопка микрофона: первый клик начинает запись, второй заканчивает. */
  const toggleRecording = () => {
    speech.unlock(); // клик — действие пользователя: после него Safari разрешает речь
    if (voiceStatus === 'recording') {
      recorder.stop();
    } else if (voiceStatus === 'idle') {
      lastRecordingRef.current = null;
      startRecordingRef.current();
    }
  };

  // ---------- Отправка сообщения ----------

  const sendError = (error: ApiError, retry: () => void) => {
    const actions: MessageAction[] = [];
    if (error.fallback === 'reload') {
      actions.push({ label: 'Обновить страницу', onClick: () => window.location.reload() });
    } else if (error.fallback === 'retry' || error.fallback === 'wait') {
      actions.push({ label: 'Повторить', waitSec: waitFor(error), onClick: retry });
    }
    postError(error.message, actions);
  };

  /** Одна попытка доставки. Повтор после сбоя использует тот же clientMessageId: запись не задвоится. */
  const deliver = async (request: SendRequest, userMessageId: string) => {
    speech.cancel();
    dropErrors();
    setIsThinking(true);
    try {
      const reply = await sendMessage(request);
      setMessages((prev) => [...prev, { id: reply.id || makeId(), role: 'assistant', text: reply.text }]);
      setQuickReplies(reply.quickReplies);
      speech.speak(reply.speechText ?? reply.text);
      if (reply.dataChanged) notifyRecordsChanged(); // боковое меню перезапросит записи
    } catch (error) {
      const apiError = toApiError(error);
      if (apiError.fallback === 'rephrase') {
        // Сообщение не принято (пустое, слишком длинное): убираем из чата и возвращаем текст в поле
        setMessages((prev) => prev.filter((m) => m.id !== userMessageId));
        setDraft((prev) => (prev.trim() ? prev : request.text));
        postError(apiError.message);
        focusInputAtEnd();
      } else {
        sendError(apiError, () => void deliver(request, userMessageId));
      }
    } finally {
      setIsThinking(false);
    }
  };

  /** Отправка. Без аргумента отправляет текст из поля ввода, с аргументом — готовую фразу (подсказки). */
  const send = async (override?: string) => {
    speech.unlock(); // вызывается из клика, до первого await
    const text = (override ?? draft).trim();
    if (!text || isThinking || voiceStatus !== 'idle') return;

    if (text.length > MAX_TEXT_CHARS) {
      postError(`Сообщение слишком длинное (максимум ${MAX_TEXT_CHARS} символов). Сократите его и отправьте снова.`);
      return;
    }

    const fromField = override === undefined;
    const source = fromField && voiceUsedRef.current ? 'voice' : 'text';
    if (fromField) {
      setDraft('');
      voiceUsedRef.current = false;
    }
    setQuickReplies([]);
    const userMessage: Message = { id: makeId(), role: 'user', text };
    setMessages((prev) => [...prev, userMessage]);
    await deliver({ text, source, clientMessageId: uuid() }, userMessage.id);
  };

  // Ошибки микрофона (нет доступа, нет устройства) приходят от useRecorder простым текстом:
  // превращаем в сообщение ассистента с кнопкой «Ввести текстом» и сбрасываем, чтобы повторная ошибка тоже появилась
  useEffect(() => {
    if (!recorder.error) return;
    postError(recorder.error, [typeInstead]);
    recorder.clearError();
  }, [recorder.error]);

  return {
    messages,
    draft,
    setDraft: changeDraft,
    isThinking,
    voiceStatus,
    elapsedSec: recorder.elapsedSec,
    quickReplies,
    speech: {
      supported: speech.supported,
      enabled: speech.enabled,
      setEnabled: speech.setEnabled,
      speaking: speech.speaking,
      voiceMissing: speech.voiceMissing,
    },
    inputRef,
    toggleRecording,
    send,
  };
}
