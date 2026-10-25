package io.freemytv;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
public final class HandoffCheck {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[]args)throws Exception{
  try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
   StringBuilder captured=new StringBuilder();
   Thread worker=new Thread(()->{try(Socket client=server.accept()){
    BufferedReader r=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));int length=0;
    for(String line;(line=r.readLine())!=null&&!line.isEmpty();){captured.append(line).append('\n');if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());}
    captured.append('\n');for(int i=0;i<length;i++)captured.append((char)r.read());
    client.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
   }catch(Exception e){throw new RuntimeException(e);}});worker.start();
   EmbyRecs.config=new JSONObject().put("server_url","http://127.0.0.1:"+server.getLocalPort()+"/emby")
     .put("server_id","server-a").put("user_id","user-a").put("api_key","dummytestcredential12345");
   EmbyRecs.fixture=(c,path)->new JSONObject().put("Id","movie-a").put("Type","Movie")
     .put("UserData",new JSONObject().put("PlaybackPositionTicks",9106750000L)).toString();
   android.content.Context context=new android.content.Context();
   EmbyPlayback.Target target=EmbyPlayback.forItem(context,"movie-a","",false);
   EmbyPlayerSession.Session session=new EmbyPlayerSession.Session(new JSONObject().put("Id","session-a")
     .put("UserId","user-a").put("SupportsRemoteControl",true).put("PlayableMediaTypes",new JSONArray().put("Video")));
   EmbyPlayer.play(context,target,session);worker.join(3000);
   check(!worker.isAlive(),"actual handoff receives successful mock response");
   String request=captured.toString();JSONObject body=new JSONObject(request.substring(request.indexOf("\n\n")+2));
   check(request.startsWith("POST /emby/Sessions/session-a/Playing?ItemIds=movie-a&PlayCommand=PlayNow&StartPositionTicks=9106750000 HTTP/1.1"),"actual click-to-transport request preserves fresh position");
   check(body.getLong("StartPositionTicks")==9106750000L,"actual JSON body uses the same position");
   check(body.getString("ControllingUserId").equals("user-a"),"actual body keeps selected user");
   check(!request.substring(0,request.indexOf('\n')).contains("dummytest"),"actual request URL contains no credential");
   EmbyRecs.config.put("user_id","user-b");
   try{EmbyPlayer.play(context,target,session);throw new AssertionError("changed profile must not dispatch");}catch(IOException expected){checks++;}
  }
  System.out.println("PASS: "+checks+" actual handoff assertions with a local mock server; no TV playback.");
 }
}
