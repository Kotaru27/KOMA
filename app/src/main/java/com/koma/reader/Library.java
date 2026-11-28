package com.koma.reader;
import android.content.*;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.content.UriPermission;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

final class Library {
    final Context context;
    final SharedPreferences prefs;
    Library(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("koma",0);}
    static class Chapter {
        final String name, uri; final long size, modified;
        Chapter(String n,String u,long s,long m){name=n;uri=u;size=s;modified=m;}
        String key(){return uri+"|"+size+"|"+modified;}
        JSONObject json() throws JSONException{return new JSONObject().put("name",name).put("uri",uri).put("size",size).put("modified",modified);}
        static Chapter parse(JSONObject o)throws JSONException{return new Chapter(o.getString("name"),o.getString("uri"),o.getLong("size"),o.getLong("modified"));}
    }
    static class Manga {
        final String name,uri; final List<Chapter> chapters=new ArrayList<>();
        Manga(String n,String u){name=n;uri=u;}
        JSONObject json()throws JSONException {JSONArray a=new JSONArray();for(Chapter c:chapters)a.put(c.json());return new JSONObject().put("name",name).put("uri",uri).put("chapters",a);}
    }
    static class Node {
        String id,name,mime,uri; long size,modified,flags;
        boolean directory(){return Document.MIME_TYPE_DIR.equals(mime);}
        boolean chapter(){return !directory()&&name.toLowerCase(Locale.ROOT).endsWith(".cbz");}
        Chapter asChapter(){return new Chapter(name,uri,size,modified);}
    }
    String root(){return prefs.getString("root","");}
    boolean permission(String root,boolean write){for(UriPermission p:context.getContentResolver().getPersistedUriPermissions())if(p.getUri().toString().equals(root)&&p.isReadPermission()&&(!write||p.isWritePermission()))return true;return false;}
    List<Node> children(Uri tree,String id)throws IOException {
        Uri uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        String[] projection={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED,Document.COLUMN_FLAGS};
        List<Node> out=new ArrayList<>();
        try(Cursor c=context.getContentResolver().query(uri,projection,null,null,null)){
            if(c==null)throw new IOException("Cannot list folder");
            if(c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING,false))throw new IOException("Folder still loading");
            while(c.moveToNext()){
                Node n=new Node();n.id=c.getString(0);n.name=c.getString(1);n.mime=c.getString(2);
                if(n.id==null||n.name==null||n.mime==null)throw new IOException("Incomplete folder listing");
                n.size=c.isNull(3)?-1:c.getLong(3);n.modified=c.isNull(4)?-1:c.getLong(4);n.flags=c.isNull(5)?0:c.getLong(5);
                n.uri=DocumentsContract.buildDocumentUriUsingTree(tree,n.id).toString();out.add(n);
            }
        }return out;
    }
    List<Manga> scan(String root)throws IOException {
        if(!permission(root,false))throw new SecurityException("Folder access expired");
        Uri tree=Uri.parse(root);String id=DocumentsContract.getTreeDocumentId(tree);
        List<Manga> out=new ArrayList<>();Set<String> visited=new HashSet<>();
        ArrayDeque<String[]> stack=new ArrayDeque<>();stack.push(new String[]{id,id.substring(Math.max(id.lastIndexOf('/'),id.lastIndexOf(':'))+1)});
        while(!stack.isEmpty()){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            String[] p=stack.pop();if(!visited.add(p[0]))continue;
            Manga m=new Manga(p[1],DocumentsContract.buildDocumentUriUsingTree(tree,p[0]).toString());
            for(Node n:children(tree,p[0]))if(n.directory())stack.push(new String[]{n.id,n.name});else if(n.chapter())m.chapters.add(n.asChapter());
            if(!m.chapters.isEmpty()){m.chapters.sort((a,b)->Rules.natural(a.name,b.name));out.add(m);}
        }
        out.sort((a,b)->Rules.natural(a.name,b.name));return out;
    }
    long added(Manga m){long first=prefs.getLong("added:"+m.uri,0);if(first>0)return first;long newest=0;for(Chapter c:m.chapters)newest=Math.max(newest,c.modified);return newest;}
    void cache(String root,List<Manga> list){try{JSONArray a=new JSONArray();SharedPreferences.Editor e=prefs.edit();long now=System.currentTimeMillis();for(Manga m:list){a.put(m.json());if(!prefs.contains("added:"+m.uri))e.putLong("added:"+m.uri,now);}e.putString("index:"+root,a.toString()).apply();}catch(JSONException ignored){}}
    List<Manga> cached(String root){
        List<Manga> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString("index:"+root,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Manga m=new Manga(o.getString("name"),o.getString("uri"));JSONArray b=o.getJSONArray("chapters");for(int j=0;j<b.length();j++)m.chapters.add(Chapter.parse(b.getJSONObject(j)));out.add(m);}}catch(JSONException ignored){}return out;
    }
    static class Position {
        final String chapter, name;final int page,offset,total;final long time;
        Position(String c,String n,int p,int o,int t,long tm){chapter=c;name=n;page=p;offset=o;total=t;time=tm;}
    }
    Position position(Manga m){try{JSONObject o=new JSONObject(prefs.getString("pos:"+m.uri,"{}"));return new Position(o.getString("chapter"),o.getString("name"),o.getInt("page"),o.getInt("offset"),o.getInt("total"),o.getLong("time"));}catch(JSONException e){return null;}}
    void save(Manga m,Chapter c,int page,int offset,int total){try{prefs.edit().putString("pos:"+m.uri,new JSONObject().put("chapter",c.uri).put("name",c.name).put("page",page).put("offset",offset).put("total",total).put("time",System.currentTimeMillis()).toString()).putString("active",m.uri).apply();}catch(JSONException ignored){}}
    String masksKey(String hash){return "coverage-v2:"+hash;}
    int[] coverage(String hash,int count){int[] a=new int[count];String s=prefs.getString(masksKey(hash),"");String[] parts=s.split(",");if(parts.length==count)try{for(int i=0;i<count;i++)a[i]=Integer.parseInt(parts[i]);}catch(NumberFormatException e){Arrays.fill(a,0);}return a;}
    void saveCoverage(String hash,int[] masks){StringBuilder s=new StringBuilder();for(int i=0;i<masks.length;i++){if(i>0)s.append(',');s.append(masks[i]);}prefs.edit().putString(masksKey(hash),s.toString()).apply();}
    void certify(Chapter c,String hash,int[] masks){for(int n:masks)if(n!=Rules.FULL)return;if(masks.length==0||c.size<=0||c.modified<=0)return;String key="complete-v2:"+c.key();if(!hash.equals(prefs.getString(key,"")))prefs.edit().putString(key,hash).apply();}
    boolean completed(Chapter c){return c.size>0&&c.modified>0&&!prefs.getString("complete-v2:"+c.key(),"").isEmpty();}
    boolean allCertified(Manga m){if(m.chapters.isEmpty())return false;for(Chapter c:m.chapters)if(!completed(c))return false;return true;}
    static String digest(InputStream in)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();md.update(b,0,n);}return hex(md.digest());
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    // Only the local primary external-storage provider is eligible. No generic
    // provider, root, nested child directory, unknown file or partial listing.
    private boolean folderIdentity(Uri tree,Uri folder,String id)throws IOException {
        String[] fields={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS};
        try(Cursor c=context.getContentResolver().query(folder,fields,null,null,null)){
            if(c==null||c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING,false)||!c.moveToFirst())return false;
            boolean ok=id.equals(c.getString(0))&&Document.MIME_TYPE_DIR.equals(c.getString(1))
                &&!c.isNull(2)&&(c.getLong(2)&Document.FLAG_SUPPORTS_DELETE)!=0;
            return ok&&!c.moveToNext();
        }
    }
    private Set<String> snapshot(List<Node> nodes,String id) {
        Set<String> result=new HashSet<>();
        for(Node n:nodes){
            if(!n.chapter()||n.size<=0||n.modified<=0||n.name.contains("/")||n.name.contains("\\")||!n.id.equals(id+"/"+n.name))return Collections.emptySet();
            if(!result.add(n.asChapter().key()))return Collections.emptySet();
        }
        return result;
    }
    // One folder-level delete, never individual files. SAF cannot provide an
    // atomic compare-and-delete; a concurrent external edit is a residual risk.
    boolean removeCompletedManga(String root,Manga manga)throws Exception {
        if(!root.equals(root())||!permission(root,true)||!allCertified(manga))return false;
        Uri tree=Uri.parse(root), folder=Uri.parse(manga.uri);
        if(!"com.android.externalstorage.documents".equals(tree.getAuthority())||!tree.getAuthority().equals(folder.getAuthority()))return false;
        String base=DocumentsContract.getTreeDocumentId(tree), id=DocumentsContract.getDocumentId(folder);
        if(!base.equals(DocumentsContract.getTreeDocumentId(folder))||!Rules.strictDescendant(base,id)||!folderIdentity(tree,folder,id))return false;
        Set<String> expected=new HashSet<>();for(Chapter c:manga.chapters)if(!expected.add(c.key()))return false;
        List<Node> nodes=children(tree,id);Set<String> verified=snapshot(nodes,id);
        if(!Rules.stableSnapshot(expected,verified,verified))return false;
        Set<String> completed=new HashSet<>();
        for(Node n:nodes){
            if(Thread.currentThread().isInterrupted())return false;
            Chapter c=n.asChapter();String hash=prefs.getString("complete-v2:"+c.key(),"");
            if(hash.isEmpty())return false;
            try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(c.uri))){if(in==null||!hash.equals(digest(in)))return false;}
            completed.add(c.key());
        }
        if(!Rules.deletionEligible(false,true,true,verified,completed))return false;
        if(!root.equals(root())||!permission(root,true)||Thread.currentThread().isInterrupted()||!folderIdentity(tree,folder,id))return false;
        Set<String> latest=snapshot(children(tree,id),id);
        if(!Rules.stableSnapshot(expected,verified,latest)||!root.equals(root())||!permission(root,true)||Thread.currentThread().isInterrupted())return false;
        if(!DocumentsContract.deleteDocument(context.getContentResolver(),folder))return false;
        SharedPreferences.Editor e=prefs.edit().remove("pos:"+manga.uri).remove("added:"+manga.uri);
        if(manga.uri.equals(prefs.getString("active","")))e.remove("active");
        for(Chapter c:manga.chapters){String hash=prefs.getString("complete-v2:"+c.key(),"");e.remove("complete-v2:"+c.key());if(!hash.isEmpty())e.remove(masksKey(c.uri+"|"+hash));}
        e.apply();return true;
    }
}
