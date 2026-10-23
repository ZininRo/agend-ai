package com.example.agend_ai.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Сессия вкладки браузера. id приходит от клиента (UUID v4). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sessions")
public class ChatSession extends BaseEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_item_id")
    private Item lastItem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_clarification", columnDefinition = "JSONB")
    private JsonNode pendingClarification;
}
