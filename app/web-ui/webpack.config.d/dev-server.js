// Dev server only (./gradlew jsBrowserDevelopmentRun): it plays the part nginx plays in the container - forwards the
// gateway's paths and serves /config.json from env vars. The production bundle has no devServer section.
if (config.devServer) {
    const required = ["WEB_UI_PORT", "GATEWAY_URL", "STRIPE_PUBLISHABLE_KEY"];
    const missing = required.filter((name) => !process.env[name]);
    if (missing.length > 0) {
        throw new Error(`The dev server needs these environment variables: ${missing.join(", ")}`);
    }

    config.devServer.port = Number(process.env.WEB_UI_PORT);
    config.devServer.proxy = [
        {
            context: ["/api", "/oauth2", "/login", "/logout"],
            target: process.env.GATEWAY_URL,
            // keep the browser's Host header: the gateway builds its OAuth2 redirect URIs from it
            changeOrigin: false,
            xfwd: true,
        },
    ];
    config.devServer.setupMiddlewares = (middlewares, devServer) => {
        devServer.app.get("/config.json", (request, response) => {
            response.json({stripePublishableKey: process.env.STRIPE_PUBLISHABLE_KEY});
        });
        return middlewares;
    };
}
