package dev.noqui.paper;

import dev.noqui.core.*;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

final class NoquiDialogs {
    private final NoquiPlugin plugin;
    private record Open(BrowserConnections.Entry connection) {}
    private final Map<UUID,Open> opened=new HashMap<>();
    NoquiDialogs(NoquiPlugin plugin){this.plugin=plugin;}
    private static Component text(String s){return Component.text(s);}
    private ActionButton button(Player owner,String label,BiConsumer<Player,DialogResponseView> action) {
        UUID id=owner.getUniqueId(),session=plugin.game().snapshot().session();var entry=plugin.connectionEntry(id);
        var dialog=opened.get(id);
        return ActionButton.create(text(label),null,210,DialogAction.customClick((response,audience)->{
            if(!(audience instanceof Player player)||!player.getUniqueId().equals(id))return;
            plugin.main(()->{if(player.isOnline()&&session.equals(plugin.game().snapshot().session())&&Objects.equals(entry,plugin.connectionEntry(id))&&opened.get(id)==dialog)
                try{opened.remove(id);if(dialog!=null&&dialog.connection()!=null)plugin.dismissBrowser(player);action.accept(player,response);}catch(IllegalArgumentException error){player.sendMessage(text(error.getMessage()));}});
        },ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()));
    }
    private void show(Player p,String title,List<ActionButton> actions) {
        show(p,title,List.of(),actions);
    }
    private void show(Player p,String title,List<DialogBody> body,List<ActionButton> actions) {
        var close=button(p,"닫기",(who,response)->{});
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text(title)).body(body).canCloseWithEscape(true).build())
                .type(actions.isEmpty()?DialogType.notice(close):DialogType.multiAction(actions,close,1))));
    }
    void controls(Player p) {
        if(!plugin.participant(p.getUniqueId()))return;var game=plugin.game();var state=game.snapshot();var buttons=new ArrayList<ActionButton>();
        begin(p,null);
        if(game.pending(p.getUniqueId()))buttons.add(button(p,"제출 취소",(who,r)->{game.cancel(who.getUniqueId());plugin.refreshTickets(who);controls(who);}));
        if(game.canVoteSkip(p.getUniqueId()))buttons.add(button(p,"넘기기 투표",(who,r)->game.voteSkip(who.getUniqueId(),state.roundId())));
        show(p,"노래퀴즈",buttons);
    }
    void proxy(Player p){begin(p,null);show(p,"노래퀴즈",List.of(button(p,"로비",(who,r)->plugin.visitCabin(who)),button(p,"출제",(who,r)->plugin.openProxySubmission(who))));}
    void connection(Player p,BrowserConnections.Entry entry,String url) {
        if(!canPrompt(p)||!entry.equals(plugin.connectionEntry(p.getUniqueId())))return;
        begin(p,entry);
        var actions=new ArrayList<ActionButton>();
        if(url!=null)actions.add(ActionButton.create(text("연결"),null,210,DialogAction.staticAction(ClickEvent.openUrl(url))));
        show(p,"브라우저 연결",actions);
    }
    void submit(Player p) {
        var game=plugin.game();var state=game.snapshot();
        if(!game.canSubmit(p.getUniqueId())||!plugin.hasTicket(p))throw new IllegalArgumentException("사용할 출제권을 확인하세요.");
        begin(p,null);
        var draft=plugin.draft(p.getUniqueId());UUID session=state.session();
        var inputs=List.of(DialogInput.text("youtubeLink",text("YouTube 링크")).initial(draft==null?"":draft.youtubeUrl()).maxLength(2048).build(),
                DialogInput.text("title",text("제목")).initial(draft==null?"":draft.title()).maxLength(160).build(),
                DialogInput.text("tags",text("태그 · ; 구분 · 최대 10개")).initial(draft==null?"":String.join(";",draft.tags())).maxLength(330).build());
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text("노래 제출 · 출제권 1장")).canCloseWithEscape(true).inputs(inputs).build())
                .type(DialogType.confirmation(button(p,"제출",(who,r)->plugin.submit(who,session,r.getText("youtubeLink"),r.getText("title"),r.getText("tags"))),button(p,"취소",(who,r)->{})))));
    }
    void admin(Player p) {
        if(!p.isOp())return;var state=plugin.game().snapshot();var actions=new ArrayList<ActionButton>();
        begin(p,null);
        if(!plugin.started())actions.add(button(p,"시작",(who,r)->{if(who.isOp())plugin.start(0);}));
        else {
            if(state.roundId()!=null)actions.add(button(p,"강제 넘기기",(who,r)->{if(who.isOp())plugin.game().forceSkip(state.roundId());}));
            actions.add(button(p,state.phase()==QuizGame.Phase.PAUSED?"재개":"일시정지",(who,r)->{if(who.isOp()){if(state.phase()==QuizGame.Phase.PAUSED)plugin.game().resume();else plugin.game().pause();}}));
            actions.add(button(p,"힌트",(who,r)->{if(who.isOp())plugin.game().hint();}));
            actions.add(button(p,"종료",(who,r)->{if(who.isOp())plugin.stopQuiz();}));
        }
        show(p,"Noqui 관리",actions);
    }
    private void begin(Player p,BrowserConnections.Entry connection){if(connection==null)plugin.dismissBrowser(p);opened.put(p.getUniqueId(),new Open(connection));}
    boolean canPrompt(Player p) {
        var current=opened.get(p.getUniqueId());if(current!=null&&current.connection()==null)return false;
        var inventory=p.getOpenInventory();if(inventory==null)return false;
        var top=inventory.getTopInventory();boolean personal=top instanceof org.bukkit.inventory.CraftingInventory crafting&&crafting.getMatrix()!=null&&crafting.getMatrix().length==4||top instanceof org.bukkit.inventory.PlayerInventory&&top==p.getInventory();
        return p.isOnline()&&!p.isDead()&&personal;
    }
    void closeConnection(Player p,BrowserConnections.Entry entry) {
        var current=opened.get(p.getUniqueId());if(current!=null&&entry.equals(current.connection())){opened.remove(p.getUniqueId());p.closeDialog();}
    }
    /** A world/inventory interaction means any previous client-side native dialog has been left. */
    void otherInteraction(Player p){opened.remove(p.getUniqueId());plugin.dismissBrowser(p);}
    void closeOwned(Player p){if(opened.remove(p.getUniqueId())!=null)p.closeDialog();}
}
