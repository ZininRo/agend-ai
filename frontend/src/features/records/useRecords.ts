import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { ApiError, isAbortError, toApiError } from '../../api/ApiError';
import { fetchRecords } from './api';
import { getRecordsVersion, subscribeRecords } from './recordsEvents';
import type { Month, RecordItem, RecordsByKind } from './types';

export type RecordsStatus = 'loading' | 'ready' | 'error';

function byDateTime(a: RecordItem, b: RecordItem): number {
  return a.date.localeCompare(b.date) || (a.startTime ?? '').localeCompare(b.startTime ?? '');
}

/** Раскладывает записи по типам и сортирует по дате и времени. */
function groupRecords(items: RecordItem[]): RecordsByKind {
  const result: RecordsByKind = { task: [], meeting: [], event: [] };
  for (const record of [...items].sort(byDateTime)) result[record.kind].push(record);
  return result;
}

const EMPTY: RecordsByKind = { task: [], meeting: [], event: [] };

type Result = { requestKey: string; monthKey: string; records?: RecordsByKind; error?: ApiError };

/**
 * Записи выбранного месяца: GET /api/items.
 * Перезапрашиваются при смене месяца, по кнопке «Повторить» и когда чат сообщает,
 * что ассистент изменил данные (recordsEvents). Пока идёт перезапрос того же месяца,
 * показываются прежние данные, чтобы список не мигал.
 */
export function useRecords(month: Month) {
  const version = useSyncExternalStore(subscribeRecords, getRecordsVersion);
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState<Result | null>(null);

  const monthKey = `${month.year}-${month.month}`;
  const requestKey = `${monthKey}:${version}:${reloadCount}`;

  useEffect(() => {
    const controller = new AbortController();
    fetchRecords(month, controller.signal)
      .then((items) => setResult({ requestKey, monthKey, records: groupRecords(items) }))
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        setResult({ requestKey, monthKey, error: toApiError(error) });
      });
    return () => controller.abort();
  }, [month, monthKey, requestKey]);

  const reload = useCallback(() => setReloadCount((count) => count + 1), []);

  const fresh = result?.requestKey === requestKey;
  const sameMonth = result?.monthKey === monthKey;
  const status: RecordsStatus = fresh ? (result.error ? 'error' : 'ready') : sameMonth && result?.records ? 'ready' : 'loading';
  const records = sameMonth && result?.records ? result.records : EMPTY;
  return { records, status, error: fresh ? (result.error ?? null) : null, reload };
}
