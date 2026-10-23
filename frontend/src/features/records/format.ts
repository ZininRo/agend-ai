import type { Month, RecordItem } from "./types";


const monthName = new Intl.DateTimeFormat('ru-RU', { month: 'long' });
const dayAndMonth = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' });

function parseDate(iso: string): Date {
  const [year, month, day] = iso.split('-').map(Number);
  return new Date(year, month - 1, day);
}

/** «Октябрь»; для другого года добавляется год: «Январь 2027». */
export function formatMonthLabel({ year, month }: Month, now = new Date()): string {
  const name = monthName.format(new Date(year, month, 1));
  const label = name.charAt(0).toUpperCase() + name.slice(1);
  return year === now.getFullYear() ? label : `${label} ${year}`;
}

/** «26 октября» */
export function formatDayMonth(iso: string): string {
  return dayAndMonth.format(parseDate(iso));
}

/** Число месяца для плашки: 21 */
export function getDayOfMonth(iso: string): number {
  return Number(iso.slice(8, 10));
}

/** «10:00 - 11:00» */
export function formatTimeRange({ startTime, endTime }: RecordItem): string {
  if (startTime && endTime) return `${startTime} - ${endTime}`;
  return startTime ?? '';
}
