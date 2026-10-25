package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sharp character art and a separately prefiltered glass surface. No blur work runs on a frame. */
final class IllustratedSettingsArt {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Map<String,Asset> CACHE=new LinkedHashMap<String,Asset>(4,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Asset> e) { return size()>2; }
    };
    private static final class Asset {
        final Bitmap image,frost;
        Asset(Bitmap image,Bitmap frost) { this.image=image; this.frost=frost; }
    }
    private final Paint imagePaint=new Paint(Paint.FILTER_BITMAP_FLAG), frostPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),
            tint=new Paint(Paint.ANTI_ALIAS_FLAG),rim=new Paint(Paint.ANTI_ALIAS_FLAG),shadow=new Paint(Paint.ANTI_ALIAS_FLAG),layerPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF frame=new RectF(),panel=new RectF(),shadowBox=new RectF(),innerPanel=new RectF();
    private final Paint innerRim=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix frostMatrix=new Matrix();
    private final Recording shadowLayer=Recording.create("illustrated-settings-shadow");
    private SettingsTheme theme=SettingsTheme.OATH;
    private Asset asset;
    private int width,height,generation;
    private long readyAt;
    private String loaded="";

    void setSize(int width,int height) { this.width=width;this.height=height;configure(); }
    private void configure() {
        if(width<=0||height<=0)return;
        float k=width/1920f;
        frame.set(0,0,width,height);panel.set(960*k,82*k,1864*k,height-16*k);
        boolean warm=theme==SettingsTheme.TWILIGHT, cyber=theme==SettingsTheme.LUCY, moon=theme==SettingsTheme.MAHOYO;
        tint.setDither(true);
        tint.setShader(new LinearGradient(0,panel.top,0,panel.bottom,moon?0xA6E7F0FC:cyber?0xA3DCE2F4:warm?0xC5FFF1EA:0x2CBF984A,moon?0xB2DDEAF7:cyber?0xA8E6DEF3:warm?0xAEFFF3DE:0x492C2112,Shader.TileMode.CLAMP));
        rim.setStyle(Paint.Style.STROKE);rim.setStrokeWidth(1.25f*k);rim.setDither(true);
        rim.setShader(new LinearGradient(panel.left,panel.top,panel.right,panel.bottom,moon?0xC9F3FAFF:cyber?0xB875EEF1:warm?0x9CFFF1DB:0xDCE8C878,moon?0x7CB1CBE3:cyber?0xB8DB8EEB:warm?0x66BD8E7B:0x70B68C46,Shader.TileMode.CLAMP));
        shadow.setColor(moon?0x3220315A:cyber?0x4217082F:warm?0x341B1024:0x3A24190A);shadow.setMaskFilter(new BlurMaskFilter((moon?22:warm?26:20)*k,BlurMaskFilter.Blur.NORMAL));
        innerPanel.set(panel);innerPanel.inset(2.5f*k,2.5f*k);
        innerRim.setStyle(Paint.Style.STROKE);innerRim.setStrokeWidth(k);innerRim.setColor(moon?0x45FFFFFF:cyber?0x5AEDE9FF:0x3DE4CA8B);
        recordShadow();prepareFrost();
    }
    private float radius() { return (theme==SettingsTheme.MAHOYO?36:theme==SettingsTheme.LUCY?18:theme==SettingsTheme.TWILIGHT?44:32)*width/1920f; }
    private void recordShadow() {
        if(width<=0||height<=0)return;
        float k=width/1920f;
        shadowLayer.setPosition(0,0,width,height);shadowLayer.setUseCompositingLayer(true,layerPaint);
        shadowBox.set(panel);shadowBox.offset(0,10*k);
        Canvas c=shadowLayer.beginRecording(width,height);
        try { c.drawRoundRect(shadowBox,radius(),radius(),shadow); } finally { shadowLayer.endRecording(); }
    }
    private void prepareFrost() {
        if(asset==null||width<=0||height<=0) { frostPaint.setShader(null);return; }
        BitmapShader shader=new BitmapShader(asset.frost,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        frostMatrix.setScale((float)width/asset.frost.getWidth(),(float)height/asset.frost.getHeight());
        shader.setLocalMatrix(frostMatrix);frostPaint.setShader(shader);
        frostPaint.setAlpha(theme==SettingsTheme.OATH?160:255);
    }
    void load(Context context,SettingsTheme theme,Runnable changed) {
        final int request=++generation,w=width,h=height;
        this.theme=theme;
        String cacheKey=theme.key+"@"+w+"x"+h;
        if(!loaded.equals(cacheKey)) { asset=null;loaded=cacheKey; }
        configure();
        Asset hit; synchronized(CACHE) { hit=CACHE.get(cacheKey); }
        if(hit!=null) { asset=hit;readyAt=0;prepareFrost();changed.run();return; }
        Context app=context.getApplicationContext();
        IO.execute(()-> {
            Asset made=null;
            try {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream in=app.getAssets().open(theme.asset)) { BitmapFactory.decodeStream(in,null,bounds); }
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(bounds.outWidth/(options.inSampleSize*2)>=w&&bounds.outHeight/(options.inSampleSize*2)>=h)options.inSampleSize*=2;
                Bitmap full;
                try(InputStream in=app.getAssets().open(theme.asset)) { full=BitmapFactory.decodeStream(in,null,options); }
                if(full!=null) {
                    Bitmap frost=theme==SettingsTheme.MAHOYO?Backdrop.settingsFrost(full,w,h,6,2):theme==SettingsTheme.LUCY?Backdrop.settingsFrost(full,w,h,8,2):theme==SettingsTheme.OATH?Backdrop.settingsFrost(full,w,h,3,1):Backdrop.settingsFrost(full,w,h);
                    made=new Asset(Backdrop.gpu(full),frost);
                    synchronized(CACHE) { CACHE.put(cacheKey,made); }
                }
            } catch(Exception e) { AppLog.w("Illustrated settings artwork unavailable: "+theme.key); }
            final Asset result=made;
            MAIN.post(()-> {
                if(request!=generation)return;
                asset=result;readyAt=SystemClock.uptimeMillis();prepareFrost();changed.run();
            });
        });
    }
    void pause() { generation++; }
    boolean animating(long now) { return asset!=null&&readyAt!=0&&now-readyAt<260; }
    String status() { return theme.key+(asset==null?" pending":" image "+asset.image.getWidth()+"x"+asset.image.getHeight()+" cached-frost"); }
    void draw(Canvas canvas,long now) {
        canvas.drawColor(theme==SettingsTheme.MAHOYO?0xff07152e:theme==SettingsTheme.LUCY?0xff172136:theme==SettingsTheme.TWILIGHT?0xff241c31:0xff111a2c);
        if(width<=0||height<=0)return;
        if(asset!=null) {
            imagePaint.setAlpha(readyAt==0?255:Math.round(255*Math.min(1f,(now-readyAt)/260f)));
            canvas.drawBitmap(asset.image,null,frame,imagePaint);
        }
        if(!shadowLayer.hasContent())recordShadow();
        shadowLayer.draw(canvas);
        float radius=radius();
        if(asset!=null)canvas.drawRoundRect(panel,radius,radius,frostPaint);
        // Essential contrast and edges remain direct geometry even after a stopped window loses its GPU layers.
        canvas.drawRoundRect(panel,radius,radius,tint);canvas.drawRoundRect(panel,radius,radius,rim);
        if(theme==SettingsTheme.OATH||theme==SettingsTheme.LUCY||theme==SettingsTheme.MAHOYO)canvas.drawRoundRect(innerPanel,radius-2.5f*width/1920f,radius-2.5f*width/1920f,innerRim);
    }
}
