import { useEffect, useRef } from 'react';
import { Composer } from './Composer';
import { EmptyState } from './EmptyState';
import { MessageList } from './MessageList';
import { SpeechToggle } from './SpeechToggle';
import { Suggestions } from './Suggestions';
import { SUGGESTIONS } from './constants';
import { useChat } from './useChat';
import styles from './ChatPage.module.css';

export function ChatPage() {
  // Вся логика чата и голоса — в useChat(); здесь только раскладка и передача данных.
  const {
    messages,
    draft,
    setDraft,
    isThinking,
    voiceStatus,
    elapsedSec,
    quickReplies,
    speech,
    inputRef,
    toggleRecording,
    send,
  } = useChat();
  const isEmpty = messages.length === 0;
  const scrollRef = useRef<HTMLDivElement>(null);

  // Новое сообщение или «Думаю…» — прокручиваем ленту к концу
  useEffect(() => {
    const el = scrollRef.current;
    el?.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [messages.length, isThinking]);

  return (
    <div className={styles.page}>
      {/* Правый верхний угол страницы (по макету) */}
      {speech.supported && (
        <SpeechToggle
          className={styles.speechToggle}
          enabled={speech.enabled}
          speaking={speech.speaking}
          voiceMissing={speech.voiceMissing}
          onChange={speech.setEnabled}
        />
      )}
      {/* Прокручивается только эта область, подсказки и поле ввода остаются внизу */}
      <div ref={scrollRef} className={styles.scroll}>
        <div className={styles.column}>
          {isEmpty ? (
            <EmptyState voiceStatus={voiceStatus} onMicClick={toggleRecording} />
          ) : (
            <MessageList messages={messages} isThinking={isThinking} />
          )}
        </div>
      </div>

      <footer className={styles.footer}>
        <div className={`${styles.column} ${styles.footerContent}`}>
          {/* Пока ассистент ждёт ответа на уточняющий вопрос, вместо примеров — его варианты ответа */}
          <Suggestions
            items={quickReplies.length > 0 ? quickReplies : SUGGESTIONS}
            label={quickReplies.length > 0 ? 'Быстрый ответ:' : undefined}
            onSelect={(text) => void send(text)}
          />
          <Composer
            value={draft}
            onChange={setDraft}
            onSubmit={() => void send()}
            onMicClick={toggleRecording}
            voiceStatus={voiceStatus}
            elapsedSec={elapsedSec}
            showMic={!isEmpty}
            inputRef={inputRef}
          />
        </div>
      </footer>
    </div>
  );
}
