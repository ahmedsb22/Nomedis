package com.nomadix.traveler.profiling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Profil calcule par le Smart Profiling Engine.
 * Table dediee (meme base que le service, regle database-per-service respectee).
 */
@Entity
@Table(name = "traveler_profiles")
public class TravelerProfile {

    @Id
    @Column(name = "traveler_id", length = 64)
    private String travelerId;

    @Column(length = 150) private String fullName;
    @Column(length = 150) private String email;
    @Column(length = 40)  private String phone;
    @Column(length = 40)  private String passportNumber;
    @Column(length = 60)  private String nationality;
    @Column(length = 40)  private String birthDate;

    private int score;
    @Column(length = 20)  private String segment;
    private int completeness;
    private boolean validEmail;
    private boolean disposableEmail;
    private boolean validPassport;
    @Column(length = 500) private String riskFlags;

    @Column(length = 10)  private String rulesVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getTravelerId()          { return travelerId; }
    public void setTravelerId(String v)    { this.travelerId = v; }
    public String getFullName()            { return fullName; }
    public void setFullName(String v)      { this.fullName = v; }
    public String getEmail()               { return email; }
    public void setEmail(String v)         { this.email = v; }
    public String getPhone()               { return phone; }
    public void setPhone(String v)         { this.phone = v; }
    public String getPassportNumber()      { return passportNumber; }
    public void setPassportNumber(String v){ this.passportNumber = v; }
    public String getNationality()         { return nationality; }
    public void setNationality(String v)   { this.nationality = v; }
    public String getBirthDate()           { return birthDate; }
    public void setBirthDate(String v)     { this.birthDate = v; }
    public int getScore()                  { return score; }
    public void setScore(int v)            { this.score = v; }
    public String getSegment()             { return segment; }
    public void setSegment(String v)       { this.segment = v; }
    public int getCompleteness()           { return completeness; }
    public void setCompleteness(int v)     { this.completeness = v; }
    public boolean isValidEmail()          { return validEmail; }
    public void setValidEmail(boolean v)   { this.validEmail = v; }
    public boolean isDisposableEmail()     { return disposableEmail; }
    public void setDisposableEmail(boolean v) { this.disposableEmail = v; }
    public boolean isValidPassport()       { return validPassport; }
    public void setValidPassport(boolean v){ this.validPassport = v; }
    public String getRiskFlags()           { return riskFlags; }
    public void setRiskFlags(String v)     { this.riskFlags = v; }
    public String getRulesVersion()        { return rulesVersion; }
    public void setRulesVersion(String v)  { this.rulesVersion = v; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}