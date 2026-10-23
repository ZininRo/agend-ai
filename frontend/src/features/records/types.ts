export type RecordKind = 'task' | 'meeting' | 'event';

export type RecordItem = {
  id: string;
  kind: RecordKind;
  title: string;
  /** Дата в формате YYYY-MM-DD */
  date: string;
  /** «HH:MM». Только для встреч и событий. */
  startTime?: string;
  endTime?: string;
  place?: string;
};

/** month: 0–11, как в Date.getMonth() */
export type Month = { year: number; month: number };

export type RecordsByKind = Record<RecordKind, RecordItem[]>;
