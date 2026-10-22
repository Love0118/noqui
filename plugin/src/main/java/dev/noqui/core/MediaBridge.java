package dev.noqui.core;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** All network operations return immediately. Paper remains authoritative for game state. */
public interface MediaBridge extends AutoCloseable {
    record Request(UUID session, long revision, Song song) {}
    record Media(String id, long durationMillis) {
        public Media {
            if (id == null || id.isBlank() || durationMillis <= 0) throw new IllegalArgumentException("Invalid prepared media");
        }
    }
    CompletionStage<Media> prepare(Request request);
    void cancel(UUID session, long revision);
    void publish(QuizGame.Snapshot state);
    @Override default void close() {}
}
