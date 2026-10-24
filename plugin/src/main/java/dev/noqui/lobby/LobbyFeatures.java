package dev.noqui.lobby;

import dev.noqui.api.LobbyService;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.*;
import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import static dev.noqui.lobby.LobbyCatalog.*;
import dev.noqui.lobby.LobbyCatalog.Currency;

/** Native lobby services. All API calls and event handlers run on the server thread. */
public final class LobbyFeatures implements LobbyService, AutoCloseable, Listener {
    public interface CreditPort { long balance(UUID player); void grant(UUID player,long amount,String transactionId); }
    public static final String NPC_TAG=ShopNpc.TAG;
    private static final NamespacedKey STUN_KEY=new NamespacedKey("noqui","lobby_stun");
    private static final NamespacedKey FROST_KEY=new NamespacedKey("noqui","lobby_frost");
    private record Projectile(UUID owner,Snowball ball,long until) {}
    private record Freeze(int previous,int expected) {}
    private record Effect(Player player,long until,long lockAfter,NamespacedKey key,AttributeModifier modifier,Freeze freeze) {}
    private final JavaPlugin plugin;
    private final World cabin;
    private final CreditPort credits;
    private final LobbyWallets wallets;
    private final Map<String,Product> catalog=new LinkedHashMap<>();
    private final Map<UUID,Player> players=new HashMap<>();
    private final Map<UUID,Long> cooldowns=new HashMap<>(),shopCooldowns=new HashMap<>();
    private final Map<UUID,Projectile> projectiles=new HashMap<>();
    private final Map<UUID,Effect> stuns=new HashMap<>(),frost=new HashMap<>();
    private final LobbyShop menus;
    private final GadgetItems gadgets;
    private final LobbyMovement movement;
    private final NativeWardrobe cosmetics;
    private final ShopNpc shop;
    private final LongSupplier clock;
    private final DoubleSupplier random;
    private PackDelivery pack;
    private boolean started,closed;
    private long nextOutbox,nextShopCheck;

    public LobbyFeatures(JavaPlugin plugin,World cabin,CreditPort credits) {
        this(plugin,cabin,credits,System::currentTimeMillis,Math::random);
    }
    LobbyFeatures(JavaPlugin plugin,World cabin,CreditPort credits,LongSupplier clock,DoubleSupplier random) {
        this(plugin,cabin,credits,clock,random,null);
    }
    LobbyFeatures(JavaPlugin plugin,World cabin,CreditPort credits,LongSupplier clock,DoubleSupplier random,Function<LobbyWallets.Gadget,ItemStack> itemFactory) {
        main();this.plugin=plugin;this.cabin=cabin;this.credits=credits;this.clock=clock;this.random=random;
        try{wallets=new LobbyWallets(plugin.getDataFolder().toPath().resolve("lobby/wallets.bin"));}
        catch(IOException error){throw new IllegalStateException("Lobby wallet unavailable; balances were not reset",error);}
        for(Product p:LobbyCatalog.defaults()) {
            String root="lobby.prices."+p.id()+".";
            catalog.put(p.id(),new Product(p.id(),p.label(),p.kind(),p.slot(),plugin.getConfig().getLong(root+"starlight",p.stars()),
                    p.points()<0?-1:plugin.getConfig().getLong(root+"points",p.points())));
        }
        movement=new LobbyMovement(cabin);cosmetics=new NativeWardrobe();
        gadgets=itemFactory==null?new GadgetItems(wallets,cabin):new GadgetItems(wallets,cabin,itemFactory);
        menus=new LobbyShop(wallets,catalog.values(),gadgets,p->started&&!closed&&players.get(p.getUniqueId())==p&&p.isOnline()&&!p.isDead()&&p.getWorld()==cabin,this::deliverCredits,plugin.getLogger()::warning);
        shop=new ShopNpc(cabin,plugin.getConfig().getDouble("lobby.shop.x",409.5),plugin.getConfig().getDouble("lobby.shop.y",11),plugin.getConfig().getDouble("lobby.shop.z",-205.5));
    }

    /** Spawn consumers mark mannequins before CreatureSpawnEvent. No generic CUSTOM bypass. */
    public static boolean ownsEntity(Entity entity){return entity instanceof Mannequin&&ShopNpc.owned(entity);}
    /** Narrow integration hook: cabin guards may allow navigation; this menu cancels all transfers. */
    public static boolean ownsInventory(Inventory inventory){return LobbyShop.owns(inventory);}
    public static boolean isGadgetItem(ItemStack item){return GadgetItems.marked(item);}
    /** Uses the actual physical item's native equipment/model factory, without an authentic serial. */
    public static ItemStack gadgetPreview(String product){return GadgetItems.preview(product);}
    /** Core owns the entire cabin inventory snapshot; C releases effects without moving any stacks. */
    @Override public void prepareInventoryChange(Player player){main();if(!closed)releaseEffects(player,false);}
    @Override public void inventoryChanged(Player player){
        main();if(closed)return;
        try{gadgets.reconcile(player);}catch(IOException error){throw new IllegalStateException("Gadget delivery could not be saved",error);}
    }

    public void start() {
        main();if(started||closed)return;
        try{
            var descriptor=plugin.getDataFolder().toPath().resolve("lobby-pack.properties");
            if(!java.nio.file.Files.exists(descriptor)) {
                try(var resource=plugin.getResource("lobby-pack.properties")) {
                    if(resource==null)throw new IOException("Missing bundled lobby-pack.properties");
                    java.nio.file.Files.createDirectories(descriptor.getParent());
                    java.nio.file.Files.copy(resource,descriptor);
                }
            }
            pack=new PackDelivery(descriptor);
        }
        catch(IOException error){throw new IllegalStateException("Install the verified lobby-pack.properties before enabling lobby features",error);}
        cosmetics.purge();shop.ensure();plugin.getServer().getPluginManager().registerEvents(this,plugin);started=true;
        for(Player player:Bukkit.getOnlinePlayers())join(player);
        deliverCredits();
    }
    public void join(Player player) {
        main();if(!started||closed)return;
        Player old=players.put(player.getUniqueId(),player);if(old!=null&&old!=player)release(old);
        pack.join(player);
    }
    public void tick() {
        main();if(!started||closed)return;
        long now=clock.getAsLong();
        menus.tick();
        for(Player player:List.copyOf(players.values())) {
            if(!player.isOnline()){release(player);players.remove(player.getUniqueId());continue;}
            LobbyWallets.Wallet wallet=wallets.wallet(player.getUniqueId());
            var equipment=new HashMap<String,String>();wallet.equipped().forEach((slot,product)->{if(Set.of("head","face","back").contains(slot))equipment.put(slot,product);});
            equipment.putAll(gadgets.equipment(player));
            movement.tick(player,equipment.get("boots"),"gravity_suit".equals(equipment.get("suit")));
            cosmetics.tick(player,equipment);
        }
        expire(stuns,now);expire(frost,now);
        for(var entry:List.copyOf(projectiles.entrySet()))if(entry.getValue().until<=now||!entry.getValue().ball.isValid()||entry.getValue().ball.getWorld()!=cabin){entry.getValue().ball.remove();projectiles.remove(entry.getKey());}
        cooldowns.entrySet().removeIf(e->e.getValue()<=now);shopCooldowns.entrySet().removeIf(e->e.getValue()<=now);
        if(now>=nextOutbox){nextOutbox=now+5000;deliverCredits();}
        if(now>=nextShopCheck){nextShopCheck=now+5000;shop.ensure();}
        shop.faceNearest();
    }
    @Override public long starlight(UUID player){main();return wallets.wallet(player).stars();}
    @Override public long answerPoints(UUID player){main();return wallets.wallet(player).points();}
    @Override public void rewardStarlight(UUID player,String transactionId,long amount){reward(player,transactionId,amount,Currency.STARLIGHT);}
    @Override public void rewardAnswerPoints(UUID player,String transactionId,long amount){reward(player,transactionId,amount,Currency.POINTS);}
    private void reward(UUID player,String transaction,long amount,Currency currency){main();if(closed)throw new IllegalStateException("Lobby closed");try{wallets.reward(player,transaction,amount,currency);}catch(IOException error){throw new IllegalStateException("Lobby reward could not be saved",error);}}
    @Override public void showMoney(Player player){
        main();if(!closed)player.sendMessage(Component.text("✦ "+starlight(player.getUniqueId())+"  정답 포인트 "+answerPoints(player.getUniqueId())));
    }
    @Override public void openShop(Player player){main();if(started&&!closed)menus.open(player);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void shopClick(InventoryClickEvent event){cosmetics.click(event);menus.click(event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void shopDrag(InventoryDragEvent event){cosmetics.drag(event);menus.drag(event);}
    @EventHandler public void shopClose(InventoryCloseEvent event){menus.closed(event);}
    private void deliverCredits() {
        for(LobbyWallets.Grant grant:wallets.pending())try{credits.grant(grant.player(),grant.amount(),grant.transaction());wallets.delivered(grant.transaction());}
        catch(RuntimeException|IOException error){plugin.getLogger().warning("Lobby paid-credit delivery pending; retrying the recorded transaction");}
        for(Player player:players.values())try{gadgets.reconcile(player);}
        catch(RuntimeException|IOException error){plugin.getLogger().warning("Lobby physical gadget delivery pending; preserving recorded items");}
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void clickNpc(PlayerInteractEntityEvent event) {
        if(!ownsEntity(event.getRightClicked()))return;event.setCancelled(true);
        if(event.getHand()!=EquipmentSlot.HAND||event.getPlayer().getWorld()!=cabin||!readyShop(event.getPlayer()))return;
        openShop(event.getPlayer());
    }
    private boolean readyShop(Player player){long now=clock.getAsLong();if(shopCooldowns.getOrDefault(player.getUniqueId(),0L)>now)return false;shopCooldowns.put(player.getUniqueId(),now+500);return true;}
    @EventHandler(priority=EventPriority.HIGHEST) public void protectNpc(EntityDamageEvent e){if(ownsEntity(e.getEntity()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void wardrobeDrop(PlayerDropItemEvent e){if(NativeWardrobe.managed(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    @EventHandler public void jumpInput(PlayerInputEvent event) {
        Player player=event.getPlayer();
        if(!started||closed||players.get(player.getUniqueId())!=player)return;
        movement.input(player,event.getInput().isJump(),gadgets.equipment(player).get("boots"));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void hammer(PrePlayerAttackEntityEvent event) {
        if(!started||closed)return;
        if(ownsEntity(event.getAttacked())){event.setCancelled(true);return;}
        Player attacker=event.getPlayer();if(!(event.getAttacked() instanceof Player target)||!canHammer(attacker,target))return;
        event.setCancelled(true);
        if(!cooldown(attacker,1000))return;
        // Consume the break before applying the effect, so a persistence failure cannot grant a free hit.
        try{if(random.getAsDouble()<.5)gadgets.consumeHand(attacker,"hammer");}
        catch(IOException|IllegalArgumentException error){return;}
        Vector force=target.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if(force.lengthSquared()<.001)force=attacker.getLocation().getDirection().setY(0);
        if(force.lengthSquared()>0)force.normalize().multiply(.9);
        target.setVelocity(force.setY(.28));target.setFallDistance(0);effect(target,STUN_KEY,1000,stuns,-1);
    }
    boolean canHammer(Player attacker,Player target) {
        return LobbyMovement.eligible(attacker,cabin)&&LobbyMovement.eligible(target,cabin)&&attacker!=target&&!target.isBlocking()
                &&attacker.getLocation().distanceSquared(target.getLocation())<=16&&attacker.hasLineOfSight(target)
                &&"hammer".equals(gadgets.hand(attacker));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void snow(PlayerInteractEvent event) {
        if(!started||closed)return;
        Player p=event.getPlayer();if(event.getHand()!=EquipmentSlot.HAND||!event.getAction().isRightClick()||!p.isSneaking()||!LobbyMovement.eligible(p,cabin))return;
        if(!"snow_bomb".equals(gadgets.hand(p)))return;
        event.setCancelled(true);if(projectiles.size()>=64||projectiles.values().stream().filter(v->v.owner.equals(p.getUniqueId())).count()>=2||!cooldown(p,1500))return;
        try{gadgets.consumeHand(p,"snow_bomb");}catch(IOException|IllegalArgumentException error){return;}
        Snowball ball=p.launchProjectile(Snowball.class);ball.addScoreboardTag("noqui_lobby_snow_v1");ball.setPersistent(false);
        projectiles.put(ball.getUniqueId(),new Projectile(p.getUniqueId(),ball,clock.getAsLong()+5000));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void snowHit(ProjectileHitEvent event) {
        Projectile shot=projectiles.remove(event.getEntity().getUniqueId());if(shot==null)return;
        event.setCancelled(true);shot.ball.remove();Player owner=players.get(shot.owner);
        if(owner!=null&&LobbyMovement.eligible(owner,cabin)&&event.getHitEntity() instanceof Player target&&LobbyMovement.eligible(target,cabin)&&!target.isBlocking())effect(target,FROST_KEY,1800,frost,-.4);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void snowDamage(EntityDamageByEntityEvent event){if(projectiles.containsKey(event.getDamager().getUniqueId())||event.getDamager().getScoreboardTags().contains("noqui_lobby_snow_v1"))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void stunMove(PlayerMoveEvent event) {
        Effect effect=stuns.get(event.getPlayer().getUniqueId());if(effect==null||effect.until<=clock.getAsLong()||effect.lockAfter>clock.getAsLong()||!LobbyMovement.eligible(event.getPlayer(),cabin)||event.getTo()==null)return;
        Location from=event.getFrom(),to=event.getTo();if(from.getX()!=to.getX()||from.getZ()!=to.getZ()){Location safe=from.clone();safe.setYaw(to.getYaw());safe.setPitch(to.getPitch());event.setTo(safe);}
    }
    private boolean cooldown(Player p,long ms){long now=clock.getAsLong();if(cooldowns.getOrDefault(p.getUniqueId(),0L)>now)return false;cooldowns.put(p.getUniqueId(),now+ms);return true;}
    private void effect(Player target,NamespacedKey key,long ms,Map<UUID,Effect> effects,double amount) {
        AttributeInstance speed=target.getAttribute(Attribute.MOVEMENT_SPEED);if(speed==null)return;
        Effect previous=effects.get(target.getUniqueId());AttributeModifier existing=speed.getModifier(key);
        if(existing!=null&&(previous==null||!existing.equals(previous.modifier)))return;
        AttributeModifier modifier=existing==null?new AttributeModifier(key,amount,AttributeModifier.Operation.MULTIPLY_SCALAR_1):existing;
        if(existing==null)speed.addTransientModifier(modifier);
        Freeze freeze=previous==null?null:previous.freeze;
        if(key.equals(FROST_KEY)) {
            if(freeze==null&&!target.isFreezeTickingLocked()) {
                int expected=Math.max(0,Math.min(100,target.getMaxFreezeTicks()-1));int original=target.getFreezeTicks();
                if(expected>original){target.setFreezeTicks(expected);target.lockFreezeTicks(true);freeze=new Freeze(original,expected);}
            }
            target.spawnParticle(Particle.SNOWFLAKE,target.getLocation().add(0,1,0),12,.3,.5,.3,0);
        }
        long now=clock.getAsLong();effects.put(target.getUniqueId(),new Effect(target,now+ms,key.equals(STUN_KEY)?now+350:now,key,modifier,freeze));
    }
    private void expire(Map<UUID,Effect> effects,long now){for(var row:List.copyOf(effects.entrySet()))if(row.getValue().until<=now||!LobbyMovement.eligible(row.getValue().player,cabin)){clearEffect(row.getValue());effects.remove(row.getKey());}}
    private void clearEffect(Effect effect){
        AttributeInstance attribute=effect.player.getAttribute(Attribute.MOVEMENT_SPEED);if(attribute!=null&&effect.modifier.equals(attribute.getModifier(effect.key)))attribute.removeModifier(effect.modifier);
        if(effect.freeze!=null&&effect.player.isFreezeTickingLocked()&&effect.player.getFreezeTicks()==effect.freeze.expected){effect.player.lockFreezeTicks(false);effect.player.setFreezeTicks(effect.freeze.previous);}
    }
    @EventHandler public void world(PlayerChangedWorldEvent e){release(e.getPlayer());}
    @EventHandler public void death(PlayerDeathEvent e){e.getDrops().removeIf(NativeWardrobe::managed);release(e.getEntity());}
    @EventHandler public void quit(PlayerQuitEvent e){release(e.getPlayer());players.remove(e.getPlayer().getUniqueId());if(pack!=null)pack.quit(e.getPlayer().getUniqueId());}
    @EventHandler public void mode(PlayerGameModeChangeEvent e){movement.leave(e.getPlayer());}
    private void release(Player player) {
        menus.leave(player);releaseEffects(player,true);
    }
    private void releaseEffects(Player player,boolean resetJumps) {
        if(resetJumps)movement.leave(player);else movement.suspend(player);
        cosmetics.leave(player);cooldowns.remove(player.getUniqueId());shopCooldowns.remove(player.getUniqueId());
        Effect stun=stuns.remove(player.getUniqueId()),ice=frost.remove(player.getUniqueId());if(stun!=null)clearEffect(stun);if(ice!=null)clearEffect(ice);
        for(var row:List.copyOf(projectiles.entrySet()))if(row.getValue().owner.equals(player.getUniqueId())){row.getValue().ball.remove();projectiles.remove(row.getKey());}
    }
    @Override public void close(){main();if(closed)return;closed=true;menus.close();HandlerList.unregisterAll(this);for(Player p:List.copyOf(players.values()))release(p);players.clear();cosmetics.close();for(Projectile p:projectiles.values())p.ball.remove();projectiles.clear();}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Lobby features require the main thread");}
}
