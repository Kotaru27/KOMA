package com.koma.reader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import androidx.viewpager2.widget.ViewPager2;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService scanner=Executors.newSingleThreadExecutor(), indexer=Executors.newSingleThreadExecutor();
    final ThreadPoolExecutor decoder=(ThreadPoolExecutor)Executors.newFixedThreadPool(2), coverWorker=(ThreadPoolExecutor)Executors.newFixedThreadPool(1);
    Library library; Archives archives; CoverStore covers;
    GridLayoutManager libraryGrid; TextView sortButton, backButton; int sortMode=0;
    String lastSavedKey=""; int lastSavedOffset=Integer.MIN_VALUE; String lastControlKey=""; int lastControlPage=-1;
    final String[] sortNames={"A–Z", "Recently read", "Recently added"};
    List<Library.Manga> mangas=new ArrayList<>();
    Library.Manga selected, browsing;
    final List<Archives.Page> pages=new ArrayList<>();
    final Map<String,int[]> coverage=new HashMap<>();
    ViewPager2 pager; FrameLayout reader; LinearLayout history,manager,top,bottom;
    TextView status,heading,counter,libraryTitle,libraryMessage;
    EditText search; SeekBar scrubber; RecyclerView libraryRows,historyRows; AxisList reading;
    LinearLayoutManager layout; PageAdapter pageAdapter;
    RowAdapter libraryAdapter, historyAdapter;
    boolean controls=false,loading=false,scanning=false,restoring=false,dead=false,pickerOpen=false,scrubbing=false;
    int generation=0, scanGeneration=0, lastLoaded=-1, failedNext=-1;
    long lastCheckpoint=0,ignoreCoverageUntil=0;
    Future<?> indexJob, scanJob;
    static final int PICK=47;
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    final Runnable heartbeat=new Runnable(){public void run(){if(dead)return;if(pager!=null&&pager.getCurrentItem()==1&&hasWindowFocus()&&!scrubbing&&!restoring){observe();checkpoint(false);}main.postDelayed(this,350);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);library=new Library(this);archives=new Archives(this);covers=new CoverStore(this);sortMode=Math.max(0,Math.min(2,library.prefs.getInt("library-sort",0)));
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        requestRefreshRate();
        reader=new FrameLayout(this);reader.setBackgroundColor(Color.BLACK);
        history=screen();manager=screen();
        buildReader();buildHistory();buildManager();
        pager=new ViewPager2(this);pager.setBackgroundColor(Color.BLACK);pager.setOffscreenPageLimit(2);
        View[] screens={history,reader,manager};
        pager.setAdapter(new RecyclerView.Adapter<ScreenHolder>(){
            @Override public ScreenHolder onCreateViewHolder(ViewGroup p,int t){FrameLayout f=new FrameLayout(MainActivity.this);f.setLayoutParams(new RecyclerView.LayoutParams(-1,-1));return new ScreenHolder(f);}
            @Override public int getItemCount(){return screens.length;}
            @Override public void onBindViewHolder(ScreenHolder h,int p){View v=screens[p];if(v.getParent()!=null)((ViewGroup)v.getParent()).removeView(v);h.frame.removeAllViews();h.frame.addView(v,new FrameLayout.LayoutParams(-1,-1));}
        });
        setContentView(pager);pager.setCurrentItem(1,false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback(){@Override public void onPageSelected(int p){if(p!=1){checkpoint(true);if(p==0)renderHistory();if(p==2&&sortMode==1)renderLibrary();}else {hideKeyboard();}bars(p==1);if(p==1){coverWorker.purge();}}});
        bars(true);main.post(heartbeat);
        String root=library.root();
        if(root.isEmpty()||!library.permission(root,false)){status.setText("Choose your manga folder");main.postDelayed(()->{if(!dead)pickFolder();},350);}
        else {mangas=library.cached(root);renderLibrary();renderHistory();restoreActive();refresh();}
    }
    static class ScreenHolder extends RecyclerView.ViewHolder{FrameLayout frame;ScreenHolder(FrameLayout f){super(f);frame=f;}}
    void requestRefreshRate(){
        try{Display d=getWindowManager().getDefaultDisplay();Display.Mode current=d.getMode(),best=current;
            for(Display.Mode m:d.getSupportedModes())if(m.getPhysicalWidth()==current.getPhysicalWidth()&&m.getPhysicalHeight()==current.getPhysicalHeight()&&m.getRefreshRate()>best.getRefreshRate())best=m;
            WindowManager.LayoutParams p=getWindow().getAttributes();p.preferredDisplayModeId=best.getModeId();getWindow().setAttributes(p);
        }catch(Exception ignored){}
    }
    void bars(boolean immersive){
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(immersive)c.hide(WindowInsets.Type.systemBars());else c.show(WindowInsets.Type.systemBars());}}
        else getWindow().getDecorView().setSystemUiVisibility(immersive?5894:0);
    }
    LinearLayout screen(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackgroundColor(Color.BLACK);l.setPadding(dp(16),dp(8),dp(16),dp(8));
        l.setOnApplyWindowInsetsListener((v,in)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets s=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(18)+s.left,dp(12)+s.top,dp(18)+s.right,dp(12)+s.bottom);}else v.setPadding(dp(18),dp(12)+in.getSystemWindowInsetTop(),dp(18),dp(12)+in.getSystemWindowInsetBottom());return in;});return l;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    TextView title(String s){TextView t=text(s,27,Color.WHITE);t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));t.setLetterSpacing(-0.025f);t.setPadding(0,dp(10),0,dp(10));return t;}
    TextView action(String label,Runnable callback){TextView t=text(label,14,Color.WHITE);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(10),dp(12),dp(10));t.setMinHeight(dp(48));t.setClickable(true);t.setFocusable(true);t.setOnClickListener(v->callback.run());android.util.TypedValue a=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,a,true);t.setBackgroundResource(a.resourceId);return t;}
    LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    android.graphics.drawable.GradientDrawable rounded(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    void hideKeyboard(){if(search!=null){search.clearFocus();android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(im!=null)im.hideSoftInputFromWindow(search.getWindowToken(),0);}}
    TextView iconAction(String symbol,String description,Runnable run){TextView v=action(symbol,run);v.setTextSize(23);v.setContentDescription(description);v.setPadding(dp(8),0,dp(8),0);v.setMinWidth(dp(48));return v;}
    void line(LinearLayout l){View v=new View(this);v.setBackgroundColor(0xff292929);l.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    void flex(LinearLayout l,View v){l.addView(v,new LinearLayout.LayoutParams(0,-2,1));}
    void buildReader(){
        reading=new AxisList(this);reading.setBackgroundColor(Color.BLACK);reading.setOverScrollMode(View.OVER_SCROLL_NEVER);
        reading.setItemAnimator(null);reading.setHasFixedSize(false);reading.setItemViewCacheSize(4);reading.getRecycledViewPool().setMaxRecycledViews(0,4);
        layout=new LinearLayoutManager(this);layout.setInitialPrefetchItemCount(4);reading.setLayoutManager(layout);pageAdapter=new PageAdapter();reading.setAdapter(pageAdapter);
        reader.addView(reading,new FrameLayout.LayoutParams(-1,-1));
        status=text("KOMA\n\nChoose your manga folder",18,0xffcccccc);status.setGravity(Gravity.CENTER);status.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));status.setLineSpacing(dp(4),1.1f);status.setOnClickListener(v->{if(library.root().isEmpty()||!library.permission(library.root(),false))pickFolder();else pager.setCurrentItem(2,true);});reader.addView(status,new FrameLayout.LayoutParams(-1,-1));
        top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(12),dp(8),dp(12),dp(8));top.setBackgroundColor(0xf2000000);
        heading=text("Koma",14,Color.WHITE);heading.setMaxLines(2);heading.setGravity(Gravity.CENTER);heading.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout nav=horizontal();nav.addView(iconAction("◷","History",()->pager.setCurrentItem(0,true)));flex(nav,heading);nav.addView(iconAction("▦","Library",()->pager.setCurrentItem(2,true)));top.addView(nav);
        reader.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(12),dp(4),dp(12),dp(8));bottom.setBackgroundColor(0xf2000000);
        scrubber=new SeekBar(this);scrubber.setMax(0);scrubber.setContentDescription("Page scrubber");bottom.addView(scrubber,new LinearLayout.LayoutParams(-1,dp(46)));
        LinearLayout tools=horizontal();flex(tools,iconAction("‹","Previous chapter",()->chapterDelta(-1)));counter=action("0 / 0",this::pageDialog);flex(tools,counter);flex(tools,iconAction("›","Next chapter",()->chapterDelta(1)));bottom.addView(tools);
        reader.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);
        scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){scrubbing=true;pager.setUserInputEnabled(false);reading.stopScroll();}
            public void onProgressChanged(SeekBar s,int p,boolean user){if(user)counter.setText((p+1)+" / "+(s.getMax()+1));}
            public void onStopTrackingTouch(SeekBar s){scrubbing=false;pager.setUserInputEnabled(true);jumpPage(s.getProgress());}
        });
        reading.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrolled(RecyclerView r,int dx,int dy){updateControls();preload();if(!restoring&&!scrubbing&&r.getScrollState()==RecyclerView.SCROLL_STATE_DRAGGING)observe();checkpoint(false);}
            @Override public void onScrollStateChanged(RecyclerView r,int state){if(state==RecyclerView.SCROLL_STATE_IDLE){observe();checkpoint(true);}}
        });
    }
    void toggleControls(){if(pages.isEmpty())return;controls=!controls;animateControls(top,controls);animateControls(bottom,controls);if(controls)updateControls();}
    void animateControls(View v,boolean show){v.animate().cancel();if(show){if(v.getVisibility()!=View.VISIBLE)v.setAlpha(0f);v.setVisibility(View.VISIBLE);v.animate().alpha(1f).setDuration(130).start();}else v.animate().alpha(0f).setDuration(110).withEndAction(()->{if(!controls)v.setVisibility(View.GONE);}).start();}
    void buildHistory(){
        LinearLayout header=horizontal();flex(header,title("History"));header.addView(iconAction("›","Back to reader",()->pager.setCurrentItem(1,true)));history.addView(header);
        TextView subtitle=text("Continue reading",13,0xff999999);subtitle.setPadding(0,0,0,dp(14));history.addView(subtitle);
        historyRows=new AxisList(this);historyRows.setLayoutManager(new LinearLayoutManager(this));historyRows.setItemAnimator(null);historyRows.setOverScrollMode(View.OVER_SCROLL_NEVER);historyAdapter=new RowAdapter();historyRows.setAdapter(historyAdapter);history.addView(historyRows,new LinearLayout.LayoutParams(-1,0,1));
    }
    void buildManager(){
        LinearLayout header=horizontal();backButton=iconAction("‹","Back",()->{if(browsing!=null){browsing=null;search.setText("");renderLibrary();}else pager.setCurrentItem(1,true);});header.addView(backButton);
        libraryTitle=title("Library");libraryTitle.setSingleLine(true);libraryTitle.setEllipsize(TextUtils.TruncateAt.END);flex(header,libraryTitle);
        header.addView(iconAction("↻","Refresh library",this::refresh));header.addView(iconAction("▱","Change folder",this::pickFolder));manager.addView(header);
        LinearLayout searchBox=horizontal();searchBox.setBackground(rounded(0xff141414,12));TextView glyph=text("⌕",25,0xffaaaaaa);glyph.setGravity(Gravity.CENTER);searchBox.addView(glyph,new LinearLayout.LayoutParams(dp(38),dp(46)));
        search=new EditText(this);search.setSingleLine(true);search.setHint("Search manga or chapters");search.setTextColor(Color.WHITE);search.setHintTextColor(0xff888888);search.setTextSize(15);search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(0,0,dp(4),0);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(46),1));TextView clear=iconAction("×","Clear search",()->search.setText(""));searchBox.addView(clear,new LinearLayout.LayoutParams(dp(44),dp(46)));manager.addView(searchBox,new LinearLayout.LayoutParams(-1,dp(46)));
        search.setOnEditorActionListener((v,a,e)->{hideKeyboard();return true;});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){renderLibrary();}public void afterTextChanged(Editable e){}});
        LinearLayout meta=horizontal();libraryMessage=text("",12,0xff999999);flex(meta,libraryMessage);sortButton=action(sortNames[sortMode]+"  ▾",this::showSort);sortButton.setTextSize(12);meta.addView(sortButton);manager.addView(meta);
        libraryRows=new AxisList(this);libraryRows.setOverScrollMode(View.OVER_SCROLL_NEVER);libraryGrid=new GridLayoutManager(this,2);libraryRows.setLayoutManager(libraryGrid);libraryRows.setItemAnimator(null);libraryRows.setClipToPadding(false);libraryAdapter=new RowAdapter();libraryRows.setAdapter(libraryAdapter);
        libraryGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup(){@Override public int getSpanSize(int p){return libraryAdapter.getItemViewType(p)==1?1:libraryGrid.getSpanCount();}});
        libraryRows.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int spans=Math.max(2,(r-l)/dp(134));if(spans!=libraryGrid.getSpanCount())libraryGrid.setSpanCount(spans);});
        manager.addView(libraryRows,new LinearLayout.LayoutParams(-1,0,1));manager.setFocusableInTouchMode(true);manager.requestFocus();
    }
    void showSort(){
        PopupMenu menu=new PopupMenu(this,sortButton);for(int i=0;i<sortNames.length;i++)menu.getMenu().add(0,i,i,sortNames[i]);menu.setOnMenuItemClickListener(item->{sortMode=item.getItemId();library.prefs.edit().putInt("library-sort",sortMode).apply();renderLibrary();return true;});menu.show();
    }
    static class Row{
        String name,detail,button;Runnable run;Library.Manga manga;int type,progress;
        Row(String n,String d,String b,Runnable r){name=n;detail=d;button=b;run=r;}
        Row cover(Library.Manga m,int t,int p){manga=m;type=t;progress=p;return this;}
    }
    class RatioImage extends ImageView{
        RatioImage(){super(MainActivity.this);setScaleType(ScaleType.CENTER_CROP);setBackground(rounded(0xff171717,6));setClipToOutline(true);}
        @Override protected void onMeasure(int w,int h){int width=View.MeasureSpec.getSize(w);setMeasuredDimension(width,Math.round(width*1.43f));}
    }
    class RowHolder extends RecyclerView.ViewHolder {
        TextView name,detail,button;LinearLayout root;ImageView cover;ProgressBar progress;Future<?> job;int token;Row row;
        RowHolder(LinearLayout r,TextView n,TextView d,TextView b){super(r);root=r;name=n;detail=d;button=b;}
        void cancelCover(){token++;if(job!=null){job.cancel(true);job=null;}coverWorker.purge();}
        void loadCover(){
            if(cover==null||row==null||row.manga==null||row.manga.chapters.isEmpty())return;
            Library.Chapter ch=row.manga.chapters.get(0);Bitmap cached=covers.cached(ch);if(cached!=null){cover.setImageBitmap(cached);return;}
            cancelCover();int request=token;
            job=coverWorker.submit(()->{try{Bitmap b=covers.cover(ch);if(b!=null)main.post(()->{if(!dead&&request==token){cover.setImageBitmap(b);}});}catch(Exception|OutOfMemoryError ignored){}});
        }
    }
    class RowAdapter extends RecyclerView.Adapter<RowHolder>{
        List<Row> rows=new ArrayList<>();
        void set(List<Row> r){rows=r;notifyDataSetChanged();}
        @Override public int getItemCount(){return rows.size();}
        @Override public int getItemViewType(int p){return rows.get(p).type;}
        @Override public RowHolder onCreateViewHolder(ViewGroup p,int type){
            LinearLayout outer=new LinearLayout(MainActivity.this);outer.setOrientation(LinearLayout.VERTICAL);outer.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));
            if(type==1){
                outer.setPadding(dp(4),0,dp(4),dp(16));RatioImage cover=new RatioImage();outer.addView(cover,new LinearLayout.LayoutParams(-1,-2));
                TextView n=text("",14,Color.WHITE);n.setMaxLines(2);n.setMinLines(2);n.setEllipsize(TextUtils.TruncateAt.END);n.setGravity(Gravity.TOP);n.setPadding(0,dp(7),0,0);outer.addView(n,new LinearLayout.LayoutParams(-1,-2));
                TextView d=text("",12,0xff999999);d.setSingleLine(true);outer.addView(d);RowHolder holder=new RowHolder(outer,n,d,null);holder.cover=cover;return holder;
            }
            LinearLayout row=horizontal();row.setMinimumHeight(dp(type==2?104:64));row.setPadding(0,dp(7),0,dp(7));
            ImageView cover=null;if(type==2){cover=new ImageView(MainActivity.this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(rounded(0xff171717,5));cover.setClipToOutline(true);row.addView(cover,new LinearLayout.LayoutParams(dp(54),dp(78)));}
            LinearLayout labels=new LinearLayout(MainActivity.this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(type==2?12:2),dp(7),dp(4),dp(7));
            TextView n=text("",15,Color.WHITE),d=text("",12,0xff999999);n.setMaxLines(2);n.setEllipsize(TextUtils.TruncateAt.END);d.setMaxLines(2);labels.addView(n);labels.addView(d);flex(row,labels);
            ProgressBar progress=null;if(type==2){progress=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffdddddd));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(3));lp.topMargin=dp(7);labels.addView(progress,lp);}
            TextView b=action("",()->{});b.setTextSize(12);row.addView(b);outer.addView(row);line(outer);RowHolder holder=new RowHolder(outer,n,d,b);holder.cover=cover;holder.progress=progress;return holder;
        }
        @Override public void onBindViewHolder(RowHolder h,int p){h.cancelCover();Row r=rows.get(p);h.row=r;h.name.setText(r.name);h.detail.setText(r.detail);if(h.button!=null){h.button.setText(r.button);h.button.setVisibility(r.button.isEmpty()?View.GONE:View.VISIBLE);h.button.setOnClickListener(v->r.run.run());}if(h.progress!=null)h.progress.setProgress(r.progress);if(h.cover!=null){h.cover.setImageDrawable(null);h.loadCover();}h.root.setOnClickListener(v->r.run.run());}
        @Override public void onViewDetachedFromWindow(RowHolder h){h.cancelCover();super.onViewDetachedFromWindow(h);}
        @Override public void onViewAttachedToWindow(RowHolder h){super.onViewAttachedToWindow(h);if(h.cover!=null&&h.cover.getDrawable()==null&&h.job==null)h.loadCover();}
        @Override public void onViewRecycled(RowHolder h){h.cancelCover();h.row=null;if(h.cover!=null)h.cover.setImageDrawable(null);super.onViewRecycled(h);}
    }
    void renderLibrary(){
        if(libraryAdapter==null)return;String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);List<Row> rows=new ArrayList<>();
        if(browsing!=null){Library.Manga m=browsing;libraryTitle.setText(m.name);sortButton.setVisibility(View.GONE);Library.Position position=library.position(m);
            if(position!=null&&q.isEmpty())rows.add(new Row("Continue reading",position.name+" · Page "+(position.page+1),"Resume",()->{hideKeyboard();resume(m,true);}));
            int count=0;for(int i=0;i<m.chapters.size();i++){int index=i;Library.Chapter c=m.chapters.get(i);if(c.name.toLowerCase(Locale.ROOT).contains(q)){count++;rows.add(new Row(c.name.replaceFirst("(?i)\\.cbz$",""),"Chapter "+(i+1),"›",()->{hideKeyboard();open(m,index,0,0,true);}));}}
            libraryMessage.setText(count+" chapters");
        }else{
            libraryTitle.setText("Library");sortButton.setVisibility(View.VISIBLE);sortButton.setText(sortNames[sortMode]+"  ▾");
            List<Library.Manga> sorted=new ArrayList<>(mangas);Map<String,Long> times=new HashMap<>();
            if(sortMode!=0)for(Library.Manga m:sorted){Library.Position p=library.position(m);times.put(m.uri,sortMode==1?(p==null?0L:p.time):library.added(m));}
            sorted.sort((a,b)->{int n=sortMode==0?0:Long.compare(times.get(b.uri),times.get(a.uri));return n!=0?n:Rules.natural(a.name,b.name);});
            for(Library.Manga m:sorted)if(m.name.toLowerCase(Locale.ROOT).contains(q)||m.chapters.stream().anyMatch(c->c.name.toLowerCase(Locale.ROOT).contains(q))){
                rows.add(new Row(m.name,m.chapters.size()+" chapters","",()->{hideKeyboard();browsing=m;search.setText("");renderLibrary();libraryRows.scrollToPosition(0);}).cover(m,1,0));
            }
            if(!scanning)libraryMessage.setText(library.root().isEmpty()?"Choose a folder to begin":rows.size()+" manga");
        }
        libraryAdapter.set(rows);if(rows.isEmpty()&&!scanning&&!q.isEmpty())libraryMessage.setText("No matches");
    }
    void renderHistory(){
        if(historyAdapter==null)return;List<Library.Manga> list=new ArrayList<>(mangas);list.removeIf(m->library.position(m)==null);list.sort((a,b)->Long.compare(library.position(b).time,library.position(a).time));List<Row> rows=new ArrayList<>();
        for(Library.Manga m:list){Library.Position p=library.position(m);rows.add(new Row(m.name,p.name.replaceFirst("(?i)\\.cbz$","")+" · "+(p.page+1)+" / "+p.total,"Resume",()->resume(m,true)).cover(m,2,Math.min(100,Math.round(100f*(p.page+1)/Math.max(1,p.total)))));}
        if(rows.isEmpty())rows.add(new Row("No reading history","Open a manga from your library","Library",()->pager.setCurrentItem(2,true)));historyAdapter.set(rows);
    }
    void pickFolder(){
        if(pickerOpen)return;checkpoint(true);pickerOpen=true;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try{startActivityForResult(i,PICK);}catch(ActivityNotFoundException e){pickerOpen=false;toast("Folder picker unavailable");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=PICK)return;pickerOpen=false;if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{if((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)throw new SecurityException("Read permission not granted");
            if((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);if(!library.permission(uri.toString(),false))throw new SecurityException();
            checkpoint(true);clearReader();library.prefs.edit().putString("root",uri.toString()).remove("active").apply();mangas=library.cached(uri.toString());browsing=null;renderLibrary();renderHistory();refresh();pager.setCurrentItem(1,false);
        }catch(Exception e){toast("Could not keep folder access. Choose another folder.");}
    }
    void refresh(){
        String root=library.root();if(root.isEmpty()||!library.permission(root,false)){clearReader();mangas=new ArrayList<>();browsing=null;renderLibrary();renderHistory();libraryMessage.setText("Folder access unavailable");pickFolder();return;}
        int token=++scanGeneration;if(scanJob!=null)scanJob.cancel(true);scanning=true;libraryMessage.setText("Scanning folders…");
        scanJob=scanner.submit(()->{try{List<Library.Manga> result=library.scan(root);if(Thread.currentThread().isInterrupted())return;library.cache(root,result);
            main.post(()->{if(dead||token!=scanGeneration||!root.equals(library.root()))return;scanning=false;mangas=result;if(browsing!=null){String id=browsing.uri;browsing=find(id);}renderLibrary();renderHistory();if(selected==null)restoreActive();if(mangas.isEmpty())libraryMessage.setText("No CBZ chapters found");});
        }catch(Exception e){main.post(()->{if(dead||token!=scanGeneration)return;scanning=false;libraryMessage.setText("Cannot scan folder. Refresh or change folder.");if(!library.permission(root,false)){clearReader();mangas=new ArrayList<>();browsing=null;renderLibrary();renderHistory();}});}});
    }
    Library.Manga find(String id){for(Library.Manga m:mangas)if(m.uri.equals(id))return m;return null;}
    void restoreActive(){Library.Manga m=find(library.prefs.getString("active",""));if(m!=null)resume(m,false);else if(pages.isEmpty()&&!loading)status.setText("KOMA\n\nSwipe left to open your library");}
    void resume(Library.Manga m,boolean navigate){Library.Position p=library.position(m);if(p!=null)for(int i=0;i<m.chapters.size();i++)if(m.chapters.get(i).uri.equals(p.chapter)){open(m,i,p.page,p.offset,navigate);return;}open(m,0,0,0,navigate);}
    void clearReader(){
        generation++;if(indexJob!=null)indexJob.cancel(true);loading=false;restoring=false;selected=null;lastLoaded=-1;failedNext=-1;reading.stopScroll();int old=pages.size();pages.clear();coverage.clear();pageAdapter.notifyItemRangeRemoved(0,old);status.setVisibility(View.VISIBLE);status.setText("KOMA\n\nChoose a manga");controls=false;top.animate().cancel();bottom.animate().cancel();top.setAlpha(1f);bottom.setAlpha(1f);top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);lastControlKey="";lastControlPage=-1;lastSavedKey="";lastSavedOffset=Integer.MIN_VALUE;
    }
    void open(Library.Manga m,int chapter,int page,int offset,boolean navigate){
        checkpoint(true);clearReader();selected=m;lastLoaded=chapter-1;loading=true;restoring=true;status.setText("Opening "+m.chapters.get(chapter).name+"…");if(navigate)pager.setCurrentItem(1,true);
        load(chapter,true,page,offset);
    }
    void load(int chapter,boolean first,int page,int offset){
        Library.Manga m=selected;if(m==null||chapter<0||chapter>=m.chapters.size())return;int token=generation;loading=true;
        indexJob=indexer.submit(()->{
            try{List<Archives.Page> result=archives.index(m.chapters.get(chapter));int[] masks=library.coverage(result.get(0).chapter.uri+"|"+result.get(0).hash,result.size());
                main.post(()->{if(dead||token!=generation)return;int start=pages.size();pages.addAll(result);coverage.put(result.get(0).chapter.uri+"|"+result.get(0).hash,masks);lastLoaded=chapter;loading=false;pageAdapter.notifyItemRangeInserted(start,result.size());status.setVisibility(View.GONE);
                    if(first){layout.scrollToPositionWithOffset(start+Math.min(Math.max(page,0),result.size()-1),offset);reading.post(()->{if(token==generation){restoring=false;ignoreCoverageUntil=SystemClock.uptimeMillis()+500;updateControls();preload();checkpoint(true);}});}else updateControls();
                });
            }catch(Exception e){main.post(()->{if(dead||token!=generation)return;loading=false;restoring=false;if(first){status.setVisibility(View.VISIBLE);status.setText("Cannot open chapter\n\nTap to return to library");}else {failedNext=chapter;toast("Next chapter unavailable. Use Next to retry.");}});}
        });
    }
    Archives.Page current(){int p=layout.findFirstVisibleItemPosition();return p>=0&&p<pages.size()?pages.get(p):null;}
    int chapterIndex(Archives.Page p){if(p!=null&&selected!=null)for(int i=0;i<selected.chapters.size();i++)if(selected.chapters.get(i).uri.equals(p.chapter.uri))return i;return -1;}
    void preload(){if(loading||selected==null||pages.isEmpty()||failedNext==lastLoaded+1||lastLoaded>=selected.chapters.size()-1)return;int end=layout.findLastVisibleItemPosition();if(end>=pages.size()-9)load(lastLoaded+1,false,0,0);}
    void chapterDelta(int delta){
        Archives.Page p=current();int i=chapterIndex(p);if(i<0)return;int target=i+delta;if(target<0||target>=selected.chapters.size()){toast("No "+(delta<0?"previous":"next")+" chapter");return;}
        Library.Chapter c=selected.chapters.get(target);for(int j=0;j<pages.size();j++)if(pages.get(j).chapter.uri.equals(c.uri)){jumpAbsolute(j);return;}open(selected,target,0,0,true);
    }
    void jumpPage(int page){Archives.Page p=current();if(p==null)return;int first=layout.findFirstVisibleItemPosition()-p.index;jumpAbsolute(first+Math.min(Math.max(page,0),p.total-1));}
    void jumpAbsolute(int pos){ignoreCoverageUntil=SystemClock.uptimeMillis()+650;reading.stopScroll();layout.scrollToPositionWithOffset(pos,0);reading.post(()->{updateControls();checkpoint(true);});}
    void pageDialog(){Archives.Page p=current();if(p==null)return;EditText number=new EditText(this);number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);number.setText(String.valueOf(p.index+1));number.selectAll();new AlertDialog.Builder(this).setTitle("Go to page · "+p.total).setView(number).setNegativeButton("Cancel",null).setPositiveButton("Go",(d,w)->{try{int i=Integer.parseInt(number.getText().toString());if(i<1||i>p.total)throw new NumberFormatException();jumpPage(i-1);}catch(NumberFormatException e){toast("Enter a page from 1 to "+p.total);}}).show();}
    void updateControls(){if(!controls||scrubbing)return;Archives.Page p=current();if(p==null)return;if(!p.chapter.uri.equals(lastControlKey)){heading.setText(selected.name+"\n"+p.chapter.name.replaceFirst("(?i)\\.cbz$",""));scrubber.setMax(p.total-1);lastControlKey=p.chapter.uri;lastControlPage=-1;}if(p.index!=lastControlPage){counter.setText((p.index+1)+" / "+p.total);scrubber.setProgress(p.index);lastControlPage=p.index;}}
    void checkpoint(boolean force){
        if(selected==null||restoring||scrubbing)return;long now=SystemClock.uptimeMillis();if(!force&&now-lastCheckpoint<250)return;
        Archives.Page p=current();if(p==null)return;View v=layout.findViewByPosition(layout.findFirstVisibleItemPosition());if(v==null)return;int offset=v.getTop()-reading.getPaddingTop();String key=selected.uri+"|"+p.chapter.uri+"|"+p.index;if(key.equals(lastSavedKey)&&offset==lastSavedOffset)return;lastCheckpoint=now;lastSavedKey=key;lastSavedOffset=offset;library.save(selected,p.chapter,p.index,offset,p.total);
    }
    void observe(){
        if(selected==null||restoring||scrubbing||pager==null||pager.getCurrentItem()!=1||!hasWindowFocus()||controls||SystemClock.uptimeMillis()<ignoreCoverageUntil||reading.getScrollState()==RecyclerView.SCROLL_STATE_SETTLING)return;
        for(int i=0;i<reading.getChildCount();i++){View v=reading.getChildAt(i);RecyclerView.ViewHolder vh=reading.getChildViewHolder(v);if(!(vh instanceof PageHolder))continue;PageHolder h=(PageHolder)vh;Archives.Page p=h.page;if(p==null||!h.decoded||v.getHeight()<=0)continue;
            int from=Math.max(0,-v.getTop()),to=Math.min(v.getHeight(),reading.getHeight()-v.getTop());if(to<=from)continue;String key=p.chapter.uri+"|"+p.hash;int[] a=coverage.get(key);if(a==null)continue;int n=Rules.coverage(a[p.index],from,to,v.getHeight());if(n!=a[p.index]){a[p.index]=n;library.saveCoverage(key,a);library.certify(p.chapter,p.hash,a);}
        }
    }
    class AxisList extends RecyclerView{
        float x,y;boolean decided;
        AxisList(Context c){super(c);}
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getX();y=e.getY();decided=false;getParent().requestDisallowInterceptTouchEvent(true);}
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!decided){float dx=Math.abs(e.getX()-x),dy=Math.abs(e.getY()-y);int slop=ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();if(Math.max(dx,dy)>slop){decided=true;getParent().requestDisallowInterceptTouchEvent(!(dx>dy*1.35f));}}
            else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
            return super.dispatchTouchEvent(e);
        }
    }
    class PageHolder extends RecyclerView.ViewHolder{
        FrameLayout frame;ImageView image;TextView error;Archives.Page page;Future<?> task;boolean decoded;int bind;
        PageHolder(FrameLayout f,ImageView i,TextView e){super(f);frame=f;image=i;error=e;}
        void cancel(){bind++;if(task!=null){task.cancel(true);decoder.purge();}task=null;decoded=false;page=null;image.setImageDrawable(null);}
    }
    class PageAdapter extends RecyclerView.Adapter<PageHolder>{
        @Override public int getItemCount(){return pages.size();}
        @Override public PageHolder onCreateViewHolder(ViewGroup parent,int t){
            FrameLayout f=new FrameLayout(MainActivity.this);f.setBackgroundColor(Color.BLACK);f.setLayoutParams(new RecyclerView.LayoutParams(-1,dp(300)));
            ImageView i=new ImageView(MainActivity.this);i.setScaleType(ImageView.ScaleType.FIT_CENTER);f.addView(i,new FrameLayout.LayoutParams(-1,-1));TextView e=text("",14,0xff888888);e.setGravity(Gravity.CENTER);f.addView(e,new FrameLayout.LayoutParams(-1,-1));f.setOnClickListener(v->toggleControls());return new PageHolder(f,i,e);
        }
        @Override public void onBindViewHolder(PageHolder h,int pos){
            h.cancel();Archives.Page p=pages.get(pos);h.page=p;int token=h.bind;
            int width=reading.getWidth();if(width<=0)width=getResources().getDisplayMetrics().widthPixels;
            final int target=Math.max(1,width);long height=p.width>0&&p.height>0?Math.round((double)target*p.height/p.width):dp(280);h.frame.getLayoutParams().height=(int)Math.max(1,Math.min(1000000,height));h.frame.requestLayout();h.error.setText("");
            h.task=decoder.submit(()->{try{Bitmap b=archives.decode(p,target);main.post(()->{if(dead||h.bind!=token||h.page!=p)return;h.image.setImageBitmap(b);h.error.setText("");h.decoded=true;});}
                catch(Exception|OutOfMemoryError ex){main.post(()->{if(!dead&&h.bind==token&&h.page==p)h.error.setText("Page unavailable · "+(p.index+1));});}});
        }
        @Override public void onViewRecycled(PageHolder h){h.cancel();super.onViewRecycled(h);}
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&pager!=null)bars(pager.getCurrentItem()==1);}
    @Override protected void onPause(){checkpoint(true);super.onPause();}
    @Override protected void onResume(){super.onResume();if(pager!=null&&!library.root().isEmpty()&&!library.permission(library.root(),false)&&selected!=null){clearReader();mangas=new ArrayList<>();renderLibrary();renderHistory();status.setText("Folder access expired. Tap to select.");}}
    @Override public void onBackPressed(){if(pager.getCurrentItem()==2&&browsing!=null){browsing=null;search.setText("");renderLibrary();}else if(pager.getCurrentItem()!=1)pager.setCurrentItem(1,true);else if(controls)toggleControls();else super.onBackPressed();}
    @Override protected void onDestroy(){checkpoint(true);dead=true;main.removeCallbacksAndMessages(null);scanner.shutdownNow();indexer.shutdownNow();decoder.shutdownNow();coverWorker.shutdownNow();covers.clearMemory();super.onDestroy();}
}
