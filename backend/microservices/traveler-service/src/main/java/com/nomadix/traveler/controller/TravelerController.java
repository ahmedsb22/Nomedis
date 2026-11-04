package com.nomadix.traveler.controller;

import com.nomadix.traveler.dto.TravelerCreateDTO;
import com.nomadix.traveler.entity.Traveler;
import com.nomadix.traveler.service.TravelerService;
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
 *
 * <p>Le corps des ecritures est un {@link TravelerCreateDTO} : il contient les
 * quatre champs du <b>Smart Profiling Engine</b> (telephone, passeport,
 * nationalite, date de naissance) en plus de l'identite. Le profiling est
 * declenche automatiquement par l'aspect AOP apres la sauvegarde.</p>
 */
@RestController
@RequestMapping("/api/travelers")
@Tag(name = "Travelers", description = "CRUD des voyageurs (base dediee nomadix_traveler_db)")
public class TravelerController {

    private final TravelerService travelerService;

    public TravelerController(TravelerService travelerService) {
        this.travelerService = travelerService;
    }

    @Operation(summary = "Lister les voyageurs", description = "Retourne tous les voyageurs (jeton requis).")
    @GetMapping
    public ResponseEntity<List<Traveler>> getAll() {
        return ResponseEntity.ok(travelerService.findAll());
    }

    @Operation(summary = "Obtenir un voyageur par son id")
    @ApiResponse(responseCode = "404", description = "Voyageur inexistant")
    @GetMapping("/{id}")
    public ResponseEntity<Traveler> getById(@PathVariable Long id) {
        return travelerService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Creer un voyageur",
            description = "Retourne 201 avec l'entite creee (id genere). Un profil est calcule "
                    + "automatiquement par l'aspect AOP : consultez /api/travelers/profiles/{id}.")
    @ApiResponse(responseCode = "201", description = "Voyageur cree")
    @PostMapping
    public ResponseEntity<Traveler> create(@RequestBody TravelerCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(travelerService.create(dto));
    }

    @Operation(
            summary = "Mettre a jour un voyageur",
            description = "Met a jour l'entite existante (404 si absente). Le profil est recalcule "
                    + "automatiquement, ce qui permet de voir le score progresser quand on complete "
                    + "le passeport ou le telephone.")
    @ApiResponse(responseCode = "404", description = "Voyageur inexistant")
    @PutMapping("/{id}")
    public ResponseEntity<Traveler> update(@PathVariable Long id, @RequestBody TravelerCreateDTO dto) {
        return travelerService.update(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Supprimer un voyageur", description = "Role ADMIN requis. Le profil associe est supprime par l'aspect AOP.")
    @ApiResponse(responseCode = "204", description = "Voyageur supprime")
    @ApiResponse(responseCode = "404", description = "Voyageur inexistant")
    @ApiResponse(responseCode = "403", description = "Role ADMIN requis")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return travelerService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
