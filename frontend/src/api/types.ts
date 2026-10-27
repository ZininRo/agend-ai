// Формы данных так, как их отдаёт бэкенд (snake_case, см. backend: ItemDto, ApiError и др.).
// В компоненты они попадают только через функции-преобразователи в features/*/api.ts.

export type ItemType = 'task' | 'meeting' | 'reminder';
export type ItemStatus = 'todo' | 'done' | 'cancelled';

export type ItemDto = {
  id: string;
  type: ItemType;
  title: string;
  description: string | null;
  /** ISO-8601 со смещением пояса пользователя: 2026-10-24T15:00:00+03:00 */
  start_at: string | null;
  end_at: string | null;
  due_at: string | null;
  place: string | null;
  status: ItemStatus;
  created_at: string;
  updated_at: string;
};

/** GET /api/items */
export type ItemsResponseDto = {
  from: string;
  to: string;
  items: ItemDto[];
};

/** POST /api/voice */
export type VoiceResponseDto = {
  recognized: boolean;
  text: string;
  confidence: number | null;
  assistant_message: string | null;
};

export type ReplyResult = 'done' | 'info' | 'clarification' | 'refused';

/** POST /api/message */
export type MessageResponseDto = {
  message_id: string;
  result: ReplyResult;
  intent: string | null;
  reply_text: string;
  speech_text: string | null;
  items: ItemDto[];
  data_changed: boolean;
  quick_replies: string[];
};
