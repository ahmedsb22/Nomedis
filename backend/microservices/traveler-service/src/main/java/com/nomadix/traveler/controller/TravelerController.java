package com.nomadix.traveler.controller;

import com.nomadix.traveler.entity.Traveler;
import com.nomadix.traveler.repository.TravelerRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD des voyageurs (base dediee nomadix_traveler_db).
 *
 * <p>Tous les endpoints exigent un jeton Keycloak. La suppression est en plus
 * reservee au role ADMIN (applique ici et par la Gateway).</p>
 */
@RestController
@RequestMapping("/api/travelers")
@Tag(name = "Travelers", description = "CRUD des voyageurs (base dediee nomadix_traveler_db)")
public class TravelerController {

    private final TravelerRepository travelerRepository;

    public TravelerController(TravelerRepository travelerRepository) {
        this.travelerRepository = travelerRepository;
    }

    @Operation(summary = "Lister les voyageurs", description = "Retourne tous les voyageurs (jeton requis).")
    @GetMapping
    public ResponseEntity<List<Traveler>> getAll() {
        List<Traveler> travelers = travelerRepository.findAll();
        System.out.println(">>> PREUVE : NOMBRE DE VOYAGEURS TROUVES EN BASE : " + travelers.size());
        return ResponseEntity.ok(travelers);
    }

    @Operation(summary = "Obtenir un voyageur par son id")
    @ApiResponse(responseCode = "404", description = "Voyageur inexistant")
    @GetMapping("/{id}")
    public ResponseEntity<Traveler> getById(@PathVariable Long id) {
        return travelerRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Creer un voyageur", description = "Retourne 201 avec l'entite creee (id genere).")
    @ApiResponse(responseCode = "201", description = "Voyageur cree")
    @PostMapping
    public ResponseEntity<Traveler> create(@RequestBody Traveler traveler) {
        return ResponseEntity.status(HttpStatus.CREATED).body(travelerRepository.save(traveler));
    }

    @Operation(summary = "Mettre a jour un voyageur", description = "Met a jour l'entite existante (404 si absente).")
    @PutMapping("/{id}")
    public ResponseEntity<Traveler> update(@PathVariable Long id, @RequestBody Traveler travelerDetails) {
        return travelerRepository.findById(id)
                .map(existing -> {
                    existing.setFirstName(travelerDetails.getFirstName());
                    existing.setLastName(travelerDetails.getLastName());
                    existing.setEmail(travelerDetails.getEmail());
                    existing.setRole(travelerDetails.getRole());
                    return ResponseEntity.ok(travelerRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Supprimer un voyageur", description = "Role ADMIN requis.")
    @ApiResponse(responseCode = "204", description = "Voyageur supprime")
    @ApiResponse(responseCode = "404", description = "Voyageur inexistant")
    @ApiResponse(responseCode = "403", description = "Role ADMIN requis")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (travelerRepository.existsById(id)) {
            travelerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
