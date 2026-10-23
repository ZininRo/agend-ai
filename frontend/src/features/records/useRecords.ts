import { useMemo } from 'react';
import { MOCK_RECORDS } from './mocks';
import type { Month, RecordItem, RecordsByKind } from './types';

function isInMonth(iso: string, { year, month }: Month): boolean {
  return Number(iso.slice(0, 4)) === year && Number(iso.slice(5, 7)) - 1 === month;
}

function byDateTime(a: RecordItem, b: RecordItem): number {
  return a.date.localeCompare(b.date) || (a.startTime ?? '').localeCompare(b.startTime ?? '');
}

/**
 * Записи выбранного месяца, сгруппированные по типу и отсортированные по дате.
 * Сейчас данные берутся из моков. При подключении API меняется только тело
 * этого хука (useEffect + fetch), сигнатура и компоненты остаются прежними.
 */
export function useRecords(month: Month): RecordsByKind {
  return useMemo(() => {
    const result: RecordsByKind = { task: [], meeting: [], event: [] };
    for (const record of MOCK_RECORDS.filter((r) => isInMonth(r.date, month)).sort(byDateTime)) {
      result[record.kind].push(record);
    }
    return result;
  }, [month]);
}
