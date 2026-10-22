package dev.noqui.lobby;

import dev.noqui.api.LobbyService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
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
    private final java.util.concurrent.ArrayBlockingQueue<Runnable> dialogActions=new java.util.concurrent.ArrayBlockingQueue<>(256);
    private final LobbyMovement movement;
    private final CosmeticDisplays cosmetics;
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
        main();this.plugin=plugin;this.cabin=cabin;this.credits=credits;this.clock=clock;this.random=random;
        try{wallets=new LobbyWallets(plugin.getDataFolder().toPath().resolve("lobby/wallets.bin"));}
        catch(IOException error){throw new IllegalStateException("Lobby wallet unavailable; balances were not reset",error);}
        for(Product p:LobbyCatalog.defaults()) {
            String root="lobby.prices."+p.id()+".";
            catalog.put(p.id(),new Product(p.id(),p.label(),p.kind(),p.slot(),plugin.getConfig().getLong(root+"starlight",p.stars()),
                    p.points()<0?-1:plugin.getConfig().getLong(root+"points",p.points())));
        }
        movement=new LobbyMovement(cabin);cosmetics=new CosmeticDisplays(plugin,cabin);
        shop=new ShopNpc(cabin,plugin.getConfig().getDouble("lobby.shop.x",409.5),plugin.getConfig().getDouble("lobby.shop.y",11),plugin.getConfig().getDouble("lobby.shop.z",-205.5));
    }

    /** Spawn consumers mark mannequins before CreatureSpawnEvent. No generic CUSTOM bypass. */
    public static boolean ownsEntity(Entity entity){return entity instanceof Mannequin&&ShopNpc.owned(entity);}

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
        for(int i=0;i<32;i++){Runnable action=dialogActions.poll();if(action==null)break;action.run();}
        for(Player player:List.copyOf(players.values())) {
            if(!player.isOnline()){release(player);players.remove(player.getUniqueId());continue;}
            LobbyWallets.Wallet wallet=wallets.wallet(player.getUniqueId());
            movement.tick(player,wallet.equipped().get("boots"),"gravity_suit".equals(wallet.equipped().get("suit")));
            cosmetics.tick(player,wallet.equipped());
        }
        expire(stuns,now);expire(frost,now);
        for(var entry:List.copyOf(projectiles.entrySet()))if(entry.getValue().until<=now||!entry.getValue().ball.isValid()||entry.getValue().ball.getWorld()!=cabin){entry.getValue().ball.remove();projectiles.remove(entry.getKey());}
        cooldowns.entrySet().removeIf(e->e.getValue()<=now);shopCooldowns.entrySet().removeIf(e->e.getValue()<=now);
        if(now>=nextOutbox){nextOutbox=now+5000;deliverCredits();}
        if(now>=nextShopCheck){nextShopCheck=now+5000;shop.ensure();}
    }
    @Override public long starlight(UUID player){main();return wallets.wallet(player).stars();}
    @Override public long answerPoints(UUID player){main();return wallets.wallet(player).points();}
    @Override public void rewardStarlight(UUID player,String transactionId,long amount){reward(player,transactionId,amount,Currency.STARLIGHT);}
    @Override public void rewardAnswerPoints(UUID player,String transactionId,long amount){reward(player,transactionId,amount,Currency.POINTS);}
    private void reward(UUID player,String transaction,long amount,Currency currency){main();if(closed)throw new IllegalStateException("Lobby closed");try{wallets.reward(player,transaction,amount,currency);}catch(IOException error){throw new IllegalStateException("Lobby reward could not be saved",error);}}
    @Override public void showMoney(Player player){main();if(!closed)notice(player,"지갑","✦ "+starlight(player.getUniqueId())+"  ·  포인트 "+answerPoints(player.getUniqueId())+"  ·  출제권 "+credits.balance(player.getUniqueId()));}
    @Override public void openShop(Player player) {
        main();if(!started||closed||!player.isOnline())return;
        var actions=new ArrayList<ActionButton>();
        for(Product p:catalog.values())actions.add(button(player,p.label(),(who,r)->product(who,p)));
        actions.add(button(player,"지갑",(who,r)->showMoney(who)));
        player.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text("✦ "+starlight(player.getUniqueId()))).canCloseWithEscape(true).build())
                .type(DialogType.multiAction(actions,ActionButton.create(text("닫기"),null,100,null),3))));
    }
    private void product(Player player,Product p) {
        var actions=new ArrayList<ActionButton>();
        if(p.kind()!=Kind.CREDIT&&wallets.wallet(player.getUniqueId()).owned().getOrDefault(p.id(),0L)>0)
            actions.add(button(player,"장착 / 해제",(who,r)->mutate(who,()->wallets.equip(who.getUniqueId(),p))));
        String transaction="shop:"+UUID.randomUUID();
        actions.add(button(player,"✦ "+p.stars(),(who,r)->buy(who,p,Currency.STARLIGHT,transaction)));
        if(p.points()>0)actions.add(button(player,"포인트 "+p.points(),(who,r)->buy(who,p,Currency.POINTS,transaction)));
        String control=switch(p.id()){case "hammer"->"좌클릭";case "snow_bomb"->"Shift+우클릭";case "triple_boots","quad_boots"->"공중 Space";default->"";};
        var body=new ArrayList<DialogBody>();body.add(DialogBody.plainMessage(text("보유 "+wallets.wallet(player.getUniqueId()).owned().getOrDefault(p.id(),0L))));
        if(!control.isEmpty())body.add(DialogBody.plainMessage(text(control)));
        player.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text(p.label())).body(body).canCloseWithEscape(true).build())
                .type(DialogType.multiAction(actions,ActionButton.create(text("닫기"),null,100,null),1))));
    }
    private void buy(Player player,Product product,Currency currency,String transaction){mutate(player,()->{wallets.purchase(player.getUniqueId(),product,currency,transaction);deliverCredits();});}
    @FunctionalInterface private interface Mutation {void run()throws IOException;}
    private void mutate(Player player,Mutation action){try{action.run();notice(player,"상점","완료");}catch(IllegalArgumentException e){notice(player,"상점",e.getMessage());}catch(IOException e){notice(player,"상점","저장 실패");plugin.getLogger().warning("Lobby purchase persistence failed; no unrecorded reward was granted");}}
    private ActionButton button(Player owner,String label,java.util.function.BiConsumer<Player,DialogResponseView> action) {
        UUID id=owner.getUniqueId();return ActionButton.create(text(label),null,160,DialogAction.customClick((r,audience)->{
            // Callback delivery can come from a network thread. All Bukkit work drains in tick().
            if(!(audience instanceof Player player))return;
            dialogActions.offer(()->{
                if(closed||!started||!id.equals(player.getUniqueId())||players.get(id)!=player||!player.isOnline())return;
                action.accept(player,null); // These action-only screens have no response inputs.
            });
        },ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()));
    }
    private void deliverCredits() {
        for(LobbyWallets.Grant grant:wallets.pending())try{credits.grant(grant.player(),grant.amount(),grant.transaction());wallets.delivered(grant.transaction());}
        catch(RuntimeException|IOException error){plugin.getLogger().warning("Lobby paid-credit delivery pending; retrying the recorded transaction");}
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void clickNpc(PlayerInteractEntityEvent event) {
        if(!ownsEntity(event.getRightClicked()))return;event.setCancelled(true);
        if(event.getHand()!=EquipmentSlot.HAND||event.getPlayer().getWorld()!=cabin||!readyShop(event.getPlayer()))return;
        openShop(event.getPlayer());
    }
    private boolean readyShop(Player player){long now=clock.getAsLong();if(shopCooldowns.getOrDefault(player.getUniqueId(),0L)>now)return false;shopCooldowns.put(player.getUniqueId(),now+500);return true;}
    @EventHandler(priority=EventPriority.HIGHEST) public void protectNpc(EntityDamageEvent e){if(ownsEntity(e.getEntity())||e.getEntity().getScoreboardTags().contains(CosmeticDisplays.TAG))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void doubleJump(PlayerToggleFlightEvent e) {
        if(!started||closed||!e.isFlying())return;
        if(movement.jump(e.getPlayer(),wallets.wallet(e.getPlayer().getUniqueId()).equipped().get("boots")))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void hammer(PrePlayerAttackEntityEvent event) {
        if(!started||closed)return;
        if(ownsEntity(event.getAttacked())){event.setCancelled(true);return;}
        Player attacker=event.getPlayer();if(!(event.getAttacked() instanceof Player target)||!canHammer(attacker,target))return;
        event.setCancelled(true);
        if(!cooldown(attacker,1000))return;
        // Consume the break before applying the effect, so a persistence failure cannot grant a free hit.
        try{if(random.getAsDouble()<.5)wallets.consume(attacker.getUniqueId(),"hammer");}
        catch(IOException|IllegalArgumentException error){return;}
        Vector force=target.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if(force.lengthSquared()<.001)force=attacker.getLocation().getDirection().setY(0);
        if(force.lengthSquared()>0)force.normalize().multiply(.9);
        target.setVelocity(force.setY(.28));target.setFallDistance(0);effect(target,STUN_KEY,1000,stuns,-1);
    }
    boolean canHammer(Player attacker,Player target) {
        return LobbyMovement.eligible(attacker,cabin)&&LobbyMovement.eligible(target,cabin)&&attacker!=target&&!target.isBlocking()
                &&"hammer".equals(wallets.wallet(attacker.getUniqueId()).equipped().get("hand"))
                &&attacker.getLocation().distanceSquared(target.getLocation())<=16&&attacker.hasLineOfSight(target);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void snow(PlayerInteractEvent event) {
        if(!started||closed)return;
        Player p=event.getPlayer();if(event.getHand()!=EquipmentSlot.HAND||!event.getAction().isRightClick()||!p.isSneaking()||!LobbyMovement.eligible(p,cabin))return;
        if(!"snow_bomb".equals(wallets.wallet(p.getUniqueId()).equipped().get("hand")))return;
        event.setCancelled(true);if(projectiles.size()>=64||projectiles.values().stream().filter(v->v.owner.equals(p.getUniqueId())).count()>=2||!cooldown(p,1500))return;
        try{wallets.consume(p.getUniqueId(),"snow_bomb");}catch(IOException|IllegalArgumentException error){return;}
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
    @EventHandler public void death(PlayerDeathEvent e){release(e.getEntity());}
    @EventHandler public void quit(PlayerQuitEvent e){release(e.getPlayer());players.remove(e.getPlayer().getUniqueId());if(pack!=null)pack.quit(e.getPlayer().getUniqueId());}
    @EventHandler public void mode(PlayerGameModeChangeEvent e){movement.leave(e.getPlayer());}
    private void release(Player player) {
        movement.leave(player);cosmetics.leave(player.getUniqueId());cooldowns.remove(player.getUniqueId());shopCooldowns.remove(player.getUniqueId());
        Effect stun=stuns.remove(player.getUniqueId()),ice=frost.remove(player.getUniqueId());if(stun!=null)clearEffect(stun);if(ice!=null)clearEffect(ice);
        for(var row:List.copyOf(projectiles.entrySet()))if(row.getValue().owner.equals(player.getUniqueId())){row.getValue().ball.remove();projectiles.remove(row.getKey());}
    }
    @Override public void close(){main();if(closed)return;closed=true;dialogActions.clear();HandlerList.unregisterAll(this);for(Player p:List.copyOf(players.values()))release(p);players.clear();cosmetics.close();for(Projectile p:projectiles.values())p.ball.remove();projectiles.clear();}
    private static void notice(Player p,String title,String message){p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text(title)).body(List.of(DialogBody.plainMessage(text(message)))).build()).type(DialogType.notice())));}
    private static Component text(String value){return Component.text(value);}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Lobby features require the main thread");}
}
