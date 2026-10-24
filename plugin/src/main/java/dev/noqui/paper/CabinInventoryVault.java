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
        return restore(player,true);
    }
    boolean restoreShared(Player player){return restore(player,false);}
    private boolean restore(Player player,boolean cabin) {
        var data=player.getPersistentDataContainer();byte[] bytes=data.get(ITEMS,PersistentDataType.BYTE_ARRAY);if(bytes==null)return false;
        ItemStack[] items=ItemStack.deserializeItemsFromBytes(bytes);int size=player.getInventory().getSize();
        if(items.length!=size+1)throw new IllegalStateException("Invalid cabin inventory vault");
        if(!cabin)for(int i=0;i<items.length;i++)if(transientItem(items[i]))items[i]=null;
        player.getInventory().setContents(Arrays.copyOf(items,size));player.setItemOnCursor(items[size]);
        player.getInventory().setHeldItemSlot(Math.clamp(data.getOrDefault(HELD,PersistentDataType.INTEGER,0),0,8));if(cabin)markLoaded(player);return true;
    }
    void markLoaded(Player player){player.getPersistentDataContainer().set(LOADED,PersistentDataType.BYTE,(byte)1);player.saveData();}
    void capture(Player player) {
        capture(player,false);
    }
    void captureShared(Player player){capture(player,true);}
    private void capture(Player player,boolean shared) {
        var inventory=player.getInventory();ItemStack[] items=Arrays.copyOf(inventory.getContents(),inventory.getSize()+1);items[inventory.getSize()]=player.getItemOnCursor();
        if(shared)for(int i=0;i<items.length;i++)if(transientItem(items[i]))items[i]=null;
        var data=player.getPersistentDataContainer();data.set(ITEMS,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(items));data.set(HELD,PersistentDataType.INTEGER,inventory.getHeldItemSlot());player.saveData();
    }
    private static boolean transientItem(ItemStack item){
        if(item==null||item.isEmpty())return false;
        if(CabinNavigation.isTool(item)||QuestionTickets.marked(item))return true;
        var keys=item.getPersistentDataContainer().getKeys();
        return keys.stream().anyMatch(k->k.getNamespace().equals("momadefense")&&java.util.Set.of("mining_tool","mine_navigation","mining_cargo").contains(k.getKey()));
    }
    void leave(Player player){capture(player);player.getPersistentDataContainer().remove(LOADED);player.setItemOnCursor(null);player.saveData();}
}
