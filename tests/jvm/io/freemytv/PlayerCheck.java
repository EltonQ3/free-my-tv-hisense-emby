package io.freemytv;
import java.util.*;
import org.json.*;
public final class PlayerCheck {
 static int checks;
 static long now=1792815000000L;
 static Set<String> local=new HashSet<>(Arrays.asList("192.168.1.20"));
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static JSONObject session(String id,String device,String user,String ip){return new JSONObject().put("Id",id)
  .put("DeviceId",device).put("UserId",user).put("Client","Android").put("RemoteEndPoint",ip)
  .put("LastActivityDate",java.time.Instant.ofEpochMilli(now).toString()).put("SupportsRemoteControl",true)
  .put("PlayableMediaTypes",new JSONArray().put("Video"));}
 static EmbyPlayerSession.Session pick(JSONArray rows,String pin)throws Exception{return EmbyPlayerSession.select(rows,local,pin,now);}
 static void reject(EmbyPlayerSession.Session s,String user,String item,long ticks)throws Exception{
  try{EmbyPlayerSession.playPath(s,user,item,ticks);throw new AssertionError("unsafe dispatch allowed");}catch(java.io.IOException expected){checks++;}
 }
 public static void main(String[]args)throws Exception{
  JSONObject tv=session("session-a","tv-a","user-a","192.168.1.20:55432");
  JSONObject other=session("session-b","phone-a","user-a","192.168.1.99");
  EmbyPlayerSession.Session s=pick(new JSONArray().put(other).put(tv),"");
  check(s!=null&&s.device.equals("tv-a"),"never control another device with the same account");
  check(EmbyPlayerSession.playPath(s,"user-a","movie-a",10619720000L).endsWith("StartPositionTicks=10619720000"),"100 ns ticks retain millisecond precision without unit conversion");
  JSONObject body=EmbyPlayerSession.playBody(s,"user-a","movie-a",10619720000L);
  check(body.getLong("StartPositionTicks")==10619720000L,"JSON and URL carry the same exact starting ticks");
  check(body.getJSONArray("ItemIds").length()==1&&body.getJSONArray("ItemIds").getString(0).equals("movie-a"),"only the requested item is dispatched");
  check(body.getString("ControllingUserId").equals("user-a"),"controller account explicit in play request");
  check(EmbyPlayerSession.playPath(s,"user-a","ep-a",0).contains("ItemIds=ep-a&PlayCommand=PlayNow"),"zero is explicit only for genuinely new content");
  reject(s,"user-b","movie-a",1);reject(s,"user-a","movie/a",1);reject(s,"user-a","",0);reject(s,"user-a","movie-a",-1);reject(null,"user-a","movie-a",1);
  check(pick(new JSONArray().put(other),"")==null,"no matching TV is not a fallback to any active device");
  check(pick(new JSONArray().put(other),"tv-a")==null,"pin never changes to another device");
  check(pick(new JSONArray().put(other).put(tv),"tv-a").device.equals("tv-a"),"paired device identity stays exact");
  try{pick(new JSONArray().put(tv).put(session("second","tv-b","user-a","192.168.1.20")),"");throw new AssertionError("ambiguous");}catch(java.io.IOException expected){checks++;}
  check(pick(new JSONArray().put(new JSONObject(tv.toString()).put("Client","Chrome")),"")==null,"browser session rejected");
  check(pick(new JSONArray().put(new JSONObject(tv.toString()).put("SupportsRemoteControl",false).put("LastActivityDate",java.time.Instant.ofEpochMilli(now-91000).toString())),"")==null,"stale disconnected session rejected");
  check(pick(new JSONArray().put(new JSONObject(tv.toString()).put("LastActivityDate",java.time.Instant.ofEpochMilli(now-600000).toString())),"")!=null,"connected cached Emby page remains controllable without recent HTTP activity");
  check(pick(new JSONArray().put(new JSONObject(tv.toString()).put("LastActivityDate","invalid")),"")==null,"malformed session time rejected");
  check(pick(new JSONArray().put(new JSONObject(tv.toString()).put("LastActivityDate",java.time.Instant.ofEpochMilli(now+61000).toString())),"")==null,"invalid future time rejected");
  reject(pick(new JSONArray().put(new JSONObject(tv.toString()).put("SupportsRemoteControl",false)),""),"user-a","movie-a",1);
  reject(pick(new JSONArray().put(new JSONObject(tv.toString()).put("PlayableMediaTypes",new JSONArray().put("Audio"))),""),"user-a","movie-a",1);
  check(EmbyPlayerSession.ip("example.invalid").isEmpty(),"remote endpoint parser performs no hostname lookup");
  check(EmbyPlayerSession.ip("[::ffff:192.168.1.20]:1234").equals("192.168.1.20"),"IPv4 mapped IPv6 normalizes correctly");
  JSONObject before=new JSONObject().put("server_url","http://192.168.1.2/emby").put("server_id","s").put("api_key","dummy").put("user_id","user-a");
  check(EmbyPlayerSession.sameConnection(before,new JSONObject(before.toString())),"same connection accepted");
  for(String key:new String[]{"server_url","server_id","user_id","api_key"})check(!EmbyPlayerSession.sameConnection(before,new JSONObject(before.toString()).put(key,"changed")),"stale connection rejected: "+key);
  EmbyRecs.config=before;final String[] requested={null};
  EmbyRecs.fixture=(c,path)->{requested[0]=path;return new JSONObject().put("Id","movie-a").put("Type","Movie").put("UserData",new JSONObject().put("PlaybackPositionTicks",9106750000L)).toString();};
  EmbyPlayback.Target t=EmbyPlayback.forItem(new android.content.Context(),"movie-a","",false);
  check(t.episode.position==9106750000L,"movie progress is refreshed from the selected user at click time");
  check(requested[0].startsWith("/Users/user-a/Items/movie-a?"),"per-user exact-item lookup");
  EmbyRecs.fixture=(c,path)->new JSONObject().put("Id","movie-a").put("Type","Movie").toString();
  try{EmbyPlayback.forItem(new android.content.Context(),"movie-a","",false);throw new AssertionError("missing progress must not become zero");}catch(java.io.IOException expected){checks++;}
  EmbyRecs.fixture=(c,path)->new JSONObject().put("Id","another").put("Type","Movie").put("UserData",new JSONObject()).toString();
  try{EmbyPlayback.forItem(new android.content.Context(),"movie-a","",false);throw new AssertionError("wrong item");}catch(java.io.IOException expected){checks++;}
  System.out.println("PASS: "+checks+" assertions for exact TV selection, profile isolation, fresh progress, tick precision and fail-closed dispatch.");
 }
}
