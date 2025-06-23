package com.devpost.amplify.service;

import com.google.ads.googleads.lib.GoogleAdsClient;
import com.google.ads.googleads.v20.enums.KeywordPlanNetworkEnum.KeywordPlanNetwork;
import com.google.ads.googleads.v20.services.*;
import com.google.ads.googleads.v20.utils.ResourceNames;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class KeywordPlannerService {

    private final GoogleAdsClient googleAdsClient;
    private final long customerId;
    private final long languageId;

    public record KeywordIdea(
            String keyword,
            long avgMonthlySearches,
            String competitionLevel
    ) {}

    public KeywordPlannerService(GoogleAdsClient googleAdsClient, long customerId, long languageId) {
        this.googleAdsClient = googleAdsClient;
        this.customerId = customerId;
        this.languageId = languageId;
    }

    public List<KeywordIdea> getKeywordIdeas(List<String> keywords, String pageUrl) {
        try (KeywordPlanIdeaServiceClient keywordClient =
                     googleAdsClient.getLatestVersion().createKeywordPlanIdeaServiceClient()) {

            GenerateKeywordIdeasRequest.Builder request = GenerateKeywordIdeasRequest.newBuilder()
                    .setCustomerId("")
                    .setLanguage(ResourceNames.languageConstant(languageId))
                    .setKeywordPlanNetwork(KeywordPlanNetwork.GOOGLE_SEARCH_AND_PARTNERS);


            if (keywords.isEmpty() && pageUrl == null) {
                throw new IllegalArgumentException("At least one of keywords or page URL must be provided.");
            }

            if (keywords.isEmpty()) {
                request.getUrlSeedBuilder().setUrl(pageUrl);
            } else if (pageUrl == null) {
                request.getKeywordSeedBuilder().addAllKeywords(keywords);
            } else {
                request.getKeywordAndUrlSeedBuilder().setUrl(pageUrl).addAllKeywords(keywords);
            }

            KeywordPlanIdeaServiceClient.GenerateKeywordIdeasPagedResponse response = keywordClient.generateKeywordIdeas(request.build());
            List<KeywordIdea> keywordIdeas = new ArrayList<>();
            response.iterateAll().forEach(result -> keywordIdeas.add(new KeywordIdea(
                            result.getText(),
                            result.getKeywordIdeaMetrics().getAvgMonthlySearches(),
                            result.getKeywordIdeaMetrics().getCompetition().name()
                    )));
            return keywordIdeas;

        } catch (Exception e) {
            e.printStackTrace(); // <-- print full stack trace to logs/console
            throw new RuntimeException("Failed to fetch keyword ideas: " + e.getMessage(), e);
        }

    }

    public static KeywordPlannerService fromConfig() throws IOException {
        // Loads from ads.properties file
        long customerId = Long.parseLong("");
        long languageId = 1000; // English// New York, for example

        return new KeywordPlannerService(GoogleAdsClient.newBuilder().fromPropertiesFile(new File("/home/karthiktiwari/ads.properties")).build(), customerId, languageId);
    }
}
