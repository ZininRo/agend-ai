// Чистые функции для озвучивания (без React): их легко проверять отдельно от интерфейса.

/** Дольше этого вслух не читаем: по ТЗ озвучивается краткий ответ, а весь список остаётся в тексте чата. */
export const MAX_SPOKEN_CHARS = 400;

/** Браузер умеет синтез речи (speechSynthesis). Нет, например, в части встроенных браузеров. */
export function isSpeechSupported(): boolean {
  return (
    typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'
  );
}

/** Убирает то, что плохо звучит вслух: нумерацию «1)», тире, кавычки, переносы строк. */
export function toSpeakable(text: string): string {
  return text
    .replace(/(^|\s)\d{1,2}\)\s*/g, '$1')
    .replace(/\s*[—–]\s*/g, ', ')
    .replace(/[«»"“”]/g, '')
    .replace(/\s*\n+\s*/g, '. ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Делит текст на предложения: Chrome иногда обрывает длинную фразу, а короткими кусками читает надёжно.
 * Набирает предложения, пока не набралось MAX_SPOKEN_CHARS; хвост пропускает (он виден в чате).
 * Первое предложение читается всегда, даже если оно длиннее лимита.
 */
export function toSpokenParts(text: string): string[] {
  const sentences = toSpeakable(text).match(/[^.!?…]+[.!?…]*/g)?.map((s) => s.trim()).filter(Boolean) ?? [];
  const parts: string[] = [];
  let total = 0;
  for (const sentence of sentences) {
    if (parts.length > 0 && total + sentence.length > MAX_SPOKEN_CHARS) break;
    parts.push(sentence);
    total += sentence.length;
  }
  return parts;
}

/** Русский голос: сначала локальный (работает без сети), затем любой русский. */
export function pickRussianVoice(voices: SpeechSynthesisVoice[]): SpeechSynthesisVoice | undefined {
  const russian = voices.filter((voice) => voice.lang.toLowerCase().replace('_', '-').startsWith('ru'));
  return russian.find((voice) => voice.localService) ?? russian[0];
}

/**
 * Голоса в системе есть, а русского среди них нет. Пустой список не считаем «нет голоса»:
 * многие браузеры отдают его до первого вызова speak(), хотя говорить умеют.
 */
export function hasNoRussianVoice(voices: SpeechSynthesisVoice[]): boolean {
  return voices.length > 0 && !pickRussianVoice(voices);
}
