import type { ReactNode } from 'react';
import ChevronUpIcon from '../../ui/icons/up.svg?react'
import ChevronDownIcon from '../../ui/icons/down.svg?react'
import styles from './NavItem.module.css';

type Props = {
  icon: ReactNode;
  label: string;
  /** Если передан — это раскрывающийся пункт: справа рисуется шеврон вверх/вниз. */
  expanded?: boolean;
  collapsed?: boolean;
  onClick?: () => void;
};

export function NavItem({ icon, label, expanded, collapsed, onClick }: Props) {
  const Chevron = expanded ? ChevronUpIcon : ChevronDownIcon;

  return (
    <button 
        type="button" 
        className={`${styles.item} ${collapsed ? styles.compact : ''}`} 
        aria-expanded={expanded} 
        aria-label={collapsed ? label : undefined}
        title={collapsed ? label : undefined}
        onClick={onClick}
    >
      <span className={styles.icon}>{icon}</span>
      {!collapsed && <span className={styles.label}>{label}</span>}
      {!collapsed && expanded !== undefined && <Chevron className={styles.chevron} />}
    </button>
  );
}
