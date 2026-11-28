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
    final ExecutorService scanner=Executors.newSingleThreadExecutor(), indexer=Executors.newSingleThreadExecutor(), cleanupWorker=Executors.newSingleThreadExecutor();
    final Set<String> cleaning=new HashSet<>();
    final ThreadPoolExecutor decoder=(ThreadPoolExecutor)Executors.newFixedThreadPool(2);
    Library library; Archives archives;
    LinearLayoutManager libraryLayout; TextView sortButton, backButton; int sortMode=0, chapterSort=0;
    String lastSavedKey=""; int lastSavedOffset=Integer.MIN_VALUE; String lastControlKey=""; int lastControlPage=-1;
    final String[] sortNames={"A–Z", "Recently read", "Recently added", "Z–A", "Oldest added", "Most chapters", "Fewest chapters"};
    final String[] chapterSortNames={"Chapter ↑", "Chapter ↓"};
    List<Library.Manga> mangas=new ArrayList<>();
    Library.Manga selected, browsing;
    final List<Archives.Page> pages=new ArrayList<>();
    final Map<String,int[]> coverage=new HashMap<>();
    final Map<String,Seen> observed=new HashMap<>();
    static class Seen {final int mask;final long time;Seen(int m,long t){mask=m;time=t;}}
    ViewPager2 pager; FrameLayout reader; LinearLayout history,manager,top,bottom;
    TextView status, stateDetail, stateButton,heading,chapterHeading,counter,libraryTitle,libraryMessage; LinearLayout readerState; TextView clearSearch;
    EditText search; SeekBar scrubber; RecyclerView libraryRows,historyRows; AxisList reading;
    LinearLayoutManager layout; PageAdapter pageAdapter;
    RowAdapter libraryAdapter, historyAdapter;
    boolean controls=false,loading=false,scanning=false,restoring=false,dead=false,pickerOpen=false,scrubbing=false;
    int generation=0, scanGeneration=0, lastLoaded=-1, failedNext=-1;
    long lastCheckpoint=0,ignoreCoverageUntil=0;
    Future<?> indexJob, scanJob;
    static final int PICK=47;
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    final Runnable heartbeat=new Runnable(){public void run(){if(dead)return;observe();if(pager!=null&&pager.getCurrentItem()==1&&hasWindowFocus()&&!scrubbing&&!restoring)checkpoint(false);main.postDelayed(this,350);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);library=new Library(this);archives=new Archives(this);sortMode=Math.max(0,Math.min(sortNames.length-1,library.prefs.getInt("library-sort",0)));chapterSort=Math.max(0,Math.min(1,library.prefs.getInt("chapter-sort",0)));
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
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback(){@Override public void onPageSelected(int p){if(p!=1){checkpoint(true);finishSelectedIfEligible();if(p==0)renderHistory();if(p==2)renderLibrary();}else {hideKeyboard();}bars(p==1);}});
        bars(true);main.post(heartbeat);
        String root=library.root();
        if(root.isEmpty()||!library.permission(root,false)){showReaderState("KOMA","Choose your manga folder","Select Folder");main.postDelayed(()->{if(!dead)pickFolder();},350);}
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
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);t.setFontFeatureSettings("kern");t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    TextView title(String s){TextView t=text(s,28,Color.WHITE);t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));t.setLetterSpacing(-0.02f);t.setPadding(0,dp(10),0,dp(10));return t;}
    TextView action(String label,Runnable callback){TextView t=text(label,14,Color.WHITE);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(10),dp(12),dp(10));t.setMinHeight(dp(48));t.setClickable(true);t.setFocusable(true);t.setOnClickListener(v->callback.run());android.util.TypedValue a=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,a,true);t.setBackgroundResource(a.resourceId);return t;}
    LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    android.graphics.drawable.GradientDrawable rounded(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    void hideKeyboard(){if(search!=null){search.clearFocus();android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(im!=null)im.hideSoftInputFromWindow(search.getWindowToken(),0);}}
    TextView iconAction(String symbol,String description,Runnable run){TextView v=action(symbol,run);v.setTextSize(23);v.setContentDescription(description);v.setPadding(0,0,0,0);v.setMinWidth(dp(44));v.setMinimumWidth(dp(44));return v;}
    TextView outlined(String label,Runnable run){TextView v=action(label,run);android.graphics.drawable.GradientDrawable bg=rounded(0xff0d0d0d,8);bg.setStroke(dp(1),0xff343434);v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22ffffff),bg,null));return v;}
    void showReaderState(String title,String detail,String button){status.setText(title);stateDetail.setText(detail);stateDetail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);stateButton.setText(button);stateButton.setVisibility(button.isEmpty()?View.GONE:View.VISIBLE);readerState.setVisibility(View.VISIBLE);}
    void readyState(){showReaderState("Ready to Read","Swipe left to pick a manga from your library.","Open library");}
    void line(LinearLayout l){View v=new View(this);v.setBackgroundColor(0xff242424);l.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    void flex(LinearLayout l,View v){l.addView(v,new LinearLayout.LayoutParams(0,-2,1));}
    void buildReader(){
        reading=new AxisList(this);reading.setBackgroundColor(Color.BLACK);reading.setOverScrollMode(View.OVER_SCROLL_NEVER);
        reading.setItemAnimator(null);reading.setHasFixedSize(false);reading.setItemViewCacheSize(4);reading.getRecycledViewPool().setMaxRecycledViews(0,4);
        layout=new LinearLayoutManager(this);layout.setInitialPrefetchItemCount(4);reading.setLayoutManager(layout);pageAdapter=new PageAdapter();reading.setAdapter(pageAdapter);
        reader.addView(reading,new FrameLayout.LayoutParams(-1,-1));
        readerState=new LinearLayout(this);readerState.setOrientation(LinearLayout.VERTICAL);readerState.setGravity(Gravity.CENTER);readerState.setPadding(dp(28),dp(24),dp(28),dp(24));
        status=text("Ready to Read",26,Color.WHITE);status.setGravity(Gravity.CENTER);status.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));readerState.addView(status,new LinearLayout.LayoutParams(-1,-2));
        stateDetail=text("",14,0xffb8b8b8);stateDetail.setGravity(Gravity.CENTER);stateDetail.setPadding(0,dp(10),0,dp(22));readerState.addView(stateDetail,new LinearLayout.LayoutParams(-1,-2));
        stateButton=outlined("Open library",()->{if(library.root().isEmpty()||!library.permission(library.root(),false))pickFolder();else pager.setCurrentItem(2,true);});readerState.addView(stateButton,new LinearLayout.LayoutParams(-2,-2));
        reader.addView(readerState,new FrameLayout.LayoutParams(-1,-1));readyState();
        top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(4),dp(4),dp(4),dp(4));top.setBackgroundColor(0xf50d0d0d);
        heading=text("Koma",14,Color.WHITE);heading.setSingleLine(true);heading.setGravity(Gravity.CENTER);heading.setEllipsize(TextUtils.TruncateAt.END);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        chapterHeading=text("",11,0xffb8b8b8);chapterHeading.setGravity(Gravity.CENTER);chapterHeading.setSingleLine(true);chapterHeading.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.addView(heading);labels.addView(chapterHeading);
        LinearLayout nav=horizontal();nav.addView(iconAction("◷","History",()->pager.setCurrentItem(0,true)));nav.addView(iconAction("‹","Previous chapter",()->chapterDelta(-1)));flex(nav,labels);nav.addView(iconAction("›","Next chapter",()->chapterDelta(1)));nav.addView(iconAction("▤","Library",()->pager.setCurrentItem(2,true)));top.addView(nav);
        reader.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(8),0,dp(8),dp(6));bottom.setBackgroundColor(0xf50d0d0d);
        counter=action("0 / 0",this::pageDialog);counter.setTypeface(Typeface.MONOSPACE);counter.setMinHeight(dp(36));counter.setPadding(0,dp(4),0,dp(4));counter.setTextSize(12);bottom.addView(counter,new LinearLayout.LayoutParams(-1,dp(36)));
        scrubber=new SeekBar(this);scrubber.setMax(0);scrubber.setContentDescription("Page scrubber");scrubber.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffdddddd));scrubber.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        LinearLayout tools=horizontal();tools.addView(iconAction("‹","Previous page",()->pageDelta(-1)),new LinearLayout.LayoutParams(dp(44),dp(48)));tools.addView(scrubber,new LinearLayout.LayoutParams(0,dp(48),1));tools.addView(iconAction("›","Next page",()->pageDelta(1)),new LinearLayout.LayoutParams(dp(44),dp(48)));bottom.addView(tools);
        reader.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);
        scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){scrubbing=true;pager.setUserInputEnabled(false);reading.stopScroll();}
            public void onProgressChanged(SeekBar s,int p,boolean user){if(user)counter.setText((p+1)+" / "+(s.getMax()+1));}
            public void onStopTrackingTouch(SeekBar s){scrubbing=false;pager.setUserInputEnabled(true);jumpPage(s.getProgress());}
        });
        reading.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrolled(RecyclerView r,int dx,int dy){updateControls();preload();checkpoint(false);}
            @Override public void onScrollStateChanged(RecyclerView r,int state){if(state==RecyclerView.SCROLL_STATE_IDLE){checkpoint(true);}else if(state==RecyclerView.SCROLL_STATE_SETTLING)observed.clear();}
        });
    }
    void toggleControls(){if(pages.isEmpty())return;controls=!controls;animateControls(top,controls);animateControls(bottom,controls);if(controls)updateControls();}
    void animateControls(View v,boolean show){v.animate().cancel();if(show){if(v.getVisibility()!=View.VISIBLE)v.setAlpha(0f);v.setVisibility(View.VISIBLE);v.animate().alpha(1f).setDuration(130).start();}else v.animate().alpha(0f).setDuration(110).withEndAction(()->{if(!controls)v.setVisibility(View.GONE);}).start();}
    void buildHistory(){
        LinearLayout header=horizontal();flex(header,title("History"));header.addView(iconAction("›","Back to reader",()->pager.setCurrentItem(1,true)));history.addView(header);
        line(history);
        historyRows=new AxisList(this);historyRows.setLayoutManager(new LinearLayoutManager(this));historyRows.setItemAnimator(null);historyRows.setOverScrollMode(View.OVER_SCROLL_NEVER);historyAdapter=new RowAdapter();historyRows.setAdapter(historyAdapter);history.addView(historyRows,new LinearLayout.LayoutParams(-1,0,1));
    }
    void buildManager(){
        LinearLayout header=horizontal();backButton=iconAction("‹","Back",()->{if(browsing!=null){browsing=null;search.setText("");renderLibrary();}else pager.setCurrentItem(1,true);});header.addView(backButton);
        libraryTitle=title("Library");libraryTitle.setSingleLine(true);libraryTitle.setEllipsize(TextUtils.TruncateAt.END);flex(header,libraryTitle);
        header.addView(iconAction("↻","Refresh library",this::refresh));TextView change=outlined("Change Folder",this::pickFolder);change.setTextSize(12);change.setPadding(dp(9),0,dp(9),0);header.addView(change);manager.addView(header);
        LinearLayout searchBox=horizontal();android.graphics.drawable.GradientDrawable searchBg=rounded(0xff0d0d0d,8);searchBg.setStroke(dp(1),0xff242424);searchBox.setBackground(searchBg);TextView glyph=text("⌕",25,0xffaaaaaa);glyph.setGravity(Gravity.CENTER);searchBox.addView(glyph,new LinearLayout.LayoutParams(dp(38),dp(46)));
        search=new EditText(this);search.setSingleLine(true);search.setHint("Search manga");search.setTextColor(Color.WHITE);search.setHintTextColor(0xff888888);search.setTextSize(15);search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(0,0,dp(4),0);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(46),1));clearSearch=iconAction("×","Clear search",()->search.setText(""));clearSearch.setVisibility(View.INVISIBLE);searchBox.addView(clearSearch,new LinearLayout.LayoutParams(dp(44),dp(46)));manager.addView(searchBox,new LinearLayout.LayoutParams(-1,dp(46)));
        search.setOnEditorActionListener((v,a,e)->{hideKeyboard();return true;});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){clearSearch.setVisibility(s.length()==0?View.INVISIBLE:View.VISIBLE);renderLibrary();}public void afterTextChanged(Editable e){}});
        LinearLayout meta=horizontal();libraryMessage=text("",12,0xff999999);flex(meta,libraryMessage);sortButton=outlined("Sort: "+sortNames[sortMode]+"  ▾",this::showSort);sortButton.setContentDescription("Sort options");sortButton.setTextSize(12);meta.addView(sortButton);manager.addView(meta);
        libraryRows=new AxisList(this);libraryRows.setOverScrollMode(View.OVER_SCROLL_NEVER);libraryLayout=new LinearLayoutManager(this);libraryRows.setLayoutManager(libraryLayout);libraryRows.setItemAnimator(null);libraryRows.setClipToPadding(false);libraryAdapter=new RowAdapter();libraryRows.setAdapter(libraryAdapter);
        manager.addView(libraryRows,new LinearLayout.LayoutParams(-1,0,1));manager.setFocusableInTouchMode(true);manager.requestFocus();
    }
    void showSort(){
        boolean chapters=browsing!=null;String[] options=chapters?chapterSortNames:sortNames;PopupMenu menu=new PopupMenu(this,sortButton);for(int i=0;i<options.length;i++)menu.getMenu().add(0,i,i,options[i]);menu.getMenu().setGroupCheckable(0,true,true);menu.getMenu().getItem(chapters?chapterSort:sortMode).setChecked(true);menu.setOnMenuItemClickListener(item->{int mode=item.getItemId();if(chapters){chapterSort=mode;library.prefs.edit().putInt("chapter-sort",mode).apply();}else{sortMode=mode;library.prefs.edit().putInt("library-sort",mode).apply();}renderLibrary();libraryRows.scrollToPosition(0);return true;});menu.show();
    }
    static class Row{
        final String name,detail,button;final Runnable run;
        Row(String n,String d,String b,Runnable r){name=n;detail=d;button=b;run=r;}
    }
    class RowHolder extends RecyclerView.ViewHolder {
        TextView name,detail,button;LinearLayout root;
        RowHolder(LinearLayout r,TextView n,TextView d,TextView b){super(r);root=r;name=n;detail=d;button=b;}
    }
    class RowAdapter extends RecyclerView.Adapter<RowHolder>{
        List<Row> rows=new ArrayList<>();
        void set(List<Row> r){rows=r;notifyDataSetChanged();}
        @Override public int getItemCount(){return rows.size();}
        @Override public RowHolder onCreateViewHolder(ViewGroup p,int type){
            LinearLayout outer=new LinearLayout(MainActivity.this);outer.setOrientation(LinearLayout.VERTICAL);outer.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));
            LinearLayout row=horizontal();row.setMinimumHeight(dp(66));row.setPadding(0,dp(7),0,dp(7));
            LinearLayout labels=new LinearLayout(MainActivity.this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(4),dp(5),dp(12),dp(5));
            TextView n=text("",16,Color.WHITE),d=text("",12,0xffb8b8b8);n.setMaxLines(2);n.setEllipsize(TextUtils.TruncateAt.END);d.setMaxLines(2);d.setPadding(0,dp(3),0,0);labels.addView(n);labels.addView(d);flex(row,labels);
            TextView b=outlined("",()->{});b.setTextSize(13);b.setMinHeight(dp(44));row.addView(b);outer.addView(row);line(outer);return new RowHolder(outer,n,d,b);
        }
        @Override public void onBindViewHolder(RowHolder h,int p){
            Row r=rows.get(p);h.name.setText(r.name);h.detail.setText(r.detail);h.detail.setVisibility(r.detail.isEmpty()?View.GONE:View.VISIBLE);h.button.setText(r.button);h.button.setVisibility(r.button.isEmpty()?View.GONE:View.VISIBLE);
            if("›".equals(r.button)){h.button.setBackgroundColor(Color.TRANSPARENT);h.button.setTextSize(23);}else{android.graphics.drawable.GradientDrawable bg=rounded(0xff0d0d0d,8);bg.setStroke(dp(1),0xff343434);h.button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22ffffff),bg,null));h.button.setTextSize(13);}
            h.button.setOnClickListener(v->r.run.run());h.root.setOnClickListener(v->r.run.run());
        }
    }
    void renderLibrary(){
        if(libraryAdapter==null)return;String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);List<Row> rows=new ArrayList<>();
        if(browsing!=null){Library.Manga m=browsing;libraryTitle.setText(m.name);search.setHint("Search chapters");sortButton.setVisibility(View.VISIBLE);sortButton.setText("Sort: "+chapterSortNames[chapterSort]+"  ▾");Library.Position position=library.position(m);
            if(position!=null&&q.isEmpty())rows.add(new Row("Continue reading",position.name+" · Page "+(position.page+1),"Resume",()->{hideKeyboard();resume(m,true);}));
            int count=0;for(int row=0;row<m.chapters.size();row++){int i=chapterSort==0?row:m.chapters.size()-1-row;int index=i;Library.Chapter c=m.chapters.get(i);if(c.name.toLowerCase(Locale.ROOT).contains(q)){count++;rows.add(new Row(c.name.replaceFirst("(?i)\\.cbz$",""),"Chapter "+(i+1)+(library.completed(c)?" · Completed":""),"›",()->{hideKeyboard();open(m,index,0,0,true);}));}}
            libraryMessage.setText(count+" chapters");
        }else{
            libraryTitle.setText("Library");search.setHint("Search manga");sortButton.setVisibility(View.VISIBLE);sortButton.setText("Sort: "+sortNames[sortMode]+"  ▾");
            List<Library.Manga> sorted=new ArrayList<>(mangas);Map<String,Long> reads=new HashMap<>(), added=new HashMap<>();
            for(Library.Manga m:sorted){Library.Position p=library.position(m);reads.put(m.uri,p==null?0L:p.time);added.put(m.uri,library.added(m));}
            sorted.sort((a,b)->Rules.mangaOrder(sortMode,a.name,b.name,reads.get(a.uri),reads.get(b.uri),added.get(a.uri),added.get(b.uri),a.chapters.size(),b.chapters.size()));
            for(Library.Manga m:sorted)if(m.name.toLowerCase(Locale.ROOT).contains(q)||m.chapters.stream().anyMatch(c->c.name.toLowerCase(Locale.ROOT).contains(q))){
                rows.add(new Row(m.name,m.chapters.size()+" chapters"+(cleaning.contains(m.uri)?" · Checking":library.allCertified(m)?" · Completed":""),"›",()->{hideKeyboard();browsing=m;search.setText("");renderLibrary();libraryRows.scrollToPosition(0);}));
            }
            if(!scanning)libraryMessage.setText(library.root().isEmpty()?"Choose a folder to begin":rows.size()+" manga");
        }
        libraryAdapter.set(rows);if(rows.isEmpty()&&!scanning&&!q.isEmpty())libraryMessage.setText("No matches");
    }
    void renderHistory(){
        if(historyAdapter==null)return;List<Library.Manga> list=new ArrayList<>(mangas);list.removeIf(m->library.position(m)==null);list.sort((a,b)->Long.compare(library.position(b).time,library.position(a).time));List<Row> rows=new ArrayList<>();
        for(Library.Manga m:list){Library.Position p=library.position(m);rows.add(new Row(m.name,p.name.replaceFirst("(?i)\\.cbz$","")+" · "+(p.page+1)+" / "+p.total,"Resume",()->resume(m,true)));}
        if(rows.isEmpty())rows.add(new Row("No reading history","Open a manga from your library","Library",()->pager.setCurrentItem(2,true)));historyAdapter.set(rows);
    }
    void pickFolder(){
        if(pickerOpen)return;if(!cleaning.isEmpty()){toast("Checking completed manga…");return;}checkpoint(true);pickerOpen=true;
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
    void restoreActive(){Library.Manga m=find(library.prefs.getString("active",""));if(m!=null)resume(m,false);else if(pages.isEmpty()&&!loading)readyState();}
    void resume(Library.Manga m,boolean navigate){Library.Position p=library.position(m);if(p!=null)for(int i=0;i<m.chapters.size();i++)if(m.chapters.get(i).uri.equals(p.chapter)){open(m,i,p.page,p.offset,navigate);return;}open(m,0,0,0,navigate);}
    void finishSelectedIfEligible(){
        Library.Manga m=selected;String root=library.root();if(m==null||!library.allCertified(m)||!library.permission(root,true)||cleaning.contains(m.uri))return;
        // Leave the current reader before mutating the storage. Block reopening the same manga while verification runs.
        cleaning.add(m.uri);clearReader();cleanupWorker.submit(()->{boolean removed=false;try{removed=library.removeCompletedManga(root,m);}catch(Exception ignored){}boolean deleted=removed;
            main.post(()->{if(dead)return;cleaning.remove(m.uri);if(deleted){if(browsing!=null&&browsing.uri.equals(m.uri))browsing=null;mangas.removeIf(x->x.uri.equals(m.uri));if(root.equals(library.root()))refresh();toast("Completed manga removed");}renderLibrary();renderHistory();});
        });
    }
    void clearReader(){
        generation++;if(indexJob!=null)indexJob.cancel(true);loading=false;restoring=false;selected=null;lastLoaded=-1;failedNext=-1;reading.stopScroll();int old=pages.size();pages.clear();coverage.clear();observed.clear();pageAdapter.notifyItemRangeRemoved(0,old);readyState();controls=false;top.animate().cancel();bottom.animate().cancel();top.setAlpha(1f);bottom.setAlpha(1f);top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);lastControlKey="";lastControlPage=-1;lastSavedKey="";lastSavedOffset=Integer.MIN_VALUE;
    }
    void open(Library.Manga m,int chapter,int page,int offset,boolean navigate){
        if(cleaning.contains(m.uri)){toast("Checking completed manga…");return;}checkpoint(true);if(selected!=null&&!selected.uri.equals(m.uri))finishSelectedIfEligible();clearReader();selected=m;lastLoaded=chapter-1;loading=true;restoring=true;showReaderState("Opening chapter",m.chapters.get(chapter).name,"");if(navigate)pager.setCurrentItem(1,true);
        load(chapter,true,page,offset);
    }
    void load(int chapter,boolean first,int page,int offset){
        Library.Manga m=selected;if(m==null||chapter<0||chapter>=m.chapters.size())return;int token=generation;loading=true;
        indexJob=indexer.submit(()->{
            try{List<Archives.Page> result=archives.index(m.chapters.get(chapter));int[] masks=library.coverage(result.get(0).chapter.uri+"|"+result.get(0).hash,result.size());
                main.post(()->{if(dead||token!=generation)return;int start=pages.size();pages.addAll(result);coverage.put(result.get(0).chapter.uri+"|"+result.get(0).hash,masks);lastLoaded=chapter;loading=false;pageAdapter.notifyItemRangeInserted(start,result.size());readerState.setVisibility(View.GONE);
                    if(first){layout.scrollToPositionWithOffset(start+Math.min(Math.max(page,0),result.size()-1),offset);reading.post(()->{if(token==generation){restoring=false;ignoreCoverageUntil=SystemClock.uptimeMillis()+500;updateControls();preload();checkpoint(true);}});}else updateControls();
                });
            }catch(Exception e){main.post(()->{if(dead||token!=generation)return;loading=false;restoring=false;if(first){showReaderState("Cannot open chapter","The archive may be unavailable or corrupted.","Open library");}else {failedNext=chapter;toast("Next chapter unavailable. Use Next to retry.");}});}
        });
    }
    Archives.Page current(){int p=layout.findFirstVisibleItemPosition();return p>=0&&p<pages.size()?pages.get(p):null;}
    int chapterIndex(Archives.Page p){if(p!=null&&selected!=null)for(int i=0;i<selected.chapters.size();i++)if(selected.chapters.get(i).uri.equals(p.chapter.uri))return i;return -1;}
    void preload(){if(loading||selected==null||pages.isEmpty()||failedNext==lastLoaded+1||lastLoaded>=selected.chapters.size()-1)return;int end=layout.findLastVisibleItemPosition();if(end>=pages.size()-9)load(lastLoaded+1,false,0,0);}
    void chapterDelta(int delta){
        Archives.Page p=current();int i=chapterIndex(p);if(i<0)return;int target=i+delta;if(target<0||target>=selected.chapters.size()){toast("No "+(delta<0?"previous":"next")+" chapter");return;}
        Library.Chapter c=selected.chapters.get(target);for(int j=0;j<pages.size();j++)if(pages.get(j).chapter.uri.equals(c.uri)){jumpAbsolute(j);return;}open(selected,target,0,0,true);
    }
    void pageDelta(int delta){Archives.Page p=current();if(p!=null&&p.index+delta>=0&&p.index+delta<p.total)jumpPage(p.index+delta);}
    void jumpPage(int page){Archives.Page p=current();if(p==null)return;int first=layout.findFirstVisibleItemPosition()-p.index;jumpAbsolute(first+Math.min(Math.max(page,0),p.total-1));}
    void jumpAbsolute(int pos){observed.clear();ignoreCoverageUntil=SystemClock.uptimeMillis()+650;reading.stopScroll();layout.scrollToPositionWithOffset(pos,0);reading.post(()->{updateControls();checkpoint(true);});}
    void pageDialog(){Archives.Page p=current();if(p==null)return;EditText number=new EditText(this);number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);number.setText(String.valueOf(p.index+1));number.selectAll();new AlertDialog.Builder(this).setTitle("Go to page · "+p.total).setView(number).setNegativeButton("Cancel",null).setPositiveButton("Go",(d,w)->{try{int i=Integer.parseInt(number.getText().toString());if(i<1||i>p.total)throw new NumberFormatException();jumpPage(i-1);}catch(NumberFormatException e){toast("Enter a page from 1 to "+p.total);}}).show();}
    void updateControls(){if(!controls||scrubbing)return;Archives.Page p=current();if(p==null)return;if(!p.chapter.uri.equals(lastControlKey)){heading.setText(selected.name);chapterHeading.setText(p.chapter.name.replaceFirst("(?i)\\.cbz$",""));scrubber.setMax(p.total-1);lastControlKey=p.chapter.uri;lastControlPage=-1;}if(p.index!=lastControlPage){counter.setText((p.index+1)+" / "+p.total);scrubber.setProgress(p.index);lastControlPage=p.index;}}
    void checkpoint(boolean force){
        if(selected==null||restoring||scrubbing)return;long now=SystemClock.uptimeMillis();if(!force&&now-lastCheckpoint<250)return;
        Archives.Page p=current();if(p==null)return;View v=layout.findViewByPosition(layout.findFirstVisibleItemPosition());if(v==null)return;int offset=v.getTop()-reading.getPaddingTop();String key=selected.uri+"|"+p.chapter.uri+"|"+p.index;if(key.equals(lastSavedKey)&&offset==lastSavedOffset)return;lastCheckpoint=now;lastSavedKey=key;lastSavedOffset=offset;library.save(selected,p.chapter,p.index,offset,p.total);
    }
    void observe(){
        long now=SystemClock.uptimeMillis();
        if(selected==null||restoring||scrubbing||pager==null||pager.getCurrentItem()!=1||!hasWindowFocus()||controls||now<ignoreCoverageUntil||reading.getScrollState()==RecyclerView.SCROLL_STATE_SETTLING){observed.clear();return;}
        Map<String,Seen> next=new HashMap<>();
        for(int i=0;i<reading.getChildCount();i++){
            View v=reading.getChildAt(i);RecyclerView.ViewHolder vh=reading.getChildViewHolder(v);if(!(vh instanceof PageHolder))continue;
            PageHolder h=(PageHolder)vh;Archives.Page p=h.page;if(p==null||!h.decoded||v.getHeight()<=0)continue;
            int from=Math.max(0,-v.getTop()),to=Math.min(v.getHeight(),reading.getHeight()-v.getTop());if(to<=from)continue;
            String key=p.chapter.uri+"|"+p.hash;int[] masks=coverage.get(key);if(masks==null)continue;
            String pageKey=key+"|"+p.index;int visible=Rules.coverage(0,from,to,v.getHeight());Seen prior=observed.get(pageKey);
            next.put(pageKey,new Seen(visible,now));
            if(prior==null||now-prior.time<250||now-prior.time>1000)continue;
            int n=masks[p.index]|(prior.mask&visible);if(n!=masks[p.index]){masks[p.index]=n;library.saveCoverage(key,masks);library.certify(p.chapter,p.hash,masks);}
        }
        observed.clear();observed.putAll(next);
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
    @Override protected void onResume(){super.onResume();if(pager!=null&&!library.root().isEmpty()&&!library.permission(library.root(),false)&&selected!=null){clearReader();mangas=new ArrayList<>();renderLibrary();renderHistory();showReaderState("Folder access expired","Choose your manga folder again.","Select Folder");}}
    @Override public void onBackPressed(){if(pager.getCurrentItem()==2&&browsing!=null){browsing=null;search.setText("");renderLibrary();}else if(pager.getCurrentItem()!=1)pager.setCurrentItem(1,true);else if(controls)toggleControls();else super.onBackPressed();}
    @Override protected void onDestroy(){checkpoint(true);dead=true;main.removeCallbacksAndMessages(null);scanner.shutdownNow();indexer.shutdownNow();decoder.shutdownNow();cleanupWorker.shutdownNow();super.onDestroy();}
}
