import { useEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import type { ItemStatus } from '../../../api/types';
import { toApiError } from '../../../api/ApiError';
import { Collapsible } from '../../ui/Collapsible';
import { formatDayMonth, formatTimeRange, getDayOfMonth } from '../../../features/records/format';
import { deleteRecord, updateRecord } from '../../../features/records/api';
import { notifyRecordsChanged } from '../../../features/records/recordsEvents';
import type { RecordItem } from '../../../features/records/types';
import { NavItem } from './NavItem';
import styles from './RecordsSection.module.css';

type Props = { label: string; icon: ReactNode; items: RecordItem[]; expanded: boolean; onToggle: () => void; emptyText?: string };
const STATUSES: { value: ItemStatus; label: string }[] = [
  { value: 'todo', label: 'Запланировано' },
  { value: 'done', label: 'Выполнено' },
  { value: 'cancelled', label: 'Отменено' },
];
const statusLabel = (status: ItemStatus) => STATUSES.find(s => s.value === status)?.label ?? status;

export function RecordsSection({ label, icon, items, expanded, onToggle, emptyText = 'Нет записей' }: Props) {
  return <section className={`${styles.section} ${expanded ? styles.open : ''}`}>
    <NavItem icon={icon} label={`${label} (${items.length})`} expanded={expanded} onClick={onToggle} />
    <Collapsible open={expanded}>
      <ul className={styles.list}>
        {items.length === 0 && <li className={`${styles.empty} ${styles.plain}`}>{emptyText}</li>}
        {items.map(item => <RecordRow key={item.id} item={item} />)}
      </ul>
    </Collapsible>
  </section>;
}

function RecordRow({ item }: { item: RecordItem }) {
  const [open, setOpen] = useState(false);
  const isTask = item.kind === 'task';
  return <li className={`${styles.row} ${isTask ? styles.plain : ''}`}>
    {!isTask && <span className={styles.day}>{getDayOfMonth(item.date)}</span>}
    <button type="button" className={styles.recordButton} onClick={() => setOpen(true)} aria-label={`Открыть запись ${item.title}`}>
      <span className={styles.recordHeading}>
        <span className={`${styles.dot} ${styles[item.status]}`} title={statusLabel(item.status)} />
        <span className={styles.title}>{item.title}</span>
      </span>
      <span className={styles.meta}>{isTask ? formatDayMonth(item.date) : `${formatDayMonth(item.date)}${item.startTime ? ` · ${formatTimeRange(item)}` : ''}`}</span>
    </button>
    {open && <RecordDialog item={item} onClose={() => setOpen(false)} />}
  </li>;
}

function toLocal(value?: string | null): string {
  return value ? value.slice(0, 16) : '';
}
function toIso(value: string, original?: string | null): string | null {
  if (!value) return null;
  const offset = original?.match(/([+-]\d{2}:\d{2}|Z)$/)?.[1];
  const date = new Date(offset ? `${value}:00${offset}` : value);
  if (Number.isNaN(date.getTime())) throw new Error('Укажи корректную дату и время');
  return date.toISOString();
}

function RecordDialog({ item, onClose }: { item: RecordItem; onClose: () => void }) {
  const [title, setTitle] = useState(item.title);
  const [description, setDescription] = useState(item.description ?? '');
  const [place, setPlace] = useState(item.place ?? '');
  const [status, setStatus] = useState<ItemStatus>(item.status);
  const [startAt, setStartAt] = useState(toLocal(item.startAt));
  const [endAt, setEndAt] = useState(toLocal(item.endAt));
  const [dueAt, setDueAt] = useState(toLocal(item.dueAt));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !busy) onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose, busy]);

  async function save() {
    if (busy) return;
    setError(null);
    if (!title.trim()) { setError('Название не может быть пустым'); return; }
    setBusy(true);
    try {
      await updateRecord(item.id, {
        title: title.trim(), description: description.trim() || null, place: place.trim() || null,
        status, startAt: toIso(startAt, item.startAt), endAt: toIso(endAt, item.endAt), dueAt: toIso(dueAt, item.dueAt),
      });
      notifyRecordsChanged();
      onClose();
    } catch (e) { setError(e instanceof Error && e.message === 'Укажи корректную дату и время' ? e.message : toApiError(e).message); }
    finally { setBusy(false); }
  }

  async function remove() {
    if (busy || !window.confirm(`Удалить запись «${item.title}»? Это действие нельзя отменить.`)) return;
    setBusy(true); setError(null);
    try { await deleteRecord(item.id); notifyRecordsChanged(); onClose(); }
    catch (e) { setError(toApiError(e).message); }
    finally { setBusy(false); }
  }

  return createPortal(<div className={styles.overlay} onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose(); }}>
    <section className={styles.dialog} role="dialog" aria-modal="true" aria-label={`Редактирование: ${item.title}`}>
      <header className={styles.dialogHeader}><h2>Детали записи</h2><button type="button" className={styles.close} onClick={onClose} disabled={busy} aria-label="Закрыть">×</button></header>
      <div className={styles.form}>
        <label>Название<input value={title} onChange={e => setTitle(e.target.value)} maxLength={255} disabled={busy} /></label>
        <label>Описание<textarea rows={3} value={description} onChange={e => setDescription(e.target.value)} disabled={busy} /></label>
        <label>Статус<select className={`${styles.statusField} ${styles[status]}`} value={status} onChange={e => setStatus(e.target.value as ItemStatus)} disabled={busy}>
          {STATUSES.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select></label>
        {item.kind === 'task' ? <label>Срок выполнения<input type="datetime-local" value={dueAt} onChange={e => setDueAt(e.target.value)} disabled={busy} required /></label> : <label>Начало<input type="datetime-local" value={startAt} onChange={e => setStartAt(e.target.value)} disabled={busy} required /></label>}
        {item.kind === 'meeting' && <label>Окончание<input type="datetime-local" value={endAt} onChange={e => setEndAt(e.target.value)} disabled={busy} /></label>}
        {item.kind === 'meeting' && <label>Место<input value={place} maxLength={255} onChange={e => setPlace(e.target.value)} disabled={busy} /></label>}
        <p className={styles.readonly}>Тип: {item.kind === 'task' ? 'Задача' : item.kind === 'meeting' ? 'Встреча' : 'Напоминание'}</p>
        {item.createdAt && <p className={styles.readonly}>Создано: {new Date(item.createdAt).toLocaleString('ru-RU')}</p>}
        {item.updatedAt && <p className={styles.readonly}>Изменено: {new Date(item.updatedAt).toLocaleString('ru-RU')}</p>}
        {error && <p role="alert" className={styles.error}>{error}</p>}
      </div>
      <footer className={styles.footer}>
        <button type="button" className={styles.deleteButton} onClick={() => void remove()} disabled={busy}>Удалить</button>
        <div className={styles.footerRight}><button type="button" className={styles.cancelButton} onClick={onClose} disabled={busy}>Отмена</button><button type="button" className={styles.saveButton} onClick={() => void save()} disabled={busy}>{busy ? 'Сохранение…' : 'Сохранить'}</button></div>
      </footer>
    </section>
  </div>, document.body);
}
