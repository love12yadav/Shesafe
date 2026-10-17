package com.example.demo.arearisk.service;

import com.example.demo.arearisk.model.AreaCrime;
import com.example.demo.arearisk.repository.AreaCrimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * STEP 2 – Structured Data Loader.
 *
 * Parses report2.md to extract one row per area paragraph, saves them into
 * the area_crime table, computes the risk level by percentile of crimes_per_km2,
 * and reports duplicates.
 *
 * Field mapping (verified against dataset header nm_pol | murder | rape | gangrape |
 *   robbery | theft | assault | sexual_harassment | tot_area_m2 | total_crimes |
 *   longitude | latitude | crimes_per_km2 | area_km2):
 *
 *   Field 1  = murder
 *   Field 2  = rape
 *   Field 3  = gangrape
 *   Field 4  = robbery
 *   Field 5  = theft
 *   Field 6  = assault
 *   Field 7  = sexual_harassment
 *   Field 8  = tot_area (m²) — stored but not exposed in API
 *   Field 9  = total_crimes
 *   Field 10 = longitude
 *   Field 11 = latitude
 *   Field 12 = crimes_per_km2
 *   Field 13 = area_km2
 */
@Service
public class AreaCrimeLoaderService {

    private static final Logger log = LoggerFactory.getLogger(AreaCrimeLoaderService.class);

    private static final String DATA_FILE = "data/report2.md";

    // Matches: Area: SOME NAME. Field 1: N; Field 2: N; ... Field 13: N.
    private static final Pattern AREA_PATTERN = Pattern.compile(
            "Area:\\s*([^.]+)\\.\\s*" +
            "Field 1:\\s*([\\d.]+);\\s*Field 2:\\s*([\\d.]+);\\s*Field 3:\\s*([\\d.]+);\\s*" +
            "Field 4:\\s*([\\d.]+);\\s*Field 5:\\s*([\\d.]+);\\s*Field 6:\\s*([\\d.]+);\\s*" +
            "Field 7:\\s*([\\d.]+);\\s*Field 8:\\s*([\\d.]+);\\s*Field 9:\\s*([\\d.]+);\\s*" +
            "Field 10:\\s*([\\d.]+);\\s*Field 11:\\s*([\\d.]+);\\s*Field 12:\\s*([\\d.]+);\\s*" +
            "Field 13:\\s*([\\d.]+)\\."
    );

    @Value("${area.risk.threshold.high-percentile:75}")
    private int highPercentile;

    @Value("${area.risk.threshold.safe-percentile:25}")
    private int safePercentile;

    private final AreaCrimeRepository areaCrimeRepository;

    public AreaCrimeLoaderService(AreaCrimeRepository areaCrimeRepository) {
        this.areaCrimeRepository = areaCrimeRepository;
    }

    /**
     * Load structured data. Idempotent: skips if area_crime table already has rows.
     * Returns a summary string for the controller/startup runner.
     */
    @Transactional
    public String loadStructuredData() {
        long existing = areaCrimeRepository.countAll();
        if (existing > 0) {
            log.info("[STEP-2] area_crime table already has {} rows. Skipping load.", existing);
            return "SKIPPED – " + existing + " rows already present.";
        }

        log.info("[STEP-2] Parsing {} ...", DATA_FILE);

        String content = readFile();
        List<AreaCrime> parsed = parseAreaCrimes(content);

        if (parsed.isEmpty()) {
            log.error("[STEP-2] ❌ No area records parsed from {}. Check the file format.", DATA_FILE);
            return "ERROR – No records parsed.";
        }

        // --- Detect duplicates (same area_name) ---
        Map<String, Long> nameCounts = parsed.stream()
                .collect(Collectors.groupingBy(AreaCrime::getAreaName, Collectors.counting()));
        List<String> duplicates = nameCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(e -> e.getKey() + " (" + e.getValue() + " records)")
                .sorted()
                .collect(Collectors.toList());

        if (!duplicates.isEmpty()) {
            log.warn("[STEP-2] ⚠️  Duplicate area names found in report2.md:");
            duplicates.forEach(d -> log.warn("[STEP-2]   → {}", d));
            log.warn("[STEP-2] Strategy: keeping the FIRST occurrence per area name, skipping subsequent ones.");
            // Deduplicate by keeping first occurrence
            Set<String> seen = new LinkedHashSet<>();
            parsed = parsed.stream()
                    .filter(a -> seen.add(a.getAreaName()))
                    .collect(Collectors.toList());
            log.info("[STEP-2] After dedup: {} unique areas.", parsed.size());
        }

        // --- Compute risk level by percentile of crimes_per_km2 ---
        assignLevels(parsed);

        // --- Save all ---
        List<AreaCrime> saved = areaCrimeRepository.saveAll(parsed);
        log.info("[STEP-2] ✅ Saved {} area records to area_crime.", saved.size());

        // --- Print sample rows ---
        printSampleRows(saved);

        String summary = String.format(
                "Loaded %d areas. HIGH=%d MODERATE=%d SAFE=%d. Duplicates found: %s",
                saved.size(),
                saved.stream().filter(a -> "HIGH".equals(a.getLevel())).count(),
                saved.stream().filter(a -> "MODERATE".equals(a.getLevel())).count(),
                saved.stream().filter(a -> "SAFE".equals(a.getLevel())).count(),
                duplicates.isEmpty() ? "none" : String.join(", ", duplicates)
        );
        log.info("[STEP-2] {}", summary);
        return summary;
    }

    /** Assigns HIGH / MODERATE / SAFE levels by percentile of crimes_per_km2. */
    private void assignLevels(List<AreaCrime> areas) {
        List<Double> sorted = areas.stream()
                .mapToDouble(AreaCrime::getCrimesPerKm2)
                .sorted()
                .boxed()
                .collect(Collectors.toList());

        double highThreshold  = percentile(sorted, highPercentile);
        double safeThreshold  = percentile(sorted, safePercentile);

        log.info("[STEP-2] Level thresholds – SAFE ≤ {:.2f}, HIGH > {:.2f} crimes/km²",
                safeThreshold, highThreshold);

        for (AreaCrime area : areas) {
            double d = area.getCrimesPerKm2();
            if (d > highThreshold) {
                area.setLevel("HIGH");
            } else if (d <= safeThreshold) {
                area.setLevel("SAFE");
            } else {
                area.setLevel("MODERATE");
            }
        }
    }

    /** Percentile value from a sorted list. */
    private double percentile(List<Double> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /** Parse all area paragraphs from report2.md content. */
    private List<AreaCrime> parseAreaCrimes(String content) {
        List<AreaCrime> results = new ArrayList<>();
        Matcher m = AREA_PATTERN.matcher(content);

        while (m.find()) {
            try {
                AreaCrime area = new AreaCrime();
                area.setAreaName(m.group(1).trim().toUpperCase());
                // Field 1-7: crime categories
                area.setMurder(parseInt(m.group(2)));           // Field 1
                area.setRape(parseInt(m.group(3)));              // Field 2
                area.setGangrape(parseInt(m.group(4)));          // Field 3
                area.setRobbery(parseInt(m.group(5)));           // Field 4
                area.setTheft(parseInt(m.group(6)));             // Field 5
                area.setAssault(parseInt(m.group(7)));           // Field 6
                area.setSexualHarassment(parseInt(m.group(8))); // Field 7
                // Field 8 = tot_area_m2 (group 9) – stored in m² but entity uses area_km2
                // We skip direct storage of tot_area_m2; area_km2 comes from Field 13.
                area.setTotalCrimes(parseInt(m.group(10)));      // Field 9
                area.setLongitude(parseDouble(m.group(11)));     // Field 10
                area.setLatitude(parseDouble(m.group(12)));      // Field 11
                area.setCrimesPerKm2(parseDouble(m.group(13))); // Field 12
                area.setAreaKm2(parseDouble(m.group(14)));       // Field 13
                results.add(area);
            } catch (NumberFormatException e) {
                log.warn("[STEP-2] Skipping malformed record near: {}... – {}",
                        m.group(0).substring(0, Math.min(60, m.group(0).length())), e.getMessage());
            }
        }
        return results;
    }

    private void printSampleRows(List<AreaCrime> areas) {
        log.info("[STEP-2] ── Sample rows (first 5) ──────────────────────────");
        areas.stream().limit(5).forEach(a ->
                log.info("[STEP-2]  Area={} | Total={} | CrimesPerKm2={:.2f} | Level={} | Lat={} Lng={}",
                        a.getAreaName(), a.getTotalCrimes(), a.getCrimesPerKm2(),
                        a.getLevel(), a.getLatitude(), a.getLongitude()));
        log.info("[STEP-2] ─────────────────────────────────────────────────");
    }

    private String readFile() {
        try {
            ClassPathResource resource = new ClassPathResource(DATA_FILE);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not read " + DATA_FILE, e);
        }
    }

    private int parseInt(String s) {
        return (int) Double.parseDouble(s.trim());
    }

    private double parseDouble(String s) {
        return Double.parseDouble(s.trim());
    }

    /** Re-compute and reassign levels for all stored areas (used after threshold change). */
    @Transactional
    public String recomputeLevels() {
        List<AreaCrime> all = areaCrimeRepository.findAll();
        assignLevels(all);
        areaCrimeRepository.saveAll(all);
        long high = all.stream().filter(a -> "HIGH".equals(a.getLevel())).count();
        long moderate = all.stream().filter(a -> "MODERATE".equals(a.getLevel())).count();
        long safe = all.stream().filter(a -> "SAFE".equals(a.getLevel())).count();
        String msg = String.format("Re-leveled %d areas: HIGH=%d MODERATE=%d SAFE=%d", all.size(), high, moderate, safe);
        log.info("[STEP-2] {}", msg);
        return msg;
    }
}
