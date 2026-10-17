package com.example.demo.arearisk.setup;

import com.example.demo.arearisk.service.AreaCrimeLoaderService;
import com.example.demo.arearisk.service.RagIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Startup runner that executes Steps 2 and 3 automatically on first run.
 * Both operations are idempotent and skip gracefully if data is already loaded.
 *
 * Run order: Order(2) so it runs AFTER PgVectorSetupCheck (Order default = lowest priority).
 * PgVectorSetupCheck has no @Order so it gets Ordered.LOWEST_PRECEDENCE by default.
 * We use @Order(2) here to run BEFORE that default, giving setup check time to init the schema.
 *
 * Actually: PgVectorSetupCheck should run first. Let's give it @Order(1) and this @Order(2).
 */
@Component
@Order(2)
public class AreaRiskStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AreaRiskStartupRunner.class);

    private final AreaCrimeLoaderService areaLoaderService;
    private final RagIngestionService ragIngestionService;

    public AreaRiskStartupRunner(AreaCrimeLoaderService areaLoaderService,
                                 RagIngestionService ragIngestionService) {
        this.areaLoaderService = areaLoaderService;
        this.ragIngestionService = ragIngestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=======================================================");
        log.info("  STEP 2: Loading structured area crime data ...");
        log.info("=======================================================");
        try {
            String step2Result = areaLoaderService.loadStructuredData();
            log.info("[STARTUP] Step 2 result: {}", step2Result);
        } catch (Exception e) {
            log.error("[STARTUP] Step 2 failed: {}", e.getMessage(), e);
            log.warn("[STARTUP] Skipping Step 3 (RAG ingestion) because Step 2 failed.");
            return;
        }

        log.info("=======================================================");
        log.info("  STEP 3: RAG ingestion ...");
        log.info("=======================================================");
        try {
            Map<String, String> step3Results = ragIngestionService.ingestAll();
            log.info("[STARTUP] Step 3 results:");
            step3Results.forEach((source, status) ->
                    log.info("[STARTUP]   {} → {}", source, status));
        } catch (Exception e) {
            log.error("[STARTUP] Step 3 failed: {}", e.getMessage(), e);
        }

        log.info("=======================================================");
        log.info("  Area Risk feature is ready. See /area-risk/* endpoints.");
        log.info("=======================================================");
    }
}
