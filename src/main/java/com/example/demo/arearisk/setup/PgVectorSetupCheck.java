package com.example.demo.arearisk.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * STEP 1 – pgvector Setup Check.
 *
 * Runs once at startup. Performs:
 *   1. Confirm pgvector extension is active (SELECT extname FROM pg_extension)
 *   2. Embed one test sentence → print vector dimensions
 *   3. Store the test document and run a similarity search
 *   4. Remove the test document afterwards
 *
 * All output goes to the log at INFO level. Stop and read the log output
 * before proceeding to Step 2.
 */
@Component
@Order(1)
public class PgVectorSetupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSetupCheck.class);

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final JdbcTemplate pgvectorJdbcTemplate;

    public PgVectorSetupCheck(
            EmbeddingModel embeddingModel,
            VectorStore vectorStore,
            @Qualifier("pgvectorJdbcTemplate") JdbcTemplate pgvectorJdbcTemplate) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.pgvectorJdbcTemplate = pgvectorJdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=======================================================");
        log.info("  STEP 1: pgvector Setup Check");
        log.info("=======================================================");

        // 1. Check pgvector extension
        try {
            List<String> extensions = pgvectorJdbcTemplate.queryForList(
                    "SELECT extname FROM pg_extension WHERE extname = 'vector'",
                    String.class);
            if (extensions.isEmpty()) {
                log.error("[SETUP-CHECK] pgvector extension NOT found in PostgreSQL! " +
                        "Run: CREATE EXTENSION IF NOT EXISTS vector;");
            } else {
                log.info("[SETUP-CHECK] ✅ pgvector extension is active: {}", extensions.get(0));
            }
        } catch (Exception e) {
            log.error("[SETUP-CHECK] ❌ Could not connect to pgvector DataSource: {}", e.getMessage());
            return;
        }

        // 2. Embed one test sentence → print vector size
        String testSentence = "SheSafe area risk test – Delhi crime density check.";
        float[] vector;
        try {
            vector = embeddingModel.embed(testSentence);
            log.info("[SETUP-CHECK] ✅ Embedding model OK. Vector dimensions = {}", vector.length);
        } catch (Exception e) {
            log.error("[SETUP-CHECK] ❌ Embedding failed: {}", e.getMessage());
            return;
        }

        // 3. Store test document and run similarity search
        String testId = "setup-check-test-doc";
        Document testDoc = new Document(
                testId,
                "SheSafe setup check: This is a test document for pgvector similarity search.",
                Map.of("source", "setup-check", "area_name", "TEST_AREA"));
        try {
            vectorStore.add(List.of(testDoc));
            log.info("[SETUP-CHECK] ✅ Test document stored in pgvector.");

            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("Delhi crime area safety test")
                            .topK(3)
                            .build());

            log.info("[SETUP-CHECK] ✅ Similarity search returned {} result(s).", results.size());
            results.forEach(doc ->
                    log.info("[SETUP-CHECK]   → ID={} score_metadata={} content_snippet={}",
                            doc.getId(),
                            doc.getMetadata(),
                            doc.getText().substring(0, Math.min(80, doc.getText().length()))));
        } catch (Exception e) {
            log.error("[SETUP-CHECK] ❌ VectorStore operation failed: {}", e.getMessage(), e);
            return;
        } finally {
            // 4. Clean up the test document
            try {
                vectorStore.delete(List.of(testId));
                log.info("[SETUP-CHECK] ✅ Test document removed from pgvector.");
            } catch (Exception e) {
                log.warn("[SETUP-CHECK] Could not remove test document (will not affect operation): {}", e.getMessage());
            }
        }

        log.info("=======================================================");
        log.info("  STEP 1 COMPLETE. Vector dims={}. pgvector is ready.", vector.length);
        log.info("  ⚠️  STOP HERE and confirm output before running Step 2.");
        log.info("=======================================================");
    }
}
