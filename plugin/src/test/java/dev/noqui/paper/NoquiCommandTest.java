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
    private void set(NoquiPlugin plugin,String field,Object value)throws Exception{var f=NoquiPlugin.class.getDeclaredField(field);f.setAccessible(true);f.set(plugin,value);var opts=NoquiPlugin.class.getDeclaredField("optedOut");opts.setAccessible(true);if(opts.get(plugin)==null)opts.set(plugin,new HashSet<UUID>());}
    private QuizGame game(){var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){throw new UnsupportedOperationException();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        return new QuizGame(bridge,Runnable::run,System::currentTimeMillis,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String m){}},115,false);}
    @Test void proxySubmissionPreservesDefenseAndZeroBalanceCannotRejoinOrSubmit()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);var game=game();game.start(List.of(id));set(plugin,"game",game);plugin.dialogs=mock(NoquiDialogs.class);
        assertThrows(IllegalArgumentException.class,()->plugin.openProxySubmission(player));verifyNoInteractions(plugin.dialogs);game.grant(id,1);plugin.openProxySubmission(player);verify(plugin.dialogs).submit(player);verify(player,never()).teleport(any(org.bukkit.Location.class));verify(plugin,never()).routeJoin(player);verify(plugin,never()).defense();
    }
    @Test void proxyLobbyTravelUsesNormalDefenseCleanupAndHonorsRefusedLeave()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);var service=mock(DefenseService.class);doReturn(service).when(plugin).defense();doReturn(true).when(plugin).routeJoin(player);
        set(plugin,"optedOut",new HashSet<UUID>());
        when(service.active(player)).thenReturn(true);plugin.visitCabin(player);verify(service).leaveGame(player);verify(plugin,never()).routeJoin(player);
        when(service.active(player)).thenReturn(true,false);plugin.visitCabin(player);verify(service,times(2)).leaveGame(player);verify(plugin).routeJoin(player);verify(player,never()).teleport(any(org.bukkit.Location.class));
    }
    @Test void explicitLeaveBlocksAutomaticRejoinUntilAnIntentionalProxyAction()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);var game=game();game.start(List.of(id));set(plugin,"game",game);
        set(plugin,"members",new HashMap<UUID,Object>());set(plugin,"drafts",new HashMap<UUID,Song>());set(plugin,"handoffs",new HashSet<UUID>());set(plugin,"cabinOwners",new HashSet<UUID>());set(plugin,"hud",mock(QuizHud.class));set(plugin,"recovery",mock(PlayerRecovery.class));
        plugin.leave(player);assertFalse(game.participant(id));assertFalse(plugin.routeJoin(player));verify(plugin,never()).enter(player);
        doReturn(null).when(plugin).defense();doNothing().when(plugin).enter(player);plugin.visitCabin(player);assertTrue(game.participant(id));verify(plugin).enter(player);
    }
    @Test void joiningActiveQuizWaitsForBackendPresenceBeforeAnyBrowserPrompt()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        var game=game();game.start(List.of());set(plugin,"game",game);set(plugin,"hud",mock(QuizHud.class));
        var connections=mock(BrowserConnections.class);set(plugin,"connections",connections);var entry=new BrowserConnections.Entry(game.snapshot().session(),UUID.randomUUID());
        doReturn(entry).when(plugin).connectionEntry(id);doNothing().when(plugin).enter(player);var tasks=new ArrayDeque<Runnable>();doAnswer(c->{tasks.add(c.getArgument(0));return null;}).when(plugin).main(any());
        assertTrue(plugin.routeJoin(player));assertTrue(game.participant(id));assertNull(game.snapshot().presenter());verify(connections,never()).open(any());assertTrue(tasks.isEmpty());
    }
    @Test void concurrentMemberRouteDoesNotEnterCabinOrChangeDefenseState()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);
        var game=game();game.start(List.of(id));set(plugin,"game",game);var hud=mock(QuizHud.class);set(plugin,"hud",hud);doReturn(true).when(plugin).defenseActive(p);
        assertTrue(plugin.routeJoin(p));verify(hud).setImmersive(p,false);verify(p,never()).getInventory();verify(p,never()).setGameMode(any());verify(p,never()).teleport(any(org.bukkit.Location.class));
    }
    @Test void cabinCompassOpensExistingMenuWithoutStartingOrLeavingQuiz()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.isOnline()).thenReturn(true);
        doReturn(true).when(plugin).ownsCabin(id);var defense=mock(DefenseService.class);doReturn(defense).when(plugin).defense();plugin.openMinigames(p);
        verify(defense).openMenu(p);verify(defense,never()).sendLobby(p);verify(defense,never()).startGame(p);verify(plugin,never()).leave(p);
    }
    @Test void managementRequiresActualOpWhileParticipantsMayVoteAndLeave()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var player=mock(Player.class);when(player.hasPermission(anyString())).thenReturn(true);when(player.isOp()).thenReturn(false);set(plugin,"game",game());
        try(var bukkit=mockStatic(Bukkit.class)) {
            for(String command:List.of("start","stop","hint","give","status","pause","resume"))assertTrue(plugin.onCommand(player,null,"noqui",new String[]{command}));
            assertEquals(List.of("skip","bgm","connect","leave"),plugin.onTabComplete(player,null,"noqui",new String[]{""}));bukkit.verifyNoInteractions();verify(player,times(7)).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }
    @Test void cabinReleaseFailureRollsBackOwnershipWithoutLeavingMembership()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);
        var owners=new HashSet<>(Set.of(id));var handoffs=new HashSet<UUID>();var recovery=mock(PlayerRecovery.class);var hud=mock(QuizHud.class);
        set(plugin,"cabinOwners",owners);set(plugin,"handoffs",handoffs);set(plugin,"recovery",recovery);set(plugin,"hud",hud);
        var vault=mock(CabinInventoryVault.class);when(vault.loaded(p)).thenReturn(true);set(plugin,"cabinInventory",vault);var lobby=mock(dev.noqui.lobby.LobbyFeatures.class);set(plugin,"lobbyFeatures",lobby);
        doThrow(new IllegalStateException("restore failed")).when(recovery).releaseCabin(p,false);
        assertThrows(IllegalStateException.class,()->plugin.prepareDefense(p));assertTrue(owners.contains(id));assertTrue(handoffs.isEmpty());
        verify(hud).setImmersive(p,false);verify(hud).setImmersive(p,true);verify(plugin,never()).leave(p);verify(p,never()).teleport(any(org.bukkit.Location.class));
        var order=inOrder(lobby,vault,recovery);order.verify(lobby).prepareInventoryChange(p);order.verify(vault).leave(p);order.verify(recovery).releaseCabin(p,false);order.verify(vault).restore(p);order.verify(lobby).inventoryChanged(p);
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
    @Test void participantBgmCommandTogglesPreferenceWithoutOpeningAnotherDialog()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);
        var game=game();game.start(List.of(id));set(plugin,"game",game);var recovery=mock(PlayerRecovery.class);set(plugin,"recovery",recovery);
        when(recovery.bgmMuted(p)).thenReturn(false,true);plugin.onCommand(p,null,"noqui",new String[]{"bgm"});plugin.onCommand(p,null,"noqui",new String[]{"bgm"});
        verify(recovery,times(2)).toggleBgm(p);verify(p).sendMessage(NoquiPlugin.text("BGM ON"));verify(p).sendMessage(NoquiPlugin.text("BGM OFF"));verify(p,never()).showDialog(any());
        game.leave(id);plugin.onCommand(p,null,"noqui",new String[]{"bgm"});verify(recovery,times(2)).toggleBgm(p);
    }
    @Test void onlyExplicitAdminCommandOpensManagementPanel()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);when(p.isOp()).thenReturn(true);set(plugin,"game",game());plugin.dialogs=mock(NoquiDialogs.class);
        plugin.onCommand(p,null,"noqui",new String[]{"unknown"});verifyNoInteractions(plugin.dialogs);
        plugin.onCommand(p,null,"noqui",new String[]{"admin"});verify(plugin.dialogs).admin(p);
    }
    @Test void ticketFormOpensNextTickOnlyForSameOnlineSessionMembershipAndPhysicalTicket()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.isOnline()).thenReturn(true);
        var game=game();game.start(List.of(id));game.grant(id,1);set(plugin,"game",game);set(plugin,"cabinOwners",new HashSet<>(Set.of(id)));var tickets=mock(QuestionTickets.class);when(tickets.has(p)).thenReturn(true);set(plugin,"tickets",tickets);plugin.dialogs=mock(NoquiDialogs.class);
        var entry=new java.util.concurrent.atomic.AtomicReference<>(new BrowserConnections.Entry(game.snapshot().session(),UUID.randomUUID()));doAnswer(c->entry.get()).when(plugin).connectionEntry(id);
        var tasks=new ArrayDeque<Runnable>();doAnswer(c->{tasks.add(c.getArgument(0));return null;}).when(plugin).main(any());
        plugin.openTicketForm(p);verify(p,never()).closeInventory();verifyNoInteractions(plugin.dialogs);tasks.remove().run();verify(p).closeInventory();verify(plugin.dialogs).submit(p);
        plugin.openTicketForm(p);entry.set(null);tasks.remove().run();verify(plugin.dialogs,times(1)).submit(p);
        plugin.openTicketForm(p);when(p.isOnline()).thenReturn(false);tasks.remove().run();verify(plugin.dialogs,times(1)).submit(p);
    }
    @Test void controlCompassAndTicketNamesAndLoreDisableItalicsWhilePreservingColors()throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);doReturn("noqui").when(plugin).namespace();
        try(var items=mockConstruction(org.bukkit.inventory.ItemStack.class,(item,context)->{
            var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);var data=mock(org.bukkit.persistence.PersistentDataContainer.class);
            when(data.getOrDefault(any(),any(),any())).thenAnswer(c->c.getArgument(2));when(meta.getPersistentDataContainer()).thenReturn(data);
            when(item.getItemMeta()).thenReturn(meta);when(item.hasItemMeta()).thenReturn(true);when(item.getType()).thenReturn((org.bukkit.Material)context.arguments().getFirst());
            doAnswer(c->{assertEquals(net.kyori.adventure.text.format.TextDecoration.State.FALSE,c.getArgument(0,net.kyori.adventure.text.Component.class).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC));return null;}).when(meta).displayName(any());
            doAnswer(c->{List<net.kyori.adventure.text.Component> lore=c.getArgument(0);for(var line:lore)assertEquals(net.kyori.adventure.text.format.TextDecoration.State.FALSE,line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC));return null;}).when(meta).lore(anyList());
        })) {
            var compass=plugin.minigameCompass();verify(compass.getItemMeta()).displayName(net.kyori.adventure.text.Component.text("미니게임",net.kyori.adventure.text.format.NamedTextColor.AQUA).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
            var controlFactory=NoquiPlugin.class.getDeclaredMethod("tool",org.bukkit.Material.class,String.class,String.class,String.class);controlFactory.setAccessible(true);
            var controls=(org.bukkit.inventory.ItemStack)controlFactory.invoke(plugin,org.bukkit.Material.PAPER,"controls","노래퀴즈","우클릭 또는 F: 노래퀴즈");
            verify(controls.getItemMeta()).displayName(net.kyori.adventure.text.Component.text("노래퀴즈").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
            var preview=QuestionTickets.displayItem();verify(preview.getItemMeta()).displayName(net.kyori.adventure.text.Component.text("출제권",net.kyori.adventure.text.format.NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
            assertFalse(QuestionTickets.marked(preview));
        }
    }
}
