import type { ReactNode } from 'react';
import styles from './Collapsible.module.css';

type Props = {
  open: boolean;
  children: ReactNode;
};

/**
 * Плавно раскрывает/сворачивает содержимое по высоте.
 * Свёрнутое содержимое остаётся в DOM, но `inert` убирает его из
 * Tab-навигации и из дерева доступности.
 */
export function Collapsible({ open, children }: Props) {
  return (
    <div className={`${styles.wrap} ${open ? styles.open : ''}`} inert={!open}>
      <div className={styles.inner}>{children}</div>
    </div>
  );
}
