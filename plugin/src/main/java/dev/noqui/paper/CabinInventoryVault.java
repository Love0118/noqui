package dev.noqui.paper;

import java.util.Arrays;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** The cabin inventory is permanent; PlayerRecovery separately owns the outside inventory transaction. */
final class CabinInventoryVault {
    private static final NamespacedKey ITEMS=new NamespacedKey("noqui","cabin_inventory_v1"),HELD=new NamespacedKey("noqui","cabin_held_slot"),LOADED=new NamespacedKey("noqui","cabin_inventory_loaded");
    boolean loaded(Player player){return player.getPersistentDataContainer().getOrDefault(LOADED,PersistentDataType.BYTE,(byte)0)==1;}
    boolean restore(Player player) {
        var data=player.getPersistentDataContainer();byte[] bytes=data.get(ITEMS,PersistentDataType.BYTE_ARRAY);if(bytes==null)return false;
        ItemStack[] items=ItemStack.deserializeItemsFromBytes(bytes);int size=player.getInventory().getSize();
        if(items.length!=size+1)throw new IllegalStateException("Invalid cabin inventory vault");
        player.getInventory().setContents(Arrays.copyOf(items,size));player.setItemOnCursor(items[size]);
        player.getInventory().setHeldItemSlot(Math.clamp(data.getOrDefault(HELD,PersistentDataType.INTEGER,0),0,8));markLoaded(player);return true;
    }
    void markLoaded(Player player){player.getPersistentDataContainer().set(LOADED,PersistentDataType.BYTE,(byte)1);player.saveData();}
    void capture(Player player) {
        var inventory=player.getInventory();ItemStack[] items=Arrays.copyOf(inventory.getContents(),inventory.getSize()+1);items[inventory.getSize()]=player.getItemOnCursor();
        var data=player.getPersistentDataContainer();data.set(ITEMS,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(items));data.set(HELD,PersistentDataType.INTEGER,inventory.getHeldItemSlot());player.saveData();
    }
    void leave(Player player){capture(player);player.getPersistentDataContainer().remove(LOADED);player.setItemOnCursor(null);player.saveData();}
}
