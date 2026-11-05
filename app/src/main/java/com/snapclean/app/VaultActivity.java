package com.snapclean.app;

import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.List;

/** Separate native screen: no thumbnails/filenames are exposed before strong biometric auth. */
public final class VaultActivity extends AppCompatActivity {
    private SnapCleanApp app;
    private VaultStore vault;
    private LinearLayout items;
    private boolean unlocked,prompting;
    private long pendingHide;
    private File preview;
    private VideoView video;
    private MediaPlayer audio;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app=SnapCleanApp.of(this);vault=new VaultStore(this,app.store,app.files);
        pendingHide=getIntent().getLongExtra("hideId",-1);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setPadding(dp(14),dp(30),dp(14),dp(12));
        root.setBackgroundColor(Color.rgb(11,18,32));setContentView(root);
        TextView heading=text("🔒 الخزنة الخاصة — 3Esnap",20);root.addView(heading);
        TextView note=text("هذه الملفات مشفرة داخل التطبيق ولا تظهر في المكتبة العامة. حذف التطبيق أو مسح بياناته يفقد محتويات الخزنة. لا تستخدمها قبل تثبيت مفتاح توقيع ثابت.",12);
        note.setTextColor(0xffb9c9d9);root.addView(note);
        Button back=button("رجوع للمكتبة");back.setOnClickListener(v->finish());root.addView(back);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        items=new LinearLayout(this);items.setOrientation(LinearLayout.VERTICAL);scroll.addView(items);
        items.addView(text("مقفلة — استخدم بصمة الجهاز لفتح الملفات.",14));
        Button authenticate=button("فتح بالبصمة");authenticate.setOnClickListener(v->authenticate());root.addView(authenticate);
        getWindow().getDecorView().post(this::authenticate);
    }
    private void authenticate() {
        if(prompting || unlocked || isFinishing())return;
        BiometricManager manager=BiometricManager.from(this);
        if(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS) {
            new AlertDialog.Builder(this).setTitle("البصمة غير متاحة")
                .setMessage("الخزنة تتطلب بصمة أو مصادقة حيوية قوية مسجلة بالجهاز. فعّلها من إعدادات الهاتف ثم أعد المحاولة.")
                .setPositiveButton("حسنًا",(d,w)->finish()).show();return;
        }
        prompting=true;
        BiometricPrompt prompt=new BiometricPrompt(this,ContextCompat.getMainExecutor(this),new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                prompting=false;unlocked=true;
                if(pendingHide>0){long id=pendingHide;pendingHide=-1;confirmHide(id);}else refresh();
            }
            @Override public void onAuthenticationError(int code,@NonNull CharSequence reason) {
                prompting=false;
                if(!unlocked && !isFinishing())Toast.makeText(VaultActivity.this,"الخزنة مقفلة: "+reason,Toast.LENGTH_LONG).show();
            }
        });
        BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder()
            .setTitle("فتح خزنة 3Esnap")
            .setSubtitle("المصادقة الحيوية المطلوبة لعرض ملفاتك المخفية")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("إلغاء").build();
        prompt.authenticate(info);
    }
    private void confirmHide(long id) {
        if(!unlocked)return;
        new AlertDialog.Builder(this).setTitle("إخفاء الملف في الخزنة؟")
            .setMessage("سيُشفّر الملف وتُحذف النسخة العامة بعد نجاح النقل. لو كان الملف مستورداً، قد يرفض موفّر الملفات الحذف ولن نعتبره مخفيًا. حذف بيانات التطبيق أو إلغاء تثبيته يفقد ملفات الخزنة.")
            .setPositiveButton("إخفاء وتشفير",(d,w)->runJob(()->vault.hide(id),"تم نقل الملف إلى الخزنة."))
            .setNegativeButton("إلغاء",(d,w)->refresh()).show();
    }
    private void runJob(ThrowingJob job,String success) {
        if(!unlocked)return;
        items.removeAllViews();items.addView(text("جاري تنفيذ العملية الآمنة…",14));
        app.io.execute(()->{
            String result=success;
            try{job.run();}catch(Exception ex){result="تعذر التنفيذ: "+ex.getMessage();}
            String message=result;
            runOnUiThread(()->{Toast.makeText(this,message,Toast.LENGTH_LONG).show();if(!isFinishing() && unlocked)refresh();});
        });
    }
    private interface ThrowingJob{void run() throws Exception;}
    private void refresh() {
        if(!unlocked || isFinishing())return;
        List<DownloadRecord> records=app.store.hiddenFiles();
        items.removeAllViews();
        if(records.isEmpty()){items.addView(text("الخزنة فارغة. من قائمة أي ملف اختر «إخفاء بالبصمة». ",14));return;}
        for(DownloadRecord r:records){
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(12),dp(12),dp(12));
            card.setBackgroundColor(Color.rgb(18,30,48));
            TextView title=text(r.title,15);title.setMaxLines(2);card.addView(title);
            card.addView(text(r.category==null?"":"التصنيف: "+r.category,11));
            LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER_VERTICAL);
            Button play=button("عرض / تشغيل");play.setOnClickListener(v->preview(r));
            Button show=button("إظهار");show.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("إخراج من الخزنة؟")
                .setMessage("سيعود الملف إلى Download/3Esnap ويظهر في المكتبة العامة.")
                .setPositiveButton("إظهار",(d,w)->runJob(()->vault.show(r.id),"الملف ظاهر في المكتبة الآن."))
                .setNegativeButton("إلغاء",null).show());
            Button delete=button("حذف");delete.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("حذف نهائي؟").setMessage("لا يمكن التراجع عن حذف الملف المشفر.")
                .setPositiveButton("حذف",(d,w)->runJob(()->vault.delete(r.id),"تم الحذف."))
                .setNegativeButton("إلغاء",null).show());
            buttons.addView(play,new LinearLayout.LayoutParams(0,dp(45),1));
            buttons.addView(show,new LinearLayout.LayoutParams(0,dp(45),1));
            buttons.addView(delete,new LinearLayout.LayoutParams(0,dp(45),1));
            card.addView(buttons);items.addView(card);
            View divider=new View(this);divider.setMinimumHeight(dp(7));items.addView(divider);
        }
    }
    private void preview(DownloadRecord r) {
        if(!unlocked)return;
        items.removeAllViews();items.addView(text("جاري فك التشفير للمعاينة داخل التطبيق…",14));
        app.io.execute(()->{
            try {
                File decoded=vault.decrypt(r);
                runOnUiThread(()->{
                    if(!unlocked || isFinishing()){decoded.delete();return;}
                    preview=decoded;openMedia(r,decoded);
                });
            }catch(Exception ex){runOnUiThread(()->{Toast.makeText(this,"تعذر فتح الملف: "+ex.getMessage(),Toast.LENGTH_LONG).show();refresh();});}
        });
    }
    private void openMedia(DownloadRecord r,File file) {
        if(!unlocked)return;
        try {
            if("image/gif".equals(r.mime)){
                ImageView picture=new ImageView(this);picture.setAdjustViewBounds(true);
                Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());picture.setImageBitmap(bitmap);
                new AlertDialog.Builder(this).setTitle(r.title+" (الإطار الأول)")
                    .setView(picture).setPositiveButton("إغلاق",(d,w)->closePreview()).setOnDismissListener(d->closePreview()).show();
            }else if("audio/mpeg".equals(r.mime)) {
                audio=new MediaPlayer();audio.setDataSource(file.getAbsolutePath());audio.prepare();audio.start();
                new AlertDialog.Builder(this).setTitle(r.title).setMessage("تشغيل الصوت من الخزنة.")
                    .setPositiveButton("إغلاق",(d,w)->closePreview()).setOnDismissListener(d->closePreview()).show();
            } else {
                video=new VideoView(this);video.setVideoPath(file.getAbsolutePath());
                video.setMediaController(new MediaController(this));
                new AlertDialog.Builder(this).setTitle(r.title).setView(video)
                    .setPositiveButton("إغلاق",(d,w)->closePreview()).setOnDismissListener(d->closePreview()).show();
                video.start();
            }
        }catch(Exception ex){Toast.makeText(this,"تعذر تشغيل المقطع.",Toast.LENGTH_LONG).show();closePreview();}
    }
    private void closePreview() {
        if(video!=null){video.stopPlayback();video=null;}
        if(audio!=null){audio.release();audio=null;}
        if(preview!=null){preview.delete();preview=null;}
        if(unlocked && !isFinishing())refresh();
    }
    @Override protected void onStart(){super.onStart();if(items!=null && !unlocked && !prompting)getWindow().getDecorView().post(this::authenticate);}
    @Override protected void onStop() {
        unlocked=false;closePreview();items.removeAllViews();items.addView(text("الخزنة مقفلة.",14));super.onStop();
    }
    @Override protected void onDestroy(){closePreview();super.onDestroy();}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(12);return b;}
    private TextView text(String label,int size){TextView t=new TextView(this);t.setText(label);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(dp(5),dp(8),dp(5),dp(8));return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
