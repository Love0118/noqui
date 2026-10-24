package dev.noqui.lobby;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import static dev.noqui.lobby.LobbyCatalog.*;

/** Physical bearer items. Wallet records authorize issuance/use; absence never causes replacement. */
final class GadgetItems {
    private static final NamespacedKey SERIAL=new NamespacedKey("noqui","gadget_serial");
    private static final NamespacedKey PRODUCT=new NamespacedKey("noqui","gadget_product");
    private static final NamespacedKey PROOFS=new NamespacedKey("noqui","gadget_delivery_proofs");
    private static final Map<String,Product> PRODUCTS=new LinkedHashMap<>();
    private static final String FOOTER="오두막에서 사용, 버리기 가능";
    static { for(Product product:LobbyCatalog.defaults())if(product.kind()==Kind.GADGET)PRODUCTS.put(product.id(),product); }
    private final LobbyWallets wallets;
    private final World cabin;
    private final Function<LobbyWallets.Gadget,ItemStack> factory;

    GadgetItems(LobbyWallets wallets,World cabin){this(wallets,cabin,GadgetItems::item);}
    GadgetItems(LobbyWallets wallets,World cabin,Function<LobbyWallets.Gadget,ItemStack> factory){this.wallets=wallets;this.cabin=cabin;this.factory=factory;}

    static ItemStack preview(String id) {
        Product product=PRODUCTS.get(id);if(product==null)throw new IllegalArgumentException("Unknown gadget");
        ItemStack item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();
        meta.setItemModel(new NamespacedKey("noqui",id));meta.setMaxStackSize(1);
        meta.displayName(Component.text(product.label(),NamedTextColor.AQUA).decoration(TextDecoration.ITALIC,false));
        String action=switch(product.slot()){case "boots"->"우클릭: 신발 착용";case "suit"->"우클릭: 몸통 착용";default->id.equals("hammer")?"손에 들고 좌클릭":"손에 들고 Shift + 우클릭";};
        meta.lore(List.of(Component.text(action,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false),
                Component.text(FOOTER,NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC,false)));
        if(Set.of("boots","suit").contains(product.slot())) {
            var equippable=meta.getEquippable();equippable.setSlot(product.slot().equals("boots")?EquipmentSlot.FEET:EquipmentSlot.CHEST);
            equippable.setAllowedEntities(org.bukkit.entity.EntityType.PLAYER);equippable.setSwappable(true);
            equippable.setDispensable(false);equippable.setDamageOnHurt(false);equippable.setEquipOnInteract(true);
            equippable.setModel(new NamespacedKey("noqui",id));
            meta.setEquippable(equippable);
        }
        item.setItemMeta(meta);return item;
    }
    private static ItemStack item(LobbyWallets.Gadget gadget) {
        ItemStack item=preview(gadget.product());var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(SERIAL,PersistentDataType.STRING,gadget.serial().toString());
        meta.getPersistentDataContainer().set(PRODUCT,PersistentDataType.STRING,gadget.product());item.setItemMeta(meta);return item;
    }
    static UUID serial(ItemStack item) {
        if(item==null||item.getType()!=Material.PAPER||!item.hasItemMeta())return null;
        var data=item.getItemMeta().getPersistentDataContainer();String value=data.get(SERIAL,PersistentDataType.STRING);
        if(value==null||!PRODUCTS.containsKey(data.get(PRODUCT,PersistentDataType.STRING)))return null;
        try{return UUID.fromString(value);}catch(IllegalArgumentException ignored){return null;}
    }
    static boolean marked(ItemStack item){return serial(item)!=null;}
    static String product(ItemStack item){return marked(item)?item.getItemMeta().getPersistentDataContainer().get(PRODUCT,PersistentDataType.STRING):null;}
    private LobbyWallets.Gadget valid(ItemStack item,boolean delivered) {
        UUID serial=serial(item);if(serial==null||item.getAmount()!=1)return null;
        var gadget=wallets.gadget(serial);
        return gadget!=null&&(!delivered||gadget.delivered())&&gadget.product().equals(item.getItemMeta().getPersistentDataContainer().get(PRODUCT,PersistentDataType.STRING))?gadget:null;
    }
    String hand(Player player) {return active(player,EquipmentSlot.HAND);}
    private String active(Player player,EquipmentSlot slot) {
        if(!LobbyMovement.eligible(player,cabin))return null;
        var item=valid(player.getInventory().getItem(slot),true);return item==null?null:item.product();
    }
    Map<String,String> equipment(Player player) {
        var result=new HashMap<String,String>();String feet=active(player,EquipmentSlot.FEET),chest=active(player,EquipmentSlot.CHEST);
        if(feet!=null&&PRODUCTS.get(feet).slot().equals("boots"))result.put("boots",feet);
        if(chest!=null&&PRODUCTS.get(chest).slot().equals("suit"))result.put("suit",chest);
        return result;
    }
    long count(Player player,String product) {
        Set<UUID> ids=new HashSet<>();
        for(ItemStack item:held(player)){var gadget=valid(item,false);if(gadget!=null&&gadget.product().equals(product))ids.add(gadget.serial());}
        return ids.size();
    }
    boolean equipped(Player player,Product product) {
        return product.slot().equals("hand")?product.id().equals(hand(player)):product.id().equals(equipment(player).get(product.slot()));
    }
    void consumeHand(Player player,String product)throws IOException {
        ItemStack item=player.getInventory().getItemInMainHand();var gadget=valid(item,true);
        if(gadget==null||!gadget.product().equals(product))throw new IllegalArgumentException("사용할 장난감 없음");
        // Retire the unique serial before applying gameplay effects; stale snapshots cannot reuse it.
        wallets.consumeGadget(gadget.serial(),product);player.getInventory().setItemInMainHand(null);player.saveData();
    }

    void reconcile(Player player)throws IOException {
        if(!deliveryEligible(player))return;
        wallets.migrateGadgets(player.getUniqueId());
        sanitize(player);
        Set<UUID> proofs=proofs(player);
        for(var gadget:wallets.pendingGadgets(player.getUniqueId())) {
            UUID id=gadget.serial();
            if(!proofs.contains(id)) {
                boolean present=held(player).stream().anyMatch(item->id.equals(serial(item)));
                if(!present) {
                    int slot=freeSlot(player);if(slot<0)break;
                    player.getInventory().setItem(slot,factory.apply(gadget));
                }
                proofs.add(id);saveProofs(player,proofs);
            }
            // Persist the issued stack and proof together before acknowledging its wallet outbox.
            // If acknowledgement fails and the player drops it, this proof still prevents reminting.
            player.saveData();wallets.gadgetDelivered(id);
            proofs.remove(id);saveProofs(player,proofs);player.saveData();
        }
        if(proofs.removeIf(id->wallets.gadget(id)==null||wallets.gadget(id).delivered())){saveProofs(player,proofs);player.saveData();}
    }
    private boolean deliveryEligible(Player player){
        if(LobbyMovement.eligible(player,cabin))return true;
        if(!player.isOnline()||player.isDead())return false;
        var services=Bukkit.getServicesManager();var mining=services==null?null:services.load(dev.noqui.api.MiningService.class);
        return mining!=null&&mining.ownsInventory(player);
    }
    /** The core cabin vault owns layout/snapshots. Only invalid or duplicate serial copies are removed. */
    private void sanitize(Player player) {
        Set<UUID> held=new HashSet<>();boolean changed=false;var inventory=player.getInventory();
        for(int slot=0;slot<inventory.getSize();slot++) {
            ItemStack item=inventory.getItem(slot);if(!marked(item))continue;
            var gadget=valid(item,false);
            if(gadget==null||!held.add(gadget.serial())){inventory.setItem(slot,null);changed=true;}
            else if(refreshLegacyLore(item)|refreshEquipment(item)){inventory.setItem(slot,item);changed=true;}
        }
        ItemStack cursor=player.getItemOnCursor();if(marked(cursor)) {
            var gadget=valid(cursor,false);if(gadget==null||!held.add(gadget.serial())){player.setItemOnCursor(null);changed=true;}
            else if(refreshLegacyLore(cursor)|refreshEquipment(cursor)){player.setItemOnCursor(cursor);changed=true;}
        }
        if(changed)player.saveData();
    }
    static boolean refreshLegacyLore(ItemStack item) {
        var meta=item.getItemMeta();var lore=meta.lore();if(lore==null)return false;
        var updated=lore.stream().map(line->PlainTextComponentSerializer.plainText().serialize(line).equals("오두막에서 사용 \u00b7 버리기 가능")
                ?Component.text(FOOTER,NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC,false):line).toList();
        if(updated.equals(lore))return false;meta.lore(updated);item.setItemMeta(meta);return true;
    }
    private static boolean refreshEquipment(ItemStack item) {
        String id=product(item);if(!Set.of("triple_boots","quad_boots","gravity_suit").contains(id==null?"":id))return false;
        var meta=item.getItemMeta();var wearable=meta.getEquippable();NamespacedKey current=wearable.getModel();
        // A selected back can be layered over the authentic suit by NativeWardrobe.
        if(current!=null&&(current.equals(new NamespacedKey("noqui",id))||id.equals("gravity_suit")&&current.getNamespace().equals("noqui")&&current.getKey().startsWith("wearable_back_gravity/")))return false;
        wearable.setModel(new NamespacedKey("noqui",id));meta.setEquippable(wearable);item.setItemMeta(meta);return true;
    }
    private int freeSlot(Player player) {
        ItemStack[] items=player.getInventory().getStorageContents();
        // The mine keeps slot 4 reserved for its Noqui shortcut even while a quiz is stopped.
        boolean mine=player.getWorld()!=cabin;
        for(int i=0;i<items.length;i++)if(!(mine&&i==3)&&(items[i]==null||items[i].getType()==Material.AIR))return i;
        return -1;
    }
    private static List<ItemStack> held(Player player) {
        var items=new ArrayList<ItemStack>(Arrays.asList(player.getInventory().getContents()));items.add(player.getItemOnCursor());return items;
    }
    private static Set<UUID> proofs(Player player) {
        var result=new HashSet<UUID>();String saved=player.getPersistentDataContainer().getOrDefault(PROOFS,PersistentDataType.STRING,"");
        if(!saved.isEmpty())for(String value:saved.split("\n"))result.add(UUID.fromString(value));return result;
    }
    private static void saveProofs(Player player,Set<UUID> proofs) {
        var data=player.getPersistentDataContainer();
        if(proofs.isEmpty())data.remove(PROOFS);else data.set(PROOFS,PersistentDataType.STRING,String.join("\n",proofs.stream().map(UUID::toString).sorted().toList()));
    }
}
