package io.freemytv;
import android.content.Context;
/** Test-only package lookup; transport and HTTP classes are production code. */
final class EmbyNativePlayer {
 static final String TV="tv.emby.embyatv", ANDROID="com.mb.android";
 static String player=TV; static boolean other; static String tvVersion="2.5.03g", androidVersion="3.5.35";
 static String preferred(Context c){return player;}
 static boolean hasOtherEnabled(Context c,String p){return other;}
 static String other(String p){return TV.equals(p)?ANDROID:TV;}
 static String version(Context c,String p){return TV.equals(p)?tvVersion:androidVersion;}
}
