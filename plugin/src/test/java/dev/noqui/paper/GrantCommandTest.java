package dev.noqui.paper;

import com.google.gson.JsonParser;
import dev.noqui.api.LobbyService;
import dev.noqui.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrantCommandTest {
    @Test void grantsRefreshTheOwningMinigameImmediatelyAndThePublicApiIsIdempotent()throws Exception{
        try(var f=new Fixture()){
            UUID id=f.a.getUniqueId();f.bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(f.a);doReturn(false).when(f.plugin).ownsCabin(id);
            var provider=mock(dev.noqui.api.TicketService.Inventory.class);when(provider.ownsInventory(f.a)).thenReturn(true);
            var services=Bukkit.getServicesManager();when(services.getRegistrations(dev.noqui.api.TicketService.Inventory.class)).thenReturn(List.of(new org.bukkit.plugin.RegisteredServiceProvider<>(dev.noqui.api.TicketService.Inventory.class,provider,org.bukkit.plugin.ServicePriority.Normal,f.plugin)));
            f.run("noqui","give","Alpha","70");verify(provider).refreshTickets(f.a,70);assertTrue(f.messages.contains("Alpha 출제권 70장 지급"));
            var api=new TicketGrants(f.plugin);api.grant(f.a.getUniqueId(),2,"api-grant-test");api.grant(f.a.getUniqueId(),2,"api-grant-test");
            assertEquals(72,api.balance(f.a.getUniqueId()));verify(provider,times(2)).refreshTickets(f.a,72);
        }
    }
    @TempDir Path directory;
    private static void set(NoquiPlugin plugin,String name,Object value)throws Exception{var field=NoquiPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);}
    private final class Fixture implements AutoCloseable {
        final NoquiPlugin plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);
        final ConsoleCommandSender admin=mock(ConsoleCommandSender.class);
        final Player a=player("Alpha"),b=player("Beta"),c=player("Gamma");
        final List<Player> online=List.of(a,b,c);
        final OfflinePlayer offline=mock(OfflinePlayer.class);
        final LobbyService wallet=mock(LobbyService.class);
        final Map<UUID,Long> stars=new HashMap<>();
        final List<String> transactions=new ArrayList<>(),messages=new ArrayList<>();
        final Path file=directory.resolve("credits.json");final CreditLedger ledger=new CreditLedger(file);
        final QuizGame game;final MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);
        UUID failingMoney;Runnable changed=()->{};
        Fixture()throws Exception{
            when(admin.isOp()).thenReturn(true);doAnswer(call->{messages.add(PlainTextComponentSerializer.plainText().serialize(call.getArgument(0)));return null;}).when(admin).sendMessage(any(Component.class));
            when(offline.getUniqueId()).thenReturn(UUID.randomUUID());when(offline.getName()).thenReturn("Offline");
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(online);bukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[]{offline});
            for(var player:online)bukkit.when(()->Bukkit.getPlayerExact(player.getName())).thenReturn(player);
            var services=mock(org.bukkit.plugin.ServicesManager.class);bukkit.when(Bukkit::getServicesManager).thenReturn(services);when(services.load(LobbyService.class)).thenReturn(wallet);
            when(wallet.starlight(any())).thenAnswer(call->stars.getOrDefault(call.getArgument(0),0L));
            doAnswer(call->{UUID id=call.getArgument(0);String transaction=call.getArgument(1);long amount=call.getArgument(2);transactions.add(transaction);if(id.equals(failingMoney))throw new IllegalStateException("disk failure");stars.merge(id,amount,Math::addExact);return null;}).when(wallet).rewardStarlight(any(),anyString(),anyLong());
            var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request request){throw new UnsupportedOperationException();}public void cancel(UUID session,long revision){}public void publish(QuizGame.Snapshot snapshot){}};
            game=new QuizGame(bridge,Runnable::run,()->100000,new QuizGame.Output(){public void changed(QuizGame.Snapshot snapshot){changed.run();}public void error(UUID player,String message){}},ledger,115,0,false,true,false,new Random(1));set(plugin,"game",game);
        }
        Player player(String name){var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn(name);return player;}
        void run(String command,String... args){assertTrue(plugin.onCommand(admin,null,command,args));}
        public void close(){bukkit.close();}
    }
    @Test void ticketAllDefaultsToOneAndUsesDurableUniqueReceiptsForEachInvocationAndRecipient()throws Exception{
        try(var f=new Fixture()){
            f.run("noqui","give","@a");for(var player:f.online)assertEquals(1,f.game.credits(player.getUniqueId()));
            var first=JsonParser.parseString(Files.readString(f.file)).getAsJsonObject().getAsJsonObject("grants");assertEquals(3,first.size());
            f.run("noqui","give","@a","2");for(var player:f.online)assertEquals(3,f.game.credits(player.getUniqueId()));
            assertEquals(6,JsonParser.parseString(Files.readString(f.file)).getAsJsonObject().getAsJsonObject("grants").size());
            var reload=new CreditLedger(f.file);for(var entry:first.entrySet()){UUID id=UUID.fromString(entry.getValue().getAsJsonObject().get("player").getAsString());assertTrue(entry.getKey().endsWith(id.toString()));reload.giveOnce(id,1,entry.getKey());assertEquals(3,reload.balance(id));}
            assertTrue(f.messages.stream().anyMatch(m->m.contains("3/3명")));assertEquals(0,f.game.credits(f.offline.getUniqueId()));
        }
    }
    @Test void namedOnlineAndKnownOfflineGrantsPreserveDefaultsAndCounts()throws Exception{
        try(var f=new Fixture()){
            f.run("noqui","give","Alpha");f.run("noqui","give","offline","4");assertEquals(1,f.game.credits(f.a.getUniqueId()));assertEquals(4,f.game.credits(f.offline.getUniqueId()));
            f.run("money","give","Alpha");f.run("money","give","Offline","25");assertEquals(1,f.stars.get(f.a.getUniqueId()));assertEquals(25,f.stars.get(f.offline.getUniqueId()));
            assertTrue(f.messages.contains("Alpha ✦ 1 지급"));assertTrue(f.messages.contains("Offline 출제권 4장 지급"));
        }
    }
    @Test void moneyAllUsesIndependentTransactionsAndNeverGrantsAnswerPoints()throws Exception{
        try(var f=new Fixture()){
            f.run("money","give","@a");f.run("money","give","@a","10");for(var player:f.online)assertEquals(11,f.stars.get(player.getUniqueId()));
            assertEquals(6,new HashSet<>(f.transactions).size());for(String id:f.transactions)assertTrue(id.startsWith("money-give:"));
            verify(f.wallet,never()).rewardAnswerPoints(any(),anyString(),anyLong());assertFalse(f.stars.containsKey(f.offline.getUniqueId()));
        }
    }
    @Test void ordinaryMoneyRemainsPublicButPermissionNodesCannotAuthorizeGrants()throws Exception{
        try(var f=new Fixture()){
            when(f.a.isOp()).thenReturn(false);when(f.a.hasPermission(anyString())).thenReturn(true);
            f.plugin.onCommand(f.a,null,"money",new String[]{});verify(f.wallet).showMoney(f.a);clearInvocations(f.wallet);
            f.plugin.onCommand(f.a,null,"money",new String[]{"give","@a"});f.plugin.onCommand(f.a,null,"noqui",new String[]{"give","@a"});
            verifyNoInteractions(f.wallet);for(var player:f.online)assertEquals(0,f.game.credits(player.getUniqueId()));
            assertTrue(f.plugin.onTabComplete(f.a,null,"money",new String[]{""}).isEmpty());
        }
    }
    @Test void invalidSyntaxSelectorsAmountsAndUnknownNamesDoNotMutateAnyone()throws Exception{
        try(var f=new Fixture()){
            for(String command:List.of("money","noqui")){
                for(String amount:List.of("0","-1","1.5","word","9223372036854775808"))f.run(command,"give","@a",amount);
                f.run(command,"give");f.run(command,"give","@p");f.run(command,"give","Unknown");f.run(command,"give","@a","1","extra");
            }
            f.run("noqui","give","@a","1001");verify(f.wallet,never()).rewardStarlight(any(),anyString(),anyLong());
            for(var player:f.online)assertEquals(0,f.game.credits(player.getUniqueId()));assertFalse(Files.exists(f.file));
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());f.run("money","give","@a");assertTrue(f.messages.getLast().contains("접속자가 없습니다"));
        }
    }
    @Test void overflowPreflightIncludesReservedTicketsAndRejectsWholeTargetSet()throws Exception{
        try(var f=new Fixture()){
            f.ledger.giveOnce(f.b.getUniqueId(),CreditLedger.MAX_BALANCE,"seed");f.ledger.reserve(UUID.randomUUID(),f.b.getUniqueId());
            f.run("noqui","give","@a");assertEquals(0,f.game.credits(f.a.getUniqueId()));assertEquals(CreditLedger.MAX_BALANCE-1,f.game.credits(f.b.getUniqueId()));
            f.stars.put(f.b.getUniqueId(),Long.MAX_VALUE);f.run("money","give","@a");verify(f.wallet,never()).rewardStarlight(any(),anyString(),anyLong());assertFalse(f.stars.containsKey(f.a.getUniqueId()));
        }
    }
    @Test void partialWalletFailureReportsOnlyFailedRecipientsWithoutReversingSuccess()throws Exception{
        try(var f=new Fixture()){
            f.failingMoney=f.b.getUniqueId();f.run("money","give","@a","7");
            assertEquals(7,f.stars.get(f.a.getUniqueId()));assertFalse(f.stars.containsKey(f.b.getUniqueId()));assertEquals(7,f.stars.get(f.c.getUniqueId()));
            assertTrue(f.messages.contains("✦ 7씩 지급 완료 2/3명"));assertTrue(f.messages.contains("지급 실패: Beta. 해당 대상만 다시 시도하세요."));
        }
    }
    @Test void ticketDiskFailureAfterFirstCommitReportsAccuratePartialSuccess()throws Exception{
        try(var f=new Fixture()){
            f.game.start(f.online.stream().map(Player::getUniqueId).toList());var calls=new AtomicInteger();
            f.changed=()->{if(calls.incrementAndGet()==1)try{Files.createDirectory(f.file.resolveSibling("credits.json.tmp"));}catch(Exception error){throw new RuntimeException(error);}};
            f.run("noqui","give","@a");assertEquals(1,f.game.credits(f.a.getUniqueId()));assertEquals(0,f.game.credits(f.b.getUniqueId()));assertEquals(0,f.game.credits(f.c.getUniqueId()));
            assertTrue(f.messages.contains("출제권 1장씩 지급 완료 1/3명"));assertTrue(f.messages.contains("지급 실패: Beta, Gamma. 해당 대상만 다시 시도하세요."));
        }
    }
    @Test void postCommitPresentationFailureIsReportedAsPendingRatherThanFailedCredit()throws Exception{
        try(var f=new Fixture()){
            f.game.start(List.of(f.a.getUniqueId()));f.changed=()->{throw new IllegalStateException("view failed");};
            f.run("noqui","give","Alpha");assertEquals(1,f.game.credits(f.a.getUniqueId()));assertTrue(f.messages.contains("Alpha 출제권 1장 지급 대기"));
        }
    }
    @Test void completionUsesCommandIdentityAndOffersOnlineNamesAndAllSelector()throws Exception{
        try(var f=new Fixture()){
            var command=mock(Command.class);when(command.getName()).thenReturn("money");
            assertEquals(List.of("give"),f.plugin.onTabComplete(f.admin,command,"noqui:money",new String[]{""}));
            for(String alias:List.of("money","noqui")){assertEquals(List.of("@a","Alpha","Beta","Gamma"),f.plugin.onTabComplete(f.admin,null,alias,new String[]{"give",""}));assertEquals(List.of("Alpha"),f.plugin.onTabComplete(f.admin,null,alias,new String[]{"give","Al"}));}
            assertEquals(List.of("1","10","100","1000"),f.plugin.onTabComplete(f.admin,null,"money",new String[]{"give","@a",""}));
        }
    }
}
