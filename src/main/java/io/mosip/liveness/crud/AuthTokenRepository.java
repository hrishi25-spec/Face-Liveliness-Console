package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    /**
     * The lookup every authenticated request makes: one indexed equality on the
     * hash, never on the token itself (the token is not stored anywhere).
     */
    Optional<AuthToken> findByTokenHash(String tokenHash);

    /**
     * Every still-open session a user holds — what a password change revokes.
     * The user's own new token is minted after that call, so it is not caught by
     * the sweep.
     */
    List<AuthToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
