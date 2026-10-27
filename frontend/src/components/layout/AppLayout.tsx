import { useEffect, useState, type ReactNode } from 'react';
import { Sidebar } from './Sidebar/Sidebar';
import styles from './AppLayout.module.css';

/** Окно уже этой ширины (меньше ноутбука): меню само сворачивается в узкую колонку иконок. */
const AUTO_COLLAPSE_QUERY = '(max-width: 1100px)';

/** Каркас приложения: сайдбар слева, текущий экран справа. */
export function AppLayout({ children }: { children: ReactNode }) {
  // Состояние живёт здесь: когда появятся другие части интерфейса, им тоже может понадобиться знать о нём.
  const [collapsed, setCollapsed] = useState(() => window.matchMedia(AUTO_COLLAPSE_QUERY).matches);

  // Окно пересекло границу: сворачиваем или разворачиваем меню. Ручное переключение кнопкой остаётся в силе до следующего пересечения.
  useEffect(() => {
    const query = window.matchMedia(AUTO_COLLAPSE_QUERY);
    const onChange = (event: MediaQueryListEvent) => setCollapsed(event.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  return (
    <div className={styles.layout}>
      <Sidebar collapsed={collapsed} onToggleCollapsed={() => setCollapsed((value) => !value)} />
      {/* Телефон: развёрнутое меню лежит поверх чата, клик по затемнению его закрывает (на других экранах не виден) */}
      {!collapsed && <div className={styles.backdrop} aria-hidden="true" onClick={() => setCollapsed(true)} />}
      <main className={styles.main}>{children}</main>
    </div>
  );
}
