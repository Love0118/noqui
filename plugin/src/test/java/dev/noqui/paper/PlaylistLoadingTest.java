package dev.noqui.paper;

import dev.noqui.bridge.BridgeClient;
import dev.noqui.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaylistLoadingTest {
    private void set(NoquiPlugin p,String name,Object value)throws Exception{var f=NoquiPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(p,value);}
    @Test void loadStartPersistsSelectionAndCancelledOrSupersededLoadsCannotStartLater()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var bridge=mock(BridgeClient.class);var config=new YamlConfiguration();
        var game=new QuizGame(new MediaBridge(){public CompletionStage<Media> prepare(Request r){throw new AssertionError();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}},Runnable::run,System::currentTimeMillis,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String m){}},40,false);
        set(plugin,"game",game);set(plugin,"bridge",bridge);set(plugin,"hud",mock(QuizHud.class));set(plugin,"optedOut",new HashSet<UUID>());
        doReturn(config).when(plugin).getConfig();doNothing().when(plugin).saveConfig();doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(plugin).main(any());
        var sender=mock(CommandSender.class);when(sender.isOp()).thenReturn(true);
        var tracks=List.of(new QuizGame.PlaylistTrack(Song.input("https://youtu.be/BaW_jenozKc","Private","writer"),new MediaBridge.Media(UUID.randomUUID().toString(),240000)));
        var future=new CompletableFuture<List<QuizGame.PlaylistTrack>>();when(bridge.playlistTracks("test")).thenReturn(future);
        try(var bukkit=mockStatic(Bukkit.class)){
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            plugin.loadPlaylist(sender,"test",0);plugin.stopQuiz();future.complete(tracks);assertFalse(game.started());assertFalse(config.getBoolean("quiz-active"));
            future=new CompletableFuture<>();when(bridge.playlistTracks("test")).thenReturn(future);plugin.loadPlaylist(sender,"test",0);
            plugin.start(0);future.complete(tracks);assertTrue(game.started());assertFalse(game.playlist());assertNull(config.getString("quiz-playlist"));game.stop();
            when(bridge.playlistTracks("test")).thenReturn(CompletableFuture.completedFuture(tracks));plugin.loadPlaylist(sender,"test",0);
            assertTrue(game.playlist());assertEquals(QuizGame.Phase.PREPARING,game.snapshot().phase());assertEquals("test",config.getString("quiz-playlist"));
            assertEquals(1,game.snapshot().registeredSongs());
        }
    }
    @Test void playlistCommandAndDirectLoadRejectNonOpBeforeNetworkAccess()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var bridge=mock(BridgeClient.class);set(plugin,"bridge",bridge);var sender=mock(CommandSender.class);
        plugin.onCommand(sender,null,"noqui",new String[]{"playlist"});assertThrows(IllegalArgumentException.class,()->plugin.loadPlaylist(sender,"test",0));verifyNoInteractions(bridge);
    }
}
