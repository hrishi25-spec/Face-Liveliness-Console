package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.models.enums.AuthRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator key's decision table.
 *
 * <p>Four things are asserted here and nowhere else: which header carries the key
 * (the wire contract the console and every deployment share), what an unconfigured
 * key does (nothing — fail closed), that the refusal text carries no trace of what
 * the caller sent, and that the two role names are the exact strings the console
 * switches on.</p>
 *
 * <p>What is no longer here is the admin half. The admin key was deleted, so
 * {@code OperatorKeyGuard} no longer takes a second configured key, no longer
 * answers {@code SUPERVISOR}, and the rule that the two keys had to differ is gone
 * with the key it was about. A supervisor is an account now, and the cases that
 * used to live in this class are {@code AuthServiceTest}'s and
 * {@code UserManagementTest}'s.</p>
 *
 * <p>All values are synthetic. A real key never appears in a test.</p>
 */
class OperatorKeyGuardTest {

    private static final String OPERATOR_KEY = "synthetic-operator-key-0001";

    @Test
    void theWireHeaderIsPartOfTheContract() {
        // A rename here silently breaks every console and deployment that sends
        // the documented name while the guard keeps refusing.
        assertEquals("X-Operator-API-Key", OperatorKeyGuard.HEADER);
    }

    @Test
    void validOperatorKeyProvesTheRole() {
        assertTrue(OperatorKeyGuard.proves(OPERATOR_KEY, OPERATOR_KEY));
    }

    @Test
    void wrongKeysProveNothing() {
        assertFalse(OperatorKeyGuard.proves(OPERATOR_KEY, "not-the-operator-key"));
        assertFalse(OperatorKeyGuard.proves(OPERATOR_KEY, "x"));
    }

    @Test
    void missingHeadersProveNothing() {
        assertFalse(OperatorKeyGuard.proves(OPERATOR_KEY, null));
    }

    @Test
    void aPrefixOrSuffixOfAKeyProvesNothing() {
        // The compare is over the whole value, not a substring of it.
        assertFalse(OperatorKeyGuard.proves(OPERATOR_KEY,
                OPERATOR_KEY.substring(0, OPERATOR_KEY.length() - 1)));
        assertFalse(OperatorKeyGuard.proves(OPERATOR_KEY, OPERATOR_KEY + "x"));
    }

    @Test
    void anUnconfiguredKeyRefusesEveryAttempt() {
        // Fail closed: no operator key on the service means the key proves nothing,
        // including when a caller sends an empty header hoping it matches. Sessions
        // still reach these routes; the key-based path is what closes.
        assertFalse(OperatorKeyGuard.proves("", OPERATOR_KEY));
        assertFalse(OperatorKeyGuard.proves("", ""));
        assertFalse(OperatorKeyGuard.proves(null, null));
        assertFalse(OperatorKeyGuard.proves("   ", "   "));
    }

    @Test
    void theRefusalTextIsGenericAndSaysNothingAboutWhatWasPresented() {
        assertFalse(OperatorKeyGuard.REFUSAL.contains(OPERATOR_KEY), OperatorKeyGuard.REFUSAL);
        // Not even a fragment: a message that named a header would tell a guesser
        // which credential is worth attacking.
        assertFalse(OperatorKeyGuard.REFUSAL.toLowerCase().contains("x-operator"));
        // And it is the same string every other unauthenticated refusal uses, so a
        // caller cannot tell a key-based route from a session-based one by wording.
        assertEquals(AuthRefusals.UNAUTHENTICATED, OperatorKeyGuard.REFUSAL);
        assertEquals(OperatorKeyGuard.REFUSAL, OperatorKeyGuard.REFUSAL.trim());
        assertNotEquals("", OperatorKeyGuard.REFUSAL);
    }

    @Test
    void theRoleNamesAreTheOnesTheConsoleSwitchesOn() {
        // Aliases of the enum, not a second spelling of it: a rename cannot drift.
        assertEquals(AuthRole.SUPERVISOR.name(), OperatorKeyGuard.ROLE_SUPERVISOR);
        assertEquals(AuthRole.OPERATOR.name(), OperatorKeyGuard.ROLE_OPERATOR);
        assertEquals("SUPERVISOR", OperatorKeyGuard.ROLE_SUPERVISOR);
        assertEquals("OPERATOR", OperatorKeyGuard.ROLE_OPERATOR);
    }
}
