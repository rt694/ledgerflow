package com.ledgerflow.identity;

import com.ledgerflow.shared.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final TokenService tokens;
  private final Clock clock;
  private final String dummyHash;

  AuthService(UserRepository users, PasswordEncoder passwords, TokenService tokens, Clock clock) {
    this.users = users;
    this.passwords = passwords;
    this.tokens = tokens;
    this.clock = clock;
    this.dummyHash = passwords.encode("unused-dummy-password");
  }

  @Transactional
  UserDirectory.UserSummary register(AuthController.RegisterRequest request) {
    checkPasswordBytes(request.password());
    String email = UserDirectory.normalizeEmail(request.email());
    if (users.existsByEmail(email))
      throw BusinessException.conflict("An account with that email already exists.");
    User user =
        users.saveAndFlush(
            new User(
                email,
                request.displayName().strip(),
                passwords.encode(request.password()),
                clock.instant()));
    return UserDirectory.summary(user);
  }

  @Transactional(readOnly = true)
  TokenService.TokenResponse login(AuthController.LoginRequest request) {
    checkPasswordBytes(request.password());
    User user = users.findByEmail(UserDirectory.normalizeEmail(request.email())).orElse(null);
    boolean matches =
        passwords.matches(request.password(), user == null ? dummyHash : user.passwordHash());
    if (user == null || !matches) {
      throw new BusinessException(
          HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
    }
    return tokens.issue(user.id());
  }

  private void checkPasswordBytes(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw BusinessException.invalid("Password must be at most 72 UTF-8 bytes.");
    }
  }
}
