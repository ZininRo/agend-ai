import styles from './Suggestions.module.css';

type Props = {
  items: string[];
  onSelect?: (text: string) => void;
};

export function Suggestions({ items, onSelect }: Props) {
  return (
    <section aria-label="Примеры запросов">
      <p className={styles.label}>Попробуйте, например:</p>
      <ul className={styles.list}>
        {items.map((item) => (
          <li key={item}>
            <button type="button" className={styles.chip} onClick={() => onSelect?.(item)}>
              {item}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
