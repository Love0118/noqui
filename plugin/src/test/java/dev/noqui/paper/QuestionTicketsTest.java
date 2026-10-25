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
    private final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
    private final AtomicReference<ItemStack> cursor=new AtomicReference<>();
    private int opened;
    @Test void inventorySaveDeathPreservesPhysicalTicketPositionsAndBalance(){
        ledger.grant(id,3);tickets.refresh(player);var original=slots[0];
        var death=mock(org.bukkit.event.entity.PlayerDeathEvent.class);when(death.getEntity()).thenReturn(player);when(death.getKeepInventory()).thenReturn(true);when(death.getDrops()).thenReturn(new ArrayList<>());
        tickets.death(death);assertSame(original,slots[0]);assertEquals(3,slots[0].getAmount());assertEquals(3,ledger.balance(id));
        when(death.getKeepInventory()).thenReturn(false);tickets.death(death);assertNull(slots[0]);assertEquals(3,ledger.balance(id));
    }
    @BeforeEach void setup()throws Exception {
        ledger=new CreditLedger(directory.resolve("credits.json"));when(player.getUniqueId()).thenReturn(id);when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(slots.length);when(inventory.getStorageContents()).thenAnswer(c->Arrays.copyOf(slots,36));when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
        when(player.getItemOnCursor()).thenAnswer(c->cursor.get());doAnswer(c->{cursor.set(c.getArgument(0));return null;}).when(player).setItemOnCursor(nullable(ItemStack.class));
        tickets=new QuestionTickets(ledger,active::get,p->opened++,this::item);
    }
    private ItemStack item(UUID owner,int amount) {
        var item=mock(ItemStack.class);var meta=mock(ItemMeta.class);var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        values.put(new NamespacedKey("noqui","question_ticket_owner"),owner.toString());values.put(new NamespacedKey("noqui","question_ticket_version"),(byte)1);
        when(data.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),any(),any());doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(data).remove(any());
        when(meta.getPersistentDataContainer()).thenReturn(data);when(item.getItemMeta()).thenReturn(meta);when(item.hasItemMeta()).thenReturn(true);when(item.getType()).thenReturn(Material.PAPER);
        var name=new AtomicReference<net.kyori.adventure.text.Component>();var lore=new AtomicReference<List<net.kyori.adventure.text.Component>>();
        when(meta.displayName()).thenAnswer(c->name.get());doAnswer(c->{name.set(c.getArgument(0));return null;}).when(meta).displayName(any());
        when(meta.lore()).thenAnswer(c->lore.get());doAnswer(c->{lore.set(c.getArgument(0));return null;}).when(meta).lore(anyList());
        int[] count={amount};when(item.getAmount()).thenAnswer(c->count[0]);doAnswer(c->{count[0]=c.getArgument(0);return null;}).when(item).setAmount(anyInt());return item;
    }
    private int physical(){return Arrays.stream(slots).filter(item->QuestionTickets.owned(item,id)).mapToInt(ItemStack::getAmount).sum()+(QuestionTickets.owned(cursor.get(),id)?cursor.get().getAmount():0);}
    private ItemStack stone(){var item=mock(ItemStack.class);when(item.getType()).thenReturn(Material.STONE);return item;}
    private record GroundFixture(Item entity,World world,java.util.concurrent.atomic.AtomicInteger age) {}
    private GroundFixture ground(ItemStack stack) {
        var entity=mock(Item.class);var world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getLocation()).thenReturn(new Location(world,2,65,3));when(entity.getWorld()).thenReturn(world);var current=new AtomicReference<>(stack);when(entity.getItemStack()).thenAnswer(c->current.get());
        doAnswer(c->{current.set(c.getArgument(0));return null;}).when(entity).setItemStack(any());var valid=new java.util.concurrent.atomic.AtomicBoolean(true);when(entity.isValid()).thenAnswer(c->valid.get());doAnswer(c->{valid.set(false);return null;}).when(entity).remove();
        var age=new java.util.concurrent.atomic.AtomicInteger();when(entity.getTicksLived()).thenAnswer(c->age.get());doAnswer(c->{age.set(c.getArgument(0));return null;}).when(entity).setTicksLived(anyInt());return new GroundFixture(entity,world,age);
    }
    private Player receiver(UUID recipient,ItemStack[] storage) {
        var p=mock(Player.class);when(p.getUniqueId()).thenReturn(recipient);var inv=mock(PlayerInventory.class);when(p.getInventory()).thenReturn(inv);when(inv.getSize()).thenReturn(storage.length);when(inv.getStorageContents()).thenAnswer(c->Arrays.copyOf(storage,36));
        when(inv.getItem(anyInt())).thenAnswer(c->storage[c.getArgument(0,Integer.class)]);doAnswer(c->{storage[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inv).setItem(anyInt(),nullable(ItemStack.class));return p;
    }
    @Test void cursorOffhandAndNumberKeyMovesRemainPhysicalAndInactiveStateHidesWithoutLoss()throws Exception {
        ledger.grant(id,3);tickets.refresh(player);cursor.set(slots[0]);slots[0]=null;tickets.refresh(player);assertEquals(3,physical());assertTrue(Arrays.stream(slots).allMatch(Objects::isNull));assertTrue(tickets.has(player));
        slots[40]=cursor.getAndSet(null);tickets.refresh(player);assertEquals(3,physical());assertEquals(3,slots[40].getAmount());assertNull(slots[0]);
        var click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);when(click.getClickedInventory()).thenReturn(inventory);when(click.getCurrentItem()).thenReturn(slots[40]);when(click.getHotbarButton()).thenReturn(0);when(click.getClick()).thenReturn(ClickType.NUMBER_KEY);
        tickets.inventory(click);verify(click,never()).setCancelled(anyBoolean());
        active.set(false);tickets.refresh(player);assertEquals(0,physical());assertEquals(3,ledger.balance(id));assertFalse(tickets.has(player));
        ledger=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(ledger,active::get,p->opened++,this::item);Arrays.fill(slots,stone());active.set(true);tickets.refresh(player);assertEquals(0,physical());assertEquals(3,tickets.pending(player));
        slots[7]=null;tickets.refresh(player);assertEquals(3,physical());assertEquals(0,tickets.pending(player));
        active.set(false);tickets.refresh(player);try(var bukkit=mockStatic(Bukkit.class)){assertEquals(3,tickets.pending(id));}assertEquals(3,ledger.balance(id));
    }
    @Test void droppedStackDoesNotRemintAndPartialPickupTransfersOnceAcrossFullInventoryAndRestart()throws Exception {
        ledger.grant(id,3);tickets.refresh(player);var drop=ground(item(id,2));slots[0].setAmount(1);var event=mock(PlayerDropItemEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getItemDrop()).thenReturn(drop.entity());
        tickets.drop(event);verify(event,never()).setCancelled(true);assertEquals(1,ledger.balance(id));tickets.refresh(player);assertEquals(1,physical());assertFalse(QuestionTickets.owned(drop.entity().getItemStack(),id));
        UUID recipient=UUID.randomUUID();ItemStack[] received=new ItemStack[41];Arrays.fill(received,stone());var other=receiver(recipient,received);var pickup=mock(org.bukkit.event.entity.EntityPickupItemEvent.class);when(pickup.getEntity()).thenReturn(other);when(pickup.getItem()).thenReturn(drop.entity());
        tickets.pickup(pickup);assertEquals(0,ledger.balance(recipient));assertEquals(2,ledger.dropped(drop.entity().getUniqueId()).amount());
        ledger.grant(recipient,63);received[0]=item(recipient,63);tickets.pickup(pickup);assertEquals(64,ledger.balance(recipient));assertEquals(1,drop.entity().getItemStack().getAmount());
        ledger=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(ledger,active::get,p->opened++,this::item);received[1]=null;tickets.pickup(pickup);assertEquals(65,ledger.balance(recipient));assertEquals(1,ledger.balance(id));assertNull(ledger.dropped(drop.entity().getUniqueId()));
        tickets.pickup(pickup);assertEquals(65,ledger.balance(recipient)); // Replayed stale ground entity cannot pay again.
        slots[5]=item(id,3);tickets.refresh(player);assertEquals(1,physical()); // Old inventory snapshot from before the drop.
    }
    @Test void groundCrashRecoveryFindsUntaggedCommittedEntityAndDespawnNeverRemints()throws Exception {
        ledger.grant(id,1);var drop=ground(item(id,1));UUID claim=drop.entity().getUniqueId();var location=drop.entity().getLocation();
        ledger.drop(claim,id,1,new CreditLedger.Ground(drop.world().getUID(),location.getX(),location.getY(),location.getZ(),20));
        ledger=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(ledger,active::get,p->opened++,this::item);
        var chunk=mock(Chunk.class);when(drop.world().getChunkAt(any(Location.class))).thenReturn(chunk);when(chunk.getEntities()).thenReturn(new Entity[]{drop.entity()});
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(()->Bukkit.getWorld(drop.world().getUID())).thenReturn(drop.world());tickets.refreshGround();}
        verify(drop.world(),never()).dropItem(any(),any());assertFalse(QuestionTickets.owned(drop.entity().getItemStack(),id));assertEquals(0,ledger.balance(id));
        var despawn=mock(org.bukkit.event.entity.ItemDespawnEvent.class);when(despawn.getEntity()).thenReturn(drop.entity());tickets.despawn(despawn);assertNull(ledger.dropped(claim));drop.entity().remove();tickets.refreshGround();
        assertNull(new CreditLedger(directory.resolve("credits.json")).dropped(claim));assertEquals(0,ledger.balance(id));
    }
    @Test void groundAgeCheckpointsAreBatchedAndDoNotRewriteLedgerEveryRefresh()throws Exception {
        ledger.grant(id,1);var drop=ground(item(id,1));var event=mock(PlayerDropItemEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getItemDrop()).thenReturn(drop.entity());tickets.drop(event);
        String durable=Files.readString(directory.resolve("credits.json"));
        for(int i=1;i<30;i++){drop.age().set(i*20);tickets.refreshGround();assertEquals(durable,Files.readString(directory.resolve("credits.json")));}
        drop.age().set(600);tickets.refreshGround();assertNotEquals(durable,Files.readString(directory.resolve("credits.json")));assertEquals(600,new CreditLedger(directory.resolve("credits.json")).dropped(drop.entity().getUniqueId()).ground().age());
    }
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
        var recovered=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(recovered,()->true,p->opened++,this::item);
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
        ledger=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(ledger,()->true,p->opened++,this::item);ledger.giveOnce(id,1,"shop:one");tickets.refresh(player);assertEquals(1,physical());
    }
    @Test void snapshotSwapAndCrashRecoveryRestoreExactlyTheUnreservedPhysicalCount()throws Exception {
        ledger.grant(id,2);tickets.refresh(player);var stale=Arrays.copyOf(slots,slots.length);
        tickets.strip(player);assertEquals(0,physical());Arrays.fill(slots,null);tickets.refresh(player);assertEquals(2,physical());
        UUID reservation=UUID.randomUUID();tickets.submit(player,()->ledger.reserve(reservation,id));ledger.spend(reservation);assertEquals(1,physical());
        System.arraycopy(stale,0,slots,0,slots.length);tickets.refresh(player);assertEquals(1,physical());
        UUID interrupted=UUID.randomUUID();tickets.submit(player,()->ledger.reserve(interrupted,id));assertEquals(0,physical());
        ledger=new CreditLedger(directory.resolve("credits.json"));tickets=new QuestionTickets(ledger,()->true,p->opened++,this::item);tickets.refresh(player);assertEquals(1,physical());
    }
    @Test void forgedOrdinaryPaperAndOtherOwnersCannotAuthorizeSubmission() {
        ledger.grant(id,1);var paper=mock(ItemStack.class);when(paper.getType()).thenReturn(Material.PAPER);slots[0]=paper;assertFalse(tickets.has(player));assertFalse(QuestionTickets.marked(paper));
        slots[0]=item(UUID.randomUUID(),1);assertFalse(tickets.has(player));assertThrows(IllegalArgumentException.class,()->tickets.submit(player,()->fail("not authorized")));
    }
    @Test void mainHandOpensFormOncePerTickWhileInventoryRightClickRemainsAnOrdinaryMove() {
        ledger.grant(id,2);tickets.refresh(player);var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getItem()).thenReturn(slots[0]);when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);tickets.interact(event);assertEquals(0,opened);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);tickets.interact(event);tickets.interact(event);assertEquals(1,opened);assertEquals(2,physical());
            var click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);when(click.getClickedInventory()).thenReturn(inventory);when(click.getCurrentItem()).thenReturn(slots[0]);when(click.getClick()).thenReturn(ClickType.RIGHT);when(click.getHotbarButton()).thenReturn(-1);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(11);tickets.inventory(click);verify(click,never()).setCancelled(anyBoolean());assertEquals(1,opened);assertEquals(2,physical());
        }
    }
    @Test void rejectedSourcesReturnPhysicalTicketBeforeReportingTheReasonExactlyOnce(){
        ledger.grant(id,3);tickets.refresh(player);var futures=new ArrayList<CompletableFuture<MediaBridge.Media>>();var messages=new ArrayList<String>();
        var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){var f=new CompletableFuture<Media>();futures.add(f);return f;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        var game=new QuizGame(bridge,Runnable::run,()->1000,new QuizGame.Output(){
            public void changed(QuizGame.Snapshot s){tickets.refresh(player);}public void error(UUID p,String text){assertEquals(id,p);assertEquals(3,physical());messages.add(text);}
        },ledger,40,0,false,true,false,new Random(1));game.start(List.of(id));
        for(String code:List.of("TOO_LONG","TOO_LARGE","SOURCE_AUTH_REQUIRED","FORMAT_UNSUPPORTED","INGEST_TIMEOUT")){
            int previous=messages.size();tickets.submit(player,()->game.submit(id,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Song","")));assertEquals(2,physical());
            futures.getLast().completeExceptionally(new CompletionException(new SubmissionFailure(code)));assertEquals(3,physical());assertEquals(3,ledger.balance(id));assertEquals(previous+1,messages.size());
            assertTrue(messages.getLast().contains(new SubmissionFailure(code).getMessage()));game.cancel(id);assertEquals(3,physical());
        }
    }
    @Test void realCoreFailureAndCancelRefundPhysicalTicketsWhilePlaybackSpendSurvivesReturn() {
        var futures=new ArrayList<CompletableFuture<MediaBridge.Media>>();var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){var f=new CompletableFuture<Media>();futures.add(f);return f;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        long[] now={1000};var game=new QuizGame(bridge,Runnable::run,()->now[0],new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String e){}},ledger,115,0,false,true,false,new Random(1));game.start(List.of(id));ledger.grant(id,2);tickets.refresh(player);
        var song=Song.input("https://youtu.be/BaW_jenozKc","Song","");tickets.submit(player,()->game.submit(id,game.snapshot().revision(),song));assertEquals(1,physical());futures.getFirst().completeExceptionally(new IllegalStateException("conversion"));tickets.refresh(player);assertEquals(2,physical());
        tickets.submit(player,()->game.submit(id,game.snapshot().revision(),song));game.cancel(id);tickets.refresh(player);assertEquals(2,physical());
        tickets.submit(player,()->game.submit(id,game.snapshot().revision(),song));futures.getLast().complete(new MediaBridge.Media("ready",1000));game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());now[0]=game.snapshot().startsAt()+1;assertTrue(game.confirmPlayback(id,game.snapshot().roundId(),game.snapshot().playbackGeneration(),1,now[0]));game.stop();tickets.refresh(player);assertEquals(1,physical());
    }
    private QuizGame game(){var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){return new CompletableFuture<>();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        var game=new QuizGame(bridge,Runnable::run,()->1000,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID p,String e){}},ledger,115,0,false,true,false,new Random(1));game.start(List.of(id));return game;}
    private NoquiPlugin plugin(QuizGame game)throws Exception {
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);
        for(var entry:Map.of("game",(Object)game,"tickets",tickets,"cabinOwners",new HashSet<>(Set.of(id)),"optedOut",new HashSet<UUID>()).entrySet()){var field=NoquiPlugin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());}return plugin;
    }
    @Test void nonparticipantHeldTicketRejoinsWithoutQueuedBrowserPromptReplacingSubmission()throws Exception {
        var game=game();game.leave(id);var plugin=plugin(game);when(player.isOnline()).thenReturn(true);
        tickets=new QuestionTickets(ledger,()->true,plugin::openTicketForm,this::item);
        var members=new HashMap<UUID,dev.noqui.bridge.BridgeClient.Member>();var connections=mock(BrowserConnections.class);
        for(var entry:Map.of("tickets",(Object)tickets,"members",members,"connections",connections,"hud",mock(QuizHud.class)).entrySet()){
            var field=NoquiPlugin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());
        }
        plugin.dialogs=mock(NoquiDialogs.class);
        doAnswer(c->{members.put(id,new dev.noqui.bridge.BridgeClient.Member(id,UUID.randomUUID(),"player",null));return null;}).when(plugin).enter(player);
        var tasks=new ArrayDeque<Runnable>();doAnswer(c->{tasks.add(c.getArgument(0));return null;}).when(plugin).main(any());
        ledger.grant(id,1);tickets.refresh(player);
        var click=mock(PlayerInteractEvent.class);when(click.getPlayer()).thenReturn(player);when(click.getHand()).thenReturn(EquipmentSlot.HAND);
        when(click.getItem()).thenReturn(slots[0]);when(click.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);bukkit.when(Bukkit::getCurrentTick).thenReturn(20);
            tickets.interact(click);verify(click).setCancelled(true);assertFalse(game.participant(id));verifyNoInteractions(plugin.dialogs);assertEquals(1,tasks.size());
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
