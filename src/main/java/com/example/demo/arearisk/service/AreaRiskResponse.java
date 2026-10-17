package com.example.demo.arearisk.service;

import java.util.List;

/**
 * JSON response returned by the /area-risk/explain endpoint.
 *
 * Fields:
 *   area       – area name (nm_pol from dataset)
 *   level      – HIGH | MODERATE | SAFE  (always from SQL, never LLM)
 *   crimeCount – total reported crimes from the structured table
 *   explanation – 2-3 sentence AI explanation (or fallback message)
 *   tips        – 2-3 practical safety tips
 *   sources     – citation list (file name + page/section)
 *   fallback    – true when AI explanation failed and DB-only data is returned
 */
public class AreaRiskResponse {

    private String area;
    private String level;
    private int crimeCount;
    private String explanation;
    private List<String> tips;
    private List<String> sources;
    private boolean fallback;

    public AreaRiskResponse() {}

    public AreaRiskResponse(String area, String level, int crimeCount,
                            String explanation, List<String> tips,
                            List<String> sources, boolean fallback) {
        this.area        = area;
        this.level       = level;
        this.crimeCount  = crimeCount;
        this.explanation = explanation;
        this.tips        = tips;
        this.sources     = sources;
        this.fallback    = fallback;
    }

    /** Factory for area not found in DB. */
    public static AreaRiskResponse notFound(String areaName) {
        return new AreaRiskResponse(
                areaName, null, 0,
                "Area '" + areaName + "' was not found in the dataset.",
                List.of(), List.of(), true);
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public int getCrimeCount() { return crimeCount; }
    public void setCrimeCount(int crimeCount) { this.crimeCount = crimeCount; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public List<String> getTips() { return tips; }
    public void setTips(List<String> tips) { this.tips = tips; }

    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }

    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
}
