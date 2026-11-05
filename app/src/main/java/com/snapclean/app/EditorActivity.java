package com.snapclean.app;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.CheckBox;
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
    private EditPlan.Crop selectedCrop;
    private static final int REQUEST_CROP=301;
    private Button cropButton;
    private TextView cropStatus;
    private SeekBar seek,startSeek,endSeek;
    private Button play,save,share,gifSave,gifShare;
    private CheckBox reduceMusic;
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
        if(state!=null && state.getInt("cropWidth",0)>0) {
            try { selectedCrop=new EditPlan.Crop(state.getInt("cropX"),state.getInt("cropY"),
                    state.getInt("cropWidth"),state.getInt("cropHeight")); }
            catch(IllegalArgumentException ignored) {selectedCrop=null;}
        }
        createLayout();
        if(!audioMode) {
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
        if(!audioMode && state==null && getIntent().getBooleanExtra("openCrop",false))
            getWindow().getDecorView().post(this::openCropPage);
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
        TextView heading=text(audioMode?"قص الصوت":"قص الفيديو",17);heading.setGravity(Gravity.CENTER);heading.setTypeface(null,Typeface.BOLD);
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
            TextView cropLabel=text("قص إطار الفيديو (Crop)",15);cropLabel.setTypeface(null,Typeface.BOLD);body.addView(cropLabel);
            cropStatus=text("",12);cropStatus.setTextColor(0xffb8c8d7);body.addView(cropStatus);
            LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
            cropButton=button("تحديد مساحة القص",false);
            controls.addView(cropButton,new LinearLayout.LayoutParams(0,dp(50),2));
            Button clear=button("إلغاء القص",false);
            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(0,dp(50),1);cl.setMarginStart(dp(8));controls.addView(clear,cl);
            body.addView(controls);
            cropButton.setOnClickListener(v->openCropPage());
            clear.setOnClickListener(v->{selectedCrop=null;updateCropStatus();});
            if(videoWidth<16 || videoHeight<16) {cropButton.setEnabled(false);cropButton.setText("قص الإطار غير متاح");}
            updateCropStatus();
        }
        reduceMusic=new CheckBox(this);
        reduceMusic.setText("تقليل الموسيقى الخلفية (تجريبي) — قد يؤثر على الصوت والكلام");
        reduceMusic.setTextColor(Color.WHITE);reduceMusic.setTextSize(12);
        body.addView(reduceMusic);
        TextView musicTip=text("هذه فلترة للترددات والضوضاء فقط، وليست فصلًا دقيقًا للموسيقى عن الصوت بالذكاء الاصطناعي.",11);
        musicTip.setTextColor(0xffa2b1c4);body.addView(musicTip);
        LinearLayout bottomArea=new LinearLayout(this);bottomArea.setOrientation(LinearLayout.VERTICAL);
        bottomArea.setBackgroundColor(PANEL);
        LinearLayout bottom=new LinearLayout(this);bottom.setPadding(dp(12),dp(8),dp(12),dp(8));bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(PANEL);
        save=button("قص وحفظ",true);share=button("قص ومشاركة",false);
        bottom.addView(save,new LinearLayout.LayoutParams(0,dp(54),1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(54),1);sp.setMarginStart(dp(10));bottom.addView(share,sp);
        bottomArea.addView(bottom);
        save.setOnClickListener(v->exportClip(false,false));share.setOnClickListener(v->exportClip(true,false));
        if(!audioMode) {
            LinearLayout gifRow=new LinearLayout(this);gifRow.setPadding(dp(12),0,dp(12),dp(8));
            gifSave=button("حفظ GIF (15 ث كحد أقصى)",false);
            gifShare=button("مشاركة GIF",false);
            gifRow.addView(gifSave,new LinearLayout.LayoutParams(0,dp(48),1));
            LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(0,dp(48),1);gp.setMarginStart(dp(8));
            gifRow.addView(gifShare,gp);bottomArea.addView(gifRow);
            gifSave.setOnClickListener(v->exportClip(false,true));
            gifShare.setOnClickListener(v->exportClip(true,true));
        }
        root.addView(bottomArea);
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
    private void openCropPage() {
        if(busy || record==null || videoWidth<16 || videoHeight<16)return;
        pause();
        Intent intent=new Intent(this,CropActivity.class).putExtra("id",record.id)
                .putExtra("videoWidth",videoWidth).putExtra("videoHeight",videoHeight)
                .putExtra("durationMs",durationMs).putExtra("positionMs",currentPosition());
        if(selectedCrop!=null) {
            intent.putExtra("cropX",selectedCrop.x).putExtra("cropY",selectedCrop.y)
                    .putExtra("cropWidth",selectedCrop.width).putExtra("cropHeight",selectedCrop.height);
        }
        startActivityForResult(intent,REQUEST_CROP);
    }
    private void updateCropStatus() {
        if(cropStatus==null)return;
        if(selectedCrop==null) {
            cropStatus.setText("بدون قص إطار. اضغط «تحديد مساحة القص» لفتح شاشة مستقلة واختيار نسبة 1:1 أو 9:16 أو 16:9.");
            if(cropButton!=null)cropButton.setText("تحديد مساحة القص");
        } else {
            cropStatus.setText("✓ مساحة القص المحددة: "+selectedCrop.width+" × "+selectedCrop.height+" بكسل. ستُطبق عند حفظ المقطع.");
            if(cropButton!=null)cropButton.setText("تعديل مساحة القص");
        }
    }
    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQUEST_CROP || resultCode!=RESULT_OK || data==null)return;
        try {
            EditPlan.Crop next=new EditPlan.Crop(data.getIntExtra("cropX",0),data.getIntExtra("cropY",0),
                    data.getIntExtra("cropWidth",0),data.getIntExtra("cropHeight",0));
            if(next.x+next.width>videoWidth || next.y+next.height>videoHeight)
                throw new IllegalArgumentException("مساحة القص خارج الفيديو.");
            selectedCrop=next;updateCropStatus();
            Toast.makeText(this,"تم تطبيق مساحة القص. احفظ الفيديو للحصول على النسخة الجديدة.",Toast.LENGTH_SHORT).show();
        } catch(IllegalArgumentException ex) {Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void exportClip(boolean shareAfter,boolean gif) {
        if(busy)return;
        EditPlan.Clip selected;EditPlan.Crop selection=null;
        try {selected=EditPlan.clip(trimStart,trimEnd,durationMs);if(!audioMode)selection=selectedCrop;}
        catch(IllegalArgumentException ex){Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();return;}
        if(gif && selected.lengthMs()>15000) {
            Toast.makeText(this,"حدد مدة لا تتجاوز 15 ثانية لحفظ GIF.",Toast.LENGTH_LONG).show();return;
        }
        pause();busy=true;save.setEnabled(false);share.setEnabled(false);play.setEnabled(false);
        if(gifSave!=null)gifSave.setEnabled(false);if(gifShare!=null)gifShare.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        spinner.setVisibility(View.VISIBLE);status.setText("جاري قص المقطع وحفظه… خليك في الشاشة لحد ما يكتمل.");
        exporter=new MediaEditExporter();
        final EditPlan.Crop editCrop=selection;
        final boolean filterAudio=reduceMusic.isChecked() && !gif;
        worker.execute(()->{
            try {
                long id=exporter.export(getApplicationContext(),record,selected,editCrop,audioMode,gif,filterAudio);
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
        if(gifSave!=null)gifSave.setEnabled(true);if(gifShare!=null)gifShare.setEnabled(true);
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
    @Override protected void onSaveInstanceState(@NonNull Bundle state){
        state.putInt("start",trimStart);state.putInt("end",trimEnd);state.putInt("position",currentPosition());
        if(selectedCrop!=null){state.putInt("cropX",selectedCrop.x);state.putInt("cropY",selectedCrop.y);
            state.putInt("cropWidth",selectedCrop.width);state.putInt("cropHeight",selectedCrop.height);}
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed(){if(!busy){super.onBackPressed();return;}new AlertDialog.Builder(this)
            .setTitle("جارٍ إنشاء المقطع").setMessage("الرجوع الآن يلغي عملية القص، والملف الأصلي لن يتأثر.")
            .setPositiveButton("إلغاء القص والرجوع",(d,w)->{if(exporter!=null)exporter.cancel();finish();})
            .setNegativeButton("متابعة الانتظار",null).show();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(exporter!=null)exporter.cancel();worker.shutdownNow();
        if(video!=null)video.stopPlayback();if(audio!=null){audio.release();audio=null;}super.onDestroy();}
}
