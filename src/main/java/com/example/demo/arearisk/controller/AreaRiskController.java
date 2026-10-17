package com.example.demo.arearisk.controller;

import com.example.demo.arearisk.model.AreaCrime;
import com.example.demo.arearisk.repository.AreaCrimeRepository;
import com.example.demo.arearisk.service.AreaCrimeLoaderService;
import com.example.demo.arearisk.service.AreaRiskResponse;
import com.example.demo.arearisk.service.AreaRiskService;
import com.example.demo.arearisk.service.RagIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Area Risk REST API – all endpoints require authentication (JWT).
 *
 * Endpoints:
 *   GET  /area-risk/areas              – list all areas with level, lat, lng, total
 *   GET  /area-risk/areas/search?q=   – search areas by name substring
 *   GET  /area-risk/areas/{name}       – get a single area's structured data
 *   GET  /area-risk/explain/{name}     – full RAG explanation (AI + tips + sources)
 *   POST /area-risk/admin/ingest       – trigger RAG ingestion (authenticated users)
 *   POST /area-risk/admin/load-data    – trigger Step 2 structured data load
 *   POST /area-risk/admin/cache/clear  – clear the explanation cache
 *
 * Note: All endpoints are behind existing JWT auth (anyRequest().authenticated()
 *       in SecurityConfig). No separate ADMIN role is used per user decision.
 */
@RestController
@RequestMapping("/area-risk")
@CrossOrigin(origins = "http://localhost:5173")
public class AreaRiskController {

    private static final Logger log = LoggerFactory.getLogger(AreaRiskController.class);

    private final AreaCrimeRepository areaCrimeRepository;
    private final AreaRiskService areaRiskService;
    private final AreaCrimeLoaderService areaLoaderService;
    private final RagIngestionService ragIngestionService;

    public AreaRiskController(
            AreaCrimeRepository areaCrimeRepository,
            AreaRiskService areaRiskService,
            AreaCrimeLoaderService areaLoaderService,
            RagIngestionService ragIngestionService) {
        this.areaCrimeRepository = areaCrimeRepository;
        this.areaRiskService     = areaRiskService;
        this.areaLoaderService   = areaLoaderService;
        this.ragIngestionService = ragIngestionService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /area-risk/areas
    // Returns all areas with lat, lng, level, total_crimes for the map.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/areas")
    public ResponseEntity<List<Map<String, Object>>> listAreas() {
        List<Map<String, Object>> areas = areaCrimeRepository.findAllOrderedByName()
                .stream()
                .map(this::toSummaryMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(areas);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /area-risk/areas/search?q=xxx
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/areas/search")
    public ResponseEntity<List<Map<String, Object>>> searchAreas(
            @RequestParam("q") String query) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<Map<String, Object>> results = areaCrimeRepository
                .findByAreaNameContainingIgnoreCase(query.trim())
                .stream()
                .map(this::toSummaryMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(results);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /area-risk/areas/{name}
    // Single area's full structured data (no AI explanation).
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/areas/{name}")
    public ResponseEntity<?> getArea(@PathVariable("name") String name) {
        Optional<AreaCrime> opt = areaCrimeRepository.findByAreaNameIgnoreCase(name.trim().toUpperCase());
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDetailMap(opt.get()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /area-risk/explain/{name}
    // Full RAG explanation: level (SQL) + AI tips + sources.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/explain/{name}")
    public ResponseEntity<AreaRiskResponse> explainArea(@PathVariable("name") String name) {
        log.info("[API] Explain request for area '{}'", name);
        AreaRiskResponse response = areaRiskService.explain(name);
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /area-risk/admin/load-data
    // Loads structured data (Step 2). Idempotent.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/admin/load-data")
    public ResponseEntity<Map<String, String>> loadData() {
        log.info("[API] Admin: triggering structured data load (Step 2).");
        String result = areaLoaderService.loadStructuredData();
        return ResponseEntity.ok(Map.of("status", result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /area-risk/admin/ingest
    // Triggers RAG ingestion (Step 3). Idempotent per source.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/admin/ingest")
    public ResponseEntity<Map<String, String>> triggerIngest() {
        log.info("[API] Admin: triggering RAG ingestion (Step 3).");
        Map<String, String> results = ragIngestionService.ingestAll();
        return ResponseEntity.ok(results);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /area-risk/admin/cache/clear
    // Clears cached explanations.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/admin/cache/clear")
    public ResponseEntity<Map<String, String>> clearCache(
            @RequestParam(value = "area", required = false) String area) {
        areaRiskService.clearCache(area);
        String msg = area != null ? "Cache cleared for area: " + area : "All area explanation cache cleared.";
        return ResponseEntity.ok(Map.of("status", msg));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private DTO builders
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> toSummaryMap(AreaCrime a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("area",        a.getAreaName());
        map.put("level",       a.getLevel());
        map.put("totalCrimes", a.getTotalCrimes());
        map.put("latitude",    a.getLatitude());
        map.put("longitude",   a.getLongitude());
        map.put("areaKm2",     a.getAreaKm2());
        map.put("crimesPerKm2", a.getCrimesPerKm2());
        return map;
    }

    private Map<String, Object> toDetailMap(AreaCrime a) {
        Map<String, Object> map = toSummaryMap(a);
        map.put("murder",           a.getMurder());
        map.put("rape",             a.getRape());
        map.put("gangrape",         a.getGangrape());
        map.put("robbery",          a.getRobbery());
        map.put("theft",            a.getTheft());
        map.put("assault",          a.getAssault());
        map.put("sexualHarassment", a.getSexualHarassment());
        return map;
    }
}
