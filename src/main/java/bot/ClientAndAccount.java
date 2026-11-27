package bot;

import module chariot;
import module java.base;

record ClientAndAccount(ClientAuth client, UserAuth account) {

    private static Logger LOGGER = Logger.getLogger("bot");

    static Opt<ClientAndAccount> initialize() {
        if (initializeClient() instanceof Some(var client)
            && initializeAccount(client) instanceof Some(var account))
            return Opt.of(new ClientAndAccount(client, account));
        return Opt.empty();
    }

    /// Initialize client with a OAuth token with scope `bot:play`,
    /// either provided via environment variable `BOT_TOKEN` (create at https://lichess.org/account/oauth/token/create),
    /// or using OAuth PKCE flow and storing granted token locally with Java Preferences API,
    /// i.e at first run the user must interactively grant access by navigating with a Web Browser
    /// to the Lichess grant page and authorizing with the Bot Account,
    /// and consecutive runs the now already stored token will be used automatically.
    static Opt<ClientAuth> initializeClient() {
        URI lichessApi = URI.create(System.getenv("LICHESS_API") instanceof String api ? api : "https://lichess.org");

        String botToken = null;
        if (Bot.SYSTEMD_CREDENTIALS) {
            String secretDir = System.getenv("CREDENTIALS_DIRECTORY");
            File secret = new File(secretDir, "lichess-api-key");
            if (secret.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(secret))) {
                    botToken = reader.readLine();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to read bot token through systemd credentials", e);
                }
            }
        } else {
            botToken = System.getenv("BOT_TOKEN");
        }

        if (botToken instanceof String token) { // scope bot:play
            var client = Client.auth(conf -> conf.api(lichessApi), token);
            var scopeReq = client.scopes();
            if (scopeReq instanceof Entries(var stream)) {
                if (stream.anyMatch(Client.Scope.bot_play::equals)) {
                    LOGGER.info(() -> "Using provided token");
                    return Opt.of(client);
                }
                LOGGER.info("Provided token is missing bot:play scope");
                return Opt.empty();
            } else {
                if (scopeReq instanceof Fail(int status, _) && status == 401) {
                    LOGGER.info("Permission denied - token considered canceled");
                } else {
                    LOGGER.info("Failed to lookup scopes: %s".formatted(scopeReq));
                    return Opt.empty();
                }
            }
        }
        LOGGER.severe("Missing BOT_TOKEN");

        return Opt.empty();
    }

    static Opt<UserAuth> initializeAccount(ClientAuth client) {
        // Check the Lichess account
        var res = client.account().profile();
        if (! (res instanceof Some(var account))) {
            LOGGER.warning(() -> "Failed to lookup bot account: %s".formatted(res));
            return Opt.empty();
        }

        // Lichess account is a BOT account, we're done - return account
        if (account.title() instanceof Some(var title) && "BOT".equals(title)) return Opt.of(account);

        // It wasn't a BOT account...
        LOGGER.warning(() -> "%s is not a BOT account".formatted(account.name()));

        // Check if eligible to upgrade to BOT account
        if (account.accountStats().all() > 0) {
            LOGGER.warning(() -> "Account has played games - won't be possible to upgrade to BOT account");
            return Opt.empty();
        }

        // Ask if user wants to upgrade to BOT account
        if (! (System.console().readLine("Transform account (%s) to BOT account? (Warning, can't be undone) [N/y]: ".formatted(account.name()))
                instanceof String choice && choice.toLowerCase(Locale.ROOT).equals("y"))) {
            LOGGER.warning(() -> "Did not want to upgrade to BOT account");
            return Opt.empty();
        }

        // User wanted to upgrade to BOT account - attempt to do so
        if (client.bot().upgradeToBotAccount() instanceof Fail<?> fail) {
            LOGGER.warning(() -> "Failed to upgrade account to BOT account: %s".formatted(fail));
            return Opt.empty();
        }

        // Tada!
        LOGGER.info(() -> "Upgraded account to BOT account");
        return Opt.of(account);
    }
}
