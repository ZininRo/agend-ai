import { useState } from 'react';
import type { Month } from './types';

/** Выбранный месяц и переключение назад/вперёд (через границу года тоже). */
export function useMonth() {
  const [month, setMonth] = useState<Month>(() => {
    const now = new Date();
    return { year: now.getFullYear(), month: now.getMonth() };
  });

  const shift = (delta: number) =>
    setMonth(({ year, month }) => {
      const next = new Date(year, month + delta, 1);
      return { year: next.getFullYear(), month: next.getMonth() };
    });

  return { month, prev: () => shift(-1), next: () => shift(1) };
}
