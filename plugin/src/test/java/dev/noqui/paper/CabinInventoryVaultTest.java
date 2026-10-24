package dev.noqui.paper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CabinInventoryVaultTest {
    private static final class Fixture implements AutoCloseable {
        final Player player=mock(Player.class);
        final PlayerInventory inventory=mock(PlayerInventory.class);
        final Map<NamespacedKey,Object> values=new HashMap<>();
        final ItemStack[] slots=new ItemStack[41];
        final AtomicReference<ItemStack> cursor=new AtomicReference<>();
        final Map<byte[],ItemStack[]> serialized=new IdentityHashMap<>();
        final org.mockito.MockedStatic<ItemStack> stacks=mockStatic(ItemStack.class);
        final org.mockito.MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);
        int held=4;
        Fixture(){
            var data=mock(PersistentDataContainer.class);when(data.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));when(data.has(any(),any())).thenAnswer(c->values.containsKey(c.getArgument(0)));
            doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),any(),any());doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(data).remove(any());
            when(player.getPersistentDataContainer()).thenReturn(data);when(player.getInventory()).thenReturn(inventory);when(inventory.getSize()).thenReturn(41);when(inventory.getContents()).thenAnswer(c->slots.clone());
            doAnswer(c->{System.arraycopy(c.getArgument(0),0,slots,0,41);return null;}).when(inventory).setContents(any());when(inventory.getHeldItemSlot()).thenAnswer(c->held);doAnswer(c->{held=c.getArgument(0);return null;}).when(inventory).setHeldItemSlot(anyInt());
            when(player.getItemOnCursor()).thenAnswer(c->cursor.get());doAnswer(c->{cursor.set(c.getArgument(0));return null;}).when(player).setItemOnCursor(nullable(ItemStack.class));
            var world=mock(World.class);UUID worldId=UUID.randomUUID();when(world.getUID()).thenReturn(worldId);when(player.getLocation()).thenReturn(new Location(world,1,65,2));when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);bukkit.when(()->Bukkit.getWorld(worldId)).thenReturn(world);
            stacks.when(()->ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(c->{byte[] bytes={(byte)serialized.size()};serialized.put(bytes,((ItemStack[])c.getArgument(0)).clone());return bytes;});
            stacks.when(()->ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenAnswer(c->serialized.get(c.getArgument(0)).clone());
        }
        public void close(){bukkit.close();stacks.close();}
    }
    @Test void fullCabinLayoutAndCursorSurviveNextSessionWithoutOverwritingOutsideInventory() {
        try(var f=new Fixture()){
            var outside=mock(ItemStack.class);f.slots[2]=outside;var recovery=new PlayerRecovery();recovery.captureCabin(f.player);
            Arrays.fill(f.slots,null);var custom=mock(ItemStack.class);var boots=mock(ItemStack.class);var offhand=mock(ItemStack.class);var cursor=mock(ItemStack.class);
            f.slots[17]=custom;f.slots[36]=boots;f.slots[40]=offhand;f.cursor.set(cursor);f.held=7;
            var vault=new CabinInventoryVault();vault.markLoaded(f.player);vault.leave(f.player);assertNull(f.cursor.get());assertFalse(vault.loaded(f.player));
            recovery.releaseCabin(f.player,false);assertSame(outside,f.slots[2]);assertNull(f.slots[17]);
            recovery.captureCabin(f.player);assertTrue(new CabinInventoryVault().restore(f.player));assertSame(custom,f.slots[17]);assertSame(boots,f.slots[36]);assertSame(offhand,f.slots[40]);assertSame(cursor,f.cursor.get());assertEquals(7,f.held);
            f.slots[9]=f.slots[17];f.slots[17]=null;vault.leave(f.player);recovery.releaseCabin(f.player,false);assertSame(outside,f.slots[2]);
            recovery.captureCabin(f.player);new CabinInventoryVault().restore(f.player);assertSame(custom,f.slots[9]);assertNull(f.slots[17]);assertSame(boots,f.slots[36]);assertSame(offhand,f.slots[40]);
        }
    }
    @Test void restoredCabinMigratesMissingNavigationWithoutMovingExistingItemsOrReminting() {
        try(var f=new Fixture()){
            when(f.inventory.getStorageContents()).thenAnswer(c->Arrays.copyOf(f.slots,36));when(f.inventory.getItem(anyInt())).thenAnswer(c->f.slots[c.getArgument(0,Integer.class)]);
            doAnswer(c->{f.slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(f.inventory).setItem(anyInt(),nullable(ItemStack.class));
            var ordinary=mock(ItemStack.class);when(ordinary.getType()).thenReturn(Material.STONE);var compass=CabinNavigationTest.tool("defense");var traits=CabinNavigationTest.tool("traits");
            f.slots[12]=compass;f.slots[20]=ordinary;f.cursor.set(traits);var vault=new CabinInventoryVault();vault.markLoaded(f.player);vault.leave(f.player);
            Arrays.fill(f.slots,null);assertTrue(vault.restore(f.player));var created=new java.util.concurrent.atomic.AtomicInteger();var navigation=new CabinNavigation(id->{created.incrementAndGet();return CabinNavigationTest.tool(id);});navigation.ensure(f.player);
            assertSame(compass,f.slots[12]);assertSame(ordinary,f.slots[20]);assertSame(traits,f.cursor.get());assertEquals("settings",CabinNavigation.id(f.slots[0]));
            assertEquals("wardrobe",CabinNavigation.id(f.slots[1]));
            vault.leave(f.player);Arrays.fill(f.slots,null);assertTrue(vault.restore(f.player));navigation.ensure(f.player);assertEquals(2,created.get());assertSame(compass,f.slots[12]);assertSame(traits,f.cursor.get());
        }
    }
    @Test void interruptedCabinRecoveryCapturesCurrentLayoutBeforeRestoringOutsideState()throws Exception {
        try(var f=new Fixture()){
            var outside=mock(ItemStack.class);f.slots[0]=outside;var recovery=new PlayerRecovery();recovery.captureCabin(f.player);
            Arrays.fill(f.slots,null);var acquired=mock(ItemStack.class);f.slots[23]=acquired;var vault=new CabinInventoryVault();vault.markLoaded(f.player);
            var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);var lobby=mock(dev.noqui.lobby.LobbyFeatures.class);
            for(var entry:Map.of("recovery",(Object)recovery,"cabinInventory",vault,"lobbyFeatures",lobby).entrySet()){var field=NoquiPlugin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());}
            doReturn(false).when(plugin).defenseActive(f.player);plugin.recoverPlayer(f.player);assertSame(outside,f.slots[0]);assertFalse(vault.loaded(f.player));verify(lobby).prepareInventoryChange(f.player);verify(lobby,never()).inventoryChanged(f.player);
            assertTrue(new CabinInventoryVault().restore(f.player));assertSame(acquired,f.slots[23]);assertNull(f.slots[0]);
        }
    }
    @Test void failedVaultSaveDoesNotClearHeldItemsOrCursor() {
        try(var f=new Fixture()){
            var item=mock(ItemStack.class);f.slots[20]=item;f.cursor.set(item);var vault=new CabinInventoryVault();vault.markLoaded(f.player);
            doThrow(new IllegalStateException("disk unavailable")).when(f.player).saveData();assertThrows(IllegalStateException.class,()->vault.leave(f.player));assertSame(item,f.slots[20]);assertSame(item,f.cursor.get());assertTrue(vault.loaded(f.player));
        }
    }
    private static ItemStack item(String marker){
        var item=mock(ItemStack.class);var data=mock(PersistentDataContainer.class);when(item.getPersistentDataContainer()).thenReturn(data);
        when(data.getKeys()).thenReturn(marker==null?Set.of():Set.of(new NamespacedKey("momadefense",marker)));return item;
    }
    @Test void mineSharesBearerItemsAndCursorButNeverItsPickaxeNavigationOrCargo() {
        try(var f=new Fixture()){
            var gadget=item(null);var acquired=item(null);var cursor=item(null);var vault=new CabinInventoryVault();
            f.slots[15]=gadget;f.slots[4]=item("mining_tool");f.slots[0]=item("mine_navigation");f.slots[24]=item("mining_cargo");f.cursor.set(cursor);
            vault.captureShared(f.player);Arrays.fill(f.slots,null);f.cursor.set(null);
            assertTrue(vault.restoreShared(f.player));assertFalse(vault.loaded(f.player));assertSame(gadget,f.slots[15]);assertSame(cursor,f.cursor.get());
            assertNull(f.slots[4]);assertNull(f.slots[0]);assertNull(f.slots[24]);
            f.slots[15]=null;f.slots[18]=acquired;vault.captureShared(f.player);Arrays.fill(f.slots,null);
            assertTrue(vault.restore(f.player));assertTrue(vault.loaded(f.player));assertNull(f.slots[15]);assertSame(acquired,f.slots[18]);
        }
    }
}
