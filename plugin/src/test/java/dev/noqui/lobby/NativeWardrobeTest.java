package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    World world;List<ItemDisplay> displays;
    @BeforeEach void setup(){
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        inventory=new GadgetTestSupport.Inventory(player);wardrobe=new NativeWardrobe();
        world=mock(World.class);displays=new ArrayList<>();
        when(player.getLocation()).thenAnswer(c->new Location(world,10,20,30,90,45));
        when(world.spawn(any(Location.class),eq(ItemDisplay.class),any(Consumer.class))).thenAnswer(c->{
            var display=mock(ItemDisplay.class);var location=new AtomicReference<Location>(c.getArgument(0));
            when(display.isValid()).thenReturn(true);when(display.getWorld()).thenReturn(world);
            when(display.getLocation()).thenAnswer(call->location.get().clone());
            when(display.teleport(any(Location.class))).thenAnswer(call->{location.set(call.getArgument(0));return true;});
            ((Consumer<ItemDisplay>)c.getArgument(2)).accept(display);displays.add(display);return display;
        });
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
    @Test void backDisplayPreservesPhysicalSuitAndMigratesRetiredCombinedAsset(){
        UUID serial=UUID.randomUUID();var suit=GadgetTestSupport.item(new LobbyWallets.Gadget(serial,player.getUniqueId(),"gravity_suit",true));
        suit.getItemMeta().getEquippable().setModel(new NamespacedKey("noqui","wearable_back_gravity/backpack"));
        inventory.slots[38]=suit;wardrobe.tick(player,Map.of("back","backpack"));
        assertSame(suit,inventory.slots[38]);assertEquals(serial,GadgetItems.serial(suit));
        assertEquals(new NamespacedKey("noqui","gravity_suit"),suit.getItemMeta().getEquippable().getModel());
        assertEquals(1,displays.size());
        wardrobe.remove(player);assertSame(suit,inventory.slots[38]);assertEquals(new NamespacedKey("noqui","gravity_suit"),suit.getItemMeta().getEquippable().getModel());
        verify(displays.getFirst()).remove();
    }
    @Test void backUsesOneVanillaModelAndLeavesRealChestArmorInPlace(){
        var chest=gear();inventory.slots[38]=chest;
        wardrobe.tick(player,Map.of("back","backpack"));var display=displays.getFirst();
        var stack=ArgumentCaptor.forClass(ItemStack.class);verify(display).setItemStack(stack.capture());
        verify(stack.getValue().getItemMeta()).setItemModel(new NamespacedKey("noqui","backpack"));
        verify(display).setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        verify(display).setPersistent(false);assertSame(chest,inventory.slots[38]);
        wardrobe.tick(player,Map.of("back","backpack"));assertEquals(1,displays.size());
        verify(display,times(1)).setTransformationMatrix(any());
        wardrobe.tick(player,Map.of("back","back_acoustic_guitar"));assertEquals(1,displays.size());
        verify(display,times(2)).setItemStack(any());verify(display,times(2)).setTransformationMatrix(any());
        wardrobe.tick(player,Map.of());verify(display).remove();assertSame(chest,inventory.slots[38]);
    }
    @Test void retiredManagedChestAndStorageCopiesAreRemovedBeforeDisplayMigration(){
        wardrobe.tick(player,Map.of("head","cap"));var retired=inventory.slots[39];
        inventory.slots[39]=null;inventory.slots[38]=retired;inventory.slots[5]=retired;
        wardrobe.tick(player,Map.of("back","backpack"));
        assertNull(inventory.slots[38]);assertNull(inventory.slots[5]);assertEquals(1,displays.size());
        wardrobe.close();verify(displays.getFirst()).remove();
    }
    @Test void backFollowsBodyYawAndCrouchingInsteadOfHeadPitch(){
        when(player.getBodyYaw()).thenReturn(120f);wardrobe.tick(player,Map.of("back","backpack"));
        var display=displays.getFirst();assertEquals(120,display.getLocation().getYaw());assertEquals(0,display.getLocation().getPitch());
        when(player.getBodyYaw()).thenReturn(130f);when(player.isSneaking()).thenReturn(true);
        wardrobe.tick(player,Map.of("back","backpack"));assertEquals(130,display.getLocation().getYaw());
        verify(display).teleport(any(Location.class));verify(display,times(2)).setTransformationMatrix(any());
        verify(display).setInterpolationDelay(0);
        when(player.isInvisible()).thenReturn(true);wardrobe.tick(player,Map.of("back","backpack"));verify(display).remove();
    }
    @Test void backRecreatesInvalidOrWorldChangedDisplaysAndCleansUpOnLeave(){
        wardrobe.tick(player,Map.of("back","backpack"));var first=displays.getFirst();when(first.isValid()).thenReturn(false);
        wardrobe.tick(player,Map.of("back","backpack"));verify(first).remove();assertEquals(2,displays.size());
        var second=displays.getLast();when(second.getWorld()).thenReturn(mock(World.class));
        wardrobe.tick(player,Map.of("back","backpack"));verify(second).remove();assertEquals(3,displays.size());
        wardrobe.leave(player);verify(displays.getLast()).remove();
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
        verify(displays.getFirst()).remove();
        wardrobe.close();assertSame(real,inventory.slots[3]);
    }
    @Test void restartHandoffCleansPersistedEquipmentBeforeTheFirstTick(){
        wardrobe.tick(player,Map.of("head","cap","back","backpack"));
        new NativeWardrobe().leave(player);assertNull(inventory.slots[39]);assertNull(inventory.slots[38]);
    }
    @Test void upgradePurgeLeavesForeignDisplaysAndGsitArmorStandsAlone(){
        try(var bukkit=mockStatic(Bukkit.class)){
            var world=mock(World.class);var old=mock(ItemDisplay.class);var current=mock(ItemDisplay.class);var foreign=mock(ItemDisplay.class);var gsit=mock(ArmorStand.class);
            when(current.getScoreboardTags()).thenReturn(Set.of(BackAccessories.TAG));
            when(old.getScoreboardTags()).thenReturn(Set.of(NativeWardrobe.LEGACY_TAG));when(foreign.getScoreboardTags()).thenReturn(Set.of("foreign"));when(gsit.getScoreboardTags()).thenReturn(Set.of("GSit"));
            when(world.getEntities()).thenReturn(List.of(old,current,foreign,gsit));bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            wardrobe.purge();verify(old).remove();verify(current).remove();verify(foreign,never()).remove();verify(gsit,never()).remove();
        }
    }
}
