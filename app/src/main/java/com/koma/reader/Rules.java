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
            int lo=(int)((long)i*height/16), hi=(int)(((long)(i+1)*height+15)/16);
            if(hi>lo && top<=lo && bottom>=hi)old|=1<<i;
        }
        return old;
    }
    static boolean strictDescendant(String root,String folder) {
        if(root==null||folder==null||!root.startsWith("primary:")||!folder.startsWith("primary:")||root.equals(folder))return false;
        String prefix=root.endsWith(":")||root.endsWith("/")?root:root+"/";
        if(!folder.startsWith(prefix))return false;
        for(String part:folder.substring("primary:".length()).split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))return false;
        return true;
    }
    static boolean stableSnapshot(Set<String> expected,Set<String> verified,Set<String> latest) {
        return !expected.isEmpty()&&expected.equals(verified)&&verified.equals(latest);
    }
    // Mode IDs preserve preferences from earlier builds.
    static int mangaOrder(int mode,String a,String b,long readA,long readB,long addA,long addB,int countA,int countB) {
        int n;
        switch(mode){
            case 1:n=Long.compare(readB,readA);break;
            case 2:n=Long.compare(addB,addA);break;
            case 3:return natural(b,a);
            case 4:n=Long.compare(addA,addB);break;
            case 5:n=Integer.compare(countB,countA);break;
            case 6:n=Integer.compare(countA,countB);break;
            default:n=0;
        }
        return n!=0?n:natural(a,b);
    }
    static boolean deletionEligible(boolean root, boolean recognizedOnly, boolean write, Set<String> current, Set<String> completed) {
        return !root && recognizedOnly && write && !current.isEmpty() && completed.containsAll(current);
    }
}
