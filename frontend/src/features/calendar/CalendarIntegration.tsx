import { useEffect, useMemo, useRef, useState } from 'react';
import CalendarIcon from '../../components/ui/icons/calendar.svg?react';
import { toApiError } from '../../api/ApiError';
import type { CalendarStatusDto } from '../../api/types';
import { notifyRecordsChanged } from '../records/recordsEvents';
import {
  beginGoogleCalendarConnect,
  disconnectGoogleCalendar,
  fetchCalendarStatus,
  refreshGoogleCalendars,
  syncCalendarNow,
  updateCalendarSelection,
} from './api';
import styles from './CalendarIntegration.module.css';

type Props = { collapsed?: boolean };
type Notice = { kind: 'success' | 'error'; text: string } | null;

function formatSync(value: string | null): string {
  if (!value) return 'ещё не синхронизировался';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'время неизвестно';
  return date.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function callbackNotice(): Notice {
  const url = new URL(window.location.href);
  const result = url.searchParams.get('calendar');
  if (!result) return null;
  url.searchParams.delete('calendar');
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
  if (result === 'connected') return { kind: 'success', text: 'Google Calendar подключён.' };
  if (result === 'denied') return { kind: 'error', text: 'Google не выдал доступ к календарю.' };
  return { kind: 'error', text: 'Не удалось завершить подключение Google Calendar.' };
}

export function CalendarIntegration({ collapsed = false }: Props) {
  const rootRef = useRef<HTMLDivElement>(null);
  const initialNotice = useMemo(callbackNotice, []);
  const [open, setOpen] = useState(Boolean(initialNotice));
  const [status, setStatus] = useState<CalendarStatusDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(initialNotice);

  const load = async () => {
    setLoading(true);
    try {
      setStatus(await fetchCalendarStatus());
    } catch (error) {
      setNotice({ kind: 'error', text: toApiError(error).message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const run = async (key: string, action: () => Promise<void>) => {
    if (busy) return;
    setBusy(key);
    setNotice(null);
    try {
      await action();
    } catch (error) {
      setNotice({ kind: 'error', text: toApiError(error).message });
    } finally {
      setBusy(null);
    }
  };

  const connected = Boolean(status?.connected);
  const stateLabel = loading
    ? 'Проверка календаря…'
    : connected
      ? 'Google Calendar подключён'
      : status?.configured
        ? 'Подключить Google Calendar'
        : 'Google Calendar не настроен';

  return (
    <div ref={rootRef} className={`${styles.wrapper} ${collapsed ? styles.collapsed : ''}`}>
      <button
        type="button"
        className={`${styles.trigger} ${connected ? styles.connected : ''}`}
        onClick={() => setOpen(value => !value)}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-label={collapsed ? stateLabel : undefined}
        title={collapsed ? stateLabel : undefined}
      >
        <span className={styles.icon}><CalendarIcon /></span>
        {!collapsed && <span className={styles.triggerText}>{stateLabel}</span>}
        {!collapsed && <span className={`${styles.dot} ${connected ? styles.dotOn : ''}`} aria-hidden="true" />}
      </button>

      {open && (
        <section className={`${styles.panel} ${collapsed ? styles.panelCollapsed : ''}`} role="dialog" aria-label="Интеграция с Google Calendar">
          <header className={styles.header}>
            <div>
              <div className={styles.eyebrow}>Интеграция</div>
              <h3>Google Calendar</h3>
            </div>
            <span className={`${styles.statusPill} ${connected ? styles.statusOn : ''}`}>
              {connected ? 'Подключён' : 'Не подключён'}
            </span>
          </header>

          {notice && <p className={`${styles.notice} ${styles[notice.kind]}`} role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p>}

          {loading ? (
            <div className={styles.skeleton}>Загрузка настроек…</div>
          ) : !status?.configured ? (
            <div className={styles.emptyState}>
              <p>Добавь OAuth Client Google в <code>.env</code>, затем пересоздай контейнер приложения.</p>
              <span>Точные переменные перечислены в P4-README.md.</span>
            </div>
          ) : !connected ? (
            <div className={styles.connectBlock}>
              <p>Встречи и события Google появятся среди записей AgendAI, а новые встречи и напоминания смогут отправляться в выбранный календарь.</p>
              {status.status === 'REAUTH_REQUIRED' && <p className={styles.reauth}>Доступ Google истёк или был отозван. Подключи аккаунт повторно.</p>}
              <button className={styles.primaryButton} type="button" disabled={busy !== null} onClick={() => void run('connect', beginGoogleCalendarConnect)}>
                {busy === 'connect' ? 'Открываем Google…' : 'Подключить Google Calendar'}
              </button>
            </div>
          ) : (
            <>
              <div className={styles.syncSummary}>
                <div><span>Последняя синхронизация</span><strong>{formatSync(status.last_sync_at)}</strong></div>
                <button
                  type="button"
                  className={styles.syncButton}
                  disabled={busy !== null}
                  onClick={() => void run('sync', async () => {
                    const result = await syncCalendarNow();
                    setStatus(await fetchCalendarStatus());
                    notifyRecordsChanged();
                    setNotice({ kind: 'success', text: `Готово: получено изменений — ${result.events_applied}, отправлено — ${result.outbound_processed}.` });
                  })}
                >
                  {busy === 'sync' ? 'Синхронизация…' : 'Синхронизировать'}
                </button>
              </div>

              <div className={styles.listHeader}>
                <div><strong>Календари</strong><span>Выбери, какие читать и куда добавлять новые записи.</span></div>
                <button type="button" disabled={busy !== null} onClick={() => void run('refresh', async () => setStatus(await refreshGoogleCalendars()))}>
                  {busy === 'refresh' ? '…' : 'Обновить'}
                </button>
              </div>

              <div className={styles.calendarList}>
                {status.calendars.length === 0 && <p className={styles.noCalendars}>Google не вернул доступных календарей.</p>}
                {status.calendars.map(calendar => (
                  <article key={calendar.id} className={`${styles.calendarRow} ${calendar.sync_enabled ? styles.calendarActive : ''}`}>
                    <label className={styles.calendarMain}>
                      <input
                        type="checkbox"
                        checked={calendar.sync_enabled}
                        disabled={busy !== null}
                        onChange={event => void run(`calendar-${calendar.id}`, async () => setStatus(await updateCalendarSelection(calendar.id, { sync_enabled: event.target.checked })))}
                      />
                      <span className={styles.checkmark} aria-hidden="true" />
                      <span className={styles.calendarText}>
                        <strong>{calendar.name || 'Без названия'}</strong>
                        <small>{calendar.primary ? 'Основной · ' : ''}{calendar.writable ? 'можно изменять' : 'только чтение'}</small>
                      </span>
                    </label>
                    {calendar.writable && (
                      <button
                        type="button"
                        className={`${styles.targetButton} ${calendar.write_target ? styles.targetActive : ''}`}
                        disabled={busy !== null || !calendar.sync_enabled}
                        title="Новые встречи AgendAI будут создаваться в этом календаре"
                        onClick={() => void run(`target-${calendar.id}`, async () => setStatus(await updateCalendarSelection(calendar.id, { sync_enabled: true, write_target: true })))}
                      >
                        {calendar.write_target ? '✓ Календарь записи' : 'Добавлять сюда'}
                      </button>
                    )}
                  </article>
                ))}
              </div>

              {status.last_error && <p className={styles.providerError}>Последняя ошибка синхронизации: {status.last_error}</p>}

              <div className={styles.actions}>
                <button
                  type="button"
                  className={styles.disconnectButton}
                  disabled={busy !== null}
                  onClick={() => {
                    if (!window.confirm('Отключить Google Calendar? Локальные записи AgendAI останутся.')) return;
                    void run('disconnect', async () => {
                      await disconnectGoogleCalendar();
                      setStatus(await fetchCalendarStatus());
                      setNotice({ kind: 'success', text: 'Google Calendar отключён.' });
                    });
                  }}
                >
                  {busy === 'disconnect' ? 'Отключение…' : 'Отключить Google Calendar'}
                </button>
              </div>
            </>
          )}
        </section>
      )}
    </div>
  );
}
