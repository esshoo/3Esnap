package com.snapclean.app;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

/** A local, in-app audio/video player with seeking; playback stops when the app leaves the screen. */
public final class PlayerActivity extends AppCompatActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SnapCleanApp app;
    private DownloadRecord record;
    private VideoView video;
    private MediaPlayer audio;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Button play;
    private SeekBar seek;
    private TextView time;
    private boolean prepared,dragging,failed,autoStart=true,resumed;
    private int restoredPosition;
    private final AudioManager.OnAudioFocusChangeListener focus=change -> {
        if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) runOnUiThread(() -> { autoStart=false;pause(); });
    };
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(prepared && !dragging) {
                int duration=duration(),position=position();
                seek.setMax(Math.max(1,duration));seek.setProgress(position);
                time.setText(clock(position)+"  /  "+clock(duration));
                play.setText(isPlaying() ? "إيقاف مؤقت" : "تشغيل");
            }
            handler.postDelayed(this,500);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app=SnapCleanApp.of(this);
        if(state!=null) { restoredPosition=state.getInt("position",0);autoStart=state.getBoolean("playing",false); }
        record=app.store.get(getIntent().getLongExtra("id",-1));
        if(record==null || !"completed".equals(record.status)) { finish();return; }
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,18,32));root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets) -> {
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightNavigationBars(false);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(12),dp(8),dp(12),dp(8));
        Button back=button("رجوع");back.setOnClickListener(v -> finish());top.addView(back);
        TextView title=text(record.title,16);title.setMaxLines(2);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);
        FrameLayout stage=new FrameLayout(this);stage.setBackgroundColor(Color.BLACK);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        boolean isAudio="audio".equals(record.format);
        if(isAudio) {
            LinearLayout art=new LinearLayout(this);art.setOrientation(LinearLayout.VERTICAL);art.setGravity(Gravity.CENTER);art.setPadding(dp(24),dp(24),dp(24),dp(24));
            TextView note=text("♫",88);note.setTextColor(Color.rgb(85,213,239));note.setGravity(Gravity.CENTER);art.addView(note);
            TextView name=text(record.title,21);name.setGravity(Gravity.CENTER);art.addView(name);
            TextView label=text("3Esnap • تشغيل من مكتبتك",14);label.setGravity(Gravity.CENTER);art.addView(label);
            stage.addView(art,new FrameLayout.LayoutParams(-1,-1));
        } else {
            video=new VideoView(this);
            FrameLayout.LayoutParams vp=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER);
            stage.addView(video,vp);
        }
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(dp(16),dp(12),dp(16),dp(16));
        time=text("00:00 / 00:00",13);time.setGravity(Gravity.CENTER);time.setTextDirection(View.TEXT_DIRECTION_LTR);controls.addView(time);
        seek=new SeekBar(this);seek.setEnabled(false);seek.setContentDescription("موضع التشغيل");seek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        controls.addView(seek,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER);
        play=button("جاري التجهيز…");play.setEnabled(false);play.setOnClickListener(v -> { if(isPlaying()) { autoStart=false;pause(); }else { autoStart=true;start(); } });buttons.addView(play);
        Button external=button("فتح بمشغل آخر");external.setOnClickListener(v -> openExternal());buttons.addView(external);
        controls.addView(buttons);root.addView(controls);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s,int progress,boolean fromUser) { if(fromUser) time.setText(clock(progress)+" / "+clock(duration())); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging=true; }
            @Override public void onStopTrackingTouch(SeekBar s) { seekTo(s.getProgress());dragging=false; }
        });
        try {
            Uri uri=Uri.parse(record.uri);
            if(isAudio) {
                audio=new MediaPlayer();
                audio.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                audio.setDataSource(this,uri);
                audio.setOnPreparedListener(mp -> onPrepared());
                audio.setOnCompletionListener(mp -> onComplete());
                audio.setOnErrorListener((mp,what,extra) -> { onError();return true; });
                audio.prepareAsync();
            } else {
                video.setOnPreparedListener(mp -> onPrepared());
                video.setOnCompletionListener(mp -> onComplete());
                video.setOnErrorListener((mp,what,extra) -> { onError();return true; });
                video.setVideoURI(uri);
            }
        } catch(Exception ex) { onError(); }
        handler.post(tick);
    }
    private TextView text(String value,int size) { TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(dp(8),dp(6),dp(8),dp(6));return t; }
    private Button button(String label) { Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));return b; }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density); }
    private void onPrepared() { prepared=true;play.setEnabled(true);seek.setEnabled(true);seekTo(restoredPosition);if(autoStart && resumed) start();else play.setText("تشغيل"); }
    private void onComplete() { autoStart=false;play.setText("تشغيل");seekTo(0);releaseAudioFocus();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    private void onError() {
        if(failed || isFinishing()) return;
        failed=true;prepared=false;if(play!=null) { play.setEnabled(false);play.setText("تعذر التشغيل"); }
        new AlertDialog.Builder(this).setTitle("تعذر تشغيل الملف")
                .setMessage("قد يكون الترميز غير مدعوم على هذا الجهاز، أو أن الملف نُقل. جرّب مشغلاً خارجياً أو حدّث المكتبة.")
                .setPositiveButton("فتح بمشغل آخر",(d,w) -> openExternal()).setNegativeButton("إغلاق",(d,w) -> finish()).show();
    }
    private void start() {
        if(!prepared || !resumed) return;
        try {
            if(audio!=null) {
                if(!requestAudioFocus()) { Toast.makeText(this,"تعذر بدء الصوت الآن.",Toast.LENGTH_SHORT).show();return; }
                audio.start();
            } else video.start();
            play.setText("إيقاف مؤقت");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch(Exception ex) { onError(); }
    }
    private void pause() {
        try { if(prepared) { if(audio!=null) audio.pause();else if(video!=null) video.pause(); } } catch(Exception ignored) {}
        if(play!=null) play.setText("تشغيل");releaseAudioFocus();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private boolean requestAudioFocus() {
        if(audioManager==null) return true;
        if(Build.VERSION.SDK_INT>=26) {
            if(focusRequest==null) focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focus).build();
            return audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    private void releaseAudioFocus() {
        if(audioManager==null || audio==null) return;
        if(Build.VERSION.SDK_INT>=26 && focusRequest!=null) audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(focus);
    }
    private int position() { try { return prepared ? (audio!=null ? audio.getCurrentPosition() : video.getCurrentPosition()) : restoredPosition; } catch(Exception ex) { return 0; } }
    private int duration() { try { return prepared ? Math.max(0,audio!=null ? audio.getDuration() : video.getDuration()) : 0; } catch(Exception ex) { return 0; } }
    private boolean isPlaying() { try { return prepared && (audio!=null ? audio.isPlaying() : video.isPlaying()); } catch(Exception ex) { return false; } }
    private void seekTo(int position) { try { if(prepared) { if(audio!=null) audio.seekTo(position);else video.seekTo(position); } } catch(Exception ignored) {} }
    private static String clock(int ms) { int s=Math.max(0,ms)/1000;return s>=3600 ? String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60) : String.format(Locale.US,"%02d:%02d",s/60,s%60); }
    private void openExternal() {
        if(record==null) return;
        autoStart=false;pause();
        try {
            Uri uri=app.files.shareUri(record);
            Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.mime);
            i.setClipData(ClipData.newRawUri(record.title,uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"فتح الملف"));
        } catch(Exception ex) { Toast.makeText(this,"لم أجد مشغلاً مناسباً أو لا يمكن الوصول إلى الملف.",Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onResume() { super.onResume();resumed=true;if(prepared && autoStart) start(); }
    @Override protected void onPause() { autoStart=isPlaying();restoredPosition=position();resumed=false;pause();super.onPause(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle state) { state.putInt("position",position());state.putBoolean("playing",isPlaying()||autoStart);super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);releaseAudioFocus();
        if(video!=null) video.stopPlayback();
        if(audio!=null) { audio.release();audio=null; }
        super.onDestroy();
    }
}
