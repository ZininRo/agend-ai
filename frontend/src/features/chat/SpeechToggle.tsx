import SpeakerIcon from '../../components/ui/icons/sound.svg?react';
import SpeakerOffIcon from '../../components/ui/icons/no-sound.svg?react';
import styles from './SpeechToggle.module.css';

type Props = {
  enabled: boolean;
  /** Ассистент говорит прямо сейчас */
  speaking: boolean;
  /** В системе нет русского голоса: озвучивать нечем */
  voiceMissing: boolean;
  onChange: (enabled: boolean) => void;
  className?: string;
};

/** Переключатель «Озвучивать ответы» (ТЗ, разд. 13). Клик во время речи выключает озвучивание и сразу её останавливает. */
export function SpeechToggle({ enabled, speaking, voiceMissing, onChange, className }: Props) {
  const on = enabled && !voiceMissing;
  const title = voiceMissing
    ? 'На этом устройстве нет русского голоса: ответы будут только текстом'
    : on
      ? 'Выключить озвучивание ответов'
      : 'Включить озвучивание ответов';

  return (
    <button
      type="button"
      className={`${styles.toggle} ${className ?? ''}`}
      aria-pressed={on}
      disabled={voiceMissing}
      title={title}
      data-speaking={speaking}
      onClick={() => onChange(!enabled)}
    >
      {on ? <SpeakerIcon className={styles.icon} /> : <SpeakerOffIcon className={styles.icon} />}
      <span className={styles.text}>{voiceMissing ? 'Нет русского голоса' : 'Озвучивать ответы'}</span>
    </button>
  );
}
