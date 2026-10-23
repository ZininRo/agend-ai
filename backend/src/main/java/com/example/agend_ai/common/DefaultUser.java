package com.example.agend_ai.common;

import java.util.UUID;

/** MVP: единственный пользователь (создаётся миграцией V1). */
public final class DefaultUser {
    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private DefaultUser() {
    }
}
