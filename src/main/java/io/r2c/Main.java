package io.r2c;

import io.r2c.util.Log;

/**
 * Command line entry point.
 *
 * <p>Runs the proxy in the foreground; the process exits when the JVM receives a
 * termination signal. A shutdown hook stops the HTTP server so in-flight streams
 * get a chance to finish.
 */
public final class Main {

    public static void main(String[] args) {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }
        if (hasFlag(args, "--version") || hasFlag(args, "-v")) {
            System.out.println("responses2completions " + version());
            return;
        }

        System.out.println("responses2completions " + version()
                + " — OpenAI Responses API exposed as Chat Completions");
        Config config;
        try {
            config = Config.fromEnv();
        } catch (RuntimeException e) {
            System.err.println("invalid configuration: " + e.getMessage());
            System.exit(2);
            return;
        }
        config.logSummary();
        if (config.upstreamApiKey == null || config.upstreamApiKey.isBlank()) {
            if (!config.passThroughClientAuth) {
                Log.warn("no upstream API key configured and client passthrough is disabled: "
                        + "upstream calls will fail with 401");
            } else {
                Log.info("no upstream API key configured: relying on client-supplied keys");
            }
        }

        ProxyServer server;
        try {
            server = new ProxyServer(config);
        } catch (java.io.IOException e) {
            System.err.println("cannot bind " + config.bindAddress + ":" + config.port
                    + " — " + e.getMessage());
            System.exit(1);
            return;
        }

        ProxyServer finalServer = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutting down");
            finalServer.stop();
        }, "r2c-shutdown"));

        server.start();
        Log.info("ready. POST chat completions to http://" + config.bindAddress + ":"
                + config.port + "/v1/chat/completions");

        // Keep the main thread parked; the HTTP server runs on its own threads.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) return false;
        for (String a : args) {
            if (flag.equals(a)) return true;
        }
        return false;
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "1.0.0" : v;
    }

    private static void printUsage() {
        System.out.println("""
                responses2completions — run an OpenAI Responses-only upstream as a
                Chat Completions API.

                Usage: java -jar responses2completions.jar

                Configuration (environment variables):
                  R2C_PORT                          listen port            (default 8080)
                  R2C_BIND                          bind address           (default 0.0.0.0)
                  R2C_UPSTREAM_BASE_URL             upstream .../v1 base   (default https://api.openai.com/v1)
                  R2C_UPSTREAM_API_KEY              key used for upstream calls
                  R2C_PASS_THROUGH_CLIENT_AUTH      prefer the caller's key (default true)
                  R2C_API_KEYS                      comma separated keys accepted from clients
                  R2C_UPSTREAM_TIMEOUT_SECONDS      upstream read timeout  (default 600)
                  R2C_UPSTREAM_CONNECT_TIMEOUT_SECONDS  connect timeout    (default 20)
                  R2C_MODEL_ALIASES                 e.g. gpt-4o=gpt-4.1
                  R2C_DEFAULT_MODEL                 used when the client omits model (default gpt-4o-mini)
                  R2C_ALWAYS_INCLUDE_USAGE          emit usage on the final chunk (default true)
                  R2C_UPSTREAM_HEADERS              extra headers, name=value;name=value
                  R2C_CORS                          permissive CORS for browsers (default true)
                  R2C_UPSTREAM_USER_AGENT           UA sent upstream           (default codex_cli_rs/0.42.0)
                  R2C_LOG_LEVEL                     error|warn|info|debug   (default info)
                """);
    }
}