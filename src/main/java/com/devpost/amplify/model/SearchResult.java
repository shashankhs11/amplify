package com.devpost.amplify.model;

import lombok.Data;

@Data
public class SearchResult {
    private String title;

    private String link;
    private String snippet;
    private String displayLink;

    // Optional: helpful for content classification
    private String keywordContext;
}