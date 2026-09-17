package com.ledgerflow.shared.api;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {
  public static <T> PageResponse<T> from(Page<T> result) {
    return new PageResponse<>(
        result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
  }
}
