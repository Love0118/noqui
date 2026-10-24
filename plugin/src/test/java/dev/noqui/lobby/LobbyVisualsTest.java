package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Consumer;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import com.destroystokyo.paper.SkinParts;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.*;
import org.mockito.*;

class LobbyVisualsTest {
    MockedStatic<SkinParts> skinFactory;
    SkinParts.Mutable allParts;
    @BeforeEach void skinFactory(){
        // Paper's public factory delegates to the running server, just like ResolvableProfile.
        skinFactory=mockStatic(SkinParts.class);allParts=mock(SkinParts.Mutable.class);
        skinFactory.when(SkinParts::allParts).thenReturn(allParts);
    }
    @AfterEach void closeSkinFactory(){skinFactory.close();}
    @Test @SuppressWarnings("unchecked") void mannequinDeduplicatesAndUsesBundledSlimSkinWithoutExternalProfileService() {
        World world=mock(World.class);WorldBorder border=mock(WorldBorder.class);Block air=mock(Block.class),floor=mock(Block.class);Chunk chunk=mock(Chunk.class);
        Material solid=mock(Material.class);when(solid.isSolid()).thenReturn(true);
        when(world.getWorldBorder()).thenReturn(border);when(border.isInside(any())).thenReturn(true);when(air.isPassable()).thenReturn(true);when(floor.getType()).thenReturn(solid);
        when(world.getBlockAt(any(Location.class))).thenAnswer(c->((Location)c.getArgument(0)).getY()<11?floor:air);
        when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
        Mannequin first=mock(Mannequin.class),duplicate=mock(Mannequin.class),foreign=mock(Mannequin.class);
        when(first.getUniqueId()).thenReturn(new UUID(0,1));when(duplicate.getUniqueId()).thenReturn(new UUID(0,2));when(first.isValid()).thenReturn(true);
        when(first.getScoreboardTags()).thenReturn(Set.of(ShopNpc.TAG));when(duplicate.getScoreboardTags()).thenReturn(Set.of(ShopNpc.TAG));when(foreign.getScoreboardTags()).thenReturn(Set.of("unrelated"));
        when(world.getEntitiesByClass(Mannequin.class)).thenReturn(List.of(duplicate,foreign,first));
        try(var profiles=mockStatic(ResolvableProfile.class)) {
            var builder=mock(ResolvableProfile.Builder.class,RETURNS_SELF);var patch=mock(ResolvableProfile.SkinPatchBuilder.class,RETURNS_SELF);
            var profile=mock(ResolvableProfile.class);profiles.when(ResolvableProfile::resolvableProfile).thenReturn(builder);when(builder.build()).thenReturn(profile);
            when(builder.skinPatch(any(Consumer.class))).thenAnswer(c->{((Consumer<ResolvableProfile.SkinPatchBuilder>)c.getArgument(0)).accept(patch);return builder;});
            ShopNpc npc=new ShopNpc(world,409.5,11,-205.5);npc.ensure();npc.ensure();
            verify(duplicate,times(1)).remove();verify(foreign,never()).remove();verify(first,times(1)).setProfile(profile);
            verify(patch).body(net.kyori.adventure.key.Key.key("noqui:entity/shopkeeper"));verify(patch).model(PlayerTextures.SkinModel.SLIM);
            skinFactory.verify(SkinParts::allParts,times(1));verify(first).setSkinParts(allParts);
            verify(first).setImmovable(true);verify(first).setPersistent(true);verify(first).setInvulnerable(true);
            verify(first).customName(net.kyori.adventure.text.Component.text("상점"));
            assertTrue(LobbyFeatures.ownsEntity(first));assertFalse(LobbyFeatures.ownsEntity(foreign));
        }
    }

    @Test void spawnConsumerSetsOwnershipBeforeSpawnEventAndInvalidFloorFails() {
        World world=mock(World.class);WorldBorder border=mock(WorldBorder.class);when(world.getWorldBorder()).thenReturn(border);when(border.isInside(any())).thenReturn(false);
        ShopNpc npc=new ShopNpc(world,409.5,11,-205.5);assertThrows(IllegalStateException.class,npc::ensure);verify(world,never()).spawn(any(Location.class),eq(Mannequin.class),any(Consumer.class));
    }

    @Test @SuppressWarnings("unchecked") void newMannequinReceivesTheNarrowSpawnGuardTagInsideItsSpawnConsumer() {
        World world=mock(World.class);WorldBorder border=mock(WorldBorder.class);Chunk chunk=mock(Chunk.class);Block air=mock(Block.class),floor=mock(Block.class);Material solid=mock(Material.class);
        when(solid.isSolid()).thenReturn(true);when(floor.getType()).thenReturn(solid);when(air.isPassable()).thenReturn(true);when(world.getWorldBorder()).thenReturn(border);when(border.isInside(any())).thenReturn(true);
        when(world.getBlockAt(any(Location.class))).thenAnswer(c->((Location)c.getArgument(0)).getY()<11?floor:air);when(world.getChunkAt(any(Location.class))).thenReturn(chunk);when(world.getEntitiesByClass(Mannequin.class)).thenReturn(List.of());
        Mannequin fresh=mock(Mannequin.class);Set<String> tags=new HashSet<>();when(fresh.getScoreboardTags()).thenReturn(tags);when(fresh.isValid()).thenReturn(true);
        doAnswer(c->{tags.add(c.getArgument(0));return true;}).when(fresh).addScoreboardTag(anyString());
        when(world.spawn(any(Location.class),eq(Mannequin.class),any(Consumer.class))).thenAnswer(c->{
            assertFalse(LobbyFeatures.ownsEntity(fresh));((Consumer<Mannequin>)c.getArgument(2)).accept(fresh);
            // Paper invokes CreatureSpawnEvent after this consumer returns.
            assertTrue(LobbyFeatures.ownsEntity(fresh));return fresh;
        });
        try(var profiles=mockStatic(ResolvableProfile.class)) {
            var builder=mock(ResolvableProfile.Builder.class,RETURNS_SELF);profiles.when(ResolvableProfile::resolvableProfile).thenReturn(builder);when(builder.build()).thenReturn(mock(ResolvableProfile.class));
            ShopNpc npc=new ShopNpc(world,409.5,11,-205.5);npc.ensure();npc.ensure();verify(world,times(1)).spawn(any(Location.class),eq(Mannequin.class),any(Consumer.class));
            verify(fresh).customName(net.kyori.adventure.text.Component.text("상점"));
        }
    }

}
