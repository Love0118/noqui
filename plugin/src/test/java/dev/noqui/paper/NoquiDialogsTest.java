package dev.noqui.paper;

import dev.noqui.core.*;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.*;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.*;
import io.papermc.paper.registry.data.dialog.body.*;
import io.papermc.paper.registry.data.dialog.type.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Inspects the actual native factory arguments emitted to Player.showDialog. */
class NoquiDialogsTest {
    private final UUID id=UUID.randomUUID(),other=UUID.randomUUID();
    private final Player player=mock(Player.class);
    private final NoquiPlugin plugin=mock(NoquiPlugin.class);
    private final Map<DialogAction,DialogActionCallback> callbacks=new IdentityHashMap<>();
    private final List<DialogRegistryEntry.Builder> dialogs=new ArrayList<>();
    private final List<CompletableFuture<MediaBridge.Media>> jobs=new ArrayList<>();
    private final List<Map<String,String>> formValues=new ArrayList<>();
    private long now=100000;
    private MockedStatic<RegistryAccess> registryMock;
    private MockedStatic<DialogInstancesProvider> instancesMock;
    private MockedStatic<InlinedRegistryBuilderProvider> inlineMock;
    private QuizGame game;
    private NoquiDialogs view;
    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"}) void setup() {
        registryMock=mockStatic(RegistryAccess.class);var registryAccess=mock(RegistryAccess.class);registryMock.when(RegistryAccess::registryAccess).thenReturn(registryAccess);
        // Registry.ATTRIBUTE is cached for the JVM when any Registry proxy initializes the interface.
        // A dialog-only empty proxy would poison later Attribute constants when this class runs first.
        Map<String,Attribute> attributes=new HashMap<>();
        org.mockito.stubbing.Answer<Object> registry=call->{
            boolean attributeRegistry=call.getArgument(0)==Attribute.class||call.getArgument(0)==RegistryKey.ATTRIBUTE;
            return java.lang.reflect.Proxy.newProxyInstance(Registry.class.getClassLoader(),new Class[]{Registry.class},(proxy,method,args)->{
                if(attributeRegistry&&(method.getName().equals("get")||method.getName().equals("getOrThrow")))
                    return attributes.computeIfAbsent(args[0].toString(),key->mock(Attribute.class));
                return method.getName().equals("iterator")?List.of().iterator():null;
            });
        };
        when(registryAccess.getRegistry(any(Class.class))).thenAnswer(registry);
        when(registryAccess.getRegistry(any(RegistryKey.class))).thenAnswer(registry);
        // Initialize the built-in Dialog constants under the same registry boundary as Paper.
        assertNull(Dialog.CUSTOM_OPTIONS);
        instancesMock=mockStatic(DialogInstancesProvider.class);var instances=mock(DialogInstancesProvider.class);instancesMock.when(DialogInstancesProvider::instance).thenReturn(instances);
        when(instances.actionButtonBuilder(any())).thenAnswer(call->{
            Component label=call.getArgument(0);var action=new AtomicReference<DialogAction>();var builder=mock(ActionButton.Builder.class,RETURNS_SELF);
            doAnswer(c->{action.set(c.getArgument(0));return builder;}).when(builder).action(any());
            when(builder.build()).thenAnswer(c->{var button=mock(ActionButton.class);when(button.label()).thenReturn(label);when(button.action()).thenReturn(action.get());return button;});return builder;
        });
        when(instances.register(any(),any())).thenAnswer(call->{var action=mock(DialogAction.CustomClickAction.class);callbacks.put(action,call.getArgument(0));return action;});
        when(instances.dialogBaseBuilder(any())).thenAnswer(call->{
            Component title=call.getArgument(0);var body=new AtomicReference<List<DialogBody>>(List.of());var builder=mock(DialogBase.Builder.class,RETURNS_SELF);
            doAnswer(c->{body.set(List.copyOf(c.getArgument(0)));return builder;}).when(builder).body(anyList());
            when(builder.build()).thenAnswer(c->{var base=mock(DialogBase.class);when(base.title()).thenReturn(title);when(base.body()).thenReturn(body.get());return base;});return builder;
        });
        when(instances.plainMessageDialogBody(any())).thenAnswer(call->{Component contents=call.getArgument(0);var body=mock(PlainMessageDialogBody.class);when(body.contents()).thenReturn(contents);return body;});
        when(instances.notice(any())).thenAnswer(call->{var type=mock(NoticeType.class);when(type.action()).thenReturn(call.getArgument(0));return type;});
        when(instances.textBuilder(anyString(),any())).thenAnswer(call->{String key=call.getArgument(0);if(key.equals("youtubeLink"))formValues.add(new LinkedHashMap<>());var values=formValues.getLast();var builder=mock(io.papermc.paper.registry.data.dialog.input.TextDialogInput.Builder.class,RETURNS_SELF);doAnswer(c->{values.put(key,c.getArgument(0));return builder;}).when(builder).initial(anyString());when(builder.build()).thenReturn(mock(io.papermc.paper.registry.data.dialog.input.TextDialogInput.class));return builder;});
        when(instances.confirmation(any(),any())).thenAnswer(call->{var type=mock(ConfirmationType.class);when(type.yesButton()).thenReturn(call.getArgument(0));when(type.noButton()).thenReturn(call.getArgument(1));return type;});
        when(instances.multiAction(anyList())).thenAnswer(call->{
            List<ActionButton> actions=List.copyOf(call.getArgument(0));var exit=new AtomicReference<ActionButton>();var builder=mock(MultiActionType.Builder.class,RETURNS_SELF);
            doAnswer(c->{exit.set(c.getArgument(0));return builder;}).when(builder).exitAction(any());
            when(builder.build()).thenAnswer(c->{var type=mock(MultiActionType.class);when(type.actions()).thenReturn(actions);when(type.exitAction()).thenReturn(exit.get());return type;});return builder;
        });
        inlineMock=mockStatic(InlinedRegistryBuilderProvider.class);var inline=mock(InlinedRegistryBuilderProvider.class);inlineMock.when(InlinedRegistryBuilderProvider::instance).thenReturn(inline);
        when(inline.createDialog(any())).thenAnswer(call->{
            var builder=mock(DialogRegistryEntry.Builder.class,RETURNS_SELF);
            doAnswer(c->{DialogBase base=c.getArgument(0);when(builder.base()).thenReturn(base);return builder;}).when(builder).base(any());
            doAnswer(c->{DialogType type=c.getArgument(0);when(builder.type()).thenReturn(type);return builder;}).when(builder).type(any());
            var factory=mock(RegistryBuilderFactory.class);when(factory.empty()).thenReturn(builder);((Consumer)call.getArgument(0)).accept(factory);
            dialogs.add(builder);return mock(Dialog.class);
        });
        var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){var future=new CompletableFuture<Media>();jobs.add(future);return future;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        game=new QuizGame(bridge,Runnable::run,()->now,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String e){}},115,false);game.start(List.of(id,other));
        when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);when(plugin.game()).thenReturn(game);when(plugin.participant(id)).thenAnswer(c->game.participant(id));
        var inventory=mock(org.bukkit.inventory.InventoryView.class);var personal=mock(org.bukkit.inventory.CraftingInventory.class);when(personal.getMatrix()).thenReturn(new org.bukkit.inventory.ItemStack[4]);when(player.getOpenInventory()).thenReturn(inventory);when(inventory.getTopInventory()).thenReturn(personal);
        when(plugin.connectionEntry(id)).thenAnswer(c->new BrowserConnections.Entry(game.snapshot().session(),id));doAnswer(c->{((Runnable)c.getArgument(0)).run();return null;}).when(plugin).main(any());view=new NoquiDialogs(plugin);
    }
    @AfterEach void close(){if(inlineMock!=null)inlineMock.close();if(instancesMock!=null)instancesMock.close();if(registryMock!=null)registryMock.close();}
    private static String plain(Component value){return PlainTextComponentSerializer.plainText().serialize(value);}
    private DialogType show(){view.controls(player);verify(player,atLeastOnce()).showDialog(any());return dialogs.getLast().type();}
    private List<String> labels(DialogType type){if(type instanceof NoticeType notice)return List.of(plain(notice.action().label()));var multi=(MultiActionType)type;var labels=new ArrayList<>(multi.actions().stream().map(b->plain(b.label())).toList());labels.add(plain(multi.exitAction().label()));assertTrue(labels.size()<=3);return labels;}
    private String body(){return dialogs.getLast().base().body().stream().map(b->plain(((PlainMessageDialogBody)b).contents())).collect(java.util.stream.Collectors.joining("\n"));}
    private void submit(UUID owner){game.submit(owner,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Private","tag"));}
    @Test void dialogRegistryLeavesNativeAttributeConstantsUsable() {
        assertNotNull(Attribute.GRAVITY);assertNotNull(Attribute.MOVEMENT_SPEED);
        assertNotSame(Attribute.GRAVITY,Attribute.MOVEMENT_SPEED);
        assertSame(Attribute.GRAVITY,Registry.ATTRIBUTE.getOrThrow(NamespacedKey.minecraft("gravity")));
        assertSame(Attribute.MOVEMENT_SPEED,Registry.ATTRIBUTE.getOrThrow(NamespacedKey.minecraft("movement_speed")));
    }
    @Test void zeroBalanceHasNoManualBrowserOrSubmissionAction() {
        assertEquals(List.of("닫기"),labels(show()));assertTrue(body().isEmpty());
    }
    @Test void eligibleOpGetsOnlySubmissionAndBrowserWithNoManagementClutter() {
        when(player.isOp()).thenReturn(true);game.grant(id,3);assertEquals(List.of("닫기"),labels(show()));assertTrue(body().isEmpty());
    }
    @Test void pendingSubmissionReplacesSubmitWithCancelAndKeepsMaximumFourButtons() {
        game.grant(id,3);submit(id);assertEquals(List.of("제출 취소","넘기기 투표","닫기"),labels(show()));assertTrue(body().isEmpty());
        jobs.getFirst().complete(new MediaBridge.Media("ready",115007));show();assertTrue(body().isEmpty());
    }
    @Test void closedQueueAndAlreadyCastVoteDoNotOfferUnavailableActions() {
        game.limit(1);game.grant(id,1);game.grant(other,1);submit(other);assertEquals(List.of("넘기기 투표","닫기"),labels(show()));
        game.voteSkip(id,game.snapshot().roundId());assertEquals(List.of("닫기"),labels(show()));
    }
    @Test void winnersKeepTheExistingVoteButtonAndCannotVoteTwice() {
        game.grant(other,1);submit(other);jobs.getFirst().complete(new MediaBridge.Media("ready",60000));
        game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());now=game.snapshot().startsAt()+1;assertTrue(game.confirmPlayback(id,game.snapshot().roundId(),game.snapshot().playbackGeneration(),1,now));assertTrue(game.answer(id,"Private"));
        var type=show();assertEquals(List.of("넘기기 투표","닫기"),labels(type));callbacks.get(((MultiActionType)type).actions().getFirst().action()).accept(null,player);
        assertTrue(game.snapshot().playing());assertEquals(1,game.snapshot().skipVotes());assertEquals(List.of("닫기"),labels(show()));
    }
    @Test void canceledRoundDoesNotShowCancellationForAnAlreadyRefundedReservation() {
        game.limit(1);game.grant(id,1);submit(id);game.forceSkip(game.snapshot().roundId());assertEquals(List.of("닫기"),labels(show()));assertFalse(game.pending(id));assertEquals(1,game.credits(id));
    }
    @Test void oldSessionCallbackCannotCancelNewSubmission() {
        game.grant(id,2);submit(id);var action=((MultiActionType)show()).actions().getFirst().action();
        game.stop();game.start(List.of(id,other));submit(id);callbacks.get(action).accept(null,player);assertTrue(game.pending(id));verify(plugin,never()).browserLink(player);
    }
    @Test void nonparticipantCannotOpenPrimaryDialog() {
        game.leave(id);view.controls(player);verify(player,never()).showDialog(any());assertTrue(dialogs.isEmpty());
    }
    @Test void connectionPromptNeverReplacesTicketFormOrShopAndOnlyClosesItsOwnDialog() {
        var entry=plugin.connectionEntry(id);game.grant(id,1);when(plugin.hasTicket(player)).thenReturn(true);
        view.submit(player);assertFalse(view.canPrompt(player));int forms=dialogs.size();view.connection(player,entry,"https://noqui.danta.qzz.io/join#t=test");assertEquals(forms,dialogs.size());
        view.closeConnection(player,entry);verify(player,never()).closeDialog();
        view.otherInteraction(player);var shop=mock(org.bukkit.inventory.Inventory.class);when(player.getOpenInventory().getTopInventory()).thenReturn(shop);
        view.connection(player,entry,"https://noqui.danta.qzz.io/join#t=test");assertEquals(forms,dialogs.size());
        var workbench=mock(org.bukkit.inventory.CraftingInventory.class);when(workbench.getMatrix()).thenReturn(new org.bukkit.inventory.ItemStack[9]);when(player.getOpenInventory().getTopInventory()).thenReturn(workbench);
        assertFalse(view.canPrompt(player));view.connection(player,entry,"https://noqui.danta.qzz.io/join#t=test");assertEquals(forms,dialogs.size());
        var personal=mock(org.bukkit.inventory.CraftingInventory.class);when(personal.getMatrix()).thenReturn(new org.bukkit.inventory.ItemStack[4]);when(player.getOpenInventory().getTopInventory()).thenReturn(personal);assertTrue(view.canPrompt(player));
        view.connection(player,entry,"https://noqui.danta.qzz.io/join#t=test");assertEquals(forms+1,dialogs.size());view.closeConnection(player,entry);verify(player).closeDialog();
    }
    @Test void escapeExitActionReleasesConnectionOwnershipAndSchedulesOnlyItsOwnRetry() {
        var entry=plugin.connectionEntry(id);view.connection(player,entry,"https://noqui.danta.qzz.io/join#t=test");
        var exit=((MultiActionType)dialogs.getLast().type()).exitAction();assertEquals("닫기",plain(exit.label()));assertNotNull(callbacks.get(exit.action()));
        clearInvocations(plugin,player);callbacks.get(exit.action()).accept(null,player);
        verify(plugin).dismissBrowser(player);assertTrue(view.canPrompt(player));view.closeConnection(player,entry);verify(player,never()).closeDialog();
    }
    @Test void oldConnectionExitCannotDismissANewerSubmissionOrAShutdownSession() {
        var entry=plugin.connectionEntry(id);view.connection(player,entry,"https://noqui.danta.qzz.io/join#t=test");
        var exit=((MultiActionType)dialogs.getLast().type()).exitAction();
        game.grant(id,1);when(plugin.hasTicket(player)).thenReturn(true);view.submit(player);clearInvocations(plugin,player);
        callbacks.get(exit.action()).accept(null,player);verify(plugin,never()).dismissBrowser(player);assertFalse(view.canPrompt(player));
        var form=(ConfirmationType)dialogs.getLast().type();callbacks.get(form.noButton().action()).accept(null,player);assertTrue(view.canPrompt(player));
        view.connection(player,entry,"https://noqui.danta.qzz.io/join#t=test");var stoppedExit=((MultiActionType)dialogs.getLast().type()).exitAction();game.stop();when(plugin.connectionEntry(id)).thenReturn(null);clearInvocations(plugin);
        callbacks.get(stoppedExit.action()).accept(null,player);verify(plugin,never()).dismissBrowser(player);
    }
    @Test void zeroTicketProxyContainsExactlyLobbyAndSubmissionWithoutBrowserControls() {
        view.proxy(player);assertEquals(List.of("로비","출제","닫기"),labels(dialogs.getLast().type()));
        var actions=((MultiActionType)dialogs.getLast().type()).actions();callbacks.get(actions.getFirst().action()).accept(null,player);verify(plugin).visitCabin(player);
        view.proxy(player);actions=((MultiActionType)dialogs.getLast().type()).actions();callbacks.get(actions.get(1).action()).accept(null,player);verify(plugin).openProxySubmission(player);verify(plugin,never()).browserLink(player);
    }
    @Test void acceptedThenRefundedAndCancelledAttemptsAlwaysOpenThreeEmptyFields() {
        game.grant(id,3);when(plugin.hasTicket(player)).thenReturn(true);
        doAnswer(c->{game.submit(id,game.snapshot().revision(),Song.input(c.getArgument(2),c.getArgument(3),c.getArgument(4)));return null;}).when(plugin).submit(eq(player),any(),anyString(),anyString(),anyString());
        view.submit(player);var first=(ConfirmationType)dialogs.getLast().type();var response=mock(io.papermc.paper.dialog.DialogResponseView.class);
        when(response.getText("youtubeLink")).thenReturn("https://youtu.be/BaW_jenozKc");when(response.getText("title")).thenReturn("Previous title");when(response.getText("tags")).thenReturn("Previous tag");
        callbacks.get(first.yesButton().action()).accept(response,player);assertTrue(game.pending(id));jobs.getFirst().complete(new MediaBridge.Media("ready",1000));game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());now=game.snapshot().startsAt()+1;assertTrue(game.confirmPlayback(id,game.snapshot().roundId(),game.snapshot().playbackGeneration(),1,now));game.forceSkip(game.snapshot().roundId());now+=1000;game.tick();assertEquals(2,game.credits(id));
        view.submit(player);var second=(ConfirmationType)dialogs.getLast().type();callbacks.get(second.yesButton().action()).accept(response,player);assertTrue(game.pending(id));game.cancel(id);assertEquals(2,game.credits(id));
        view.submit(player);var third=(ConfirmationType)dialogs.getLast().type();callbacks.get(third.noButton().action()).accept(null,player);view.submit(player);
        assertEquals(4,formValues.size());for(var values:formValues)assertEquals(Map.of("youtubeLink","","title","","tags",""),values);
    }
}
