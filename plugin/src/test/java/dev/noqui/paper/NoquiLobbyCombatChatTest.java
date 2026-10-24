package dev.noqui.paper;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class NoquiLobbyCombatChatTest {
    final World cabin=mock(World.class),other=mock(World.class);
    final NoquiPlugin plugin=mock(NoquiPlugin.class);
    final NoquiListener listener=new NoquiListener(plugin);
    NoquiLobbyCombatChatTest(){
        plugin.cabin=mock(Cabin.class);
        when(plugin.cabin.contains(any())).thenAnswer(call->{Location l=call.getArgument(0);return l!=null&&l.getWorld()==cabin;});
    }
    <T extends Entity>T entity(Class<T> type,World world){
        T entity=mock(type);when(entity.getLocation()).thenReturn(new Location(world,0,64,0));
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());return entity;
    }
    EntityDamageByEntityEvent hit(Entity attacker,Entity victim){
        var event=mock(EntityDamageByEntityEvent.class);when(event.getDamager()).thenReturn(attacker);when(event.getEntity()).thenReturn(victim);return event;
    }
    @Test void meleeAndPlayerProjectilesAreAllowedOnlyBetweenCabinPlayers(){
        Player attacker=entity(Player.class,cabin),victim=entity(Player.class,cabin);
        var melee=hit(attacker,victim);listener.damage(melee);listener.attack(melee);verify(melee,never()).setCancelled(true);
        var arrow=entity(Arrow.class,cabin);when(arrow.getShooter()).thenReturn(attacker);
        var ranged=hit(arrow,victim);listener.damage(ranged);listener.attack(ranged);verify(ranged,never()).setCancelled(true);
        for(var blocked:List.of(hit(attacker,entity(Mannequin.class,cabin)),hit(entity(Zombie.class,cabin),victim),hit(entity(Player.class,other),victim),hit(attacker,entity(Player.class,other)))){
            listener.damage(blocked);verify(blocked).setCancelled(true);
        }
        var protectedLobby=hit(entity(Player.class,other),entity(Player.class,other));listener.attack(protectedLobby);
        verify(protectedLobby,never()).setCancelled(false);
        var existingCancellation=hit(attacker,victim);listener.damage(existingCancellation);verify(existingCancellation,never()).setCancelled(false);
    }
    @Test void environmentRemainsProtectedAndDeathKeepsItemsAndExperience(){
        var player=entity(Player.class,cabin);var fall=mock(EntityDamageEvent.class);when(fall.getEntity()).thenReturn(player);
        listener.damage(fall);verify(fall).setCancelled(true);
        var death=mock(PlayerDeathEvent.class);when(death.getPlayer()).thenReturn(player);var drops=new ArrayList<>(List.of(mock(org.bukkit.inventory.ItemStack.class)));when(death.getDrops()).thenReturn(drops);
        listener.death(death);verify(death).setKeepInventory(true);verify(death).setKeepLevel(true);verify(death).setDroppedExp(0);assertTrue(drops.isEmpty());
        var outsidePlayer=entity(Player.class,other);var outside=mock(PlayerDeathEvent.class);when(outside.getPlayer()).thenReturn(outsidePlayer);listener.death(outside);verify(outside,never()).setKeepInventory(true);
    }
    AsyncChatEvent modern(Player p,String text){return new AsyncChatEvent(true,p,new HashSet<Audience>(Set.of(p)),mock(ChatRenderer.class),Component.text(text),Component.text(text),null);}
    @Test void ampersandColorsApplyInBothChatPathsOnlyInsideCabin(){
        for(World world:List.of(cabin,other)){
            Player p=entity(Player.class,world);var modern=modern(p,"&easdf &a초록");listener.chat(modern);
            var legacy=new AsyncPlayerChatEvent(true,p,"&easdf &a초록",new HashSet<>(Set.of(p)));listener.legacy(legacy);
            if(world==cabin){
                assertEquals("asdf 초록",PlainTextComponentSerializer.plainText().serialize(modern.message()));
                assertEquals("§easdf §a초록",LegacyComponentSerializer.legacySection().serialize(modern.message()));
                assertEquals("§easdf §a초록",legacy.getMessage());
            }else{assertEquals(Component.text("&easdf &a초록"),modern.message());assertEquals("&easdf &a초록",legacy.getMessage());}
        }
    }
    @Test void coloredAnswersAndSkipReachAnswerHandlingWithoutTheCodes(){
        Player p=entity(Player.class,cabin);when(plugin.captureAnswer(p.getUniqueId(),"스킵")).thenReturn(true);
        var modern=modern(p,"&e스킵");listener.chat(modern);assertTrue(modern.isCancelled());assertTrue(modern.viewers().isEmpty());
        var legacy=new AsyncPlayerChatEvent(true,p,"&e스킵",new HashSet<>(Set.of(p)));listener.legacy(legacy);assertTrue(legacy.isCancelled());assertTrue(legacy.getRecipients().isEmpty());
        verify(plugin,times(2)).captureAnswer(p.getUniqueId(),"스킵");
    }
}
