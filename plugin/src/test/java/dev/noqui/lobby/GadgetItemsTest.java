package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import dev.noqui.lobby.LobbyCatalog.Currency;

class GadgetItemsTest {
    @TempDir Path dir;
    World cabin=mock(World.class),arena=mock(World.class);
    Player player;GadgetTestSupport.Inventory inventory;
    UUID owner=UUID.randomUUID();World world;
    LobbyWallets wallets;GadgetItems gadgets;
    AtomicBoolean failure=new AtomicBoolean();
    @BeforeEach void setup()throws Exception {
        player=mock(Player.class);world=cabin;when(player.getUniqueId()).thenReturn(owner);when(player.getWorld()).thenAnswer(c->world);when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        inventory=new GadgetTestSupport.Inventory(player);
        wallets=new LobbyWallets(dir.resolve("wallet"),(p,b)->{if(failure.get())throw new IOException("disk full");Files.write(p,b);});
        wallets.reward(owner,"seed",1000,Currency.STARLIGHT);gadgets=new GadgetItems(wallets,cabin,GadgetTestSupport::item);
    }
    LobbyCatalog.Product product(String id){return LobbyCatalog.defaults().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();}
    LobbyWallets.Gadget buy(String id,String transaction)throws Exception {wallets.purchase(owner,product(id),Currency.STARLIGHT,transaction);return wallets.pendingGadgets(owner).stream().filter(g->g.product().equals(id)).findFirst().orElseThrow();}
    ItemStack take(UUID serial) {for(int i=0;i<inventory.slots.length;i++)if(serial.equals(GadgetItems.serial(inventory.slots[i]))){ItemStack item=inventory.slots[i];inventory.slots[i]=null;return item;}throw new AssertionError("Missing item");}
    ItemStack stone(){ItemStack item=mock(ItemStack.class);when(item.getType()).thenReturn(Material.STONE);return item;}
    @Test void minePurchasesDeliverToSharedInventoryWithoutOccupyingReservedNoquiSlot()throws Exception {
        world=arena;when(player.isOnline()).thenReturn(true);
        var services=mock(org.bukkit.plugin.ServicesManager.class);var mining=mock(dev.noqui.api.MiningService.class);
        when(services.load(dev.noqui.api.MiningService.class)).thenReturn(mining);when(mining.ownsInventory(player)).thenReturn(true);
        try(var bukkit=mockStatic(Bukkit.class)){
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            for(int i=0;i<36;i++)inventory.slots[i]=stone();inventory.slots[3]=null;inventory.slots[9]=null;
            var gadget=buy("snow_bomb","mine-purchase");gadgets.reconcile(player);
            assertNull(inventory.slots[3]);assertEquals(gadget.serial(),GadgetItems.serial(inventory.slots[9]));
            world=cabin;gadgets.reconcile(player);assertEquals(1,gadgets.count(player,"snow_bomb"));
        }
    }
    @Test void legacyFooterRefreshPreservesCustomLoreAndItemIdentity() {
        var item=mock(ItemStack.class);var meta=mock(ItemMeta.class);when(item.getItemMeta()).thenReturn(meta);
        var custom=Component.text("사용자 제목 \u00b7 유지");var lore=new AtomicReference<List<Component>>(List.of(custom,Component.text("오두막에서 사용 \u00b7 버리기 가능")));
        when(meta.lore()).thenAnswer(c->lore.get());doAnswer(c->{lore.set(c.getArgument(0));return null;}).when(meta).lore(anyList());
        assertTrue(GadgetItems.refreshLegacyLore(item));assertSame(custom,lore.get().getFirst());assertEquals("오두막에서 사용, 버리기 가능",net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(lore.get().getLast()));
        assertFalse(GadgetItems.refreshLegacyLore(item));verify(item).setItemMeta(meta);verify(meta,never()).displayName(any());
    }

    @Test void deliveryDropTransferAndConsumptionNeverRecreateWalletOwnership()throws Exception {
        var token=buy("hammer","purchase");gadgets.reconcile(player);
        assertTrue(wallets.gadget(token.serial()).delivered());assertEquals(1,gadgets.count(player,"hammer"));assertTrue(wallets.wallet(owner).owned().isEmpty());
        ItemStack dropped=take(token.serial());gadgets.reconcile(player);assertEquals(0,gadgets.count(player,"hammer"));
        Player recipient=mock(Player.class);when(recipient.getUniqueId()).thenReturn(UUID.randomUUID());when(recipient.getWorld()).thenReturn(cabin);when(recipient.getGameMode()).thenReturn(GameMode.ADVENTURE);
        var recipientInventory=new GadgetTestSupport.Inventory(recipient);recipientInventory.slots[0]=dropped;
        assertEquals("hammer",gadgets.hand(recipient));gadgets.consumeHand(recipient,"hammer");assertNull(recipientInventory.slots[0]);
        var restart=new LobbyWallets(dir.resolve("wallet"));new GadgetItems(restart,cabin,GadgetTestSupport::item).reconcile(player);
        assertNull(restart.gadget(token.serial()));assertTrue(restart.pendingGadgets(owner).isEmpty());assertEquals(0,gadgets.count(player,"hammer"));
        recipientInventory.slots[0]=dropped;assertNull(gadgets.hand(recipient));gadgets.reconcile(recipient);assertNull(recipientInventory.slots[0]);
    }
    @Test void equipmentRequiresTheActualFeetChestOrMainHandSlotAndStopsOutsideCabin()throws Exception {
        var boots=buy("triple_boots","boots");var suit=buy("gravity_suit","suit");gadgets.reconcile(player);
        assertTrue(gadgets.equipment(player).isEmpty());inventory.slots[36]=take(boots.serial());inventory.slots[38]=take(suit.serial());
        assertEquals(Map.of("boots","triple_boots","suit","gravity_suit"),gadgets.equipment(player));
        inventory.slots[40]=inventory.slots[36];inventory.slots[36]=null;assertFalse(gadgets.equipment(player).containsKey("boots"));
        world=arena;assertTrue(gadgets.equipment(player).isEmpty());assertNull(gadgets.hand(player));
        world=cabin;assertEquals(Map.of("suit","gravity_suit"),gadgets.equipment(player));inventory.slots[38]=null;assertTrue(gadgets.equipment(player).isEmpty());
    }
    @Test void fullInventoryKeepsOnePendingSerialAndRetryNeverChargesAgain()throws Exception {
        Arrays.fill(inventory.slots,stone());var token=buy("triple_boots","one");gadgets.reconcile(player);
        assertFalse(wallets.gadget(token.serial()).delivered());assertEquals(800,wallets.wallet(owner).stars());
        inventory.slots[8]=null;gadgets.reconcile(player);wallets.purchase(owner,product("triple_boots"),Currency.STARLIGHT,"one");gadgets.reconcile(player);
        assertEquals(token.serial(),GadgetItems.serial(inventory.slots[8]));assertTrue(wallets.pendingGadgets(owner).isEmpty());assertEquals(800,wallets.wallet(owner).stars());assertEquals(1,gadgets.count(player,"triple_boots"));
    }
    @Test void persistedIssuanceProofPreventsRemintAfterAckFailureAndDrop()throws Exception {
        var token=buy("hammer","one");failure.set(true);assertThrows(IOException.class,()->gadgets.reconcile(player));
        ItemStack dropped=take(token.serial());assertFalse(wallets.gadget(token.serial()).delivered());
        var restart=new LobbyWallets(dir.resolve("wallet"));gadgets=new GadgetItems(restart,cabin,GadgetTestSupport::item);gadgets.reconcile(player);
        assertTrue(restart.gadget(token.serial()).delivered());assertEquals(0,gadgets.count(player,"hammer"));
        inventory.slots[0]=dropped;assertEquals("hammer",gadgets.hand(player));
    }
    @Test void failedPlayerSaveCannotAcknowledgeDeliveryAndRetryKeepsTheSameSerial()throws Exception {
        var token=buy("hammer","one");doThrow(new IllegalStateException("player save failed")).doNothing().when(player).saveData();
        assertThrows(IllegalStateException.class,()->gadgets.reconcile(player));assertFalse(wallets.gadget(token.serial()).delivered());
        assertEquals(token.serial(),GadgetItems.serial(inventory.slots[0]));gadgets.reconcile(player);
        assertTrue(wallets.gadget(token.serial()).delivered());assertEquals(1,gadgets.count(player,"hammer"));
    }
    @Test void coreCabinSnapshotKeepsSlotLayoutAndDroppedItemsAreNotRestoredByC()throws Exception {
        ItemStack ordinary=stone();inventory.slots[0]=ordinary;
        var boots=buy("quad_boots","boots");var hammer=buy("hammer","hammer");gadgets.reconcile(player);
        inventory.slots[36]=take(boots.serial());inventory.slots[40]=take(hammer.serial());
        ItemStack[] cabinSnapshot=inventory.slots.clone();
        world=arena;Arrays.fill(inventory.slots,ordinary);gadgets.reconcile(player);
        assertTrue(Arrays.stream(inventory.slots).allMatch(item->item==ordinary));
        world=cabin;System.arraycopy(cabinSnapshot,0,inventory.slots,0,cabinSnapshot.length);gadgets.reconcile(player);gadgets.reconcile(player);
        assertArrayEquals(cabinSnapshot,inventory.slots);assertEquals(boots.serial(),GadgetItems.serial(inventory.slots[36]));assertEquals(hammer.serial(),GadgetItems.serial(inventory.slots[40]));
        inventory.slots[36]=null;cabinSnapshot=inventory.slots.clone();
        Arrays.fill(inventory.slots,null);System.arraycopy(cabinSnapshot,0,inventory.slots,0,cabinSnapshot.length);gadgets.reconcile(player);
        assertNull(inventory.slots[36]);assertEquals(0,gadgets.count(player,"quad_boots"));
        assertFalse(player.getPersistentDataContainer().has(new NamespacedKey("noqui","gadget_stacks"),org.bukkit.persistence.PersistentDataType.BYTE_ARRAY));
    }    @Test void failedRetirementLeavesPhysicalItemAndNoEffectAuthorizationIsConsumed()throws Exception {
        var token=buy("snow_bomb","bomb");gadgets.reconcile(player);ItemStack original=inventory.slots[0];failure.set(true);
        assertThrows(IOException.class,()->gadgets.consumeHand(player,"snow_bomb"));assertSame(original,inventory.slots[0]);assertNotNull(wallets.gadget(token.serial()));
    }
    @Test void unregisteredSerialAndDuplicateInventoryCopiesCannotAuthorizeExtraItems()throws Exception {
        ItemStack ordinary=stone();inventory.slots[4]=ordinary;
        inventory.slots[0]=GadgetTestSupport.item(new LobbyWallets.Gadget(UUID.randomUUID(),owner,"hammer",true));assertNull(gadgets.hand(player));
        gadgets.reconcile(player);assertNull(inventory.slots[0]);assertSame(ordinary,inventory.slots[4]);
        buy("hammer","valid");gadgets.reconcile(player);inventory.slots[1]=inventory.slots[0];gadgets.reconcile(player);
        assertNull(inventory.slots[1]);assertEquals(1,gadgets.count(player,"hammer"));
    }
    @Test void nativePreviewFactorySetsPlainNamesAndExplicitFeetChestComponentsWithoutAUsableSerial() {
        try(var stacks=mockConstruction(ItemStack.class,(item,context)->{
            assertEquals(Material.PAPER,context.arguments().getFirst());var meta=mock(ItemMeta.class);var equippable=mock(EquippableComponent.class);
            when(item.getItemMeta()).thenReturn(meta);when(meta.getEquippable()).thenReturn(equippable);
        })) {
            for(String id:List.of("triple_boots","quad_boots","gravity_suit","hammer","snow_bomb")) {
                ItemStack item=LobbyFeatures.gadgetPreview(id);var meta=item.getItemMeta();verify(meta).setMaxStackSize(1);verify(meta).setItemModel(new NamespacedKey("noqui",id));
                var name=ArgumentCaptor.forClass(Component.class);verify(meta).displayName(name.capture());assertEquals(TextDecoration.State.FALSE,name.getValue().decoration(TextDecoration.ITALIC));assertNotNull(name.getValue().color());
                @SuppressWarnings("unchecked") var lore=(ArgumentCaptor<List<Component>>)(ArgumentCaptor<?>)ArgumentCaptor.forClass(List.class);
                verify(meta).lore(lore.capture());assertTrue(lore.getValue().stream().allMatch(c->c.decoration(TextDecoration.ITALIC)==TextDecoration.State.FALSE&&c.color()!=null));
                if(id.endsWith("boots")||id.equals("gravity_suit")) {
                    var equip=meta.getEquippable();verify(equip).setSlot(id.endsWith("boots")?EquipmentSlot.FEET:EquipmentSlot.CHEST);verify(equip).setEquipOnInteract(true);verify(equip).setDamageOnHurt(false);verify(equip).setSwappable(true);verify(meta).setEquippable(equip);
                } else verify(meta,never()).setEquippable(any());
                assertFalse(LobbyFeatures.isGadgetItem(item));
            }
        }
    }
}
