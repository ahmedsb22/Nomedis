package com.nomadix.traveler.profiling;

import com.nomadix.traveler.entity.Traveler;
import com.nomadix.traveler.repository.TravelerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests du recalcul de profil.
 *
 * <p>Regression corrigee : {@code POST /api/travelers/profiles/1/recompute}
 * renvoyait 404 quand le voyageur existait mais n'avait pas encore de ligne
 * {@code traveler_profiles}. Le service construit maintenant le profil a la
 * demande depuis l'entite Traveler.</p>
 */
@ExtendWith(MockitoExtension.class)
class TravelerProfilerServiceTest {

    @Mock
    private TravelerProfileRepository profileRepository;

    @Mock
    private TravelerRepository travelerRepository;

    private final ProfilingRulesEngine rulesEngine = new ProfilingRulesEngine();

    private TravelerProfilerService service() {
        return new TravelerProfilerService(rulesEngine, profileRepository, travelerRepository);
    }

    @Test
    @DisplayName("Profil existant : les regles courantes sont reappliquees")
    void recomputeExistingProfile() {
        TravelerProfile stored = new TravelerProfile();
        stored.setTravelerId("1");
        stored.setFullName("Amira Ben Salah");
        stored.setEmail("amira.bensalah@gmail.com");
        stored.setPhone("+216 55 123 456");
        stored.setPassportNumber("TN1234567");
        stored.setNationality("Tunisienne");
        stored.setBirthDate("1998-04-12");

        when(profileRepository.findById("1")).thenReturn(Optional.of(stored));
        when(profileRepository.save(any(TravelerProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TravelerProfile result = service().recompute("1");

        assertEquals(100, result.getScore());
        assertEquals("PLATINUM", result.getSegment());
        assertEquals(ProfilingRulesEngine.RULES_VERSION, result.getRulesVersion());
        assertNotNull(result.getUpdatedAt());
        verify(travelerRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Voyageur sans profil : le profil est cree a la demande (plus de faux 404)")
    void recomputeCreatesMissingProfileFromTraveler() {
        Traveler traveler = new Traveler();
        traveler.setId(7L);
        traveler.setFirstName("Sami");
        traveler.setLastName("Trabelsi");
        traveler.setEmail("sami@nomadix.tn");

        when(profileRepository.findById("7")).thenReturn(Optional.empty());
        when(travelerRepository.findById(7L)).thenReturn(Optional.of(traveler));
        when(profileRepository.save(any(TravelerProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TravelerProfile result = service().recompute("7");

        assertEquals("7", result.getTravelerId());
        assertEquals("Sami Trabelsi", result.getFullName());
        assertEquals("BRONZE", result.getSegment());
        assertTrue(result.isValidEmail());
        assertTrue(result.getRiskFlags().contains("PASSEPORT_MANQUANT"));
        assertNotNull(result.getCreatedAt());

        ArgumentCaptor<TravelerProfile> saved = ArgumentCaptor.forClass(TravelerProfile.class);
        verify(profileRepository).save(saved.capture());
        assertEquals("7", saved.getValue().getTravelerId());
    }

    @Test
    @DisplayName("Identifiant inconnu : erreur metier (mappee en 404 par le controleur)")
    void recomputeUnknownTravelerThrows() {
        when(profileRepository.findById("999")).thenReturn(Optional.empty());
        when(travelerRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service().recompute("999"));
        verify(profileRepository, never()).save(any(TravelerProfile.class));
    }

    @Test
    @DisplayName("Identifiant non numerique : erreur metier sans requete en base Traveler")
    void recomputeNonNumericIdThrows() {
        when(profileRepository.findById("abc")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service().recompute("abc"));
        verify(travelerRepository, never()).findById(any());
    }
}
