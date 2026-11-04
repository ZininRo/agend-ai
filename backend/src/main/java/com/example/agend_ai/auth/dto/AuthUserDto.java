package com.example.agend_ai.auth.dto;

import com.example.agend_ai.entity.User;

public record AuthUserDto(
    String id, String email, String displayName, String timezone, String ttsVoice) {
  public static AuthUserDto from(User user, String email, String effectiveTtsVoice) {
    return new AuthUserDto(
        user.getId().toString(),
        email,
        user.getDisplayName(),
        user.getTimezone(),
        effectiveTtsVoice);
  }
}
