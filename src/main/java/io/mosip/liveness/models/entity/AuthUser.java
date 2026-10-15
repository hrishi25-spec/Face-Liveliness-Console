package io.mosip.liveness.models.entity;

import io.mosip.liveness.models.enums.AuthRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One operator account, as stored in {@code auth_users}.
 *
 * <p>The row holds a BCrypt hash and never a password: {@code passwordHash} is
 * the output of {@code PasswordHasher}, which is the only thing in this service
 * that produces or checks one. Nothing here is reversible, and nothing derived
 * from a password is logged.</p>
 *
 * <p>Fields worth knowing about, because each exists to make one refusal
 * possible:</p>
 * <ul>
 *   <li>{@code mustChangePassword} — {@code true} for a bootstrap or
 *       supervisor-reset password. Enforced by {@code AuthContextFilter}, not
 *       by the change-password handler, so a session in this state cannot reach
 *       anything else even if a future endpoint forgets to check.</li>
 *   <li>{@code disabled} — authenticates as nobody while keeping the row, so
 *       disabling an account does not erase who it was from the audit trail.</li>
 *   <li>{@code failedAttempts} / {@code lockedUntil} — a lockout that expires
 *       on its own from a timestamp rather than needing a reaper job. Stored,
 *       not in-memory, so a restart cannot clear it.</li>
 * </ul>
 */
@Entity
@Table(name = "auth_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * The login name, lowercased. Normalisation is the service's job (it
     * lowercases before every lookup and every insert) so that
     * {@code uq_auth_users_username} can stay a plain unique index instead of
     * depending on a database collation.
     */
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** BCrypt hash, self-describing ({@code $2a$12$...}); never a password. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AuthRole role;

    /**
     * Whether this account's password is still one the service handed out.
     * Defaults to {@code true} so an account created without an explicit
     * choice is the restrictive one.
     */
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = true;

    /** A disabled account authenticates as nobody, without losing its row. */
    @Column(name = "disabled", nullable = false)
    @Builder.Default
    private boolean disabled = false;

    /** Consecutive failed logins; a success resets it to zero. */
    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    /**
     * When the lockout lapses. Non-null while locked; the comparison is against
     * the current time at each login attempt, so the lock needs no job to end.
     */
    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "password_changed_at")
    private OffsetDateTime passwordChangedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** True while this account is inside its lockout window. */
    public boolean isLockedAt(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** True when this account may authenticate at all. */
    public boolean isActive() {
        return !disabled;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
