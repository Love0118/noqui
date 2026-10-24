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
    @EventHandler(priority=EventPriority.LOWEST) public void recover(PlayerJoinEvent e){plugin.recoverPlayer(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent e){plugin.joined(e.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent e){plugin.quit(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e){
        if(plugin.ownsCabin(e.getPlayer().getUniqueId()))e.setRespawnLocation(plugin.cabin.spawn.clone());
        else if(plugin.participant(e.getPlayer().getUniqueId()))plugin.main(()->{
            if(e.getPlayer().isOnline()&&!plugin.defenseActive(e.getPlayer()))plugin.routeJoin(e.getPlayer());
        });
    }
    @EventHandler public void death(PlayerDeathEvent e){if(plugin.ownsCabin(e.getPlayer().getUniqueId())){e.setKeepInventory(true);e.getDrops().clear();e.setDroppedExp(0);}}
    @EventHandler public void world(PlayerChangedWorldEvent e){if(!cabin(e.getPlayer().getLocation()))plugin.leftWorld(e.getPlayer());}
    @EventHandler public void move(PlayerMoveEvent e){if(cabin(e.getTo()) && plugin.cabin.outside(e.getTo())){e.getPlayer().setFallDistance(0);e.setTo(plugin.cabin.spawn.clone());}}
    @EventHandler public void interact(PlayerInteractEvent e) {
        plugin.otherInteraction(e.getPlayer());
        if(!cabin(e.getPlayer().getLocation()))return;
        if(gsitSeat(e))return;
        var item=e.getItem();boolean equip=e.getAction().isRightClick()&&dev.noqui.lobby.LobbyFeatures.isGadgetItem(item)&&item.getItemMeta().hasEquippable()&&item.getItemMeta().getEquippable().isEquipOnInteract();
        e.setUseInteractedBlock(Event.Result.DENY);e.setUseItemInHand(equip?Event.Result.ALLOW:Event.Result.DENY);
        if(!(e.getAction()==Action.RIGHT_CLICK_AIR || e.getAction()==Action.RIGHT_CLICK_BLOCK) || !plugin.ownsCabin(e.getPlayer().getUniqueId()))return;
        if(e.getHand()==EquipmentSlot.OFF_HAND&&CabinNavigation.isTool(e.getPlayer().getInventory().getItemInMainHand()))return;
        String id=CabinNavigation.id(item);
        if("defense".equals(id))plugin.openMinigames(e.getPlayer());
        else if("settings".equals(id))plugin.openSettings(e.getPlayer());
        else if("traits".equals(id))plugin.openTraits(e.getPlayer());
        else if("wardrobe".equals(id))plugin.openWardrobe(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGH) public void legacy(AsyncPlayerChatEvent e){
        if(plugin.captureAnswer(e.getPlayer().getUniqueId(),e.getMessage())){legacy.add(e);hide(e);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void legacyGuard(AsyncPlayerChatEvent e){if(legacy.remove(e))hide(e);}
    private void hide(AsyncPlayerChatEvent e){e.setCancelled(true);e.setMessage("");e.getRecipients().clear();}
    @EventHandler(priority=EventPriority.HIGH) public void chat(AsyncChatEvent e){
        if(plugin.captureAnswer(e.getPlayer().getUniqueId(),PlainTextComponentSerializer.plainText().serialize(e.message()))){modern.add(e);hide(e);}
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
    private boolean gsitSeat(PlayerInteractEvent e) {
        if(!plugin.gsitEnabled()||e.isCancelled()||e.getHand()!=EquipmentSlot.HAND||e.getAction()!=Action.RIGHT_CLICK_BLOCK
                ||e.getBlockFace()!=org.bukkit.block.BlockFace.UP||e.getItem()!=null||e.getClickedBlock()==null||!e.getPlayer().isValid()||e.getPlayer().isSneaking())return false;
        String material=e.getClickedBlock().getType().name();
        return material.endsWith("_STAIRS")||material.endsWith("_SLAB")||material.endsWith("_CARPET")||material.equals("SNOW")||material.equals("SHELF_MUSHROOM");
    }
    private boolean gsitPlayer(PlayerInteractEntityEvent e) {
        if(!plugin.gsitEnabled()||e.isCancelled()||e.getHand()!=EquipmentSlot.HAND||!(e.getRightClicked() instanceof Player target)
                ||!e.getPlayer().isValid()||!target.isValid()||target.hasMetadata("NPC")||e.getPlayer().isSneaking()||e.getPlayer().getGameMode()==org.bukkit.GameMode.SPECTATOR)return false;
        return e.getPlayer().getInventory().getItemInMainHand().getType()==org.bukkit.Material.AIR;
    }
    @EventHandler public void entity(PlayerInteractEntityEvent e){if(cabin(e.getRightClicked().getLocation()) || cabin(e.getPlayer().getLocation())){if(gsitPlayer(e))return;e.setCancelled(true);}}
    @EventHandler public void entityAt(PlayerInteractAtEntityEvent e){entity(e);}
    @EventHandler public void armor(PlayerArmorStandManipulateEvent e){if(cabin(e.getRightClicked().getLocation()))e.setCancelled(true);}
    @EventHandler public void hanging(HangingBreakEvent e){if(cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void hanging(HangingPlaceEvent e){if(cabin(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void inventory(InventoryClickEvent e){if(e.getWhoClicked() instanceof org.bukkit.entity.Player p)plugin.otherInteraction(p);if(CabinNavigation.blocks(e))e.setCancelled(true);}
    @EventHandler public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof org.bukkit.entity.Player p)plugin.otherInteraction(p);if(CabinNavigation.blocks(e))e.setCancelled(true);}
    @EventHandler public void dropNavigation(PlayerDropItemEvent e){if(CabinNavigation.isTool(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    @EventHandler public void inventoryOpen(InventoryOpenEvent e){if(e.getPlayer() instanceof org.bukkit.entity.Player p)plugin.otherInteraction(p);}
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
    @EventHandler public void spawn(CreatureSpawnEvent e){
        if(cabin(e.getLocation())&&!dev.noqui.lobby.LobbyFeatures.ownsEntity(e.getEntity()))e.setCancelled(true);
    }
    @EventHandler public void weather(WeatherChangeEvent e){if(e.getWorld().equals(plugin.cabin.world))e.setCancelled(true);}
}
