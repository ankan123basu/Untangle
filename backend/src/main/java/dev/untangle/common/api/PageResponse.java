package dev.untangle.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paginated lists (§9): {@code {content, page, size, totalElements,
 * totalPages}}. We don't serialise Spring's {@code Page} directly, because its JSON shape is an
 * implementation detail that has changed between versions.
 */
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
    return new PageResponse<>(
        page.getContent().stream().map(mapper).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
