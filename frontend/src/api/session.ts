/**
 * UUID v4. crypto.randomUUID работает только в защищённом контексте (https или localhost),
 * поэтому запасной путь собирает UUID из getRandomValues (он доступен всегда).
 */
export function uuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const SESSION_KEY = 'agendai.session_id';
let memorySessionId: string | null = null;

/**
 * Идентификатор сессии = одна вкладка браузера (FR-17): живёт в sessionStorage,
 * поэтому переживает перезагрузку страницы, а новая вкладка начинает новый диалог.
 * Если хранилище недоступно, id держится в памяти до закрытия страницы.
 */
export function getSessionId(): string {
  try {
    const saved = sessionStorage.getItem(SESSION_KEY);
    if (saved) return saved;
    const id = uuid();
    sessionStorage.setItem(SESSION_KEY, id);
    return id;
  } catch {
    memorySessionId ??= uuid();
    return memorySessionId;
  }
}

/**
 * Часовой пояс пользователя (IANA). Передаётся и в /api/items, и в /api/message:
 * бэкенд считает по нему границы дней и слова «завтра», «в пятницу».
 */
export function getTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Moscow';
  } catch {
    return 'Europe/Moscow';
  }
}
