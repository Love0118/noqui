package dev.noqui.core;

import java.util.*;
import java.util.random.RandomGenerator;

final class HintMask {
    private final String title;
    private final boolean[] revealed;
    private final RandomGenerator random;
    private boolean initialized;
    HintMask(String title,RandomGenerator random) {
        this.title=title.replaceAll("[^0-9A-Za-z가-힣ㄱ-ㅎㅏ-ㅣ]","");
        revealed=new boolean[this.title.length()];this.random=random;
    }
    String next() {
        if(initialized) {
            var hidden=new ArrayList<Integer>();for(int i=0;i<revealed.length;i++)if(!revealed[i])hidden.add(i);
            int count=Math.min((int)Math.ceil(title.length()*.25),Math.max(0,hidden.size()-1));
            for(int i=0;i<count;i++)revealed[hidden.remove(random.nextInt(hidden.size()))]=true;
        }
        initialized=true;var result=new StringBuilder();String initials="ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
        for(int i=0;i<title.length();i++){char c=title.charAt(i);result.append(revealed[i]?c:c>='가'&&c<='힣'?initials.charAt((c-'가')/588):'_');}
        return result.toString();
    }
}
