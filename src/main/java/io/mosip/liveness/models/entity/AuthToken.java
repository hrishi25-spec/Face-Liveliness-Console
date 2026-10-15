package io.mosip.liveness.models.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One logged-in session, as stored in {@code auth_tokens}.
 *
 * <p>The row holds {@code SHA-256(token)}, never the token: a database snapshot
 * is therefore not a set of usable sessions. The token itself exists only in the
 * {@code Set-Cookie} header that handed it to the browser, which is why no
 * response body and no log line ever contains one — there is nothing to leak
 * from this table and nothing that puts the value anywhere else.</p>
 *
 * <p>A fast hash rather than BCrypt is deliberate. The token is 256 bits of
 * CSPRNG output, so there is no guessable input for a slow hash to defend
 * against, and this lookup runs on every authenticated request — it must stay
 * one indexed equality.</p>
 *
 * <p>Expiry and revocation are separate columns because they mean different
 * things to an operator: {@code expiresAt} is the clock running out on a session
 * that was still valid, {@code revokedAt} is a session that was ended
 * deliberately (a logout, or a password change invalidating everything). Both
 * refuse identically on the wire — a caller learns only that it is not
 * authenticated.</p>
 */
@Entity
@Table(name = "auth_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The account this session belongs to; deleting the account cascades. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AuthUser user;

    /** Hex SHA-256 of the opaque token; the only form the service ever stores. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /**
     * Last time this token authenticated a request, refreshed at most once a
     * minute so a busy session does not write on every call.
     */
    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    /** Whether this session may still authenticate at {@code now}. */
    public boolean isUsableAt(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** Ends this session, if it is still open. */
    public void revoke(OffsetDateTime now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    @PrePersist
    void onCreate() {
        if (issuedAt == null) {
            issuedAt = OffsetDateTime.now();
        }
    }
}
