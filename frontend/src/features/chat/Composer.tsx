import type { FormEvent, Ref } from 'react';
import { GradientButton } from '../../components/ui/GradientButton';
import SendIcon from '../../components/ui/icons/next.svg?react'
import { MAX_RECORDING_SEC } from './constants';
import { MicButton } from './MicButton';
import type { VoiceStatus } from './types';
import styles from './Composer.module.css';

type Props = {
  value: string;
  onChange: (value: string) => void;
  onSubmit?: () => void;
  onMicClick?: () => void;
  voiceStatus: VoiceStatus;
  /** Сколько секунд идёт запись (для подписи в поле) */
  elapsedSec?: number;
  /** Малый микрофон показываем только когда на экране нет большого (т.е. в чате с сообщениями). */
  showMic?: boolean;
  /** Нужен, чтобы после распознавания вернуть фокус в поле ввода */
  inputRef?: Ref<HTMLInputElement>;
};

const formatTime = (sec: number) => `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;

const STATUS_LABEL: Record<Exclude<VoiceStatus, 'idle'>, string> = {
  requesting: 'Разрешите доступ к микрофону…',
  recording: 'Слушаю… нажмите на микрофон, чтобы закончить',
  transcribing: 'Распознаю речь…',
};

export function Composer({
  value,
  onChange,
  onSubmit,
  onMicClick,
  voiceStatus,
  elapsedSec = 0,
  showMic = true,
  inputRef,
}: Props) {
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit?.();
  };

  // Во время записи подпись в поле показывает таймер: макет не меняется, смещений нет
  const placeholder =
    voiceStatus === 'idle'
      ? 'Написать сообщение...'
      : voiceStatus === 'recording'
        ? `Слушаю… ${formatTime(elapsedSec)} / ${formatTime(MAX_RECORDING_SEC)} — нажмите на микрофон, чтобы закончить`
        : STATUS_LABEL[voiceStatus];

  return (
    <form className={styles.composer} onSubmit={handleSubmit}>
      <input
        ref={inputRef}
        className={styles.input}
        value={value}
        placeholder={placeholder}
        aria-label="Сообщение"
        onChange={(event) => onChange(event.target.value)}
      />
      {/* Для скринридеров: смена состояния озвучивается один раз, без секундомера */}
      <span className={styles.srOnly} role="status">
        {voiceStatus === 'idle' ? '' : STATUS_LABEL[voiceStatus]}
      </span>
      {showMic && <MicButton status={voiceStatus} onClick={onMicClick} />}
      <GradientButton type="submit" aria-label="Отправить">
        <SendIcon className={styles.sendIcon} />
      </GradientButton>
    </form>
  );
}
