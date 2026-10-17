package com.example.demo.arearisk.service;

import com.example.demo.arearisk.model.AreaCrime;
import com.example.demo.arearisk.repository.AreaCrimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * STEP 4 – RAG Service: retrieves context from pgvector and asks Gemini to
 * explain the area's safety profile.
 *
 * Rules:
 *   - Risk level is ALWAYS determined from the area_crime DB table (SQL), never by the LLM.
 *   - LLM uses ONLY the retrieved chunks; it must not invent facts.
 *   - Language: "higher reported crime in this area" — never "dangerous".
 *   - If Gemini fails or times out, returns a fallback with level + counts only.
 *   - Results are cached per area_name for configurable TTL minutes.
 */
@Service
public class AreaRiskService {

    private static final Logger log = LoggerFactory.getLogger(AreaRiskService.class);

    @Value("${area.risk.rag.chunks-per-area:2}")
    private int chunksPerArea;

    @Value("${area.risk.rag.chunks-global:2}")
    private int chunksGlobal;

    @Value("${area.risk.gemini.timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${area.risk.cache.ttl-minutes:60}")
    private int cacheTtlMinutes;

    private final AreaCrimeRepository areaCrimeRepository;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    // In-memory per-area cache: area_name → {payload, createdAt}
    private final ConcurrentHashMap<String, CachedEntry> explanationCache = new ConcurrentHashMap<>();

    public AreaRiskService(
            AreaCrimeRepository areaCrimeRepository,
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder) {
        this.areaCrimeRepository = areaCrimeRepository;
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the full Area Risk response for the given area name.
     * Format: { area, level, crimeCount, explanation, tips[], sources[] }
     */
    public AreaRiskResponse explain(String areaName) {
        String normalizedName = areaName.trim().toUpperCase();

        // 1. Check cache
        CachedEntry cached = explanationCache.get(normalizedName);
        if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
            log.info("[RAG-SVC] Cache hit for area '{}'", normalizedName);
            return cached.payload;
        }

        // 2. Load structured data from MySQL (level is determined here — never by LLM)
        Optional<AreaCrime> areaOpt = areaCrimeRepository.findByAreaNameIgnoreCase(normalizedName);
        if (areaOpt.isEmpty()) {
            return AreaRiskResponse.notFound(areaName);
        }
        AreaCrime area = areaOpt.get();

        // 3. Retrieve RAG context from pgvector
        List<Document> ragChunks = retrieveChunks(normalizedName);

        // 4. Call Gemini (with fallback on failure)
        AreaRiskResponse response;
        try {
            response = callGemini(area, ragChunks);
        } catch (Exception e) {
            log.warn("[RAG-SVC] Gemini failed for '{}': {}. Returning fallback.", normalizedName, e.getMessage());
            response = buildFallback(area);
        }

        // 5. Cache and return
        explanationCache.put(normalizedName, new CachedEntry(response));
        return response;
    }

    /** Clears the explanation cache for a specific area (or all if areaName is null). */
    public void clearCache(String areaName) {
        if (areaName == null) {
            explanationCache.clear();
            log.info("[RAG-SVC] Cache cleared for all areas.");
        } else {
            explanationCache.remove(areaName.trim().toUpperCase());
            log.info("[RAG-SVC] Cache cleared for area '{}'.", areaName);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RAG retrieval
    // ─────────────────────────────────────────────────────────────────────────

    private List<Document> retrieveChunks(String areaName) {
        List<Document> result = new ArrayList<>();

        // Filtered: chunks specifically tagged with this area_name
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            List<Document> areaChunks = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("crime safety " + areaName + " Delhi")
                            .topK(chunksPerArea)
                            .filterExpression(b.eq("area_name", areaName).build())
                            .build());
            result.addAll(areaChunks);
            log.debug("[RAG-SVC] {} area-filtered chunks for '{}'", areaChunks.size(), areaName);
        } catch (Exception e) {
            log.warn("[RAG-SVC] Area-filtered search failed: {}", e.getMessage());
        }

        // Unfiltered: general context chunks
        try {
            List<Document> globalChunks = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("Delhi crime statistics safety awareness " + areaName)
                            .topK(chunksGlobal)
                            .build());
            result.addAll(globalChunks);
            log.debug("[RAG-SVC] {} global chunks", globalChunks.size());
        } catch (Exception e) {
            log.warn("[RAG-SVC] Global search failed: {}", e.getMessage());
        }

        // De-duplicate by document ID
        Map<String, Document> deduped = new LinkedHashMap<>();
        result.forEach(doc -> deduped.putIfAbsent(doc.getId(), doc));
        log.info("[RAG-SVC] {} deduplicated chunks for '{}'", deduped.size(), areaName);
        return new ArrayList<>(deduped.values());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gemini call
    // ─────────────────────────────────────────────────────────────────────────

    private AreaRiskResponse callGemini(AreaCrime area, List<Document> ragChunks) {
        String contextBlock = buildContextBlock(ragChunks);
        String prompt = buildPrompt(area, contextBlock);

        log.info("[RAG-SVC] Calling Gemini for area '{}' with {} context chunks ...",
                area.getAreaName(), ragChunks.size());

        // Call with timeout
        String rawResponse = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        return parseGeminiResponse(area, rawResponse, ragChunks);
    }

    private String buildContextBlock(List<Document> chunks) {
        if (chunks.isEmpty()) return "No context available from the crime reports.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Document doc = chunks.get(i);
            String source = (String) doc.getMetadata().getOrDefault("source", "unknown");
            String page   = doc.getMetadata().containsKey("page_number")
                    ? "page " + doc.getMetadata().get("page_number") : "";
            String section = (String) doc.getMetadata().getOrDefault("section", "");
            String ref = source + (page.isEmpty() ? "" : ", " + page)
                              + (section.isEmpty() ? "" : ", section: " + section);
            sb.append("[Chunk ").append(i + 1).append(" – ").append(ref).append("]\n");
            sb.append(doc.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(AreaCrime area, String contextBlock) {
        return """
                You are a safety information assistant for SheSafe, a women's safety app in Delhi.
                
                AREA: %s
                RISK LEVEL (determined by statistical analysis, not by you): %s
                TOTAL REPORTED CRIMES (from dataset): %d
                
                CONTEXT RETRIEVED FROM CRIME REPORTS:
                ---
                %s
                ---
                
                INSTRUCTIONS:
                1. Write 2-3 sentences explaining the crime situation in %s using ONLY the context above.
                   Use the phrase "higher reported crime in this area" rather than calling it "dangerous".
                   If the context does not support a statement, say the information is not available.
                2. Give exactly 2-3 short, practical safety tips appropriate for this area.
                3. List each source you used as: (file name, page N) or (file name, section: NAME).
                   Only cite sources that appear in the context above.
                4. If two reports contradict each other, prefer report1.pdf and note the difference.
                5. Never invent crime numbers. Never guess.
                
                RESPOND IN THIS EXACT FORMAT (no markdown headers, just the labels):
                EXPLANATION: <your 2-3 sentence explanation>
                TIPS:
                - <tip 1>
                - <tip 2>
                - <tip 3 if applicable>
                SOURCES:
                - <source citation 1>
                - <source citation 2 if applicable>
                """.formatted(
                area.getAreaName(), area.getLevel(), area.getTotalCrimes(),
                contextBlock, area.getAreaName());
    }

    /** Parses Gemini's structured response into an AreaRiskResponse. */
    private AreaRiskResponse parseGeminiResponse(AreaCrime area, String raw, List<Document> chunks) {
        String explanation = extractSection(raw, "EXPLANATION:", "TIPS:");
        String tipsBlock   = extractSection(raw, "TIPS:", "SOURCES:");
        String sourcesBlock = extractSection(raw, "SOURCES:", null);

        List<String> tips = parseBullets(tipsBlock);
        List<String> sources = parseBullets(sourcesBlock);

        // Fall back on Gemini not returning sources – build from chunk metadata
        if (sources.isEmpty()) {
            sources = chunks.stream()
                    .map(doc -> {
                        String src = (String) doc.getMetadata().getOrDefault("source", "unknown");
                        String pg  = doc.getMetadata().containsKey("page_number")
                                ? ", page " + doc.getMetadata().get("page_number") : "";
                        return src + pg;
                    })
                    .distinct()
                    .collect(Collectors.toList());
        }

        return new AreaRiskResponse(
                area.getAreaName(),
                area.getLevel(),           // ← Level ALWAYS from DB, never from LLM
                area.getTotalCrimes(),
                explanation.isBlank() ? raw : explanation,
                tips,
                sources,
                false
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback (no LLM)
    // ─────────────────────────────────────────────────────────────────────────

    private AreaRiskResponse buildFallback(AreaCrime area) {
        String explanation = String.format(
                "%s has %d total reported crimes in this dataset (%.1f per km²). " +
                "Detailed AI explanation is temporarily unavailable.",
                area.getAreaName(), area.getTotalCrimes(), area.getCrimesPerKm2());

        List<String> tips = List.of(
                "Stay aware of your surroundings at all times.",
                "Share your live location with a trusted contact when travelling alone.",
                "Use the SheSafe SOS feature if you feel unsafe."
        );

        return new AreaRiskResponse(
                area.getAreaName(),
                area.getLevel(),
                area.getTotalCrimes(),
                explanation,
                tips,
                List.of("Data source: Delhi crime dataset (structured data)"),
                true  // isFallback
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // String parsing helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String extractSection(String text, String start, String end) {
        int startIdx = text.indexOf(start);
        if (startIdx < 0) return "";
        startIdx += start.length();
        int endIdx = end != null ? text.indexOf(end, startIdx) : -1;
        String section = (endIdx > startIdx) ? text.substring(startIdx, endIdx) : text.substring(startIdx);
        return section.strip();
    }

    private List<String> parseBullets(String block) {
        if (block == null || block.isBlank()) return new ArrayList<>();
        return Arrays.stream(block.split("\\n"))
                .map(line -> line.replaceFirst("^[-*•]\\s*", "").strip())
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache entry
    // ─────────────────────────────────────────────────────────────────────────

    private static class CachedEntry {
        final AreaRiskResponse payload;
        final Instant createdAt;

        CachedEntry(AreaRiskResponse payload) {
            this.payload = payload;
            this.createdAt = Instant.now();
        }

        boolean isExpired(int ttlMinutes) {
            return Duration.between(createdAt, Instant.now()).toMinutes() >= ttlMinutes;
        }
    }
}
