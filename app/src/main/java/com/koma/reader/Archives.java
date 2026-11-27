package com.koma.reader;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.LruCache;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

final class Archives {
    final Context context;
    final File directory;
    final LinkedHashMap<String,Staged> files=new LinkedHashMap<>(4,0.75f,true);
    final LruCache<String,Bitmap> bitmaps=new LruCache<String,Bitmap>((int)Math.min(32L*1024*1024,Runtime.getRuntime().maxMemory()/8)){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    Archives(Context c){context=c.getApplicationContext();directory=new File(c.getCacheDir(),"archives");directory.mkdirs();File[] old=directory.listFiles();if(old!=null)for(File f:old)f.delete();}
    static class Staged {File file;String digest;Staged(File f,String d){file=f;digest=d;}}
    static class Page {
        final Library.Chapter chapter;final String entry,hash;final int width,height,index,total;
        Page(Library.Chapter c,String e,String h,int w,int y,int i,int t){chapter=c;entry=e;hash=h;width=w;height=y;index=i;total=t;}
        String key(int w){return hash+"|"+entry+"|"+w;}
    }
    static class Meta {String name;int w,h;Meta(String n,int w,int h){name=n;this.w=w;this.h=h;}}
    private synchronized Staged stage(Library.Chapter chapter)throws Exception {
        Staged existing=files.get(chapter.key());if(existing!=null&&existing.file.exists())return existing;
        File f=File.createTempFile("cbz-",".zip",directory);
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(chapter.uri));OutputStream out=new BufferedOutputStream(new FileOutputStream(f))){
            if(in==null)throw new IOException("Chapter unavailable");byte[] b=new byte[128*1024];int n;
            while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();md.update(b,0,n);out.write(b,0,n);}
        }catch(Exception e){f.delete();throw e;}
        Staged s=new Staged(f,Library.hex(md.digest()));files.put(chapter.key(),s);
        while(files.size()>3){Iterator<Staged> it=files.values().iterator();Staged old=it.next();it.remove();old.file.delete();}
        return s;
    }
    static boolean image(String name){String n=name.toLowerCase(Locale.ROOT);return !n.startsWith("__macosx/")&&!n.contains("/._")&&(n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp")||n.endsWith(".gif")||n.endsWith(".bmp")||n.endsWith(".avif"));}
    List<Page> index(Library.Chapter c)throws Exception {
        Staged s;ZipFile z;
        synchronized(this){s=stage(c);z=new ZipFile(s.file);}
        try(ZipFile zip=z){
            ArrayList<Meta> meta=new ArrayList<>();Set<String> names=new HashSet<>();
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                ZipEntry e=entries.nextElement();if(e.isDirectory()||!image(e.getName()))continue;
                if(!names.add(e.getName()))throw new IOException("Duplicate page names");
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
                try(InputStream in=zip.getInputStream(e)){BitmapFactory.decodeStream(in,null,o);}catch(Exception ignored){}
                meta.add(new Meta(e.getName(),o.outWidth,o.outHeight));
                if(meta.size()>50000)throw new IOException("Too many pages");
            }
            if(meta.isEmpty())throw new IOException("No supported images");
            meta.sort((a,b)->Rules.natural(a.name,b.name));List<Page> out=new ArrayList<>();
            for(int i=0;i<meta.size();i++){Meta m=meta.get(i);out.add(new Page(c,m.name,s.digest,m.w,m.h,i,meta.size()));}return out;
        }
    }
    Bitmap decode(Page p,int width)throws Exception {
        if(p.width<=0||p.height<=0)throw new IOException("Corrupted image");
        String key=p.key(width);Bitmap cached=bitmaps.get(key);if(cached!=null)return cached;
        Staged s;ZipFile z;
        synchronized(this){s=stage(p.chapter);if(!s.digest.equals(p.hash))throw new IOException("Chapter changed; refresh library");z=new ZipFile(s.file);}
        try(ZipFile zip=z){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            ZipEntry e=zip.getEntry(p.entry);if(e==null)throw new IOException("Page missing");
            BitmapFactory.Options o=new BitmapFactory.Options();int sample=1;
            while((p.width/(long)sample>width*2L||((long)p.width/sample)*(p.height/(long)sample)>8000000L)&&sample<65536)sample*=2;
            o.inSampleSize=sample;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap b;try(InputStream in=zip.getInputStream(e)){b=BitmapFactory.decodeStream(in,null,o);}
            if(b==null)throw new IOException("Corrupted image");
            if(Thread.currentThread().isInterrupted()){b.recycle();throw new InterruptedIOException();}
            bitmaps.put(key,b);return b;
        }
    }
}
