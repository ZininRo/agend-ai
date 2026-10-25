
import { request } from '../../api/http';
import type { ItemDto, ItemType, ItemsResponseDto } from '../../api/types';
import type { Month, RecordItem, RecordKind } from './types';
import { getTimezone } from '../../api/session';

// Типы записей на бэкенде: task | meeting | reminder. В меню третий раздел называется «События»,
// поэтому reminder показывается там (подпись раздела — в Sidebar.tsx).
const KIND_BY_TYPE: Record<ItemType, RecordKind> = {
  task: 'task',
  meeting: 'meeting',
  reminder: 'event',
};

const pad = (n: number) => String(n).padStart(2, '0');

/** Первый и последний день месяца в формате YYYY-MM-DD (оба включительно, как ждёт бэкенд). */
function monthRange({ year, month }: Month) {
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
  if (!kind || !main) return null;

  const item: RecordItem = { id: dto.id, kind, title: dto.title, date: main.slice(0, 10) };
  if (kind !== 'task') {
    item.startTime = main.slice(11, 16);
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
