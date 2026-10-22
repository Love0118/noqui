package dev.noqui.paper;

import dev.noqui.api.DefenseService;
import dev.noqui.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoquiCommandTest {
    private void set(NoquiPlugin plugin,String field,Object value)throws Exception{var f=NoquiPlugin.class.getDeclaredField(field);f.setAccessible(true);f.set(plugin,value);}
    private QuizGame game(){var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){throw new UnsupportedOperationException();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        return new QuizGame(bridge,Runnable::run,System::currentTimeMillis,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String m){}},115,false);}
    @Test void joiningActiveQuizSchedulesNativeConnectionBeforeAnySubmissionForm()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        var game=game();game.start(List.of());set(plugin,"game",game);set(plugin,"hud",mock(QuizHud.class));
        var connections=mock(BrowserConnections.class);set(plugin,"connections",connections);var entry=new BrowserConnections.Entry(game.snapshot().session(),UUID.randomUUID());
        doReturn(entry).when(plugin).connectionEntry(id);doNothing().when(plugin).enter(player);var tasks=new ArrayDeque<Runnable>();doAnswer(c->{tasks.add(c.getArgument(0));return null;}).when(plugin).main(any());
        assertTrue(plugin.routeJoin(player));assertTrue(game.participant(id));assertNull(game.snapshot().presenter());verify(connections,never()).open(any());tasks.remove().run();verify(connections).open(id);
    }
    @Test void concurrentMemberRouteDoesNotEnterCabinOrChangeDefenseState()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);
        var game=game();game.start(List.of(id));set(plugin,"game",game);var hud=mock(QuizHud.class);set(plugin,"hud",hud);doReturn(true).when(plugin).defenseActive(p);
        assertTrue(plugin.routeJoin(p));verify(hud).setImmersive(p,false);verify(p,never()).getInventory();verify(p,never()).setGameMode(any());verify(p,never()).teleport(any(org.bukkit.Location.class));
    }
    @Test void cabinCompassCallsDirectStartAndNeverLeavesQuizOrOpensDefenseMenu()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.isOnline()).thenReturn(true);
        doReturn(true).when(plugin).ownsCabin(id);var defense=mock(DefenseService.class);doReturn(defense).when(plugin).defense();plugin.toDefense(p);
        verify(defense).startGame(p);verify(defense,never()).sendLobby(p);verify(defense,never()).openMenu(p);verify(plugin,never()).leave(p);
    }
    @Test void managementRequiresActualOpWhileParticipantsMayVoteAndLeave()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);when(player.hasPermission(anyString())).thenReturn(true);when(player.isOp()).thenReturn(false);set(plugin,"game",game());
        try(var bukkit=mockStatic(Bukkit.class)) {
            for(String command:List.of("start","stop","hint","give","status","pause","resume"))assertTrue(plugin.onCommand(player,null,"noqui",new String[]{command}));
            assertEquals(List.of("skip","leave"),plugin.onTabComplete(player,null,"noqui",new String[]{""}));bukkit.verifyNoInteractions();verify(player,times(7)).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }
    @Test void cabinReleaseFailureRollsBackOwnershipWithoutLeavingMembership()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);
        var owners=new HashSet<>(Set.of(id));var handoffs=new HashSet<UUID>();var recovery=mock(PlayerRecovery.class);var hud=mock(QuizHud.class);
        set(plugin,"cabinOwners",owners);set(plugin,"handoffs",handoffs);set(plugin,"recovery",recovery);set(plugin,"hud",hud);
        doThrow(new IllegalStateException("restore failed")).when(recovery).releaseCabin(p,false);
        assertThrows(IllegalStateException.class,()->plugin.prepareDefense(p));assertTrue(owners.contains(id));assertTrue(handoffs.isEmpty());
        verify(hud).setImmersive(p,false);verify(hud).setImmersive(p,true);verify(plugin,never()).leave(p);verify(p,never()).teleport(any(org.bukkit.Location.class));
    }
    @Test void startEntersAllOnlinePlayersAndPassesUnlimitedOrExplicitLimit()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var game=game();set(plugin,"game",game);set(plugin,"hud",mock(QuizHud.class));
        var idle=mock(Player.class);var defender=mock(Player.class);var otherWorld=mock(Player.class);
        for(Player p:List.of(idle,defender,otherWorld)){when(p.getUniqueId()).thenReturn(UUID.randomUUID());doNothing().when(plugin).enter(p);}
        var config=new org.bukkit.configuration.file.YamlConfiguration();doReturn(config).when(plugin).getConfig();doNothing().when(plugin).saveConfig();
        doReturn(null).when(plugin).connectionEntry(any());doNothing().when(plugin).main(any());
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(idle,defender,otherWorld));plugin.start(200);
            for(Player p:List.of(idle,defender,otherWorld)){verify(plugin).enter(p);assertTrue(game.participant(p.getUniqueId()));}
            assertEquals(200,game.snapshot().totalRounds());assertTrue(config.getBoolean("quiz-active"));assertEquals(200,config.getInt("quiz-limit"));
        }
    }
    @Test void startCommandDefaultsUnlimitedAndLimitAndMoneyUseCurrentServices()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);doNothing().when(plugin).start(anyInt());var sender=mock(Player.class);when(sender.isOp()).thenReturn(true);
        plugin.onCommand(sender,null,"noqui",new String[]{"start"});verify(plugin).start(0);plugin.onCommand(sender,null,"noqui",new String[]{"start","200"});verify(plugin).start(200);
        var services=mock(org.bukkit.plugin.ServicesManager.class);var wallet=mock(dev.noqui.api.LobbyService.class);when(services.load(dev.noqui.api.LobbyService.class)).thenReturn(wallet);
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::getServicesManager).thenReturn(services);plugin.onCommand(sender,null,"money",new String[]{});verify(wallet).showMoney(sender);}
    }
}
