package com.ledgerflow.identity;

import com.ledgerflow.shared.api.BusinessException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public identity API; persistence models stay inside this package. */
@Service
@Transactional(readOnly = true)
public class UserDirectory {
  private final UserRepository users;

  public UserDirectory(UserRepository users) {
    this.users = users;
  }

  public UserSummary require(UUID id) {
    return summary(users.findById(id).orElseThrow(BusinessException::notFound));
  }

  public UserSummary requireByEmail(String email) {
    return summary(
        users.findByEmail(normalizeEmail(email)).orElseThrow(BusinessException::notFound));
  }

  static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  static UserSummary summary(User user) {
    return new UserSummary(user.id(), user.email(), user.displayName());
  }

  public record UserSummary(UUID id, String email, String displayName) {}
}
