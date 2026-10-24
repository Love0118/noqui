package dev.noqui.core;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackHandshakeTest {
    private static final class Fixture {
        final UUID author=UUID.randomUUID(),other=UUID.randomUUID();
        long now=100000;int ended;
        final QuizGame game;
        Fixture(){this(60000,false,false);}
        Fixture(long duration,boolean noncompetitive,boolean hints){
            var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){return CompletableFuture.completedFuture(new Media("ready",duration));}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
            game=new QuizGame(bridge,Runnable::run,()->now,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String m){fail(m);}public void roundEnded(){ended++;}},115,noncompetitive);
            game.modes(game.modes().with(QuizModes.Mode.NONCOMPETITIVE,noncompetitive).with(QuizModes.Mode.AUTO_HINT,hints));
            game.start(List.of(author,other),1);game.grant(author,1);game.submit(author,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Song","tag"));
            game.browsersReady(game.snapshot().revision(),true,true);
        }
        void ack(){game.controlAccepted(game.snapshot().revision());}
        boolean proof(UUID player,long position,long seen){var s=game.snapshot();return game.confirmPlayback(player,s.roundId(),s.playbackGeneration(),position,seen);}
        void start(){ack();now=game.snapshot().startsAt()+1;assertTrue(proof(author,1,now));}
    }
    @Test void freshProgressAfterLongHiddenWaitCommitsOnceWithoutChangingGeneration() {
        var f=new Fixture();f.ack();var scheduled=f.game.snapshot();f.now=scheduled.startsAt()+120000;f.game.tick();
        assertEquals(0,f.game.snapshot().endsAt());assertEquals(60000,f.game.snapshot().remainingMillis());assertTrue(f.game.pending(f.author));
        assertTrue(f.proof(f.other,250,f.now-4000));var playing=f.game.snapshot();
        assertEquals(QuizGame.Phase.PLAYING,playing.phase());assertEquals(scheduled.playbackGeneration(),playing.playbackGeneration());
        assertEquals(2250,playing.anchorPositionMillis());assertEquals(f.now,playing.startsAt());assertEquals(f.now+57750,playing.endsAt());assertFalse(f.game.pending(f.author));
        assertFalse(f.proof(f.other,350,f.now));assertEquals(playing,f.game.snapshot());assertTrue(f.game.answer(f.other,"tag"));
        f.game.stop();assertEquals(0,f.game.credits(f.author));
    }
    @Test void transportIdentityFreshnessAndPositiveProgressAreAllRequired() {
        var f=new Fixture();var s=f.game.snapshot();f.now=s.startsAt()+1000;
        assertFalse(f.proof(f.author,1,f.now));f.ack();
        assertFalse(f.proof(f.author,1,s.startsAt()-1));assertFalse(f.proof(f.author,1,f.now+1));
        assertFalse(f.proof(UUID.randomUUID(),1,f.now));assertFalse(f.proof(f.author,0,f.now));assertFalse(f.proof(f.author,60001,f.now));
        assertFalse(f.game.confirmPlayback(f.author,UUID.randomUUID(),s.playbackGeneration(),1,f.now));
        assertFalse(f.game.confirmPlayback(f.author,s.roundId(),UUID.randomUUID(),1,f.now));
        f.now=s.startsAt()+10000;assertFalse(f.proof(f.author,1,f.now-5001));assertTrue(f.game.pending(f.author));
        assertTrue(f.proof(f.author,1,f.now-5000));assertEquals(2001,f.game.snapshot().anchorPositionMillis());
    }
    @Test void pauseDuringPendingStartFreezesAnchorAndOldGenerationCannotResumeIt() {
        var f=new Fixture();f.ack();var first=f.game.snapshot();f.now=first.startsAt()+120000;f.game.pause();
        assertEquals(0,f.game.snapshot().anchorPositionMillis());assertEquals(60000,f.game.snapshot().remainingMillis());f.game.resume();f.ack();f.now=f.game.snapshot().startsAt()+1;
        assertFalse(f.game.confirmPlayback(f.author,first.roundId(),first.playbackGeneration(),1,f.now));
        assertTrue(f.proof(f.author,1,f.now));assertEquals(59999,f.game.snapshot().remainingMillis());
    }
    @Test void repeatedPendingResumePreservesNoncompetitiveAnswerWindowAndSpendsOnlyOnce() {
        // The presenter submits the song, so the two answers must come from the other participants.
        var f=new Fixture(60000,true,false);UUID third=UUID.randomUUID();f.game.join(third);f.start();assertTrue(f.game.answer(f.other,"Song"));f.now+=1000;f.game.pause();long anchor=f.game.snapshot().anchorPositionMillis();
        f.game.resume();f.ack();f.now=f.game.snapshot().startsAt()+120000;f.game.tick();assertEquals(QuizGame.Phase.COUNTDOWN,f.game.snapshot().phase());assertEquals(0,f.ended);
        f.game.pause();assertEquals(anchor,f.game.snapshot().anchorPositionMillis());f.game.resume();f.ack();f.now=f.game.snapshot().startsAt()+1;assertTrue(f.proof(f.other,anchor+1,f.now));
        f.now+=999;f.game.tick();assertTrue(f.game.snapshot().playing());assertTrue(f.game.answer(third,"tag"));f.now++;f.game.tick();
        assertFalse(f.game.snapshot().playing());assertEquals(1,f.ended);assertEquals(0,f.game.credits(f.author));assertEquals(List.of(f.other,third),f.game.snapshot().winners());
    }
    @Test void repeatedPendingResumePreservesHintDelay() {
        var f=new Fixture(60000,false,true);f.start();f.now+=4000;f.game.pause();long anchor=f.game.snapshot().anchorPositionMillis();
        f.game.resume();f.ack();f.now=f.game.snapshot().startsAt()+120000;f.game.tick();f.game.pause();f.game.resume();f.ack();
        f.now=f.game.snapshot().startsAt()+1;assertTrue(f.proof(f.author,anchor+1,f.now));f.now+=4999;f.game.tick();assertTrue(f.game.snapshot().hint().isEmpty());
        f.now++;f.game.tick();assertFalse(f.game.snapshot().hint().isEmpty());
    }
    @Test void endPositionForVeryShortClipConfirmsAndEndsExactlyOnePlayedRound() {
        var f=new Fixture(1,false,false);f.ack();var s=f.game.snapshot();f.now=s.startsAt()+1;
        assertTrue(f.proof(f.author,1,f.now));assertEquals(QuizGame.Phase.REVEAL,f.game.snapshot().phase());assertFalse(f.game.snapshot().playing());
        assertEquals(1,f.ended);assertEquals(1,f.game.snapshot().round());assertEquals(f.now+1000,f.game.snapshot().endsAt());assertEquals(0,f.game.credits(f.author));
        assertFalse(f.game.confirmPlayback(f.author,s.roundId(),s.playbackGeneration(),1,f.now));f.now+=1000;f.game.tick();assertFalse(f.game.started());assertEquals(1,f.ended);
    }
    @Test void lateProofCannotReviveSkippedStoppedOrDepartedParticipant() {
        for(String action:List.of("skip","stop","leave")){
            var f=new Fixture();f.ack();var s=f.game.snapshot();f.now=s.startsAt()+1;
            switch(action){case "skip"->f.game.forceSkip(s.roundId());case "stop"->f.game.stop();default->f.game.leave(f.author);}
            var before=f.game.snapshot();assertFalse(f.game.confirmPlayback(f.author,s.roundId(),s.playbackGeneration(),1,f.now));assertEquals(before,f.game.snapshot());assertEquals(1,f.game.credits(f.author));
        }
    }
    @Test void doubleSpeedBoundsExtrapolationInWallTimeAndNeverPassesClipEnd() {
        var f=new Fixture();f.game.stop();f.game.modes(f.game.modes().with(QuizModes.Mode.SPEED_X2,true));f.game.start(List.of(f.author));
        f.game.submit(f.author,f.game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Song","tag"));f.game.browsersReady(f.game.snapshot().revision(),true,true);f.ack();f.now=f.game.snapshot().startsAt()+5000;
        assertTrue(f.proof(f.author,100,f.now-5000));assertEquals(4100,f.game.snapshot().anchorPositionMillis());assertEquals(27950,f.game.snapshot().remainingMillis());
    }
    @Test void outlierEndPositionCannotAdvanceAnEntireLongRound() {
        var f=new Fixture();f.ack();f.now=f.game.snapshot().startsAt()+5000;
        assertTrue(f.proof(f.author,60000,f.now-4000));assertEquals(4000,f.game.snapshot().anchorPositionMillis());
        assertEquals(56000,f.game.snapshot().remainingMillis());assertEquals(QuizGame.Phase.PLAYING,f.game.snapshot().phase());assertEquals(0,f.ended);
    }
}
