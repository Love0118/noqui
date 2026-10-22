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
    NoquiDialogs(NoquiPlugin plugin){this.plugin=plugin;}
    private static Component text(String s){return Component.text(s);}
    private ActionButton button(Player owner,String label,BiConsumer<Player,DialogResponseView> action) {
        UUID id=owner.getUniqueId(),session=plugin.game().snapshot().session();var entry=plugin.connectionEntry(id);
        return ActionButton.create(text(label),null,210,DialogAction.customClick((response,audience)->{
            if(!(audience instanceof Player player)||!player.getUniqueId().equals(id))return;
            plugin.main(()->{if(player.isOnline()&&session.equals(plugin.game().snapshot().session())&&Objects.equals(entry,plugin.connectionEntry(id)))
                try{action.accept(player,response);}catch(IllegalArgumentException error){player.sendMessage(text(error.getMessage()));}});
        },ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()));
    }
    private void show(Player p,String title,List<ActionButton> actions) {
        var close=ActionButton.create(text("닫기"),null,120,null);
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text(title)).canCloseWithEscape(true).build())
                .type(actions.isEmpty()?DialogType.notice(close):DialogType.multiAction(actions,close,1))));
    }
    void controls(Player p) {
        if(!plugin.participant(p.getUniqueId()))return;var game=plugin.game();var state=game.snapshot();var buttons=new ArrayList<ActionButton>();
        if(game.credits(p.getUniqueId())>0&&!game.pending(p.getUniqueId()))buttons.add(button(p,"노래 제출 · 출제권 "+game.credits(p.getUniqueId())+"개",(who,r)->submit(who)));
        else buttons.add(button(p,"출제권 "+game.credits(p.getUniqueId())+"개"+(game.pending(p.getUniqueId())?" · 제출 대기 중":""),(who,r)->controls(who)));
        if(game.pending(p.getUniqueId()))buttons.add(button(p,"제출 취소 · 출제권 반환",(who,r)->{game.cancel(who.getUniqueId());controls(who);}));
        buttons.add(button(p,"브라우저 연결",(who,r)->plugin.browserLink(who)));
        buttons.add(button(p,"점수 · "+state.scores().getOrDefault(p.getUniqueId(),0),(who,r)->scores(who)));
        buttons.add(button(p,"BGM ON / OFF",(who,r)->{plugin.recovery.toggleBgm(who);controls(who);}));
        buttons.add(button(p,"상점 · 별빛 ✦ / 정답 포인트",(who,r)->plugin.openShop(who)));
        if(state.roundId()!=null)buttons.add(button(p,"넘기기 투표 · "+state.skipVotes()+" / "+state.skipRequired(),(who,r)->game.voteSkip(who.getUniqueId(),state.roundId())));
        if(plugin.ownsCabin(p.getUniqueId()))buttons.add(button(p,"MC Luck Defense 바로 시작",(who,r)->plugin.toDefense(who)));
        if(p.isOp())buttons.add(button(p,"관리",(who,r)->admin(who)));
        buttons.add(button(p,"노래퀴즈 나가기",(who,r)->plugin.leave(who)));
        show(p,"노래퀴즈 · 출제권 "+game.credits(p.getUniqueId())+"개",buttons);
    }
    void connection(Player p,BrowserConnections.Entry entry,String url) {

        var actions=new ArrayList<ActionButton>();
        if(url!=null)actions.add(ActionButton.create(text("연결"),null,210,DialogAction.staticAction(ClickEvent.openUrl(url))));
        show(p,url==null?"브라우저 연결 준비 중":"브라우저 연결",actions);
    }
    private void scores(Player p) {
        String lines=plugin.game().snapshot().scores().entrySet().stream().sorted(Map.Entry.<UUID,Integer>comparingByValue().reversed()).limit(10)
                .map(e->Objects.toString(org.bukkit.Bukkit.getOfflinePlayer(e.getKey()).getName(),"참가자")+" · "+e.getValue()+"점").collect(java.util.stream.Collectors.joining("\n"));
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text("점수")).body(List.of(DialogBody.plainMessage(text(lines)))).build()).type(DialogType.notice())));
    }
    void submit(Player p) {
        var game=plugin.game();var state=game.snapshot();
        if(!plugin.participant(p.getUniqueId())||game.credits(p.getUniqueId())<1||game.pending(p.getUniqueId()))throw new IllegalArgumentException("출제권과 제출 대기 상태를 확인하세요.");
        var draft=plugin.draft(p.getUniqueId());long revision=state.revision();
        var inputs=List.of(DialogInput.text("youtubeLink",text("YouTube 링크")).initial(draft==null?"":draft.youtubeUrl()).maxLength(2048).build(),
                DialogInput.text("title",text("제목")).initial(draft==null?"":draft.title()).maxLength(160).build(),
                DialogInput.text("tags",text("태그 · ; 구분 · 최대 10개")).initial(draft==null?"":String.join(";",draft.tags())).maxLength(330).build());
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text("노래 제출 · 출제권 1개")).canCloseWithEscape(true).inputs(inputs).build())
                .type(DialogType.confirmation(button(p,"제출",(who,r)->plugin.submit(who,revision,r.getText("youtubeLink"),r.getText("title"),r.getText("tags"))),ActionButton.create(text("취소"),null,120,null)))));
    }
    void admin(Player p) {
        if(!p.isOp())return;var state=plugin.game().snapshot();var actions=new ArrayList<ActionButton>();
        if(!plugin.started())actions.add(button(p,"시작",(who,r)->{if(who.isOp())plugin.start(0);}));
        else {
            if(state.roundId()!=null)actions.add(button(p,"강제 넘기기",(who,r)->{if(who.isOp())plugin.game().forceSkip(state.roundId());}));
            actions.add(button(p,state.phase()==QuizGame.Phase.PAUSED?"재개":"일시정지",(who,r)->{if(who.isOp()){if(state.phase()==QuizGame.Phase.PAUSED)plugin.game().resume();else plugin.game().pause();}}));
            actions.add(button(p,"힌트",(who,r)->{if(who.isOp())plugin.game().hint();}));
            actions.add(button(p,"종료",(who,r)->{if(who.isOp())plugin.stopQuiz();}));
        }
        show(p,"Noqui 관리",actions);
    }
}
