package com.devpost.amplify.service.agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

public class ContentGenerationAgent extends BaseAgent {
    private final LlmAgent llmAgent;

    public ContentGenerationAgent(LlmAgent llmAgent) {
        super("content_generation", "Generate content summary", List.of(llmAgent), null, null);
        this.llmAgent = llmAgent;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return llmAgent.runAsync(ctx)
                .doOnNext(e -> e.content().ifPresent(c ->
                        ctx.session().state().put("generatedContent", c.text())
                ));
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        return null;
    }
}



