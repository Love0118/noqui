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
        var p=mock(PersistentDataContainer.class);
        when(p.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));
        when(p.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        when(p.has(any(),any())).thenAnswer(c->values.containsKey(c.getArgument(0)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(p).set(any(),any(),any());
        doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(p).remove(any());return p;
    }
    @Test void originalRawPreferenceAndInventorySurviveToggleAndServiceRecreation() {
        for(Byte original:Arrays.asList(null,(byte)0,(byte)1,(byte)7)) {
            var values=new HashMap<NamespacedKey,Object>();var key=new NamespacedKey("momadefense","bgm_muted");if(original!=null)values.put(key,original);
            Player player=mock(Player.class);var inventory=mock(PlayerInventory.class);var world=mock(World.class);UUID worldId=UUID.randomUUID();
            var container=data(values);when(world.getUID()).thenReturn(worldId);when(player.getPersistentDataContainer()).thenReturn(container);when(player.getInventory()).thenReturn(inventory);
            when(player.getLocation()).thenReturn(new Location(world,4,65,-7,30,15));when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getAllowFlight()).thenReturn(true);when(player.isFlying()).thenReturn(true);when(inventory.getHeldItemSlot()).thenReturn(5);
            var items=new ItemStack[]{mock(ItemStack.class)};when(inventory.getContents()).thenReturn(items);byte[] bytes={1,2,3};
            try(var stacks=mockStatic(ItemStack.class);var bukkit=mockStatic(Bukkit.class)) {
                stacks.when(()->ItemStack.serializeItemsAsBytes(items)).thenReturn(bytes);stacks.when(()->ItemStack.deserializeItemsFromBytes(bytes)).thenReturn(items);
                bukkit.when(()->Bukkit.getWorld(worldId)).thenReturn(world);
                var recovery=new PlayerRecovery();UUID membership=recovery.enter(player);assertNotNull(membership);assertEquals((byte)1,values.get(key));
                assertThrows(IllegalStateException.class,()->recovery.enter(player));recovery.toggleBgm(player);assertEquals((byte)0,values.get(key));
                assertTrue(new PlayerRecovery().restore(player,true));assertEquals(original,values.get(key));assertFalse(recovery.pending(player));
                assertFalse(recovery.restore(player,true));verify(inventory).setContents(items);verify(inventory).setHeldItemSlot(5);verify(player).setGameMode(GameMode.SURVIVAL);
                verify(player).setAllowFlight(true);verify(player).setFlying(true);verify(player).teleport(new Location(world,4,65,-7,30,15));verify(player,times(3)).saveData();
            }
        }
    }
}
