package com.nomadix.traveler.controller;

import com.nomadix.traveler.entity.Traveler;
import com.nomadix.traveler.repository.TravelerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/travelers")
public class TravelerController {

    private final TravelerRepository travelerRepository;

    public TravelerController(TravelerRepository travelerRepository) {
        this.travelerRepository = travelerRepository;
    }

    @GetMapping
    public ResponseEntity<List<Traveler>> getAll() {
        List<Traveler> travelers = travelerRepository.findAll();
        System.out.println(">>> 🚨 PREUVE : NOMBRE DE VOYAGEURS TROUVÉS EN BASE : " + travelers.size());
        return ResponseEntity.ok(travelers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Traveler> getById(@PathVariable Long id) {
        return travelerRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Traveler> create(@RequestBody Traveler traveler) {
        return ResponseEntity.status(HttpStatus.CREATED).body(travelerRepository.save(traveler));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Traveler> update(@PathVariable Long id, @RequestBody Traveler travelerDetails) {
        return travelerRepository.findById(id)
                .map(existing -> {
                    existing.setFirstName(travelerDetails.getFirstName());
                    existing.setLastName(travelerDetails.getLastName());
                    existing.setEmail(travelerDetails.getEmail());
                    existing.setRole(travelerDetails.getRole()); // Cette ligne fonctionne maintenant !
                    return ResponseEntity.ok(travelerRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (travelerRepository.existsById(id)) {
            travelerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}