import { useState, type ReactNode } from 'react';
import LogoIcon from '../../ui/icons/logo.svg?react'
import DoubleChevronLeftIcon from '../../ui/icons/double_chevron_left.svg?react'
import ChatIcon from '../../ui/icons/chat.svg?react'
import PencilIcon from '../../ui/icons/pencil.svg?react'
import TasksIcon from '../../ui/icons/task.svg?react'
import MeetingsIcon from '../../ui/icons/meetings.svg?react'
import EventsIcon from '../../ui/icons/events.svg?react'

import { Collapsible } from '../../ui/Collapsible';
import { formatMonthLabel } from '../../../features/records/format';
import type { RecordKind } from '../../../features/records/types';
import { useMonth } from '../../../features/records/useMonth';
import { useRecords } from '../../../features/records/useRecords';
import { MonthSwitcher } from './MonthSwitcher';
import { NavItem } from './NavItem';
import { RecordsSection } from './RecordsSection';
import { UserMenu } from './UserMenu';
import styles from './Sidebar.module.css';

// Разделы «Мои записи»: порядок и подписи как в макете.
const RECORD_SECTIONS: { kind: RecordKind; label: string; icon: ReactNode }[] = [
  { kind: 'task', label: 'Задачи', icon: <TasksIcon /> },
  { kind: 'meeting', label: 'Встречи', icon: <MeetingsIcon /> },
  { kind: 'event', label: 'События', icon: <EventsIcon /> },
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
    event: false,
  });
  const { month, prev, next } = useMonth();
  const records = useRecords(month);

  const toggleKind = (kind: RecordKind) =>
    setOpenKinds((state) => ({ ...state, [kind]: !state[kind] }));

  // В свёрнутом меню «Мои записи» — только иконка: клик разворачивает меню
  // и сразу показывает вложенные блоки.
  const expandRecords = () => {
    onToggleCollapsed();
    setRecordsOpen(true);
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
        <NavItem icon={<ChatIcon />} label="Чат" collapsed={collapsed} />

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
              <MonthSwitcher label={formatMonthLabel(month)} onPrev={prev} onNext={next} />
              {RECORD_SECTIONS.map((section) => (
                <RecordsSection
                  key={section.kind}
                  label={section.label}
                  icon={section.icon}
                  items={records[section.kind]}
                  expanded={openKinds[section.kind]}
                  onToggle={() => toggleKind(section.kind)}
                />
              ))}
            </Collapsible>
          </>
        )}
      </nav>

      <footer className={styles.footer}>
        <UserMenu name="Ivan Ivanov" collapsed={collapsed} />
      </footer>
    </aside>
  );
}