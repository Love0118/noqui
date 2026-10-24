package dev.noqui.paper;

import dev.noqui.bridge.BridgeClient;
import dev.noqui.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoquiPlaybackFlowTest {
    private static void set(NoquiPlugin plugin,String name,Object value)throws Exception{var f=NoquiPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(plugin,value);}
    @Test void confirmationRevalidatesCurrentSessionAndMembershipBeforeSpending()throws Exception {
        UUID player=UUID.randomUUID(),membership=UUID.randomUUID();long[] now={100000};var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);
        var members=new HashMap<UUID,BridgeClient.Member>();members.put(player,new BridgeClient.Member(player,membership,"Player",null));set(plugin,"members",members);
        var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){return CompletableFuture.completedFuture(new Media("ready",60000));}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        var game=new QuizGame(bridge,Runnable::run,()->now[0],new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String m){fail(m);}},115,false);set(plugin,"game",game);
        game.start(List.of(player));game.grant(player,1);game.submit(player,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Song","tag"));
        game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());var s=game.snapshot();now[0]=s.startsAt()+1;
        var progress=new BridgeClient.PlaybackProgress(player,membership,s.roundId(),s.playbackGeneration(),1,now[0]);
        plugin.playbackStarted(UUID.randomUUID(),progress);assertTrue(game.pending(player));
        members.remove(player);plugin.playbackStarted(s.session(),progress);assertTrue(game.pending(player));
        UUID replacement=UUID.randomUUID();members.put(player,new BridgeClient.Member(player,replacement,"Player",null));plugin.playbackStarted(s.session(),progress);assertTrue(game.pending(player));
        plugin.playbackStarted(s.session(),new BridgeClient.PlaybackProgress(player,replacement,s.roundId(),s.playbackGeneration(),1,now[0]));assertEquals(QuizGame.Phase.PLAYING,game.snapshot().phase());
        var started=game.snapshot();plugin.playbackStarted(s.session(),progress);assertEquals(started,game.snapshot());game.stop();assertEquals(0,game.credits(player));
    }
}
