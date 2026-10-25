package io.freemytv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/** Full-resolution charcoal light fields and analytic frosted glass, with no enlarged texture or blur pass. */
final class ClassicSettingsArt {
    private static final long STEP_MS = 125;
    private static final String PROGRAM =
        "uniform float2 res; uniform float t;\n" +
        "float blob(float2 p, float2 center, float2 variance, float frost) {\n" +
        " float2 v = variance + frost; float2 d = p-center;\n" +
        " return exp(-dot(d*d, 1.0/v))*sqrt((variance.x*variance.y)/(v.x*v.y)); }\n" +
        "float3 sky(float2 p, float frost) {\n" +
        " float s=t*0.035;\n" +
        " float a=blob(p,float2(0.24+0.07*sin(s),0.43+0.04*cos(s*0.8)),float2(0.105,0.18),frost);\n" +
        " float b=blob(p,float2(0.64+0.06*cos(s*0.7),0.75+0.05*sin(s*0.6)),float2(0.14,0.20),frost);\n" +
        " float c=blob(p,float2(0.76+0.05*sin(s*0.5),0.18),float2(0.12,0.16),frost);\n" +
        " float3 base=float3(0.061,0.078,0.102);\n" +
        " return base + a*float3(0.055,0.103,0.140) + b*float3(0.025,0.064,0.067) + c*float3(0.045,0.036,0.073); }\n" +
        "float rounded(float2 p,float2 center,float2 halfSize,float radius) {\n" +
        " float2 q=abs(p-center)-halfSize+radius; return length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius; }\n" +
        "half4 main(float2 xy) {\n" +
        " float k=res.x/1920.0; float2 p=xy/res;\n" +
        " float2 center=float2(1412.0*k,(82.0*k+res.y-16.0*k)*0.5);\n" +
        " float2 halfSize=float2(452.0*k,(res.y-98.0*k)*0.5);\n" +
        " float d=rounded(xy,center,halfSize,36.0*k);\n" +
        " float3 col=sky(p,0.0);\n" +
        " float shade=exp(-max(d,0.0)/(22.0*k))*0.15; col*=1.0-shade;\n" +
        " if(d<1.0) {\n" +
        "  float2 normal=(xy-center)/halfSize;\n" +
        "  float bevel=exp(-abs(d)/(8.0*k));\n" +
        "  float3 frost=sky(p+normal*bevel*0.004,0.018);\n" +
        "  float3 glass=mix(frost,float3(0.105,0.133,0.166),0.48);\n" +
        "  glass+=float3(0.024,0.029,0.036)*(1.0-p.y);\n" +
        "  float rim=exp(-abs(d)/(0.9*k));\n" +
        "  glass+=rim*float3(0.105,0.125,0.145)*(0.54+0.46*(1.0-p.y));\n" +
        "  col=mix(col,glass,1.0-smoothstep(-0.65,0.65,d)); }\n" +
        // Screen-space sub-level quantization dithering: fixed in time and never scaled into visible grains.
        " float q=fract(52.9829189*fract(dot(floor(xy),float2(0.06711056,0.00583715))))-0.5;\n" +
        " return half4(col+q*(0.75/255.0),1.0); }\n";
    private final Recording layer = Recording.create("classic-settings-native");
    private final Paint paint = new Paint(), layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG), fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RuntimeShader shader;
    private int width, height;
    private long tick = -1, origin;
    ClassicSettingsArt() {
        try { shader = new RuntimeShader(PROGRAM); paint.setShader(shader); }
        catch (RuntimeException | LinkageError e) { AppLog.w("Classic settings shader unavailable: " + e.getMessage()); }
        layer.setUseCompositingLayer(true, layerPaint);
        fallback.setDither(true);
    }
    void setSize(int width, int height) {
        this.width=width; this.height=height; tick=-1;
        layer.setPosition(0,0,width,height);
        if(shader!=null) shader.setFloatUniform("res",width,height);
        fallback.setShader(new LinearGradient(0,0,width,height,new int[]{0xff18232e,0xff263b49,0xff17212c},null,Shader.TileMode.CLAMP));
    }
    String status() { return (shader != null ? "native" : "fallback") + " " + width + "x" + height; }
    void resume() { tick=-1; }
    boolean needsFrame(long now) { return shader!=null && tick!=now/STEP_MS; }
    void draw(Canvas canvas,long now) {
        if(width<=0 || height<=0) return;
        if(shader==null) {
            canvas.drawRect(0,0,width,height,fallback);
            float k=width/1920f;
            paint.setShader(null);paint.setColor(0xB3232F3C);
            canvas.drawRoundRect(960*k,82*k,1864*k,height-16*k,36*k,36*k,paint);
            return;
        }
        if(tick!=now/STEP_MS || !layer.hasContent()) {
            if(origin==0) origin=now;
            tick=now/STEP_MS;
            shader.setFloatUniform("t",(now-origin)/1000f);
            Canvas recorded=layer.beginRecording(width,height);
            try { recorded.drawRect(0,0,width,height,paint); } finally { layer.endRecording(); }
        }
        canvas.drawColor(0xff151d27); layer.draw(canvas);
    }
}
