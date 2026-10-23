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
    private MockedStatic<RegistryAccess> registryMock;
    private MockedStatic<DialogInstancesProvider> instancesMock;
    private MockedStatic<InlinedRegistryBuilderProvider> inlineMock;
    private QuizGame game;
    private NoquiDialogs view;
    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"}) void setup() {
        registryMock=mockStatic(RegistryAccess.class);var registryAccess=mock(RegistryAccess.class);registryMock.when(RegistryAccess::registryAccess).thenReturn(registryAccess);
        org.mockito.stubbing.Answer<Object> registry=call->java.lang.reflect.Proxy.newProxyInstance(org.bukkit.Registry.class.getClassLoader(),new Class[]{org.bukkit.Registry.class},(proxy,method,args)->method.getName().equals("iterator")?List.of().iterator():null);
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
        game=new QuizGame(bridge,Runnable::run,()->100000,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String e){}},115,false);game.start(List.of(id,other));
        when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);when(plugin.game()).thenReturn(game);when(plugin.participant(id)).thenAnswer(c->game.participant(id));
        when(plugin.connectionEntry(id)).thenAnswer(c->new BrowserConnections.Entry(game.snapshot().session(),id));doAnswer(c->{((Runnable)c.getArgument(0)).run();return null;}).when(plugin).main(any());view=new NoquiDialogs(plugin);
    }
    @AfterEach void close(){if(inlineMock!=null)inlineMock.close();if(instancesMock!=null)instancesMock.close();if(registryMock!=null)registryMock.close();}
    private static String plain(Component value){return PlainTextComponentSerializer.plainText().serialize(value);}
    private MultiActionType show(){view.controls(player);verify(player,atLeastOnce()).showDialog(any());return (MultiActionType)dialogs.getLast().type();}
    private List<String> labels(MultiActionType type){var labels=new ArrayList<>(type.actions().stream().map(b->plain(b.label())).toList());labels.add(plain(type.exitAction().label()));assertTrue(labels.size()<=4);return labels;}
    private String body(){return dialogs.getLast().base().body().stream().map(b->plain(((PlainMessageDialogBody)b).contents())).collect(java.util.stream.Collectors.joining("\n"));}
    private void submit(UUID owner){game.submit(owner,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Private","tag"));}
    @Test void zeroBalanceIsTextAndBrowserIsTheOnlyAction() {
        assertEquals(List.of("브라우저 연결","닫기"),labels(show()));assertTrue(body().isEmpty());
    }
    @Test void eligibleOpGetsOnlySubmissionAndBrowserWithNoManagementClutter() {
        when(player.isOp()).thenReturn(true);game.grant(id,3);assertEquals(List.of("브라우저 연결","닫기"),labels(show()));assertTrue(body().isEmpty());
    }
    @Test void pendingSubmissionReplacesSubmitWithCancelAndKeepsMaximumFourButtons() {
        game.grant(id,3);submit(id);assertEquals(List.of("제출 취소","브라우저 연결","넘기기 투표","닫기"),labels(show()));assertTrue(body().isEmpty());
        jobs.getFirst().complete(new MediaBridge.Media("ready",115007));show();assertTrue(body().isEmpty());
    }
    @Test void closedQueueAndAlreadyCastVoteDoNotOfferUnavailableActions() {
        game.limit(1);game.grant(id,1);game.grant(other,1);submit(other);assertEquals(List.of("브라우저 연결","넘기기 투표","닫기"),labels(show()));
        game.voteSkip(id,game.snapshot().roundId());assertEquals(List.of("브라우저 연결","닫기"),labels(show()));
    }
    @Test void canceledRoundDoesNotShowCancellationForAnAlreadyRefundedReservation() {
        game.limit(1);game.grant(id,1);submit(id);game.forceSkip(game.snapshot().roundId());assertEquals(List.of("브라우저 연결","닫기"),labels(show()));assertFalse(game.pending(id));assertEquals(1,game.credits(id));
    }
    @Test void browserActionWorksButOldSessionCallbackCannotOpenConnection() {
        var action=show().actions().getFirst().action();callbacks.get(action).accept(null,player);verify(plugin).browserLink(player);
        game.stop();game.start(List.of(id,other));callbacks.get(action).accept(null,player);verify(plugin,times(1)).browserLink(player);
    }
    @Test void nonparticipantCannotOpenPrimaryDialog() {
        game.leave(id);view.controls(player);verify(player,never()).showDialog(any());assertTrue(dialogs.isEmpty());
    }
}
