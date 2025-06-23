package com.devpost.amplify.model;

import lombok.Data;

@Data
public class HtmlAnalysisResult {
    private String title;
    private String metaDescription;
    private String h1;
    private String fullText;

    @Override
    public String toString() {
        return "HtmlAnalysisResult{" +
                "title='" + title + '\'' +
                ", metaDescription='" + metaDescription + '\'' +
                ", h1='" + h1 + '\'' +
                ", fullText='" + fullText + '\'' +
                '}';
    }

    public HtmlAnalysisResult(String title, String metaDescription, String h1, String fullText) {
        this.title = title;
        this.metaDescription = metaDescription;
        this.h1 = h1;
        this.fullText = fullText;
    }
}
