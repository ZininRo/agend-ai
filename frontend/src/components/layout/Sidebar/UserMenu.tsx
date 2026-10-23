// import { ChevronDownIcon, UserIcon } from '../../ui/Icons';
import UserIcon from '../../ui/icons/user.svg?react'
import styles from './UserMenu.module.css';

type Props = {
  name: string;
  /** Режим узкого меню: только аватар. */
  collapsed?: boolean;
};

export function UserMenu({ name, collapsed }: Props) {
  return (
    <button
      type="button"
      className={`${styles.user} ${collapsed ? styles.compact : ''}`}
      aria-label={collapsed ? name : undefined}
      title={collapsed ? name : undefined}
    >
      <span className={styles.avatar}>
        <UserIcon />
      </span>
      {!collapsed && <span className={styles.name}>{name}</span>}
      {/* <ChevronDownIcon className={styles.chevron} /> */}
    </button>
  );
}
