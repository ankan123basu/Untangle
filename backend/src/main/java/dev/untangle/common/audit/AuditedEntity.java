package dev.untangle.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Base for mutable entities: creation/update timestamps and an optimistic-lock version.
 *
 * <p>{@code @Version}: every UPDATE runs {@code ... WHERE id = ? AND version = ?} and increments
 * the version. If someone else changed the row first, zero rows match and Hibernate throws {@code
 * OptimisticLockException}. The API maps that to {@code 409 VERSION_CONFLICT} (ADR-011), so two
 * agents can never silently overwrite each other.
 */
@Getter
@MappedSuperclass
public abstract class AuditedEntity extends BaseEntity {

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
