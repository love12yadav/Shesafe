package com.example.demo.arearisk.model;

import jakarta.persistence.*;

/**
 * Represents one row of structured crime data per Delhi police area.
 * Data source: report2.md (Fields mapped to real column names from the dataset header).
 *
 * Column name mapping from dataset:
 *   nm_pol (area_name), murder, rape, gangrape, robbery, theft,
 *   assault, sexual_harassment, tot_area_m2, total_crimes,
 *   longitude, latitude, crimes_per_km2, area_km2
 */
@Entity
@Table(name = "area_crime", indexes = {
        @Index(name = "idx_area_crime_name", columnList = "area_name")
})
public class AreaCrime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "area_name", nullable = false, length = 120)
    private String areaName;

    // Crime counts by category (Fields 1-7)
    @Column(name = "murder")
    private int murder;

    @Column(name = "rape")
    private int rape;

    @Column(name = "gangrape")
    private int gangrape;

    @Column(name = "robbery")
    private int robbery;

    @Column(name = "theft")
    private int theft;

    @Column(name = "assault")
    private int assault;

    @Column(name = "sexual_harassment")
    private int sexualHarassment;

    // Aggregate / geo fields
    @Column(name = "total_crimes")
    private int totalCrimes;

    @Column(name = "longitude")
    private double longitude;

    @Column(name = "latitude")
    private double latitude;

    @Column(name = "crimes_per_km2")
    private double crimesPerKm2;

    @Column(name = "area_km2")
    private double areaKm2;

    /**
     * Risk level: HIGH, MODERATE, SAFE.
     * Computed by percentile of crimes_per_km2 — NEVER by the LLM.
     */
    @Column(name = "level", length = 10)
    private String level;

    // ── Getters & Setters ────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAreaName() { return areaName; }
    public void setAreaName(String areaName) { this.areaName = areaName; }

    public int getMurder() { return murder; }
    public void setMurder(int murder) { this.murder = murder; }

    public int getRape() { return rape; }
    public void setRape(int rape) { this.rape = rape; }

    public int getGangrape() { return gangrape; }
    public void setGangrape(int gangrape) { this.gangrape = gangrape; }

    public int getRobbery() { return robbery; }
    public void setRobbery(int robbery) { this.robbery = robbery; }

    public int getTheft() { return theft; }
    public void setTheft(int theft) { this.theft = theft; }

    public int getAssault() { return assault; }
    public void setAssault(int assault) { this.assault = assault; }

    public int getSexualHarassment() { return sexualHarassment; }
    public void setSexualHarassment(int sexualHarassment) { this.sexualHarassment = sexualHarassment; }

    public int getTotalCrimes() { return totalCrimes; }
    public void setTotalCrimes(int totalCrimes) { this.totalCrimes = totalCrimes; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getCrimesPerKm2() { return crimesPerKm2; }
    public void setCrimesPerKm2(double crimesPerKm2) { this.crimesPerKm2 = crimesPerKm2; }

    public double getAreaKm2() { return areaKm2; }
    public void setAreaKm2(double areaKm2) { this.areaKm2 = areaKm2; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
}
