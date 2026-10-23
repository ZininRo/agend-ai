package com.example.agend_ai.api.error;

/** Каталог технических ошибок (разд. 13.3 ТЗ). */
public enum ErrorCode {

    REQUEST_INVALID(400, "Не удалось обработать запрос. Обновите страницу и попробуйте снова.", false, Fallback.RELOAD),
    TEXT_EMPTY(400, "Введите сообщение.", false, Fallback.REPHRASE),
    TEXT_TOO_LONG(400, "Сообщение слишком длинное (максимум 1000 символов). Сократите его и отправьте снова.", false, Fallback.REPHRASE),
    AUDIO_MISSING(400, "Запись не получена. Попробуйте записать сообщение ещё раз.", true, Fallback.RETRY),
    AUDIO_TOO_LARGE(413, "Запись слишком большая. Скажите фразу короче.", true, Fallback.RETRY),
    AUDIO_TOO_LONG(422, "Запись длиннее 30 секунд. Скажите фразу короче.", true, Fallback.RETRY),
    AUDIO_FORMAT_UNSUPPORTED(415, "Не удалось прочитать запись с этого устройства. Введите сообщение текстом.", false, Fallback.TEXT_INPUT),
    STT_TIMEOUT(504, "Распознавание речи заняло слишком много времени. Попробуйте ещё раз или введите сообщение текстом.", true, Fallback.RETRY),
    STT_UNAVAILABLE(503, "Распознавание речи временно недоступно. Введите сообщение текстом.", false, Fallback.TEXT_INPUT),
    LLM_TIMEOUT(504, "Ассистент отвечает слишком долго. Попробуйте отправить сообщение ещё раз.", true, Fallback.RETRY),
    LLM_UNAVAILABLE(503, "Ассистент временно недоступен. Попробуйте через минуту.", true, Fallback.WAIT),
    STORAGE_UNAVAILABLE(503, "Не удалось сохранить данные. Ничего не изменилось, попробуйте ещё раз через минуту.", true, Fallback.WAIT),
    RATE_LIMITED(429, "Слишком много запросов. Подождите немного и повторите.", true, Fallback.WAIT),
    INTERNAL_ERROR(500, "Что-то пошло не так на нашей стороне. Попробуйте ещё раз.", true, Fallback.RETRY);

    private final int httpStatus;
    private final String message;
    private final boolean retryable;
    private final Fallback fallback;

    ErrorCode(int httpStatus, String message, boolean retryable, Fallback fallback) {
        this.httpStatus = httpStatus;
        this.message = message;
        this.retryable = retryable;
        this.fallback = fallback;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }

    public Fallback fallback() {
        return fallback;
    }
}
