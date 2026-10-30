import { useEffect, useRef, useState } from 'react';
import { disablePush, enablePush, pushEnabled, pushSupported, restorePushSubscription } from './push';
import styles from './PushToggle.module.css';

type Status = 'checking' | 'enabled' | 'disabled' | 'working';

/** User-controlled Web Push setting; browser permission is requested only on click. */
export function PushToggle({ collapsed = false }: { collapsed?: boolean }) {
  const [status, setStatus] = useState<Status>('checking');
  const [error, setError] = useState('');
  const supported = pushSupported();
  const inFlight = useRef(false);

  useEffect(() => {
    let active = true;
    if (!supported) {
      setStatus('disabled');
      return;
    }
    void (async () => {
      try {
        if (Notification.permission === 'granted') await restorePushSubscription();
        const enabled = await pushEnabled();
        if (active) setStatus(enabled ? 'enabled' : 'disabled');
      } catch (e: unknown) {
        if (active) {
          setStatus((await pushEnabled().catch(() => false)) ? 'enabled' : 'disabled');
          setError(e instanceof Error ? e.message : 'Не удалось восстановить подписку');
        }
      }
    })();
    return () => { active = false; };
  }, [supported]);

  const busy = status === 'checking' || status === 'working';
  const enabled = status === 'enabled';
  const label = busy ? 'Проверка уведомлений…' : enabled ? 'Уведомления включены' : 'Включить уведомления';

  async function toggle() {
    if (!supported || inFlight.current || busy) return;
    inFlight.current = true;
    setError('');
    const wasEnabled = enabled;
    setStatus('working');
    try {
      if (wasEnabled) await disablePush();
      else await enablePush();
      // Read actual browser subscription instead of assuming the operation succeeded.
      setStatus((await pushEnabled()) ? 'enabled' : 'disabled');
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Не удалось изменить настройки уведомлений');
      try {
        setStatus((await pushEnabled()) ? 'enabled' : 'disabled');
      } catch {
        setStatus(wasEnabled ? 'enabled' : 'disabled');
      }
    } finally {
      inFlight.current = false;
    }
  }

  const hint = !supported
    ? 'Уведомления доступны в поддерживаемом браузере через HTTPS или localhost.'
    : error;

  return (
    <div className={`${styles.container} ${collapsed ? styles.collapsed : ''}`}>
      <button
        type="button"
        className={`${styles.button} ${enabled ? styles.active : ''}`}
        onClick={() => { void toggle(); }}
        disabled={!supported || busy}
        aria-label={collapsed ? label : undefined}
        aria-pressed={enabled}
        aria-busy={busy}
        title={collapsed ? (hint || label) : undefined}
      >
        <span className={styles.icon} aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
            <path d="M10 21h4" />
            {!enabled && !busy && <path d="M4 4l16 16" />}
          </svg>
        </span>
        {!collapsed && <span className={styles.label}>{busy ? 'Подождите…' : label}</span>}
        {!collapsed && <span className={`${styles.switch} ${enabled ? styles.switchOn : ''}`} aria-hidden="true"><span className={styles.thumb} /></span>}
      </button>
      {!collapsed && hint && <p className={styles.message} role={error ? 'alert' : 'status'}>{hint}</p>}
    </div>
  );
}
