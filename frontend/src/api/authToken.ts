import { API_BASE } from './env';
import type { AuthResponseDto } from './types';

export const AUTH_EXPIRED_EVENT = 'agendai:auth-expired';

let accessToken: string | null = null;
let refreshInFlight: Promise<AuthResponseDto | null> | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function clearAccessToken(): void {
  accessToken = null;
}

/**
 * Refresh token intentionally lives only in the HttpOnly cookie. This function is also used by
 * the generic HTTP client when a short-lived access token expires; concurrent 401 responses share
 * a single refresh request so rotation cannot invalidate sibling requests.
 */
export function refreshAccessToken(): Promise<AuthResponseDto | null> {
  if (refreshInFlight) return refreshInFlight;
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 10_000);
  refreshInFlight = fetch(`${API_BASE}/api/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        accessToken = null;
        return null;
      }
      const data = (await response.json()) as AuthResponseDto;
      accessToken = data.access_token;
      return data;
    })
    .catch(() => {
      accessToken = null;
      return null;
    })
    .finally(() => {
      window.clearTimeout(timer);
      refreshInFlight = null;
    });
  return refreshInFlight;
}
