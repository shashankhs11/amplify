package com.devpost.amplify.service.agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Arrays;
import java.util.List;

public class QueryGenerationAgent extends BaseAgent {
    private final LlmAgent llmAgent;

    public QueryGenerationAgent(LlmAgent llmAgent) {
        super("query_generation", "Generate related queries", List.of(llmAgent), null, null);
        this.llmAgent = llmAgent;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return llmAgent.runAsync(ctx)
                .doOnNext(e -> {
                    e.content().ifPresent(c -> {
                        List<String> queries = Arrays.stream(c.text().split("\n"))
                                .map(String::trim).filter(s -> !s.isEmpty()).toList();
                        ctx.session().state().put("relatedQueries", queries);
                    });
                });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        return null;
    }
}
