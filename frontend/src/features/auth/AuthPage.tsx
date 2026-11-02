import { useState, type FormEvent } from 'react';
import LogoIcon from '../../components/ui/icons/logo.svg?react';
import { toApiError } from '../../api/ApiError';
import { useAuth } from './AuthContext';
import styles from './AuthPage.module.css';

type Mode = 'login' | 'register';

export function AuthPage() {
  const auth = useAuth();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const switchMode = (next: Mode) => {
    setMode(next);
    setError(null);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      if (mode === 'login') await auth.login(email.trim(), password);
      else await auth.register(email.trim(), password, displayName);
    } catch (reason) {
      setError(toApiError(reason).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className={styles.page}>
      <section className={styles.card} aria-labelledby="auth-title">
        <div className={styles.brand}>
          <LogoIcon />
          <span>agendAI</span>
        </div>
        <h1 id="auth-title">{mode === 'login' ? 'Вход' : 'Создать аккаунт'}</h1>
        <p className={styles.subtitle}>
          {mode === 'login'
            ? 'Ваши задачи, встречи и история теперь привязаны к аккаунту.'
            : 'Создайте личное пространство для задач, встреч и напоминаний.'}
        </p>

        <div className={styles.tabs} role="tablist" aria-label="Режим авторизации">
          <button type="button" className={mode === 'login' ? styles.activeTab : ''} onClick={() => switchMode('login')}>
            Вход
          </button>
          <button type="button" className={mode === 'register' ? styles.activeTab : ''} onClick={() => switchMode('register')}>
            Регистрация
          </button>
        </div>

        <form className={styles.form} onSubmit={submit}>
          {mode === 'register' && (
            <label>
              <span>Имя</span>
              <input
                autoComplete="name"
                maxLength={100}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="Роман"
              />
            </label>
          )}
          <label>
            <span>Почта</span>
            <input
              required
              type="email"
              autoComplete="email"
              maxLength={320}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="you@example.com"
            />
          </label>
          <label>
            <span>Пароль</span>
            <input
              required
              type="password"
              minLength={8}
              maxLength={72}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="Минимум 8 символов"
            />
          </label>
          {error && <p className={styles.error} role="alert">{error}</p>}
          <button className={styles.submit} type="submit" disabled={submitting}>
            {submitting ? 'Подождите…' : mode === 'login' ? 'Войти' : 'Создать аккаунт'}
          </button>
        </form>
      </section>
    </main>
  );
}
