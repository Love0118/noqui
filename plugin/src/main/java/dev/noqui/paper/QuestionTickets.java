package dev.noqui.paper;

import dev.noqui.core.CreditLedger;
import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Held/hidden balances and transferable ground claims share one durable ledger. */
public final class QuestionTickets implements Listener {
    public static final class DeliveryPending extends RuntimeException {public DeliveryPending(){super("출제권 지급 대기");}}
    private static final NamespacedKey OWNER=new NamespacedKey("noqui","question_ticket_owner");
    private static final NamespacedKey CLAIM=new NamespacedKey("noqui","question_ticket_claim");
    private static final NamespacedKey VERSION=new NamespacedKey("noqui","question_ticket_version");
    private static final Component DISPLAY_NAME=Component.text("출제권",NamedTextColor.GOLD).decoration(TextDecoration.ITALIC,false);
    private static final List<Component> LORE=List.of(Component.text("우클릭: 노래 제출",NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false),Component.text("노래퀴즈에서 사용",NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC,false));
    private final CreditLedger ledger;
    private final BooleanSupplier active;
    private final Predicate<Player> visible;
    private final Consumer<Player> openForm;
    private final BiFunction<UUID,Integer,ItemStack> factory;
    private final Map<UUID,Integer> clicks=new HashMap<>();
    private final Map<UUID,Item> groundItems=new HashMap<>();
    private final Set<UUID> retaining=new HashSet<>();
    private final Set<UUID> unloaded=new HashSet<>();
    private final Map<UUID,CreditLedger.Ground> checkpoints=new HashMap<>();
    private int groundPasses;
    QuestionTickets(CreditLedger ledger,BooleanSupplier active,Consumer<Player> openForm){this(ledger,active,p->true,openForm,QuestionTickets::ticket);}
    QuestionTickets(CreditLedger ledger,BooleanSupplier active,Consumer<Player> openForm,BiFunction<UUID,Integer,ItemStack> factory){this(ledger,active,p->true,openForm,factory);}
    QuestionTickets(CreditLedger ledger,BooleanSupplier active,Predicate<Player> visible,Consumer<Player> openForm){this(ledger,active,visible,openForm,QuestionTickets::ticket);}
    QuestionTickets(CreditLedger ledger,BooleanSupplier active,Predicate<Player> visible,Consumer<Player> openForm,BiFunction<UUID,Integer,ItemStack> factory){this.ledger=ledger;this.active=active;this.visible=visible;this.openForm=openForm;this.factory=factory;}
    /** Shop preview has no authentic ticket marker. */
    public static ItemStack displayItem(){var item=new ItemStack(Material.PAPER);updateStyle(item);return item;}
    private static boolean updateStyle(ItemStack item) {
        var meta=item.getItemMeta();if(DISPLAY_NAME.equals(meta.displayName())&&LORE.equals(meta.lore()))return false;
        meta.displayName(DISPLAY_NAME);meta.lore(LORE);item.setItemMeta(meta);return true;
    }
    private static ItemStack ticket(UUID owner,int amount) {
        ItemStack item=displayItem();item.setAmount(amount);var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(OWNER,PersistentDataType.STRING,owner.toString());meta.getPersistentDataContainer().set(VERSION,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);return item;
    }
    private ItemStack groundItem(UUID claim,int amount) {
        ItemStack item=factory.apply(claim,amount);var meta=item.getItemMeta();var data=meta.getPersistentDataContainer();
        data.remove(OWNER);data.set(CLAIM,PersistentDataType.STRING,claim.toString());data.set(VERSION,PersistentDataType.BYTE,(byte)2);item.setItemMeta(meta);return item;
    }
    private static byte version(ItemStack item){return item!=null&&item.getType()==Material.PAPER&&item.hasItemMeta()?item.getItemMeta().getPersistentDataContainer().getOrDefault(VERSION,PersistentDataType.BYTE,(byte)0):0;}
    static boolean marked(ItemStack item){byte version=version(item);return version==1||version==2;}
    static boolean owned(ItemStack item,UUID owner){return version(item)==1&&owner.toString().equals(item.getItemMeta().getPersistentDataContainer().get(OWNER,PersistentDataType.STRING));}
    private static UUID claim(ItemStack item){if(version(item)!=2)return null;try{return UUID.fromString(item.getItemMeta().getPersistentDataContainer().get(CLAIM,PersistentDataType.STRING));}catch(RuntimeException ignored){return null;}}
    private UUID claim(Item item){UUID id=claim(item.getItemStack());return id!=null?id:ledger.dropped(item.getUniqueId())!=null?item.getUniqueId():null;}
    private static boolean empty(ItemStack item){return item==null||item.getType()==Material.AIR;}
    private long held(Player player) {
        long amount=0;var inventory=player.getInventory();UUID id=player.getUniqueId();
        for(int slot=0;slot<inventory.getSize();slot++)if(owned(inventory.getItem(slot),id))amount+=inventory.getItem(slot).getAmount();
        if(owned(player.getItemOnCursor(),id))amount+=player.getItemOnCursor().getAmount();return amount;
    }
    boolean has(Player player){return active.getAsBoolean()&&visible.test(player)&&ledger.balance(player.getUniqueId())>0&&held(player)>0;}
    void refresh(UUID player){Player online=Bukkit.getPlayer(player);if(online!=null)refresh(online);}
    long pending(UUID player){Player online=Bukkit.getPlayer(player);return online==null?ledger.balance(player):pending(online);}
    long pending(Player player){return Math.max(0,ledger.balance(player.getUniqueId())-held(player));}
    void strip(Player player) {
        var inventory=player.getInventory();boolean changed=false;
        if(marked(player.getItemOnCursor())){player.setItemOnCursor(null);changed=true;}
        for(int slot=0;slot<inventory.getSize();slot++)if(marked(inventory.getItem(slot))){inventory.setItem(slot,null);changed=true;}
        if(changed)player.saveData();
    }
    /** Cursor and offhand units participate in reconciliation; moving them never creates replacement units. */
    void refresh(Player player) {
        if(!active.getAsBoolean()||!visible.test(player)){strip(player);return;}
        UUID id=player.getUniqueId();var inventory=player.getInventory();int storage=inventory.getStorageContents().length;
        long remaining=ledger.balance(id);boolean changed=false;ItemStack cursor=player.getItemOnCursor();
        if(marked(cursor)) {
            if(!owned(cursor,id)||remaining==0){player.setItemOnCursor(null);changed=true;}
            else {int keep=(int)Math.min(Math.min(64,cursor.getAmount()),remaining);remaining-=keep;
                boolean style=updateStyle(cursor);if(cursor.getAmount()!=keep||style){cursor.setAmount(keep);player.setItemOnCursor(cursor);changed=true;}}
        }
        for(int slot=0;slot<inventory.getSize();slot++) {
            ItemStack item=inventory.getItem(slot);if(!marked(item))continue;
            if(!owned(item,id)||remaining==0){inventory.setItem(slot,null);changed=true;continue;}
            int keep=(int)Math.min(Math.min(64,item.getAmount()),remaining);remaining-=keep;
            boolean style=updateStyle(item);if(item.getAmount()!=keep||style){item.setAmount(keep);inventory.setItem(slot,item);changed=true;}
        }
        for(int slot=0;slot<storage&&remaining>0;slot++) {
            ItemStack item=inventory.getItem(slot);if(!empty(item)&&!owned(item,id))continue;
            int previous=empty(item)?0:item.getAmount();int add=(int)Math.min(64-previous,remaining);if(add==0)continue;
            inventory.setItem(slot,factory.apply(id,previous+add));remaining-=add;changed=true;
        }
        if(changed)player.saveData();
    }
    void submit(Player player,Runnable acceptedSubmission) {
        if(!has(player))throw new IllegalArgumentException("사용할 출제권이 없습니다.");
        long before=ledger.balance(player.getUniqueId());acceptedSubmission.run();
        if(ledger.balance(player.getUniqueId())<before) {
            var inventory=player.getInventory();int held=inventory.getHeldItemSlot();int slot=owned(inventory.getItem(held),player.getUniqueId())?held:-1;
            if(slot<0)for(int i=0;i<inventory.getSize();i++)if(owned(inventory.getItem(i),player.getUniqueId())){slot=i;break;}
            if(slot>=0){var item=inventory.getItem(slot);if(item.getAmount()==1)inventory.setItem(slot,null);else{item.setAmount(item.getAmount()-1);inventory.setItem(slot,item);}}
            else if(owned(player.getItemOnCursor(),player.getUniqueId())){var item=player.getItemOnCursor();if(item.getAmount()==1)player.setItemOnCursor(null);else{item.setAmount(item.getAmount()-1);player.setItemOnCursor(item);}}
            player.saveData();
        }
        refresh(player);
    }
    private void use(Player player) {
        int tick=Bukkit.getCurrentTick();if(clicks.getOrDefault(player.getUniqueId(),-1)==tick)return;
        clicks.put(player.getUniqueId(),tick);refresh(player);if(has(player))try{openForm.accept(player);}catch(IllegalArgumentException error){player.sendMessage(Component.text(error.getMessage()));}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND||!(event.getAction()==Action.RIGHT_CLICK_AIR||event.getAction()==Action.RIGHT_CLICK_BLOCK)||!owned(event.getItem(),event.getPlayer().getUniqueId()))return;
        event.setCancelled(true);use(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void inventory(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return;
        boolean ticket=marked(event.getCurrentItem())||marked(event.getCursor())||event.getHotbarButton()>=0&&marked(player.getInventory().getItem(event.getHotbarButton()));
        if(!ticket)return;
        // Personal moves, splitting, offhand and dropping are ordinary inventory operations. Tickets are not crafting materials or container deposits.
        if(event.getClickedInventory()!=null&&event.getClickedInventory()!=player.getInventory())event.setCancelled(true);
        var top=event.isShiftClick()?event.getView().getTopInventory():null;
        if(event.isShiftClick()&&top!=player.getInventory()&&!(top instanceof CraftingInventory crafting&&crafting.getMatrix()!=null&&crafting.getMatrix().length==4))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(marked(event.getOldCursor())&&event.getRawSlots().stream().anyMatch(slot->event.getView().getInventory(slot)!=event.getWhoClicked().getInventory()))event.setCancelled(true);}
    private static CreditLedger.Ground position(Item item){var p=item.getLocation();return new CreditLedger.Ground(p.getWorld().getUID(),p.getX(),p.getY(),p.getZ(),Math.max(0,item.getTicksLived()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent event) {
        var player=event.getPlayer();var entity=event.getItemDrop();var stack=entity.getItemStack();if(!owned(stack,player.getUniqueId()))return;
        if(!active.getAsBoolean()||!visible.test(player)){event.setCancelled(true);return;}
        try{UUID id=entity.getUniqueId();ledger.drop(id,player.getUniqueId(),stack.getAmount(),position(entity));entity.setItemStack(groundItem(id,stack.getAmount()));entity.setCanMobPickup(false);groundItems.put(id,entity);player.saveData();}
        catch(java.io.UncheckedIOException|IllegalArgumentException error){event.setCancelled(true);player.sendMessage(Component.text("출제권을 떨어뜨리지 못했습니다. 다시 시도하세요."));}
    }
    private int capacity(Player player) {
        if(!active.getAsBoolean()||!visible.test(player))return 64;int space=0;
        for(var item:player.getInventory().getStorageContents())if(empty(item))space+=64;else if(owned(item,player.getUniqueId()))space+=Math.max(0,64-item.getAmount());return space;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent event) {
        var entity=event.getItem();if(!marked(entity.getItemStack()))return;event.setCancelled(true);
        if(!(event.getEntity() instanceof Player player))return;
        UUID id=claim(entity);var value=id==null?null:ledger.dropped(id);
        if(value==null){removeRetaining(entity);return;}
        try{int received=ledger.pickup(id,player.getUniqueId(),capacity(player));if(received==0)return;
            var remainder=ledger.dropped(id);if(remainder==null){groundItems.remove(id);removeRetaining(entity);}else entity.setItemStack(groundItem(id,remainder.amount()));
            refresh(player);
        }catch(java.io.UncheckedIOException error){player.sendMessage(Component.text("출제권을 줍지 못했습니다. 다시 시도하세요."));}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void hopper(InventoryPickupItemEvent event){if(marked(event.getItem().getItemStack()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void merge(ItemMergeEvent event){if(marked(event.getEntity().getItemStack())||marked(event.getTarget().getItemStack()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void despawn(ItemDespawnEvent event){UUID id=claim(event.getEntity());if(id==null)return;try{ledger.retireDrop(id);groundItems.remove(id);}catch(java.io.UncheckedIOException error){event.setCancelled(true);}}
    @EventHandler(priority=EventPriority.MONITOR) public void removed(EntityRemoveEvent event) {
        if(!(event.getEntity() instanceof Item item)||retaining.contains(item.getUniqueId()))return;UUID id=claim(item);if(id==null||ledger.dropped(id)==null)return;
        if(event.getCause()==EntityRemoveEvent.Cause.UNLOAD){checkpoints.put(id,position(item));unloaded.add(id);}else ledger.retireDrop(id);groundItems.remove(id);
    }
    private void removeRetaining(Item item){retaining.add(item.getUniqueId());try{item.remove();}finally{retaining.remove(item.getUniqueId());}}
    @EventHandler(priority=EventPriority.MONITOR) public void loaded(EntitiesLoadEvent event) {
        for(var entity:event.getEntities())if(entity instanceof Item item&&marked(item.getItemStack())) {
            UUID id=claim(item);if(id==null||ledger.dropped(id)==null){removeRetaining(item);continue;}
            var existing=groundItems.get(id);if(existing!=null&&existing.isValid()&&existing!=item){removeRetaining(item);continue;}
            unloaded.remove(id);groundItems.put(id,item);if(!active.getAsBoolean()){checkpoints.put(id,position(item));removeRetaining(item);groundItems.remove(id);}
        }
    }
    /** Recreate only outstanding claims; consumed/despawned claims can never be reconstructed. */
    void refreshGround() {
        int restored=0;boolean visible=active.getAsBoolean();
        for(var entry:ledger.dropped().entrySet()) {
            UUID id=entry.getKey();var value=entry.getValue();var item=groundItems.get(id);
            if(item!=null&&item.isValid()) {
                checkpoints.put(id,position(item));
                if(!visible){removeRetaining(item);groundItems.remove(id);}
                else if(!id.equals(claim(item.getItemStack()))||item.getItemStack().getAmount()!=value.amount())item.setItemStack(groundItem(id,value.amount()));
                continue;
            }
            groundItems.remove(id);if(!visible||unloaded.contains(id)||restored>=8)continue;
            var p=checkpoints.getOrDefault(id,value.ground());var world=Bukkit.getWorld(p.world());if(world==null)continue;restored++;
            var location=new Location(world,p.x(),p.y(),p.z());
            for(var entity:world.getChunkAt(location).getEntities())if(entity instanceof Item candidate&&id.equals(claim(candidate))) {
                if(item==null||!item.isValid())item=candidate;else if(item!=candidate)removeRetaining(candidate);
            }
            if(item==null||!item.isValid())item=world.dropItem(location,groundItem(id,value.amount()));
            item.setItemStack(groundItem(id,value.amount()));item.setTicksLived(Math.max(1,p.age()));item.setCanMobPickup(false);groundItems.put(id,item);
        }
        if(++groundPasses%30==0||!visible){ledger.checkpointDrops(checkpoints);checkpoints.clear();}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent event){event.getDrops().removeIf(QuestionTickets::marked);if(!event.getKeepInventory())strip(event.getEntity());}
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent event){refresh(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event){refresh(event.getPlayer());clicks.remove(event.getPlayer().getUniqueId());}
}
