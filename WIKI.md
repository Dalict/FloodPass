# FloodPass Wiki

[简体中文](https://github.com/Dalict/FloodPass/blob/main/WIKI_CN.md) | English

## How It Works

### 1. Automatic Sign-In

1. When a player joins, FloodPass listens to `PlayerJoinEvent` at `MONITOR` priority, by which time Floodgate identity data for the connection is already available
2. `FloodgateApi#isFloodgatePlayer(uuid)` decides whether this is a real Bedrock connection; Java players schedule nothing
3. FloodPass waits `login-delay-ticks` (default 20 ticks = 1 second) so AuthMe can finish its own join processing (limbo setup, session resume)
4. It checks whether the player is already authenticated; if not, there are three cases:

| Account state | Auto-register | FloodPass action | Player sees |
|---|---|---|---|
| Registered | irrelevant | `forceLogin` without a password | auto-login message |
| Not registered | on | `forceRegister` with a random password, then signed in | auto-register message |
| Not registered | off | nothing | AuthMe's normal registration flow |

**Why the delay**: AuthMe runs a lot of asynchronous work during join. If FloodPass called `forceLogin` in the same event, the two plugins' tasks could interleave, producing races such as "signed in, then pushed back into limbo". Waiting one second is the simplest fix, which is why `login-delay-ticks` should not be lowered below 10.

**Why already-authenticated players are skipped**: AuthMe's session feature (`settings.sessions.enabled: true`) signs returning players in by itself. FloodPass then stays silent, and the player sees AuthMe's "session reconnection" message instead of FloodPass's message. That is expected behavior, not a failure.

### 2. Nickname Check Bypass

**Problem**: AuthMe's `allowedNicknameCharacters` (default `[a-zA-Z0-9_]*`) does not allow dots, while Bedrock names carry the Floodgate prefix. AuthMe denies them during the **pre-login phase** (`AsyncPlayerPreLoginEvent`), so `PlayerJoinEvent` never fires and auto-login has no chance to run.

**Solution**: FloodPass listens to the same event at `LOWEST` priority, but registers after AuthMe (the `depend` declaration in `plugin.yml` guarantees AuthMe enables first and registers its listener first). After AuthMe's deny, FloodPass re-checks and restores the connection.

Admission is restored only when all four conditions hold:

1. The result is `KICK_OTHER` (the result AuthMe uses for this check)
2. The UUID is a genuine Floodgate UUID (derived from the XUID; a Java client cannot forge it)
3. The name really violates the current pattern
4. The kick message matches AuthMe's own `characters_in_name` template (or falls back to "the message contains the pattern string")

**Why it cannot be simpler**: AuthMe's antibot deny is also `KICK_OTHER`, and Bedrock names always violate the pattern. Restoring every matching connection would disable antibot protection for Bedrock players.

**Why not relax the regex**: changing `allowedNicknameCharacters` to `'\.?[a-zA-Z0-9_]*'` would let **Java players use dotted names** and impersonate Bedrock players. FloodPass keeps the regex unchanged for Java players and exempts only genuine Bedrock UUIDs.

**How template matching works**:

- Reads `characters_in_name` from `plugins/AuthMe/messages/messages_*.yml` (disk first, AuthMe's bundled jar files as fallback)
- Strips color codes; treats placeholders (`%valid_chars`, `%reg`, any `%xxx`) as wildcards; quotes the remaining text literally
- Matches the rendered template against the actual kick message

This works for official, beautified, translated and fork-shipped language files, and removing the placeholder does not break detection. A template that contains only placeholders and no text is discarded — otherwise it would compile to `^.*$` and match every message.

**Why it must be `LOWEST`**: an earlier version restored at `NORMAL`. LuckPerms then saw the deny at `LOW` (skipping permission loading) and killed the re-allowed connection at `MONITOR` with an empty kick. Restoring at `LOWEST` keeps LuckPerms seeing an allowed connection the whole time.

When detection fails, the console prints a **once-per-session** warning containing the actual kick message, the loaded template list and a fix hint.

### 3. Premium Name-Collision Guard

**Problem**: AuthMe 6.x premium verification (`enablePremium` + PacketEvents) intercepts `LOGIN_START` at the packet layer. The packet Geyser sends contains the raw gamertag (for example `Dalict`); the Floodgate prefix is added only after the backend decrypts the data. If that raw name happens to be a premium-enrolled account, AuthMe starts a Mojang encryption challenge — and a Bedrock client has no Mojang session, so it can never answer.

**Solution**: FloodPass uses PacketEvents at `LOWEST` priority (before AuthMe's `NORMAL`) to rewrite the packet username:

- Only connections whose channel carries the `floodgate-player` attribute are touched — it is written only after Floodgate successfully decrypts and verifies the Xbox-signed data, and is always null for Java connections
- The username becomes `prefix + name` (for example `.Dalict`)
- AuthMe's own username filter `^[a-zA-Z0-9_]{2,16}$` sees the dot and skips premium verification
- Floodgate then builds the final identity from its verified data; the packet username is only a temporary value, so **no double prefix** can appear

Result: the Java account keeps premium passwordless sign-in, and the same-named Bedrock player joins through Xbox verification.

**Without PacketEvents**: the feature is skipped and the plugin starts normally (since 1.0.3). On 1.0.2 and older, a missing PacketEvents caused a class-loading failure that prevented the whole plugin from loading; that is fixed.

### 4. Pre-Join Dialog Bypass

**Problem**: AuthMe 6.x can show a login/register dialog during Paper/Folia's **configuration phase** (after login, before `PlayerJoinEvent`). While the player is in that dialog, no plugin can complete the login; when it times out, AuthMe kicks with a "login timeout". The symptom: a Bedrock player sits in the password dialog for a long time and then gets kicked.

**Solution**: FloodPass calls AuthMe's public API `forceLoginFromProxy(name)` during `AsyncPlayerPreLoginEvent` (`MONITOR`) for verified Floodgate players. This tells AuthMe that a trusted automatic login is pending. AuthMe checks that queue when the configuration phase starts and skips the dialog:

- Registered players are signed in during join processing
- Unregistered players join normally, and FloodPass's auto-register takes over

**Boundaries**:

- Only genuine Floodgate UUIDs are affected; Java players still see the dialog
- Requires the AuthMe 6.x API; AuthMe 5.x has no pre-join dialog, so the feature is inactive there
- With the pre-join dialog enabled, registered Bedrock players may see AuthMe's "session reconnection" message instead of FloodPass's auto-login message — both mean they are signed in

---

## Security

A Floodgate identity exists only after the Bedrock client completes Floodgate's encrypted handshake with a valid Xbox Live token. The player's UUID is derived from the XUID, which a Java Edition client cannot forge. Therefore:

- Passwordless sign-in is only offered to players Microsoft has already verified
- The nickname bypass exempts only genuine Floodgate UUIDs; the regex is unchanged for Java players
- The premium guard only rewrites connections whose channel has been verified

Preconditions: the Floodgate private key (`key.pem`) stays private, all Bedrock traffic enters through Geyser, and Geyser's `validate-bedrock-login` stays `true`.

---

## Installation

1. Install and configure **Geyser** and **Floodgate** (2.x) so Bedrock players can join
2. Install **AuthMe** (AuthMeReloaded, 5.2+) and configure it for your Java players
3. Drop `FloodPass-x.x.x.jar` into `plugins/` and restart the server

Verify with a Bedrock account: the first join should say "registered and signed in", the next one "signed in".

> If you use an AuthMe fork with built-in Bedrock auto-login: when the fork's feature is disabled (the default), FloodPass works normally and also gives you auto-registration. If the fork's own auto-login is enabled, do not stack FloodPass on top.

---

## Proxies (Velocity / BungeeCord)

FloodPass is not a proxy plugin. Do not put it in Velocity's or BungeeCord's plugins folder — it will not load there. It goes wherever AuthMe goes: **on the backend server that runs AuthMe.**

| Component | Proxy | Backend (the server running AuthMe) |
|---|---|---|
| Geyser | install | not needed |
| Floodgate | install | **install too** (same key.pem as the proxy) |
| AuthMe | no | install |
| FloodPass | no | install |

**Why the backend needs its own Floodgate**: the proxy's Floodgate only encrypts and forwards the Bedrock identity. The backend's Floodgate is what decrypts it so backend plugins can tell who is a Bedrock player. With Floodgate only on the proxy, backend plugins cannot recognize Bedrock players.

**key.pem**: copy the proxy's Floodgate `key.pem` into every backend's Floodgate folder; both sides must be identical.

Two more things:

- On Velocity, enable modern forwarding and match the backend Paper's `forwarding-secret`; on BungeeCord, set `bungeecord: true` (ip-forward) in the backend's `spigot.yml`
- If you use AuthMe's `/premium`, install the official companion on the proxy (`authme-velocity` for Velocity, `authme-bungee` for BungeeCord), and set `Hooks.bungeecord: true` plus the same `proxySharedSecret` on the backend AuthMe

Connect with a Bedrock client to Geyser's port (default UDP 19132). The auto-login message should appear about a second after joining.

---

## Commands and Permissions

| Command | Permission | Description |
|---|---|---|
| `/floodpass` | `floodpass.admin` (default OP) | Show status (enabled, delay, auto-register) |
| `/floodpass reload` | `floodpass.admin` | Reload config and language files, restoring missing files |

Alias: `/fpass`

---

## Configuration

File: `plugins/FloodPass/config.yml`

| Option | Default | Description |
|---|---|---|
| `language` | `en` | Uses `lang/messages_<language>.yml`; 17 languages are bundled, custom files work too |
| `enabled` | `true` | Master switch for automatic sign-in |
| `login-delay-ticks` | `20` | Ticks to wait after join before signing in; keep at 10 or above |
| `auto-register.enabled` | `true` | Register first-time Bedrock players automatically |
| `auto-register.password-length` | `32` | Random registration password length (8-64) |
| `bypass-nickname-check` | `true` | Nickname prefix bypass |
| `premium-conflict-guard` | `true` | Premium name-collision guard (needs PacketEvents) |
| `bypass-prejoin-dialog` | `true` | Skip AuthMe's pre-join dialog automatically |
| `debug` | `false` | Log every sign-in decision to the console |
| `config-version` | `3` | Internal layout version, do not edit |

### Language Files

- Location: `plugins/FloodPass/lang/messages_<language>.yml`
- To add a language: create `messages_zh_tw.yml`, set `language: zh_tw`
- Missing keys fall back in this order: disk file, bundled same language, bundled English
- Messages support `&` color codes; an empty string disables that message

### Config Self-Healing

On startup and on `/floodpass reload`, the plugin checks the structure of `config.yml`:

- Already current: nothing happens
- Missing new keys / old version / wrong types: back up as `config.yml.backup-<timestamp>`, migrate old values into the bundled template and write it back, **comments preserved**
- Corrupt YAML: back up as `config.yml.broken-<timestamp>` and reset to defaults

Comments added manually by the owner are kept only in the backup when a migration rewrites the file, because the rewrite uses the bundled template's comments.

---

## Design Notes

- **Delayed execution instead of forcing at join**: calling `forceLogin` directly races AuthMe's own asynchronous join processing
- **Stable API surface only**: `isRegistered`, `isAuthenticated`, `forceLogin`, `forceRegister` have identical signatures from AuthMe 5.2 through 6.x, so one build covers all
- **Random passwords are never shown**: nobody knows the auto-registered password; the account is reachable only through Xbox verification or the server operator
- **Reflection only at boundaries**: the entity scheduler (Folia), the pre-join API probe and the PacketEvents-isolated guard all have fallbacks; failures do not crash the plugin
- **Pre-login re-allow instead of relaxing the regex**: only AuthMe's single nickname deny is restored; the regex still protects Java players
- **Collision guard borrows AuthMe's own skip path**: it never fights the premium interceptor; it only rewrites the packet name of verified Floodgate connections
- **Comment-preserving config migration**: the self-healer replaces values line by line in the bundled text template instead of saving a parsed object

---

## Known Limitations

- Bedrock accounts are stored under their Floodgate-prefixed name (`.Steve` by default). **Changing the Floodgate username prefix later breaks the name mapping** for existing accounts; migrate the AuthMe data, or let players re-register with a random password
- `bypass-nickname-check` relies on the `characters_in_name` template in AuthMe's language files to recognize the deny; if detection fails, the console shows the kick text and a fix hint
- With an empty Floodgate `username-prefix`, the name-collision guard cannot separate the two identities, and AuthMe itself would store both accounts under the same name; that setup is unsupported
- AuthMe's nickname length limit (`maxNickLength`, default 20) is not bypassed; prefixed Bedrock names are at most 17 characters, which fits the default
- `forceLogin` bypasses AuthMe two-factor authentication for that Bedrock account. For an Xbox-verified identity this is intended behavior
- On AuthMe 6.x, a Bedrock player may see the **post-join** login dialog flash briefly; the **pre-join** dialog is skipped automatically
- The pre-join bypass needs the AuthMe 6.x API; on AuthMe 5.x the option is inactive (5.x has no such dialog)
- Config self-healing preserves the bundled template comments; comments added manually by the owner live only in the backup
- Session resume, proxies and BungeeCord messaging are untouched; if AuthMe already signed the player in through a session, FloodPass stays silent
- AuthMe's `/premium` targets Java accounts and is safely rejected for regular Bedrock players; Bedrock players do not need it

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Plugin fails to load with `NoClassDefFoundError: ...packetevents...` | 1.0.2 and older treated PacketEvents as a load-time dependency | Upgrade to 1.0.3+ |
| Bedrock player kicked with "username does not meet the standard" | AuthMe's nickname regex rejected the prefixed name and the bypass did not fire | Upgrade to 1.0.3+; enable `debug` and check for a detection warning |
| Bedrock player stuck in a password dialog, then "login timeout" after 2-5 minutes | AuthMe's pre-join dialog blocked the join | Upgrade to 1.0.4+ and keep `bypass-prejoin-dialog: true` |
| LuckPerms logs `Player connection was re-allowed` and kicks the player | Older version restored the connection at `NORMAL` priority | Upgrade to 1.0.3+ |
| Player joins, but FloodPass's auto-login message is missing | AuthMe's session feature signed the player in first | Expected; with `debug: true` you see `already authenticated, skipping` |
| Geyser logs "tried to log in as a Java player" | The premium challenge was not skipped (PacketEvents missing or guard inactive) | Make sure PacketEvents is installed and `premium-conflict-guard: true` |
| No Floodgate-prefixed name appears on the backend at all | Geyser is not sending the Floodgate identity | Check Geyser's `java.auth-type: floodgate`, `validate-bedrock-login: true`, `key.pem`, and the backend Floodgate |
| No console output on Windows with `nogui` | Started from a non-console host, invalid stdin handle | Remove `nogui`, or start the script from Explorer |
| Config comments disappear after an upgrade | Versions before 1.0.4 rewrote the file through Bukkit's YAML save | Upgrade to 1.0.4+; old comments are in the backup |

Useful checks:

```bash
# FloodPass startup state
grep -i floodpass logs/latest.log

# Whether Floodgate recognized the player
grep "Floodgate 玩家" logs/latest.log

# Who AuthMe actually signed in
grep "\[AuthMe\].*logged in" logs/latest.log

# Current nickname regex
grep allowedNicknameCharacters plugins/AuthMe/config.yml

# Name-check message template
grep -A1 characters_in_name plugins/AuthMe/messages/messages_*.yml
```

---

## Building

```bash
mvn package
```

The jar lands in `target/FloodPass-<version>.jar`. It is compiled for Java 8 and runs on Java 8 or newer.

---

## License and Feedback

MIT License.

- Source and issues: https://github.com/Dalict/FloodPass
- Issues: https://github.com/Dalict/FloodPass/issues

---
