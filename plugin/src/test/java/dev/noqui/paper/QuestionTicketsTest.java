package dev.noqui.paper;

import dev.noqui.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionTicketsTest {
    @TempDir Path directory;
    private final UUID id=UUID.randomUUID();
    private final Player player=mock(Player.class);
    private final PlayerInventory inventory=mock(PlayerInventory.class);
    private final ItemStack[] slots=new ItemStack[41];
    private CreditLedger ledger;
    private QuestionTickets tickets;
    private int opened;
    @BeforeEach void setup()throws Exception {
        ledger=new CreditLedger(directory.resolve("credits.json"));when(player.getUniqueId()).thenReturn(id);when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(slots.length);when(inventory.getStorageContents()).thenAnswer(c->Arrays.copyOf(slots,36));when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
        tickets=new QuestionTickets(ledger::balance,p->opened++,this::item);
    }
    private ItemStack item(UUID owner,int amount) {
        var item=mock(ItemStack.class);var meta=mock(ItemMeta.class);var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        values.put(new NamespacedKey("noqui","question_ticket_owner"),owner.toString());values.put(new NamespacedKey("noqui","question_ticket_version"),(byte)1);
        when(data.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        when(meta.getPersistentDataContainer()).thenReturn(data);when(item.getItemMeta()).thenReturn(meta);when(item.hasItemMeta()).thenReturn(true);when(item.getType()).thenReturn(Material.PAPER);
        int[] count={amount};when(item.getAmount()).thenAnswer(c->count[0]);doAnswer(c->{count[0]=c.getArgument(0);return null;}).when(item).setAmount(anyInt());return item;
    }
    private int physical(){return Arrays.stream(slots).filter(item->QuestionTickets.owned(item,id)).mapToInt(ItemStack::getAmount).sum();}
    private ItemStack stone(){var item=mock(ItemStack.class);when(item.getType()).thenReturn(Material.STONE);return item;}
    @Test void legacyBalancesBecomePhysicalAndDuplicateSnapshotsCannotInflateThem() {
        ledger.grant(id,3);tickets.refresh(player);assertEquals(3,physical());assertTrue(tickets.has(player));
        slots[5]=item(id,3);tickets.refresh(player);assertEquals(3,physical());assertNull(slots[5]);
        ledger.reserve(UUID.randomUUID(),id);tickets.refresh(player);assertEquals(2,physical());
    }
    @Test void existingTicketStylingIsCorrectedOnceWithoutChangingOwnershipOrQuantity() {
        ledger.grant(id,2);slots[0]=item(id,2);var meta=slots[0].getItemMeta();
        var name=new AtomicReference<>(net.kyori.adventure.text.Component.text("출제권",net.kyori.adventure.text.format.NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.ITALIC));
        var lore=new AtomicReference<List<net.kyori.adventure.text.Component>>(List.of(net.kyori.adventure.text.Component.text("우클릭: 노래 제출")));
        when(meta.displayName()).thenAnswer(c->name.get());doAnswer(c->{name.set(c.getArgument(0));return null;}).when(meta).displayName(any());
        when(meta.lore()).thenAnswer(c->lore.get());doAnswer(c->{lore.set(c.getArgument(0));return null;}).when(meta).lore(anyList());
        tickets.refresh(player);
        assertEquals(net.kyori.adventure.text.Component.text("출제권",net.kyori.adventure.text.format.NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false),name.get());
        assertEquals(2,lore.get().size());for(var line:lore.get())assertEquals(net.kyori.adventure.text.format.TextDecoration.State.FALSE,line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC));
        assertEquals(2,physical());assertEquals(2,ledger.balance(id));assertTrue(QuestionTickets.owned(slots[0],id));verify(player).saveData();
        clearInvocations(player,inventory,meta);tickets.refresh(player);verify(player,never()).saveData();verify(inventory,never()).setItem(anyInt(),any());verify(meta,never()).displayName(any());verify(meta,never()).lore(anyList());
    }
    @Test void fullInventoryLeavesDurablePendingDeliveryAndNeverDropsItems()throws Exception {
        Arrays.fill(slots,stone());ledger.grant(id,100);tickets.refresh(player);assertEquals(0,physical());assertEquals(100,ledger.balance(id));
        var recovered=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(recovered::balance,p->opened++,this::item);
        slots[8]=null;tickets.refresh(player);assertEquals(64,physical());slots[10]=null;tickets.refresh(player);assertEquals(100,physical());assertEquals(100,recovered.balance(id));
        verify(player,never()).getWorld();
    }
    @Test void acceptedReservationConsumesOneAndRepeatedRefundReturnsOne() {
        ledger.grant(id,3);tickets.refresh(player);UUID reservation=UUID.randomUUID();
        tickets.submit(player,()->ledger.reserve(reservation,id));assertEquals(2,physical());assertEquals(2,ledger.balance(id));
        ledger.refund(reservation);tickets.refresh(player);ledger.refund(reservation);tickets.refresh(player);assertEquals(3,physical());
    }
    @Test void rejectedInputAndUnsubmittedFormNeverBurnAndWriteFailureLeavesItemIntact()throws Exception {
        ledger.grant(id,1);tickets.refresh(player);assertThrows(IllegalArgumentException.class,()->tickets.submit(player,()->{throw new IllegalArgumentException("invalid");}));assertEquals(1,physical());
        Path blocked=directory.resolve("credits.json.tmp");Files.createDirectory(blocked);
        assertThrows(java.io.UncheckedIOException.class,()->tickets.submit(player,()->ledger.reserve(UUID.randomUUID(),id)));assertEquals(1,physical());assertEquals(1,ledger.balance(id));
    }
    @Test void paidGrantIsIdempotentBeforeAndAfterRestartAndIsImmediatelyDeliverable()throws Exception {
        ledger.giveOnce(id,1,"shop:one");tickets.refresh(player);ledger.giveOnce(id,1,"shop:one");tickets.refresh(player);assertEquals(1,physical());
        ledger=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(ledger::balance,p->opened++,this::item);ledger.giveOnce(id,1,"shop:one");tickets.refresh(player);assertEquals(1,physical());
    }
    @Test void snapshotSwapAndCrashRecoveryRestoreExactlyTheUnreservedPhysicalCount()throws Exception {
        ledger.grant(id,2);tickets.refresh(player);var stale=Arrays.copyOf(slots,slots.length);
        tickets.strip(player);assertEquals(0,physical());Arrays.fill(slots,null);tickets.refresh(player);assertEquals(2,physical());
        UUID reservation=UUID.randomUUID();tickets.submit(player,()->ledger.reserve(reservation,id));ledger.spend(reservation);assertEquals(1,physical());
        System.arraycopy(stale,0,slots,0,slots.length);tickets.refresh(player);assertEquals(1,physical());
        UUID interrupted=UUID.randomUUID();tickets.submit(player,()->ledger.reserve(interrupted,id));assertEquals(0,physical());
        ledger=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(ledger::balance,p->opened++,this::item);tickets.refresh(player);assertEquals(1,physical());
    }
    @Test void forgedOrdinaryPaperAndOtherOwnersCannotAuthorizeSubmission() {
        ledger.grant(id,1);var paper=mock(ItemStack.class);when(paper.getType()).thenReturn(Material.PAPER);slots[0]=paper;assertFalse(tickets.has(player));assertFalse(QuestionTickets.marked(paper));
        slots[0]=item(UUID.randomUUID(),1);assertFalse(tickets.has(player));assertThrows(IllegalArgumentException.class,()->tickets.submit(player,()->fail("not authorized")));
    }
    @Test void mainHandAndInventoryRightClickOpenExistingFormOncePerTickAndOffhandIsIgnored() {
        ledger.grant(id,2);tickets.refresh(player);var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getItem()).thenReturn(slots[0]);when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);tickets.interact(event);assertEquals(0,opened);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);tickets.interact(event);tickets.interact(event);assertEquals(1,opened);assertEquals(2,physical());
            var click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);when(click.getClickedInventory()).thenReturn(inventory);when(click.getCurrentItem()).thenReturn(slots[0]);when(click.getClick()).thenReturn(ClickType.RIGHT);when(click.getHotbarButton()).thenReturn(-1);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(11);tickets.inventory(click);verify(click).setCancelled(true);assertEquals(2,opened);assertEquals(2,physical());
        }
    }
    @Test void realCoreFailureAndCancelRefundPhysicalTicketsWhilePlaybackSpendSurvivesReturn() {
        var futures=new ArrayList<CompletableFuture<MediaBridge.Media>>();var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){var f=new CompletableFuture<Media>();futures.add(f);return f;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        long[] now={1000};var game=new QuizGame(bridge,Runnable::run,()->now[0],new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String e){}},ledger,115,0,false,true,false,new Random(1));game.start(List.of(id));ledger.grant(id,2);tickets.refresh(player);
        var song=Song.input("https://youtu.be/BaW_jenozKc","Song","");tickets.submit(player,()->game.submit(id,game.snapshot().revision(),song));assertEquals(1,physical());futures.getFirst().completeExceptionally(new IllegalStateException("conversion"));tickets.refresh(player);assertEquals(2,physical());
        tickets.submit(player,()->game.submit(id,game.snapshot().revision(),song));game.cancel(id);tickets.refresh(player);assertEquals(2,physical());
        tickets.submit(player,()->game.submit(id,game.snapshot().revision(),song));futures.getLast().complete(new MediaBridge.Media("ready",1000));game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());now[0]=game.snapshot().startsAt();game.tick();game.stop();tickets.refresh(player);assertEquals(1,physical());
    }
    private QuizGame game(){var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){return new CompletableFuture<>();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        var game=new QuizGame(bridge,Runnable::run,()->1000,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String e){}},ledger,115,0,false,true,false,new Random(1));game.start(List.of(id));return game;}
    private NoquiPlugin plugin(QuizGame game)throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);
        for(var entry:Map.of("game",(Object)game,"tickets",tickets,"drafts",new HashMap<UUID,Song>()).entrySet()){var field=NoquiPlugin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());}return plugin;
    }
    @Test void nonparticipantInventoryTicketRejoinsWithoutQueuedBrowserPromptReplacingSubmission()throws Exception {
        var game=game();game.leave(id);var plugin=plugin(game);when(player.isOnline()).thenReturn(true);
        tickets=new QuestionTickets(ledger::balance,plugin::openTicketForm,this::item);
        var members=new HashMap<UUID,dev.noqui.bridge.BridgeClient.Member>();var connections=mock(BrowserConnections.class);
        for(var entry:Map.of("tickets",(Object)tickets,"members",members,"connections",connections,"hud",mock(QuizHud.class)).entrySet()){
            var field=NoquiPlugin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());
        }
        plugin.dialogs=mock(NoquiDialogs.class);
        doAnswer(c->{members.put(id,new dev.noqui.bridge.BridgeClient.Member(id,UUID.randomUUID(),"player",null));return null;}).when(plugin).enter(player);
        var tasks=new ArrayDeque<Runnable>();doAnswer(c->{tasks.add(c.getArgument(0));return null;}).when(plugin).main(any());
        ledger.grant(id,1);tickets.refresh(player);
        var click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);when(click.getClickedInventory()).thenReturn(inventory);
        when(click.getCurrentItem()).thenReturn(slots[0]);when(click.getClick()).thenReturn(ClickType.RIGHT);when(click.getHotbarButton()).thenReturn(-1);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);bukkit.when(Bukkit::getCurrentTick).thenReturn(20);
            tickets.inventory(click);verify(click).setCancelled(true);assertFalse(game.participant(id));verifyNoInteractions(plugin.dialogs);assertEquals(1,tasks.size());
            tasks.remove().run();assertTrue(game.participant(id));assertNotNull(plugin.connectionEntry(id));
            var order=inOrder(player,plugin.dialogs);order.verify(player).closeInventory();order.verify(plugin.dialogs).submit(player);
            while(!tasks.isEmpty())tasks.remove().run();
            verify(connections,never()).open(any());verify(plugin.dialogs,times(1)).submit(player);assertEquals(1,physical());assertEquals(1,ledger.balance(id));
        }
    }
    @Test void actualPaidPortAcknowledgesOnlyAfterPhysicalDeliveryAndRetriesWithoutDuplicateGrant()throws Exception {
        var game=game();var plugin=plugin(game);Arrays.fill(slots,stone());
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);
            assertThrows(QuestionTickets.DeliveryPending.class,()->plugin.giveTickets(id,1,"purchase:one"));assertEquals(1,ledger.balance(id));assertEquals(0,physical());assertEquals(1,tickets.pending(player));
            assertThrows(QuestionTickets.DeliveryPending.class,()->plugin.giveTickets(id,1,"purchase:one"));assertEquals(1,ledger.balance(id));
            slots[20]=null;assertDoesNotThrow(()->plugin.giveTickets(id,1,"purchase:one"));assertEquals(1,physical());assertEquals(0,tickets.pending(player));
            plugin.giveTickets(id,1,"purchase:one");assertEquals(1,physical());
        }
    }
    @Test void physicalFormUsesCurrentEligibilityAfterRoomChangesButRejectsOldSessionsAndInvalidInput()throws Exception {
        var game=game();var plugin=plugin(game);ledger.grant(id,2);tickets.refresh(player);UUID session=game.snapshot().session();long oldRevision=game.snapshot().revision();
        game.grant(UUID.randomUUID(),1);assertNotEquals(oldRevision,game.snapshot().revision());plugin.submit(player,session,"https://youtu.be/BaW_jenozKc","Song","tag");assertEquals(1,physical());
        game.cancel(id);tickets.refresh(player);assertEquals(2,physical());
        assertThrows(IllegalArgumentException.class,()->plugin.submit(player,session,"invalid","Song","tag"));assertEquals(2,physical());
        game.stop();game.start(List.of(id));assertThrows(IllegalArgumentException.class,()->plugin.submit(player,session,"https://youtu.be/BaW_jenozKc","Song","tag"));assertEquals(2,physical());
    }
    @Test void actualCabinRecoverySnapshotDoesNotLoseOrResurrectSpentTickets() {
        var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(data.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));when(data.has(any(),any())).thenAnswer(c->values.containsKey(c.getArgument(0)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),any(),any());doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(data).remove(any());when(player.getPersistentDataContainer()).thenReturn(data);
        var world=mock(World.class);UUID worldId=UUID.randomUUID();when(world.getUID()).thenReturn(worldId);when(player.getLocation()).thenReturn(new Location(world,1,65,2));when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        when(inventory.getContents()).thenAnswer(c->slots.clone());doAnswer(c->{ItemStack[] contents=c.getArgument(0);System.arraycopy(contents,0,slots,0,contents.length);return null;}).when(inventory).setContents(any());
        var saved=new AtomicReference<ItemStack[]>();byte[] bytes={1,2,3};
        try(var stacks=mockStatic(ItemStack.class);var bukkit=mockStatic(Bukkit.class)) {
            stacks.when(()->ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(c->{ItemStack[] snapshot=c.getArgument(0);assertTrue(Arrays.stream(snapshot).noneMatch(QuestionTickets::marked));saved.set(snapshot.clone());return bytes;});
            stacks.when(()->ItemStack.deserializeItemsFromBytes(bytes)).thenAnswer(c->saved.get().clone());bukkit.when(()->Bukkit.getWorld(worldId)).thenReturn(world);
            ledger.grant(id,2);tickets.refresh(player);var recovery=new PlayerRecovery();recovery.beginMembership(player);tickets.strip(player);recovery.captureCabin(player);Arrays.fill(slots,null);tickets.refresh(player);assertEquals(2,physical());
            UUID spent=UUID.randomUUID();tickets.submit(player,()->ledger.reserve(spent,id));ledger.spend(spent);tickets.strip(player);recovery.releaseCabin(player,false);tickets.refresh(player);assertEquals(1,physical());assertEquals(1,ledger.balance(id));
        }
    }
}
