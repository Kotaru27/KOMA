package com.koma.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

final class CoverStore {
    private final Context context;
    private final File directory;
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(12*1024*1024){
        @Override protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}
    };
    CoverStore(Context c){context=c.getApplicationContext();directory=new File(c.getCacheDir(),"covers-v1");directory.mkdirs();}
    Bitmap cached(Library.Chapter c){return memory.get(c.key());}
    Bitmap cover(Library.Chapter c)throws Exception {
        String key=c.key();Bitmap hit=memory.get(key);if(hit!=null)return hit;
        String id=Library.hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        File target=new File(directory,id+".jpg");
        if(target.exists()){Bitmap b=BitmapFactory.decodeFile(target.getPath());if(b!=null){target.setLastModified(System.currentTimeMillis());memory.put(key,b);return b;}target.delete();}
        if(c.size>768L*1024*1024)return null;
        File temp=File.createTempFile("cover-",".cbz",directory);
        try{
            try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(c.uri));OutputStream out=new BufferedOutputStream(new FileOutputStream(temp))){
                if(in==null)return null;byte[] buf=new byte[65536];int n;long total=0;
                while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();total+=n;if(total>768L*1024*1024)return null;out.write(buf,0,n);}
            }
            try(ZipFile zip=new ZipFile(temp)){
                ArrayList<ZipEntry> images=new ArrayList<>();Enumeration<? extends ZipEntry> en=zip.entries();
                while(en.hasMoreElements()){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();ZipEntry e=en.nextElement();if(!e.isDirectory()&&Archives.image(e.getName()))images.add(e);}
                images.sort((a,b)->Rules.natural(a.getName(),b.getName()));
                for(int i=0;i<Math.min(5,images.size());i++){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    ZipEntry e=images.get(i);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                    try(InputStream in=zip.getInputStream(e)){BitmapFactory.decodeStream(in,null,bounds);}
                    if(bounds.outWidth<=0||bounds.outHeight<=0)continue;
                    BitmapFactory.Options opt=new BitmapFactory.Options();int sample=1;
                    while(Math.max(bounds.outWidth,bounds.outHeight)/(long)sample>720&&sample<65536)sample*=2;
                    opt.inSampleSize=sample;opt.inPreferredConfig=Bitmap.Config.RGB_565;
                    Bitmap b;try(InputStream in=zip.getInputStream(e)){b=BitmapFactory.decodeStream(in,null,opt);}
                    if(b==null)continue;if(Thread.currentThread().isInterrupted()){b.recycle();throw new InterruptedIOException();}
                    memory.put(key,b);File partial=File.createTempFile("thumb-",".tmp",directory);
                    try{try(OutputStream out=new FileOutputStream(partial)){b.compress(Bitmap.CompressFormat.JPEG,85,out);}partial.renameTo(target);}finally{partial.delete();}
                    trim();return b;
                }
            }
            return null;
        }finally{temp.delete();}
    }
    private void trim(){
        File[] files=directory.listFiles((d,n)->n.endsWith(".jpg"));if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));long size=0;for(File f:files)size+=f.length();
        for(File f:files){if(size<=64L*1024*1024)break;long n=f.length();if(f.delete())size-=n;}
    }
    void clearMemory(){memory.evictAll();}
}
