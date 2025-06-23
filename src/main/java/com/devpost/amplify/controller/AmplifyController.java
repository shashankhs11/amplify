package com.devpost.amplify.controller;

import com.devpost.amplify.model.HtmlAnalysisResult;
import com.devpost.amplify.model.SessionStore;
import com.devpost.amplify.service.agents.ContentGenerationAgent;
import com.devpost.amplify.service.agents.QueryGenerationAgent;
import com.devpost.amplify.service.agents.SummarizationAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.apache.commons.lang.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping()
public class AmplifyController {

    private final Runner runner;
    private final QueryGenerationAgent queryAgent;
    private final ContentGenerationAgent contentAgent;
    private final SummarizationAgent analyseAgent;

    private final Logger logger = LoggerFactory.getLogger(AmplifyController.class);

    public AmplifyController() throws Exception {
        // Prompt for generating related queries
        String queryPrompt = """
        Given a seed search term: {{input}},
        generate 5 highly relevant and specific search queries a user might also search.
        
        Only return the list as a single comma-separated line (CSV format), without any explanations, numbering, or extra text.
        Example output: "paneer butter masala recipe, how to make paneer at home, best paneer tikka marinade, paneer nutrition facts, types of paneer dishes"
        """;

        String analysisPrompt = """
        You are an expert summarizer. Below is scraped content for search queries from high-ranking web pages.
        Your task is to extract key points and generate a structured summary for marketing professionals or businesses to use for their own content.
        Share as structured html file with proper formatting that can be used to display directly on a web page
        Below are the main sections that should be present
        Main Concepts:
            - ...
        Trends and Patterns:
            - ...
        Popular Sources:
            - ...
        Audience Insights:
            - ...
        Content Gaps or Opportunities:
            - ...

        {{input}}
        """;

        // Prompt for generating content based on earlier analysis
        String contentPrompt = """
        Based on the summary or insights provided,
        generate a high-quality piece of content.
        Maintain clarity and make it informative. Generate in the form of a template that's easily editable and include placeholders.
        Share as structured html file with proper formatting that can be used to display directly on a web page
        Just share the actual content. No affirmations, greetings or acknowledgements. Below is the analysis
        
        {{input}}
        """;

        LlmAgent llmGenQuery = LlmAgent.builder()
                .name("query_gen")
                .model("gemini-2.0-flash")
                .description("Generate related queries")
                .instruction(queryPrompt)
                .build();

        LlmAgent llmAnalyseResult = LlmAgent.builder()
                .name("analyse_gen")
                .model("gemini-2.0-flash")
                .description("Analyse related Content")
                .instruction(analysisPrompt)
                .build();

        LlmAgent llmGenContent = LlmAgent.builder()
                .name("content_gen")
                .model("gemini-2.0-flash")
                .description("Generate final content")
                .instruction(contentPrompt)
                .build();

        this.queryAgent = new QueryGenerationAgent(llmGenQuery);
        this.contentAgent = new ContentGenerationAgent(llmGenContent);
        this.analyseAgent = new SummarizationAgent(llmAnalyseResult);

        this.runner = new Runner(
                llmGenQuery,
                "Amplify",
                new InMemoryArtifactService(),
                new InMemorySessionService()
        );
    }

    @PostMapping("/query")
    public ResponseEntity<?> generateQueries(@RequestBody Map<String, String> input) {
        String sessionId = input.get("sessionId");
        String seed = input.get("seedTerm");

        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(seed)) {
            return ResponseEntity.badRequest().body("sessionId and seedTerm are required");
        }

        try {
            InvocationContext ctx = SessionStore.getOrCreate(
                    runner.sessionService(),
                    runner.artifactService(),
                    sessionId,
                    queryAgent,
                    Content.fromParts(Part.fromText(seed))
            );

            // Bind prompt variables
            ctx.session().state().put("input", seed);
            ctx.session().state().put("seedTerm", seed);

            Flowable<Event> flow = queryAgent.runAsync(ctx);
            flow.blockingSubscribe();

            Object queries = ctx.session().state().get("relatedQueries").toString();
            if (queries == null) {
                return ResponseEntity.internalServerError().body("Failed to generate queries");
            }

            List<String> relatedQueries;

            // Ensure proper format for LLM output
            if (queries instanceof String str) {
                relatedQueries = Arrays.stream(str.split("\n"))
                        .map(line -> line.replaceAll("^\\d+[.)]\\s*", "").replaceAll("[\\[\\]\"]", "").trim())
                        .filter(s -> !s.isEmpty())
                        .toList();
            } else {
                return ResponseEntity.badRequest().body("LLM output not in expected string format: " + queries);
            }

            SessionStore.save(sessionId, ctx);

            logger.info("Generated {} queries for session {}", relatedQueries.size(), sessionId);

            return ResponseEntity.ok(Map.of(
                    "relatedQueries", relatedQueries.getFirst().split(","),
                    "sessionInfo", SessionStore.getSessionInfo(sessionId)
            ));

        } catch (Exception e) {
            logger.error("Error generating queries for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error generating queries: " + e.getMessage());
        }
    }

    @PostMapping("/summarize")
    public ResponseEntity<?> summarize(@RequestBody Map<String, String> input) {
        String sessionId = input.get("sessionId");

        if (StringUtils.isBlank(sessionId)) {
            return ResponseEntity.badRequest().body("sessionId is required");
        }

        try {
            InvocationContext ctx = SessionStore.getOrCreate(
                    runner.sessionService(),
                    runner.artifactService(),
                    sessionId,
                    analyseAgent,
                    Content.fromParts(Part.fromText(""))
            );

            // Handle the case where relatedQueries might be stored as different types
            List<String> queries;
            if (input.containsKey("queries") && StringUtils.isNotBlank(input.get("queries"))) {
                queries = Arrays.stream(input.get("queries").split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            } else {
                Object storedQueries = ctx.session().state().get("relatedQueries");
                if (storedQueries instanceof List) {
                    queries = (List<String>) storedQueries;
                } else if (storedQueries instanceof String) {
                    queries = Arrays.stream(((String) storedQueries).split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                } else {
                    return ResponseEntity.badRequest().body("No queries found in session or input. Please call /query first.");
                }
            }

            if (queries.isEmpty()) {
                return ResponseEntity.badRequest().body("No valid queries found");
            }

            logger.info("Processing {} queries for session {}", queries.size(), sessionId);

            StringBuilder aggregatedTextBuilder = new StringBuilder();
            List<String> failedQueries = new ArrayList<>();
            List<String> successfulQueries = new ArrayList<>();

            for (String query : queries) {
                try {
                    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                    String apiUrl = UriComponentsBuilder
                            .fromUriString("https://www.googleapis.com/customsearch/v1")
                            .queryParam("q", encodedQuery)
                            .queryParam("key", "ADD_YOUR_API_KEY_HERE")
                            .queryParam("cx", "ADD_YOUR_CX_HERE")
                            .build()
                            .toUriString();

                    logger.debug("Calling Custom Search API for query: {}", query);
                    HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10_000);
                    connection.setReadTimeout(10_000);

                    int responseCode = connection.getResponseCode();
                    if (responseCode != 200) {
                        logger.warn("Skipping query '{}' due to API response code {}", query, responseCode);
                        failedQueries.add(query);
                        continue;
                    }

                    InputStream res = connection.getInputStream();
                    JsonNode items = new ObjectMapper().readTree(res).get("items");

                    if (items != null && items.size() > 0) {
                        JsonNode item = items.get(0);
                        String link = item.path("link").asText();

                        if (StringUtils.isNotBlank(link)) {
                            // Rate limiting
                            Thread.sleep(500 + (int)(Math.random() * 400));

                            Document doc = Jsoup.connect(link)
                                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                    .timeout(10000)
                                    .get();

                            String title = doc.title();
                            String metaDescription = doc.selectFirst("meta[name=description]") != null
                                    ? doc.selectFirst("meta[name=description]").attr("content")
                                    : "";

                            String h1 = doc.select("h1").stream()
                                    .map(e -> e.text())
                                    .findFirst()
                                    .orElse("");

                            String fullText = doc.body().text();

                            // Limit text length to prevent excessive token usage
                            if (fullText.length() > 2000) {
                                fullText = fullText.substring(0, 2000) + "...";
                            }

                            HtmlAnalysisResult analysisResult = new HtmlAnalysisResult(title, metaDescription, h1, fullText);
                            aggregatedTextBuilder.append(analysisResult.toString())
                                    .append("\n\n----\n\n");

                            successfulQueries.add(query);
                            logger.debug("Successfully fetched content for query: {}", query);
                        }
                    } else {
                        logger.warn("No items found for query '{}'", query);
                        failedQueries.add(query);
                    }

                } catch (Exception e) {
                    logger.error("Error processing query '{}': {}", query, e.getMessage());
                    failedQueries.add(query);
                }
            }

            String aggregatedText = aggregatedTextBuilder.toString();

            // Prepare input for analysis
            Content analysisInput;
            if (aggregatedText.isBlank()) {
                logger.info("No search content available. Using fallback analysis for queries: {}", queries);
                analysisInput = Content.fromParts(Part.fromText(String.format("""
                Assume the following queries were searched: %s
                Provide a structured analysis even without page content.
                Share without any affirmation, greeting or acknowledgement.
                Use LLM knowledge to estimate:
                - Main Concepts
                - Trends
                - Gaps
                - Audience types
                - Source patterns
                """, String.join(", ", queries))));
            } else {
                analysisInput = Content.fromParts(Part.fromText(String.format("""
                You are an expert summarizer. Summarize the following scraped content.
                Share without any affirmation, greeting or acknowledgement.
                Use LLM knowledge to estimate:
                - Main Concepts
                - Trends
                - Gaps
                - Audience types
                - Source patterns
                === Begin Content ===
                %s
                === End Content ===
                """, aggregatedText)));
            }

            ctx.session().state().put("input", analysisInput);

            analyseAgent.runAsync(ctx).blockingSubscribe();
            Object analysisResult = ctx.session().state().get("analysisText");

            if (analysisResult == null) {
                return ResponseEntity.internalServerError().body("Analysis failed - no result generated");
            }

            String summary = analysisResult.toString();
            SessionStore.save(sessionId, ctx);

            logger.info("Analysis completed for session {}. Successful queries: {}, Failed queries: {}",
                    sessionId, successfulQueries.size(), failedQueries.size());

            return ResponseEntity.ok(Map.of(
                    "summary", summary,
                    "processedQueries", successfulQueries,
                    "failedQueries", failedQueries,
                    "sessionInfo", SessionStore.getSessionInfo(sessionId)
            ));

        } catch (Exception e) {
            logger.error("Error during analysis for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Analysis failed: " + e.getMessage());
        }
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, String> input) {
        String sessionId = input.get("sessionId");
        String contentType = input.get("contentType");

        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(contentType)) {
            return ResponseEntity.badRequest().body("sessionId and contentType are required");
        }

        try {
            // Get the existing context to retrieve analysis
            InvocationContext ctx = SessionStore.getOrCreate(
                    runner.sessionService(),
                    runner.artifactService(),
                    sessionId,
                    contentAgent,
                    Content.fromParts(Part.fromText(""))
            );

            Object analysisText = ctx.session().state().get("analysisText");
            if (analysisText == null) {
                return ResponseEntity.badRequest().body("No analysis found. Please call /summarize first.");
            }

            // Create the content generation prompt based on content type
            String contentPrompt;
            if ("blog-post".equals(contentType)) {
                contentPrompt = String.format("""
                Based on the analysis provided below, generate a comprehensive blog post.
                
                Requirements:
                - Create an engaging title
                - Include proper headings and subheadings
                - Make it informative and well-structured
                - Include actionable insights
                - Format as HTML for web display
                - No greetings, acknowledgements, or affirmations
                
                Analysis:
                %s
                """, analysisText);
            } else if ("social-media".equals(contentType)) {
                contentPrompt = String.format("""
                Based on the analysis provided below, generate engaging social media content.
                
                Requirements:
                - Create multiple post variations (2-3 different posts)
                - Include relevant hashtags
                - Make it engaging and shareable
                - Keep within social media character limits
                - Format for direct posting
                - No greetings, acknowledgements, or affirmations
                
                Analysis:
                %s
                """, analysisText);
            } else {
                contentPrompt = String.format("""
                Based on the analysis provided below, generate high-quality marketing content.
                
                Requirements:
                - Make it informative and actionable
                - Structure it professionally
                - Include key insights and recommendations
                - Format as HTML for web display
                - No greetings, acknowledgements, or affirmations
                
                Analysis:
                %s
                """, analysisText);
            }

            // Set the complete prompt as input
            ctx.session().state().put("input", contentPrompt);

            // Run the content generation
            Flowable<Event> flow = contentAgent.runAsync(ctx);
            flow.blockingSubscribe();

            Object content = ctx.session().state().get("generatedContent");
            if (content == null) {
                return ResponseEntity.internalServerError().body("Content generation failed - no result generated");
            }

            // Store the generated content in session for potential future use
            ctx.session().state().put("lastGeneratedContent", content);
            ctx.session().state().put("lastContentType", contentType);

            SessionStore.save(sessionId, ctx);

            logger.info("Content generated for session {} with type {}", sessionId, contentType);

            return ResponseEntity.ok(Map.of(
                    "generatedContent", content.toString(),
                    "contentType", contentType,
                    "sessionInfo", SessionStore.getSessionInfo(sessionId)
            ));

        } catch (Exception e) {
            logger.error("Error generating content for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Content generation failed: " + e.getMessage());
        }
    }

    // Additional utility endpoints for session management

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSessionInfo(@PathVariable String sessionId) {
        Map<String, Object> sessionInfo = SessionStore.getSessionInfo(sessionId);
        return ResponseEntity.ok(sessionInfo);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<?> deleteSession(@PathVariable String sessionId) {
        SessionStore.remove(sessionId);
        return ResponseEntity.ok(Map.of("message", "Session deleted", "sessionId", sessionId));
    }

    @GetMapping("/sessions/active")
    public ResponseEntity<?> getActiveSessions() {
        return ResponseEntity.ok(Map.of(
                "activeSessionCount", SessionStore.getActiveSessionCount(),
                "timestamp", System.currentTimeMillis()
        ));
    }

    @PostMapping("/sessions/cleanup")
    public ResponseEntity<?> forceCleanup() {
        int cleanedCount = SessionStore.cleanupExpiredSessions();
        return ResponseEntity.ok(Map.of(
                "cleanedSessions", cleanedCount,
                "remainingSessions", SessionStore.getActiveSessionCount()
        ));
    }
}