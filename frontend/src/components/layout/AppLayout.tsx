import { useState, type ReactNode } from 'react';
import { Sidebar } from './Sidebar/Sidebar';
import styles from './AppLayout.module.css';

/** Каркас приложения: сайдбар слева, текущий экран справа. */
export function AppLayout({ children }: { children: ReactNode }) {
  // Состояние живёт здесь: когда появятся другие части интерфейса, им тоже может понадобиться знать о нём.
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className={styles.layout}>
      <Sidebar collapsed={collapsed} onToggleCollapsed={() => setCollapsed((value) => !value)} />
      <main className={styles.main}>{children}</main>
    </div>
  );
}
