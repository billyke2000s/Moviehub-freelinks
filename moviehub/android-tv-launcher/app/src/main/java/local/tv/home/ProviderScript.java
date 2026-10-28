package local.tv.home;

import java.io.IOException;
import java.util.UUID;
import org.mozilla.javascript.*;

/** Pure JS transforms only. No Java objects, filesystem, network, dynamic imports or Node globals. */
final class ProviderScript {

    /** The whole script input (provider responses included). Plex's catalogue is about 9 MB. */
    static final int MAX_BYTES = 16 * 1024 * 1024;

    // Compiled code only: every invocation still receives fresh globals and capabilities.
    private static final java.util.LinkedHashMap<String, Script> compiled = new java.util.LinkedHashMap<>();
    private static int compilations;

    private ProviderScript() {}

    static synchronized int compilations() {
        return compilations;
    }

    private static synchronized Script compiled(Context cx, String source) {
        Script script = compiled.get(source);
        if (script == null) {
            script = cx.compileString(source, "provider", 1, null);
            if (compiled.size() >= 2) compiled.remove(compiled.keySet().iterator().next());
            compiled.put(source, script);
            compilations++;
        }
        return script;
    }

    static String evaluate(String source, String operation, String payload) throws IOException {
        return evaluate(source, operation, payload, false);
    }

    static String evaluate(String source, String operation, String payload, boolean reuseCode)
        throws IOException {
        // Binder threads may have a boot-only context loader. Rhino's ServiceLoader then misses
        // its RegExp implementation, although it is packaged in the same APK.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(ProviderScript.class.getClassLoader());
            return evaluateWithLoader(source, operation, payload, reuseCode);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static String evaluateWithLoader(
        String source,
        String operation,
        String payload,
        boolean reuseCode
    ) throws IOException {
        if (source.length() > 256 * 1024 || payload.length() > MAX_BYTES) throw new IOException(
            "Provider limit"
        );
        // A catalogue step parses a whole channel list; on a TV box's CPU a large one takes
        // several seconds in the interpreter, so it gets longer than other steps.
        long deadline =
            System.nanoTime() +
            (operation.equals("resolve")
                ? 8_000_000_000L
                : operation.equals("catalogue")
                  ? 10_000_000_000L
                  : 3_000_000_000L);
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context makeContext() {
                Context cx = super.makeContext();
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setInterpretedMode(true);
                cx.setMaximumInterpreterStackDepth(128);
                cx.setInstructionObserverThreshold(1000);
                cx.setClassShutter(name -> false);
                return cx;
            }

            @Override
            protected void observeInstructionCount(Context cx, int count) {
                if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                    // Error, not a JS-catchable exception: scripts cannot swallow the deadline.
                    throw new Deadline();
                }
            }
        };
        try {
            return factory.call(cx -> {
                ScriptableObject scope = cx.initSafeStandardObjects();
                ScriptableObject.putProperty(
                    scope,
                    "uuid",
                    new BaseFunction() {
                        @Override
                        public Object call(Context c, Scriptable s, Scriptable self, Object[] args) {
                            return UUID.randomUUID().toString();
                        }
                    }
                );
                ScriptableObject.putProperty(
                    scope,
                    "codec",
                    new BaseFunction() {
                        @Override
                        public Object call(Context c, Scriptable s, Scriptable self, Object[] args) {
                            try {
                                String op = Context.toString(args[0]);
                                String input = Context.toString(args[1]);
                                if (op.equals("hmac")) {
                                    Object parsed = ScriptableObject.callMethod(
                                        c,
                                        (Scriptable) ScriptableObject.getProperty(scope, "JSON"),
                                        "parse",
                                        new Object[] { input }
                                    );
                                    Scriptable object = (Scriptable) parsed;
                                    byte[] key = Context.toString(
                                        ScriptableObject.getProperty(object, "key")
                                    ).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                                    String message = Context.toString(
                                        ScriptableObject.getProperty(object, "message")
                                    );
                                    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                                    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
                                    return new String(
                                        mac.doFinal(
                                            message.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                        ),
                                        java.nio.charset.StandardCharsets.ISO_8859_1
                                    );
                                }
                                if (op.equals("url")) {
                                    Object parsed = ScriptableObject.callMethod(
                                        c,
                                        (Scriptable) ScriptableObject.getProperty(scope, "JSON"),
                                        "parse",
                                        new Object[] { input }
                                    );
                                    Scriptable object = (Scriptable) parsed;
                                    String value = Context.toString(
                                        ScriptableObject.getProperty(object, "value")
                                    );
                                    String base = Context.toString(
                                        ScriptableObject.getProperty(object, "base")
                                    );
                                    java.net.URI uri = base.isEmpty()
                                        ? new java.net.URI(value.replace(" ", "%20"))
                                        : new java.net.URI(base).resolve(value.replace(" ", "%20"));
                                    if (
                                        uri.getHost() == null || uri.getUserInfo() != null
                                    ) throw new java.io.IOException("Invalid URL");
                                    Scriptable result = c.newObject(scope);
                                    String origin = uri.getScheme() + "://" + uri.getRawAuthority();
                                    String[] keys = {
                                        "protocol",
                                        "hostname",
                                        "host",
                                        "origin",
                                        "pathname",
                                        "search",
                                        "hash",
                                    };
                                    String[] values = {
                                        uri.getScheme() + ":",
                                        uri.getHost(),
                                        uri.getRawAuthority(),
                                        origin,
                                        uri.getRawPath().isEmpty() ? "/" : uri.getRawPath(),
                                        uri.getRawQuery() == null ? "" : uri.getRawQuery(),
                                        uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment(),
                                    };
                                    for (int i = 0; i < keys.length; i++) ScriptableObject.putProperty(
                                        result,
                                        keys[i],
                                        values[i]
                                    );
                                    return ScriptableObject.callMethod(
                                        c,
                                        (Scriptable) ScriptableObject.getProperty(scope, "JSON"),
                                        "stringify",
                                        new Object[] { result }
                                    );
                                }
                                return ProviderCodec.call(op, input);
                            } catch (Exception e) {
                                throw Context.throwAsScriptRuntimeEx(e);
                            }
                        }
                    }
                );
                if (reuseCode) compiled(cx, source).exec(cx, scope);
                else cx.evaluateString(scope, source, "provider", 1, null);
                Object callable = ScriptableObject.getProperty(scope, "invoke");
                if (!(callable instanceof Function)) throw new IllegalArgumentException(
                    "Missing provider interface"
                );
                Object value = ((Function) callable).call(cx, scope, scope, new Object[] {
                    operation,
                    payload,
                });
                Object json = ScriptableObject.getProperty(scope, "JSON");
                Object result = ScriptableObject.callMethod(cx, (Scriptable) json, "stringify", new Object[] {
                    value,
                });
                String out = Context.toString(result);
                if (out.length() > MAX_BYTES) throw new IllegalArgumentException("Provider result too large");
                return out;
            });
        } catch (Deadline | RuntimeException e) {
            throw new IOException("Provider transform failed", e);
        }
    }

    private static final class Deadline extends Error {}
}
