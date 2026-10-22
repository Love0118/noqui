package dev.noqui.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import dev.noqui.core.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoquiCommandTest {
    @Test void joiningAnActiveQuizSchedulesNativeConnectionBeforeAnyPresenterForm()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){throw new UnsupportedOperationException();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        var game=new QuizGame(bridge,Runnable::run,System::currentTimeMillis,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String message){}},115,false);game.start(null,List.of());
        var gameField=NoquiPlugin.class.getDeclaredField("game");gameField.setAccessible(true);gameField.set(plugin,game);
        var connections=mock(BrowserConnections.class);var connectionField=NoquiPlugin.class.getDeclaredField("connections");connectionField.setAccessible(true);connectionField.set(plugin,connections);
        var entry=new BrowserConnections.Entry(game.snapshot().session(),UUID.randomUUID());doReturn(entry).when(plugin).connectionEntry(id);doReturn(null).when(plugin).defense();
        doNothing().when(plugin).enter(player);var scheduled=new ArrayDeque<Runnable>();doAnswer(call->{scheduled.add(call.getArgument(0));return null;}).when(plugin).main(any());
        assertTrue(plugin.routeJoin(player));assertEquals(id,game.snapshot().presenter());verify(connections,never()).open(any());
        scheduled.remove().run();verify(connections).open(id);assertTrue(scheduled.isEmpty());
    }
    @Test void managementRequiresActualOpEvenWithWildcardPermission() {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);when(player.isOp()).thenReturn(false);
        try(var bukkit=mockStatic(Bukkit.class)) {
            for(String command:new String[]{"start","stop","skip","hint","give","status"})assertTrue(plugin.onCommand(player,null,"noqui",new String[]{command}));
            assertTrue(plugin.onTabComplete(player,null,"noqui",new String[]{""}).isEmpty());bukkit.verifyNoInteractions();
            verify(player,times(6)).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }
}
