package com.nomadix.traveler.profiling;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 *
 * <p>Toutes les operations exigent un jeton Keycloak portant le role ADMIN :
 * c'est la regle appliquee a la fois par la Gateway et par ce service.</p>
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

    @Operation(
            summary = "Tous les profils calcules",
            description = "Renvoie un tableau (vide si aucun voyageur n'a encore ete profile). "
                    + "Un profil est cree automatiquement a chaque sauvegarde d'un voyageur.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des profils"),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role ADMIN manquant", content = @Content)
    })
    @GetMapping
    public ResponseEntity<List<TravelerProfile>> all() {
        return ResponseEntity.ok(profileRepository.findAll());
    }

    @Operation(
            summary = "Classement des meilleurs voyageurs (par score)",
            description = "Trie les profils par score decroissant et renvoie les `limit` premiers. "
                    + "Une valeur negative ou superieure au nombre de profils est ramenee dans les bornes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Classement des profils"),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role ADMIN manquant", content = @Content)
    })
    @GetMapping("/top")
    public ResponseEntity<List<TravelerProfile>> top(
            @Parameter(description = "Nombre de profils a renvoyer", example = "5")
            @RequestParam(defaultValue = "5") int limit) {
        List<TravelerProfile> all = profileRepository.findAllByOrderByScoreDesc();
        int bounded = Math.max(0, Math.min(limit, all.size()));
        return ResponseEntity.ok(all.subList(0, bounded));
    }

    @Operation(
            summary = "Repartition des voyageurs par segment (PLATINUM/GOLD/SILVER/BRONZE)",
            description = "Compte les profils de chaque segment. Les quatre cles sont toujours presentes, "
                    + "meme quand le compteur vaut 0.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compteur par segment"),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role ADMIN manquant", content = @Content)
    })
    @GetMapping("/segments")
    public ResponseEntity<Map<String, Long>> segments() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String s : new String[]{"PLATINUM", "GOLD", "SILVER", "BRONZE"}) {
            map.put(s, profileRepository.countBySegment(s));
        }
        return ResponseEntity.ok(map);
    }

    @Operation(
            summary = "Statistiques globales (KPIs du moteur de profiling)",
            description = """
                    Indicateurs calcules sur l'ensemble des profils :
                    `totalProfils`, `scoreMoyen`, `completudeMoyenne`, `emailsValides`,
                    `emailsJetables`, `passeportsValides`, `profilsARisque`, `versionRegles`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KPIs du profiling"),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role ADMIN manquant", content = @Content)
    })
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
        stats.put("profilsARisque", all.stream().filter(p -> p.getScore() < 45).count());
        stats.put("versionRegles", ProfilingRulesEngine.RULES_VERSION);
        return ResponseEntity.ok(stats);
    }

    @Operation(
            summary = "Voyageurs presents des anomalies (detection de risques)",
            description = "Profils dont le score est strictement inferieur a 45 (segment BRONZE), "
                    + "tries du plus risque au moins risque.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profils a risque"),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role ADMIN manquant", content = @Content)
    })
    @GetMapping("/risky")
    public ResponseEntity<List<TravelerProfile>> risky() {
        return ResponseEntity.ok(profileRepository.findByScoreLessThanOrderByScoreAsc(45));
    }

    @Operation(summary = "Profil detaille d'un voyageur")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profil du voyageur"),
            @ApiResponse(responseCode = "404", description = "Aucun profil pour ce voyageur", content = @Content),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role ADMIN manquant", content = @Content)
    })
    @GetMapping("/{travelerId}")
    public ResponseEntity<TravelerProfile> byId(
            @Parameter(description = "Identifiant du voyageur", example = "1")
            @PathVariable String travelerId) {
        return profileRepository.findById(travelerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Recalculer un profil avec les regles courantes (evolution du moteur)",
            description = """
                    Reapplique la version courante du moteur de regles.
                    Si le voyageur existe mais n'a pas encore de profil, celui-ci est cree
                    a la volee a partir de ses donnees (fin du faux 404 sur un voyageur valide).
                    Renvoie le profil recalcule, jamais une reponse vide.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profil recalcule et enregistre"),
            @ApiResponse(responseCode = "404", description = "Voyageur inexistant et sans profil", content = @Content),
            @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role ADMIN manquant", content = @Content)
    })
    @PostMapping("/{travelerId}/recompute")
    public ResponseEntity<TravelerProfile> recompute(
            @Parameter(description = "Identifiant du voyageur", example = "1")
            @PathVariable String travelerId) {
        try {
            return ResponseEntity.ok(profilerService.recompute(travelerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
