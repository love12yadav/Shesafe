package com.example.demo.arearisk.service;

import com.example.demo.arearisk.model.AreaCrime;
import com.example.demo.arearisk.repository.AreaCrimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * STEP 3 – RAG Ingestion Service.
 *
 * Ingests three source types into the pgvector vector_store table:
 *   1. report1.pdf  – page-by-page, TokenTextSplitter (400 tok / 50 overlap)
 *   2. report2.md   – one chunk per area paragraph (Area: NAME. Field…)
 *   3. Excel/MD summary – one sentence per area from structured data,
 *                         metadata: source="excel", area_name
 *
 * Idempotent: skips any source file whose documents are already stored.
 * Uses @Retryable with exponential backoff to handle Gemini 429 rate limits.
 */
@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    private static final String PDF_SOURCE  = "report1.pdf";
    private static final String MD_SOURCE   = "report2.md";
    private static final String EXCEL_SOURCE = "excel";

    // Tracks which sources have already been ingested
    private static final String INGESTION_CHECK_SQL =
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'source' = ?";

    private static final Pattern AREA_PARA_PATTERN = Pattern.compile(
            "Area:\\s*([^.]+)\\.\\s*Field 1:\\s*([\\d.]+);.*?Field 13:\\s*([\\d.]+)\\.",
            Pattern.DOTALL);

    private final VectorStore vectorStore;
    private final AreaCrimeRepository areaCrimeRepository;
    private final JdbcTemplate pgvectorJdbcTemplate;

    public RagIngestionService(
            VectorStore vectorStore,
            AreaCrimeRepository areaCrimeRepository,
            @Qualifier("pgvectorJdbcTemplate") JdbcTemplate pgvectorJdbcTemplate) {
        this.vectorStore = vectorStore;
        this.areaCrimeRepository = areaCrimeRepository;
        this.pgvectorJdbcTemplate = pgvectorJdbcTemplate;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public entrypoint
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ingests all sources. Returns a map of source → status message.
     */
    public Map<String, String> ingestAll() {
        Map<String, String> results = new LinkedHashMap<>();
        results.put(PDF_SOURCE,   ingestPdf());
        results.put(MD_SOURCE,    ingestMarkdown());
        results.put(EXCEL_SOURCE, ingestExcelSummaries());
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Source 1: report1.pdf – page-by-page
    // ─────────────────────────────────────────────────────────────────────────

    private String ingestPdf() {
        if (alreadyIngested(PDF_SOURCE)) {
            log.info("[RAG] {} already ingested. Skipping.", PDF_SOURCE);
            return "SKIPPED – already ingested";
        }

        log.info("[RAG] Ingesting {} ...", PDF_SOURCE);
        try {
            ClassPathResource pdfResource = new ClassPathResource("data/" + PDF_SOURCE);

            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                    pdfResource,
                    PdfDocumentReaderConfig.builder()
                            .withPagesPerDocument(1)   // one Document per page
                            .build());

            List<Document> rawPages = pdfReader.get();
            log.info("[RAG]   {} has {} pages.", PDF_SOURCE, rawPages.size());

            // Tag each page with source metadata
            List<Document> taggedPages = rawPages.stream()
                    .map(doc -> {
                        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                        meta.put("source", PDF_SOURCE);
                        // PagePdfDocumentReader sets page_number in metadata
                        return new Document(doc.getText(), meta);
                    })
                    .collect(Collectors.toList());

            // TokenTextSplitter: ~400 tokens, ~50 overlap
            TokenTextSplitter splitter = new TokenTextSplitter(400, 50, 5, 10000, true);
            List<Document> chunks = splitter.apply(taggedPages);

            log.info("[RAG]   {} → {} chunks after splitting.", PDF_SOURCE, chunks.size());
            embedAndStore(chunks, PDF_SOURCE);
            printSampleChunks(chunks, PDF_SOURCE, 3);

            return "OK – " + chunks.size() + " chunks ingested from " + rawPages.size() + " pages";
        } catch (Exception e) {
            log.error("[RAG] ❌ PDF ingestion failed: {}", e.getMessage(), e);
            return "ERROR – " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Source 2: report2.md – one chunk per area paragraph
    // ─────────────────────────────────────────────────────────────────────────

    private String ingestMarkdown() {
        if (alreadyIngested(MD_SOURCE)) {
            log.info("[RAG] {} already ingested. Skipping.", MD_SOURCE);
            return "SKIPPED – already ingested";
        }

        log.info("[RAG] Ingesting {} ...", MD_SOURCE);
        try {
            String content = readClasspathFile("data/" + MD_SOURCE);
            List<Document> chunks = new ArrayList<>();

            Matcher m = AREA_PARA_PATTERN.matcher(content);
            while (m.find()) {
                String areaName = m.group(1).trim().toUpperCase();
                String paragraph = m.group(0).trim();

                // Sub-split only if paragraph exceeds ~600 tokens (very unlikely here)
                if (paragraph.length() > 2400) {  // ~4 chars per token rough estimate
                    TokenTextSplitter splitter = new TokenTextSplitter(600, 60, 5, 10000, true);
                    Document bigDoc = new Document(paragraph, Map.of(
                            "source", MD_SOURCE,
                            "area_name", areaName,
                            "section", "area-" + areaName));
                    chunks.addAll(splitter.apply(List.of(bigDoc)));
                } else {
                    chunks.add(new Document(paragraph, Map.of(
                            "source", MD_SOURCE,
                            "area_name", areaName,
                            "section", "area-" + areaName)));
                }
            }

            log.info("[RAG]   {} → {} area chunks.", MD_SOURCE, chunks.size());
            embedAndStore(chunks, MD_SOURCE);
            printSampleChunks(chunks, MD_SOURCE, 3);

            return "OK – " + chunks.size() + " area chunks ingested";
        } catch (Exception e) {
            log.error("[RAG] ❌ Markdown ingestion failed: {}", e.getMessage(), e);
            return "ERROR – " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Source 3: One short sentence per area from structured DB data
    // ─────────────────────────────────────────────────────────────────────────

    private String ingestExcelSummaries() {
        if (alreadyIngested(EXCEL_SOURCE)) {
            log.info("[RAG] {} summaries already ingested. Skipping.", EXCEL_SOURCE);
            return "SKIPPED – already ingested";
        }

        List<AreaCrime> areas = areaCrimeRepository.findAllOrderedByName();
        if (areas.isEmpty()) {
            return "SKIPPED – area_crime table is empty. Run Step 2 first.";
        }

        log.info("[RAG] Generating {} area summary sentences ...", areas.size());
        List<Document> summaries = areas.stream()
                .map(this::buildSummarySentence)
                .collect(Collectors.toList());

        embedAndStore(summaries, EXCEL_SOURCE);
        printSampleChunks(summaries, EXCEL_SOURCE, 3);

        return "OK – " + summaries.size() + " area summaries ingested";
    }

    /** Builds a short, factual sentence for one area from its DB row.
     *  Uses real category names; never invents numbers. */
    private Document buildSummarySentence(AreaCrime area) {
        // Rank the 7 categories by count, pick top 2
        Map<String, Integer> cats = new LinkedHashMap<>();
        cats.put("murder",             area.getMurder());
        cats.put("rape",               area.getRape());
        cats.put("gangrape",           area.getGangrape());
        cats.put("robbery",            area.getRobbery());
        cats.put("theft",              area.getTheft());
        cats.put("assault",            area.getAssault());
        cats.put("sexual harassment",  area.getSexualHarassment());

        List<String> top2 = cats.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(2)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.toList());

        String sentence = String.format(
                "%s reported %d total crimes in the period covered by this dataset. " +
                "The two most reported categories were %s and %s. " +
                "Crime density: %.1f crimes per km\u00b2 (area %.2f km\u00b2). Risk level: %s.",
                area.getAreaName(),
                area.getTotalCrimes(),
                top2.get(0),
                top2.size() > 1 ? top2.get(1) : "—",
                area.getCrimesPerKm2(),
                area.getAreaKm2(),
                area.getLevel() != null ? area.getLevel() : "unclassified"
        );

        return new Document(sentence, Map.of(
                "source",    EXCEL_SOURCE,
                "area_name", area.getAreaName()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Embeds and stores documents in batches of 20 with retry on 429. */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 2000, multiplier = 2.5, maxDelay = 30000))
    private void embedAndStore(List<Document> docs, String sourceLabel) {
        int batchSize = 20;
        int total = docs.size();
        for (int i = 0; i < total; i += batchSize) {
            List<Document> batch = docs.subList(i, Math.min(i + batchSize, total));
            vectorStore.add(batch);
            log.info("[RAG]   [{}] Stored batch {}/{} ({} docs)",
                    sourceLabel, (i / batchSize) + 1, (int) Math.ceil((double) total / batchSize), batch.size());
            // Small pause between batches to stay within rate limits
            if (i + batchSize < total) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /** Check if a source is already stored in pgvector. */
    private boolean alreadyIngested(String source) {
        try {
            Integer count = pgvectorJdbcTemplate.queryForObject(
                    INGESTION_CHECK_SQL, Integer.class, source);
            return count != null && count > 0;
        } catch (Exception e) {
            // Table may not exist yet on first run
            log.debug("[RAG] Could not check ingestion status for {}: {}", source, e.getMessage());
            return false;
        }
    }

    /** Print first N chunks as sample output. */
    private void printSampleChunks(List<Document> chunks, String source, int n) {
        log.info("[RAG] ── Sample chunks from {} ──────────────────────", source);
        chunks.stream().limit(n).forEach(doc -> {
            String preview = doc.getText().replace("\n", " ");
            if (preview.length() > 150) preview = preview.substring(0, 150) + "…";
            log.info("[RAG]   [{}] metadata={} | text={}",
                    source, doc.getMetadata(), preview);
        });
        log.info("[RAG] ─────────────────────────────────────────────────");
    }

    private String readClasspathFile(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not read " + path, e);
        }
    }
}
