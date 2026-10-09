# Real LumaGuilds companion verification

SPEAR contract: alliance and system-bank calls must link against the actual
reviewed provider JAR, rather than only the checked-in compile shim. Unknown
alliance capability and missing bank methods must fail closed; personal wallets
must not be used as a fallback. Existing payout policies remain unchanged.

`actualGuildApiTest` puts `ENTHUSIA_GUILD_API_JAR` first on the runtime classpath,
asserts its class origin, and runs alliance, bank and protected-payout regressions.
The configured path and artifact contents are Gradle inputs. Java 25 is used
because the current LumaGuilds artifact targets that runtime; ordinary KOTH
compilation and unit tests retain the existing Java 21/Paper 1.21.11 profile.
These adapter contracts do not prove server startup or Paper 26.2 gameplay.

Spec/prove: inspect fetched main and the reviewed anti-abuse head; previous tests
used only a compile mirror. Engine/arch: add a separate opt-in verification task,
without changing runtime plugin behavior, payouts, banks or classloader wiring.
Refine: actual-artifact results and final hosted checks follow below. Project-local
EARS/state helpers are absent; this manual record applies. Production is untouched
and TEST/client acceptance remains deferred.

Final local evidence: ordinary clean test/build discovers 283 cases, with 282 passing and the real-provider-only case explicitly skipped. The Java 25 real-provider task executes 14 cases with zero failures/errors/skips against Guilds implementation fff95638, SHA-256 `d6fa8b62a3842534699aff5d9aaf13a2425391c3e165d7341bc20a9b6c509fd2`. The task reruns for changed path and changed JAR contents, while unchanged inputs are UP-TO-DATE. Consumer JAR SHA-256 `0e277a734ca791ef73275b914538708738e6d996b8bf84d115772442158a6cf7`; no duplicate ZIP entries or bundled Guilds/Bukkit/Paper/Vault API classes. The Gradle 8 launcher stays on Java 21 and forks Java 25 for provider tests; running that launcher directly on Java 25 is unsupported. Hosted checks and server/client acceptance remain separate gates.

GitHub fork activation: the user approved enabling the reviewed inherited workflow on 8 October 2026. The Actions page confirms Actions Enabled and normal pull-request builds now execute. Existing workflow triggers are retained; a temporary review-branch push trigger was removed once the fork gate was confirmed, avoiding duplicate push/PR builds. Final hosted results remain separate from the 14 local real-provider cases. No production action or manual workflow rerun.
