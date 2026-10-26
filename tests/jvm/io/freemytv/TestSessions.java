package io.freemytv;
import java.util.Collections;
import org.json.*;
/** Fixtures go through the production selector, including package/version provenance. */
final class TestSessions {
 static EmbyPlayerSession.Session tvAlias(String id,String user)throws Exception {
  JSONObject profile=row("native-profile","AndroidTv",EmbyNativePlayer.tvVersion,user).put("SupportsRemoteControl",false);
  JSONObject control=row(id,"Emby for Android",EmbyNativePlayer.tvVersion,"startup-user");
  return EmbyPlayerSession.select(new JSONArray().put(profile).put(control),Collections.singleton("127.0.0.1"),"",
   System.currentTimeMillis(),EmbyNativePlayer.TV,EmbyNativePlayer.tvVersion,true);
 }
 static JSONObject row(String id,String client,String version,String user){
  return new JSONObject().put("Id",id).put("UserId",user).put("DeviceId","test-device").put("Client",client)
   .put("ApplicationVersion",version).put("RemoteEndPoint","127.0.0.1").put("LastActivityDate",java.time.Instant.now().toString())
   .put("SupportsRemoteControl",true).put("PlayableMediaTypes",new JSONArray().put("Video"));
 }
 static EmbyPlayerSession.Session player(String pkg,String id,String user)throws Exception {
  JSONObject o=new JSONObject().put("Id",id).put("UserId",user).put("DeviceId","test-device")
   .put("Client",EmbyNativePlayer.TV.equals(pkg)?"AndroidTv":"Android")
   .put("ApplicationVersion",EmbyNativePlayer.TV.equals(pkg)?EmbyNativePlayer.tvVersion:EmbyNativePlayer.androidVersion)
   .put("RemoteEndPoint","127.0.0.1").put("LastActivityDate",java.time.Instant.now().toString())
   .put("SupportsRemoteControl",true).put("PlayableMediaTypes",new JSONArray().put("Video"));
  return EmbyPlayerSession.select(new JSONArray().put(o),Collections.singleton("127.0.0.1"),"",System.currentTimeMillis(),pkg,
    EmbyNativePlayer.TV.equals(pkg)?EmbyNativePlayer.tvVersion:EmbyNativePlayer.androidVersion);
 }
}
