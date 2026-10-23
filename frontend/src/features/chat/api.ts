import type { Recording } from './types';

// ЗАГЛУШКИ вместо бэкенда. При подключении API меняется только тело функций,
// сигнатуры остаются прежними:
//   transcribeAudio → POST /api/voice   (multipart: audio + session_id)
//   sendMessage     → POST /api/message ({ session_id, text })

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

const MOCK_TRANSCRIPTS = [
  'Назначь встречу с командой завтра в 15:00',
  'Что у меня запланировано на сегодня?',
  'Добавь задачу подготовить презентацию на пятницу',
];
let transcriptIndex = 0;

/** Распознавание речи. Заглушка не слушает запись и по кругу возвращает готовые фразы. */
export async function transcribeAudio(_recording: Recording): Promise<string> {
  await wait(1200);
  return MOCK_TRANSCRIPTS[transcriptIndex++ % MOCK_TRANSCRIPTS.length];
}

/** Ответ ассистента на сообщение. */
export async function sendMessage(text: string): Promise<string> {
  await wait(900);
  return `Это тестовый ответ без бэкенда. Ваш запрос: «${text}»`;
}
