import styles from './Suggestions.module.css';

type Props = {
  items: string[];
  onSelect?: (text: string) => void;
  /** Подпись над кнопками: у быстрых ответов на вопрос ассистента она другая */
  label?: string;
};

export function Suggestions({ items, onSelect, label = 'Попробуйте, например:' }: Props) {
  return (
    <section aria-label={label}>
      <p className={styles.label}>{label}</p>
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
