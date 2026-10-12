package com.nomadix.traveler.profiling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires du moteur de regles du service avance.
 */
class ProfilingRulesEngineTest {

    private final ProfilingRulesEngine engine = new ProfilingRulesEngine();

    private TravelerAttributes completeTraveler() {
        TravelerAttributes a = new TravelerAttributes();
        a.setTravelerId("1");
        a.setFullName("Amira Ben Salah");
        a.setEmail("amira.bensalah@gmail.com");
        a.setPhone("+216 55 123 456");
        a.setPassportNumber("TN1234567");
        a.setNationality("Tunisienne");
        a.setBirthDate("1998-04-12");
        return a;
    }

    @Test
    @DisplayName("Profil complet et coherent -> segment GOLD ou PLATINUM, aucune anomalie")
    void completeProfileScoresHigh() {
        ProfileResult r = engine.evaluate(completeTraveler());
        assertTrue(r.getScore() >= 65, "score attendu >= 65, obtenu " + r.getScore());
        assertTrue(r.getSegment().equals("GOLD") || r.getSegment().equals("PLATINUM"));
        assertTrue(r.isValidEmail());
        assertTrue(r.isValidPassport());
        assertTrue(r.getRiskFlags().isEmpty());
    }

    @Test
    @DisplayName("Passeport invalide -> flag PASSEPORT_INVALIDE et score reduit")
    void invalidPassportIsFlagged() {
        TravelerAttributes a = completeTraveler();
        a.setPassportNumber("abc!");
        ProfileResult r = engine.evaluate(a);
        assertTrue(r.getRiskFlags().contains("PASSEPORT_INVALIDE"));
        assertFalse(r.isValidPassport());
    }

    @Test
    @DisplayName("Email jetable -> flag EMAIL_JETABLE")
    void disposableEmailIsFlagged() {
        TravelerAttributes a = completeTraveler();
        a.setEmail("amira@mailinator.com");
        ProfileResult r = engine.evaluate(a);
        assertTrue(r.getRiskFlags().contains("EMAIL_JETABLE"));
        assertTrue(r.isDisposableEmail());
    }

    @Test
    @DisplayName("Profil quasi vide -> segment BRONZE et flag PROFIL_A_RISQUE")
    void emptyProfileIsRisky() {
        TravelerAttributes a = new TravelerAttributes();
        a.setTravelerId("2");
        a.setFullName("X");
        ProfileResult r = engine.evaluate(a);
        assertEquals("BRONZE", r.getSegment());
        assertTrue(r.getRiskFlags().contains("PROFIL_A_RISQUE"));
        assertTrue(r.getScore() < 45);
    }
}