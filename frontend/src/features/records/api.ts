
import { request } from '../../api/http';
import type { ItemDto, ItemType, ItemStatus, ItemsResponseDto } from '../../api/types';
import type { Month, RecordItem, RecordKind } from './types';
import { getTimezone } from '../../api/session';
import { API_BASE } from '../../api/env';

// Типы записей соответствуют ItemType backend.
const KIND_BY_TYPE: Record<ItemType, RecordKind> = {
  task: 'task',
  meeting: 'meeting',
  reminder: 'reminder',
};

const pad = (n: number) => String(n).padStart(2, '0');

/** Первый и последний день месяца в формате YYYY-MM-DD (оба включительно, как ждёт бэкенд). */
export function monthRange({ year, month }: Month) {
  const lastDay = new Date(year, month + 1, 0).getDate();
  const prefix = `${year}-${pad(month + 1)}`;
  return { from: `${prefix}-01`, to: `${prefix}-${pad(lastDay)}` };
}

/**
 * DTO бэкенда → запись меню. Бэкенд отдаёт даты в поясе пользователя
 * (мы передаём timezone), поэтому дату и время берём срезом строки ISO:
 * «2026-10-24T15:00:00+03:00» → дата «2026-10-24», время «15:00».
 * Основная дата: срок (due_at) у задач, начало (start_at) у встреч и напоминаний.
 */
export function toRecordItem(dto: ItemDto): RecordItem | null {
  const kind = KIND_BY_TYPE[dto.type];
  const main = kind === 'task' ? dto.due_at : dto.start_at;
  if (!kind) return null;
  const dateValue = main ?? dto.created_at;
  if (!dateValue) return null;

  const item: RecordItem = { id: dto.id, kind, title: dto.title, description: dto.description ?? undefined, status: dto.status, date: dateValue.slice(0, 10), createdAt: dto.created_at, updatedAt: dto.updated_at, startAt: dto.start_at, endAt: dto.end_at, dueAt: dto.due_at };
  if (kind !== 'task') {
    if (main) item.startTime = main.slice(11, 16);
    if (dto.end_at) item.endTime = dto.end_at.slice(11, 16);
    if (dto.place) item.place = dto.place;
  }
  return item;
}

/** GET /api/items за месяц. */
export async function fetchRecords(month: Month, signal?: AbortSignal): Promise<RecordItem[]> {
  const { from, to } = monthRange(month);
  const response = await request<ItemsResponseDto>('/api/items', {
    query: { from, to, timezone: getTimezone() },
    signal,
  });
  return response.items.map(toRecordItem).filter((item): item is RecordItem => item !== null);
}

/** Изменение статуса и мягкое удаление через backend API. */
export async function updateRecordStatus(id: string, status: ItemStatus): Promise<ItemDto> {
  return request<ItemDto>(`/api/items/${encodeURIComponent(id)}/status`, {
    method: 'PATCH', json: { status }, query: { timezone: getTimezone() },
  });
}

export async function deleteRecord(id: string): Promise<void> {
  return request<void>(`/api/items/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/** Редактирование полей записи. Даты — ISO 8601 с часовым смещением. */
export async function updateRecord(id: string, changes: {
  title: string; description: string | null; place: string | null;
  startAt: string | null; endAt: string | null; dueAt: string | null; status: ItemStatus;
}): Promise<ItemDto> {
  return request<ItemDto>(`/api/items/${encodeURIComponent(id)}`, {
    method: 'PATCH', query: { timezone: getTimezone() }, json: { title: changes.title, description: changes.description, place: changes.place,
      start_at: changes.startAt, end_at: changes.endAt, due_at: changes.dueAt, status: changes.status },
  });
}


export type ExportFormat = 'csv' | 'ics';

/** Скачивает записи выбранного месяца: CSV для Excel/таблиц или ICS для календаря. */
export async function exportRecords(month: Month, format: ExportFormat): Promise<void> {
  const { from, to } = monthRange(month);
  const params = new URLSearchParams({ from, to, timezone: getTimezone(), format });
  const response = await fetch(`${API_BASE}/api/items/export?${params.toString()}`);
  if (!response.ok) throw new Error('Не удалось экспортировать записи. Попробуйте ещё раз.');

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = format === 'csv' ? `agendai-${from}-${to}.csv` : `agendai-${from}-${to}.ics`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
