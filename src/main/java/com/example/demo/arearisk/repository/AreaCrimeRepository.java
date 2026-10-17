package com.example.demo.arearisk.repository;

import com.example.demo.arearisk.model.AreaCrime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AreaCrimeRepository extends JpaRepository<AreaCrime, Long> {

    /** Exact name lookup (case-insensitive). */
    Optional<AreaCrime> findByAreaNameIgnoreCase(String areaName);

    /** Search areas whose name contains the query (case-insensitive). */
    List<AreaCrime> findByAreaNameContainingIgnoreCase(String query);

    /** Used for percentile computation: all crimes_per_km2 values sorted ascending. */
    @Query("SELECT a.crimesPerKm2 FROM AreaCrime a ORDER BY a.crimesPerKm2 ASC")
    List<Double> findAllCrimesPerKm2Sorted();

    /** Returns areas where areaName is already stored (duplicate check). */
    @Query("SELECT a.areaName FROM AreaCrime a GROUP BY a.areaName HAVING COUNT(a) > 1")
    List<String> findDuplicateAreaNames();

    /** All areas for the map endpoint. */
    @Query("SELECT a FROM AreaCrime a ORDER BY a.areaName ASC")
    List<AreaCrime> findAllOrderedByName();

    /** Count rows that already exist with a given level (for sanity checks). */
    long countByLevel(String level);

    /** Check if area data is already loaded. */
    @Query("SELECT COUNT(a) FROM AreaCrime a")
    long countAll();

    /** Find by level for display. */
    List<AreaCrime> findByLevel(@Param("level") String level);
}
