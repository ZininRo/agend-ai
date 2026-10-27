// Форма сообщения совпадает с таблицей messages из ТЗ (role, text),
// поэтому ответ бэкенда ляжет в эти типы без преобразований.
export type MessageRole = 'user' | 'assistant';

/** Кнопка под сообщением ассистента об ошибке («Повторить», «Ввести текстом»…). */
export type MessageAction = {
  label: string;
  onClick: () => void;
  /** Кнопка станет активной через столько секунд (RATE_LIMITED, «попробуйте через минуту») */
  waitSec?: number;
};

export type Message = {
  id: string;
  role: MessageRole;
  text: string;
  /** Сообщение-ошибка от ассистента: исчезает, когда пользователь повторяет действие */
  isError?: boolean;
  /** Кнопки исправления; показываются, только пока это последнее сообщение в чате */
  actions?: MessageAction[];
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

/** Результат распознавания (POST /api/voice). */
export type Transcription = {
  recognized: boolean;
  text: string;
  /** Если речь не распознана, бэкенд присылает фразу ассистента: «Не расслышал вас, повторите, пожалуйста.» */
  assistantMessage: string | null;
};

/** Ответ ассистента (POST /api/message). */
export type AssistantReply = {
  id: string;
  /** done — данные изменены, info — показаны, clarification — ассистент задал вопрос, refused — не понял */
  result: 'done' | 'info' | 'clarification' | 'refused';
  text: string;
  /** Короткий текст для озвучивания (если null — озвучивать text) */
  speechText: string | null;
  /** true: ассистент изменил записи, боковое меню нужно обновить */
  dataChanged: boolean;
  /** Готовые варианты ответа на уточняющий вопрос */
  quickReplies: string[];
};

/** Откуда пришёл текст сообщения: после голоса бэкенд осторожнее с сомнительными местами. */
export type MessageSource = 'voice' | 'text';

export type SendRequest = {
  text: string;
  source: MessageSource;
  /** Один на попытку отправки: повтор с тем же id не создаёт запись второй раз */
  clientMessageId: string;
};
