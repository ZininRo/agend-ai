import { MessageBubble } from './MessageBubble';
import type { Message } from './types';
import styles from './MessageList.module.css';

type Props = {
  messages: Message[];
  /** Ассистент готовит ответ: в конце списка показываем «Думаю…» */
  isThinking?: boolean;
};

export function MessageList({ messages, isThinking }: Props) {
  return (
    <div className={styles.list} role="log" aria-live="polite" aria-label="Диалог с ассистентом">
      {messages.map((message) => (
        <MessageBubble key={message.id} role={message.role} text={message.text} />
      ))}
      {isThinking && <MessageBubble role="assistant" text="Думаю…" pending />}
    </div>
  );
}
