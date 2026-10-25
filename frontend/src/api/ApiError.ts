/** Подсказка бэкенда: что предложить пользователю (поле error.fallback). */
export type Fallback = 'retry' | 'text_input' | 'rephrase' | 'wait' | 'reload';

export const FALLBACKS: readonly string[] = ['retry', 'text_input', 'rephrase', 'wait', 'reload'];

type Init = {
  code: string;
  message: string;
  status: number;
  retryable: boolean;
  retryAfterSec: number | null;
  fallback: Fallback;
  requestId: string | null;
};

/**
 * Любая ошибка обращения к серверу. message — готовый русский текст для пользователя:
 * его присылает бэкенд (каталог ErrorCode) или он создан здесь для сбоев на стороне клиента.
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly retryable: boolean;
  readonly retryAfterSec: number | null;
  readonly fallback: Fallback;
  readonly requestId: string | null;

  constructor(init: Init) {
    super(init.message);
    this.name = 'ApiError';
    this.code = init.code;
    this.status = init.status;
    this.retryable = init.retryable;
    this.retryAfterSec = init.retryAfterSec;
    this.fallback = init.fallback;
    this.requestId = init.requestId;
  }
}

// Ошибки, которые бэкенд не присылает: нет сети, таймаут, ответ не по контракту.
const CLIENT_ERRORS = {
  NETWORK_ERROR: {
    message: 'Нет связи с сервером. Проверьте интернет и повторите.',
    fallback: 'retry',
  },
  CLIENT_TIMEOUT: {
    message: 'Сервер отвечает слишком долго. Попробуйте ещё раз.',
    fallback: 'retry',
  },
  BAD_RESPONSE: {
    message: 'Сервер вернул непонятный ответ. Попробуйте ещё раз.',
    fallback: 'retry',
  },
  SERVER_UNAVAILABLE: {
    message: 'Сервер временно недоступен. Попробуйте через минуту.',
    fallback: 'wait',
  },
} as const satisfies Record<string, { message: string; fallback: Fallback }>;

export type ClientErrorCode = keyof typeof CLIENT_ERRORS;

export function clientError(code: ClientErrorCode, status = 0): ApiError {
  const { message, fallback } = CLIENT_ERRORS[code];
  return new ApiError({ code, message, status, retryable: true, retryAfterSec: null, fallback, requestId: null });
}

/**
 * Ответ с ошибкой, но не в нашем формате. Настоящий бэкенд всегда отвечает JSON с полем error,
 * поэтому любой 5xx без него — это прокси (Vite, nginx), за которым бэкенд не запущен или упал.
 */
export function errorFromStatus(status: number): ApiError {
  return clientError(status >= 500 ? 'SERVER_UNAVAILABLE' : 'BAD_RESPONSE', status);
}

export function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : clientError('NETWORK_ERROR');
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
