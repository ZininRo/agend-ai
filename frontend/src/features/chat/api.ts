import { ApiError } from '../../api/ApiError';
import { request } from '../../api/http';
import { getSessionId, getTimezone } from '../../api/session';
import type { MessageResponseDto, VoiceResponseDto } from '../../api/types';
import type { AssistantReply, Recording, SendRequest, Transcription } from './types';

// Запросы чата к настоящему бэкенду:
//   transcribeAudio → POST /api/voice   (multipart: audio + session_id)
//   sendMessage     → POST /api/message ({ session_id, text, timezone, source, client_message_id })

const VOICE_TIMEOUT_MS = 30_000; // распознавание (до 10 с у провайдера + конвертация)
const MESSAGE_TIMEOUT_MS = 40_000; // LLM до 15 с, плюс одна повторная попытка на невалидный JSON

/** Распознавание речи. */
export async function transcribeAudio(recording: Recording): Promise<Transcription> {
  const form = new FormData();
  form.append('audio', recording.blob, `recording.${extensionFor(recording.mimeType)}`);
  form.append('session_id', getSessionId());
  // Справочно: бэкенд сам проверяет длительность по файлу и этому значению не доверяет
  form.append('duration_ms', String(Math.round(recording.durationMs)));

  let data: VoiceResponseDto;
  try {
    data = await request<VoiceResponseDto>('/api/voice', { method: 'POST', form, timeoutMs: VOICE_TIMEOUT_MS });
  } catch (error) {
    // Бэкенд ещё не реализовал POST /api/voice (на неизвестный путь он отвечает 404/405)
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) {
      throw new ApiError({
        code: 'VOICE_NOT_AVAILABLE',
        message: 'Голосовой ввод на сервере пока не подключён. Введите сообщение текстом.',
        status: error.status,
        retryable: false,
        retryAfterSec: null,
        fallback: 'text_input',
        requestId: error.requestId,
      });
    }
    throw error;
  }
  return {
    recognized: data.recognized,
    text: data.text ?? '',
    assistantMessage: data.assistant_message ?? null,
  };
}

/** Ответ ассистента на сообщение. */
export async function sendMessage({ text, source, clientMessageId }: SendRequest): Promise<AssistantReply> {
  const data = await request<MessageResponseDto>('/api/message', {
    method: 'POST',
    json: {
      session_id: getSessionId(),
      text,
      timezone: getTimezone(),
      source,
      client_message_id: clientMessageId,
    },
    timeoutMs: MESSAGE_TIMEOUT_MS,
  });
  return {
    id: data.message_id,
    result: data.result,
    text: data.reply_text,
    speechText: data.speech_text ?? null,
    dataChanged: data.data_changed ?? false,
    quickReplies: data.quick_replies ?? [],
  };
}

function extensionFor(mimeType: string): string {
  if (mimeType.includes('mp4')) return 'mp4';
  if (mimeType.includes('ogg')) return 'ogg';
  return 'webm';
}
