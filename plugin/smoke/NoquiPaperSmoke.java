package dev.noqui.smoke;

import dev.noqui.api.*;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs only in the disposable fixture server, with zero external clients. */
public final class NoquiPaperSmoke extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                require(Bukkit.getPluginManager().isPluginEnabled("Noqui"),"Noqui enabled");
                require(Bukkit.getPluginManager().isPluginEnabled("MCLuckDefense"),"Moma enabled");
                var world=Objects.requireNonNull(Bukkit.getWorld("noqui_cabin"));
                require(world.getWorldBorder().getSize()==250,"250 border");
                require(world.getWorldBorder().getCenter().getX()==416 && world.getWorldBorder().getCenter().getZ()==-192,"border center");
                var spawn=world.getSpawnLocation();require(world.getBlockAt(spawn.clone().subtract(0,1,0)).getType().isSolid(),"spawn floor");
                var dialog=Dialog.create(b->b.empty().base(DialogBase.builder(Component.text("Noqui native fixture"))
                        .inputs(List.of(DialogInput.text("title",Component.text("제목")).maxLength(160).build())).build())
                        .type(DialogType.notice(ActionButton.create(Component.text("닫기"),null,120,null))));
                require(dialog!=null,"native dialog factory");
                var noqui=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Noqui"));
                require(Bukkit.getServicesManager().load(LobbyService.class)!=null,"wallet service");
                require(world.getEntitiesByClass(org.bukkit.entity.Mannequin.class).stream()
                        .filter(entity->entity.getScoreboardTags().contains("noqui_lobby_shop_v1")).count()==1,"single owned shop NPC");
                for(var npc:world.getEntitiesByClass(org.bukkit.entity.Mannequin.class))if(npc.getScoreboardTags().contains("noqui_lobby_shop_v1"))require(npc.getDescription()==null,"shop description label hidden");
                verifyPhysicalItems(world);
                verifyNativeWardrobe();
                verifyMine();
                var quiz=Objects.requireNonNull(Bukkit.getServicesManager().load(QuizService.class));
                var before=Objects.requireNonNull(Bukkit.getServicesManager().load(DefenseService.class));
                boolean expectedActive=Boolean.getBoolean("noqui.smoke.expect-active");
                require(quiz.started()==expectedActive,"persisted active flag");
                if(expectedActive){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui stop");require(!quiz.started(),"restored waiting game stops");}
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui start 200");require(quiz.started(),"empty-server limited start");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui limit 0");require(quiz.started(),"live unlimited limit");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"mud reload");
                var after=Objects.requireNonNull(Bukkit.getServicesManager().load(DefenseService.class));
                require(before!=after,"runtime provider replaced");require(quiz.started(),"quiz survives Moma reload");
                require(Bukkit.getServicesManager().getRegistrations(DefenseService.class).size()==1,"single current provider");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui stop");require(!quiz.started(),"stop");
                if(Boolean.getBoolean("noqui.smoke.keep-active")){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui start");require(quiz.started(),"persist active before shutdown");}
                getLogger().info("NOQUI_SMOKE_OK world shop wallet native-dialog physical-ticket-claim gadget-equipment cabin-layout-serialization services limits empty-start runtime-reload stop persisted-active="+expectedActive);
            } catch(Throwable error){getLogger().log(java.util.logging.Level.SEVERE,"NOQUI_SMOKE_FAIL",error);}
            finally{Bukkit.shutdown();}
        },160);
    }
    private static Object field(Object object,String name)throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void verifyMine()throws Exception {
        var moma=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("MCLuckDefense"));
        Object games=field(moma,"games"),mine=field(games,"mining"),cave=field(mine,"cave");
        var ready=cave.getClass().getDeclaredMethod("ready");ready.setAccessible(true);require((boolean)ready.invoke(cave),"mine terrain ready");
        var world=Objects.requireNonNull(Bukkit.getWorld("moma_mining"));var spawn=world.getSpawnLocation();
        require(world.getBlockAt(spawn.clone().subtract(0,1,0)).getType().isSolid(),"mine spawn floor");
        require(world.getBlockAt(spawn).isPassable()&&world.getBlockAt(spawn.clone().add(0,1,0)).isPassable(),"mine spawn headroom");
        require(world.getEntitiesByClass(org.bukkit.entity.Mannequin.class).stream().filter(e->e.getScoreboardTags().contains("moma_mining_shop_v1")).count()==1,"mine shop NPC");
        for(var npc:world.getEntitiesByClass(org.bukkit.entity.Mannequin.class))if(npc.getScoreboardTags().contains("moma_mining_shop_v1"))require(npc.getDescription()==null,"mine description label hidden");
        Object store=field(mine,"store"),items=field(mine,"items");UUID owner=UUID.randomUUID();
        var profile=store.getClass().getDeclaredMethod("profile",UUID.class);profile.setAccessible(true);Object saved=profile.invoke(store,owner);
        var factory=items.getClass().getDeclaredMethod("pickaxe",UUID.class,saved.getClass());factory.setAccessible(true);
        var first=(org.bukkit.inventory.ItemStack)factory.invoke(items,owner,saved);plain(first);
        require(first.getType()==org.bukkit.Material.IRON_PICKAXE&&first.getItemMeta().hasTool(),"native mine iron pickaxe tool component");
        require(Math.abs(first.getItemMeta().getTool().getRules().getFirst().getSpeed()-3f)<.001,"initial 50% iron speed");
        var fields=saved.getClass().getRecordComponents();Object[] values=new Object[fields.length];Class<?>[] types=new Class<?>[fields.length];
        for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();var accessor=fields[i].getAccessor();accessor.setAccessible(true);values[i]=accessor.invoke(saved);}
        values[1]=6;values[2]=3;var constructor=saved.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);
        var max=(org.bukkit.inventory.ItemStack)factory.invoke(items,owner,constructor.newInstance(values));
        require(Math.abs(max.getItemMeta().getTool().getRules().getFirst().getSpeed()-12f)<.001,"maximum 200% iron speed");
        require(max.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FORTUNE)==3,"native fortune III");
        var restored=org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(org.bukkit.inventory.ItemStack.serializeItemsAsBytes(new org.bukkit.inventory.ItemStack[]{max}))[0];
        require(restored.isSimilar(max),"mine component serialization");
        var wallet=Objects.requireNonNull(Bukkit.getServicesManager().load(LobbyService.class));String tx="smoke-mine:"+owner;
        wallet.rewardStarlight(owner,tx+":seed",150);require(wallet.spendStarlight(owner,tx+":buy",100),"mine shared debit");
        require(wallet.spendStarlight(owner,tx+":buy",100)&&wallet.starlight(owner)==50,"mine shared debit replay");
        require(!wallet.spendStarlight(owner,tx+":insufficient",51)&&wallet.starlight(owner)==50,"mine insufficient funds");
        require(dev.noqui.lobby.LobbyCatalog.defaults().getFirst().stars()==100,"ticket price 100");
    }
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
    private static void plain(org.bukkit.inventory.ItemStack item) {
        var meta=item.getItemMeta();require(meta.displayName()!=null&&meta.displayName().decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC)==net.kyori.adventure.text.format.TextDecoration.State.FALSE,"plain item name");
        require(meta.lore()!=null&&meta.lore().stream().allMatch(line->line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC)==net.kyori.adventure.text.format.TextDecoration.State.FALSE),"plain item lore");
    }
    private static void verifyPhysicalItems(org.bukkit.World world)throws Exception {
        var ledger=new dev.noqui.core.CreditLedger();UUID owner=UUID.randomUUID(),recipient=UUID.randomUUID(),claim=UUID.randomUUID();ledger.grant(owner,3);
        var factory=dev.noqui.paper.QuestionTickets.class.getDeclaredMethod("ticket",UUID.class,int.class);factory.setAccessible(true);
        var ticket=(org.bukkit.inventory.ItemStack)factory.invoke(null,owner,3);plain(ticket);
        var constructor=dev.noqui.paper.QuestionTickets.class.getDeclaredConstructor(dev.noqui.core.CreditLedger.class,java.util.function.BooleanSupplier.class,java.util.function.Consumer.class);constructor.setAccessible(true);
        var tickets=constructor.newInstance(ledger,(java.util.function.BooleanSupplier)()->true,(java.util.function.Consumer<org.bukkit.entity.Player>)p->{});
        var groundFactory=dev.noqui.paper.QuestionTickets.class.getDeclaredMethod("groundItem",UUID.class,int.class);groundFactory.setAccessible(true);
        ledger.drop(claim,owner,2,new dev.noqui.core.CreditLedger.Ground(world.getUID(),0,65,0,0));var ground=(org.bukkit.inventory.ItemStack)groundFactory.invoke(tickets,claim,2);plain(ground);
        var preview=dev.noqui.paper.QuestionTickets.displayItem();require(!preview.getItemMeta().getPersistentDataContainer().has(new org.bukkit.NamespacedKey("noqui","question_ticket_owner")),"preview cannot authorize tickets");
        var triple=dev.noqui.lobby.LobbyFeatures.gadgetPreview("triple_boots");var quad=dev.noqui.lobby.LobbyFeatures.gadgetPreview("quad_boots");var suit=dev.noqui.lobby.LobbyFeatures.gadgetPreview("gravity_suit");
        for(String product:List.of("triple_boots","quad_boots","gravity_suit","hammer","snow_bomb")){
            var item=dev.noqui.lobby.LobbyFeatures.gadgetPreview(product);plain(item);require(!dev.noqui.lobby.LobbyFeatures.isGadgetItem(item),"gadget preview cannot authorize ownership");
            require(item.getType()==org.bukkit.Material.PAPER&&item.getItemMeta().getMaxStackSize()==1,"physical gadget material and stack limit");
            require(new org.bukkit.NamespacedKey("noqui",product).equals(item.getItemMeta().getItemModel()),"gadget custom model");
            if(product.equals("hammer")||product.equals("snow_bomb"))require(!item.getItemMeta().hasEquippable(),"hand gadgets have no equipment component");
            else {
                var equipment=item.getItemMeta().getEquippable();require(equipment.isEquipOnInteract()&&equipment.isSwappable()&&!equipment.isDamageOnHurt()&&!equipment.isDispensable(),"native equipment behavior flags");
                require(new org.bukkit.NamespacedKey("noqui",product).equals(equipment.getModel()),"native gadget equipment asset");
                require(Set.copyOf(equipment.getAllowedEntities()).equals(Set.of(org.bukkit.entity.EntityType.PLAYER)),"only players equip gadgets");
            }
        }
        require(triple.getItemMeta().getEquippable().getSlot()==org.bukkit.inventory.EquipmentSlot.FEET&&quad.getItemMeta().getEquippable().getSlot()==org.bukkit.inventory.EquipmentSlot.FEET,"boots native feet component");
        require(suit.getItemMeta().getEquippable().getSlot()==org.bukkit.inventory.EquipmentSlot.CHEST,"suit native chest component");
        var layout=new org.bukkit.inventory.ItemStack[42];layout[12]=ticket;layout[36]=triple;layout[38]=suit;layout[40]=quad;layout[41]=ground;
        var restored=org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(org.bukkit.inventory.ItemStack.serializeItemsAsBytes(layout));
        require(restored.length==42&&restored[0].isEmpty()&&restored[12].getAmount()==3&&restored[41].getAmount()==2,"full cabin layout and cursor serialization");
        require(restored[36].getItemMeta().getEquippable().getSlot()==org.bukkit.inventory.EquipmentSlot.FEET&&restored[38].getItemMeta().getEquippable().getSlot()==org.bukkit.inventory.EquipmentSlot.CHEST,"equipment survives native serialization");
        require(restored[36].getItemMeta().getItemModel().equals(triple.getItemMeta().getItemModel()),"custom model survives serialization");
        var data=restored[41].getItemMeta().getPersistentDataContainer();require(claim.toString().equals(data.get(new org.bukkit.NamespacedKey("noqui","question_ticket_claim"),org.bukkit.persistence.PersistentDataType.STRING)),"claim survives native serialization");
        require(!data.has(new org.bukkit.NamespacedKey("noqui","question_ticket_owner")),"ground claim is transferable");
        require(ledger.pickup(claim,recipient,2)==2&&ledger.pickup(claim,recipient,2)==0&&ledger.balance(owner)==1&&ledger.balance(recipient)==2,"serialized drop claim redeems once");
    }
    private static void verifyNativeWardrobe()throws Exception {
        var type=Class.forName("dev.noqui.lobby.NativeWardrobe");UUID owner=UUID.randomUUID();
        var headFactory=type.getDeclaredMethod("head",UUID.class,String.class,String.class);headFactory.setAccessible(true);
        var backFactory=type.getDeclaredMethod("back",UUID.class,String.class);backFactory.setAccessible(true);
        var head=(org.bukkit.inventory.ItemStack)headFactory.invoke(null,owner,"bunny","glasses");
        var back=(org.bukkit.inventory.ItemStack)backFactory.invoke(null,owner,"backpack");
        require(head.getItemMeta().getItemModel().equals(new org.bukkit.NamespacedKey("noqui","wearable_head")),"native composite head item");
        require(head.getItemMeta().getCustomModelDataComponent().getStrings().equals(List.of("bunny","glasses")),"native two-part selection");
        require(head.getItemMeta().getEquippable().getSlot()==org.bukkit.inventory.EquipmentSlot.HEAD&&head.getItemMeta().getEquippable().getModel()==null,"head takes custom item geometry path");
        require(back.getItemMeta().getEquippable().getSlot()==org.bukkit.inventory.EquipmentSlot.CHEST&&back.getItemMeta().getEquippable().getModel().equals(new org.bukkit.NamespacedKey("noqui","wearable_back/backpack")),"back takes native equipment path");
        var restored=org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(org.bukkit.inventory.ItemStack.serializeItemsAsBytes(new org.bukkit.inventory.ItemStack[]{head,back}));
        require(restored[0].equals(head)&&restored[1].equals(back),"native wardrobe metadata survives real server serialization");
    }
}
