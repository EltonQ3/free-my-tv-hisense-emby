package io.freemytv;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class HttpCheck {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static String run(String response,boolean post)throws Exception{
  try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
   StringBuilder captured=new StringBuilder();
   Thread thread=new Thread(()->{try(Socket client=server.accept()){
    client.setSoTimeout(2000);BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
    int length=0;for(String row;(row=reader.readLine())!=null&&!row.isEmpty();){captured.append(row).append('\n');if(row.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(row.substring(15).trim());}
    for(int i=0;i<length;i++)captured.append((char)reader.read());
    client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
   }catch(Exception e){throw new RuntimeException(e);}});thread.start();
   String url="http://127.0.0.1:"+server.getLocalPort()+"/emby/Sessions/test/Playing?StartPositionTicks=9106750000";
   try{if(post)EmbyHttp.post(url,"dummytestcredential12345","{}".getBytes(StandardCharsets.UTF_8),1024);else EmbyHttp.get(url,"dummytestcredential12345",1024);}
   finally{thread.join(3000);}
   return captured.toString();
  }
 }
 public static void main(String[]args)throws Exception{
  String request=run("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n",true);
  check(request.startsWith("POST /emby/Sessions/test/Playing?StartPositionTicks=9106750000 HTTP/1.1"),"playback method, path and precision");
  check(request.contains("X-Emby-Token: dummytestcredential12345"),"header authentication retained");
  check(!request.substring(0,request.indexOf('\n')).contains("dummytest"),"credential excluded from URL");
  check(request.endsWith("{}"),"JSON body sent once");
  request=run("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}",false);
  check(request.startsWith("GET "),"existing read-only requests remain GET");
  try{run("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1/other\r\nContent-Length: 0\r\n\r\n",true);throw new AssertionError("redirect followed");}catch(IOException expected){checks++;}
  try{run("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n",true);throw new AssertionError("denial accepted");}catch(IOException expected){checks++;}
  try{EmbyHttp.post("http://8.8.8.8/emby","dummytestcredential12345",new byte[0],1024);throw new AssertionError("public cleartext accepted");}catch(IOException expected){checks++;}
  System.out.println("PASS: "+checks+" transport assertions against local mock server; no real playback command sent.");
 }
}
