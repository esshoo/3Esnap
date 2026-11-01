package com.snapclean.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Interactive crop on a still video frame, fitted exactly like the ImageView preview. */
public final class CropOverlayView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF picture=new RectF(),selected=new RectF();
    private int sourceWidth=16,sourceHeight=9;
    private float x=.08f,y=.08f,w=.84f,h=.84f,aspect=0;
    private boolean active;
    // 1: move; 2 TL, 3 TR, 4 BL, 5 BR
    private int gesture=0;
    private float lastX,lastY;
    private final float handleRadius;

    public CropOverlayView(Context context) { super(context);handleRadius=27f*getResources().getDisplayMetrics().density; }
    public void setVideoSize(int width,int height) {sourceWidth=Math.max(16,width);sourceHeight=Math.max(16,height);invalidate();}
    public void setActive(boolean enabled) {active=enabled;setVisibility(enabled?VISIBLE:GONE);invalidate();}
    public boolean isActive() {return active;}
    public void reset() {x=.08f;y=.08f;w=.84f;h=.84f;setAspect(aspect);}
    public void setSelection(EditPlan.Crop saved) {
        if(saved==null)return;
        x=(float)saved.x/sourceWidth;y=(float)saved.y/sourceHeight;
        w=(float)saved.width/sourceWidth;h=(float)saved.height/sourceHeight;
        aspect=0;invalidate();
    }
    /** 0 for free, otherwise target DISPLAY aspect ratio W/H. */
    public void setAspect(float requested) {
        aspect=requested;
        if(requested>0) {
            float sourceRatio=(float)sourceWidth/sourceHeight;
            float width=Math.min(.84f,.84f*requested/sourceRatio);
            float height=width*sourceRatio/requested;
            w=width;h=height;x=(1f-w)/2f;y=(1f-h)/2f;
        }
        invalidate();
    }
    public EditPlan.Crop selectedPixels() {return EditPlan.crop(x,y,w,h,sourceWidth,sourceHeight);}
    private void measurePicture() {
        float vw=getWidth(),vh=getHeight();
        float frame=(float)sourceWidth/sourceHeight;
        float aw=vw,ah=aw/frame;
        if(ah>vh) {ah=vh;aw=ah*frame;}
        picture.set((vw-aw)/2f,(vh-ah)/2f,(vw+aw)/2f,(vh+ah)/2f);
        selected.set(picture.left+x*aw,picture.top+y*ah,picture.left+(x+w)*aw,picture.top+(y+h)*ah);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);if(!active||getWidth()==0||getHeight()==0)return;
        measurePicture();
        Path outside=new Path();outside.setFillType(Path.FillType.EVEN_ODD);
        outside.addRect(picture,Path.Direction.CW);outside.addRect(selected,Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xb807101c);canvas.drawPath(outside,paint);
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f*getResources().getDisplayMetrics().density);
        canvas.drawRect(selected,paint);
        paint.setStrokeWidth(1f*getResources().getDisplayMetrics().density);paint.setColor(0xaaffffff);
        for(int i=1;i<=2;i++) {
            float u=i/3f;
            canvas.drawLine(selected.left+selected.width()*u,selected.top,selected.left+selected.width()*u,selected.bottom,paint);
            canvas.drawLine(selected.left,selected.top+selected.height()*u,selected.right,selected.top+selected.height()*u,paint);
        }
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xff55d5ef);
        float r=6f*getResources().getDisplayMetrics().density;
        canvas.drawCircle(selected.left,selected.top,r,paint);
        canvas.drawCircle(selected.right,selected.top,r,paint);
        canvas.drawCircle(selected.left,selected.bottom,r,paint);
        canvas.drawCircle(selected.right,selected.bottom,r,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!active)return false;
        measurePicture();
        float px=event.getX(),py=event.getY();
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if(near(px,py,selected.left,selected.top))gesture=2;
                else if(near(px,py,selected.right,selected.top))gesture=3;
                else if(near(px,py,selected.left,selected.bottom))gesture=4;
                else if(near(px,py,selected.right,selected.bottom))gesture=5;
                else if(selected.contains(px,py))gesture=1;
                else return false;
                lastX=px;lastY=py;getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(gesture==0)return false;
                float dx=(px-lastX)/Math.max(1,picture.width());
                float dy=(py-lastY)/Math.max(1,picture.height());
                if(gesture==1) {
                    x=clamp(x+dx,0,1-w);y=clamp(y+dy,0,1-h);
                } else {
                    boolean left=gesture==2||gesture==4;
                    boolean top=gesture==2||gesture==3;
                    float oppositeX=left?x+w:x;
                    float oppositeY=top?y+h:y;
                    float signedDx=left?-dx:dx,signedDy=top?-dy:dy;
                    float newW=w+signedDx,newH=h+signedDy;
                    if(aspect>0) {
                        float heightPerWidth=((float)sourceWidth/sourceHeight)/aspect;
                        if(Math.abs(signedDx)>=Math.abs(signedDy)) newH=newW*heightPerWidth;
                        else newW=newH/heightPerWidth;
                        float maxW=left?oppositeX:1-oppositeX;
                        float maxH=top?oppositeY:1-oppositeY;
                        float lower=Math.max(.10f,.10f/heightPerWidth);
                        float upper=Math.min(maxW,maxH/heightPerWidth);
                        if(upper<lower) {lastX=px;lastY=py;return true;}
                        newW=clamp(newW,lower,upper);newH=newW*heightPerWidth;
                    } else {
                        newW=clamp(newW,.10f,left?oppositeX:1-oppositeX);
                        newH=clamp(newH,.10f,top?oppositeY:1-oppositeY);
                    }
                    x=left?oppositeX-newW:oppositeX;
                    y=top?oppositeY-newH:oppositeY;
                    w=newW;h=newH;
                }
                lastX=px;lastY=py;invalidate();return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                gesture=0;getParent().requestDisallowInterceptTouchEvent(false);return true;
        }
        return false;
    }
    private boolean near(float x,float y,float ax,float ay) {return Math.abs(x-ax)<=handleRadius && Math.abs(y-ay)<=handleRadius;}
    private static float clamp(float a,float lo,float hi) {return Math.max(lo,Math.min(hi,a));}
}
