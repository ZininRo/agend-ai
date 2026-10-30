import { useState, type ReactNode } from 'react';
import LogoIcon from '../../ui/icons/logo.svg?react'
import DoubleChevronLeftIcon from '../../ui/icons/double_chevron_left.svg?react'
// import ChatIcon from '../../ui/icons/chat.svg?react'
import PencilIcon from '../../ui/icons/pencil.svg?react'
import TasksIcon from '../../ui/icons/task.svg?react'
import MeetingsIcon from '../../ui/icons/meetings.svg?react'
import EventsIcon from '../../ui/icons/events2.svg?react'

import { Collapsible } from '../../ui/Collapsible';
import { formatMonthLabel } from '../../../features/records/format';
import type { RecordKind } from '../../../features/records/types';
import { useMonth } from '../../../features/records/useMonth';
import { useRecords } from '../../../features/records/useRecords';
import { exportRecords, type ExportFormat } from '../../../features/records/api';
import { MonthPicker } from './MonthPicker';
import { NavItem } from './NavItem';
import { RecordsSection } from './RecordsSection';
import { RecordsStatus } from './RecordStatus';
import { UserMenu } from './UserMenu';
import { PushToggle } from '../../../features/push/PushToggle';
import styles from './Sidebar.module.css';

// Разделы «Мои записи»: порядок и подписи как в макете.
const RECORD_SECTIONS: { kind: RecordKind; label: string; icon: ReactNode }[] = [
  { kind: 'task', label: 'Задачи', icon: <TasksIcon /> },
  { kind: 'meeting', label: 'Встречи', icon: <MeetingsIcon /> },
  { kind: 'reminder', label: 'Напоминания', icon: <EventsIcon /> },
];

type Props = {
  collapsed: boolean;
  onToggleCollapsed: () => void;
};

export function Sidebar({ collapsed, onToggleCollapsed }: Props) {
  const [recordsOpen, setRecordsOpen] = useState(true);
  const [openKinds, setOpenKinds] = useState<Record<RecordKind, boolean>>({
    task: false,
    meeting: false,
    reminder: false,
  });
  const { month, select } = useMonth();
  const { records, status, error, reload } = useRecords(month);
  const [exporting, setExporting] = useState<ExportFormat | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);

  // Текст ошибки сервера про «сохранение данных» к чтению не подходит, поэтому для сбоев
  // самого сервера пишем свой текст; про отсутствие сети — готовый текст из ApiError.
  const loadError =
    error && (error.code === 'NETWORK_ERROR' || error.code === 'CLIENT_TIMEOUT')
      ? error.message
      : 'Не удалось загрузить записи. Попробуйте ещё раз.';

  const toggleKind = (kind: RecordKind) =>
    setOpenKinds((state) => ({ ...state, [kind]: !state[kind] }));

  // В свёрнутом меню «Мои записи» — только иконка: клик разворачивает меню
  // и сразу показывает вложенные блоки.
  const expandRecords = () => {
    onToggleCollapsed();
    setRecordsOpen(true);
  };

  const downloadExport = async (format: ExportFormat) => {
    if (exporting) return;
    setExportError(null);
    setExporting(format);
    try {
      await exportRecords(month, format);
    } catch (error) {
      setExportError(error instanceof Error ? error.message : 'Не удалось экспортировать записи.');
    } finally {
      setExporting(null);
    }
  };

  return (
    <aside className={`${styles.sidebar} ${collapsed ? styles.collapsed : ''}`}>
      <header className={styles.header}>
        {!collapsed && <LogoIcon />}
        {!collapsed && <span className={styles.brand}>agendAI</span>}
        <button
          type="button"
          className={styles.collapse}
          aria-label={collapsed ? 'Развернуть меню' : 'Свернуть меню'}
          aria-expanded={!collapsed}
          onClick={onToggleCollapsed}
        >
          <DoubleChevronLeftIcon />
        </button>
      </header>

      <nav className={styles.nav} aria-label="Основное меню">
        {/* <NavItem icon={<ChatIcon />} label="Чат" collapsed={collapsed} /> */}

        {collapsed ? (
          <NavItem icon={<PencilIcon />} label="Мои записи" collapsed onClick={expandRecords} />
        ) : (
          <>
            <NavItem
              icon={<PencilIcon />}
              label="Мои записи"
              expanded={recordsOpen}
              onClick={() => setRecordsOpen((open) => !open)}
            />
            <Collapsible open={recordsOpen}>
              <MonthPicker label={formatMonthLabel(month)} value={month} onSelect={select} />
              <div className={styles.exportRow} aria-label="Экспорт записей выбранного месяца">
                <button type="button" onClick={() => void downloadExport('csv')} disabled={exporting !== null}>
                  {exporting === 'csv' ? 'Экспорт…' : 'Excel / CSV'}
                </button>
                <button type="button" onClick={() => void downloadExport('ics')} disabled={exporting !== null}>
                  {exporting === 'ics' ? 'Экспорт…' : 'Календарь / ICS'}
                </button>
              </div>
              {exportError && <p className={styles.exportError} role="alert">{exportError}</p>}
              {status === 'error' && <RecordsStatus message={loadError} onRetry={reload} />}
              {RECORD_SECTIONS.map((section) => (
                <RecordsSection
                  key={section.kind}
                  label={section.label}
                  icon={section.icon}
                  items={records[section.kind]}
                  expanded={openKinds[section.kind]}
                  onToggle={() => toggleKind(section.kind)}
                  emptyText={status === 'loading' ? 'Загрузка…' : 'Нет записей'}
                />
              ))}
            </Collapsible>
          </>
        )}
      </nav>

      <footer className={styles.footer}>
        <PushToggle collapsed={collapsed} />
        <UserMenu name="Пользователь" collapsed={collapsed} />
      </footer>
    </aside>
  );
}