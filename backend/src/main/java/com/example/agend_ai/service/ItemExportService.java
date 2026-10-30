package com.example.agend_ai.service;

import com.example.agend_ai.api.dto.ItemDto;
import com.example.agend_ai.api.dto.ItemsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemExportService {

    private static final DateTimeFormatter ICS_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX");
    private final ItemQueryService itemQueryService;

    public ExportFile export(LocalDate from, LocalDate to, List<String> types, String status,
                             String timezone, String format) {
        ItemsResponse response = itemQueryService.list(from, to, types, status, timezone);
        String normalized = format == null ? "csv" : format.trim().toLowerCase();
        return switch (normalized) {
            case "csv" -> new ExportFile(csv(response.items()), "text/csv; charset=UTF-8", "agendai-items.csv");
            case "ics" -> new ExportFile(ics(response.items()), "text/calendar; charset=UTF-8", "agendai-calendar.ics");
            default -> throw new com.example.agend_ai.api.error.AppException(com.example.agend_ai.api.error.ErrorCode.REQUEST_INVALID);
        };
    }

    private byte[] csv(List<ItemDto> items) {
        StringBuilder out = new StringBuilder("\uFEFF");
        out.append("Тип;Название;Описание;Статус;Дата/время начала;Дата/время окончания;Срок;Место;Создано;Изменено\r\n");
        for (ItemDto i : items) {
            row(out, i.type(), i.title(), i.description(), i.status(), i.startAt(), i.endAt(),
                    i.dueAt(), i.place(), i.createdAt(), i.updatedAt());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void row(StringBuilder out, String... cells) {
        for (int n = 0; n < cells.length; n++) {
            if (n > 0) out.append(';');
            out.append(csvCell(cells[n]));
        }
        out.append("\r\n");
    }

    private String csvCell(String value) {
        if (value == null) return "";
        String safe = value;
        String leadingTrimmed = safe.stripLeading();
        if (!leadingTrimmed.isEmpty() && "=+-@".indexOf(leadingTrimmed.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private byte[] ics(List<ItemDto> items) {
        StringBuilder out = new StringBuilder();
        out.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//agendAI//Export//RU\r\nCALSCALE:GREGORIAN\r\n");
        for (ItemDto i : items) {
            if ("task".equals(i.type())) appendTodo(out, i);
            else appendEvent(out, i);
        }
        out.append("END:VCALENDAR\r\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendEvent(StringBuilder out, ItemDto i) {
        if (i.startAt() == null) return;
        out.append("BEGIN:VEVENT\r\nUID:").append(i.id()).append("@agendai\r\n");
        out.append("DTSTAMP:").append(icsTime(i.updatedAt())).append("\r\n");
        out.append("DTSTART:").append(icsTime(i.startAt())).append("\r\n");
        if (i.endAt() != null) out.append("DTEND:").append(icsTime(i.endAt())).append("\r\n");
        out.append("SUMMARY:").append(icsText(i.title())).append("\r\n");
        if (i.description() != null) out.append("DESCRIPTION:").append(icsText(i.description())).append("\r\n");
        if (i.place() != null) out.append("LOCATION:").append(icsText(i.place())).append("\r\n");
        if ("cancelled".equals(i.status())) out.append("STATUS:CANCELLED\r\n");
        out.append("END:VEVENT\r\n");
    }

    private void appendTodo(StringBuilder out, ItemDto i) {
        out.append("BEGIN:VTODO\r\nUID:").append(i.id()).append("@agendai\r\n");
        out.append("DTSTAMP:").append(icsTime(i.updatedAt())).append("\r\n");
        if (i.dueAt() != null) out.append("DUE:").append(icsTime(i.dueAt())).append("\r\n");
        out.append("SUMMARY:").append(icsText(i.title())).append("\r\n");
        if (i.description() != null) out.append("DESCRIPTION:").append(icsText(i.description())).append("\r\n");
        if ("done".equals(i.status())) out.append("STATUS:COMPLETED\r\n");
        else if ("cancelled".equals(i.status())) out.append("STATUS:CANCELLED\r\n");
        else out.append("STATUS:NEEDS-ACTION\r\n");
        out.append("END:VTODO\r\n");
    }

    private String icsTime(String iso) {
        return OffsetDateTime.parse(iso).toInstant().atOffset(java.time.ZoneOffset.UTC).format(ICS_DATE_TIME);
    }

    private String icsText(String value) {
        return value.replace("\\", "\\\\").replace("\r\n", "\\n").replace("\n", "\\n")
                .replace(",", "\\,").replace(";", "\\;");
    }

    public record ExportFile(byte[] bytes, String contentType, String filename) {}
}
