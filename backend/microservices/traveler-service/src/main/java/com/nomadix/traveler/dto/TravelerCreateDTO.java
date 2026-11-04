package com.nomadix.traveler.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Corps recu par {@code POST /api/travelers} et {@code PUT /api/travelers/{id}}.
 *
 * <p>Pourquoi un DTO plutot que l'entite directement ? L'entite porte des
 * champs geres par le service ({@code id}, {@code favoriteFlights}) qu'un
 * client ne doit pas pouvoir injecter. Le DTO ne declare que les donnees
 * saisissables, et c'est lui qui est converti en entite dans le service.</p>
 *
 * <p>Seuls {@code firstName}, {@code lastName} et {@code email} sont
 * obligatoires. Les quatre champs suivants alimentent le
 * <b>Smart Profiling Engine</b> : plus ils sont renseignes, plus le score de
 * qualite de profil progresse (jusqu'a 100).</p>
 */
@Schema(name = "TravelerCreateDTO", description = "Donnees de creation / mise a jour d'un voyageur")
public class TravelerCreateDTO {

    @Schema(description = "Prenom", example = "Sami", requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @Schema(description = "Nom", example = "Trabelsi", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @Schema(description = "Adresse e-mail", example = "sami@test.tn", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @Schema(description = "Role metier libre (USER, ADMIN...), sans lien avec les roles Keycloak",
            example = "USER")
    private String role;

    // ---- Champs du profiling (optionnels, mais valorises par le score) ----

    @Schema(description = "Telephone (8 a 15 chiffres, prefixe + accepte)", example = "+216 55 123 456")
    private String phone;

    @Schema(description = "Passeport : 6 a 9 caracteres alphanumeriques", example = "TN1234567")
    private String passportNumber;

    @Schema(description = "Nationalite", example = "Tunisienne")
    private String nationality;

    @Schema(description = "Date de naissance (yyyy-MM-dd)", example = "1998-04-12", type = "string", format = "date")
    private LocalDate dateOfBirth;

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPassportNumber() { return passportNumber; }
    public void setPassportNumber(String passportNumber) { this.passportNumber = passportNumber; }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
}
