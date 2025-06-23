package com.devpost.amplify.service.agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

public class SummarizationAgent extends BaseAgent {

    public SummarizationAgent(LlmAgent llmAgent) {
        super(
                "summarization",
                "Summarizes raw scraped content",
                List.of(llmAgent),
                null,
                null
        );
        this.llmAgent = llmAgent;
    }
    private final LlmAgent llmAgent;


    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return llmAgent.runAsync(ctx)
                .doOnNext(evt ->
                        evt.content().ifPresent(c ->
                                ctx.session().state().put("analysisText", c.text())
                        )
                );
    }

    // No live impl
    @Override protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
        return Flowable.empty();
    }
}

