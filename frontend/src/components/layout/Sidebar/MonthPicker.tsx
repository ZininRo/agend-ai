import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { Month } from '../../../features/records/types';
import BackIcon from '../../ui/icons/back.svg?react';
import CalendarIcon from '../../ui/icons/events.svg?react';
import NextIcon from '../../ui/icons/next.svg?react';
import styles from './MonthPicker.module.css';

const MONTHS = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];

/** Отступ календаря от кнопки и запас, чтобы он не уходил за нижний край окна */
const GAP = 24;
const PANEL_HEIGHT = 250;
const PANEL_WIDTH = 273;

type Props = {
  /** Подпись на кнопке: «Октябрь» или «Январь 2027» */
  label: string;
  value: Month;
  onSelect: (month: Month) => void;
};

/**
 * Выбор месяца для списка записей: кнопка с названием месяца и значком календаря.
 * По клику справа от меню открывается календарь: стрелками меняется год, кнопкой выбирается месяц.
 * Календарь выводится через портал в body: у списка меню своя прокрутка, и она обрезала бы его.
 */
export function MonthPicker({ label, value, onSelect }: Props) {
  const [open, setOpen] = useState(false);
  const [year, setYear] = useState(value.year);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();

  const now = new Date();

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    const rect = buttonRef.current?.getBoundingClientRect();
    if (rect) {
      const fitsRight = rect.right + GAP + PANEL_WIDTH <= window.innerWidth - 8;
      // Справа от меню, на уровне кнопки; на телефоне справа места нет, тогда календарь под кнопкой
      const left = fitsRight ? rect.right + GAP : Math.max(8, Math.min(rect.left, window.innerWidth - PANEL_WIDTH - 8));
      const top = fitsRight ? rect.top : rect.bottom + 8;
      setPos({ left, top: Math.max(8, Math.min(top, window.innerHeight - PANEL_HEIGHT - 8)) });
    }
    setYear(value.year); // каждый раз открываем на году выбранного месяца
    setOpen(true);
  };

  // Закрытие: клик мимо, Escape, прокрутка или смена размера окна (положение уже не совпадёт с кнопкой)
  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (panelRef.current?.contains(target) || buttonRef.current?.contains(target)) return;
      close();
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      close();
      buttonRef.current?.focus();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [open]);

  // Фокус на выбранный месяц, если открыт его год, иначе на первый
  useEffect(() => {
    if (!open) return;
    const target =
      panelRef.current?.querySelector<HTMLElement>('[aria-pressed="true"]') ??
      panelRef.current?.querySelector<HTMLElement>('[data-month]');
    target?.focus();
  }, [open]);

  const choose = (month: number) => {
    onSelect({ year, month });
    setOpen(false);
    buttonRef.current?.focus();
  };

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={styles.button}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={toggle}
      >
        <span className={styles.label}>{label}</span>
        <CalendarIcon className={styles.icon} aria-hidden="true" />
      </button>

      {open &&
        createPortal(
          <div
            ref={panelRef}
            className={styles.panel}
            role="dialog"
            aria-labelledby={titleId}
            style={{ top: pos.top, left: pos.left }}
          >
            <div className={styles.year}>
              <button type="button" className={styles.arrow} aria-label="Предыдущий год" onClick={() => setYear((y) => y - 1)}>
                <BackIcon />
              </button>
              <span id={titleId} aria-live="polite">
                {year}
              </span>
              <button type="button" className={styles.arrow} aria-label="Следующий год" onClick={() => setYear((y) => y + 1)}>
                <NextIcon />
              </button>
            </div>
            <div className={styles.months}>
              {MONTHS.map((name, index) => {
                const selected = value.year === year && value.month === index;
                const current = now.getFullYear() === year && now.getMonth() === index;
                return (
                  <button
                    key={name}
                    type="button"
                    data-month={index}
                    className={`${styles.month} ${selected ? styles.selected : ''} ${current ? styles.current : ''}`}
                    aria-pressed={selected}
                    aria-current={current ? 'date' : undefined}
                    onClick={() => choose(index)}
                  >
                    {name}
                  </button>
                );
              })}
            </div>
          </div>,
          document.body,
        )}
    </>
  );
}
