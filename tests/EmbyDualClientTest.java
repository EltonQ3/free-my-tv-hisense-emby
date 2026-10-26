package io.freemytv;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Callable;
import org.json.*;

/** Both installed clients may share Android ID, IP and user; package/version provenance must still isolate them. */
public final class EmbyDualClientTest {
    static int checks;
    static final String TV="tv.emby.embyatv", ANDROID="com.mb.android", TVV="2.5.03g", AV="3.5.35";
    static final long NOW=1792930000000L, TICKS=9106750000L;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void denied(Callable<?> action,String why)throws Exception {
        try{action.call();throw new AssertionError(why);}catch(IOException expected){checks++;}
    }
    static JSONObject row(String id,String client,String version,String user,boolean ready){
        return new JSONObject().put("Id",id).put("Client",client).put("ApplicationVersion",version)
            .put("DeviceId","shared-device").put("RemoteEndPoint","192.168.1.20").put("UserId",user)
            .put("LastActivityDate",java.time.Instant.ofEpochMilli(NOW).toString())
            .put("SupportsRemoteControl",ready).put("PlayableMediaTypes",new JSONArray().put("Video"));
    }
    static JSONArray both(){return new JSONArray().put(row("native-tv","AndroidTv",TVV,"tv-user",false))
        .put(row("tv-sdk","Emby for Android",TVV,"startup-user",true))
        .put(row("universal","Emby for Android",AV,"android-user",true));}
    static EmbyPlayerSession.Session pick(JSONArray all,String player,String version,String otherVersion)throws Exception {
        return EmbyPlayerSession.select(all,Collections.singleton("192.168.1.20"),"",NOW,player,version,true,true,otherVersion);
    }
    public static void main(String[] args)throws Exception {
        JSONArray all=both();
        EmbyPlayerSession.Session tv=pick(all,TV,TVV,AV), mobile=pick(all,ANDROID,AV,TVV);
        check(tv!=null&&tv.ready&&tv.id.equals("tv-sdk")&&tv.playerPackage.equals(TV),"TV chooses only its own SDK transport");
        check(tv.user.equals("tv-user")&&tv.controllerUser.equals("startup-user"),"native authenticated TV profile governs owner");
        check(mobile!=null&&mobile.ready&&mobile.id.equals("universal")&&mobile.playerPackage.equals(ANDROID),"universal ignores TV alias with the same device/IP");
        check(mobile.user.equals("android-user"),"universal preserves its own authenticated account");
        EmbyPlayerSession.requirePlayer(tv,TV,TVV,true,AV);checks++;
        EmbyPlayerSession.requirePlayer(mobile,ANDROID,AV,true,TVV);checks++;
        check(EmbyPlayerSession.playPath(tv,"tv-user","episode",TICKS).contains("tv-sdk/Playing?"),"TV dispatch cannot target universal session");
        check(EmbyPlayerSession.playPath(mobile,"android-user","movie",TICKS).contains("universal/Playing?"),"universal dispatch cannot target TV alias");
        check(EmbyPlayerSession.playBody(mobile,"android-user","movie",TICKS).getLong("StartPositionTicks")==TICKS,"universal exact seek is unchanged");
        denied(()->EmbyPlayerSession.playPath(tv,"startup-user","episode",TICKS),"transport's old login never overrides native user");
        denied(()->{EmbyPlayerSession.requirePlayer(tv,ANDROID,AV,true,TVV);return null;},"changing selected client invalidates prepared TV dispatch");
        denied(()->{EmbyPlayerSession.requirePlayer(mobile,TV,TVV,true,AV);return null;},"changing selected client invalidates universal dispatch");
        denied(()->{EmbyPlayerSession.requirePlayer(tv,TV,"new-version",true,AV);return null;},"installed TV update invalidates stale session");
        denied(()->{EmbyPlayerSession.requirePlayer(mobile,ANDROID,"new-version",true,TVV);return null;},"installed universal update invalidates stale session");
        denied(()->{EmbyPlayerSession.requirePlayer(new EmbyPlayerSession.Session(row("raw","Android",AV,"u",true)),ANDROID,AV,false,"");return null;},"unselected raw session cannot mutate playback");
        denied(()->pick(all,TV,TVV,""),"unknown other-app version cannot authorize an aliased TV transport");
        denied(()->pick(all,ANDROID,AV,""),"unknown other-app version cannot authorize universal dispatch");
        denied(()->pick(all,TV,"",AV),"unknown selected version cannot identify a coexisting client");
        denied(()->pick(all,ANDROID,AV,AV),"equal installed versions refuse Android-named ambiguity");
        denied(()->pick(all,TV,TVV,TVV),"equal installed versions refuse TV's ambiguous SDK alias");
        denied(()->{EmbyPlayerSession.requirePlayer(tv,TV,TVV,true,TVV);return null;},"a newly installed colliding client blocks aliased TV mutation");
        denied(()->{EmbyPlayerSession.requirePlayer(mobile,ANDROID,AV,true,AV);return null;},"a newly installed colliding TV client blocks universal mutation");
        JSONArray nativeReady=new JSONArray().put(row("tv-direct","AndroidTv",TVV,"tv-user",true));
        EmbyPlayerSession.Session direct=pick(nativeReady,TV,TVV,TVV);
        check(direct!=null&&direct.id.equals("tv-direct"),"unambiguous native TV transport can coexist with equal app versions");
        EmbyPlayerSession.requirePlayer(direct,TV,TVV,true,TVV);checks++;
        for(String field:new String[]{"DeviceId","RemoteEndPoint","ApplicationVersion"}){
            JSONArray changed=both();changed.getJSONObject(1).put(field,field.equals("RemoteEndPoint")?"192.168.1.99":"different");
            EmbyPlayerSession.Session s=pick(changed,TV,TVV,AV);
            check(s==null||!s.ready,"TV never borrows universal transport after identity mismatch: "+field);
        }
        JSONArray duplicate=both().put(row("second-tv-sdk","Android",TVV,"another",true));
        denied(()->pick(duplicate,TV,TVV,AV),"multiple equally identified TV controllers refuse playback");
        JSONArray duplicateMobile=both().put(row("second-mobile","Android",AV,"another",true));
        denied(()->pick(duplicateMobile,ANDROID,AV,TVV),"multiple universal sessions refuse arbitrary choice");
        JSONArray wrongVersion=new JSONArray().put(row("wrong-mobile","Android",TVV,"tv-user",true));
        check(pick(wrongVersion,ANDROID,AV,TVV)==null,"TV alias alone never substitutes for absent universal session");
        check(pick(new JSONArray().put(row("universal","Android",AV,"u",true)),TV,TVV,AV)==null,"universal alone never substitutes for absent native TV profile");
        EmbyPlayerSession.Session legacy=EmbyPlayerSession.select(both(),Collections.singleton("192.168.1.20"),"",NOW,ANDROID,AV);
        check(legacy!=null&&legacy.id.equals("universal"),"single-client overload retains version isolation");
        EmbyPlayerSession.Session pin=EmbyPlayerSession.select(both(),Collections.singleton("192.168.1.20"),"other-device",NOW,TV,TVV,true,true,AV);
        check(pin==null,"coexistence cannot bypass pinned physical device");
        System.out.println("PASS: "+checks+" coexisting Emby clients, package provenance and cross-dispatch rejection assertions.");
    }
}
