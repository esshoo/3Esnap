package com.snapclean.app;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Time trimming for audio/video and optional drag-to-crop for video. Exports are new files. */
public final class EditorActivity extends AppCompatActivity {
    private static final int BG=Color.rgb(11,18,32),PANEL=Color.rgb(18,30,48),ACCENT=Color.rgb(85,213,239);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private MediaEditExporter exporter;
    private SnapCleanApp app;
    private DownloadRecord record;
    private VideoView video;
    private MediaPlayer audio;
    private CropOverlayView crop;
    private SeekBar seek,startSeek,endSeek;
    private Button play,save,share,toggleCrop;
    private TextView playerTime,startLabel,endLabel,selectedLength,status;
    private ProgressBar spinner;
    private boolean prepared,playingPreview,busy,audioMode,resumed,updating;
    private int durationMs,videoWidth,videoHeight,restoredPosition;
    private int trimStart,trimEnd;
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(prepared && !busy) {
                int p=currentPosition();
                if(playingPreview && p>=trimEnd) {pause();seekTo(trimStart);p=trimStart;}
                seek.setProgress(Math.max(0,Math.min(durationMs,p)));
                playerTime.setText(clock(p)+" / "+clock(durationMs));
                play.setText(isPlaying()?"إيقاف مؤقت":"معاينة الجزء");
            }
            handler.postDelayed(this,250);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app=SnapCleanApp.of(this);
        record=app.store.get(getIntent().getLongExtra("id",-1));
        if(record==null || !"completed".equals(record.status) || !app.files.exists(record)) {
            Toast.makeText(this,"الملف غير متاح للتحرير.",Toast.LENGTH_LONG).show();finish();return;
        }
        audioMode="audio".equals(record.format);
        try {
            MediaMetadataRetriever metadata=new MediaMetadataRetriever();
            try {
                metadata.setDataSource(this,Uri.parse(record.uri));
                durationMs=parse(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if(!audioMode) {
                    videoWidth=parse(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    videoHeight=parse(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    int rotation=parse(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                    if(rotation==90 || rotation==270) {int old=videoWidth;videoWidth=videoHeight;videoHeight=old;}
                }
            } finally { metadata.release(); }
            if(durationMs<EditPlan.MIN_CLIP_MS) throw new IllegalArgumentException("الملف قصير جداً أو لا يحتوي على مدة صالحة.");
        } catch(Exception ex) {
            new AlertDialog.Builder(this).setTitle("تعذر فتح محرر القص")
                    .setMessage("تعذر قراءة مدة الملف أو معلوماته. جرّب ملف MP3 أو MP4 مدعوماً.")
                    .setPositiveButton("حسنًا",(d,w)->finish()).show();return;
        }
        trimStart=state==null?0:state.getInt("start",0);
        trimEnd=state==null?durationMs:Math.min(durationMs,state.getInt("end",durationMs));
        restoredPosition=state==null?0:state.getInt("position",0);
        createLayout();
        if(!audioMode) {
            crop.setVideoSize(videoWidth,videoHeight);
            video.setOnPreparedListener(mp -> onPrepared());
            video.setOnErrorListener((mp,what,extra)->{playbackError();return true;});
            video.setVideoURI(Uri.parse(record.uri));
        } else {
            audio=new MediaPlayer();
            try {
                audio.setDataSource(this,Uri.parse(record.uri));
                audio.setOnPreparedListener(mp -> onPrepared());
                audio.setOnErrorListener((mp,what,extra)->{playbackError();return true;});
                audio.prepareAsync();
            } catch(Exception ex) {playbackError();}
        }
        handler.post(tick);
    }
    private void createLayout() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(BG);setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightNavigationBars(false);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(8),dp(12),dp(8));
        Button back=button("رجوع",false);back.setOnClickListener(v->onBackPressed());header.addView(back);
        TextView heading=text(audioMode?"قص الصوت":"قص الفيديو",17);heading.setGravity(Gravity.CENTER);heading.setTypeface(null,1);
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));
        header.addView(new View(this),new LinearLayout.LayoutParams(dp(70),dp(1)));
        root.addView(header);
        FrameLayout stage=new FrameLayout(this);stage.setBackgroundColor(Color.BLACK);
        int h=Math.min(dp(245),Math.max(dp(128),(int)(getResources().getDisplayMetrics().widthPixels*.57f)));
        if(getResources().getDisplayMetrics().heightPixels<dp(520))h=dp(125);
        root.addView(stage,new LinearLayout.LayoutParams(-1,h));
        if(audioMode) {
            LinearLayout art=new LinearLayout(this);art.setOrientation(LinearLayout.VERTICAL);art.setGravity(Gravity.CENTER);
            TextView icon=text("♫",72);icon.setTextColor(ACCENT);art.addView(icon);
            TextView title=text(record.title,15);title.setGravity(Gravity.CENTER);title.setMaxLines(2);art.addView(title);
            stage.addView(art,new FrameLayout.LayoutParams(-1,-1));
        } else {
            video=new VideoView(this);stage.addView(video,new FrameLayout.LayoutParams(-1,-1));
            crop=new CropOverlayView(this);crop.setActive(false);stage.addView(crop,new FrameLayout.LayoutParams(-1,-1));
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(12),dp(16),dp(10));
        scroll.addView(body);
        TextView info=text("الملف الأصلي محفوظ بدون تغيير. اختَر الجزء ثم احفظ نسخة جديدة.",12);
        info.setTextColor(0xffa2b1c4);body.addView(info);
        playerTime=text("00:00 / "+clock(durationMs),13);playerTime.setGravity(Gravity.CENTER);playerTime.setTextDirection(View.TEXT_DIRECTION_LTR);body.addView(playerTime);
        seek=new SeekBar(this);seek.setMax(durationMs);seek.setProgress(restoredPosition);seek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        seek.setContentDescription("موضع معاينة الفيديو أو الصوت");body.addView(seek);
        play=button("جاري تجهيز المعاينة…",false);play.setEnabled(false);body.addView(play,new LinearLayout.LayoutParams(-1,dp(46)));
        play.setOnClickListener(v->{if(isPlaying()){pause();return;}seekTo(Math.max(trimStart,Math.min(currentPosition(),trimEnd-250)));playingPreview=true;start();});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean byUser) {if(byUser){seekTo(value);playerTime.setText(clock(value)+" / "+clock(durationMs));}}
            @Override public void onStartTrackingTouch(SeekBar bar) {pause();}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        addRange(body,true);
        addRange(body,false);
        LinearLayout markers=new LinearLayout(this);markers.setGravity(Gravity.CENTER);markers.setPadding(0,dp(5),0,0);
        Button setStart=button("تعيين البداية من هنا",false);markers.addView(setStart,new LinearLayout.LayoutParams(0,dp(48),1));
        Button setEnd=button("تعيين النهاية من هنا",false);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,dp(48),1);ep.setMarginStart(dp(8));markers.addView(setEnd,ep);
        body.addView(markers);
        setStart.setOnClickListener(v->{int t=currentPosition(); if(t+EditPlan.MIN_CLIP_MS<trimEnd){trimStart=t;updateRange();}else Toast.makeText(this,"حرّك المؤشر قبل النهاية.",Toast.LENGTH_SHORT).show();});
        setEnd.setOnClickListener(v->{int t=currentPosition();if(t-trimStart>=EditPlan.MIN_CLIP_MS){trimEnd=t;updateRange();}else Toast.makeText(this,"حرّك المؤشر بعد البداية.",Toast.LENGTH_SHORT).show();});
        selectedLength=text("",12);selectedLength.setTextColor(ACCENT);selectedLength.setGravity(Gravity.CENTER);body.addView(selectedLength);
        if(!audioMode) {
            TextView cropLabel=text("قص إطار الفيديو (Crop)",15);cropLabel.setTypeface(null,1);body.addView(cropLabel);
            toggleCrop=button("تفعيل قص الإطار",false);body.addView(toggleCrop,new LinearLayout.LayoutParams(-1,dp(46)));
            LinearLayout ratioRow=new LinearLayout(this);ratioRow.setPadding(0,dp(7),0,dp(5));body.addView(ratioRow);
            float[] ratios={0,1,9f/16f,16f/9f};String[] labels={"حر","1:1","9:16","16:9"};
            Button[] buttons=new Button[4];
            for(int i=0;i<labels.length;i++) {
                final float ratio=ratios[i];Button b=button(labels[i],false);buttons[i]=b;
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);if(i>0) lp.setMarginStart(dp(5));ratioRow.addView(b,lp);
                b.setEnabled(false);
                b.setOnClickListener(v->{crop.setAspect(ratio);for(Button other:buttons)style(other,false);style(b,true);});
            }
            style(buttons[0],true);
            toggleCrop.setOnClickListener(v->{boolean next=!crop.isActive();crop.setActive(next);toggleCrop.setText(next?"إلغاء قص الإطار":"تفعيل قص الإطار");for(Button button:buttons)button.setEnabled(next);});
            TextView tip=text("اسحب المستطيل لتغيير مكان القص، واسحب المقبض السفلي لتغيير مساحته.",11);
            tip.setTextColor(0xffa2b1c4);body.addView(tip);
            if(videoWidth<16 || videoHeight<16) {toggleCrop.setEnabled(false);toggleCrop.setText("قص الإطار غير متاح لهذا الفيديو");}
        }
        LinearLayout bottom=new LinearLayout(this);bottom.setPadding(dp(12),dp(8),dp(12),dp(12));bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(PANEL);
        save=button("قص وحفظ",true);share=button("قص ومشاركة",false);
        bottom.addView(save,new LinearLayout.LayoutParams(0,dp(54),1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(54),1);sp.setMarginStart(dp(10));bottom.addView(share,sp);
        root.addView(bottom);
        save.setOnClickListener(v->exportClip(false));share.setOnClickListener(v->exportClip(true));
        spinner=new ProgressBar(this);spinner.setIndeterminate(true);spinner.setVisibility(View.GONE);
        status=text("",11);status.setGravity(Gravity.CENTER);status.setTextColor(0xffa2b1c4);
        LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.setBackgroundColor(PANEL);
        line.addView(spinner,new LinearLayout.LayoutParams(dp(25),dp(25)));
        line.addView(status,new LinearLayout.LayoutParams(0,dp(28),1));root.addView(line);
        updateRange();
    }
    private void addRange(LinearLayout parent,boolean start) {
        TextView label=text(start?"بداية المقطع":"نهاية المقطع",12);label.setTextColor(0xffa2b1c4);
        TextView time=text("",13);time.setTextDirection(View.TEXT_DIRECTION_LTR);time.setTextColor(ACCENT);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label,new LinearLayout.LayoutParams(0,dp(36),1));row.addView(time);parent.addView(row);
        SeekBar bar=new SeekBar(this);bar.setMax(durationMs);bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setContentDescription(start?"بداية المقطع":"نهاية المقطع");parent.addView(bar);
        if(start){startSeek=bar;startLabel=time;}else{endSeek=bar;endLabel=time;}
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int progress,boolean byUser){
                if(!byUser || updating)return;
                if(start) trimStart=Math.min(progress,trimEnd-EditPlan.MIN_CLIP_MS);
                else trimEnd=Math.max(progress,trimStart+EditPlan.MIN_CLIP_MS);
                pause();seekTo(start?trimStart:trimEnd);updateRange();
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private void updateRange() {
        updating=true;
        trimStart=Math.max(0,Math.min(trimStart,durationMs-EditPlan.MIN_CLIP_MS));
        trimEnd=Math.max(trimStart+EditPlan.MIN_CLIP_MS,Math.min(trimEnd,durationMs));
        startSeek.setProgress(trimStart);endSeek.setProgress(trimEnd);
        startLabel.setText(clock(trimStart));endLabel.setText(clock(trimEnd));
        selectedLength.setText("المدة المحددة: "+clock(trimEnd-trimStart));updating=false;
    }
    private void exportClip(boolean shareAfter) {
        if(busy)return;
        EditPlan.Clip selected;EditPlan.Crop selection=null;
        try {selected=EditPlan.clip(trimStart,trimEnd,durationMs);if(!audioMode && crop.isActive())selection=crop.selectedPixels();}
        catch(IllegalArgumentException ex){Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();return;}
        pause();busy=true;save.setEnabled(false);share.setEnabled(false);play.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        spinner.setVisibility(View.VISIBLE);status.setText("جاري قص المقطع وحفظه… خليك في الشاشة لحد ما يكتمل.");
        exporter=new MediaEditExporter();
        final EditPlan.Crop editCrop=selection;
        worker.execute(()->{
            try {
                long id=exporter.export(getApplicationContext(),record,selected,editCrop,audioMode);
                runOnUiThread(()->{
                    if(isFinishing() || isDestroyed())return;
                    finishExport("تم حفظ المقطع في مكتبة 3Esnap.");
                    if(shareAfter) shareClip(id);
                });
            } catch(Exception ex) {
                runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())finishExport("تعذر إنشاء المقطع: "+(ex.getMessage()==null?"خطأ غير معروف":ex.getMessage()));});
            }
        });
    }
    private void finishExport(String result) {
        busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        spinner.setVisibility(View.GONE);save.setEnabled(true);share.setEnabled(true);play.setEnabled(prepared);
        status.setText(result);Toast.makeText(this,result,Toast.LENGTH_LONG).show();
    }
    private void shareClip(long id) {
        try {
            DownloadRecord output=app.store.get(id);if(output==null)return;
            Uri uri=app.files.shareUri(output);
            Intent send=new Intent(Intent.ACTION_SEND).setType(output.mime)
                    .putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TITLE,output.title);
            send.setClipData(ClipData.newRawUri(output.title,uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send,"مشاركة المقطع"));
        } catch(Exception ex){Toast.makeText(this,"تم حفظ المقطع، لكن تعذرت المشاركة.",Toast.LENGTH_LONG).show();}
    }
    private void onPrepared() {prepared=true;seek.setEnabled(true);play.setEnabled(true);play.setText("معاينة الجزء");seekTo(restoredPosition);}
    private void playbackError() {play.setEnabled(false);play.setText("المعاينة غير متاحة");status.setText("قد يكون هذا الترميز غير مدعوم في المشغل؛ جرّب ملفاً آخر.");}
    private boolean isPlaying(){try{return prepared && (audio!=null?audio.isPlaying():video.isPlaying());}catch(Exception ex){return false;}}
    private int currentPosition(){try{return prepared?(audio!=null?audio.getCurrentPosition():video.getCurrentPosition()):restoredPosition;}catch(Exception ex){return restoredPosition;}}
    private void seekTo(int ms){restoredPosition=ms;try{if(prepared){if(audio!=null)audio.seekTo(ms);else video.seekTo(ms);}}catch(Exception ignored){}}
    private void start(){if(!prepared||!resumed)return;try{if(audio!=null)audio.start();else video.start();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}catch(Exception ex){playbackError();}}
    private void pause(){playingPreview=false;try{if(prepared){if(audio!=null)audio.pause();else video.pause();}}catch(Exception ignored){}if(!busy)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(play!=null)play.setText("معاينة الجزء");}
    private void style(Button b,boolean bright){b.setBackground(background(bright));b.setTextColor(bright?BG:Color.WHITE);}
    private GradientDrawable background(boolean filled){GradientDrawable d=new GradientDrawable();d.setColor(filled?ACCENT:PANEL);d.setCornerRadius(dp(12));d.setStroke(dp(1),filled?ACCENT:0xff27374c);return d;}
    private Button button(String label,boolean bright){Button b=new Button(this);b.setText(label);b.setTextSize(12);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(44));b.setPadding(dp(7),dp(3),dp(7),dp(3));style(b,bright);return b;}
    private TextView text(String label,int size){TextView t=new TextView(this);t.setText(label);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(dp(5),dp(4),dp(5),dp(4));return t;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private static int parse(String value){try{return Math.max(0,Integer.parseInt(value));}catch(Exception ignored){return 0;}}
    private static String clock(int ms){int s=Math.max(0,ms)/1000;return s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.US,"%02d:%02d",s/60,s%60);}
    @Override protected void onResume(){super.onResume();resumed=true;}
    @Override protected void onPause(){pause();resumed=false;super.onPause();}
    @Override protected void onSaveInstanceState(@NonNull Bundle state){state.putInt("start",trimStart);state.putInt("end",trimEnd);state.putInt("position",currentPosition());super.onSaveInstanceState(state);}
    @Override public void onBackPressed(){if(!busy){super.onBackPressed();return;}new AlertDialog.Builder(this)
            .setTitle("جارٍ إنشاء المقطع").setMessage("الرجوع الآن يلغي عملية القص، والملف الأصلي لن يتأثر.")
            .setPositiveButton("إلغاء القص والرجوع",(d,w)->{if(exporter!=null)exporter.cancel();finish();})
            .setNegativeButton("متابعة الانتظار",null).show();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(exporter!=null)exporter.cancel();worker.shutdownNow();
        if(video!=null)video.stopPlayback();if(audio!=null){audio.release();audio=null;}super.onDestroy();}
}
