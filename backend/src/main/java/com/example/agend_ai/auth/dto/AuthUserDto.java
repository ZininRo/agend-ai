package com.example.agend_ai.auth.dto;

import com.example.agend_ai.entity.User;

public record AuthUserDto(String id, String email, String displayName, String timezone) {
  public static AuthUserDto from(User user, String email) {
    return new AuthUserDto(
        user.getId().toString(), email, user.getDisplayName(), user.getTimezone());
  }
}
