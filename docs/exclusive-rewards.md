# Exclusive KOTH relics and activation gates

Each immutable challenge ID is the lifetime reward identity. Never rename
`sovereign_blade` or `sovereign_crown` to recycle its reward. Ownership is a unique
database row, independent of seasons, reload, account inventory or item loss.
Back up the KOTH database; deleting/restoring it can destroy or rewind ownership.
No GUI, advancement grant or season reset transfers ownership. First qualifying
transaction wins; simultaneous contributors are ordered by scoring time then UUID.

Draft challenge metrics in progression.yml are inactive until TEST. All five
metrics must qualify, and the scoring/account/opposition policy must qualify each
match. Known allies collapse into one side across events. Unlisted cooperating
guilds cannot be proven independent from guild relations alone: reports prompt
staff inspection, not automatic punishment or a promise that collusion is impossible.

## Sword template

Create the actual netherite sword with compatible maximum survival enchantments:
Sharpness V, Sweeping Edge III, Looting III, Fire Aspect II, Knockback II,
Unbreaking III and Mending. TEST decides the final combat balance and lore.
Suggested lore: "Forged for the sovereign of Enthusia's contested hills."
FainNeito must genuinely sign the held item using EnthusiaSignature's signing
confirmation flow and attach its `player_kills` tracker. A display-name signature
is insufficient. Preserve Signature PDC and managed tracker lore when saving the
held item as a LoreItems definition. Tracker kills are cosmetic statistics and
never authorize challenge completion; its cooldown is not durable anti-farming proof.

## Helmet template

Use a real netherite helmet with Protection IV, Respiration III, Aqua Affinity,
Unbreaking III and Mending (Thorns III is a TEST balance choice). A special visual
requires the supported resource-pack/Nexo item model plus Java/Bedrock client
acceptance. This change does not invent a texture, fake an installed model, or
change the item's armour material. Suggested lore: "One crown. One sovereign."
Save the fully prepared held template through LoreItems, preserving its metadata.

## Safe activation sequence

1. Keep production unchanged. Feature source is merged; see [tasks.md](tasks.md).
   Merge the owning network integration and verify runtime APIs, canonical pins
   and combined/trusted provider builds separately before deployment.
2. In TEST verify WorldGuard arena/notification regions and MaceGuard's current
   rotating warzone rules cover each arena. KOTH adds no separate combat rotation.
3. Inspect Signature signer UUID, tracker metadata, LoreItems definitions, actual
   enchantments, lore and helmet model. Test full inventory/offline delivery,
   restart/retry with the SAME claim ID and player identity.
4. Register the chosen earned tags in EnthusiaTags; tag IDs and LoreItems
   definition keys remain blank by default. A blank exclusive definition does
   not reserve ownership or complete that exclusive challenge.
5. Calibrate challenge difficulty, age/playtime, repeated opponents, scoring,
   account/roster and package value. Package quantities/currency have one fixed
   event pool; different item definitions still require economic value review.
6. Enable protection and progression together only after TEST acceptance. Enabled
   progression replaces legacy monetary and command rewards, avoiding a second pool.

## Claims and staff operations

Players: `/ekoth claims`, `/ekoth challenges`, `/ekoth eligibility`.
Staff: `/ekoth history`, `/ekoth reports <match UUID>`, `/ekoth readiness`.
`/ekoth reconcile` lists uncertain currency/XP claims. After checking the actual
provider, `/ekoth reconcile <claim-id> paid|retry <evidence>` records staff identity
and evidence. `retry` explicitly confirms unpaid; it never transfers a relic.
Economy/XP operations enter REVIEW before dispatch and never replay automatically.
Command acceptance is not proof of the XP amount; configure only a verified
supported companion command. LoreItems QUEUED means durable queue acceptance,
not physical delivery. Its operation IDs survive retries and restarts.

The readiness command reports world/region/provider/schedule/possible overlap
checks. MaceGuard arena scope, template authenticity/visuals and XP behavior remain
manual TEST gates. Observed membership history is not an invented joined-at value;
when the optional roster age gate is enabled, unknown history fails closed.
All guild relation changes conservatively exclude challenge credit for active
matches, including changes that might later return to the original relationship.

Current Advancements display-only renderer is supported via owner-bound
ProjectionService (0..1000 progress). Full renderer uses KothProgressionV1 (0..100),
with its own eKOTH rewards suppressed, ordinary triggers/admin grants blocked and
automatic root grants disabled. Provider failures preserve the current display.
