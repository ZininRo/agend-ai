import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { AUTH_EXPIRED_EVENT, refreshAccessToken } from '../../api/authToken';
import type { AuthUserDto } from '../../api/types';
import * as authApi from './api';

type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated';

type AuthContextValue = {
  status: AuthStatus;
  user: AuthUserDto | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  setTtsVoice: (voice: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<AuthUserDto | null>(null);

  useEffect(() => {
    let cancelled = false;
    const expire = () => {
      setUser(null);
      setStatus('unauthenticated');
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, expire);
    void refreshAccessToken().then((result) => {
      if (cancelled) return;
      if (result) {
        setUser(result.user);
        setStatus('authenticated');
      } else {
        expire();
      }
    });
    return () => {
      cancelled = true;
      window.removeEventListener(AUTH_EXPIRED_EVENT, expire);
    };
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      login: async (email, password) => {
        const nextUser = await authApi.login(email, password);
        setUser(nextUser);
        setStatus('authenticated');
      },
      register: async (email, password, displayName) => {
        const nextUser = await authApi.register(email, password, displayName);
        setUser(nextUser);
        setStatus('authenticated');
      },
      setTtsVoice: async (voice) => {
        const nextUser = await authApi.updateTtsVoice(voice);
        setUser(nextUser);
      },
      logout: async () => {
        try {
          await authApi.logout();
        } finally {
          setUser(null);
          setStatus('unauthenticated');
        }
      },
    }),
    [status, user],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
