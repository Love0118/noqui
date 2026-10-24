package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.*;
import org.junit.jupiter.api.*;
import org.mockito.*;

class NativeWardrobeTest {
    Player player;GadgetTestSupport.Inventory inventory;NativeWardrobe wardrobe;
    MockedConstruction<ItemStack> stacks;
    @BeforeEach void setup(){
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        inventory=new GadgetTestSupport.Inventory(player);wardrobe=new NativeWardrobe();
        stacks=mockConstruction(ItemStack.class,(item,context)->{
            ItemMeta meta=mock(ItemMeta.class);var wearable=mock(EquippableComponent.class);var custom=mock(CustomModelDataComponent.class);
            var key=new AtomicReference<NamespacedKey>();var strings=new AtomicReference<List<String>>(List.of());
            when(item.getType()).thenReturn(Material.PAPER);when(item.hasItemMeta()).thenReturn(true);when(item.getAmount()).thenReturn(1);
            var persistent=GadgetTestSupport.data();when(item.getItemMeta()).thenReturn(meta);when(meta.getPersistentDataContainer()).thenReturn(persistent);
            when(meta.getEquippable()).thenReturn(wearable);when(meta.getCustomModelDataComponent()).thenReturn(custom);
            when(wearable.getModel()).thenAnswer(c->key.get());doAnswer(c->{key.set(c.getArgument(0));return null;}).when(wearable).setModel(nullable(NamespacedKey.class));
            when(custom.getStrings()).thenAnswer(c->strings.get());doAnswer(c->{strings.set(c.getArgument(0));return null;}).when(custom).setStrings(anyList());
        });
    }
    @AfterEach void close(){stacks.close();}
    ItemStack gear(){var item=mock(ItemStack.class);when(item.getType()).thenReturn(Material.DIAMOND_HELMET);return item;}
    @Test void realHeadwearCombinesHeadAndFaceWithoutAnyEntityOrEquipmentPacket(){
        wardrobe.tick(player,Map.of("head","bunny","face","glasses"));
        var worn=inventory.slots[39];assertTrue(NativeWardrobe.managed(worn));
        verify(worn.getItemMeta()).setItemModel(new NamespacedKey("noqui","wearable_head"));
        assertEquals(List.of("bunny","glasses"),worn.getItemMeta().getCustomModelDataComponent().getStrings());
        verify(player,never()).getWorld();verify(player,never()).addPassenger(any());verify(player,never()).sendEquipmentChange(any(LivingEntity.class),anyMap());
    }
    @Test void realHelmetIsMovedToPersistedPocketAndFullInventoryDefers(){
        ItemStack original=gear();inventory.slots[39]=original;
        Arrays.fill(inventory.slots,0,36,gear());wardrobe.tick(player,Map.of("head","crown"));assertSame(original,inventory.slots[39]);
        inventory.slots[7]=null;wardrobe.tick(player,Map.of("head","crown"));assertSame(original,inventory.slots[7]);assertTrue(NativeWardrobe.managed(inventory.slots[39]));
        wardrobe.remove(player);assertNull(inventory.slots[39]);assertSame(original,inventory.slots[7]);
    }
    @Test void bothInventoryHandoffsAndStaleStorageCopiesAreCleanedWithoutTouchingOrdinaryItems(){
        var original=gear();inventory.slots[4]=original;wardrobe.tick(player,Map.of("head","cap","back","backpack"));
        var stale=inventory.slots[39];wardrobe.remove(player);assertNull(inventory.slots[39]);assertNull(inventory.slots[38]);assertSame(original,inventory.slots[4]);
        inventory.slots[2]=stale;wardrobe.tick(player,Map.of("head","cap"));assertNull(inventory.slots[2]);assertTrue(NativeWardrobe.managed(inventory.slots[39]));
        wardrobe.remove(player);wardrobe.tick(player,Map.of("head","cap"));wardrobe.leave(player.getUniqueId());assertNull(inventory.slots[39]);assertSame(original,inventory.slots[4]);
    }
    @Test void backLayersOntoPhysicalSuitAndRestoresBaseAssetAtHandoff(){
        UUID serial=UUID.randomUUID();var suit=GadgetTestSupport.item(new LobbyWallets.Gadget(serial,player.getUniqueId(),"gravity_suit",true));
        inventory.slots[38]=suit;wardrobe.tick(player,Map.of("back","backpack"));
        assertSame(suit,inventory.slots[38]);assertEquals(serial,GadgetItems.serial(suit));
        assertEquals(new NamespacedKey("noqui","wearable_back_gravity/backpack"),suit.getItemMeta().getEquippable().getModel());
        wardrobe.remove(player);assertSame(suit,inventory.slots[38]);assertEquals(new NamespacedKey("noqui","gravity_suit"),suit.getItemMeta().getEquippable().getModel());
    }
    @Test void managedClickAndDragAreBlockedButActualGadgetsRemainTransferable(){
        wardrobe.tick(player,Map.of("head","cap"));var event=mock(InventoryClickEvent.class);when(event.getCurrentItem()).thenReturn(inventory.slots[39]);wardrobe.click(event);verify(event).setCancelled(true);
        var drag=mock(InventoryDragEvent.class);when(drag.getOldCursor()).thenReturn(inventory.slots[39]);wardrobe.drag(drag);verify(drag).setCancelled(true);
        var normal=mock(InventoryClickEvent.class);when(normal.getHotbarButton()).thenReturn(-1);when(normal.getWhoClicked()).thenReturn(player);
        var hammer=GadgetTestSupport.item(new LobbyWallets.Gadget(UUID.randomUUID(),player.getUniqueId(),"hammer",true));when(normal.getCurrentItem()).thenReturn(hammer);
        wardrobe.click(normal);verify(normal,never()).setCancelled(true);
    }
    @Test void spectatorAndCloseRemoveOnlyManagedEquipment(){
        var real=gear();inventory.slots[3]=real;wardrobe.tick(player,Map.of("head","cap","back","backpack"));
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);wardrobe.tick(player,Map.of("head","cap"));assertNull(inventory.slots[39]);assertNull(inventory.slots[38]);
        wardrobe.close();assertSame(real,inventory.slots[3]);
    }
    @Test void restartHandoffCleansPersistedEquipmentBeforeTheFirstTick(){
        wardrobe.tick(player,Map.of("head","cap","back","backpack"));
        new NativeWardrobe().leave(player);assertNull(inventory.slots[39]);assertNull(inventory.slots[38]);
    }
    @Test void upgradePurgeLeavesForeignDisplaysAndGsitArmorStandsAlone(){
        try(var bukkit=mockStatic(Bukkit.class)){
            var world=mock(World.class);var old=mock(ItemDisplay.class);var foreign=mock(ItemDisplay.class);var gsit=mock(ArmorStand.class);
            when(old.getScoreboardTags()).thenReturn(Set.of(NativeWardrobe.LEGACY_TAG));when(foreign.getScoreboardTags()).thenReturn(Set.of("foreign"));when(gsit.getScoreboardTags()).thenReturn(Set.of("GSit"));
            when(world.getEntities()).thenReturn(List.of(old,foreign,gsit));bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            wardrobe.purge();verify(old).remove();verify(foreign,never()).remove();verify(gsit,never()).remove();
        }
    }
}
