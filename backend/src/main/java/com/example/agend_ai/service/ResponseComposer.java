package com.example.agend_ai.service;

import com.example.agend_ai.entity.Item;
import org.springframework.stereotype.Component;

@Component
public class ResponseComposer {

    public String composeCreateMeeting(Item item, String formattedDate) {
        return "Готово: встреча с " + item.getTitle().toLowerCase() + " — " + formattedDate + ".";
    }

    public String composeCreateTask(Item item, String formattedDate) {
        return "Готово: задача «" + item.getTitle() + "» — до " + formattedDate + ".";
    }

    public String composeCreateReminder(Item item, String formattedDate) {
        return "Готово: напомню " + formattedDate + " " + item.getTitle().toLowerCase() + ".";
    }

    public String composeEmptyList(String periodName) {
        return "На " + periodName + " задач нет.";
    }

    public String composeRefused() {
        return "Этого я пока не умею. Могу создавать задачи, напоминания и встречи, показывать списки и искать записи.";
    }
}