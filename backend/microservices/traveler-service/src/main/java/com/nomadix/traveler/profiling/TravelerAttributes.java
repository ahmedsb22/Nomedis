package com.nomadix.traveler.profiling;

/**
 * Attributs extraits d'un voyageur, en entree du moteur de regles.
 */
public class TravelerAttributes {

    private String travelerId;
    private String fullName;
    private String email;
    private String phone;
    private String passportNumber;
    private String nationality;
    private String birthDate;

    public String getTravelerId()     { return travelerId; }
    public void setTravelerId(String v)      { this.travelerId = v; }
    public String getFullName()       { return fullName; }
    public void setFullName(String v)        { this.fullName = v; }
    public String getEmail()          { return email; }
    public void setEmail(String v)           { this.email = v; }
    public String getPhone()          { return phone; }
    public void setPhone(String v)           { this.phone = v; }
    public String getPassportNumber() { return passportNumber; }
    public void setPassportNumber(String v)  { this.passportNumber = v; }
    public String getNationality()    { return nationality; }
    public void setNationality(String v)     { this.nationality = v; }
    public String getBirthDate()      { return birthDate; }
    public void setBirthDate(String v)       { this.birthDate = v; }

    public boolean filled(String s) { return s != null && !s.trim().isEmpty(); }
}