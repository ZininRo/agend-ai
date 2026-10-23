// Форма сообщения совпадает с таблицей messages из ТЗ (role, text),
// поэтому ответ бэкенда ляжет в эти типы без преобразований.
export type MessageRole = 'user' | 'assistant';

export type Message = {
  id: string;
  role: MessageRole;
  text: string;
};

/** Готовая запись с микрофона: это уходит на распознавание. */
export type Recording = {
  blob: Blob;
  /** Реальный формат записи: в Chrome webm/opus, в Safari mp4. Нужен бэкенду для декодирования. */
  mimeType: string;
  durationMs: number;
};

/**
 * Состояние голосового ввода:
 * idle → requesting (ждём разрешение) → recording → transcribing (распознаём) → idle
 */
export type VoiceStatus = 'idle' | 'requesting' | 'recording' | 'transcribing';
