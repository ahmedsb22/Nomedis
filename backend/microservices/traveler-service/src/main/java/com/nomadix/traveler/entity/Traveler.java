package com.nomadix.traveler.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;

/**
 * Voyageur Nomadix (base dediee nomadix_traveler_db).
 *
 * <p>Les annotations {@code @Schema} rendent le corps de POST /api/travelers
 * lisible dans Swagger UI : format attendu, exemple pret a coller et champs
 * obligatoires. Le champ {@code role} est un role metier libre, distinct des
 * roles Keycloak ADMIN / TRAVELER.</p>
 */
@Entity
@Table(name = "travelers")
@Schema(name = "Traveler", description = "Voyageur et ses vols favoris")
public class Traveler {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Identifiant genere par le service (ignorer en creation)",
            example = "1", accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Schema(description = "Prenom", example = "Sami", requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @Schema(description = "Nom", example = "Trabelsi", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @Schema(description = "Adresse e-mail du voyageur", example = "sami@test.tn",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @Schema(description = "Role metier libre (USER, ADMIN...), sans lien avec les roles Keycloak",
            example = "USER")
    private String role;

    /** Seuls les identifiants de vols sont stockes : le detail vient de Feign. */
    @ElementCollection
    @Schema(description = "Identifiants des vols favoris (le detail est enrichi par OpenFeign)",
            accessMode = Schema.AccessMode.READ_ONLY)
    private java.util.Set<Long> favoriteFlights = new java.util.HashSet<>();

    // Getters et Setters indispensables
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public java.util.Set<Long> getFavoriteFlights() { return this.favoriteFlights; }
    public void setFavoriteFlights(java.util.Set<Long> favoriteFlights) { this.favoriteFlights = favoriteFlights; }
}
