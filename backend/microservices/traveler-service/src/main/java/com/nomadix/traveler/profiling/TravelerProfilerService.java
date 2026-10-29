package com.nomadix.traveler.profiling;

import com.nomadix.traveler.entity.Traveler;
import com.nomadix.traveler.repository.TravelerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.StringJoiner;

/**
 * Orchestration du service avance :
 * 1. extrait les attributs d'une entite Traveler quelconque (reflexion,
 *    tolerant aux noms de champs reels du projet),
 * 2. evalue le profil via le moteur de regles,
 * 3. persiste le resultat dans traveler_profiles.
 * Fail-safe : aucune erreur de profiling ne peut faire echouer le metier.
 */
@Service
public class TravelerProfilerService {

    private static final Logger log = LoggerFactory.getLogger(TravelerProfilerService.class);

    private final ProfilingRulesEngine rulesEngine;
    private final TravelerProfileRepository profileRepository;
    private final TravelerRepository travelerRepository;

    public TravelerProfilerService(ProfilingRulesEngine rulesEngine,
                                   TravelerProfileRepository profileRepository,
                                   TravelerRepository travelerRepository) {
        this.rulesEngine = rulesEngine;
        this.profileRepository = profileRepository;
        this.travelerRepository = travelerRepository;
    }

    /** Appele par l'aspect AOP apres chaque save() d'un traveler. */
    public void profileAndSave(Object travelerEntity) {
        try {
            if (travelerEntity == null) return;
            if (travelerEntity.getClass().getSimpleName().equals("TravelerProfile")) return;

            TravelerAttributes attrs = extractAttributes(travelerEntity);
            if (attrs.getTravelerId() == null || attrs.getTravelerId().trim().isEmpty()) {
                log.warn("[PROFILING] Entite sans id : profiling ignore");
                return;
            }

            ProfileResult result = rulesEngine.evaluate(attrs);

            TravelerProfile profile = profileRepository.findById(attrs.getTravelerId())
                    .orElseGet(() -> {
                        TravelerProfile p = new TravelerProfile();
                        p.setTravelerId(attrs.getTravelerId());
                        p.setCreatedAt(LocalDateTime.now());
                        return p;
                    });

            applyResult(profile, attrs, result);
            profileRepository.save(profile);
            log.info("[PROFILING] Traveler {} -> score={}, segment={}, flags=[{}]",
                    attrs.getTravelerId(), result.getScore(), result.getSegment(), profile.getRiskFlags());

        } catch (Exception e) {
            log.warn("[PROFILING] Echec du profiling (metier non impacte) : {}", e.getMessage());
        }
    }

    /** Supprime le profil quand le traveler est supprime. */
    public void deleteProfile(String travelerId) {
        try {
            if (travelerId != null && profileRepository.existsById(travelerId)) {
                profileRepository.deleteById(travelerId);
                log.info("[PROFILING] Profil supprime pour traveler {}", travelerId);
            }
        } catch (Exception e) {
            log.warn("[PROFILING] Suppression impossible : {}", e.getMessage());
        }
    }

    /**
     * Reapplique les regles courantes sur un profil deja stocke.
     *
     * <p>Si le voyageur existe mais n'a jamais ete profile (par exemple cree
     * avant l'activation de l'aspect), le profil est construit a la volee a
     * partir de l'entite Traveler : l'endpoint recompute ne renvoie donc plus
     * un 404 trompeur pour un identifiant valide.</p>
     */
    public TravelerProfile recompute(String travelerId) {
        TravelerProfile existing = profileRepository.findById(travelerId).orElse(null);
        return (existing != null) ? recomputeStored(existing) : recomputeFromTraveler(travelerId);
    }

    private TravelerProfile recomputeStored(TravelerProfile p) {
        TravelerAttributes attrs = new TravelerAttributes();
        attrs.setTravelerId(p.getTravelerId());
        attrs.setFullName(p.getFullName());
        attrs.setEmail(p.getEmail());
        attrs.setPhone(p.getPhone());
        attrs.setPassportNumber(p.getPassportNumber());
        attrs.setNationality(p.getNationality());
        attrs.setBirthDate(p.getBirthDate());

        applyResult(p, attrs, rulesEngine.evaluate(attrs));
        return profileRepository.save(p);
    }

    private TravelerProfile recomputeFromTraveler(String travelerId) {
        Long id = parseId(travelerId);
        Traveler traveler = (id == null) ? null : travelerRepository.findById(id).orElse(null);
        if (traveler == null) {
            throw new IllegalArgumentException("Voyageur ou profil introuvable : " + travelerId);
        }

        TravelerAttributes attrs = extractAttributes(traveler);
        TravelerProfile profile = new TravelerProfile();
        profile.setTravelerId(attrs.getTravelerId());
        profile.setCreatedAt(LocalDateTime.now());

        applyResult(profile, attrs, rulesEngine.evaluate(attrs));
        log.info("[PROFILING] Profil cree a la demande pour traveler {}", travelerId);
        return profileRepository.save(profile);
    }

    private void applyResult(TravelerProfile profile, TravelerAttributes attrs, ProfileResult result) {
        profile.setFullName(attrs.getFullName());
        profile.setEmail(attrs.getEmail());
        profile.setPhone(attrs.getPhone());
        profile.setPassportNumber(attrs.getPassportNumber());
        profile.setNationality(attrs.getNationality());
        profile.setBirthDate(attrs.getBirthDate());
        profile.setScore(result.getScore());
        profile.setSegment(result.getSegment());
        profile.setCompleteness(result.getCompleteness());
        profile.setValidEmail(result.isValidEmail());
        profile.setDisposableEmail(result.isDisposableEmail());
        profile.setValidPassport(result.isValidPassport());
        StringJoiner flags = new StringJoiner(", ");
        result.getRiskFlags().forEach(flags::add);
        profile.setRiskFlags(flags.toString());
        profile.setRulesVersion(ProfilingRulesEngine.RULES_VERSION);
        profile.setUpdatedAt(LocalDateTime.now());
    }

    private Long parseId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Extraction tolerant : cherche les champs par noms candidats,
    // en remontant la hierarchie de classes de l'entite.
    // ------------------------------------------------------------------
    private TravelerAttributes extractAttributes(Object entity) {
        TravelerAttributes a = new TravelerAttributes();
        a.setTravelerId(str(val(entity, "id")));
        a.setFullName(composeName(entity));
        a.setEmail(str(val(entity, "email", "mail", "emailAddress")));
        a.setPhone(str(val(entity, "phone", "phoneNumber", "tel", "telephone", "mobile")));
        a.setPassportNumber(str(val(entity, "passport", "passportNumber", "passportNo", "numPassport")));
        a.setNationality(str(val(entity, "nationality", "country", "pays")));
        a.setBirthDate(str(val(entity, "birthDate", "dateOfBirth", "dob", "dateNaissance")));
        return a;
    }

    private String composeName(Object entity) {
        String full  = str(val(entity, "name", "fullName", "nom"));
        if (full != null) return full;
        String first = str(val(entity, "firstName", "prenom"));
        String last  = str(val(entity, "lastName", "surname"));
        if (first != null && last != null) return first + " " + last;
        if (first != null) return first;
        return last;
    }

    private Object val(Object target, String... names) {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v != null) return v;
                } catch (NoSuchFieldException ignored) {
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private String str(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }
}
