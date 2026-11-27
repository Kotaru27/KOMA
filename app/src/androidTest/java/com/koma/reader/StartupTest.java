package com.koma.reader;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import android.graphics.Bitmap;
@RunWith(AndroidJUnit4.class)
public class StartupTest {
 @Test public void activityStarts(){try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){s.onActivity(a->assertNotNull(a.findViewById(android.R.id.content)));}}
 @Test public void archiveIndexAndDecode() throws Exception {
   android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
   File f=new File(c.getCacheDir(),"fixture.cbz");Bitmap b=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888);
   try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){for(String name:List.of("10.png","2.png","1.png")){z.putNextEntry(new ZipEntry(name));b.compress(Bitmap.CompressFormat.PNG,100,z);z.closeEntry();}z.putNextEntry(new ZipEntry("3.png"));z.write(new byte[]{1,2,3});z.closeEntry();}
   b.recycle();
   Archives a=new Archives(c);Library.Chapter ch=new Library.Chapter("fixture",android.net.Uri.fromFile(f).toString(),f.length(),f.lastModified());
   List<Archives.Page> pages=a.index(ch);assertEquals(4,pages.size());assertEquals("1.png",pages.get(0).entry);assertEquals("10.png",pages.get(3).entry);assertEquals(32,a.decode(pages.get(0),100).getWidth());
   try{a.decode(pages.get(2),100);fail("Corrupt page decoded");}catch(IOException expected){}
   f.delete();
 }
}
