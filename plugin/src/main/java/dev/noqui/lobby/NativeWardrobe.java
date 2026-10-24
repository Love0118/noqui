package dev.noqui.lobby;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Real equipment stacks follow the client's head/torso bones without helper entities. */
final class NativeWardrobe {
    static final String LEGACY_TAG="noqui_lobby_cosmetic_v1";
    private static final NamespacedKey MARKER=new NamespacedKey("noqui","wardrobe_owner");
    private final Map<UUID,Player> players=new HashMap<>();

    static boolean managed(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(MARKER,PersistentDataType.STRING);}
    void purge(){for(World world:Bukkit.getWorlds())for(Entity entity:world.getEntities())if((entity instanceof ItemDisplay||entity instanceof ArmorStand)&&entity.getScoreboardTags().contains(LEGACY_TAG))entity.remove();}
    void tick(Player player,Map<String,String> selected) {
        players.put(player.getUniqueId(),player);
        if(player.isDead()||player.isInvisible()||player.getGameMode()==GameMode.SPECTATOR){remove(player);return;}
        var inventory=player.getInventory();
        // A crash may leave a managed armor stack in player data. It is a view of
        // the durable wardrobe selection, never a transferable second product.
        for(int slot=0;slot<inventory.getSize();slot++)if(slot!=39&&slot!=38&&managed(inventory.getItem(slot)))inventory.setItem(slot,null);
        if(managed(player.getItemOnCursor()))player.setItemOnCursor(null);
        String head=selected.getOrDefault("head",""),face=selected.getOrDefault("face","");
        equip(player,EquipmentSlot.HEAD,head.isEmpty()&&face.isEmpty()?null:head(player.getUniqueId(),head,face));
        String back=selected.get("back");ItemStack chest=inventory.getItem(EquipmentSlot.CHEST);
        if("gravity_suit".equals(GadgetItems.product(chest))) {
            if(model(chest,new NamespacedKey("noqui",back==null?"gravity_suit":"wearable_back_gravity/"+back)))inventory.setItem(EquipmentSlot.CHEST,chest);
        } else equip(player,EquipmentSlot.CHEST,back==null?null:back(player.getUniqueId(),back));
    }
    private static ItemStack head(UUID owner,String head,String face) {
        ItemStack item=item(owner,EquipmentSlot.HEAD,"치장");var meta=item.getItemMeta();
        meta.setItemModel(new NamespacedKey("noqui","wearable_head"));
        var data=meta.getCustomModelDataComponent();data.setStrings(List.of(head,face));meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);return item;
    }
    private static ItemStack back(UUID owner,String id) {
        ItemStack item=item(owner,EquipmentSlot.CHEST,"치장");var meta=item.getItemMeta();
        meta.setItemModel(new NamespacedKey("noqui",id));item.setItemMeta(meta);
        model(item,new NamespacedKey("noqui","wearable_back/"+id));return item;
    }
    private static ItemStack item(UUID owner,EquipmentSlot slot,String title) {
        ItemStack item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(MARKER,PersistentDataType.STRING,owner.toString());meta.setMaxStackSize(1);
        meta.displayName(Component.text(title).decoration(TextDecoration.ITALIC,false));
        var wearable=meta.getEquippable();wearable.setSlot(slot);wearable.setAllowedEntities(EntityType.PLAYER);
        wearable.setModel(null);wearable.setSwappable(false);wearable.setDispensable(false);
        wearable.setDamageOnHurt(false);wearable.setEquipOnInteract(false);meta.setEquippable(wearable);item.setItemMeta(meta);return item;
    }
    private static boolean model(ItemStack item,NamespacedKey key) {
        var meta=item.getItemMeta();var wearable=meta.getEquippable();if(key.equals(wearable.getModel()))return false;
        wearable.setModel(key);meta.setEquippable(wearable);item.setItemMeta(meta);return true;
    }
    private static void equip(Player player,EquipmentSlot slot,ItemStack desired) {
        var inventory=player.getInventory();ItemStack current=inventory.getItem(slot);
        if(desired==null){if(managed(current))inventory.setItem(slot,null);return;}
        if(desired.equals(current))return;
        if(current!=null&&current.getType()!=Material.AIR&&!managed(current)) {
            int free=-1;ItemStack[] storage=inventory.getStorageContents();
            for(int i=0;i<storage.length;i++)if(storage[i]==null||storage[i].getType()==Material.AIR){free=i;break;}
            if(free<0)return;
            // Real gear is moved to an ordinary persisted pocket before replacement.
            inventory.setItem(free,current);
        }
        inventory.setItem(slot,desired);
    }
    void remove(Player player) {
        var inventory=player.getInventory();
        for(int slot=0;slot<inventory.getSize();slot++)if(managed(inventory.getItem(slot)))inventory.setItem(slot,null);
        if(managed(player.getItemOnCursor()))player.setItemOnCursor(null);
        ItemStack chest=inventory.getItem(EquipmentSlot.CHEST);
        if("gravity_suit".equals(GadgetItems.product(chest))&&model(chest,new NamespacedKey("noqui","gravity_suit")))inventory.setItem(EquipmentSlot.CHEST,chest);
    }
    void leave(UUID id){Player player=players.remove(id);if(player!=null)remove(player);}
    void leave(Player player){players.remove(player.getUniqueId());remove(player);}
    void close(){for(UUID id:List.copyOf(players.keySet()))leave(id);}
    void click(InventoryClickEvent event) {
        if(managed(event.getCurrentItem())||managed(event.getCursor())){event.setCancelled(true);return;}
        if(event.getWhoClicked() instanceof Player player) {
            int button=event.getHotbarButton();
            if(button>=0&&managed(player.getInventory().getItem(button)))event.setCancelled(true);
            if(event.getClick()==ClickType.SWAP_OFFHAND&&managed(player.getInventory().getItemInOffHand()))event.setCancelled(true);
        }
    }
    void drag(InventoryDragEvent event){if(managed(event.getOldCursor()))event.setCancelled(true);}
}
