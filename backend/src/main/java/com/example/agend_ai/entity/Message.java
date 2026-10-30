package com.example.agend_ai.entity;

import com.example.agend_ai.entity.enums.MessageInputType;
import com.example.agend_ai.entity.enums.MessageRole;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import com.example.agend_ai.crypto.EncryptedStringConverter;
import com.example.agend_ai.crypto.EncryptedJsonConverter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "messages")
public class Message extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", length = 20)
    private MessageInputType inputType;

    /** Допущенный интент ("create_item", "stt_not_recognized", ...). */
    @Column(name = "intent", length = 50)
    private String intent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    @Column(name = "client_message_id")
    private UUID clientMessageId;

    /** JSON, который вернул LLM (для отладки/аналитики). */
    @Convert(converter = EncryptedJsonConverter.class)
    @Column(name = "intent_payload", columnDefinition = "TEXT")
    private JsonNode intentPayload;

    /** Сохранённый ответ API (идемпотентность по client_message_id). */
    @Convert(converter = EncryptedJsonConverter.class)
    @Column(name = "response_json", columnDefinition = "TEXT")
    private JsonNode responseJson;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Europe/Moscow";
}
