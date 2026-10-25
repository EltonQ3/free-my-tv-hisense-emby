package io.freemytv;
import android.content.Context;
import org.json.JSONObject;
final class EmbyRecs {
 interface Request { String get(JSONObject config,String path) throws Exception; }
 static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9-]{1,128}"); }
 static JSONObject config;
 static Request fixture;
 static JSONObject readConfig(Context c) { return new JSONObject(config.toString()); }
 static String request(JSONObject c,String path) throws Exception { return fixture.get(c,path); }
}
