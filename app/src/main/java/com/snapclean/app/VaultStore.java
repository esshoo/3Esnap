package com.snapclean.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Files encrypted at rest in app-private storage. UI access is gated by BiometricPrompt.
 * This key is NOT biometric-auth-bound; the gate is enforced by VaultActivity.
 * Uninstalling the app/clearing its data destroys the key and content.
 */
public final class VaultStore {
    private static final String ALIAS="3Esnap-private-vault-v1";
    private static final byte[] MAGIC={'3','E','V','1'};
    private final Context c;
    private final DownloadStore db;
    private final MediaFiles files;
    public VaultStore(Context context,DownloadStore store,MediaFiles media) {
        c=context.getApplicationContext();db=store;files=media;
    }
    private File dir() {return new File(c.getFilesDir(),"private-vault");}
    public File encryptedFile(long id){return new File(dir(),id+".vlt");}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            generator.generateKey();
        }
        KeyStore.SecretKeyEntry entry=(KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null);
        if(entry==null)throw new IOException("لم أجد مفتاح الخزنة؛ ربما تغيرت بيانات التطبيق.");
        return entry.getSecretKey();
    }
    private void encrypt(Uri uri,File target) throws Exception {
        File parent=target.getParentFile();if(parent==null || (!parent.exists()&&!parent.mkdirs()))throw new IOException("تعذر إنشاء الخزنة.");
        File pending=new File(parent,target.getName()+".pending");
        if(pending.exists())pending.delete();
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));
        boolean done=false;
        try(InputStream in=c.getContentResolver().openInputStream(uri);
            FileOutputStream out=new FileOutputStream(pending)) {
            if(in==null)throw new IOException("تعذر قراءة الملف الأصلي.");
            out.write(MAGIC);out.write(iv);
            try(CipherOutputStream encrypted=new CipherOutputStream(out,cipher)) {
                byte[] bytes=new byte[65536];int n;
                while((n=in.read(bytes))!=-1)encrypted.write(bytes,0,n);
            }
            done=true;
        } finally {if(!done)pending.delete();}
        if(target.exists())throw new IOException("الملف موجود بالفعل في الخزنة.");
        if(!pending.renameTo(target)) {pending.delete();throw new IOException("تعذر إكمال الملف المشفّر.");}
    }
    public File decrypt(DownloadRecord record) throws Exception {
        if(record==null || !record.hidden || !encryptedFile(record.id).isFile())throw new IOException("الملف المخفي غير موجود.");
        File target=new File(c.getCacheDir(),"vault-preview/"+record.id+"-"+System.nanoTime()+extension(record));
        File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("تعذر تجهيز المعاينة.");
        boolean done=false;
        try(DataInputStream raw=new DataInputStream(new FileInputStream(encryptedFile(record.id)));
            OutputStream out=new FileOutputStream(target)) {
            byte[] magic=new byte[4];raw.readFully(magic);
            if(!java.util.Arrays.equals(magic,MAGIC))throw new IOException("تنسيق الخزنة غير معروف.");
            byte[] iv=new byte[12];raw.readFully(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            // Explicit doFinal() is essential: CipherInputStream may swallow GCM
            // authentication failures on some providers. Never publish tampered data.
            byte[] b=new byte[65536];int n;
            while((n=raw.read(b))!=-1) {
                byte[] plain=cipher.update(b,0,n);
                if(plain!=null && plain.length>0)out.write(plain);
            }
            byte[] last=cipher.doFinal(); // throws AEADBadTagException on corruption/tampering
            if(last!=null && last.length>0)out.write(last);
            out.flush();done=true;return target;
        } finally {if(!done)target.delete();}
    }
    private String extension(DownloadRecord r){
        String name=r.filename==null?"":r.filename;
        int dot=name.lastIndexOf('.');
        if(dot>=0 && dot<name.length()-1) {
            String ext=name.substring(dot+1).toLowerCase(java.util.Locale.ROOT);
            if(ext.matches("mp4|mp3|gif|m4a|m4v|aac|wav|flac|ogg|opus|webm|mkv|mov|3gp|3g2|avi|wma|amr"))
                return "."+ext;
        }
        if("image/gif".equals(r.mime))return ".gif";
        if("audio/mp4".equals(r.mime))return ".m4a";
        if("audio/wav".equals(r.mime)||"audio/x-wav".equals(r.mime))return ".wav";
        if("audio/ogg".equals(r.mime))return ".ogg";
        if("video/webm".equals(r.mime))return ".webm";
        if("video/quicktime".equals(r.mime))return ".mov";
        if("audio/mpeg".equals(r.mime))return ".mp3";
        return ".mp4";
    }
    /** Hide, then remove the public original. Imported SAF documents must allow deletion. */
    public void hide(long id) throws Exception {
        DownloadRecord r=db.get(id);
        if(r==null || r.hidden || !"completed".equals(r.status) || !files.exists(r))throw new IOException("الملف غير متاح للإخفاء.");
        if(r.size>0 && dir().getUsableSpace() < r.size+16L*1024*1024)throw new IOException("لا توجد مساحة كافية لتشفير نسخة داخلية.");
        File vault=encryptedFile(id);boolean removedPublic=false;
        try {
            encrypt(Uri.parse(r.uri),vault);
            if(r.imported) {
                Uri original=Uri.parse(r.uri);
                if(!DocumentsContract.isDocumentUri(c,original) || !DocumentsContract.deleteDocument(c.getContentResolver(),original))
                    throw new IOException("الملف مستورد: مزود الملفات رفض حذف الأصل. لن نعتبره مخفيًا.");
            } else files.deleteOwned(r);
            removedPublic=true;
            if(!db.setHidden(id,true,"vault://"+id,false))throw new IOException("تعذر تحديث حالة الملف؛ يوجد ملف مشفر للاسترداد.");
            files.thumbnailFile(id).delete();
            SnapCleanApp.of(c).changed();
        } finally {
            // Preserve the encrypted copy if the public original was already removed;
            // an interrupted DB update can be recovered by repairIncomplete().
            if(!removedPublic)vault.delete();
        }
    }
    /** Recover power-loss window after public source deletion but before DB update. */
    public void repairIncomplete() {
        for(DownloadRecord r:db.all()) {
            if(r.hidden)continue;
            File vault=encryptedFile(r.id);
            if(!vault.isFile())continue;
            if(files.exists(r))vault.delete();
            else if(db.setHidden(r.id,true,"vault://"+r.id,false))files.thumbnailFile(r.id).delete();
        }
    }
    /** Restore as a new public media file before removing encrypted original. */
    public void show(long id) throws Exception {
        DownloadRecord r=db.get(id);
        if(r==null || !r.hidden)throw new IOException("هذا الملف ليس في الخزنة.");
        File preview=decrypt(r);
        MediaFiles.Saved output=null;boolean updated=false;
        try {
            String extension=extension(r).substring(1);
            String filename=DownloadPolicy.fileName(r.title,extension,System.currentTimeMillis());
            output=files.publish(preview,filename,r.mime,()->false);
            if(!db.reveal(id,output))throw new IOException("تعذر تحديث المكتبة بعد إخراج الملف.");
            updated=true;
            File encrypted=encryptedFile(id);encrypted.delete();
            files.createThumbnail(db.get(id));SnapCleanApp.of(c).changed();
        } finally {
            preview.delete();
            if(output!=null && !updated)files.discard(output);
        }
    }
    public void delete(long id) throws IOException {
        DownloadRecord r=db.get(id);if(r==null || !r.hidden)return;
        File f=encryptedFile(id);
        if(f.isFile() && !f.delete())throw new IOException("تعذر حذف الملف المشفر.");
        db.remove(id);files.thumbnailFile(id).delete();SnapCleanApp.of(c).changed();
    }
    public void cleanPreviews(){
        File p=new File(c.getCacheDir(),"vault-preview");MediaFiles.deleteTree(p);
    }
}
