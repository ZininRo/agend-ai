import ErrorIcon from '../../components/ui/icons/error.svg?react';
import type { Message } from './types';
import styles from './MessageBubble.module.css';

type Props = Pick<Message, 'role' | 'text' | 'actions' | 'isError'> & {
  /** Ответ ещё готовится: пузырь мигает */
  pending?: boolean;
};

export function MessageBubble({ role, text, pending, actions, isError }: Props) {
  const bubble = (
    <div className={`${styles.bubble} ${styles[role]} ${pending ? styles.pending : ''} ${isError ? styles.error : ''}`}>
      {isError && <ErrorIcon className={styles.errorIcon} aria-hidden="true" />}
      <span>{text}</span>
    </div>
  );
  if (!actions || actions.length === 0) return bubble;

  // Ошибка с кнопками: пузырь и кнопки одним блоком слева, как любой ответ ассистента
  return (
    <div className={styles.group}>
      {bubble}
    </div>
  );
}
