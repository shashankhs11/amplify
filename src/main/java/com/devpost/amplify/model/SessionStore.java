package com.devpost.amplify.model;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionStore {
    private static final Logger logger = LoggerFactory.getLogger(SessionStore.class);
    private static final String USER = "user";
    private static final String APP = "Amplify";
    private static final long SESSION_TIMEOUT_MINUTES = 5;

    // Store for session contexts
    private static final Map<String, SessionEntry> store = new ConcurrentHashMap<>();

    // Scheduled executor for cleanup tasks
    private static final ScheduledExecutorService cleanupExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SessionStore-Cleanup");
                t.setDaemon(true);
                return t;
            });

    // Static initializer to start cleanup task
    static {
        // Run cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(SessionStore::cleanupExpiredSessions,
                1, 1, TimeUnit.MINUTES);

        // Shutdown hook to clean up executor
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down SessionStore cleanup executor");
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    /**
     * Internal class to track session context with timestamp
     */
    private static class SessionEntry {
        private final InvocationContext context;
        @Getter
        private final LocalDateTime createdAt;
        @Getter
        private LocalDateTime lastAccessedAt;

        public SessionEntry(InvocationContext context) {
            this.context = context;
            this.createdAt = LocalDateTime.now();
            this.lastAccessedAt = LocalDateTime.now();
        }

        public InvocationContext getContext() {
            this.lastAccessedAt = LocalDateTime.now();
            return context;
        }

        public boolean isExpired() {
            return ChronoUnit.MINUTES.between(createdAt, LocalDateTime.now()) >= SESSION_TIMEOUT_MINUTES;
        }

    }

    public static InvocationContext getOrCreate(
            BaseSessionService sessionService,
            BaseArtifactService artifactService,
            String sessionId,
            BaseAgent agent,
            Content input
    ) {
        SessionEntry entry = store.get(sessionId);

        if (entry != null) {
            if (entry.isExpired()) {
                logger.info("Session {} expired, removing and creating new one", sessionId);
                cleanupSession(sessionId, entry);
                entry = null;
            } else {
                logger.debug("Reusing existing session {}", sessionId);
                return entry.getContext();
            }
        }

        if (entry == null) {
            logger.info("Creating new session {}", sessionId);
            try {
                Session session = sessionService.createSession(APP, USER).blockingGet();
                InvocationContext context = InvocationContext.create(
                        sessionService,
                        artifactService,
                        USER,
                        agent,
                        session,
                        input,
                        RunConfig.builder().build()
                );

                entry = new SessionEntry(context);
                store.put(sessionId, entry);

                logger.info("Session {} created successfully. Total active sessions: {}",
                        sessionId, store.size());

                return entry.getContext();
            } catch (Exception e) {
                logger.error("Failed to create session {}: {}", sessionId, e.getMessage(), e);
                throw new RuntimeException("Failed to create session", e);
            }
        }

        return entry.getContext();
    }

    public static void save(String sessionId, InvocationContext ctx) {
        SessionEntry entry = store.get(sessionId);
        if (entry != null && !entry.isExpired()) {
            // Update last accessed time
            entry.getContext();
            logger.debug("Session {} saved/updated", sessionId);
        } else {
            logger.warn("Attempted to save expired or non-existent session {}", sessionId);
        }
    }

    public static void remove(String sessionId) {
        SessionEntry entry = store.remove(sessionId);
        if (entry != null) {
            cleanupSession(sessionId, entry);
            logger.info("Session {} manually removed. Total active sessions: {}",
                    sessionId, store.size());
        }
    }

    /**
     * Get session info for debugging
     */
    public static Map<String, Object> getSessionInfo(String sessionId) {
        SessionEntry entry = store.get(sessionId);
        if (entry == null) {
            return Map.of("exists", false);
        }

        return Map.of(
                "exists", true,
                "createdAt", entry.getCreatedAt(),
                "lastAccessedAt", entry.getLastAccessedAt(),
                "expired", entry.isExpired(),
                "sessionId", sessionId
        );
    }

    /**
     * Get all active sessions count
     */
    public static int getActiveSessionCount() {
        return store.size();
    }

    /**
     * Manual cleanup trigger for testing
     */
    public static int cleanupExpiredSessions() {
        int cleanedCount = 0;

        for (Map.Entry<String, SessionEntry> entry : store.entrySet()) {
            if (entry.getValue().isExpired()) {
                String sessionId = entry.getKey();
                SessionEntry sessionEntry = store.remove(sessionId);
                if (sessionEntry != null) {
                    cleanupSession(sessionId, sessionEntry);
                    cleanedCount++;
                }
            }
        }

        if (cleanedCount > 0) {
            logger.info("Cleaned up {} expired sessions. Active sessions remaining: {}",
                    cleanedCount, store.size());
        }

        return cleanedCount;
    }

    /**
     * Cleanup individual session resources
     */
    private static void cleanupSession(String sessionId, SessionEntry entry) {
        try {
            InvocationContext context = entry.context;

            // Clear session state
            if (context.session() != null && context.session().state() != null) {
                context.session().state().clear();
            }

            // Additional cleanup if needed for your specific agents
            // You might want to add specific cleanup for your agents here

            logger.debug("Session {} resources cleaned up", sessionId);

        } catch (Exception e) {
            logger.warn("Error cleaning up session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Force cleanup all sessions (for testing or shutdown)
     */
    public static void clearAllSessions() {
        int count = store.size();
        store.forEach((sessionId, entry) -> cleanupSession(sessionId, entry));
        store.clear();
        logger.info("Cleared all {} sessions", count);
    }
}