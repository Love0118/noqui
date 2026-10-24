package dev.noqui.paper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CabinNavigationTest {
    static ItemStack tool(String id){
        var item=mock(ItemStack.class);var meta=mock(ItemMeta.class);var data=mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.COMPASS);when(item.hasItemMeta()).thenReturn(true);when(item.getItemMeta()).thenReturn(meta);when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(new NamespacedKey("noqui","tool"),PersistentDataType.STRING)).thenReturn(id);return item;
    }
    private static final class Fixture {
        final Player player=mock(Player.class);final PlayerInventory inventory=mock(PlayerInventory.class);final ItemStack[] slots=new ItemStack[41];
        final AtomicReference<ItemStack> cursor=new AtomicReference<>();final CabinNavigation navigation=new CabinNavigation(CabinNavigationTest::tool);
        Fixture(){when(player.getInventory()).thenReturn(inventory);when(inventory.getContents()).thenAnswer(c->slots.clone());when(inventory.getStorageContents()).thenAnswer(c->Arrays.copyOf(slots,36));when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));when(player.getItemOnCursor()).thenAnswer(c->cursor.get());}
    }
    @Test void movedToolsOffhandAndCursorAreKeptAndOnlyMissingToolsFillEmptySlots(){
        var f=new Fixture();var ordinary=mock(ItemStack.class);when(ordinary.getType()).thenReturn(Material.STONE);f.slots[0]=ordinary;
        var compass=tool("defense");var settings=tool("settings");f.slots[40]=compass;f.cursor.set(settings);
        f.navigation.ensure(f.player);assertSame(ordinary,f.slots[0]);assertSame(compass,f.slots[40]);assertSame(settings,f.cursor.get());assertEquals("traits",CabinNavigation.id(f.slots[1]));
        var before=f.slots.clone();f.navigation.ensure(f.player);assertArrayEquals(before,f.slots);verify(f.inventory,times(1)).setItem(anyInt(),any());
    }
    @Test void fullInventoryIsNeverOverwrittenAndFreedSlotsReceiveOnlyMissingTools(){
        var f=new Fixture();var ordinary=mock(ItemStack.class);when(ordinary.getType()).thenReturn(Material.STONE);Arrays.fill(f.slots,ordinary);f.slots[20]=tool("defense");var before=f.slots.clone();
        f.navigation.ensure(f.player);assertArrayEquals(before,f.slots);verify(f.inventory,never()).setItem(anyInt(),any());
        f.slots[17]=null;f.navigation.ensure(f.player);assertEquals("settings",CabinNavigation.id(f.slots[17]));f.slots[25]=null;f.navigation.ensure(f.player);assertEquals("traits",CabinNavigation.id(f.slots[25]));assertSame(before[20],f.slots[20]);
    }
    @Test void playerInventoryMovesStayFreeButDropsCraftingAndContainerTransfersAreBlocked(){
        var f=new Fixture();var event=mock(InventoryClickEvent.class);var view=mock(InventoryView.class);var top=mock(CraftingInventory.class);var tool=tool("settings");
        when(event.getWhoClicked()).thenReturn(f.player);when(event.getView()).thenReturn(view);when(view.getTopInventory()).thenReturn(top);when(top.getMatrix()).thenReturn(new ItemStack[4]);when(top.getSize()).thenReturn(5);
        when(event.getCurrentItem()).thenReturn(tool);when(event.getClickedInventory()).thenReturn(f.inventory);when(event.getAction()).thenReturn(InventoryAction.PICKUP_ALL);when(event.getClick()).thenReturn(ClickType.LEFT);
        assertFalse(CabinNavigation.blocks(event));when(event.isShiftClick()).thenReturn(true);assertFalse(CabinNavigation.blocks(event));
        var chest=mock(Inventory.class);when(view.getTopInventory()).thenReturn(chest);assertTrue(CabinNavigation.blocks(event));when(event.isShiftClick()).thenReturn(false);when(event.getClickedInventory()).thenReturn(top);assertTrue(CabinNavigation.blocks(event));
        when(event.getClickedInventory()).thenReturn(f.inventory);when(event.getAction()).thenReturn(InventoryAction.DROP_ALL_SLOT);assertTrue(CabinNavigation.blocks(event));
        var credit=tool("question_credit");when(event.getCurrentItem()).thenReturn(credit);assertFalse(CabinNavigation.blocks(event));when(view.getTopInventory()).thenReturn(top);
        var drag=mock(InventoryDragEvent.class);when(drag.getOldCursor()).thenReturn(tool);when(drag.getView()).thenReturn(view);when(drag.getRawSlots()).thenReturn(Set.of(6,7));assertFalse(CabinNavigation.blocks(drag));when(drag.getRawSlots()).thenReturn(Set.of(2,6));assertTrue(CabinNavigation.blocks(drag));
    }
    @Test void adapterMenusDismissTransientPromptsWithoutStartingOrLeavingGames(){
        var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.isOnline()).thenReturn(true);doReturn(true).when(plugin).ownsCabin(id);
        var service=mock(dev.noqui.api.DefenseService.class);doReturn(service).when(plugin).defense();plugin.dialogs=mock(NoquiDialogs.class);
        plugin.openMinigames(p);plugin.openSettings(p);plugin.openTraits(p);verify(plugin.dialogs,times(3)).otherInteraction(p);verify(service).openMenu(p);verify(service).openSettings(p);verify(service).openTraits(p);
        verify(service,never()).startGame(p);verify(service,never()).leaveGame(p);verify(p,never()).teleport(any(Location.class));
    }
}
