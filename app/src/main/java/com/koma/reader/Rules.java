package com.koma.reader;
import java.util.*;
import java.util.regex.*;

final class Rules {
    private static final Pattern TOKEN = Pattern.compile("[0-9]+|[^0-9]+");
    static int natural(String a, String b) {
        Matcher x=TOKEN.matcher(a), y=TOKEN.matcher(b);
        while (x.find()) {
            if (!y.find()) return 1;
            String p=x.group(), q=y.group();
            int c;
            if (Character.isDigit(p.charAt(0)) && Character.isDigit(q.charAt(0))) {
                String pp=p.replaceFirst("^0+(?!$)",""), qq=q.replaceFirst("^0+(?!$)","");
                c=Integer.compare(pp.length(),qq.length());
                if(c==0)c=pp.compareTo(qq);
            } else c=p.compareToIgnoreCase(q);
            if(c!=0)return c;
        }
        if(y.find())return -1;
        return a.compareTo(b);
    }
    static final int FULL=0xffff;
    static int coverage(int old,int top,int bottom,int height) {
        if(height<=0)return old;
        for(int i=0;i<16;i++) {
            int lo=(int)((long)i*height/16), hi=(int)((long)(i+1)*height/16);
            if(hi>lo && top<=lo && bottom>=hi)old|=1<<i;
        }
        return old;
    }
    static boolean deletionEligible(boolean root, boolean recognizedOnly, boolean write, Set<String> current, Set<String> completed) {
        return !root && recognizedOnly && write && !current.isEmpty() && completed.containsAll(current);
    }
}
