package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.AuthUser;
import io.mosip.liveness.models.enums.AuthRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthUserRepository extends JpaRepository<AuthUser, UUID> {

    /**
     * The login lookup. The caller normalises the name first (see
     * {@code AuthService}), so this is a plain equality over the unique index
     * and not a collation-dependent {@code ILIKE}.
     */
    Optional<AuthUser> findByUsername(String username);

    /**
     * Whether an enabled account holds this role — the count behind
     * last-supervisor protection. Enabled-only on purpose: a disabled
     * supervisor cannot log in, so letting one satisfy the check would let the
     * last able supervisor be removed from the system entirely.
     */
    long countByRoleAndDisabledFalse(AuthRole role);

    /** The operator surface's user list; name order, not insertion order. */
    List<AuthUser> findAllByOrderByUsernameAsc();

    /**
     * Every enabled account holding a role — what last-supervisor protection
     * counts, and the list it subtracts the account being changed from.
     */
    List<AuthUser> findByRoleAndDisabledFalse(AuthRole role);

    /** Same normalisation rule as {@link #findByUsername}. */
    boolean existsByUsername(String username);
}
