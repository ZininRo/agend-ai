import { MicButton } from './MicButton';
import type { VoiceStatus } from './types';
import styles from './EmptyState.module.css';

type Props = {
  voiceStatus: VoiceStatus;
  onMicClick?: () => void;
};

// Тексты по состоянию записи: пользователь смотрит на большую кнопку, поэтому подсказываем рядом с ней
const TEXTS: Record<VoiceStatus, { title: string; subtitle: string }> = {
  idle: {
    title: 'Нажмите и говорите',
    subtitle: 'Ассистент распознает речь и поможет с задачами и встречами',
  },
  requesting: {
    title: 'Разрешите доступ к микрофону',
    subtitle: 'Браузер запросит разрешение на использование микрофона',
  },
  recording: {
    title: 'Слушаю…',
    subtitle: 'Скажите, что нужно сделать, и нажмите на микрофон ещё раз',
  },
  transcribing: {
    title: 'Распознаю речь…',
    subtitle: 'Текст появится в поле ввода, перед отправкой его можно исправить',
  },
};

/** Экран до первого сообщения: приглашение и большая кнопка записи. */
export function EmptyState({ voiceStatus, onMicClick }: Props) {
  const { title, subtitle } = TEXTS[voiceStatus];

  return (
    <div className={styles.empty}>
      <h1 className={styles.title}>{title}</h1>
      <p className={styles.subtitle}>{subtitle}</p>
      <div className={styles.mic}>
        <MicButton size="lg" status={voiceStatus} onClick={onMicClick} />
      </div>
    </div>
  );
}
