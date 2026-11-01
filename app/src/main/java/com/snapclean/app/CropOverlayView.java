package com.snapclean.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Crop rectangle over the actual fitted video, with move and lower-corner resize gestures. */
public final class CropOverlayView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF picture=new RectF(),selected=new RectF();
    private int sourceWidth=16,sourceHeight=9;
    private float x=.08f,y=.08f,w=.84f,h=.84f,aspect=0;
    private boolean active;
    private int gesture=0;
    private float lastX,lastY;
    private final float handleRadius;

    public CropOverlayView(Context context) { super(context);handleRadius=21f*getResources().getDisplayMetrics().density;setLayerType(View.LAYER_TYPE_SOFTWARE,null); }
    public void setVideoSize(int width,int height) {sourceWidth=Math.max(16,width);sourceHeight=Math.max(16,height);invalidate();}
    public void setActive(boolean enabled) {active=enabled;setVisibility(enabled?VISIBLE:GONE);invalidate();}
    public boolean isActive() {return active;}
    public void reset() {x=.08f;y=.08f;w=.84f;h=.84f;setAspect(aspect);}
    /** 0 for free, otherwise target display aspect ratio W/H. */
    public void setAspect(float requested) {
        aspect=requested;
        if(requested>0) {
            float frameRatio=(float)sourceWidth/sourceHeight;
            float maxW=.84f,maxH=.84f;
            float width=Math.min(maxW,maxH*requested/frameRatio);
            float height=width*frameRatio/requested;
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
        canvas.drawCircle(selected.right,selected.bottom,r+2,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!active)return false;
        measurePicture();
        float px=event.getX(),py=event.getY();
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if(Math.abs(px-selected.right)<handleRadius && Math.abs(py-selected.bottom)<handleRadius) gesture=2;
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
                    float newW=clamp(w+dx,.1f,1-x),newH=clamp(h+dy,.1f,1-y);
                    if(aspect>0) {
                        float frame=(float)sourceWidth/sourceHeight;
                        newH=newW*frame/aspect;
                        if(newH>1-y) {newH=1-y;newW=newH*aspect/frame;}
                        if(newH<.10f || newW<.10f) {lastX=px;lastY=py;return true;}
                    }
                    w=newW;h=newH;
                }
                lastX=px;lastY=py;invalidate();return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                gesture=0;getParent().requestDisallowInterceptTouchEvent(false);return true;
        }
        return false;
    }
    private static float clamp(float a,float lo,float hi) {return Math.max(lo,Math.min(hi,a));}
}
