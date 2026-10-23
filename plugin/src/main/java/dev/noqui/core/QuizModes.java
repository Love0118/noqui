package dev.noqui.core;

import java.util.*;

/** The original script's independent modifiers; combinations preserve their distinct answer/skip behavior. */
public record QuizModes(Set<Mode> enabled,Set<String> bannedAnswers) {
    public enum Mode {
        NO_EASY("날먹금지"),SPEED_X2("스피드x2"),MATCH_SKIP("맞추면바로스킵"),NONCOMPETITIVE("무경쟁"),TITLE_BONUS("제목점수보너스"),TITLE_ONLY("오직제목만"),AUTO_HINT("오토힌트");
        private final String label;
        Mode(String label){this.label=label;}
        public String label(){return label;}
        public static Mode parse(String value){return Arrays.stream(values()).filter(mode->mode.label.equals(value)||mode.name().equalsIgnoreCase(value)).findFirst().orElseThrow(()->new IllegalArgumentException("없는 모드 이름입니다."));}
    }
    public QuizModes {enabled=Set.copyOf(enabled);bannedAnswers=bannedAnswers.stream().map(Song::normalize).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    public boolean has(Mode mode){return enabled.contains(mode);}
    public QuizModes with(Mode mode,boolean on){var next=EnumSet.noneOf(Mode.class);next.addAll(enabled);if(on)next.add(mode);else next.remove(mode);return new QuizModes(next,bannedAnswers);}
    public QuizModes withBanned(Set<String> banned){return new QuizModes(enabled,banned);}
    public int points(Song song,String input) {
        String message=Song.normalize(input);if(message.isEmpty())return 0;
        if(has(Mode.TITLE_ONLY))return song.matchesTitle(input)?has(Mode.TITLE_BONUS)?2:1:0;
        var answers=new ArrayList<String>();answers.add(Song.normalize(song.title()));song.tags().stream().map(Song::normalize).forEach(answers::add);
        for(int i=0;i<answers.size();i++){String answer=answers.get(i);if(message.contains(answer)&&(!has(Mode.NO_EASY)||!bannedAnswers.contains(answer)))return i==0&&has(Mode.TITLE_BONUS)?2:1;}
        return 0;
    }
    public static QuizModes legacy(boolean skip,boolean bonus,boolean auto){var modes=EnumSet.noneOf(Mode.class);if(skip)modes.add(Mode.MATCH_SKIP);if(bonus)modes.add(Mode.TITLE_BONUS);if(auto)modes.add(Mode.AUTO_HINT);return new QuizModes(modes,Set.of());}
}
