package io.freemytv;
import android.content.Context;
import org.json.*;
import java.util.*;
public final class PlaybackCheck {
 static int assertions;
 static final Context CONTEXT=new Context();
 static JSONObject episode(String id,String series,int season,int number,long position,boolean played) {
  return new JSONObject().put("Id",id).put("SeriesId",series).put("Type","Episode")
   .put("ParentIndexNumber",season).put("IndexNumber",number)
   .put("UserData",new JSONObject().put("PlaybackPositionTicks",position).put("Played",played));
 }
 static String items(JSONObject... list) {return new JSONObject().put("Items",new JSONArray(Arrays.asList(list))).toString();}
 static void check(boolean ok,String message) {assertions++;if(!ok)throw new AssertionError(message);}
 static List<String> fixture(String user,String resume,String next,String first) {
  EmbyRecs.config=new JSONObject().put("user_id",user).put("server_id","server-a");
  List<String> calls=new ArrayList<>();
  EmbyRecs.fixture=(config,path)->{
   calls.add(path);check(config.getString("user_id").equals(user),"one immutable profile per lookup");
   if(path.startsWith("/Users/"+user+"/Items?")) {
    check(path.contains("ParentId=show-a")&&path.contains("IsResumable")&&path.contains("Recursive=true"),"series scoped resume query");return resume;
   }
   check(path.contains("UserId="+user),"explicit selected user on every show query");
   if(path.startsWith("/Shows/NextUp?")) {check(path.contains("SeriesId=show-a"),"next-up series scoped");return next;}
   if(path.startsWith("/Shows/show-a/Episodes?")) {check(path.contains("IsPlayed=false"),"unplayed fallback filter");return first;}
   throw new AssertionError("unexpected route");
  };
  return calls;
 }
 static String resolve() throws Exception {return EmbyPlayback.resolve(CONTEXT,"show-a");}
 public static void main(String[] args) throws Exception {
  List<String> calls=fixture("user-a",items(episode("ep-3","show-a",1,3,1200,false)),items(episode("ep-12","show-a",1,12,0,false)),items());
  check(resolve().equals("emby://play/server-a/ep-3"),"unfinished episode wins over latest addition");check(calls.size()==1,"no unnecessary next-up query when resuming");
  calls=fixture("user-a",items(episode("ep-3","show-a",1,3,1200,true)),items(episode("ep-4","show-a",1,4,0,false)),items());
  check(resolve().endsWith("/ep-4"),"completed episode advances to next-up");check(calls.size()==2,"next-up before first-unplayed fallback");
  fixture("user-a",items(),items(episode("wrong-show","show-b",1,1,0,false),episode("missing","show-a",1,5,0,false).put("IsMissing",true),episode("ep-6","show-a",1,6,0,false)),items());
  check(resolve().endsWith("/ep-6"),"cross-series and missing candidates rejected");
  fixture("user-a",items(),items(),items(episode("special","show-a",0,1,0,false),episode("s2e1","show-a",2,1,0,false),episode("s1e10","show-a",1,10,0,false),episode("s1e2","show-a",1,2,0,false),episode("s1e1","show-a",1,1,0,true)));
  check(resolve().endsWith("/s1e2"),"never watched chooses earliest available unplayed regular episode");
  fixture("user-a",items(),items(),items(episode("played","show-a",1,1,0,true)));
  check(resolve()==null,"all watched never falls back to newest episode");
  fixture("user-b",items(episode("b-progress","show-a",2,7,99,false)),items(),items());
  check(resolve().endsWith("/b-progress"),"selected account has independent progress");
  fixture("user-a",items(),items(episode("old-profile-next","show-a",1,2,0,false)),items());
  EmbyRecs.Request previous=EmbyRecs.fixture;
  EmbyRecs.fixture=(config,path)->{String result=previous.get(config,path);EmbyRecs.config.put("user_id","user-b");return result;};
  check(resolve().endsWith("/old-profile-next"),"lookup cannot mix profiles during a configuration change");
  final int[] failed={0};EmbyRecs.fixture=(config,path)->{failed[0]++;throw new java.io.IOException();};
  try{resolve();throw new AssertionError("must fail closed");}catch(java.io.IOException expected){}
  check(failed[0]==1,"network failure does not skip progress and play another episode");
  fixture("user-a","{}",items(episode("newest","show-a",1,12,0,false)),items());
  try{resolve();throw new AssertionError("malformed payload must fail closed");}catch(java.io.IOException expected){}
  fixture("user-a",items(),items(episode("virtual","show-a",1,1,0,false).put("LocationType","Virtual"),episode("placeholder","show-a",1,2,0,false).put("IsPlaceHolder",true),episode("real","show-a",1,3,0,false)),items());
  check(resolve().endsWith("/real"),"virtual and placeholder episodes never auto-play");
  fixture("user-a",items(episode("negative","show-a",1,1,-50,false)),items(episode("next","show-a",1,2,0,false)),items());
  check(resolve().endsWith("/next"),"invalid negative positions do not count as resume");
  System.out.println("PASS: "+assertions+" assertions across resume, next-up, earliest-unplayed, watched, profile-isolation, error and malformed-response scenarios.");
 }
}
