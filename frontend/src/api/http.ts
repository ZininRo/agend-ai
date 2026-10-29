import { API_BASE } from './env';
import { ApiError, FALLBACKS, clientError, errorFromStatus, type Fallback } from './ApiError';

// Формат ошибки бэкенда: { "error": { code, message, retryable, retry_after_sec, fallback }, "request_id" }
type ErrorBody = {
  error?: {
    code?: string;
    message?: string;
    retryable?: boolean;
    retry_after_sec?: number | null;
    fallback?: string;
  };
  request_id?: string;
};

const DEFAULT_TIMEOUT_MS = 15_000;

type Options = {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  /** Тело JSON */
  json?: unknown;
  /** Тело multipart (загрузка аудио). Заголовок Content-Type браузер ставит сам. */
  form?: FormData;
  query?: Record<string, string | string[] | undefined>;
  timeoutMs?: number;
  /** Отмена запроса вызывающим (смена месяца, уход со страницы) */
  signal?: AbortSignal;
};

function buildUrl(path: string, query: Options['query']): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined) continue;
    for (const v of Array.isArray(value) ? value : [value]) params.append(key, v);
  }
  const qs = params.toString();
  return `${API_BASE}${path}${qs ? `?${qs}` : ''}`;
}

async function parseError(response: Response): Promise<ApiError> {
  const body = (await response.json().catch(() => null)) as ErrorBody | null;
  const e = body?.error;
  if (!e?.code || !e.message) return errorFromStatus(response.status);

  const header = Number(response.headers.get('Retry-After'));
  return new ApiError({
    code: e.code,
    message: e.message,
    status: response.status,
    retryable: e.retryable ?? false,
    retryAfterSec: e.retry_after_sec ?? (Number.isFinite(header) && header > 0 ? header : null),
    fallback: FALLBACKS.includes(e.fallback ?? '') ? (e.fallback as Fallback) : 'retry',
    requestId: body?.request_id ?? response.headers.get('X-Request-Id'),
  });
}

/**
 * Единая точка запросов к бэкенду. Успех — разобранный JSON.
 * Любой сбой — ApiError с готовым текстом для пользователя; отмена через signal пробрасывается как AbortError.
 */
export async function request<T>(path: string, options: Options = {}): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const forwardAbort = () => controller.abort();
  options.signal?.addEventListener('abort', forwardAbort);

  try {
    const response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers: options.json !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: options.json !== undefined ? JSON.stringify(options.json) : options.form,
      signal: controller.signal,
    });
    if (!response.ok) throw await parseError(response);
    if (response.status === 204) return undefined as T;
    try {
      return (await response.json()) as T;
    } catch {
      throw clientError('BAD_RESPONSE', response.status);
    }
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (options.signal?.aborted) throw error;
    throw clientError(timedOut ? 'CLIENT_TIMEOUT' : 'NETWORK_ERROR');
  } finally {
    window.clearTimeout(timer);
    options.signal?.removeEventListener('abort', forwardAbort);
  }
}
