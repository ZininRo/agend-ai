import { ApiError, FALLBACKS, clientError, errorFromStatus, type Fallback } from './ApiError';
import { AUTH_EXPIRED_EVENT, getAccessToken, refreshAccessToken } from './authToken';
import { API_BASE } from './env';

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
  /** false только для login/register/logout: эти endpoints не требуют access token. */
  auth?: boolean;
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

async function fetchOnce(path: string, options: Options, signal: AbortSignal): Promise<Response> {
  const headers = new Headers();
  if (options.json !== undefined) headers.set('Content-Type', 'application/json');
  if (options.auth !== false) {
    const token = getAccessToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);
  }
  return fetch(buildUrl(path, options.query), {
    method: options.method ?? 'GET',
    headers,
    body: options.json !== undefined ? JSON.stringify(options.json) : options.form,
    signal,
    credentials: 'include',
  });
}

async function fetchWithRefresh(path: string, options: Options, signal: AbortSignal): Promise<Response> {
  let response = await fetchOnce(path, options, signal);
  const canRefresh = options.auth !== false && !path.startsWith('/api/auth/');
  if (response.status === 401 && canRefresh && !signal.aborted) {
    const refreshed = await refreshAccessToken();
    if (refreshed && !signal.aborted) {
      response = await fetchOnce(path, options, signal);
      if (response.status === 401) window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    } else if (!signal.aborted) {
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    }
  }
  return response;
}

async function withResponse<T>(
  path: string,
  options: Options,
  parseSuccess: (response: Response) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const forwardAbort = () => controller.abort();
  options.signal?.addEventListener('abort', forwardAbort);

  try {
    const response = await fetchWithRefresh(path, options, controller.signal);
    if (!response.ok) throw await parseError(response);
    return await parseSuccess(response);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (options.signal?.aborted) throw error;
    throw clientError(timedOut ? 'CLIENT_TIMEOUT' : 'NETWORK_ERROR');
  } finally {
    window.clearTimeout(timer);
    options.signal?.removeEventListener('abort', forwardAbort);
  }
}

/**
 * Единая точка JSON-запросов к бэкенду. Access token живёт только в памяти; при 401 клиент один
 * раз делает refresh по HttpOnly cookie и повторяет исходный запрос.
 */
export async function request<T>(path: string, options: Options = {}): Promise<T> {
  return withResponse(path, options, async (response) => {
    if (response.status === 204) return undefined as T;
    try {
      return (await response.json()) as T;
    } catch {
      throw clientError('BAD_RESPONSE', response.status);
    }
  });
}

/** Авторизованная загрузка бинарного ответа (CSV/ICS/аудио). */
export async function requestBlob(path: string, options: Options = {}): Promise<Blob> {
  return withResponse(path, options, (response) => response.blob());
}
