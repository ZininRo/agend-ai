import { useState } from 'react';
import type { Month } from './types';

/** Выбранный месяц (по умолчанию текущий) и его смена из календаря. */
export function useMonth() {
  const [month, setMonth] = useState<Month>(() => {
    const now = new Date();
    return { year: now.getFullYear(), month: now.getMonth() };
  });

  return { month, select: setMonth };
}
