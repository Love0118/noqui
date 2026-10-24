package dev.noqui.paper;

import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Cabin-owned tools keep their moved positions; the vault persists them with the other inventory. */
final class CabinNavigation {
    private static final NamespacedKey KEY=new NamespacedKey("noqui","tool");
    private static final List<String> IDS=List.of("defense","settings","traits","wardrobe");
    private final Function<String,ItemStack> factory;
    CabinNavigation(){this(CabinNavigation::item);}
    CabinNavigation(Function<String,ItemStack> factory){this.factory=factory;}
    static String id(ItemStack item){if(item==null||!item.hasItemMeta())return null;String id=item.getItemMeta().getPersistentDataContainer().get(KEY,PersistentDataType.STRING);return id!=null&&IDS.contains(id)?id:null;}
    static boolean isTool(ItemStack item){return id(item)!=null;}
    static ItemStack item(String id) {
        Material type;String name,lore;NamedTextColor color;
        switch(id){
            case "defense"->{type=Material.COMPASS;name="미니게임";lore="우클릭: 미니게임";color=NamedTextColor.AQUA;}
            case "settings"->{type=Material.COMPARATOR;name="환경설정";lore="우클릭: 환경설정";color=NamedTextColor.YELLOW;}
            case "traits"->{type=Material.ENCHANTED_BOOK;name="특성 선택";lore="우클릭: 특성 장착 및 해제";color=NamedTextColor.LIGHT_PURPLE;}
            case "wardrobe"->{type=Material.LEATHER_CHESTPLATE;name="치장";lore="우클릭: 장착 및 해제";color=NamedTextColor.LIGHT_PURPLE;}
            default->throw new IllegalArgumentException("Unknown cabin navigation tool");
        }
        var item=new ItemStack(type);var meta=item.getItemMeta();meta.setMaxStackSize(1);
        meta.displayName(Component.text(name,color).decoration(TextDecoration.ITALIC,false));
        meta.lore(List.of(Component.text(lore,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)));
        meta.getPersistentDataContainer().set(KEY,PersistentDataType.STRING,id);item.setItemMeta(meta);return item;
    }
    void ensure(Player player) {
        var inventory=player.getInventory();var present=new HashSet<String>();
        for(var item:inventory.getContents()){String id=id(item);if(id!=null)present.add(id);}
        String cursor=id(player.getItemOnCursor());if(cursor!=null)present.add(cursor);
        int storage=inventory.getStorageContents().length;
        for(String id:IDS)if(!present.contains(id))for(int slot=0;slot<storage;slot++){
            var item=inventory.getItem(slot);if(item==null||item.getType()==Material.AIR){inventory.setItem(slot,factory.apply(id));break;}
        }
    }
    static boolean blocks(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return false;
        var current=event.getCurrentItem();var cursor=event.getCursor();
        ItemStack swapped=event.getClick()==ClickType.NUMBER_KEY&&event.getHotbarButton()>=0?player.getInventory().getItem(event.getHotbarButton()):event.getClick()==ClickType.SWAP_OFFHAND?player.getInventory().getItemInOffHand():null;
        if(!isTool(current)&&!isTool(cursor)&&!isTool(swapped))return false;
        switch(event.getAction()){
            case DROP_ALL_CURSOR,DROP_ONE_CURSOR->{return isTool(cursor);}
            case DROP_ALL_SLOT,DROP_ONE_SLOT,CLONE_STACK->{return isTool(current);}
            default->{}
        }
        var inventory=player.getInventory();
        if(event.getClickedInventory()!=inventory)return isTool(current)||isTool(cursor)||isTool(swapped);
        var top=event.getView().getTopInventory();boolean personal=top instanceof org.bukkit.inventory.CraftingInventory crafting&&crafting.getMatrix().length==4;
        return event.isShiftClick()&&!personal&&isTool(current);
    }
    static boolean blocks(InventoryDragEvent event){return isTool(event.getOldCursor())&&event.getRawSlots().stream().anyMatch(slot->slot<event.getView().getTopInventory().getSize());}
}
