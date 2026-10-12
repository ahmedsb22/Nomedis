package com.nomadix.traveler.profiling;

import java.util.ArrayList;
import java.util.List;

/**
 * Resultat produit par le moteur de regles de profiling.
 */
public class ProfileResult {

    private int score;              // 0 a 100
    private String segment;         // PLATINUM / GOLD / SILVER / BRONZE
    private int completeness;       // 0 a 100 (completude du profil)
    private boolean validEmail;
    private boolean disposableEmail;
    private boolean validPassport;
    private final List<String> riskFlags = new ArrayList<>();

    public int getScore()                   { return score; }
    public void setScore(int v)             { this.score = v; }
    public String getSegment()              { return segment; }
    public void setSegment(String v)        { this.segment = v; }
    public int getCompleteness()            { return completeness; }
    public void setCompleteness(int v)      { this.completeness = v; }
    public boolean isValidEmail()           { return validEmail; }
    public void setValidEmail(boolean v)    { this.validEmail = v; }
    public boolean isDisposableEmail()      { return disposableEmail; }
    public void setDisposableEmail(boolean v) { this.disposableEmail = v; }
    public boolean isValidPassport()        { return validPassport; }
    public void setValidPassport(boolean v) { this.validPassport = v; }
    public List<String> getRiskFlags()      { return riskFlags; }
    public void addRiskFlag(String flag)    { this.riskFlags.add(flag); }
}