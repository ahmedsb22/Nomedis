package com.nomadix.traveler.profiling;


import java.lang.reflect.Field;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Aspect AOP (valeur ajoutee transversale) :
 * intercepte automatiquement les sauvegardes et suppressions du
 * repository de travelers et declenche le profiling - SANS modifier
 * une seule ligne du code metier existant.
 * Exclusion explicite de notre propre repository (anti-recursivite).
 */
@Aspect
@Component
public class TravelerProfilerAspect {

    private static final Logger log = LoggerFactory.getLogger(TravelerProfilerAspect.class);

    private final TravelerProfilerService profilerService;

    public TravelerProfilerAspect(TravelerProfilerService profilerService) {
        this.profilerService = profilerService;
    }

    /** Apres chaque save() d'un traveler (l'entite retournee porte l'id genere). */
    @AfterReturning(
            pointcut = "execution(* com.nomadix.traveler..*Repository+.save(..))",
            returning = "entity")
    public void onTravelerSaved(JoinPoint jp, Object entity) {
        if (entity == null) return;
        if (entity.getClass().getSimpleName().equals("TravelerProfile")) return; // anti-recursivite
        profilerService.profileAndSave(entity);
    }

    /** Apres chaque suppression : le profil est supprime aussi. */
    @AfterReturning(
            pointcut = "execution(* com.nomadix.traveler..*Repository+.delete(..)) || "
                     + "execution(* com.nomadix.traveler..*Repository+.deleteById(..))")
    public void onTravelerDeleted(JoinPoint jp) {
        try {
            for (Object arg : jp.getArgs()) {
                if (arg == null) continue;
                String id = extractId(arg);
                if (id != null) profilerService.deleteProfile(id);
            }
        } catch (Exception e) {
            log.warn("[PROFILING] Aspect delete : {}", e.getMessage());
        }
    }

    private String extractId(Object arg) {
        if (arg instanceof Number || arg instanceof String) {
            return String.valueOf(arg);
        }
        try {
            Field f = arg.getClass().getDeclaredField("id");
            f.setAccessible(true);
            Object v = f.get(arg);
            return (v == null) ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }
}