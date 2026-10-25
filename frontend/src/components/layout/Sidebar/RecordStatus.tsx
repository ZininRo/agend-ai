import styles from './RecordStatus.module.css';

type Props = {
  message: string;
  onRetry: () => void;
};

/** Ошибка загрузки записей в боковом меню: что случилось и как исправить (кнопка «Повторить»). */
export function RecordsStatus({ message, onRetry }: Props) {
  return (
    <div role="alert" className={styles.status}>
      <p className={styles.text}>{message}</p>
      <button type="button" className={styles.retry} onClick={onRetry}>
        Повторить
      </button>
    </div>
  );
}
