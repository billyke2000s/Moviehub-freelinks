package local.tv.home;

import java.nio.file.*;
import org.json.*;

/** Runs on the real Rhino interpreter; compiled-code reuse must not reuse script state. */
public final class ProviderScriptFastTest {

    public static void main(String[] args) throws Exception {
        String source =
            "if(typeof count==='undefined')count=0;function invoke(){count++;return {count:count,javaType:typeof java};}";
        int before = ProviderScript.compilations();
        for (int i = 0; i < 3; i++) {
            JSONObject value = new JSONObject(ProviderScript.evaluate(source, "resolve", "{}", true));
            if (
                value.getInt("count") != 1 || !value.getString("javaType").equals("undefined")
            ) throw new AssertionError("Script scope or Java capability leaked");
        }
        if (ProviderScript.compilations() != before + 1) throw new AssertionError(
            "Source recompiled between steps"
        );
        String compatibility = Files.readString(Path.of("provider-engine/tools/compat.js"));
        String clock =
            compatibility +
            "\nfunction __load(){} function __run(){var t=Date.now();return fetch('https://fixture.example/slow').then(function(){return Date.now()-t;},function(){return Date.now()-t;});}";
        JSONObject input = new JSONObject()
            .put("now", 100000)
            .put("seed", "fixture")
            .put("row", new JSONObject())
            .put("responses", new JSONArray());
        JSONObject pending = new JSONObject(
            ProviderScript.evaluate(clock, "resolve", input.toString(), true)
        );
        if (!pending.has("requests")) throw new AssertionError("Fixture did not request transport");
        String replay =
            "{\"now\":100000,\"seed\":\"fixture\",\"row\":{},\"fastResolve\":true,\"responses\":[{\"request\":{\"url\":\"https://fixture.example/slow\",\"method\":\"GET\",\"headers\":{},\"body\":\"\"},\"error\":true,\"completedAt\":130000}]}";
        JSONObject result = new JSONObject(ProviderScript.evaluate(clock, "resolve", replay, true));
        if (result.getLong("value") != 30000) throw new AssertionError("Failed transport time was lost");
        replay = replay.replace("\"fastResolve\":true", "\"fastResolve\":false");
        result = new JSONObject(ProviderScript.evaluate(clock, "resolve", replay, false));
        if (result.getLong("value") != 0) throw new AssertionError("Legacy clock contract changed");
        System.out.println(
            "6 fast runtime checks passed: compile reuse, fresh globals, Java denial, failed-request clock and legacy isolation"
        );
    }
}
