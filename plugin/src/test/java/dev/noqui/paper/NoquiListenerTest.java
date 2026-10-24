package dev.noqui.paper;

import java.util.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class NoquiListenerTest {
    @Test void gsitSeatPassthroughIsRestrictedToEligibleEmptyHandClicksAndPreservesCancellation() {
        var plugin=mock(NoquiPlugin.class);plugin.cabin=mock(Cabin.class);when(plugin.cabin.contains(any())).thenReturn(true);when(plugin.gsitEnabled()).thenReturn(true);
        var player=mock(Player.class);when(player.isValid()).thenReturn(true);var block=mock(org.bukkit.block.Block.class);when(block.getType()).thenReturn(org.bukkit.Material.OAK_STAIRS);var listener=new NoquiListener(plugin);
        var allowed=new org.bukkit.event.player.PlayerInteractEvent(player,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,null,block,org.bukkit.block.BlockFace.UP,org.bukkit.inventory.EquipmentSlot.HAND);
        listener.interact(allowed);org.junit.jupiter.api.Assertions.assertNotEquals(org.bukkit.event.Event.Result.DENY,allowed.useInteractedBlock());
        allowed.setCancelled(true);listener.interact(allowed);org.junit.jupiter.api.Assertions.assertTrue(allowed.isCancelled());
        for(String denied:List.of("container","held","offhand","sneaking","absent")){
            when(block.getType()).thenReturn(denied.equals("container")?org.bukkit.Material.CHEST:org.bukkit.Material.OAK_STAIRS);when(player.isSneaking()).thenReturn(denied.equals("sneaking"));when(plugin.gsitEnabled()).thenReturn(!denied.equals("absent"));
            var item=denied.equals("held")?mock(org.bukkit.inventory.ItemStack.class):null;
            var event=new org.bukkit.event.player.PlayerInteractEvent(player,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,item,block,org.bukkit.block.BlockFace.UP,denied.equals("offhand")?org.bukkit.inventory.EquipmentSlot.OFF_HAND:org.bukkit.inventory.EquipmentSlot.HAND);
            listener.interact(event);org.junit.jupiter.api.Assertions.assertEquals(org.bukkit.event.Event.Result.DENY,event.useInteractedBlock());
        }
    }
    @Test void gsitPlayerStackAllowsEmptyMainHandButKeepsNpcHeldAndExistingCancellations() {
        var plugin=mock(NoquiPlugin.class);plugin.cabin=mock(Cabin.class);when(plugin.cabin.contains(any())).thenReturn(true);when(plugin.gsitEnabled()).thenReturn(true);
        var player=mock(Player.class);var target=mock(Player.class);when(player.isValid()).thenReturn(true);when(target.isValid()).thenReturn(true);when(player.getGameMode()).thenReturn(org.bukkit.GameMode.ADVENTURE);
        var inventory=mock(org.bukkit.inventory.PlayerInventory.class);var hand=mock(org.bukkit.inventory.ItemStack.class);when(player.getInventory()).thenReturn(inventory);when(inventory.getItemInMainHand()).thenReturn(hand);when(hand.getType()).thenReturn(org.bukkit.Material.AIR);var listener=new NoquiListener(plugin);
        var event=new org.bukkit.event.player.PlayerInteractAtEntityEvent(player,target,new org.bukkit.util.Vector(),org.bukkit.inventory.EquipmentSlot.HAND);listener.entityAt(event);org.junit.jupiter.api.Assertions.assertFalse(event.isCancelled());
        event.setCancelled(true);listener.entityAt(event);org.junit.jupiter.api.Assertions.assertTrue(event.isCancelled());
        when(hand.getType()).thenReturn(org.bukkit.Material.STICK);var held=new org.bukkit.event.player.PlayerInteractAtEntityEvent(player,target,new org.bukkit.util.Vector(),org.bukkit.inventory.EquipmentSlot.HAND);listener.entityAt(held);org.junit.jupiter.api.Assertions.assertTrue(held.isCancelled());
        var npc=new org.bukkit.event.player.PlayerInteractAtEntityEvent(player,mock(Mannequin.class),new org.bukkit.util.Vector(),org.bukkit.inventory.EquipmentSlot.HAND);listener.entityAt(npc);org.junit.jupiter.api.Assertions.assertTrue(npc.isCancelled());
    }
    @Test void cabinNavigationRoutesOnlyItsOwnToolsAndPreventsTheirDropWithoutBlockingTickets() {
        var plugin=mock(NoquiPlugin.class);plugin.cabin=mock(Cabin.class);when(plugin.cabin.contains(any())).thenReturn(true);var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(plugin.ownsCabin(player.getUniqueId())).thenReturn(true);var listener=new NoquiListener(plugin);
        for(String id:List.of("defense","settings","traits")){
            var item=CabinNavigationTest.tool(id);var event=new org.bukkit.event.player.PlayerInteractEvent(player,org.bukkit.event.block.Action.RIGHT_CLICK_AIR,item,null,org.bukkit.block.BlockFace.UP,org.bukkit.inventory.EquipmentSlot.HAND);listener.interact(event);
            var dropped=mock(Item.class);when(dropped.getItemStack()).thenReturn(item);var drop=new org.bukkit.event.player.PlayerDropItemEvent(player,dropped);listener.dropNavigation(drop);org.junit.jupiter.api.Assertions.assertTrue(drop.isCancelled());
        }
        verify(plugin).openMinigames(player);verify(plugin).openSettings(player);verify(plugin).openTraits(player);verify(plugin,times(3)).otherInteraction(player);
        for(String id:List.of("question_credit","gadget")){var dropped=mock(Item.class);var item=CabinNavigationTest.tool(id);when(dropped.getItemStack()).thenReturn(item);var drop=new org.bukkit.event.player.PlayerDropItemEvent(player,dropped);listener.dropNavigation(drop);org.junit.jupiter.api.Assertions.assertFalse(drop.isCancelled());}
    }
    @Test void nativeGadgetEquipIsAllowedWhileCabinBlockUseRemainsDenied() {
        var plugin=mock(NoquiPlugin.class);plugin.cabin=mock(Cabin.class);when(plugin.cabin.contains(any())).thenReturn(true);var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(plugin.ownsCabin(player.getUniqueId())).thenReturn(true);
        var item=mock(org.bukkit.inventory.ItemStack.class);var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);var equippable=mock(org.bukkit.inventory.meta.components.EquippableComponent.class);
        when(item.getItemMeta()).thenReturn(meta);when(meta.hasEquippable()).thenReturn(true);when(meta.getEquippable()).thenReturn(equippable);when(equippable.isEquipOnInteract()).thenReturn(true);
        var block=mock(org.bukkit.block.Block.class);var event=new org.bukkit.event.player.PlayerInteractEvent(player,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,item,block,org.bukkit.block.BlockFace.UP,org.bukkit.inventory.EquipmentSlot.HAND);
        try(var lobby=mockStatic(dev.noqui.lobby.LobbyFeatures.class)) {
            lobby.when(()->dev.noqui.lobby.LobbyFeatures.isGadgetItem(item)).thenReturn(true);new NoquiListener(plugin).interact(event);
            org.junit.jupiter.api.Assertions.assertEquals(org.bukkit.event.Event.Result.DENY,event.useInteractedBlock());org.junit.jupiter.api.Assertions.assertEquals(org.bukkit.event.Event.Result.ALLOW,event.useItemInHand());
            lobby.when(()->dev.noqui.lobby.LobbyFeatures.isGadgetItem(item)).thenReturn(false);new NoquiListener(plugin).interact(event);
            org.junit.jupiter.api.Assertions.assertEquals(org.bukkit.event.Event.Result.DENY,event.useItemInHand());org.junit.jupiter.api.Assertions.assertEquals(org.bukkit.event.Event.Result.DENY,event.useInteractedBlock());
        }
    }
    @Test void cabinCreatureGuardAllowsOnlyOwnedShopMannequin() {
        var plugin=mock(NoquiPlugin.class);plugin.cabin=mock(Cabin.class);when(plugin.cabin.contains(any())).thenReturn(true);var listener=new NoquiListener(plugin);
        var shop=mock(Mannequin.class);when(shop.getScoreboardTags()).thenReturn(Set.of("noqui_lobby_shop_v1"));var allowed=mock(CreatureSpawnEvent.class);when(allowed.getEntity()).thenReturn(shop);
        listener.spawn(allowed);verify(allowed,never()).setCancelled(true);
        var unowned=mock(Mannequin.class);var ordinary=mock(CreatureSpawnEvent.class);when(ordinary.getEntity()).thenReturn(unowned);listener.spawn(ordinary);verify(ordinary).setCancelled(true);
        var taggedMob=mock(Cow.class);when(taggedMob.getScoreboardTags()).thenReturn(Set.of("noqui_lobby_shop_v1"));var forged=mock(CreatureSpawnEvent.class);when(forged.getEntity()).thenReturn(taggedMob);listener.spawn(forged);verify(forged).setCancelled(true);
    }
    @Test void concurrentQuizMembershipDoesNotHijackDefenseFKey() {
        org.junit.jupiter.api.Assertions.assertTrue(Arrays.stream(NoquiListener.class.getDeclaredMethods()).noneMatch(m->Arrays.asList(m.getParameterTypes()).contains(PlayerSwapHandItemsEvent.class)));
        org.junit.jupiter.api.Assertions.assertTrue(Arrays.stream(QuestionTickets.class.getDeclaredMethods()).noneMatch(m->Arrays.asList(m.getParameterTypes()).contains(PlayerSwapHandItemsEvent.class)));
    }
    @Test void cabinInventoryMovesAreNotCancelledAndOtherUiDefersBrowserPrompts() {
        var plugin=mock(NoquiPlugin.class);plugin.cabin=mock(Cabin.class);when(plugin.cabin.contains(any())).thenReturn(true);var listener=new NoquiListener(plugin);
        var event=mock(org.bukkit.event.inventory.InventoryClickEvent.class);var player=mock(Player.class);when(event.getWhoClicked()).thenReturn(player);when(event.getHotbarButton()).thenReturn(-1);
        var view=mock(org.bukkit.inventory.InventoryView.class);var top=mock(org.bukkit.inventory.Inventory.class);when(event.getView()).thenReturn(view);when(view.getTopInventory()).thenReturn(top);when(event.getClickedInventory()).thenReturn(mock(org.bukkit.inventory.PlayerInventory.class));
        listener.inventory(event);verify(event,never()).setCancelled(true);
        var tool=mock(org.bukkit.inventory.ItemStack.class);when(event.getCurrentItem()).thenReturn(tool);listener.inventory(event);verify(event,never()).setCancelled(anyBoolean());verify(plugin,times(2)).otherInteraction(player);
        var drag=mock(org.bukkit.event.inventory.InventoryDragEvent.class);when(drag.getWhoClicked()).thenReturn(player);listener.drag(drag);verify(drag,never()).setCancelled(anyBoolean());
    }
}
