import { request } from '../../api/http';
import type {
  CalendarConnectResponseDto,
  CalendarStatusDto,
  CalendarSyncResponseDto,
} from '../../api/types';

export function fetchCalendarStatus(): Promise<CalendarStatusDto> {
  return request<CalendarStatusDto>('/api/calendar/status');
}

export async function beginGoogleCalendarConnect(): Promise<void> {
  const response = await request<CalendarConnectResponseDto>('/api/calendar/google/connect', {
    method: 'POST',
  });
  if (!response.authorization_url) throw new Error('Сервер не вернул ссылку подключения Google Calendar.');
  window.location.assign(response.authorization_url);
}

export function disconnectGoogleCalendar(): Promise<void> {
  return request<void>('/api/calendar/google/disconnect', { method: 'POST' });
}

export function refreshGoogleCalendars(): Promise<CalendarStatusDto> {
  return request<CalendarStatusDto>('/api/calendar/refresh-calendars', { method: 'POST' });
}

export function updateCalendarSelection(
  calendarId: string,
  patch: { sync_enabled?: boolean; write_target?: boolean },
): Promise<CalendarStatusDto> {
  return request<CalendarStatusDto>(`/api/calendar/calendars/${calendarId}`, {
    method: 'PATCH',
    json: patch,
  });
}

export function syncCalendarNow(): Promise<CalendarSyncResponseDto> {
  return request<CalendarSyncResponseDto>('/api/calendar/sync', { method: 'POST', timeoutMs: 30_000 });
}
