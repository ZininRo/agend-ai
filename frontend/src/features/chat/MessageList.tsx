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
      {messages.map((message, index) => (
        <MessageBubble
          key={message.id}
          role={message.role}
          text={message.text}
          isError={message.isError}
          // Кнопки нужны, пока ошибка последняя: после повтора или нового сообщения они исчезают
          actions={index === messages.length - 1 && !isThinking ? message.actions : undefined}
        />
      ))}
      {isThinking && <MessageBubble role="assistant" text="Думаю…" pending />}
    </div>
  );
}
