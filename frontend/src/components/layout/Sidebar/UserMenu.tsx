import { useState } from 'react';
import UserIcon from '../../ui/icons/user.svg?react';
import { useAuth } from '../../../features/auth/AuthContext';
import styles from './UserMenu.module.css';

type Props = {
  /** Режим узкого меню: только аватар. */
  collapsed?: boolean;
};

export function UserMenu({ collapsed }: Props) {
  const auth = useAuth();
  const [open, setOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const name = auth.user?.display_name || auth.user?.email || 'Пользователь';

  const logout = async () => {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await auth.logout();
    } finally {
      setLoggingOut(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      <button
        type="button"
        className={`${styles.user} ${collapsed ? styles.compact : ''}`}
        aria-label={collapsed ? name : undefined}
        aria-expanded={open}
        title={collapsed ? name : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={styles.avatar}><UserIcon /></span>
        {!collapsed && <span className={styles.name}>{name}</span>}
      </button>
      {open && (
        <div className={`${styles.menu} ${collapsed ? styles.menuCollapsed : ''}`}>
          {auth.user?.email && <div className={styles.email}>{auth.user.email}</div>}
          <button type="button" className={styles.logoutButton} onClick={() => void logout()} disabled={loggingOut}>
            {loggingOut ? 'Выход…' : 'Выйти'}
          </button>
        </div>
      )}
    </div>
  );
}
