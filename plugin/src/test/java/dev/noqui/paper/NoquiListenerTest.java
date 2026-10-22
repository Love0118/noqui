package dev.noqui.paper;

import java.util.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class NoquiListenerTest {
    @Test void cabinCreatureGuardAllowsOnlyOwnedShopMannequin() {
        var plugin=mock(NoquiPlugin.class);plugin.cabin=mock(Cabin.class);when(plugin.cabin.contains(any())).thenReturn(true);var listener=new NoquiListener(plugin);
        var shop=mock(Mannequin.class);when(shop.getScoreboardTags()).thenReturn(Set.of("noqui_lobby_shop_v1"));var allowed=mock(CreatureSpawnEvent.class);when(allowed.getEntity()).thenReturn(shop);
        listener.spawn(allowed);verify(allowed,never()).setCancelled(true);
        var unowned=mock(Mannequin.class);var ordinary=mock(CreatureSpawnEvent.class);when(ordinary.getEntity()).thenReturn(unowned);listener.spawn(ordinary);verify(ordinary).setCancelled(true);
        var taggedMob=mock(Cow.class);when(taggedMob.getScoreboardTags()).thenReturn(Set.of("noqui_lobby_shop_v1"));var forged=mock(CreatureSpawnEvent.class);when(forged.getEntity()).thenReturn(taggedMob);listener.spawn(forged);verify(forged).setCancelled(true);
    }
    @Test void concurrentQuizMembershipDoesNotHijackDefenseFKey() {
        var plugin=mock(NoquiPlugin.class);var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(plugin.participant(id)).thenReturn(true);when(plugin.ownsCabin(id)).thenReturn(false);
        var event=mock(PlayerSwapHandItemsEvent.class);when(event.getPlayer()).thenReturn(p);new NoquiListener(plugin).swap(event);verify(event,never()).setCancelled(anyBoolean());
    }
}
