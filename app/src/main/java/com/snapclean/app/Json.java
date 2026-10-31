package com.snapclean.app;

import org.json.JSONException;
import org.json.JSONObject;

final class Json {
    private Json() {}
    static JSONObject object(Object... pairs) {
        if(pairs.length%2!=0) throw new IllegalArgumentException("Unpaired JSON field");
        JSONObject out=new JSONObject();
        try { for(int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]==null ? JSONObject.NULL : pairs[i+1]); }
        catch(JSONException ex) { throw new IllegalArgumentException(ex); }
        return out;
    }
}
