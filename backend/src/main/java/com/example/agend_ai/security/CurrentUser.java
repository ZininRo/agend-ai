package com.example.agend_ai.security;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.entity.User;
import com.example.agend_ai.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Единая точка получения authenticated пользователя текущего запроса. */
@Component
@RequiredArgsConstructor
public class CurrentUser {
  private final UserRepository users;

  public UUID id() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal()))
      throw new AppException(ErrorCode.AUTH_REQUIRED);
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException invalid) {
      throw new AppException(ErrorCode.AUTH_REQUIRED);
    }
  }

  public User reference() {
    return users.getReferenceById(id());
  }
}
