import type { ReactNode } from 'react';
import { Collapsible } from '../../ui/Collapsible';
import {
  formatDayMonth,
  formatTimeRange,
  getDayOfMonth,
} from '../../../features/records/format';
import type { RecordItem } from '../../../features/records/types';
import { NavItem } from './NavItem';
import styles from './RecordsSection.module.css';

type Props = {
  label: string;
  icon: ReactNode;
  items: RecordItem[];
  expanded: boolean;
  onToggle: () => void;
};

/** Выпадающий раздел («Задачи», «Встречи», «События»): заголовок + список записей. */
export function RecordsSection({ label, icon, items, expanded, onToggle }: Props) {
  return (
    <section className={`${styles.section} ${expanded ? styles.open : ''}`}>
      <NavItem icon={icon} label={label} expanded={expanded} onClick={onToggle} />
      <Collapsible open={expanded}>
        <ul className={styles.list}>
          {items.length === 0 && <li className={`${styles.empty} ${styles.plain}`}>Нет записей</li>}
          {items.map((item) => (
            <RecordRow key={item.id} item={item} />
          ))}
        </ul>
      </Collapsible>
    </section>
  );
}

function RecordRow({ item }: { item: RecordItem }) {
  // У задачи нет времени и места: только дата под названием, без плашки с числом.
  const isTask = item.kind === 'task';

  return (
    <li className={`${styles.row} ${isTask ? styles.plain : ''}`}>
      {!isTask && <span className={styles.day}>{getDayOfMonth(item.date)}</span>}
      <div>
        <p className={styles.title}>{item.title}</p>
        {isTask ? (
          <p className={styles.meta}>{formatDayMonth(item.date)}</p>
        ) : (
          <>
            <p className={styles.meta}>{formatTimeRange(item)}</p>
            {item.place && <p className={styles.meta}>{item.place}</p>}
          </>
        )}
      </div>
    </li>
  );
}
