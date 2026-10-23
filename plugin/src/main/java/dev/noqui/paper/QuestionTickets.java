package dev.noqui.paper;

import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Owner-bound physical tickets backed by the durable ledger, including undelivered/full-inventory units. */
public final class QuestionTickets implements Listener {
    public static final class DeliveryPending extends RuntimeException {public DeliveryPending(){super("출제권 지급 대기");}}
    private static final NamespacedKey OWNER=new NamespacedKey("noqui","question_ticket_owner");
    private static final NamespacedKey VERSION=new NamespacedKey("noqui","question_ticket_version");
    private static final Component DISPLAY_NAME=Component.text("출제권",NamedTextColor.GOLD).decoration(TextDecoration.ITALIC,false);
    private static final List<Component> LORE=List.of(Component.text("우클릭: 노래 제출",NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false),Component.text("개인 출제권",NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC,false));
    private final ToLongFunction<UUID> balance;
    private final Consumer<Player> openForm;
    private final BiFunction<UUID,Integer,ItemStack> factory;
    private final Map<UUID,Integer> clicks=new HashMap<>();
    QuestionTickets(ToLongFunction<UUID> balance,Consumer<Player> openForm){this(balance,openForm,QuestionTickets::ticket);}
    QuestionTickets(ToLongFunction<UUID> balance,Consumer<Player> openForm,BiFunction<UUID,Integer,ItemStack> factory){this.balance=balance;this.openForm=openForm;this.factory=factory;}
    /** Shop preview only: deliberately has no authentic ticket marker. */
    public static ItemStack displayItem() {
        var item=new ItemStack(Material.PAPER);updateStyle(item);return item;
    }
    private static boolean updateStyle(ItemStack item) {
        var meta=item.getItemMeta();if(DISPLAY_NAME.equals(meta.displayName())&&LORE.equals(meta.lore()))return false;
        meta.displayName(DISPLAY_NAME);meta.lore(LORE);item.setItemMeta(meta);return true;
    }
    private static ItemStack ticket(UUID owner,int amount) {
        ItemStack item=displayItem();item.setAmount(amount);var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(OWNER,PersistentDataType.STRING,owner.toString());meta.getPersistentDataContainer().set(VERSION,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);return item;
    }
    static boolean marked(ItemStack item){return item!=null&&item.getType()==Material.PAPER&&item.hasItemMeta()
            &&item.getItemMeta().getPersistentDataContainer().getOrDefault(VERSION,PersistentDataType.BYTE,(byte)0)==1;}
    static boolean owned(ItemStack item,UUID owner){return marked(item)&&owner.toString().equals(item.getItemMeta().getPersistentDataContainer().get(OWNER,PersistentDataType.STRING));}
    private static boolean empty(ItemStack item){return item==null||item.getType()==Material.AIR;}
    boolean has(Player player) {
        if(balance.applyAsLong(player.getUniqueId())<1)return false;
        return Arrays.stream(player.getInventory().getStorageContents()).anyMatch(item->owned(item,player.getUniqueId())&&item.getAmount()>0);
    }
    void refresh(UUID player){Player online=Bukkit.getPlayer(player);if(online!=null)refresh(online);}
    long pending(UUID player){Player online=Bukkit.getPlayer(player);return online==null?balance.applyAsLong(player):pending(online);}
    long pending(Player player){long held=Arrays.stream(player.getInventory().getStorageContents()).filter(item->owned(item,player.getUniqueId())).mapToLong(ItemStack::getAmount).sum();return Math.max(0,balance.applyAsLong(player.getUniqueId())-held);}
    void strip(Player player) {
        var inventory=player.getInventory();boolean changed=false;
        if(marked(player.getItemOnCursor())){player.setItemOnCursor(null);changed=true;}
        for(int slot=0;slot<inventory.getSize();slot++)if(marked(inventory.getItem(slot))){inventory.setItem(slot,null);changed=true;}
        if(changed)player.saveData();
    }
    /** Reconciles old inventory snapshots, duplicates and pending delivery against durable unreserved credit units. */
    void refresh(Player player) {
        UUID id=player.getUniqueId();var inventory=player.getInventory();int storage=inventory.getStorageContents().length;
        long remaining=balance.applyAsLong(id);boolean changed=false;
        if(marked(player.getItemOnCursor())){player.setItemOnCursor(null);changed=true;}
        for(int slot=0;slot<inventory.getSize();slot++) {
            ItemStack item=inventory.getItem(slot);if(!marked(item))continue;
            if(slot>=storage||!owned(item,id)||remaining==0){inventory.setItem(slot,null);changed=true;continue;}
            int keep=(int)Math.min(Math.min(64,item.getAmount()),remaining);remaining-=keep;
            if(item.getAmount()!=keep){item.setAmount(keep);inventory.setItem(slot,item);changed=true;}
            if(updateStyle(item)){inventory.setItem(slot,item);changed=true;}
        }
        for(int slot=0;slot<storage&&remaining>0;slot++) {
            ItemStack item=inventory.getItem(slot);
            if(!empty(item)&&!owned(item,id))continue;
            int previous=empty(item)?0:item.getAmount();int add=(int)Math.min(64-previous,remaining);if(add==0)continue;
            inventory.setItem(slot,factory.apply(id,previous+add));remaining-=add;changed=true;
        }
        if(changed)player.saveData();
    }
    /** Called after validation and durable reservation. No inventory mutation occurs on a rejected submission. */
    void submit(Player player,Runnable acceptedSubmission) {
        if(!has(player))throw new IllegalArgumentException("사용할 출제권이 없습니다.");
        long before=balance.applyAsLong(player.getUniqueId());acceptedSubmission.run();
        if(balance.applyAsLong(player.getUniqueId())<before) {
            var inventory=player.getInventory();int held=inventory.getHeldItemSlot();
            int slot=owned(inventory.getItem(held),player.getUniqueId())?held:-1;
            if(slot<0)for(int i=0;i<inventory.getStorageContents().length;i++)if(owned(inventory.getItem(i),player.getUniqueId())){slot=i;break;}
            if(slot>=0){var item=inventory.getItem(slot);if(item.getAmount()==1)inventory.setItem(slot,null);else{item.setAmount(item.getAmount()-1);inventory.setItem(slot,item);}player.saveData();}
        }
        refresh(player);
    }
    private void use(Player player) {
        int tick=Bukkit.getCurrentTick();if(clicks.getOrDefault(player.getUniqueId(),-1)==tick)return;
        clicks.put(player.getUniqueId(),tick);refresh(player);if(has(player))try{openForm.accept(player);}catch(IllegalArgumentException error){player.sendMessage(Component.text(error.getMessage()));}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND||!(event.getAction()==Action.RIGHT_CLICK_AIR||event.getAction()==Action.RIGHT_CLICK_BLOCK)
                ||!owned(event.getItem(),event.getPlayer().getUniqueId()))return;
        event.setCancelled(true);use(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void inventory(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return;
        ItemStack current=event.getCurrentItem(),cursor=event.getCursor();
        boolean hotbar=event.getHotbarButton()>=0&&marked(player.getInventory().getItem(event.getHotbarButton()));
        if(!marked(current)&&!marked(cursor)&&!hotbar)return;
        // Tickets cannot be moved into containers/crafting or duplicated via cursor/number-key operations.
        event.setCancelled(true);
        if(event.getClickedInventory()==player.getInventory()&&event.getClick()==ClickType.RIGHT&&owned(current,player.getUniqueId()))use(player);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(marked(event.getOldCursor()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent event){if(marked(event.getItemDrop().getItemStack()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent event){if(marked(event.getMainHandItem())||marked(event.getOffHandItem()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent event){event.getDrops().removeIf(QuestionTickets::marked);strip(event.getEntity());}
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent event){refresh(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event){refresh(event.getPlayer());clicks.remove(event.getPlayer().getUniqueId());}
}
