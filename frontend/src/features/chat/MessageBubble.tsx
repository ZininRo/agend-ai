import type { Message } from './types';
import styles from './MessageBubble.module.css';

type Props = Pick<Message, 'role' | 'text'> & {
  /** Ответ ещё готовится: пузырь мигает */
  pending?: boolean;
};

export function MessageBubble({ role, text, pending }: Props) {
  return <div className={`${styles.bubble} ${styles[role]} ${pending ? styles.pending : ''}`}>{text}</div>;
}
