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
  source: 'local' | 'google_calendar';
  all_day: boolean;
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


/** GET /api/history */
export type HistoryMessageDto = {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  created_at: string;
  item: ItemDto | null;
};

export type HistoryResponseDto = {
  messages: HistoryMessageDto[];
};

export type AuthUserDto = {
  id: string;
  email: string | null;
  display_name: string | null;
  timezone: string;
  tts_voice: string;
};

export type TtsVoiceDto = {
  id: string;
  name: string;
  gender: 'female' | 'male';
};

export type TtsVoicesResponseDto = {
  voices: TtsVoiceDto[];
};

export type AuthResponseDto = {
  access_token: string;
  token_type: 'Bearer';
  expires_in: number;
  user: AuthUserDto;
};

export type ExternalCalendarDto = {
  id: string;
  name: string;
  timezone: string | null;
  access_role: string | null;
  primary: boolean;
  writable: boolean;
  sync_enabled: boolean;
  write_target: boolean;
  last_sync_at: string | null;
};

export type CalendarStatusDto = {
  configured: boolean;
  connected: boolean;
  provider: 'GOOGLE';
  status: 'CONNECTED' | 'REAUTH_REQUIRED' | 'ERROR' | 'DISCONNECTED' | string;
  last_sync_at: string | null;
  last_error: string | null;
  calendars: ExternalCalendarDto[];
};

export type CalendarConnectResponseDto = {
  authorization_url: string;
};

export type CalendarSyncResponseDto = {
  calendars_synced: number;
  events_applied: number;
  outbound_processed: number;
};
