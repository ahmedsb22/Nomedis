package com.nomadix.traveler.service;

import com.nomadix.traveler.dto.TravelerCreateDTO;
import com.nomadix.traveler.entity.Traveler;
import com.nomadix.traveler.repository.TravelerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Couche metier des voyageurs.
 *
 * <p>Elle isole le controleur du repository et centralise la conversion
 * DTO -&gt; entite. C'est ici que nait l'entite reellement persistee, donc
 * c'est ce {@code save()} qui declenche l'aspect AOP de profiling
 * ({@code TravelerProfilerAspect}) : le score est recalcule a chaque
 * creation comme a chaque mise a jour, sans aucun appel explicite.</p>
 */
@Service
public class TravelerService {

    private static final Logger log = LoggerFactory.getLogger(TravelerService.class);

    private final TravelerRepository travelerRepository;

    public TravelerService(TravelerRepository travelerRepository) {
        this.travelerRepository = travelerRepository;
    }

    @Transactional(readOnly = true)
    public List<Traveler> findAll() {
        List<Traveler> travelers = travelerRepository.findAll();
        log.debug("[TRAVELERS] {} voyageur(s) en base", travelers.size());
        return travelers;
    }

    @Transactional(readOnly = true)
    public Optional<Traveler> findById(Long id) {
        return travelerRepository.findById(id);
    }

    /** Creation : l'id est genere cote service, le role vaut USER par defaut. */
    @Transactional
    public Traveler create(TravelerCreateDTO dto) {
        Traveler traveler = new Traveler();
        apply(traveler, dto);
        if (traveler.getRole() == null || traveler.getRole().isBlank()) {
            traveler.setRole("USER");
        }
        return travelerRepository.save(traveler);
    }

    /**
     * Mise a jour : les vols favoris ne sont jamais touches par ce chemin
     * (ils passent uniquement par les endpoints dedies et OpenFeign).
     */
    @Transactional
    public Optional<Traveler> update(Long id, TravelerCreateDTO dto) {
        return travelerRepository.findById(id).map(existing -> {
            apply(existing, dto);
            return travelerRepository.save(existing);
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (!travelerRepository.existsById(id)) {
            return false;
        }
        travelerRepository.deleteById(id);
        return true;
    }

    /** Conversion DTO -> entite, seul endroit qui decide quels champs sont ecrits. */
    private void apply(Traveler traveler, TravelerCreateDTO dto) {
        traveler.setFirstName(dto.getFirstName());
        traveler.setLastName(dto.getLastName());
        traveler.setEmail(dto.getEmail());
        traveler.setRole(dto.getRole());
        traveler.setPhone(dto.getPhone());
        traveler.setPassportNumber(dto.getPassportNumber());
        traveler.setNationality(dto.getNationality());
        traveler.setDateOfBirth(dto.getDateOfBirth());
    }
}
