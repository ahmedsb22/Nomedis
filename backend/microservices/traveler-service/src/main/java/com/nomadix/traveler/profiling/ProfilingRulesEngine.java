package com.nomadix.traveler.profiling;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * =====================================================================
 *  SERVICE AVANCE - SMART TRAVELER PROFILING ENGINE
 * =====================================================================
 *  Moteur de regles metier pur (sans dependance technique) :
 *  - calcule un score de qualite de profil (0 a 100)
 *  - determine un segment : PLATINUM / GOLD / SILVER / BRONZE
 *  - mesure la completude du profil
 *  - detecte les anomalies (email invalide, email jetable,
 *    passeport invalide, profil incomplet, profil a risque)
 *
 *  Versionnage des regles : permet de faire evoluer la politique
 *  de scoring et de recalculer tous les profils (endpoint recompute).
 * =====================================================================
 */
@Component
public class ProfilingRulesEngine {

    public static final String RULES_VERSION = "1.1";

    private static final String EMAIL_REGEX    = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    private static final String PASSPORT_REGEX = "^[A-Z0-9]{6,9}$";
    private static final String PHONE_REGEX    = "^[+]?[0-9 ]{8,15}$";

    private static final Set<String> DISPOSABLE_DOMAINS = new HashSet<>(Arrays.asList(
            "mailinator.com", "tempmail.com", "10minutemail.com", "yopmail.com",
            "guerrillamail.com", "trashmail.com", "sharklasers.com", "getnada.com"));

    /** Barèmes de la grille de scoring (total max = 100) */
    private static final int PTS_COMPLETENESS   = 40; // 6 champs -> 40 pts au prorata
    private static final int PTS_EMAIL_VALID    = 10;
    private static final int PTS_EMAIL_CLEAN    = 5;  // non jetable
    private static final int PTS_PASSPORT_VALID = 15;
    private static final int PTS_PHONE_VALID    = 10;
    private static final int PTS_NAME_QUALITY   = 5;
    private static final int PTS_NATIONALITY    = 5;
    private static final int PTS_COHERENCE      = 10; // email ET passeport valides

    public ProfileResult evaluate(TravelerAttributes a) {

        ProfileResult r = new ProfileResult();
        int score = 0;

        // --- Regle 1 : completude du profil (40 pts au prorata) --------------
        int filled = 0;
        if (a.filled(a.getFullName()))       filled++;
        if (a.filled(a.getEmail()))          filled++;
        if (a.filled(a.getPhone()))          filled++;
        if (a.filled(a.getPassportNumber())) filled++;
        if (a.filled(a.getNationality()))    filled++;
        if (a.filled(a.getBirthDate()))      filled++;
        r.setCompleteness((int) Math.round(filled * PTS_COMPLETENESS / 6.0));
        score += r.getCompleteness();

        // --- Regle 2 : validite de l'email ------------------------------------
        String email = a.filled(a.getEmail()) ? a.getEmail().trim().toLowerCase() : null;
        r.setValidEmail(email != null && email.matches(EMAIL_REGEX));
        if (email == null) {
            r.addRiskFlag("EMAIL_MANQUANT");
        } else if (!r.isValidEmail()) {
            r.addRiskFlag("EMAIL_INVALIDE");
        } else {
            score += PTS_EMAIL_VALID;
        }

        // --- Regle 3 : email jetable (domaine temporaire) ----------------------
        if (email != null) {
            String domain = email.substring(email.indexOf('@') + 1);
            r.setDisposableEmail(DISPOSABLE_DOMAINS.contains(domain));
            if (r.isDisposableEmail()) {
                r.addRiskFlag("EMAIL_JETABLE");
            } else if (r.isValidEmail()) {
                score += PTS_EMAIL_CLEAN;
            }
        }

        // --- Regle 4 : validite du passeport ------------------------------------
        String passport = a.filled(a.getPassportNumber()) ? a.getPassportNumber().trim().toUpperCase() : null;
        r.setValidPassport(passport != null && passport.matches(PASSPORT_REGEX));
        if (passport == null) {
            r.addRiskFlag("PASSEPORT_MANQUANT");
        } else if (!r.isValidPassport()) {
            r.addRiskFlag("PASSEPORT_INVALIDE");
        } else {
            score += PTS_PASSPORT_VALID;
        }

        // --- Regle 5 : validite du telephone -------------------------------------
        if (a.filled(a.getPhone())) {
            if (a.getPhone().trim().matches(PHONE_REGEX)) {
                score += PTS_PHONE_VALID;
            } else {
                r.addRiskFlag("TELEPHONE_INVALIDE");
            }
        }

        // --- Regle 6 : qualite du nom ---------------------------------------------
        if (a.filled(a.getFullName())) {
            score += (a.getFullName().trim().length() >= 3 && a.getFullName().trim().contains(" "))
                     ? PTS_NAME_QUALITY : PTS_NAME_QUALITY / 2;
        }

        // --- Regle 7 : nationalite renseignee --------------------------------------
        if (a.filled(a.getNationality())) {
            score += PTS_NATIONALITY;
        }

        // --- Regle 8 : coherence globale (bonus) ------------------------------------
        if (r.isValidEmail() && r.isValidPassport()) {
            score += PTS_COHERENCE;
        }

        // --- Regle 9 : segment et profil a risque -----------------------------------
        r.setScore(Math.max(0, Math.min(100, score)));
        if (r.getScore() >= 80)      r.setSegment("PLATINUM");
        else if (r.getScore() >= 65) r.setSegment("GOLD");
        else if (r.getScore() >= 45) r.setSegment("SILVER");
        else                         r.setSegment("BRONZE");

        if (r.getScore() < 45) {
            r.addRiskFlag("PROFIL_A_RISQUE");
        }
        return r;
    }
}