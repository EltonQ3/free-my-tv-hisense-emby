package io.freemytv;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real production HTTP mutations with both official apps marked installed. */
public final class DualPlayerHandoffCheck {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  List<String> requests=Collections.synchronizedList(new ArrayList<>());
  final Throwable[] error={null};
  try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
   server.setSoTimeout(1500);
   Thread worker=new Thread(()->{try{for(int at=0;at<2;at++)try(Socket socket=server.accept()){
    BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
    StringBuilder text=new StringBuilder();int length=0;
    for(String line;(line=in.readLine())!=null&&!line.isEmpty();){text.append(line).append('\n');if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());}
    text.append('\n');for(int i=0;i<length;i++)text.append((char)in.read());requests.add(text.toString());
    socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
   }}catch(Throwable e){error[0]=e;}});worker.start();
   EmbyRecs.config=new JSONObject().put("server_url","http://127.0.0.1:"+server.getLocalPort()+"/emby")
    .put("server_id","server-a").put("user_id","user-a").put("api_key","dummytestcredential12345");
   EmbyRecs.fixture=(c,path)->new JSONObject().put("Id","movie-a").put("Type","Movie")
    .put("UserData",new JSONObject().put("PlaybackPositionTicks",9106750000L)).toString();
   android.content.Context context=new android.content.Context();EmbyPlayback.Target target=EmbyPlayback.forItem(context,"movie-a","",false);
   EmbyNativePlayer.other=true;
   EmbyNativePlayer.player=EmbyNativePlayer.ANDROID;
   EmbyPlayerSession.Session mobile=TestSessions.player(EmbyNativePlayer.ANDROID,"universal-session","user-a");
   EmbyPlayer.play(context,target,mobile);
   EmbyNativePlayer.player=EmbyNativePlayer.TV;
   EmbyPlayerSession.Session tv=TestSessions.tvAlias("tv-session","user-a");
   EmbyPlayer.play(context,target,tv);worker.join(2500);
   check(!worker.isAlive()&&error[0]==null,"both distinct installed clients complete one request each");
   check(requests.size()==2,"no duplicate or cross-client dispatch");
   check(requests.get(0).startsWith("POST /emby/Sessions/universal-session/Playing?"),"selected universal target is exact");
   check(requests.get(1).startsWith("POST /emby/Sessions/tv-session/Playing?"),"selected TV target is exact");
   for(int i=0;i<2;i++){
    String r=requests.get(i);check(r.substring(0,r.indexOf('\n')).contains("StartPositionTicks=9106750000"),"both selected clients receive fresh seek");
    JSONObject b=new JSONObject(r.substring(r.indexOf("\n\n")+2));check(b.getString("ControllingUserId").equals("user-a"),"owner stays explicit");
    check(!r.substring(0,r.indexOf('\n')).contains("dummytest"),"credential remains out of request URL");
   }
   int before=EmbyPlayer.dispatchCount();
   try{EmbyPlayer.play(context,target,mobile);throw new AssertionError("stale selected package");}catch(IOException expected){checks++;}
   EmbyNativePlayer.androidVersion=EmbyNativePlayer.tvVersion;
   try{EmbyPlayer.play(context,target,tv);throw new AssertionError("changed other version");}catch(IOException expected){checks++;}
   check(EmbyPlayer.dispatchCount()==before,"blocked cross-client requests never reach HTTP mutation");
   server.setSoTimeout(250);try(Socket unexpected=server.accept()){throw new AssertionError("unexpected mutation");}catch(SocketTimeoutException expected){checks++;}
  }
  System.out.println("PASS: "+checks+" actual dual-player HTTP handoff and no-cross-play assertions.");
 }
}
