package io.freemytv;

import org.json.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Account menu data and local configuration validation; never authenticates as another user. */
final class EmbyAccounts {
    static final class User {
        final String id, name, imageTag;
        User(String id, String name, String imageTag) { this.id = id; this.name = name; this.imageTag = imageTag; }
    }

    static List<User> users(JSONArray users) {
        List<User> result = new ArrayList<>();
        for (int i = 0; i < Math.min(users.length(), 100); i++) {
            JSONObject user = users.optJSONObject(i); if (user == null) continue;
            String id = user.optString("Id"), name = user.optString("Name").trim();
            JSONObject policy = user.optJSONObject("Policy");
            if (!EmbyRecs.validId(id) || name.isEmpty() || policy != null && policy.optBoolean("IsDisabled")) continue;
            result.add(new User(id, name, user.optString("PrimaryImageTag")));
        }
        return result;
    }

    static JSONObject selection(JSONObject before, JSONObject current, String id, JSONObject user) throws Exception {
        for (String field : new String[] {"server_url", "server_id", "user_id", "api_key"})
            if (!current.optString(field).equals(before.optString(field))) throw new IOException("Connection changed");
        String name = user.optString("Name").trim(); JSONObject policy = user.optJSONObject("Policy");
        if (!EmbyRecs.validId(id) || !id.equals(user.optString("Id")) || name.isEmpty()
                || policy != null && policy.optBoolean("IsDisabled")) throw new IOException("Account unavailable");
        return new JSONObject(current.toString()).put("user_id", id).put("user_name", name);
    }
}
