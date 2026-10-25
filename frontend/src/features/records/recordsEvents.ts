// Маленькая «шина» между чатом и боковым меню: когда ассистент изменил данные
// (создал встречу, перенёс задачу), чат вызывает notifyRecordsChanged(), а меню само
// перезапрашивает записи. Компоненты друг о друге не знают и состояние не поднимается наверх.

let version = 0;
const listeners = new Set<() => void>();

export function notifyRecordsChanged(): void {
  version += 1;
  listeners.forEach((listener) => listener());
}

/** Для useSyncExternalStore */
export function subscribeRecords(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getRecordsVersion(): number {
  return version;
}
