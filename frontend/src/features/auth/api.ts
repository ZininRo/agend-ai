import { clearAccessToken, setAccessToken } from '../../api/authToken';
import { request } from '../../api/http';
import { resetSessionId, getTimezone } from '../../api/session';
import type { AuthResponseDto, AuthUserDto } from '../../api/types';
import { disablePush, pushSupported } from '../push/push';

export async function login(email: string, password: string): Promise<AuthUserDto> {
  const result = await request<AuthResponseDto>('/api/auth/login', {
    method: 'POST',
    auth: false,
    json: { email, password },
  });
  setAccessToken(result.access_token);
  resetSessionId();
  return result.user;
}

export async function register(email: string, password: string, displayName: string): Promise<AuthUserDto> {
  const result = await request<AuthResponseDto>('/api/auth/register', {
    method: 'POST',
    auth: false,
    json: {
      email,
      password,
      display_name: displayName.trim() || null,
      timezone: getTimezone(),
    },
  });
  setAccessToken(result.access_token);
  resetSessionId();
  return result.user;
}

export async function logout(): Promise<void> {
  try {
    // A browser push subscription identifies the device, not the account. Remove it while the
    // current access token is still valid so a shared browser cannot receive the previous user's
    // reminders after logout.
    if (pushSupported()) {
      try {
        await disablePush();
      } catch {
        // Logout must remain available even when Push API/network cleanup fails.
      }
    }
    await request<void>('/api/auth/logout', { method: 'POST', auth: false });
  } finally {
    clearAccessToken();
    resetSessionId();
  }
}
