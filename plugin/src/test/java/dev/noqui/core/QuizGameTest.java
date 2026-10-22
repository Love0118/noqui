package dev.noqui.core;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class QuizGameTest {
    private final UUID author=UUID.randomUUID(),second=UUID.randomUUID(),third=UUID.randomUUID();
    private final long[] now={100000};
    private final List<String> errors=new ArrayList<>();
    private final Queue<Runnable> callbacks=new ArrayDeque<>();
    private final Song song=Song.input("https://youtu.be/BaW_jenozKc","Bad Apple!!","배드애플;동방");
    private static final class Bridge implements MediaBridge {
        final List<CompletableFuture<Media>> futures=new ArrayList<>();
        final List<QuizGame.Snapshot> states=new ArrayList<>();
        public CompletionStage<Media> prepare(Request r){var f=new CompletableFuture<Media>();futures.add(f);return f;}
        public void cancel(UUID s,long r){} // Deliberately leaves work alive to exercise stale-result suppression.
        public void publish(QuizGame.Snapshot s){states.add(s);}
    }
    private final Bridge bridge=new Bridge();
    private QuizGame game(boolean skip){return new QuizGame(bridge,callbacks::add,()->now[0],new QuizGame.Output(){
        public void changed(QuizGame.Snapshot s){}public void error(UUID id,String error){errors.add(error);}
    },115,skip);}
    private QuizGame start(boolean skip){var game=game(skip);game.start(author,List.of(author,second,third));return game;}
    private void ready(QuizGame game,long duration) {
        game.submit(author,game.snapshot().revision(),song);bridge.futures.getLast().complete(new MediaBridge.Media(UUID.randomUUID().toString(),duration));callbacks.remove().run();
        assertEquals(QuizGame.Phase.PREPARING,game.snapshot().phase());
        game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());now[0]=game.snapshot().startsAt();game.tick();
    }
    @Test void mediaAndBrowserGatesDoNotStartClockEarlyAndDurationCapsRound() {
        var g=start(false);g.submit(author,g.snapshot().revision(),song);now[0]+=50000;g.tick();
        assertEquals(QuizGame.Phase.WAITING_FOR_MEDIA,g.snapshot().phase());assertEquals(0,g.snapshot().startsAt());
        bridge.futures.getFirst().complete(new MediaBridge.Media(UUID.randomUUID().toString(),10000));callbacks.remove().run();
        assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());assertFalse(g.answer(second,"bad apple"));
        g.browsersReady(g.snapshot().revision()-1,true,true);assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());
        g.browsersReady(g.snapshot().revision(),true,true);assertEquals(now[0]+4000,g.snapshot().startsAt());g.controlAccepted(g.snapshot().revision());
        assertEquals(g.snapshot().startsAt()+10000,g.snapshot().endsAt());
        now[0]+=3999;g.tick();assertFalse(g.answer(second,"bad apple"));now[0]++;g.tick();assertTrue(g.answer(second,"bad apple"));
    }
    @Test void firstCorrectScoresOnceContinuesAudioAndGetsNextRight() {
        var g=start(false);ready(g,200000);assertEquals(115000,g.snapshot().clipEndMillis());
        assertFalse(g.answer(author,"bad apple"));assertFalse(g.answer(UUID.randomUUID(),"bad apple"));
        assertTrue(g.answer(second,"I think BAD APPLE!!!"));assertFalse(g.answer(third,"동방"));
        assertEquals(1,g.snapshot().scores().get(second));assertEquals(QuizGame.Phase.REVEAL,g.snapshot().phase());assertTrue(g.snapshot().playing());
        now[0]=g.snapshot().endsAt();g.tick();assertFalse(g.snapshot().playing());
        now[0]+=1000;g.tick();assertEquals(second,g.snapshot().presenter());assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());
    }
    @Test void noWinnerSkipRotatesAndMatchSkipIsOptional() {
        var g=start(false);ready(g,90000);g.skip();now[0]+=1000;g.tick();assertEquals(second,g.snapshot().presenter());
        var quick=start(true);ready(quick,90000);assertTrue(quick.answer(second,"배드애플"));assertFalse(quick.snapshot().playing());
    }
    @Test void staleFormWrongPlayerAndDuplicateSubmissionAreRejected() {
        var g=start(false);long revision=g.snapshot().revision();
        assertThrows(IllegalArgumentException.class,()->g.submit(second,revision,song));
        g.join(UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->g.submit(author,revision,song));
        g.submit(author,g.snapshot().revision(),song);assertThrows(IllegalArgumentException.class,()->g.submit(author,g.snapshot().revision(),song));
        assertEquals(1,bridge.futures.size());
    }
    @Test void stoppedSkippedAndDisconnectedPreparationCannotBeRevived() {
        for(String action:List.of("stop","skip","leave","give")) {
            var g=start(false);g.submit(author,g.snapshot().revision(),song);var pending=bridge.futures.getLast();
            switch(action){case "stop"->g.stop();case "skip"->g.skip();case "leave"->g.leave(author);default->g.give(second);}
            var phase=g.snapshot().phase();long revision=g.snapshot().revision();
            pending.complete(new MediaBridge.Media(UUID.randomUUID().toString(),60000));callbacks.remove().run();
            assertEquals(phase,g.snapshot().phase());assertEquals(revision,g.snapshot().revision());
        }
    }
    @Test void failedPreparationReturnsSameRightsAndCanBeRetried() {
        var g=start(false);g.submit(author,g.snapshot().revision(),song);bridge.futures.getLast().completeExceptionally(new IllegalStateException("private stderr"));callbacks.remove().run();
        assertEquals(author,g.snapshot().presenter());assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());
        assertFalse(errors.getFirst().contains("private stderr"));g.submit(author,g.snapshot().revision(),song);assertEquals(2,bridge.futures.size());
    }
    @Test void browserWaitIsBoundedAndOnlyActualReadyMembersStart() {
        for(boolean ready:List.of(false,true)) {
            var g=start(false);g.submit(author,g.snapshot().revision(),song);bridge.futures.getLast().complete(new MediaBridge.Media(UUID.randomUUID().toString(),60000));callbacks.remove().run();
            g.browsersReady(g.snapshot().revision(),ready,false);now[0]+=20000;g.tick();
            assertEquals(ready?QuizGame.Phase.COUNTDOWN:QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());
        }
    }
    @Test void lastPresenterLeavingWaitsAndNewMembershipReceivesRights() {
        var g=game(false);g.start(author,List.of(author));g.leave(author);assertEquals(QuizGame.Phase.WAITING_FOR_PRESENTER,g.snapshot().phase());
        g.join(second);assertEquals(second,g.snapshot().presenter());assertThrows(IllegalArgumentException.class,()->g.give(author));
    }
    @Test void emptyServerCanStartAndFirstJoinReceivesRights() {
        var g=game(false);g.start(null,List.of());assertTrue(g.started());assertEquals(QuizGame.Phase.WAITING_FOR_PRESENTER,g.snapshot().phase());
        g.join(author);assertEquals(author,g.snapshot().presenter());assertTrue(g.participant(author));
    }
    @Test void unacknowledgedCountdownReschedulesInsteadOfAcceptingSilentAnswers() {
        var g=start(false);g.submit(author,g.snapshot().revision(),song);bridge.futures.getLast().complete(new MediaBridge.Media(UUID.randomUUID().toString(),60000));callbacks.remove().run();
        g.browsersReady(g.snapshot().revision(),true,true);now[0]=g.snapshot().startsAt();g.tick();
        assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());assertFalse(g.answer(second,"bad apple"));
    }
}
