package dev.noqui.core;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistGameTest {
    private long now=100000;
    private final UUID player=UUID.randomUUID(),other=UUID.randomUUID();
    private final MediaBridge bridge=new MediaBridge(){
        public CompletionStage<Media> prepare(Request r){fail("Playlist must not ingest YouTube");return null;}
        public void cancel(UUID s,long r){fail("Playlist must not cancel submission jobs");}
        public void publish(QuizGame.Snapshot s){}
    };
    private QuizGame game() {return new QuizGame(bridge,Runnable::run,()->now,new QuizGame.Output(){
        public void changed(QuizGame.Snapshot s){}public void error(UUID p,String m){fail(m);}
    },new CreditLedger(),120,0,false,true,false,new Random(42));}
    private List<QuizGame.PlaylistTrack> tracks(int count) {
        var tracks=new ArrayList<QuizGame.PlaylistTrack>();
        for(int i=0;i<count;i++)tracks.add(new QuizGame.PlaylistTrack(new Song("https://youtu.be/BaW_jenozKc","Track"+i,List.of("writer")),new MediaBridge.Media(UUID.randomUUID().toString(),240000)));
        return tracks;
    }
    private void play(QuizGame game) {
        game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());now=game.snapshot().startsAt()+1;
        assertTrue(game.confirmPlayback(player,game.snapshot().roundId(),game.snapshot().playbackGeneration(),1,now));
    }
    private void finish(QuizGame game) {game.forceSkip(game.snapshot().roundId());now+=1000;game.tick();}
    @Test void shufflesLargePlaylistWithoutCreditsOrPresenterAndEndsAtExhaustion() {
        var game=game();var tracks=tracks(541);game.startPlaylist(List.of(player,other),tracks,0);
        assertTrue(game.playlist());assertEquals(541,game.snapshot().registeredSongs());assertNull(game.snapshot().presenter());
        assertFalse(game.canSubmit(player));assertFalse(game.pending(player));
        assertThrows(IllegalArgumentException.class,()->game.submit(player,game.snapshot().revision(),tracks.getFirst().song()));
        var order=new ArrayList<Song>();
        while(game.started()) {
            assertEquals(QuizGame.Phase.PREPARING,game.snapshot().phase());order.add(game.snapshot().song());play(game);
            assertEquals(120000,game.snapshot().clipEndMillis());assertTrue(game.answer(player,game.snapshot().song().title()));finish(game);
        }
        assertEquals(541,new HashSet<>(order).size());assertNotEquals(tracks.stream().map(QuizGame.PlaylistTrack::song).toList(),order);
        assertEquals(0,game.credits(player));assertEquals(541,game.snapshot().scores().get(player));
        game.start(List.of(player));assertFalse(game.playlist());game.grant(player,1);assertTrue(game.canSubmit(player));
    }
    @Test void limitsTrimExistingOrderAndNeverRestoreDiscardedTracks() {
        var a=game();var b=game();var tracks=tracks(15);a.startPlaylist(List.of(player),tracks,0);b.startPlaylist(List.of(player),tracks,0);
        var expected=new ArrayList<Song>();for(int i=0;i<3;i++){expected.add(b.snapshot().song());play(b);finish(b);}
        a.limit(3);assertEquals(3,a.snapshot().registeredSongs());a.limit(0);assertEquals(3,a.snapshot().registeredSongs());
        var actual=new ArrayList<Song>();while(a.started()){actual.add(a.snapshot().song());play(a);finish(a);}
        assertEquals(expected,actual);assertEquals(0,a.credits(player));
    }
    @Test void leaveStopAndLatePlaybackCannotRemoveTracksOrSpendTickets() {
        var game=game();game.grant(player,2);game.startPlaylist(List.of(player,other),tracks(4),2);
        game.leave(other);assertEquals(2,game.snapshot().registeredSongs());assertNull(game.snapshot().presenter());
        play(game);assertThrows(IllegalArgumentException.class,()->game.limit(0-1));var state=game.snapshot();game.stop();
        assertFalse(game.confirmPlayback(player,state.roundId(),state.playbackGeneration(),20,now));assertEquals(2,game.credits(player));
    }
}
