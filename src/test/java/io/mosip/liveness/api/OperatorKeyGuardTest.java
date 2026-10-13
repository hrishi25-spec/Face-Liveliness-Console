package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The operator gate's decision table.
 *
 * <p>Four things are asserted here and nowhere else: which header carries the
 * key (the wire contract the console and every deployment share), which role each
 * configured key proves, what an unconfigured key does (nothing — fail closed),
 * and that the refusal text carries no trace of what the caller sent, because the
 * same string is what a guesser reads back on every failed attempt.</p>
 *
 * <p>All values are synthetic. A real key never appears in a test.</p>
 */
class OperatorKeyGuardTest {

    private static final String OPERATOR_KEY = "synthetic-operator-key-0001";
    private static final String ADMIN_KEY = "synthetic-admin-key-0000000000000000001";

    @Test
    void theWireHeaderIsPartOfTheContract() {
        // A rename here silently breaks every console and deployment that sends
        // the documented name while the guard keeps answering 401.
        assertEquals("X-Operator-API-Key", OperatorKeyGuard.HEADER);
        assertEquals("X-Admin-API-Key", AdminKeyGuard.HEADER);
    }

    @Test
    void validOperatorKeyProvesTheOperatorRole() {
        assertEquals(OperatorKeyGuard.ROLE_OPERATOR,
                OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, OPERATOR_KEY, null));
    }

    @Test
    void validAdminKeyProvesTheSupervisorRoleOnOperatorRoutes() {
        // A supervisor can do everything an operator can, so the admin key is
        // accepted here and answered as the larger role.
        assertEquals(OperatorKeyGuard.ROLE_SUPERVISOR,
                OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, null, ADMIN_KEY));
    }

    @Test
    void bothKeysPresentAnswersTheLargerRole() {
        // Both headers are caller-supplied; whoever holds both is a supervisor,
        // and the answer must not depend on which header was read first.
        assertEquals(OperatorKeyGuard.ROLE_SUPERVISOR,
                OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, OPERATOR_KEY, ADMIN_KEY));
    }

    @Test
    void wrongKeysProveNothing() {
        assertNull(OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, "not-the-operator-key", null));
        assertNull(OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, null, "not-the-admin-key"));
        assertNull(OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, "x", "y"));
    }

    @Test
    void missingHeadersProveNothing() {
        assertNull(OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, null, null));
    }

    @Test
    void aPrefixOrSuffixOfAKeyProvesNothing() {
        // The compare is over the whole value, not a substring of it.
        assertNull(OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY,
                OPERATOR_KEY.substring(0, OPERATOR_KEY.length() - 1), null));
        assertNull(OperatorKeyGuard.role(OPERATOR_KEY, ADMIN_KEY, OPERATOR_KEY + "x", null));
    }

    @Test
    void anUnconfiguredOperatorKeyRefusesEveryOperatorAttempt() {
        // Fail closed: no operator key on the service means nobody is an operator,
        // including a caller who sends an empty header hoping it matches.
        assertNull(OperatorKeyGuard.role("", ADMIN_KEY, OPERATOR_KEY, null));
        assertNull(OperatorKeyGuard.role("", ADMIN_KEY, "", null));
        assertNull(OperatorKeyGuard.role(null, ADMIN_KEY, null, null));
    }

    @Test
    void anUnconfiguredOperatorKeyStillHonoursTheAdminKey() {
        // Deliberate, and the reason a blank operator key is not "disabled
        // everything": a deployment that sets only MOSIP_ADMIN_API_KEY keeps its
        // supervisor working, while nobody gains operator access.
        assertEquals(OperatorKeyGuard.ROLE_SUPERVISOR,
                OperatorKeyGuard.role("", ADMIN_KEY, null, ADMIN_KEY));
    }

    @Test
    void noKeyConfiguredRefusesEveryone() {
        assertNull(OperatorKeyGuard.role("", "", null, null));
        assertNull(OperatorKeyGuard.role("", "", OPERATOR_KEY, ADMIN_KEY));
        assertNull(OperatorKeyGuard.role(null, null, OPERATOR_KEY, ADMIN_KEY));
    }

    @Test
    void theRefusalTextIsGenericAndSaysNothingAboutWhatWasPresented() {
        assertFalse(OperatorKeyGuard.REFUSAL.contains(OPERATOR_KEY), OperatorKeyGuard.REFUSAL);
        assertFalse(OperatorKeyGuard.REFUSAL.contains(ADMIN_KEY), OperatorKeyGuard.REFUSAL);
        // Not even a fragment: a message that named a header would tell a guesser
        // which of the two keys is worth attacking.
        assertFalse(OperatorKeyGuard.REFUSAL.toLowerCase().contains("x-operator"));
        assertFalse(OperatorKeyGuard.REFUSAL.toLowerCase().contains("x-admin"));
        // And it is one fixed string, so two different caller mistakes cannot be
        // told apart by the wording either.
        assertEquals(OperatorKeyGuard.REFUSAL, OperatorKeyGuard.REFUSAL.trim());
        assertNotEquals("", OperatorKeyGuard.REFUSAL);
    }

    @Test
    void theRoleNamesAreTheOnesTheConsoleSwitchesOn() {
        assertEquals("SUPERVISOR", OperatorKeyGuard.ROLE_SUPERVISOR);
        assertEquals("OPERATOR", OperatorKeyGuard.ROLE_OPERATOR);
    }
}
