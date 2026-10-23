package dev.noqui.lobby;

import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;

/** Mutable Bukkit inventory/PDC boundary; the real gadget and wallet services run against it. */
final class GadgetTestSupport {
    static PersistentDataContainer data() {
        var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(data.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));
        when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        when(data.has(any(),any())).thenAnswer(c->values.containsKey(c.getArgument(0)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),any(),any());
        doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(data).remove(any());return data;
    }
    static ItemStack item(LobbyWallets.Gadget gadget) {
        ItemStack item=mock(ItemStack.class);var meta=mock(ItemMeta.class);var data=data();
        data.set(new NamespacedKey("noqui","gadget_serial"),PersistentDataType.STRING,gadget.serial().toString());
        data.set(new NamespacedKey("noqui","gadget_product"),PersistentDataType.STRING,gadget.product());
        when(item.getType()).thenReturn(Material.PAPER);when(item.getAmount()).thenReturn(1);when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);when(meta.getPersistentDataContainer()).thenReturn(data);return item;
    }
    static final class Inventory {
        final PlayerInventory inventory=mock(PlayerInventory.class);
        final ItemStack[] slots=new ItemStack[41];
        ItemStack cursor;
        Inventory(Player player) {
            var persistent=data();when(player.getInventory()).thenReturn(inventory);when(player.getPersistentDataContainer()).thenReturn(persistent);
            when(player.getItemOnCursor()).thenAnswer(c->cursor);doAnswer(c->{cursor=c.getArgument(0);return null;}).when(player).setItemOnCursor(nullable(ItemStack.class));
            when(inventory.getSize()).thenReturn(slots.length);when(inventory.getContents()).thenAnswer(c->slots.clone());
            when(inventory.getStorageContents()).thenAnswer(c->Arrays.copyOf(slots,36));
            when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);
            when(inventory.getItem(any(EquipmentSlot.class))).thenAnswer(c->slots[slot(c.getArgument(0))]);
            doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
            when(inventory.getItemInMainHand()).thenAnswer(c->slots[0]);
            doAnswer(c->{slots[0]=c.getArgument(0);return null;}).when(inventory).setItemInMainHand(nullable(ItemStack.class));
        }
        static int slot(EquipmentSlot slot){return switch(slot){case HAND->0;case OFF_HAND->40;case FEET->36;case LEGS->37;case CHEST->38;case HEAD->39;default->throw new IllegalArgumentException();};}
    }
}
