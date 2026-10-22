package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import dev.noqui.lobby.LobbyCatalog.Currency;

class LobbyRuntimeTest {
    @TempDir Path dir;
    MockedStatic<Bukkit> bukkit;
    MockedConstruction<ShopNpc> shop;
    MockedConstruction<CosmeticDisplays> cosmetics;
    World cabin=mock(World.class),arena=mock(World.class);
    JavaPlugin plugin;
    AtomicLong clock=new AtomicLong(1000);
    AtomicReference<Double> random=new AtomicReference<>(.5);
    LobbyFeatures features;
    static MockedStatic<RegistryAccess> registryAccess;
    @BeforeAll @SuppressWarnings({"unchecked","rawtypes"}) static void registry() {
        registryAccess=mockStatic(RegistryAccess.class);RegistryAccess access=mock(RegistryAccess.class);
        registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
        Map<String,Attribute> values=new HashMap<>();
        org.mockito.stubbing.Answer<Object> registry=c->java.lang.reflect.Proxy.newProxyInstance(org.bukkit.Registry.class.getClassLoader(),new Class[]{org.bukkit.Registry.class},(proxy,method,args)->{
            if(method.getName().equals("getOrThrow")||method.getName().equals("get"))return values.computeIfAbsent(args[0].toString(),key->mock(Attribute.class));
            if(method.getName().equals("iterator"))return List.of().iterator();
            return null;
        });
        when(access.getRegistry(any(Class.class))).thenAnswer(registry);
        when(access.getRegistry(any(RegistryKey.class))).thenAnswer(registry);
        assertNotNull(Attribute.GRAVITY);
    }
    @AfterAll static void registryClose(){registryAccess.close();}
    @BeforeEach void setup()throws Exception {
        bukkit=mockStatic(Bukkit.class);bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        shop=mockConstruction(ShopNpc.class);cosmetics=mockConstruction(CosmeticDisplays.class);
        plugin=mock(JavaPlugin.class);when(plugin.getDataFolder()).thenReturn(dir.toFile());when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        Files.writeString(dir.resolve("lobby-pack.properties"),"url=https://noqui.danta.qzz.io/packs/"+"a".repeat(64)+".zip\nsha1="+"b".repeat(40)+"\n");
    }
    @AfterEach void cleanup(){if(features!=null)features.close();cosmetics.close();shop.close();bukkit.close();}
    LobbyFeatures create(LobbyFeatures.CreditPort port){features=new LobbyFeatures(plugin,cabin,port,clock::get,()->random.get());features.start();return features;}
    LobbyFeatures.CreditPort port(){return new LobbyFeatures.CreditPort(){public long balance(UUID id){return 0;}public void grant(UUID id,long n,String tx){}};}
    final class Person {
        Player p=mock(Player.class);UUID id=UUID.randomUUID();boolean allow,flying,ground,dead,blocking,freezeLocked;int freezeTicks;GameMode mode=GameMode.ADVENTURE;World world=cabin;
        Location location=new Location(cabin,411.5,11,-205.5,0,0);
        Map<org.bukkit.NamespacedKey,AttributeModifier> modifiers=new HashMap<>();AttributeInstance attr=mock(AttributeInstance.class);
        Person() {
            when(p.getUniqueId()).thenReturn(id);when(p.getWorld()).thenAnswer(c->world);when(p.getGameMode()).thenAnswer(c->mode);when(p.isDead()).thenAnswer(c->dead);
            when(p.isOnline()).thenReturn(true);when(p.getLocation()).thenAnswer(c->location.clone());when(p.getAllowFlight()).thenAnswer(c->allow);when(p.isFlying()).thenAnswer(c->flying);
            doAnswer(c->{allow=c.getArgument(0);return null;}).when(p).setAllowFlight(anyBoolean());doAnswer(c->{flying=c.getArgument(0);return null;}).when(p).setFlying(anyBoolean());
            when(p.isOnGround()).thenAnswer(c->ground);when(p.isBlocking()).thenAnswer(c->blocking);when(p.hasLineOfSight(any(Entity.class))).thenReturn(true);
            when(p.getMaxFreezeTicks()).thenReturn(140);when(p.getFreezeTicks()).thenAnswer(c->freezeTicks);when(p.isFreezeTickingLocked()).thenAnswer(c->freezeLocked);
            doAnswer(c->{freezeTicks=c.getArgument(0);return null;}).when(p).setFreezeTicks(anyInt());doAnswer(c->{freezeLocked=c.getArgument(0);return null;}).when(p).lockFreezeTicks(anyBoolean());
            when(p.getAttribute(any(Attribute.class))).thenReturn(attr);
            when(attr.getModifier(any(net.kyori.adventure.key.Key.class))).thenAnswer(c->modifiers.get(c.getArgument(0)));
            doAnswer(c->{AttributeModifier m=c.getArgument(0);modifiers.put(m.getKey(),m);return null;}).when(attr).addTransientModifier(any());
            doAnswer(c->{AttributeModifier m=c.getArgument(0);modifiers.remove(m.getKey(),m);return null;}).when(attr).removeModifier(any(AttributeModifier.class));
        }
    }
    LobbyWallets ledger()throws Exception {return new LobbyWallets(dir.resolve("lobby/wallets.bin"));}
    void buyEquip(Person person,String id)throws Exception {
        // A reward/purchase is persisted through the actual module ledger before module construction.
        LobbyWallets w=ledger();var item=LobbyCatalog.defaults().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();
        w.reward(person.id,"seed:"+id,100,Currency.STARLIGHT);w.purchase(person.id,item,Currency.STARLIGHT,"buy:"+id);w.equip(person.id,item);
    }

    @Test void doubleTripleQuadJumpsCountAndLandingResetsWithoutCreativeFlightTheft() {
        for(String boots:Arrays.asList(null,"triple_boots","quad_boots")) {
            Person p=new Person();LobbyMovement m=new LobbyMovement(cabin);
            for(int i=0;i<LobbyMovement.extraJumps(boots);i++){m.tick(p.p,boots,false);assertTrue(p.allow);assertTrue(m.jump(p.p,boots));}
            m.tick(p.p,boots,false);assertFalse(m.jump(p.p,boots));
            p.ground=true;m.tick(p.p,boots,false);p.ground=false;assertTrue(m.jump(p.p,boots));m.leave(p.p);assertFalse(p.allow);
        }
        Person creative=new Person();creative.mode=GameMode.CREATIVE;creative.allow=true;creative.flying=true;
        LobbyMovement m=new LobbyMovement(cabin);m.tick(creative.p,"quad_boots",true);assertFalse(m.jump(creative.p,"quad_boots"));
        verify(creative.p,never()).setAllowFlight(anyBoolean());verify(creative.p,never()).setFlying(anyBoolean());
    }
    @Test void foreignFlightAndGravitySurviveCleanupAndArenaNeverGetsJumpBoost() {
        Person p=new Person();p.allow=true;LobbyMovement m=new LobbyMovement(cabin);m.tick(p.p,null,false);assertFalse(m.jump(p.p,null));m.leave(p.p);assertTrue(p.allow);
        Person q=new Person();m.tick(q.p,null,true);assertEquals(-.5,q.modifiers.get(LobbyMovement.GRAVITY_KEY).getAmount());
        NamespacedKey foreignKey=new NamespacedKey("other","gravity");AttributeModifier foreign=new AttributeModifier(foreignKey,.2,AttributeModifier.Operation.ADD_NUMBER);q.modifiers.put(foreignKey,foreign);
        q.world=arena;m.tick(q.p,null,true);assertFalse(q.allow);assertFalse(q.modifiers.containsKey(LobbyMovement.GRAVITY_KEY));assertSame(foreign,q.modifiers.get(foreignKey));
        verify(q.p,times(1)).setAllowFlight(true);
    }
    @Test void hammerPreAttackWorksWithImmuneTargetsBreakBoundaryAndStunExpires()throws Exception {
        Person attacker=new Person(),target=new Person();target.location.add(1,0,0);buyEquip(attacker,"hammer");create(port());features.join(attacker.p);features.join(target.p);
        random.set(.5);PrePlayerAttackEntityEvent first=new PrePlayerAttackEntityEvent(attacker.p,target.p,false);features.hammer(first);assertTrue(first.isCancelled());
        verify(target.p).setVelocity(any(Vector.class));verify(target.p,never()).damage(anyDouble());
        assertEquals(1,ledger().wallet(attacker.id).owned().get("hammer"));
        features.hammer(new PrePlayerAttackEntityEvent(attacker.p,target.p,false));verify(target.p,times(1)).setVelocity(any(Vector.class));
        clock.addAndGet(1000);random.set(.499999);features.hammer(new PrePlayerAttackEntityEvent(attacker.p,target.p,false));
        assertFalse(ledger().wallet(attacker.id).owned().containsKey("hammer"));assertTrue(target.modifiers.keySet().stream().anyMatch(k->k.getKey().equals("lobby_stun")));
        clock.addAndGet(1001);features.tick();assertFalse(target.modifiers.keySet().stream().anyMatch(k->k.getKey().equals("lobby_stun")));
    }
    @Test void hammerRejectsShieldOutOfWorldRangeAndDoesNotUseArenaInventory()throws Exception {
        Person a=new Person(),b=new Person();buyEquip(a,"hammer");create(port());
        b.blocking=true;assertFalse(features.canHammer(a.p,b.p));b.blocking=false;b.world=arena;assertFalse(features.canHammer(a.p,b.p));b.world=cabin;b.location.add(20,0,0);assertFalse(features.canHammer(a.p,b.p));
        verify(a.p,never()).getInventory();verify(b.p,never()).getInventory();
    }
    @Test void failedCreditDeliveryReplaysSamePaidGrantWithoutSecondDebit()throws Exception {
        UUID id=UUID.randomUUID();LobbyWallets w=ledger();w.reward(id,"funds",20,Currency.STARLIGHT);w.purchase(id,LobbyCatalog.defaults().getFirst(),Currency.STARLIGHT,"credit");
        Set<String> grants=new HashSet<>();AtomicInteger calls=new AtomicInteger();
        LobbyFeatures.CreditPort port=new LobbyFeatures.CreditPort(){public long balance(UUID p){return grants.size();}public void grant(UUID p,long n,String tx){grants.add(tx);if(calls.getAndIncrement()==0)throw new IllegalStateException("lost acknowledgement");}};
        create(port);assertEquals(10,features.starlight(id));assertEquals(1,ledger().pending().size());
        features.tick();assertEquals(1,grants.size());assertTrue(ledger().pending().isEmpty());assertEquals(10,features.starlight(id));
    }
    @Test void packIsAddedOnceByUniqueIdAndCloseNeverRemovesOtherPacksOrItems()throws Exception {
        Person p=new Person();create(port());features.join(p.p);features.join(p.p);
        verify(p.p,times(1)).addResourcePack(eq(PackDelivery.ID),anyString(),any(byte[].class),eq("Noqui 로비"),eq(true));
        features.close();features.close();verify(p.p,never()).removeResourcePacks();verify(p.p,never()).removeResourcePack(any());verify(p.p,never()).getInventory();
    }
    @Test void packDescriptorRejectsHttpForeignHostsAndBadHash()throws Exception {
        Path p=dir.resolve("bad");for(String url:List.of("http://noqui.danta.qzz.io/packs/"+"a".repeat(64)+".zip","https://evil.invalid/packs/"+"a".repeat(64)+".zip","https://noqui.danta.qzz.io/packs/../x.zip")) {
            Files.writeString(p,"url="+url+"\nsha1="+"b".repeat(40));assertThrows(java.io.IOException.class,()->new PackDelivery(p));
        }
    }

    @Test void invulnerabilityDoesNotBlockHammerAndMovementTravelsBeforeStunLocks()throws Exception {
        Person a=new Person(),b=new Person();b.location.add(1,0,0);when(b.p.isInvulnerable()).thenReturn(true);buyEquip(a,"hammer");create(port());features.join(a.p);features.join(b.p);
        features.hammer(new PrePlayerAttackEntityEvent(a.p,b.p,false));
        var moved=new PlayerMoveEvent(b.p,b.location,b.location.clone().add(.7,.2,0));features.stunMove(moved);assertEquals(b.location.getX()+.7,moved.getTo().getX(),.0001);
        clock.addAndGet(351);features.stunMove(moved);assertEquals(b.location.getX(),moved.getTo().getX(),.0001);
        verify(b.p).setVelocity(argThat(v->v.getX()>.5));verify(b.p,never()).setInvulnerable(anyBoolean());
        features.world(new PlayerChangedWorldEvent(b.p,cabin));assertTrue(b.modifiers.isEmpty());
    }
    Snowball fireSnow(Person owner)throws Exception {
        Snowball ball=mock(Snowball.class);when(ball.getUniqueId()).thenReturn(UUID.randomUUID());when(ball.getWorld()).thenReturn(cabin);when(ball.isValid()).thenReturn(true);
        when(owner.p.isSneaking()).thenReturn(true);when(owner.p.launchProjectile(Snowball.class)).thenReturn(ball);
        var event=new PlayerInteractEvent(owner.p,org.bukkit.event.block.Action.RIGHT_CLICK_AIR,null,null,org.bukkit.block.BlockFace.UP,org.bukkit.inventory.EquipmentSlot.HAND);
        features.snow(event);assertTrue(event.isCancelled());return ball;
    }
    @Test void snowBombHasBoundedVisibleFrostbiteRestoresOnExpiryAndNeverDamagesBlocks()throws Exception {
        Person a=new Person(),b=new Person();b.freezeTicks=12;buyEquip(a,"snow_bomb");create(port());features.join(a.p);features.join(b.p);
        Snowball ball=fireSnow(a);features.snowHit(new org.bukkit.event.entity.ProjectileHitEvent(ball,b.p));
        assertEquals(100,b.freezeTicks);assertTrue(b.freezeLocked);assertTrue(b.freezeTicks<140);assertFalse(ledger().wallet(a.id).owned().containsKey("snow_bomb"));
        verify(ball).remove();verify(b.p,never()).damage(anyDouble());verify(b.p,never()).getInventory();
        clock.addAndGet(1801);features.tick();assertEquals(12,b.freezeTicks);assertFalse(b.freezeLocked);assertTrue(b.modifiers.isEmpty());
    }
    @Test void foreignFreezeStateIsNotOverwrittenAndArenaTargetsAreImmune()throws Exception {
        Person a=new Person(),b=new Person();b.freezeLocked=true;b.freezeTicks=120;buyEquip(a,"snow_bomb");create(port());features.join(a.p);features.join(b.p);
        Snowball ball=fireSnow(a);features.snowHit(new org.bukkit.event.entity.ProjectileHitEvent(ball,b.p));clock.addAndGet(1801);features.tick();assertEquals(120,b.freezeTicks);assertTrue(b.freezeLocked);
        verify(b.p,never()).lockFreezeTicks(anyBoolean());verify(b.p,never()).setFreezeTicks(anyInt());
        b.world=arena;features.world(new PlayerChangedWorldEvent(b.p,cabin));assertTrue(b.modifiers.isEmpty());
    }

    @Test void snowCooldownOwnerLimitExpiryAndForeignOverlayReplacementArePreserved()throws Exception {
        Person a=new Person(),b=new Person();buyEquip(a,"snow_bomb");
        LobbyWallets before=ledger();var bomb=LobbyCatalog.defaults().stream().filter(p->p.id().equals("snow_bomb")).findFirst().orElseThrow();
        for(int i=0;i<4;i++)before.purchase(a.id,bomb,Currency.STARLIGHT,"extra:"+i);
        create(port());features.join(a.p);features.join(b.p);
        Snowball first=fireSnow(a);fireSnow(a);verify(a.p,times(1)).launchProjectile(Snowball.class);
        clock.addAndGet(1500);Snowball second=fireSnow(a);clock.addAndGet(1500);fireSnow(a);verify(a.p,times(2)).launchProjectile(Snowball.class);
        features.snowHit(new org.bukkit.event.entity.ProjectileHitEvent(first,b.p));assertTrue(b.freezeLocked);
        b.freezeTicks=130;clock.addAndGet(1801);features.tick();assertEquals(130,b.freezeTicks);assertTrue(b.freezeLocked);
        clock.addAndGet(5001);features.tick();verify(second).remove();
        // A tagged projectile never damages even if it hits a player in a different world.
        when(second.getScoreboardTags()).thenReturn(Set.of("noqui_lobby_snow_v1"));b.world=arena;
        var damage=mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);when(damage.getDamager()).thenReturn(second);when(damage.getEntity()).thenReturn(b.p);
        features.snowDamage(damage);verify(damage).setCancelled(true);
    }
}
