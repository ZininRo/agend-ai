import type { Message } from './types';

// Тестовый диалог из макета. Удаляется, когда сообщения начнут приходить от API.
export const MOCK_MESSAGES: Message[] = [
  { id: '1', role: 'user', text: 'Запланируй встречу с командой разработчиков в 10 на вторник' },
  { id: '2', role: 'assistant', text: 'Встреча запланирована' },
  { id: '3', role: 'user', text: 'Когда у меня встреча с командой?' },
  { id: '4', role: 'assistant', text: 'Встреча состоится 21 октября в 10 часов' },
];
