import styles from './Suggestions.module.css';

type Props = {
  items: string[];
  onSelect?: (text: string) => void;
  /** Подпись над кнопками: у быстрых ответов на вопрос ассистента она другая */
  label?: string;
  /** На телефоне показать только первые две подсказки (для примеров; быстрые ответы на вопрос ассистента скрывать нельзя) */
  limitOnPhone?: boolean;
};

export function Suggestions({ items, onSelect, label = 'Попробуйте, например:', limitOnPhone }: Props) {
  return (
    <section aria-label={label}>
      <p className={styles.label}>{label}</p>
      <ul className={`${styles.list} ${limitOnPhone ? styles.limited : ''}`}>
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
