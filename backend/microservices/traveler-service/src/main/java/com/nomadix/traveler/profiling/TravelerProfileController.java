package com.nomadix.traveler.profiling;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints du SERVICE AVANCE (valeur ajoutee metier).
 * Accessibles via la Gateway sur /api/travelers/profiles/**
 * (route existante /api/travelers/** - aucune modification Gateway requise).
 */
@RestController
@RequestMapping("/api/travelers/profiles")
@Tag(name = "Smart Profiling", description = "Service avance : scoring, segmentation et detection d'anomalies des voyageurs")
public class TravelerProfileController {

    private final TravelerProfileRepository profileRepository;
    private final TravelerProfilerService profilerService;

    public TravelerProfileController(TravelerProfileRepository profileRepository,
                                     TravelerProfilerService profilerService) {
        this.profileRepository = profileRepository;
        this.profilerService = profilerService;
    }

    @Operation(summary = "Tous les profils calcules")
    @GetMapping
    public ResponseEntity<List<TravelerProfile>> all() {
        return ResponseEntity.ok(profileRepository.findAll());
    }

    @Operation(summary = "Classement des meilleurs voyageurs (par score)")
    @GetMapping("/top")
    public ResponseEntity<List<TravelerProfile>> top(@RequestParam(defaultValue = "5") int limit) {
        List<TravelerProfile> all = profileRepository.findAllByOrderByScoreDesc();
        return ResponseEntity.ok(all.subList(0, Math.min(limit, all.size())));
    }

    @Operation(summary = "Repartition des voyageurs par segment (PLATINUM/GOLD/SILVER/BRONZE)")
    @GetMapping("/segments")
    public ResponseEntity<Map<String, Long>> segments() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String s : new String[]{"PLATINUM", "GOLD", "SILVER", "BRONZE"}) {
            map.put(s, profileRepository.countBySegment(s));
        }
        return ResponseEntity.ok(map);
    }

    @Operation(summary = "Statistiques globales (KPIs du moteur de profiling)")
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> statistics() {
        List<TravelerProfile> all = profileRepository.findAll();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProfils", all.size());
        stats.put("scoreMoyen", all.isEmpty() ? 0 :
                all.stream().mapToInt(TravelerProfile::getScore).average().orElse(0));
        stats.put("completudeMoyenne", all.isEmpty() ? 0 :
                all.stream().mapToInt(TravelerProfile::getCompleteness).average().orElse(0));
        stats.put("emailsValides", all.stream().filter(TravelerProfile::isValidEmail).count());
        stats.put("emailsJetables", all.stream().filter(TravelerProfile::isDisposableEmail).count());
        stats.put("passeportsValides", all.stream().filter(TravelerProfile::isValidPassport).count());
        stats.put("profilsARisque", all.stream()
                .filter(p -> p.getRiskFlags() != null && !p.getRiskFlags().isEmpty()
                          && p.getScore() < 45).count());
        stats.put("versionRegles", ProfilingRulesEngine.RULES_VERSION);
        return ResponseEntity.ok(stats);
    }

    @Operation(summary = "Voyageurs presents des anomalies (detection de risques)")
    @GetMapping("/risky")
    public ResponseEntity<List<TravelerProfile>> risky() {
        return ResponseEntity.ok(profileRepository.findByScoreLessThanOrderByScoreAsc(45));
    }

    @Operation(summary = "Profil detaille d'un voyageur")
    @GetMapping("/{travelerId}")
    public ResponseEntity<TravelerProfile> byId(@PathVariable String travelerId) {
        return profileRepository.findById(travelerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Recalculer un profil avec les regles courantes (evolution du moteur)")
    @PostMapping("/{travelerId}/recompute")
    public ResponseEntity<?> recompute(@PathVariable String travelerId) {
        try {
            return ResponseEntity.ok(profilerService.recompute(travelerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}