package io.freemytv;
import org.json.*;
import java.util.*;
public final class AccountCheck {
 static int checks;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static JSONObject account(String id,String name,boolean disabled){return new JSONObject().put("Id",id).put("Name",name).put("Policy",new JSONObject().put("IsDisabled",disabled));}
 static JSONObject config(){return new JSONObject().put("server_url","https://example.invalid/emby").put("server_id","server-a").put("user_id","user-a").put("user_name","Account A").put("api_key","dummy-key-for-local-test").put("device_id","device-a");}
 static void denied(JSONObject old,JSONObject now,String id,JSONObject user) throws Exception {
  try{EmbyAccounts.selection(old,now,id,user);throw new AssertionError("selection must be rejected");}
  catch(java.io.IOException expected){checks++;}
 }
 public static void main(String[] args) throws Exception {
  JSONArray source=new JSONArray().put(account("user-a","Account A",false)).put(account("disabled","Hidden disabled",true)).put(account("invalid/id","Invalid",false)).put(account("empty","  ",false)).put(account("user-b","Account B",false));
  List<EmbyAccounts.User> users=EmbyAccounts.users(source);
  check(users.size()==2,"disabled, malformed and nameless users excluded");check(users.get(1).id.equals("user-b"),"available user order retained");
  JSONObject old=config(),now=config();String baseline=old.toString();
  JSONObject selected=EmbyAccounts.selection(old,now,"user-b",account("user-b","Renamed B",false));
  check(selected.getString("user_id").equals("user-b"),"selected identity saved");check(selected.getString("user_name").equals("Renamed B"),"fresh server profile name used");
  for(String key:new String[]{"server_url","server_id","api_key","device_id"})check(selected.getString(key).equals(old.getString(key)),"connection preserved: "+key);
  check(old.toString().equals(baseline)&&now.getString("user_id").equals("user-a"),"selection does not mutate existing saved configuration");
  denied(old,now,"user-b",account("user-b","Account B",true));
  denied(old,now,"user-b",account("user-c","Wrong user",false));
  denied(old,now,"user-b",account("user-b","",false));
  for(String key:new String[]{"server_url","server_id","api_key","user_id"})denied(old,new JSONObject(now.toString()).put(key,"changed"),"user-b",account("user-b","Account B",false));
  check(EmbyAccounts.users(new JSONArray()).isEmpty(),"empty profile response supported");
  System.out.println("PASS: "+checks+" assertions for account availability, switching, preserved credentials, stale-connection rejection and immutability.");
 }
}
