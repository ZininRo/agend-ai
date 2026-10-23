import styles from './MonthSwitcher.module.css';
import ChevronLeftIcon from '../../ui/icons/back.svg?react'
import ChevronRightIcon from '../../ui/icons/next.svg?react'

type Props = {
  label: string;
  onPrev?: () => void;
  onNext?: () => void;
};

export function MonthSwitcher({ label, onPrev, onNext }: Props) {
  return (
    <div className={styles.switcher}>
      <button type="button" className={styles.arrow} aria-label="Предыдущий месяц" onClick={onPrev}>
        <ChevronLeftIcon />
      </button>
      <span>{label}</span>
      <button type="button" className={styles.arrow} aria-label="Следующий месяц" onClick={onNext}>
        <ChevronRightIcon />
      </button>
    </div>
  );
}
