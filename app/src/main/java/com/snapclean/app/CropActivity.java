package com.snapclean.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate, touch-operated crop page. Static frames avoid SurfaceView overlay z-order problems. */
public final class CropActivity extends AppCompatActivity {
    private static final int BG=0xff0b1220,PANEL=0xff121e30,ACCENT=0xff55d5ef;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private DownloadRecord record;
    private CropOverlayView overlay;
    private ImageView preview;
    private ProgressBar spinner;
    private TextView timestamp,caption;
    private SeekBar frameSeek;
    private int sourceWidth,sourceHeight,durationMs;
    private int requestSerial;
    private Bitmap displayed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SnapCleanApp app=SnapCleanApp.of(this);
        record=app.store.get(getIntent().getLongExtra("id",-1L));
        if(record==null || !"video".equals(record.format) || !"completed".equals(record.status) || !app.files.exists(record)) {
            Toast.makeText(this,"الفيديو غير متاح لقص الإطار.",Toast.LENGTH_LONG).show();finish();return;
        }
        sourceWidth=getIntent().getIntExtra("videoWidth",0);
        sourceHeight=getIntent().getIntExtra("videoHeight",0);
        durationMs=getIntent().getIntExtra("durationMs",0);
        if(sourceWidth<16 || sourceHeight<16 || durationMs<=0) {
            Toast.makeText(this,"تعذر قراءة أبعاد الفيديو.",Toast.LENGTH_LONG).show();finish();return;
        }
        buildLayout();
        overlay.setVideoSize(sourceWidth,sourceHeight);
        overlay.setActive(true);
        int w=getIntent().getIntExtra("cropWidth",0),h=getIntent().getIntExtra("cropHeight",0);
        if(w>=16 && h>=16) {
            try {overlay.setSelection(new EditPlan.Crop(getIntent().getIntExtra("cropX",0),
                    getIntent().getIntExtra("cropY",0),w,h));}
            catch(IllegalArgumentException ignored) {overlay.reset();}
        }
        int start=Math.max(0,Math.min(durationMs,getIntent().getIntExtra("positionMs",0)));
        frameSeek.setMax(Math.max(1,durationMs));frameSeek.setProgress(start);
        requestFrame(start);
    }
    private void buildLayout() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(BG);setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightNavigationBars(false);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12),dp(10),dp(12),dp(12));
        Button back=button("رجوع",false);back.setOnClickListener(v->finish());header.addView(back);
        TextView title=text("قص إطار الفيديو",18);title.setTypeface(null,Typeface.BOLD);title.setGravity(Gravity.CENTER);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));
        Button reset=button("إعادة تعيين",false);reset.setOnClickListener(v->{overlay.reset();caption.setText("تمت إعادة ضبط إطار القص.");});
        header.addView(reset);root.addView(header);

        FrameLayout stage=new FrameLayout(this);stage.setBackgroundColor(Color.BLACK);
        int displayH=getResources().getDisplayMetrics().heightPixels;
        int displayW=getResources().getDisplayMetrics().widthPixels;
        int height=Math.max(dp(215),Math.min(dp(430),Math.min((int)(displayH*.46f),displayW)));
        root.addView(stage,new LinearLayout.LayoutParams(-1,height));
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("لقطة من الفيديو لتحديد منطقة قص الإطار");
        stage.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        overlay=new CropOverlayView(this);overlay.setContentDescription("اسحب إطار القص، أو اسحب إحدى الزوايا الأربع لتغيير المساحة");
        stage.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        spinner=new ProgressBar(this);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER);
        stage.addView(spinner,sp);

        LinearLayout center=new LinearLayout(this);center.setOrientation(LinearLayout.VERTICAL);
        center.setPadding(dp(16),dp(12),dp(16),dp(12));root.addView(center,new LinearLayout.LayoutParams(-1,0,1));
        timestamp=text("00:00 / "+clock(durationMs),13);timestamp.setTextDirection(View.TEXT_DIRECTION_LTR);timestamp.setGravity(Gravity.CENTER);
        center.addView(timestamp);
        frameSeek=new SeekBar(this);frameSeek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        frameSeek.setContentDescription("اختيار لقطة من الفيديو لتحديد القص");center.addView(frameSeek);
        frameSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                if(user)timestamp.setText(clock(value)+" / "+clock(durationMs));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {requestFrame(bar.getProgress());}
        });
        TextView subtitle=text("نسبة العرض إلى الارتفاع",15);subtitle.setTypeface(null,Typeface.BOLD);center.addView(subtitle);
        LinearLayout ratios=new LinearLayout(this);ratios.setGravity(Gravity.CENTER);ratios.setPadding(0,dp(8),0,dp(10));center.addView(ratios);
        float[] values={0f,1f,9f/16f,16f/9f};String[] names={"حر","1:1","9:16","16:9"};
        Button[] selectors=new Button[4];
        for(int i=0;i<4;i++) {
            final float value=values[i];Button b=button(names[i],i==0);selectors[i]=b;
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);if(i>0)p.setMarginStart(dp(6));ratios.addView(b,p);
            b.setOnClickListener(v->{overlay.setAspect(value);for(Button other:selectors)style(other,other==b);caption.setText("اسحب المستطيل أو إحدى زواياه لتعديل مساحة القص.");});
        }
        caption=text("اسحب المستطيل لتحريكه، واسحب أي زاوية لتعديل مساحة القص. غيّر اللقطة بالشريط أعلاه.",12);
        caption.setTextColor(0xffbdc9d8);caption.setGravity(Gravity.CENTER);center.addView(caption);

        LinearLayout footer=new LinearLayout(this);footer.setPadding(dp(14),dp(8),dp(14),dp(14));
        footer.setBackgroundColor(PANEL);root.addView(footer);
        Button apply=button("تطبيق القص",true),cancel=button("إلغاء",false);
        footer.addView(apply,new LinearLayout.LayoutParams(0,dp(54),1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(54),1);cp.setMarginStart(dp(10));footer.addView(cancel,cp);
        apply.setOnClickListener(v->{
            try {
                EditPlan.Crop result=overlay.selectedPixels();
                Intent output=new Intent().putExtra("cropX",result.x).putExtra("cropY",result.y)
                        .putExtra("cropWidth",result.width).putExtra("cropHeight",result.height);
                setResult(RESULT_OK,output);finish();
            } catch(IllegalArgumentException ex) {Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();}
        });
        cancel.setOnClickListener(v->finish());
    }
    private void requestFrame(int ms) {
        final int serial=++requestSerial;
        spinner.setVisibility(View.VISIBLE);
        worker.execute(()->{
            Bitmap frame=null;
            try {
                MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(this,Uri.parse(record.uri));
                    int rotationValue=0;
                    try {rotationValue=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));}
                    catch(Exception ignored) {}
                    if(Build.VERSION.SDK_INT>=27) frame=retriever.getScaledFrameAtTime(ms*1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,640,640);
                    else frame=retriever.getFrameAtTime(ms*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if(frame!=null && (rotationValue==90 || rotationValue==180 || rotationValue==270)) {
                        // Rotate only when the decoded frame does not match the displayed
                        // orientation; some Android decoders already apply rotation.
                        boolean framePortrait=frame.getHeight()>frame.getWidth();
                        boolean displayPortrait=sourceHeight>sourceWidth;
                        if((rotationValue==180) || (framePortrait!=displayPortrait)) {
                            Matrix matrix=new Matrix();matrix.postRotate(rotationValue);
                            Bitmap oriented=Bitmap.createBitmap(frame,0,0,frame.getWidth(),frame.getHeight(),matrix,true);
                            if(oriented!=frame) {frame.recycle();frame=oriented;}
                        }
                    }
                    if(frame!=null && (frame.getWidth()>1100 || frame.getHeight()>1100)) {
                        float scale=640f/Math.max(frame.getWidth(),frame.getHeight());
                        Bitmap small=Bitmap.createScaledBitmap(frame,Math.max(1,(int)(frame.getWidth()*scale)),Math.max(1,(int)(frame.getHeight()*scale)),true);
                        if(small!=frame) {frame.recycle();frame=small;}
                    }
                } finally {retriever.release();}
            } catch(Exception ignored) {}
            final Bitmap image=frame;
            runOnUiThread(()->{
                if(isFinishing() || isDestroyed() || serial!=requestSerial) {if(image!=null)image.recycle();return;}
                if(displayed!=null) {preview.setImageDrawable(null);displayed.recycle();}
                displayed=image;preview.setImageBitmap(image);
                spinner.setVisibility(View.GONE);
                if(image==null)caption.setText("لم أتمكن من عرض لقطة؛ يمكن اختيار مساحة القص اعتماداً على إطار الفيديو.");
            });
        });
    }
    private void style(Button b,boolean bright) {
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(bright?ACCENT:PANEL);
        drawable.setCornerRadius(dp(12));drawable.setStroke(dp(1),bright?ACCENT:0xff294058);
        b.setBackground(drawable);b.setTextColor(bright?BG:Color.WHITE);
    }
    private Button button(String label,boolean bright) {
        Button b=new Button(this);b.setText(label);b.setTextSize(12);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(6),dp(4),dp(6),dp(4));style(b,bright);return b;
    }
    private TextView text(String label,int size) {
        TextView t=new TextView(this);t.setText(label);t.setTextSize(size);t.setTextColor(Color.WHITE);
        t.setPadding(dp(5),dp(5),dp(5),dp(5));return t;
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private static String clock(int ms) {int s=Math.max(0,ms)/1000;return s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.US,"%02d:%02d",s/60,s%60);}
    @Override protected void onDestroy() {
        requestSerial++;worker.shutdownNow();
        if(preview!=null)preview.setImageDrawable(null);
        if(displayed!=null){displayed.recycle();displayed=null;}
        super.onDestroy();
    }
}
