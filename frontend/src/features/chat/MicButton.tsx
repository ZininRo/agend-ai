import { GradientButton } from '../../components/ui/GradientButton';
import MicIcon from '../../components/ui/icons/mic_big.svg?react'
import StopIcon from '../../components/ui/icons/stop.svg?react'
import type { VoiceStatus } from './types';
import styles from './MicButton.module.css';

type Props = {
  /** sm — в поле ввода, lg — большая кнопка на пустом экране */
  size?: 'sm' | 'lg';
  status: VoiceStatus;
  onClick?: () => void;
};

/** Кнопка микрофона: «начать запись» → «остановить» (пульсирует) → недоступна, пока идёт распознавание. */
export function MicButton({ size = 'sm', status, onClick }: Props) {
  const isRecording = status === 'recording';
  // Пока ждём разрешение или распознаём речь, повторное нажатие бессмысленно
  const isBusy = status === 'requesting' || status === 'transcribing';
  const label = isRecording ? 'Остановить запись' : isBusy ? 'Подождите…' : 'Начать запись';
  const Icon = isRecording ? StopIcon : MicIcon;

  return (
    <GradientButton
      size={size}
      className={isRecording ? styles.recording : undefined}
      aria-label={label}
      aria-pressed={isRecording}
      disabled={isBusy}
      onClick={onClick}
    >
      <Icon className={size === 'lg' ? styles.iconLg : styles.iconSm} />
    </GradientButton>
  );
}
