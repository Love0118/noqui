package dev.noqui.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerRecoveryTest {
    private PersistentDataContainer data(Map<NamespacedKey,Object> values) {
        var p=mock(PersistentDataContainer.class);when(p.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));
        when(p.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        when(p.has(any(),any())).thenAnswer(c->values.containsKey(c.getArgument(0)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(p).set(any(),any(),any());
        doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(p).remove(any());return p;
    }
    @Test void membershipRestoresExactRawByteOrAbsenceWithoutTouchingDefenseInventoryOrMode() {
        for(Byte original:Arrays.asList(null,(byte)0,(byte)1,(byte)7,(byte)-128)) {
            var values=new HashMap<NamespacedKey,Object>();var key=new NamespacedKey("momadefense","bgm_muted");if(original!=null)values.put(key,original);
            var p=mock(Player.class);var container=data(values);when(p.getPersistentDataContainer()).thenReturn(container);var recovery=new PlayerRecovery();
            assertNotNull(recovery.beginMembership(p));assertEquals((byte)1,values.get(key));assertThrows(IllegalStateException.class,()->recovery.beginMembership(p));
            recovery.toggleBgm(p);assertEquals((byte)0,values.get(key));assertTrue(new PlayerRecovery().restoreMusic(p));assertEquals(original,values.get(key));assertFalse(recovery.restoreMusic(p));
            verify(p,never()).getInventory();verify(p,never()).setGameMode(any());verify(p,never()).teleport(any(Location.class));verify(p,never()).sendActionBar(any(net.kyori.adventure.text.Component.class));
        }
    }
    @Test void cabinCanBeReleasedAndReenteredWithoutOverwritingMembershipMusicSnapshot() {
        var values=new HashMap<NamespacedKey,Object>();var key=new NamespacedKey("momadefense","bgm_muted");values.put(key,(byte)7);
        var player=mock(Player.class);var inventory=mock(PlayerInventory.class);var world=mock(World.class);UUID worldId=UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);var container=data(values);when(player.getPersistentDataContainer()).thenReturn(container);when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenReturn(new Location(world,4,65,-7,30,15));when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getAllowFlight()).thenReturn(true);when(player.isFlying()).thenReturn(true);when(inventory.getHeldItemSlot()).thenReturn(5);
        var items=new ItemStack[]{mock(ItemStack.class)};when(inventory.getContents()).thenReturn(items);byte[] bytes={1,2,3};
        try(var stacks=mockStatic(ItemStack.class);var bukkit=mockStatic(Bukkit.class)) {
            stacks.when(()->ItemStack.serializeItemsAsBytes(items)).thenReturn(bytes);stacks.when(()->ItemStack.deserializeItemsFromBytes(bytes)).thenReturn(items);bukkit.when(()->Bukkit.getWorld(worldId)).thenReturn(world);
            var recovery=new PlayerRecovery();recovery.beginMembership(player);recovery.captureCabin(player);assertThrows(IllegalStateException.class,()->recovery.captureCabin(player));
            assertTrue(recovery.releaseCabin(player,false));assertEquals((byte)1,values.get(key));verify(player,never()).teleport(any(Location.class));
            recovery.captureCabin(player);new PlayerRecovery().recover(player,false,true);assertEquals((byte)7,values.get(key));assertFalse(recovery.pending(player));assertFalse(recovery.releaseCabin(player,true));
            verify(inventory,times(2)).setContents(items);verify(inventory,times(2)).setHeldItemSlot(5);verify(player,times(2)).setGameMode(GameMode.SURVIVAL);verify(player).teleport(new Location(world,4,65,-7,30,15));
        }
    }
    @Test void legacyCabinMarkersAreDiscardedDuringActiveDefenseRecovery() {
        var values=new HashMap<NamespacedKey,Object>();values.put(new NamespacedKey("noqui","original_state"),"legacy malformed cabin");values.put(new NamespacedKey("noqui","original_inventory"),new byte[]{1});
        values.put(new NamespacedKey("noqui","bgm_original_present"),(byte)1);values.put(new NamespacedKey("noqui","bgm_original_value"),(byte)-9);
        var p=mock(Player.class);var container=data(values);when(p.getPersistentDataContainer()).thenReturn(container);new PlayerRecovery().recover(p,true,true);
        assertEquals((byte)-9,values.get(new NamespacedKey("momadefense","bgm_muted")));verify(p,never()).getInventory();verify(p,never()).setGameMode(any());assertEquals(1,values.size());
    }
}
