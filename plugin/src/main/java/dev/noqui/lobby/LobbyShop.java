package dev.noqui.lobby;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import static dev.noqui.lobby.LobbyCatalog.*;
import dev.noqui.lobby.LobbyCatalog.Currency;

/** Main-thread chest UI. Item stacks are presentation; only the current holder authorizes actions. */
final class LobbyShop implements AutoCloseable {
    private enum Section {
        COSMETICS("치장"),GADGETS("장난감"),TICKETS("출제권"),OWNED("보유");
        final String label;Section(String label){this.label=label;}
    }
    private record Page(Section section,int index) {}
    private static final int PAGE_SIZE=21;
    private static final class Holder implements InventoryHolder {
        final Player owner;
        final Map<Integer,Runnable> actions = new HashMap<>();
        Inventory inventory;
        boolean used;
        Holder(Player owner) { this.owner=owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final LobbyWallets wallets;
    private final List<Product> catalog;
    private final GadgetItems gadgets;
    private final Predicate<Player> eligible;
    private final Runnable delivery;
    private final Consumer<String> warning;
    private final Map<UUID,Holder> sessions = new HashMap<>();
    private final ArrayBlockingQueue<Runnable> queued = new ArrayBlockingQueue<>(256);
    private boolean closed;

    LobbyShop(LobbyWallets wallets, Collection<Product> catalog,GadgetItems gadgets, Predicate<Player> eligible,
              Runnable delivery, Consumer<String> warning) {
        this.wallets=wallets;this.catalog=List.copyOf(catalog);this.eligible=eligible;
        this.gadgets=gadgets;
        this.delivery=delivery;this.warning=warning;
    }

    static boolean owns(Inventory inventory) {
        return inventory!=null && inventory.getHolder() instanceof Holder holder && holder.inventory==inventory;
    }

    void open(Player player) { if(!closed && eligible.test(player))category(player,new Page(Section.COSMETICS,0)); }

    private Holder chest(Player player,String title) {
        Holder holder=new Holder(player);
        holder.inventory=Bukkit.createInventory(holder,54,Component.text(title));
        var wallet=wallets.wallet(player.getUniqueId());
        holder.inventory.setItem(4,item(Material.NETHER_STAR,"지갑",NamedTextColor.YELLOW,
                "✦ "+wallet.stars(),"정답 포인트 "+wallet.points()));
        return holder;
    }

    private void show(Holder holder) {
        if(closed || !eligible.test(holder.owner))return;
        // The close event for the old chest must not invalidate the replacement session.
        sessions.put(holder.owner.getUniqueId(),holder);
        holder.owner.openInventory(holder.inventory);
    }

    private void category(Player player,Page requested) {
        var products=catalog.stream().filter(product->switch(requested.section()){
            case COSMETICS->product.kind()==Kind.COSMETIC;
            case GADGETS->product.kind()==Kind.GADGET;
            case TICKETS->product.kind()==Kind.CREDIT;
            case OWNED->product.kind()!=Kind.CREDIT&&owned(player,product)>0;
        }).toList();
        int pages=Math.max(1,(products.size()+PAGE_SIZE-1)/PAGE_SIZE);var page=new Page(requested.section(),Math.clamp(requested.index(),0,pages-1));
        Holder holder=chest(player,"상점 "+page.section().label+(pages>1?" "+(page.index()+1)+"/"+pages:""));
        nav(holder,0,Material.LEATHER_HELMET,"치장",()->category(player,new Page(Section.COSMETICS,0)));
        nav(holder,1,Material.FEATHER,"장난감",()->category(player,new Page(Section.GADGETS,0)));
        nav(holder,2,Material.PAPER,"출제권",()->category(player,new Page(Section.TICKETS,0)));
        nav(holder,3,Material.CHEST,"보유",()->category(player,new Page(Section.OWNED,0)));
        int offset=page.index()*PAGE_SIZE;
        for(int i=0;i<Math.min(PAGE_SIZE,products.size()-offset);i++) {
            Product product=products.get(offset+i);int slot=19+(i/7)*9+i%7;
            holder.inventory.setItem(slot,productIcon(player,product));
            holder.actions.put(slot,()->product(player,product,page));
        }
        if(page.index()>0)nav(holder,45,Material.ARROW,"이전",()->category(player,new Page(page.section(),page.index()-1)));
        if(page.index()+1<pages)nav(holder,53,Material.ARROW,"다음",()->category(player,new Page(page.section(),page.index()+1)));
        nav(holder,49,Material.BARRIER,"닫기",player::closeInventory);
        show(holder);
    }

    private void product(Player player,Product product,Page origin) {
        Holder holder=chest(player,"상점 "+product.label());
        holder.inventory.setItem(13,productIcon(player,product));
        String transaction="shop:"+UUID.randomUUID();
        boolean durable=product.kind()==Kind.COSMETIC;
        long owned=owned(player,product);
        if(!durable || owned==0) {
            nav(holder,29,Material.EMERALD,"✦ "+product.stars()+" 구매",
                    ()->purchase(player,product,Currency.STARLIGHT,transaction,origin));
            if(product.points()>0)nav(holder,33,Material.EXPERIENCE_BOTTLE,"정답 포인트 "+product.points()+" 구매",
                    ()->purchase(player,product,Currency.POINTS,transaction,origin));
        }
        if(product.kind()==Kind.COSMETIC && owned>0) {
            boolean equipped=product.id().equals(wallets.wallet(player.getUniqueId()).equipped().get(product.slot()));
            nav(holder,22,Material.LIME_DYE,equipped?"해제":"장착",()->{
                try { wallets.equip(player.getUniqueId(),product); }
                catch(IOException error) { failed(player); }
                catch(IllegalArgumentException error) { message(player,error.getMessage(),NamedTextColor.RED); }
                product(player,product,origin);
            });
        }
        nav(holder,49,Material.ARROW,"돌아가기",()->category(player,origin));
        show(holder);
    }

    private void purchase(Player player,Product product,Currency currency,String transaction,Page origin) {
        try {
            wallets.purchase(player.getUniqueId(),product,currency,transaction);
            delivery.run();
            boolean pending=product.kind()==Kind.CREDIT?wallets.pending().stream().anyMatch(g->g.player().equals(player.getUniqueId()))
                    :product.kind()==Kind.GADGET&&wallets.pendingGadgets(player.getUniqueId()).stream().anyMatch(g->g.product().equals(product.id()));
            message(player,pending?product.label()+" 지급 대기: 인벤토리 공간을 확인하세요":"구매 완료",NamedTextColor.GREEN);
        } catch(IOException error) { failed(player); }
        catch(IllegalArgumentException error) { message(player,error.getMessage(),NamedTextColor.RED); }
        product(player,product,origin);
    }

    private void failed(Player player) {
        message(player,"저장 실패: 다시 시도하세요",NamedTextColor.RED);
        warning.accept("Lobby shop persistence failed; no unsaved purchase or equipment change was applied");
    }

    private ItemStack productIcon(Player player,Product product) {
        var wallet=wallets.wallet(player.getUniqueId());
        List<String> lore=new ArrayList<>();
        lore.add("✦ "+product.stars()+(product.points()>0?"  /  정답 포인트 "+product.points():" 별빛 전용"));
        if(product.kind()==Kind.CREDIT)lore.add("실물 1장, 사용 시 소모");
        else {
            lore.add("보유 "+owned(player,product));
            boolean equipped=product.kind()==Kind.GADGET?gadgets.equipped(player,product):product.id().equals(wallet.equipped().get(product.slot()));
            lore.add(equipped?"장착 중":"미장착");
        }
        String control=switch(product.id()) {
            case "hammer"->"좌클릭";
            case "snow_bomb"->"Shift + 우클릭";
            case "triple_boots","quad_boots"->"신발 착용 후 공중에서 Space";
            case "gravity_suit"->"우클릭: 몸통 착용";
            default->"";
        };
        if(!control.isBlank())lore.add(control);
        ItemStack icon=item(Material.PAPER,product.label(),NamedTextColor.AQUA,lore.toArray(String[]::new));
        if(product.kind()!=Kind.CREDIT) {
            var meta=icon.getItemMeta();
            meta.setItemModel(new NamespacedKey("noqui",product.id()));
            icon.setItemMeta(meta);
        }
        return icon;
    }
    private long owned(Player player,Product product) {
        return product.kind()==Kind.GADGET?gadgets.count(player,product.id()):wallets.wallet(player.getUniqueId()).owned().getOrDefault(product.id(),0L);
    }

    private static ItemStack item(Material material,String name,NamedTextColor color,String... lore) {
        ItemStack item=new ItemStack(material);
        var meta=item.getItemMeta();
        meta.displayName(Component.text(name,color).decoration(TextDecoration.ITALIC,false));
        meta.lore(Arrays.stream(lore).map(line->Component.text(line,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static void nav(Holder holder,int slot,Material material,String name,Runnable action) {
        holder.inventory.setItem(slot,item(material,name,NamedTextColor.YELLOW));
        holder.actions.put(slot,action);
    }

    void click(InventoryClickEvent event) {
        Inventory top=event.getView().getTopInventory();
        if(!owns(top))return;
        Holder holder=(Holder)top.getHolder();
        if(!(event.getWhoClicked() instanceof Player player) || closed || holder.used
                || holder.owner!=player || sessions.get(player.getUniqueId())!=holder
                || !eligible.test(player) || player.getOpenInventory().getTopInventory()!=top){event.setCancelled(true);return;}
        if(event.getRawSlot()<0 || event.getRawSlot()>=top.getSize()) {
            // Normal personal-inventory moves/drop are allowed; these operations can reach GUI icons.
            if(event.isShiftClick()||event.getClick()==ClickType.DOUBLE_CLICK||event.getAction()==InventoryAction.MOVE_TO_OTHER_INVENTORY
                    ||event.getAction()==InventoryAction.COLLECT_TO_CURSOR||event.getAction()==InventoryAction.UNKNOWN)event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if(event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT)return;
        Runnable action=holder.actions.get(event.getRawSlot());
        if(action==null)return;
        // Navigation/purchases execute after InventoryClickEvent has finished. A view accepts one click.
        holder.used=true;
        if(!queued.offer(()->{
            if(!closed && sessions.get(player.getUniqueId())==holder && eligible.test(player)
                    && player.getOpenInventory().getTopInventory()==top)action.run();
        }))holder.used=false;
    }

    void drag(InventoryDragEvent event) {
        Inventory top=event.getView().getTopInventory();if(!owns(top))return;
        Holder holder=(Holder)top.getHolder();
        if(event.getWhoClicked()!=holder.owner||sessions.get(holder.owner.getUniqueId())!=holder
                ||event.getRawSlots().stream().anyMatch(slot->slot<top.getSize()))event.setCancelled(true);
    }

    void closed(InventoryCloseEvent event) {
        Inventory top=event.getView().getTopInventory();
        if(owns(top))sessions.remove(event.getPlayer().getUniqueId(),(Holder)top.getHolder());
    }

    void tick() {
        for(int i=0;i<32;i++){Runnable action=queued.poll();if(action==null)return;action.run();}
    }

    void leave(Player player) {
        Holder holder=sessions.get(player.getUniqueId());
        if(holder==null || holder.owner!=player)return;
        sessions.remove(player.getUniqueId());
        if(player.getOpenInventory().getTopInventory()==holder.inventory)player.closeInventory();
    }

    @Override public void close() {
        closed=true;queued.clear();
        for(Holder holder:List.copyOf(sessions.values()))leave(holder.owner);
    }

    private static void message(Player player,String value,NamedTextColor color) {
        player.sendMessage(Component.text(value,color));
    }
}
