

// Заглушка вместо ответа бэкенда (GET /api/items). Когда появится API,
// файл удаляется, а useRecords начинает ходить на сервер.
//
// Даты заданы относительно текущего месяца (offset 0 — этот месяц, -1 — прошлый),
// поэтому меню всегда показывает данные, в каком бы месяце его ни открыли.

import type { RecordItem } from "./types";

const now = new Date();

function at(monthOffset: number, day: number): string {
  const first = new Date(now.getFullYear(), now.getMonth() + monthOffset, 1);
  const daysInMonth = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  const date = new Date(first.getFullYear(), first.getMonth(), Math.min(day, daysInMonth));
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${mm}-${dd}`;
}

export const MOCK_RECORDS: RecordItem[] = [
  // Текущий месяц — данные из макета
  { id: 't1', kind: 'task', title: 'Подготовить отчет', date: at(0, 26) },
  { id: 't2', kind: 'task', title: 'Согласовать макет', date: at(0, 27) },
  { id: 't3', kind: 'task', title: 'Написать клиенту', date: at(0, 28) },
  { id: 't4', kind: 'task', title: 'Провести анализ ТЗ', date: at(0, 30) },

  { id: 'm1', kind: 'meeting', title: 'Встреча с командой', date: at(0, 21), startTime: '10:00', endTime: '11:00', place: 'Офис, переговорная' },
  { id: 'm2', kind: 'meeting', title: 'Встреча по проекту', date: at(0, 23), startTime: '10:00', endTime: '12:00', place: 'Офис, переговорная' },
  { id: 'm3', kind: 'meeting', title: 'Встреча с клиентом', date: at(0, 29), startTime: '14:00', endTime: '14:30', place: 'Кафе "Волконский"' },

  { id: 'e1', kind: 'event', title: 'Презентация проекта', date: at(0, 24), startTime: '12:00', endTime: '13:00', place: 'ул. Первопроходцев' },
  { id: 'e2', kind: 'event', title: 'Корпоративное мероприятие', date: at(0, 26), startTime: '17:00', endTime: '21:00', place: 'Ресторан "Novo"' },
  { id: 'e3', kind: 'event', title: 'День рождения Алексея', date: at(0, 30), startTime: '16:00', endTime: '22:00', place: 'ул. Мечтателей д.10' },

  // Прошлый месяц
  { id: 't5', kind: 'task', title: 'Сдать квартальный отчет', date: at(-1, 25) },
  { id: 'm4', kind: 'meeting', title: 'Ретроспектива спринта', date: at(-1, 12), startTime: '15:00', endTime: '16:00', place: 'Офис, переговорная' },
  { id: 'e4', kind: 'event', title: 'Конференция разработчиков', date: at(-1, 18), startTime: '10:00', endTime: '18:00', place: 'Экспоцентр' },

  // Следующий месяц (месяц +2 намеренно пустой — виден пустой список)
  { id: 't6', kind: 'task', title: 'Подготовить план на квартал', date: at(1, 5) },
  { id: 't7', kind: 'task', title: 'Обновить документацию', date: at(1, 12) },
  { id: 'm5', kind: 'meeting', title: 'Планирование спринта', date: at(1, 3), startTime: '11:00', endTime: '12:00', place: 'Онлайн' },
  { id: 'e5', kind: 'event', title: 'Командный ужин', date: at(1, 14), startTime: '19:00', endTime: '22:00', place: 'Ресторан "Novo"' },
];
