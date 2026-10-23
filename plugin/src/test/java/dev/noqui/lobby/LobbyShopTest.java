package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import dev.noqui.lobby.LobbyCatalog.Currency;

class LobbyShopTest {
    @TempDir Path dir;
    MockedStatic<Bukkit> bukkit;
    MockedConstruction<ItemStack> stacks;
    LobbyWallets wallets;
    LobbyShop shop;
    Player player;
    UUID id=UUID.randomUUID();
    InventoryView current;
    final Map<Inventory,Map<Integer,ItemStack>> contents=new IdentityHashMap<>();
    final Map<ItemStack,ItemMeta> metadata=new IdentityHashMap<>();
    final List<Component> messages=new ArrayList<>();
    final List<String> deliveryAttempts=new ArrayList<>();
    final AtomicBoolean failure=new AtomicBoolean(),eligible=new AtomicBoolean(true),full=new AtomicBoolean();
    @BeforeEach void setup()throws Exception {
        bukkit=mockStatic(Bukkit.class);
        stacks=mockConstruction(ItemStack.class,(item,context)->{
            ItemMeta meta=mock(ItemMeta.class);metadata.put(item,meta);when(item.getItemMeta()).thenReturn(meta);
        });
        bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{
            Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(c.getArgument(0));when(inventory.getSize()).thenReturn(54);
            Map<Integer,ItemStack> items=new HashMap<>();contents.put(inventory,items);
            doAnswer(write->{items.put(write.getArgument(0),write.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any());return inventory;
        });
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(id);when(player.getOpenInventory()).thenAnswer(c->current);
        when(player.openInventory(any(Inventory.class))).thenAnswer(c->{
            if(current!=null)closeEvent(current,player);
            current=view(c.getArgument(0),player);return current;
        });
        doAnswer(c->{closeEvent(current,player);current=view(mock(Inventory.class),player);return null;}).when(player).closeInventory();
        doAnswer(c->{messages.add(c.getArgument(0));return null;}).when(player).sendMessage(any(Component.class));
        wallets=new LobbyWallets(dir.resolve("wallet"),(path,bytes)->{
            if(failure.get())throw new IOException("disk full");Files.write(path,bytes);
        });
        shop=new LobbyShop(wallets,LobbyCatalog.defaults(),mock(GadgetItems.class),p->eligible.get(),()->{
            for(var grant:wallets.pending()) {
                deliveryAttempts.add(grant.transaction());
                // The CreditPort adapter keeps a full inventory pending, with the same durable ID.
                if(!full.get())try{wallets.delivered(grant.transaction());}catch(IOException error){throw new IllegalStateException(error);}
            }
        },message->{});
        wallets.reward(id,"stars",100,Currency.STARLIGHT);wallets.reward(id,"points",10,Currency.POINTS);
    }
    @AfterEach void cleanup(){shop.close();stacks.close();bukkit.close();}
    static InventoryView view(Inventory top,Player owner){InventoryView v=mock(InventoryView.class);when(v.getTopInventory()).thenReturn(top);when(v.getPlayer()).thenReturn(owner);return v;}
    void closeEvent(InventoryView view,Player owner){InventoryCloseEvent e=mock(InventoryCloseEvent.class);when(e.getView()).thenReturn(view);when(e.getPlayer()).thenReturn(owner);shop.closed(e);}
    InventoryClickEvent event(InventoryView view,Player owner,int slot,ClickType type) {
        InventoryClickEvent e=mock(InventoryClickEvent.class);when(e.getView()).thenReturn(view);when(e.getWhoClicked()).thenReturn(owner);
        when(e.getRawSlot()).thenReturn(slot);when(e.getClick()).thenReturn(type);return e;
    }
    InventoryClickEvent click(int slot){var e=event(current,player,slot,ClickType.LEFT);shop.click(e);verify(e).setCancelled(true);shop.tick();return e;}
    void openProduct(int category){shop.open(player);if(category!=0)click(category);click(19);}
    ItemMeta meta(int slot){return metadata.get(contents.get(current.getTopInventory()).get(slot));}
    static String plain(Component c){return PlainTextComponentSerializer.plainText().serialize(c);}

    @Test void chestCategoriesShowActualModelsStyledNamesPricesAndOwnership() {
        shop.open(player);assertTrue(LobbyFeatures.ownsInventory(current.getTopInventory()));
        ItemMeta pirate=meta(19);verify(pirate).setItemModel(new NamespacedKey("noqui","pirate_hat"));
        var name=ArgumentCaptor.forClass(Component.class);verify(pirate).displayName(name.capture());
        assertEquals("해적 모자",plain(name.getValue()));assertEquals(TextDecoration.State.FALSE,name.getValue().decoration(TextDecoration.ITALIC));assertNotNull(name.getValue().color());
        @SuppressWarnings("unchecked") var lore=(ArgumentCaptor<List<Component>>)(ArgumentCaptor<?>)ArgumentCaptor.forClass(List.class);
        verify(pirate).lore(lore.capture());assertEquals(List.of("✦ 20  /  정답 포인트 2","보유 0","미장착"),lore.getValue().stream().map(LobbyShopTest::plain).toList());
        assertTrue(lore.getValue().stream().allMatch(c->c.decoration(TextDecoration.ITALIC)==TextDecoration.State.FALSE&&c.color()!=null));
        click(1);verify(meta(19)).setItemModel(new NamespacedKey("noqui","triple_boots"));click(2);
        verify(meta(19),never()).setItemModel(any());verify(player,never()).showDialog(any());
        assertFalse(LobbyFeatures.ownsInventory(mock(Inventory.class)));assertFalse(LobbyFeatures.ownsInventory(null));
    }
    @Test void oneViewAcceptsOneCurrencyClickAndUpdatesDurableOwnershipAndEquip()throws Exception {
        openProduct(0);InventoryView old=current;
        var stars=event(old,player,29,ClickType.LEFT);
        var points=event(old,player,33,ClickType.RIGHT);
        when(stars.isCancelled()).thenReturn(true);shop.click(stars);shop.click(points);
        assertEquals(100,wallets.wallet(id).stars());shop.tick();
        assertEquals(80,wallets.wallet(id).stars());assertEquals(10,wallets.wallet(id).points());assertEquals(1,wallets.wallet(id).owned().get("pirate_hat"));
        shop.click(stars);shop.tick();assertEquals(80,wallets.wallet(id).stars());
        assertFalse(contents.get(current.getTopInventory()).containsKey(29));click(22);assertEquals("pirate_hat",wallets.wallet(id).equipped().get("head"));
        assertEquals("pirate_hat",new LobbyWallets(dir.resolve("wallet")).wallet(id).equipped().get("head"));
        click(22);assertFalse(wallets.wallet(id).equipped().containsKey("head"));
    }
    @Test void cosmeticsAndGadgetsBuyWithValuableAnswerPointsAndAllDualPricesAreTenToOne() {
        for(var p:LobbyCatalog.defaults())if(p.kind()!=LobbyCatalog.Kind.CREDIT)assertEquals(10*p.points(),p.stars());
        openProduct(0);click(33);assertEquals(8,wallets.wallet(id).points());assertEquals(100,wallets.wallet(id).stars());
        shop.open(player);click(1);click(19);click(33);assertEquals(6,wallets.wallet(id).points());assertEquals("triple_boots",wallets.pendingGadgets(id).getFirst().product());
        assertFalse(wallets.wallet(id).owned().containsKey("triple_boots"));
    }
    @Test void guiTransferOperationsAreBlockedWhilePersonalInventoryRemainsMovable() {
        openProduct(0);
        for(ClickType type:ClickType.values())if(type!=ClickType.LEFT && type!=ClickType.RIGHT) {
            var e=event(current,player,29,type);shop.click(e);verify(e).setCancelled(true);
        }
        for(int slot:new int[]{-999,54,55,89})for(ClickType type:List.of(ClickType.LEFT,ClickType.RIGHT)) {
            var e=event(current,player,slot,type);shop.click(e);verify(e,never()).setCancelled(anyBoolean());
        }
        var shift=event(current,player,55,ClickType.SHIFT_LEFT);when(shift.isShiftClick()).thenReturn(true);shop.click(shift);verify(shift).setCancelled(true);
        var collect=event(current,player,55,ClickType.DOUBLE_CLICK);when(collect.getAction()).thenReturn(InventoryAction.COLLECT_TO_CURSOR);shop.click(collect);verify(collect).setCancelled(true);
        InventoryDragEvent drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(current);when(drag.getWhoClicked()).thenReturn(player);when(drag.getRawSlots()).thenReturn(Set.of(19,55));shop.drag(drag);verify(drag).setCancelled(true);
        InventoryDragEvent personal=mock(InventoryDragEvent.class);when(personal.getView()).thenReturn(current);when(personal.getWhoClicked()).thenReturn(player);when(personal.getRawSlots()).thenReturn(Set.of(55,56));shop.drag(personal);verify(personal,never()).setCancelled(anyBoolean());
        shop.tick();assertEquals(100,wallets.wallet(id).stars());assertEquals(10,wallets.wallet(id).points());assertTrue(wallets.wallet(id).owned().isEmpty());
        verify(player,never()).getInventory();
    }
    @Test void foreignOwnerForeignHolderAndOldPlayerHandleCannotAct() {
        openProduct(0);Player stranger=mock(Player.class);when(stranger.getUniqueId()).thenReturn(id);
        var e=event(current,stranger,29,ClickType.LEFT);shop.click(e);verify(e).setCancelled(true);
        InventoryView foreign=view(mock(Inventory.class),player);var foreignEvent=event(foreign,player,29,ClickType.LEFT);
        shop.click(foreignEvent);verify(foreignEvent,never()).setCancelled(anyBoolean());shop.tick();assertEquals(100,wallets.wallet(id).stars());
    }
    @Test void queuedActionsAreInvalidatedByClosingReplacingLeavingOrShutdown() {
        openProduct(0);shop.click(event(current,player,29,ClickType.LEFT));closeEvent(current,player);shop.tick();assertEquals(100,wallets.wallet(id).stars());
        openProduct(0);shop.click(event(current,player,29,ClickType.LEFT));shop.open(player);shop.tick();assertEquals(100,wallets.wallet(id).stars());
        openProduct(0);shop.click(event(current,player,29,ClickType.LEFT));eligible.set(false);shop.tick();assertEquals(100,wallets.wallet(id).stars());
        eligible.set(true);openProduct(0);shop.click(event(current,player,29,ClickType.LEFT));shop.close();shop.tick();assertEquals(100,wallets.wallet(id).stars());
    }
    @Test void persistenceFailureCannotDebitDeliverOrEquipAndInsufficientFundsAreSafe()throws Exception {
        openProduct(2);failure.set(true);click(29);assertEquals(100,wallets.wallet(id).stars());assertTrue(wallets.pending().isEmpty());assertTrue(deliveryAttempts.isEmpty());
        assertTrue(messages.stream().anyMatch(m->plain(m).contains("저장 실패")));
        failure.set(false);wallets.purchase(id,LobbyCatalog.defaults().get(1),Currency.STARLIGHT,"direct");openProduct(0);
        failure.set(true);click(22);assertTrue(wallets.wallet(id).equipped().isEmpty());failure.set(false);
        for(int i=0;i<8;i++){openProduct(2);click(29);}assertEquals(0,wallets.wallet(id).stars());
        openProduct(2);click(29);assertEquals(8,deliveryAttempts.size());assertEquals(0,wallets.wallet(id).stars());
        assertTrue(messages.stream().anyMatch(m->plain(m).contains("부족")));
    }
    @Test void physicalTicketIsStarsOnlyAndFullInventoryLeavesOneDurableDelivery()throws Exception {
        full.set(true);openProduct(2);assertFalse(contents.get(current.getTopInventory()).containsKey(33));
        InventoryView old=current;click(29);assertEquals(90,wallets.wallet(id).stars());assertEquals(1,wallets.pending().size());
        assertFalse(wallets.wallet(id).owned().containsKey("question_credit"));assertEquals(1,new LobbyWallets(dir.resolve("wallet")).pending().size());
        shop.click(event(old,player,29,ClickType.LEFT));shop.tick();assertEquals(1,deliveryAttempts.size());
        assertTrue(messages.stream().anyMatch(m->plain(m).contains("지급 대기")));verify(player,never()).getInventory();
    }
}
