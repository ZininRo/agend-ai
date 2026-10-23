import { useCallback, useEffect, useRef, useState } from 'react';
import { MAX_RECORDING_SEC } from './constants';
import type { Recording } from './types';

export type RecorderStatus = 'idle' | 'requesting' | 'recording';

type Options = {
  /** Вызывается с готовой записью — и при ручной остановке, и при автоостановке по лимиту. */
  onFinish: (recording: Recording) => void;
};

const MIN_DURATION_MS = 600;

// Chrome/Firefox пишут webm/opus, Safari — только mp4. Берём первый формат, который умеет браузер.
const MIME_CANDIDATES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];

const MESSAGES = {
  unsupported:
    'Запись голоса не поддерживается: нужен современный браузер и защищённое соединение (HTTPS). Введите сообщение текстом.',
  denied:
    'Доступ к микрофону запрещён. Разрешите его в настройках браузера (значок рядом с адресом) или введите сообщение текстом.',
  notFound: 'Микрофон не найден. Подключите его или введите сообщение текстом.',
  busy: 'Не удаётся получить доступ к микрофону: возможно, он занят другим приложением.',
  failed: 'Не удалось записать звук. Попробуйте ещё раз или введите сообщение текстом.',
  tooShort: 'Запись слишком короткая. Нажмите на микрофон, скажите фразу и нажмите ещё раз.',
};

function pickMimeType(): string | undefined {
  return MIME_CANDIDATES.find((type) => MediaRecorder.isTypeSupported(type));
}

function describeError(error: unknown): string {
  const name = error instanceof DOMException ? error.name : '';
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return MESSAGES.denied;
    case 'NotFoundError':
    case 'OverconstrainedError':
      return MESSAGES.notFound;
    case 'NotReadableError':
    case 'AbortError':
      return MESSAGES.busy;
    default:
      return MESSAGES.failed;
  }
}

/**
 * Запись с микрофона: запрос разрешения, запись, автоостановка по лимиту и
 * гарантированное освобождение микрофона (индикатор записи в браузере гаснет).
 * Ничего не знает ни про чат, ни про распознавание: отдаёт готовую запись в onFinish.
 */
export function useRecorder({ onFinish }: Options) {
  const [status, setStatus] = useState<RecorderStatus>('idle');
  const [elapsedSec, setElapsedSec] = useState(0);
  const [error, setError] = useState<string | null>(null);

  // statusRef нужен, чтобы быстрые повторные клики не запускали вторую запись
  const statusRef = useRef<RecorderStatus>('idle');
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const tickRef = useRef<number | undefined>(undefined);
  const limitRef = useRef<number | undefined>(undefined);
  const mountedRef = useRef(true);
  const onFinishRef = useRef(onFinish);

  useEffect(() => {
    onFinishRef.current = onFinish;
  });

  const changeStatus = useCallback((next: RecorderStatus) => {
    statusRef.current = next;
    setStatus(next);
  }, []);

  /** Останавливает таймеры и дорожки микрофона. */
  const releaseDevice = useCallback(() => {
    window.clearInterval(tickRef.current);
    window.clearTimeout(limitRef.current);
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    recorderRef.current = null;
  }, []);

  const stop = useCallback(() => {
    const recorder = recorderRef.current;
    if (recorder && recorder.state === 'recording') recorder.stop();
  }, []);

  const start = useCallback(async () => {
    if (statusRef.current !== 'idle') return;
    setError(null);

    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      setError(MESSAGES.unsupported);
      return;
    }

    // Браузер покажет окно с запросом разрешения; ждём ответ пользователя
    changeStatus('requesting');
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      if (mountedRef.current) {
        changeStatus('idle');
        setError(describeError(e));
      }
      return;
    }
    if (!mountedRef.current) {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }

    const mimeType = pickMimeType();
    let recorder: MediaRecorder;
    try {
      recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    } catch {
      stream.getTracks().forEach((track) => track.stop());
      changeStatus('idle');
      setError(MESSAGES.failed);
      return;
    }

    const chunks: Blob[] = [];
    const startedAt = Date.now();

    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunks.push(event.data);
    };
    recorder.onerror = () => {
      recorder.onstop = null;
      releaseDevice();
      changeStatus('idle');
      setElapsedSec(0);
      setError(MESSAGES.failed);
    };
    recorder.onstop = () => {
      const durationMs = Date.now() - startedAt;
      const type = recorder.mimeType || mimeType || 'audio/webm';
      releaseDevice();
      changeStatus('idle');
      setElapsedSec(0);
      if (durationMs < MIN_DURATION_MS || chunks.length === 0) {
        setError(MESSAGES.tooShort);
        return;
      }
      onFinishRef.current({ blob: new Blob(chunks, { type }), mimeType: type, durationMs });
    };

    recorderRef.current = recorder;
    streamRef.current = stream;
    recorder.start();
    changeStatus('recording');
    setElapsedSec(0);
    tickRef.current = window.setInterval(
      () => setElapsedSec(Math.floor((Date.now() - startedAt) / 1000)),
      250,
    );
    limitRef.current = window.setTimeout(stop, MAX_RECORDING_SEC * 1000);
  }, [changeStatus, releaseDevice, stop]);

  // При закрытии страницы/размонтировании: не оставляем микрофон включённым и не отправляем запись
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      const recorder = recorderRef.current;
      if (recorder) {
        recorder.onstop = null;
        recorder.ondataavailable = null;
        if (recorder.state !== 'inactive') recorder.stop();
      }
      releaseDevice();
    };
  }, [releaseDevice]);

  const clearError = useCallback(() => setError(null), []);

  return { status, elapsedSec, error, clearError, start, stop };
}
