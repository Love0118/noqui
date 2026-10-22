package dev.noqui.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;

@SuppressWarnings("deprecation")
final class NoquiListener implements Listener {
    private final NoquiPlugin plugin;
    private final Set<AsyncPlayerChatEvent> legacy=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<AsyncChatEvent> modern=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    NoquiListener(NoquiPlugin plugin){this.plugin=plugin;}
    private boolean cabin(org.bukkit.Location l){return plugin.cabin.contains(l);}
    @EventHandler(priority=EventPriority.LOWEST) public void recover(PlayerJoinEvent e){plugin.recovery.restore(e.getPlayer(),true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent e){plugin.routeJoin(e.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent e){plugin.quit(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e){if(plugin.participant(e.getPlayer().getUniqueId()))e.setRespawnLocation(plugin.cabin.spawn.clone());}
    @EventHandler public void death(PlayerDeathEvent e){if(plugin.participant(e.getPlayer().getUniqueId())){e.setKeepInventory(true);e.getDrops().clear();e.setDroppedExp(0);}}
    @EventHandler public void world(PlayerChangedWorldEvent e){if(!cabin(e.getPlayer().getLocation()))plugin.leftWorld(e.getPlayer());}
    @EventHandler public void move(PlayerMoveEvent e){if(cabin(e.getTo()) && plugin.cabin.outside(e.getTo())){e.getPlayer().setFallDistance(0);e.setTo(plugin.cabin.spawn.clone());}}
    @EventHandler public void swap(PlayerSwapHandItemsEvent e){if(plugin.participant(e.getPlayer().getUniqueId())){e.setCancelled(true);plugin.dialogs.controls(e.getPlayer());}}
    @EventHandler public void interact(PlayerInteractEvent e) {
        if(!cabin(e.getPlayer().getLocation()))return;e.setCancelled(true);
        if(e.getHand()!=EquipmentSlot.HAND || !(e.getAction()==Action.RIGHT_CLICK_AIR || e.getAction()==Action.RIGHT_CLICK_BLOCK) || !plugin.participant(e.getPlayer().getUniqueId()))return;
        String id=plugin.tool(e.getPlayer());
        if("defense".equals(id))plugin.toDefense(e.getPlayer());else if("controls".equals(id))plugin.dialogs.controls(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.LOWEST) public void legacy(AsyncPlayerChatEvent e){
        if(plugin.captureAnswer(e.getPlayer().getUniqueId(),e.getMessage())){legacy.add(e);hide(e);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void legacyGuard(AsyncPlayerChatEvent e){if(legacy.remove(e))hide(e);}
    private void hide(AsyncPlayerChatEvent e){e.setCancelled(true);e.setMessage("");e.getRecipients().clear();}
    @EventHandler(priority=EventPriority.LOWEST) public void chat(AsyncChatEvent e){
        if(plugin.captureAnswer(e.getPlayer().getUniqueId(),PlainTextComponentSerializer.plainText().serialize(e.originalMessage()))){modern.add(e);hide(e);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void chatGuard(AsyncChatEvent e){if(modern.remove(e))hide(e);}
    private void hide(AsyncChatEvent e){e.setCancelled(true);e.message(Component.empty());e.viewers().clear();}
    @EventHandler public void damage(EntityDamageEvent e){if(cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void attack(EntityDamageByEntityEvent e){if(cabin(e.getDamager().getLocation()) || cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void hunger(FoodLevelChangeEvent e){if(cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void breakBlock(BlockBreakEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void placeBlock(BlockPlaceEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void bucket(PlayerBucketEmptyEvent e){if(cabin(e.getBlock().getLocation()) || cabin(e.getPlayer().getLocation()))e.setCancelled(true);}
    @EventHandler public void bucket(PlayerBucketFillEvent e){if(cabin(e.getBlock().getLocation()) || cabin(e.getPlayer().getLocation()))e.setCancelled(true);}
    @EventHandler public void entity(PlayerInteractEntityEvent e){if(cabin(e.getRightClicked().getLocation()) || cabin(e.getPlayer().getLocation()))e.setCancelled(true);}
    @EventHandler public void entityAt(PlayerInteractAtEntityEvent e){entity(e);}
    @EventHandler public void armor(PlayerArmorStandManipulateEvent e){if(cabin(e.getRightClicked().getLocation()))e.setCancelled(true);}
    @EventHandler public void hanging(HangingBreakEvent e){if(cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void hanging(HangingPlaceEvent e){if(cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void inventory(InventoryClickEvent e){if(cabin(e.getWhoClicked().getLocation()))e.setCancelled(true);}
    @EventHandler public void drag(InventoryDragEvent e){if(cabin(e.getWhoClicked().getLocation()))e.setCancelled(true);}
    @EventHandler public void drop(PlayerDropItemEvent e){if(cabin(e.getPlayer().getLocation()))e.setCancelled(true);}
    @EventHandler public void pickup(EntityPickupItemEvent e){if(cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void portal(PlayerPortalEvent e){if(cabin(e.getFrom()))e.setCancelled(true);}
    @EventHandler public void portal(EntityPortalEvent e){if(cabin(e.getFrom()))e.setCancelled(true);}
    @EventHandler public void explode(EntityExplodeEvent e){e.blockList().removeIf(b->cabin(b.getLocation()));if(cabin(e.getLocation()))e.setCancelled(true);}
    @EventHandler public void explode(BlockExplodeEvent e){e.blockList().removeIf(b->cabin(b.getLocation()));}
    @EventHandler public void change(EntityChangeBlockEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void ignite(BlockIgniteEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void burn(BlockBurnEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void fade(BlockFadeEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void flow(BlockFromToEvent e){if(cabin(e.getBlock().getLocation()) || cabin(e.getToBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void grow(BlockGrowEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void form(BlockFormEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void spread(BlockSpreadEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void piston(BlockPistonExtendEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void piston(BlockPistonRetractEvent e){if(cabin(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void redstone(BlockRedstoneEvent e){if(cabin(e.getBlock().getLocation()))e.setNewCurrent(e.getOldCurrent());}
    @EventHandler public void spawn(CreatureSpawnEvent e){if(cabin(e.getLocation()))e.setCancelled(true);}
    @EventHandler public void weather(WeatherChangeEvent e){if(e.getWorld().equals(plugin.cabin.world))e.setCancelled(true);}
}
