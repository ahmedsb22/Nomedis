package com.nomadix.traveler.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Arrays;

@Aspect
@Component
public class LoggingPerformanceAspect {

    private static final Logger logger = LoggerFactory.getLogger(LoggingPerformanceAspect.class);

    // Pointcut : Intercepte toutes les méthodes publiques du package controller
    @Pointcut("execution(public * com.nomadix.traveler.controller.*.*(..))")
    public void controllerMethods() {}

    // Advice : Autour de l'exécution de la méthode
    @Around("controllerMethods()")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        String methodName = joinPoint.getSignature().toShortString();
        Object[] args = joinPoint.getArgs();

        logger.info(">>> [ENTRÉE] Méthode: {} | Arguments: {}", methodName, Arrays.toString(args));

        Object result = null;
        try {
            // Exécution de la méthode réelle (le CRUD)
            result = joinPoint.proceed(); 
        } catch (Exception e) {
            logger.error("!!! [ERREUR] Méthode: {} | Exception: {}", methodName, e.getMessage());
            throw e; // Propage l'erreur au GlobalExceptionHandler
        } finally {
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000;
            
            logger.info("<<< [SORTIE] Méthode: {} | Temps d'exécution: {} ms", methodName, durationMs);
            
            // Alerte performance si la requête dépasse 500ms
            if (durationMs > 500) {
                logger.warn("️ [ALERTE PERF] La méthode {} est LENTE ({} ms) !", methodName, durationMs);
            }
        }

        return result;
    }
}