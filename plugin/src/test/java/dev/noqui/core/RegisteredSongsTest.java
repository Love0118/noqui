package dev.noqui.core;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredSongsTest {
    private static final class Fixture {
        final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        final List<CompletableFuture<MediaBridge.Media>> media=new ArrayList<>();long now=100000;
        final List<String> errors=new ArrayList<>();
        final QuizGame game;
        Fixture(){
            var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){var future=new CompletableFuture<Media>();media.add(future);return future;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){assertTrue(s.round()<=s.registeredSongs());}};
            game=new QuizGame(bridge,Runnable::run,()->now,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String m){errors.add(m);}},115,false);
            game.start(List.of(a,b,c),200);for(UUID id:List.of(a,b,c))game.grant(id,10);
        }
        void submit(UUID id){game.submit(id,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Song","tag"));}
        void ready(int index){media.get(index).complete(new MediaBridge.Media(UUID.randomUUID().toString(),60000));}
        void play(){game.browsersReady(game.snapshot().revision(),true,false);game.controlAccepted(game.snapshot().revision());now=game.snapshot().startsAt()+1;assertTrue(game.confirmPlayback(a,game.snapshot().roundId(),game.snapshot().playbackGeneration(),1,now));}
        void display(int round,int registered){assertEquals(round,game.snapshot().round());assertEquals(registered,game.snapshot().registeredSongs());}
    }
    @Test void playedSongsRemainRegisteredAndConfigurationIsNotTheDenominator() {
        var f=new Fixture();f.display(0,0);f.submit(f.a);f.submit(f.b);f.submit(f.c);f.display(1,3);assertEquals(200,f.game.snapshot().totalRounds());
        f.ready(0);f.play();f.game.forceSkip(f.game.snapshot().roundId());f.display(1,3);f.now+=1000;f.game.tick();f.display(2,3);
        f.game.cancel(f.b);f.display(2,2);f.game.cancel(f.c);f.display(1,1);f.game.limit(0);f.display(1,1);
        f.game.stop();f.display(1,1);f.game.start(List.of(f.a),200);f.display(0,0);
    }
    @Test void cancellationFailureLeaveAndLimitTrimmingRemoveOnlyUnplayedRegistrations() {
        var f=new Fixture();f.submit(f.a);f.submit(f.b);f.submit(f.c);f.game.cancel(f.b);f.display(1,2);
        f.media.get(2).completeExceptionally(new IllegalStateException("conversion failed"));f.display(1,1);
        f.game.leave(f.a);f.display(0,0);f.game.join(f.a);f.submit(f.a);f.submit(f.b);f.submit(f.c);f.game.limit(1);f.display(1,1);
        assertEquals(10,f.game.credits(f.b));assertEquals(10,f.game.credits(f.c));f.game.forceSkip(f.game.snapshot().roundId());f.display(0,0);
        assertTrue(f.game.canSubmit(f.b));f.submit(f.b);f.display(0,1);f.now+=1000;f.game.tick();f.display(1,1);
    }
    @Test void alreadyPreparedNextSongStartsCountdownImmediatelyWithOnlyOneReadyBrowser() {
        var f=new Fixture();f.submit(f.a);f.submit(f.b);assertEquals(2,f.media.size());f.ready(0);f.ready(1);f.play();
        f.game.forceSkip(f.game.snapshot().roundId());f.now+=999;f.game.tick();assertEquals(QuizGame.Phase.REVEAL,f.game.snapshot().phase());
        f.now++;f.game.tick();assertEquals(QuizGame.Phase.PREPARING,f.game.snapshot().phase());assertEquals(0,f.game.snapshot().endsAt());
        f.game.browsersReady(f.game.snapshot().revision(),false,false);assertEquals(QuizGame.Phase.PREPARING,f.game.snapshot().phase());
        f.game.browsersReady(f.game.snapshot().revision(),true,false);assertEquals(QuizGame.Phase.COUNTDOWN,f.game.snapshot().phase());assertEquals(f.now+4000,f.game.snapshot().startsAt());
        f.game.controlAccepted(f.game.snapshot().revision());f.now+=120000;f.game.tick();assertEquals(QuizGame.Phase.COUNTDOWN,f.game.snapshot().phase());assertTrue(f.game.pending(f.b));assertFalse(f.game.answer(f.a,"Song"));f.display(2,2);
    }
}
