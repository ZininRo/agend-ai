import { useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../../api/ApiError';
import type { TtsVoiceDto } from '../../api/types';
import DownIcon from '../../components/ui/icons/down.svg?react';
import SpeakerIcon from '../../components/ui/icons/sound.svg?react';
import SpeakerOffIcon from '../../components/ui/icons/no-sound.svg?react';
import { useAuth } from '../auth/AuthContext';
import { fetchVoices, previewVoice } from '../tts/api';
import styles from './SpeechToggle.module.css';

type Props = {
  enabled: boolean;
  /** Ассистент говорит прямо сейчас */
  speaking: boolean;
  /** Ни серверное аудио, ни русский browser fallback на устройстве недоступны. */
  voiceMissing: boolean;
  onChange: (enabled: boolean) => void;
  className?: string;
};

function genderLabel(gender: TtsVoiceDto['gender']): string {
  return gender === 'female' ? 'Женский' : 'Мужской';
}

/**
 * Управление озвучиванием и выбором серверного голоса.
 * Основная кнопка включает/выключает речь, соседняя открывает компактное меню выбора голоса.
 */
export function SpeechToggle({ enabled, speaking, voiceMissing, onChange, className }: Props) {
  const auth = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const [voices, setVoices] = useState<TtsVoiceDto[]>([]);
  const [voicesLoading, setVoicesLoading] = useState(false);
  const [savingVoice, setSavingVoice] = useState<string | null>(null);
  const [previewingVoice, setPreviewingVoice] = useState<string | null>(null);
  const [menuMessage, setMenuMessage] = useState('');
  const wrapperRef = useRef<HTMLDivElement>(null);
  const previewAudio = useRef<HTMLAudioElement | null>(null);
  const previewUrl = useRef<string | null>(null);

  const on = enabled && !voiceMissing;
  const currentVoiceId = auth.user?.tts_voice ?? 'jane';
  const selectedVoice = useMemo(
    () => voices.find((voice) => voice.id === currentVoiceId),
    [currentVoiceId, voices],
  );
  const currentVoiceName = selectedVoice?.name ?? currentVoiceId;

  const title = voiceMissing
    ? 'Озвучивание на этом устройстве недоступно: ответы будут только текстом'
    : on
      ? 'Выключить озвучивание ответов'
      : 'Включить озвучивание ответов';

  const stopPreview = () => {
    previewAudio.current?.pause();
    previewAudio.current = null;
    if (previewUrl.current) {
      URL.revokeObjectURL(previewUrl.current);
      previewUrl.current = null;
    }
    setPreviewingVoice(null);
  };

  useEffect(
    () => () => {
      previewAudio.current?.pause();
      if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
    },
    [],
  );

  useEffect(() => {
    if (!menuOpen || voices.length > 0) return;
    let cancelled = false;
    setVoicesLoading(true);
    setMenuMessage('');
    void fetchVoices()
      .then((result) => {
        if (!cancelled) setVoices(result.voices);
      })
      .catch((error) => {
        if (!cancelled) {
          setMenuMessage(error instanceof ApiError ? error.message : 'Не удалось загрузить список голосов.');
        }
      })
      .finally(() => {
        if (!cancelled) setVoicesLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [menuOpen, voices.length]);

  useEffect(() => {
    if (!menuOpen) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!wrapperRef.current?.contains(event.target as Node)) {
        stopPreview();
        setMenuOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        stopPreview();
        setMenuOpen(false);
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [menuOpen]);

  const chooseVoice = async (voiceId: string) => {
    if (savingVoice || voiceId === currentVoiceId) return;
    setSavingVoice(voiceId);
    setMenuMessage('');
    try {
      await auth.setTtsVoice(voiceId);
    } catch (error) {
      setMenuMessage(error instanceof ApiError ? error.message : 'Не удалось сохранить голос.');
    } finally {
      setSavingVoice(null);
    }
  };

  const playPreview = async (voiceId: string) => {
    if (previewingVoice || voicesLoading) return;
    stopPreview();
    setPreviewingVoice(voiceId);
    setMenuMessage('');
    try {
      const blob = await previewVoice(voiceId);
      const url = URL.createObjectURL(blob);
      const audio = new Audio(url);
      previewUrl.current = url;
      previewAudio.current = audio;
      audio.addEventListener(
        'ended',
        () => {
          if (previewAudio.current === audio) stopPreview();
        },
        { once: true },
      );
      await audio.play();
    } catch (error) {
      stopPreview();
      setMenuMessage(error instanceof ApiError ? error.message : 'Не удалось воспроизвести пример.');
    }
  };

  const toggleMenu = () => {
    if (menuOpen) stopPreview();
    setMenuMessage('');
    setMenuOpen((value) => !value);
  };

  return (
    <div ref={wrapperRef} className={`${styles.wrapper} ${className ?? ''}`} data-open={menuOpen}>
      <div className={styles.control}>
        <button
          type="button"
          className={styles.toggle}
          aria-pressed={on}
          disabled={voiceMissing}
          title={title}
          data-speaking={speaking}
          onClick={() => onChange(!enabled)}
        >
          {on ? <SpeakerIcon className={styles.icon} /> : <SpeakerOffIcon className={styles.icon} />}
          <span className={styles.text}>{voiceMissing ? 'Озвучивание недоступно' : 'Озвучивать ответы'}</span>
        </button>

        <button
          type="button"
          className={styles.voiceTrigger}
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          aria-label={`Выбрать голос агента. Сейчас: ${currentVoiceName}`}
          title={`Голос агента: ${currentVoiceName}`}
          onClick={toggleMenu}
        >
          <span className={styles.voiceTriggerName}>{currentVoiceName}</span>
          <DownIcon className={styles.chevron} />
        </button>
      </div>

      {menuOpen && (
        <div className={styles.menu} role="menu" aria-label="Выбор голоса агента">
          <div className={styles.menuHeader}>
            <div>
              <div className={styles.menuTitle}>Голос агента</div>
              <div className={styles.menuSubtitle}>Выберите голос и прослушайте пример</div>
            </div>
          </div>

          <div className={styles.voiceList}>
            {voicesLoading && <div className={styles.loading}>Загружаю голоса…</div>}

            {!voicesLoading && voices.map((voice) => {
              const selected = voice.id === currentVoiceId;
              const busy = savingVoice === voice.id;
              const previewing = previewingVoice === voice.id;
              return (
                <div key={voice.id} className={styles.voiceRow} data-selected={selected}>
                  <button
                    type="button"
                    role="menuitemradio"
                    aria-checked={selected}
                    className={styles.voiceChoice}
                    disabled={Boolean(savingVoice)}
                    onClick={() => void chooseVoice(voice.id)}
                  >
                    <span className={styles.radio} aria-hidden="true"><span /></span>
                    <span className={styles.voiceMeta}>
                      <span className={styles.voiceName}>{voice.name}</span>
                      <span className={styles.voiceGender}>{genderLabel(voice.gender)}</span>
                    </span>
                    {busy && <span className={styles.saving}>Сохраняю…</span>}
                  </button>
                  <button
                    type="button"
                    className={styles.previewButton}
                    aria-label={`Прослушать голос ${voice.name}`}
                    title={`Прослушать ${voice.name}`}
                    disabled={Boolean(previewingVoice) || voicesLoading}
                    onClick={() => void playPreview(voice.id)}
                  >
                    {previewing ? <span className={styles.previewPulse} /> : <SpeakerIcon className={styles.previewIcon} />}
                  </button>
                </div>
              );
            })}

            {!voicesLoading && voices.length === 0 && !menuMessage && (
              <div className={styles.loading}>Нет доступных голосов.</div>
            )}
          </div>

          {menuMessage && <div className={styles.menuMessage} role="status">{menuMessage}</div>}
        </div>
      )}
    </div>
  );
}
