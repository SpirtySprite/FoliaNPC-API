# FoliaNPC

A packet-based NPC library for Paper and Folia with no third-party dependencies. Everything is done
through reflection against the server's own classes, so there's no ProtocolLib or packetevents to
install alongside it.

FoliaNPC is a library, not a plugin. It ships no `plugin.yml` and no commands; you shade it into your
own plugin and control it entirely from code.

## Table of contents

- [Design](#design)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Creating NPCs](#creating-npcs)
- [Cloning](#cloning)
- [Movement](#movement)
- [Visibility](#visibility)
- [Skins](#skins)
- [Nametags](#nametags)
- [Appearance](#appearance)
- [Equipment](#equipment)
- [Clicks](#clicks)
- [Events](#events)
- [Persistence](#persistence)
- [Diagnostics](#diagnostics)
- [Threading](#threading)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [Building](#building)

## Design

The NPCs are not real entities. Nothing is added to the world, nothing is ticked by the server, and
nothing is written to region files. Each NPC exists only as a set of packets sent to the players close
enough to see it — the server never spawns an `Entity` object for it, there's no AI, no pathing goal,
no chunk ticket, no persistence in the level data.

That has three consequences worth understanding up front, because they explain almost every behavior
described later in this document:

1. **It's cheap.** An NPC nobody is looking at costs nothing beyond a map entry. Ten thousand NPCs
   spread across a large world cost roughly what the *visible* subset costs, not what all ten thousand
   would cost as real entities.
2. **It works cleanly on Folia.** Folia's whole model is built around real entities and players each
   being owned by exactly one region thread, and code touching one from the wrong thread throws or
   corrupts state. A library with no real entities has nothing Folia needs to protect it from — the
   only entities anywhere in this system are the *players themselves*, and FoliaNPC already tracks
   which thread owns each one (see [Threading](#threading)).
3. **It has no physics.** There's no gravity, no collision resolution, no falling, no fluid pushing it
   around. Position is 100% whatever you (or `walkTo`/`navigateTo`) last set it to. Spawn an NPC over a
   hole and it will float there indefinitely; nothing will ever pull it down on its own. If you need
   that, see [Known limitations](#known-limitations).

Every NPC is identified by a real, random `UUID` (so it can hold a tab-list profile and, for `PLAYER`
type, a skin) but that UUID belongs to no actual player and no actual entity anywhere on the server.

## Requirements

- Java 21
- Paper or Folia **1.20.6 or newer**, Mojang-mapped (this is the default for all modern Paper/Folia
  builds; you don't need to do anything special to get it)
- No other plugins, dependencies, or protocol libraries required at runtime

`FoliaNpc.create(plugin)` checks the version at startup and throws `IllegalStateException` immediately
if the server is older than 1.20.6. There's no partial/degraded support for older versions — either the
whole packet layer binds, or NPC creation refuses to start at all. See
[FoliaNpc.create() throws IllegalStateException](#foliaNpccreate-throws-illegalstateexception) if this
happens on a server you believe meets the requirement.

## Installation

It isn't published to a public repository yet, so install it to your local Maven repository first:

```bash
mvn install
```

Then depend on it and shade it into your plugin:

```xml
<dependency>
    <groupId>net.folianpc</groupId>
    <artifactId>folianpc</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Use the `maven-shade-plugin` (or the Gradle Shadow plugin) to bundle it into your own jar — FoliaNPC has
no `plugin.yml`, so it cannot be installed as a standalone plugin, and shading it cannot collide with
your own plugin descriptor. If two of your own plugins both shade FoliaNPC, each gets its own
independent copy: they do not share NPCs, view distance, or any other state, because each shaded copy
is a completely separate set of classes to the JVM.

The public API lives entirely in `net.folianpc.api`. The `internal` packages (`net.folianpc.internal.*`)
are marked `@ApiStatus.Internal` and may change shape or behavior between releases without notice —
don't call into them directly, and don't rely on anything not exposed through `net.folianpc.api`.

## Quick start

```java
public final class MyPlugin extends JavaPlugin {

    private FoliaNpc npcs;

    @Override
    public void onEnable() {
        npcs = FoliaNpc.create(this);

        npcs.builder()
            .name("Shopkeeper")
            .location(someLocation)
            .lookAtPlayers(true)
            .nametag("<gradient:gold:yellow><bold>Shop</bold></gradient>", "<gray>Right-click me")
            .action(ClickType.RIGHT, Actions.message("<green>Welcome, %player%!"))
            .spawn();
    }

    @Override
    public void onDisable() {
        npcs.close();
    }
}
```

Create exactly **one** `FoliaNpc` instance per plugin, in `onEnable`, and keep it for the plugin's
lifetime. Call `close()` in `onDisable` — this despawns every NPC for every online player, cancels the
internal tick timer, unregisters the join/quit/move listener, and shuts down the skin-fetch HTTP client
and its background thread. Skipping `close()` on a `/reload` leaves stale packet listeners injected into
player connections and a thread pool that never terminates.

## Creating NPCs

The builder handles creation. Only `location` is required — everything else has a sensible default.

```java
Npc npc = npcs.builder()
    .name("Guard")                 // display name, and the vanilla name plate
    .type(EntityType.ZOMBIE)       // defaults to PLAYER
    .location(loc)
    .lookAtPlayers(true)
    .viewDistance(32)              // 0 or less uses the global default of 48
    .cooldown(1000)                // minimum milliseconds between clicks, per player
    .owner(player.getUniqueId())   // purely informational, see Persistence
    .spawn();
```

Every builder option has a matching setter on the live `Npc`, so anything you configure at spawn you can
also change afterwards:

```java
npc.name("Merchant").glowing(true).scale(1.5);
npcs.all();          // every live NPC, as a snapshot list safe to iterate
npcs.get(uuid);       // one by id, or null if it doesn't exist / has been removed
npc.remove();         // despawns for everyone and unregisters; the handle must not be reused after this
```

Renaming re-sends the NPC (a full despawn/respawn for every current viewer), because the name is
embedded in the tab-list profile the client received at spawn time and can't be patched afterwards.

### Visibility range

An NPC is shown to players within range (48 blocks by default) in the *same world* and hidden again the
moment they leave, either by walking away or by changing worlds. You do not manage this yourself — the
manager recalculates who should see what roughly ten times a second (every 2 game ticks) and sends only
the difference (newly-in-range players get a spawn packet, newly-out-of-range players get a despawn).
The range is adjustable globally with `npcs.viewDistance(64)` or per NPC with `.viewDistance(32)`; a
per-NPC value of 0 or less falls back to the current global default.

### Entity type

Player NPCs receive the full tab-list profile and can carry a skin. Any other `EntityType` is sent as a
plain spawn packet with no tab-list entry at all, so skins, skin layers, and the tab-list toggle never
apply to those — everything else (nametags, equipment, actions, glow, scale, pose, baby) works
identically for both.

The type can be changed after spawning, not just at creation:

```java
npc.type(EntityType.ZOMBIE);
```

This always triggers a full despawn/respawn for every current viewer — the entity type is part of the
spawn packet itself, so there is no way to patch it in place. Switching *from* `PLAYER` to something
else drops the skin/skin-layers/tab-list-listing visual effects for viewers until you switch back
(the underlying `Skin`/`mirrorSkin`/`showInTabList` state is preserved on the `Npc`, just not applied
while the type doesn't support it).

## Cloning

```java
Npc copy = npc.copy(otherLocation);
```

Spawns a new, fully independent NPC at `otherLocation` with the same configuration as the source: type,
skin, skin mirroring, equipment, nametag lines, appearance (glow/invisible/skin layers/scale/glow
color/collidable/nametag visibility), pose, baby state, cooldown, view distance, click listener, and
every registered action (for both `ClickType.LEFT` and `ClickType.RIGHT`, in the same order with the
same delays). The copy gets a brand-new random id and starts completely fresh at the *tracking* level:
no current viewers, no per-player interaction cooldowns, no `showTo`/`hideFrom` visibility overrides —
those are runtime state tied to the original NPC's history, not configuration, so they are never copied.

Actions and the click listener are copied by reference (the same `NpcAction`/`NpcClickListener`
instances are reused on the copy) rather than being deep-cloned, since they're just functional
interfaces. This is safe as long as your actions don't close over the *specific* source `Npc` instance
expecting it to always be the one that was clicked — well-written actions read the NPC from
`ctx.npc()` rather than capturing one from an enclosing scope, and those work identically on a copy.

## Movement

```java
npc.teleport(location);      // instant, works across worlds
npc.walkTo(location, 4.0);   // walk in a straight line at 4 blocks per second
npc.stopWalking();
```

`teleport` is instant and works across worlds. It re-sends the NPC to every current viewer (a full
despawn/respawn), which produces a brief visual flicker on a long jump — there's no interpolation, the
NPC simply appears at the new spot. Teleporting also immediately cancels any in-progress `walkTo` or
`navigateTo`.

`walkTo` moves in a perfectly straight line at the given speed (blocks per second), with **no
pathfinding, no obstacle avoidance, and no gravity**: it will walk through walls, off the edges of
cliffs, and straight across gaps that a real mob would fall into. If your use case can guarantee open,
flat ground between the NPC and its destination (e.g. a fixed patrol route you designed by hand), this
is the cheapest option — it costs nothing but simple linear interpolation, computed on the same thread
that already ticks the NPC, no block reads at all. A target in a different world silently falls back to
a `teleport` instead of attempting to walk.

### Pathfinding with `navigateTo`

```java
npc.navigateTo(target, 4.0).thenAccept(found -> {
    if (!found) plugin.getLogger().warning("no route to " + target);
});
```

`navigateTo` computes an actual route instead of a straight line, using a simple grid-based A* search
over the live block data around the NPC:

- It considers the 8 horizontal directions from each grid column, checking that both the "feet" and
  "head" blocks are passable and the block below is solid ground.
- It can **step up** one block and **step down/fall** up to three blocks to handle uneven terrain
  (stairs, single-block ledges, shallow drops).
- Diagonal moves are rejected if they would cut through a solid wall corner (both of the two flanking
  columns must be open, not just the diagonal target itself).
- The search is bounded by both a maximum radius (128 blocks from the start by default) and a maximum
  number of expanded nodes (4000 by default), so an unreachable or maze-like goal fails predictably
  instead of hanging.
- Step-up legs are given an extra waypoint that arcs slightly above the landing height, so the NPC
  visibly hops onto the block instead of gliding up it in a straight diagonal line.
- Both `walkTo` and `navigateTo`-driven movement face the direction of travel while the NPC is actually
  moving, **even if `lookAtPlayers(true)` is set** — the travel-facing rotation takes priority for the
  duration of the walk, and `lookAtPlayers` resumes control the instant the NPC arrives and stops.

This is a simple grid search, not real mob AI: it has no concept of doors, ladders, water, minecarts, or
multi-block structures, and it will not squeeze through a 1-wide gap it hasn't explicitly modeled as
open. See [Troubleshooting](#troubleshooting) if a route you'd expect to succeed keeps failing, or if a
route that should be blocked isn't.

`navigateTo` returns a `CompletableFuture<Boolean>` because the search itself has to run on a specific
thread (see below) rather than synchronously on the caller's thread. `false` means the NPC did not
move at all — either no route was found within the search bounds, or `target` is in a different world
(cross-world routes are not supported by `navigateTo`; use `teleport` or `walkTo` for that case, exactly
as with plain `walkTo`). Call `stopWalking()` at any time to cancel a route in progress, exactly as you
would cancel a plain `walkTo`.

**Threading detail, because this bit us during development and is worth being explicit about:** reading
block data (`World.getBlockAt(...)`) is only legal, on Folia and Folia forks, from the region thread
that actually owns the chunk in question — *not* from the global region thread, which some other
Folia-aware code (including earlier versions of this library) mistakenly treats as a safe place to do
arbitrary world reads. Doing so throws (on Canvas, a Folia fork, this surfaces as
`IllegalStateException: Thread failed main thread check: Cannot read world asynchronously`; other forks
may phrase it differently, but the underlying rule is the same). `navigateTo` schedules its search via
`Bukkit.getRegionScheduler().execute(plugin, npcLocation, ...)`, tied to the NPC's own current location,
which is guaranteed correct for reads within that region. If the route wanders into a neighboring region
that hasn't merged with the NPC's own, block reads there are best-effort rather than strictly
guaranteed-safe — the same tradeoff every Folia-aware plugin accepts for any feature that can span
region boundaries. In practice, regions are large relative to typical NPC walk distances, so this only
matters for very long routes near a region boundary on a busy, heavily-split server.

### General movement notes

The floating nametag (if any) and held/worn equipment move together with the NPC during both `walkTo`
and `navigateTo` — they are separate entities from the NPC's own, but the manager moves all of them in
lockstep every step.

## Visibility

By default an NPC is shown to anyone within range, in the same world. You can override that per player,
which is how you gate an NPC behind a permission check, a quest step, or an A/B test:

```java
npc.hideFrom(player);         // never shown to this player, however close they are
npc.showTo(player);           // always shown to this player, ignoring the distance check
npc.resetVisibility(player);  // drop the override, return to the normal distance check
```

Overrides take effect on the next visibility pass (within ~100ms), not instantly, and only affect the
one player they were set for. `showTo` ignores distance but never crosses worlds — a player standing in
the nether will never see an overworld NPC no matter how forcefully you `showTo` them. Overrides are
**cleared automatically when a player disconnects** (this is deliberate — a permission or quest state
that changed while they were offline shouldn't silently resurrect a stale override), so if you want an
override to persist across sessions, re-apply it yourself on their next join, sourced from your own
storage. `UUID`-based overloads exist for setting overrides on players who are currently offline.

Visibility overrides are **not** included in `NpcData` / `npc.data()` — they are runtime-only state by
design, precisely because they're meant to be re-derived from your own permission/quest logic on each
join rather than snapshotted and blindly restored.

## Skins

A skin is Mojang's signed texture pair (`value` + `signature`). Fetch one by player name or UUID; both
calls are asynchronous (they hit Mojang's public API over HTTPS) and cached:

```java
npcs.fetchSkin("Notch").thenAccept(npc::skin);
npcs.fetchSkinFromUrl("https://.../skin.png").thenAccept(npc::skin);   // generated via Mineskin
npcs.skinCacheTtl(Duration.ofHours(1));                                // default is 30 minutes
```

Applying a skin re-sends the NPC (a full despawn/respawn for current viewers), so it's safe to call at
any time, including before the NPC has ever been shown to anyone. If you already have the raw
value/signature pair from somewhere else, skip the network call entirely and build one directly:
`new Skin(value, signature)`.

Failed lookups are **never cached** — a single Mojang API blip (rate limiting, a timeout, a transient
5xx) does not permanently break that name/UUID's skin for the rest of the server's uptime; the next call
simply retries the network request. All requests, including the URL/Mineskin path, time out after ten
seconds. Skins apply **only to `PLAYER`-type NPCs**; setting one on any other entity type is harmless
but has no visible effect, and it's preserved (not discarded) if you later switch the type back to
`PLAYER`.

### Mirroring

```java
npc.mirrorSkin(true);   // each viewer now sees the NPC wearing their own current skin
```

Each viewer's own skin is read directly off their live Bukkit profile (`Player.getPlayerProfile()`) at
the moment they're shown the NPC — there is **no network fetch and no caching involved at all**, unlike
`fetchSkin`. The static skin set with `npc.skin(...)` is retained underneath and comes back immediately
for every viewer as soon as mirroring is turned back off; the two are not mutually destructive. Mirroring
only applies to `PLAYER`-type NPCs, same restriction as static skins.

## Nametags

Two different things can appear above an NPC's head, and they are not the same mechanism.

The **vanilla name plate** is drawn by the client purely from the tab-list profile name — it's a single
line, no color/formatting beyond a single team color (see below), and every `PLAYER`-type NPC gets it
automatically the moment it spawns, using whatever `npc.name()` currently is.

A **floating nametag** is a stack of real `text_display` entities, one per line, positioned just above
the NPC's head. This is what you want for color, gradients, multiple lines, or a nametag on a
non-player NPC (mobs have no vanilla name plate of their own worth using).

```java
npc.nametag(List.of(
    "<gradient:gold:yellow><bold>Shopkeeper</bold></gradient>",
    "<gray><italic>Right-click to trade</italic>",
    "&aOpen now"));   // legacy & codes still work
```

Setting a non-empty floating nametag **automatically hides the vanilla plate**, so the two never visibly
overlap or double up. Clearing it with `nametag(List.of())` restores the vanilla plate. If you actually
want both showing at once (unusual, but supported), explicitly call `.nametagVisible(true)` afterwards.

### Why `npc.name()` and the name on the wire can differ

Hiding the vanilla name plate (whether directly via `nametagVisible(false)`, indirectly via setting a
floating nametag, or via `glowColor`/`collidable(false)`, all three of which force a scoreboard team —
see [Appearance](#appearance)) is implemented with a scoreboard team set to never show its members' name
tags. Scoreboard teams are keyed on the *player name string*. If an NPC were teamed under its own
display name — say, an NPC named `Notch` — a real player actually named Notch would also have their
real nametag hidden as a side effect, which would be a serious, confusing bug. To avoid this entirely,
whenever a team is required the NPC is sent under a unique, id-derived name instead of its display name.
This substitution is entirely internal and never surfaces through the public API — you never see or
need to know the wire name — but it's the reason `npc.name()` (what you set) and the name Mojang's
tab-list/team system actually sees on the wire can differ. `Capabilities.namePlateHiding` reports
whether the underlying team subsystem bound at all on this server.

### Text formatting

Text is parsed as MiniMessage if it contains a `<` character, otherwise as legacy `&`-code formatting —
this rule applies everywhere text is accepted (nametag lines, `Actions.message`). `Text.mini(...)`,
`Text.legacy(...)`, and `Text.toMini(component)` are available if you want to be explicit rather than
rely on the auto-detection, or need to convert an existing `Component` back into a MiniMessage string
for storage. `Capabilities.richText` reports whether the server can render true Adventure components
(gradients, hover, etc.) client-side; where it can't, formatting degrades to legacy section-sign codes.

### Per-player text and placeholders

Nametag text is resolved **per viewer**, not once globally, so you can wire in PlaceholderAPI or any
resolver of your own:

```java
npcs.placeholders((player, line) -> PlaceholderAPI.setPlaceholders(player, line));
npc.nametag(List.of("<gold>%vault_eco_balance%"));   // resolves differently for each player who sees it
npc.autoRefreshNametag(40);                          // re-resolve and re-send every 40 ticks; 0 disables
```

The resolver function runs on the raw line string *before* MiniMessage/legacy parsing, so placeholder
tokens can themselves contain color codes if your resolver produces them. `refreshNametag()` re-sends
the resolved text to every current viewer without respawning the underlying line entities (cheap, no
flicker); `autoRefreshNametag(ticks)` does the same thing automatically on a repeating timer so a live
value (an economy balance, a countdown, a player's current world) stays current without you having to
remember to call `refreshNametag()` yourself. Passing `0` or less turns automatic refresh back off.

## Appearance

```java
npc.glowing(true)
   .glowColor(NamedTextColor.AQUA)   // outline colour, and the vanilla name-plate colour
   .invisible(true)                  // hides the body, keeps armour and the nametag
   .skinLayers(false)                // disables hat/jacket/sleeves on player NPCs
   .scale(1.6)                       // render size; 1.0 is normal, requires 1.20.5+
   .collidable(false)                // players pass through it instead of bumping into it
   .showInTabList(true)              // player NPCs only; off by default
   .pose(NpcPose.SITTING);

npc.swing();          // one-shot arm-swing animation, useful as click feedback
npc.swingOffHand();
```

`glowing`, `invisible`, and `skinLayers` all travel together in a single entity-metadata packet, so
changing any of them is a cheap live update with no respawn.

`glowColor` and `collidable` are **team properties** in vanilla Minecraft — there is no other mechanism
to set an entity's glow color or disable player collision — so setting either one (to a non-default
value) forces the NPC onto its own scoreboard team, exactly like hiding the nametag does (see
[Nametags](#nametags) above for the wire-name consequence of that). As a direct result: **an NPC with a
non-null `glowColor` cannot simultaneously show a custom-colored vanilla name plate**, because glow color
*is* the team's name-plate color and there's only one team-color slot. Use a floating nametag for
independent text color instead — which is almost certainly what you wanted anyway if you're setting a
glow color in the first place.

`showInTabList` controls the tab-list entry independently of the vanilla name plate. It's `false` (not
listed) by default specifically so that spawning many NPCs doesn't clutter every player's tab list with
entries that aren't real players. Toggling it re-sends the NPC (full despawn/respawn), because the
listed/unlisted flag lives in the same tab-list packet as the profile and skin. It only has any effect
on `PLAYER`-type NPCs — other entity types never receive a tab-list entry regardless of this setting.

All of the cosmetic state above (everything except pose and baby, see below) is available as a single
immutable `NpcAppearance` object, which is also exactly what `NpcData` stores for persistence:

```java
otherNpc.appearance(npc.appearance());   // copy one NPC's full look onto another
```

### Baby / adult state

```java
npc.baby(true);
```

`baby` only takes effect on entity types that actually support an adult/baby distinction in vanilla —
zombies, villagers, most animals, and so on — and is a **harmless no-op** on anything that doesn't
(players, skeletons, and any other type with no such concept). Whether a given `EntityType` qualifies is
decided by checking Bukkit's own `org.bukkit.entity.Ageable` interface hierarchy (via
`EntityType.getEntityClass()`), not a hand-maintained list of mob names — so it stays correct
automatically as Mojang adds new ageable mobs in future versions, with zero maintenance burden on this
library's side. Toggling it is a plain metadata push, not a respawn. `Capabilities.baby` reports whether
the underlying metadata field could be resolved on this server at all; see
[Troubleshooting](#troubleshooting) if it reports `false` unexpectedly.

### Mob variants (cat, wolf, frog, rabbit, parrot, axolotl, mooshroom, horse)

```java
npc.variant("black");       // Cat, Wolf, Frog: a named registry entry
npc.variant(2);             // Rabbit, Parrot, Axolotl, Mooshroom, Horse: a raw ordinal
```

Which overload actually applies is decided by `npc.type()`, exactly like `baby` — calling either one on
an entity type it doesn't apply to is a harmless no-op, and the value you set is retained even while
it's inapplicable, in case you later change the type to something it does apply to.

**`Cat`, `Wolf`, and `Frog`** use `variant(String)` — as of the versions this was implemented against,
their coloring is a live server **registry** lookup (a `Holder<CatVariant>` etc.), not a fixed ordinal,
because vanilla itself moved these to data-driven registries that a resource/data pack can add entries
to. A bare name like `"black"` is resolved with the `minecraft:` namespace assumed; use `"mymodpack:custom_cat"`
explicitly for anything from a data pack. An unknown name silently fails to apply (see
[Troubleshooting](#troubleshooting)) rather than throwing.

**`Rabbit`, `Parrot`, `Axolotl`, `Mooshroom`, and `Horse`** use `variant(int)` — these are still plain
integer-coded fields in vanilla, so there's no registry to look up, just a raw ordinal written straight
to the entity's own variant field. This library does not maintain a name-to-ordinal table for these
(unlike the registry-backed ones, there's no live authority to ask, and the meanings are baked into the
client), so you'll need to look up or test the specific ordinal-to-appearance mapping for the version(s)
you support. `npc.variant()` reads back whatever raw int was last set; `npc.variantName()` reads back
whatever registry name was last set — the two are stored independently, so switching an NPC's type back
and forth doesn't lose either one.

`Capabilities.mobVariants` reports whether *any* of the above resolved on this server — it's one combined
flag covering all eight mob types rather than one flag each, consistent with how `Capabilities.equipment`
already covers every equipment slot as a single flag.

### Villager profession, type, and level

```java
npc.villagerProfession("farmer")   // e.g. "farmer", "librarian", "cartographer", "none", ...
   .villagerType("plains")         // the biome-flavoured skin tint, e.g. "plains", "desert", "taiga"
   .villagerLevel(3);              // 1-5; the copper/iron/gold/emerald badge, clamped to at least 1
```

Only applies to `EntityType.VILLAGER`; a no-op on everything else, same as the other variant methods.
Unlike the coloring mobs above, villager data is a genuine **composite** value — profession and type are
each their own registry lookup (again resolved against the live server registry, so data-pack-added
professions/types work by name automatically), and the three together are packed into one `VillagerData`
object written as a single metadata field. All three of `villagerProfession`, `villagerType`, and
`villagerLevel` must resolve to something for *any* of them to apply — if either the profession or type
name doesn't exist in the registry, nothing is sent for that update at all (the previous value, if any,
is simply left in place) rather than sending a partially-valid `VillagerData`. There is no built-in list
of valid profession/type names in this library; the vanilla ones haven't changed in a long time (farmer,
librarian, cartographer, cleric, armorer, weaponsmith, toolsmith, butcher, leatherworker, fletcher,
fisherman, shepherd, mason, nitwit, none for profession; plains, desert, savanna, snow, swamp, taiga,
jungle for type) but any data pack on the server can add more.

`npc.mobVariant()` / `npc.mobVariant(MobVariant)` read/write all five of the above (the int variant, the
named variant, and the three villager fields) as one immutable `MobVariant` object in a single call,
mirroring how `appearance()`/`appearance(NpcAppearance)` work for cosmetic state — handy for copying one
NPC's variant configuration onto another, or restoring it from your own storage in one shot instead of
five separate calls. `Capabilities.villagerData` reports whether this specific subsystem resolved.

### Everything else: chicken/cow/pig variants, painting variants, and other mob-specific fields

A handful of other mobs (chicken, cow, pig, painting frames) also moved to the same kind of live-registry
variant system as cat/wolf/frog in recent versions, but don't have a typed method here yet — the
registry-lookup machinery `variant(String)` relies on is shared internally, so adding another mob to that
list is a small, low-risk addition if you need one; it just hasn't been done. Everything else — anything
not listed above, on any entity type — has no typed API at all and falls back to the raw metadata escape
hatch:

```java
npc.metadata(17, MetadataType.INT, 3);    // a simple int-valued field, by raw index, for a mob not listed above
npc.metadata(17, MetadataType.INT, null); // clear it
```

`BYTE`, `INT`, `BOOLEAN`, and `FLOAT` are supported — this covers flag bytes and simple integer/float
variants, but not composite/registry-backed fields, which this raw path has no way to represent at all.
**This is a genuinely dangerous escape hatch if you get the index wrong**: entity metadata indices are
assigned per entity class, in registration order, and are *not* validated against the entity type you're
targeting. Writing to an index that doesn't correspond to what you think it does on that particular
entity type will silently write garbage into whatever field actually lives at that index for that type —
there is no error, no warning, just a corrupted or nonsensical-looking NPC. Indices also **change between
Minecraft versions**, so any index you hardcode should be treated as tied to a specific version range,
not assumed portable. A value that doesn't fit its declared `MetadataType` (e.g. a `String` passed as
`INT`) is silently skipped rather than breaking the rest of the NPC's metadata packet, but a well-typed
*wrong* value at a plausible-looking index will not be caught — verify visually in-game after using this,
on every Minecraft version you support.

## Equipment

```java
npc.equipment(EquipmentSlot.HEAD, helmet);
npc.equipment(EquipmentSlot.HAND, sword);
npc.equipment(EquipmentSlot.HAND, null);   // clears the slot (an ItemStack of AIR also clears it)
```

Works identically on player and mob NPCs, and applies live without a respawn — a single equipment packet
per change. `EquipmentSlot.BODY` (horse/wolf armor, on 1.20.5+) is supported where the running server's
NMS classes expose it; on older servers that slot is silently ignored rather than throwing, consistent
with this library's overall "degrade a feature rather than break the NPC" philosophy.

## Clicks

For the simple case, a one-shot listener:

```java
npc.onClick((player, clicked, type) ->
    player.sendMessage("You " + type + "-clicked " + clicked.name()));
```

For anything with ordering, delay, or conditions, use actions instead. They form an ordered list per
click type (`LEFT`/`RIGHT` are tracked completely separately), run in the order they were added, and
each one can carry its own delay:

```java
npc.addAction(ClickType.RIGHT, Actions.message("<green>Hello %player%!"))
   .addAction(ClickType.RIGHT, Actions.sound(Sound.ENTITY_VILLAGER_YES, 1f, 1f), 10L)
   .addAction(ClickType.RIGHT, Actions.consoleCommand("give %player% diamond 1"))
   .cooldown(2000);
```

The built-in actions are `message`, `command`, `consoleCommand`, `teleport`, `sound`,
`connectToServer`, and `requirePermission`. `%player%` (the clicking player's name) and `%npc%` (the
NPC's display name) are substituted into any text-bearing action for you. Actions compose functionally:

```java
Actions.command("/warp shop")
       .when(ctx -> ctx.player().hasPermission("shop.use"))
       .then(Actions.message("<gray>Off you go."));
```

`Actions.connectToServer(name)` sends the clicking player to another backend server through a BungeeCord
or Velocity proxy, over the standard `BungeeCord` plugin-messaging channel. **Your own plugin must
register that outgoing channel first** —
`Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord")` — FoliaNPC deliberately never
registers anything on your behalf that you didn't explicitly ask for; if you forget this step, the
action silently does nothing (the plugin-messaging channel simply isn't open, so the packet the proxy
would need to see never leaves the server, with no exception on your end).

An action is just a `NpcAction` — a lambda over a context (`NpcClickContext`) that carries the click
details plus thread-safe ways to schedule follow-up work, so you never have to reason about which Folia
thread you're currently on:

```java
npc.addAction(ClickType.RIGHT, ctx -> {
    ctx.player().sendMessage("clicked " + ctx.click() + (ctx.sneaking() ? " while sneaking" : ""));
    ctx.data().put("step", 1);                        // scratch map, shared with later actions in this one click
    ctx.runGlobal(() -> world.strikeLightning(loc));  // global-region-thread work (console commands, world edits)
    ctx.runAsync(() -> database.load(...));           // off the server threads entirely; never touch Bukkit here
    ctx.runLater(() -> ..., 20L);                      // delayed, still follows the player across regions
    ctx.cancelRemaining();                             // skip every action still queued after this one, for this click
});
```

A listener or action that throws an exception is caught, logged as a warning (naming the NPC), and
skipped — one buggy handler never breaks the rest of the chain or crashes anything. `cooldown(millis)`
is tracked per clicking player, not globally, and any click that lands inside another player's cooldown
window is simply dropped (no message, no event, nothing — treat it as if the click never happened).
`ctx.sneaking()` (and `event.isSneaking()` on the Bukkit event, below) exposes whether shift was held
during the click, which is the standard way to implement a shift-click as a secondary action distinct
from a normal click.

### Reach validation

A click is only actually acted on when the clicking player's last known tracked position is plausibly
near the NPC — this rejects an interact packet forged (by a modified client) to claim a click on an NPC
the player is nowhere near, since the raw interact packet itself carries no position for the server to
check against. This check fails **open** (i.e. the click is allowed through) when no tracked position is
known yet for that player at all, which is normal and expected in the very first instant after they join
before their first position snapshot has been taken — the alternative (failing closed) would silently
drop a player's very first legitimate click on any NPC visible immediately on spawn.

## Events

FoliaNPC fires ordinary Bukkit events, so other plugins (permission plugins, region-protection plugins,
anything) can react to NPCs they did not create themselves:

```java
@EventHandler
public void onNpcClick(NpcInteractEvent event) {
    if (!canInteractHere(event.getPlayer())) {
        event.setCancelled(true);   // stops both the NPC's own listener and every one of its actions
    }
    if (event.isSneaking() && event.getClick() == ClickType.RIGHT) {
        openAdminMenu(event.getPlayer(), event.getNpc());
    }
}

@EventHandler public void onSpawn(NpcSpawnEvent event) { ... }   // fires for every NPC registered, including ones you didn't create
@EventHandler public void onRemove(NpcRemoveEvent event) { ... } // fires once the NPC is already gone; not cancellable
```

`NpcInteractEvent` is `Cancellable` and fires **before** the NPC's own `onClick` listener and its
registered actions run, specifically so that a region-protection plugin (or anything else that doesn't
own the NPC) can veto an interaction by cancelling the event — cancelling stops the listener and every
action from running at all for that click. It fires on the clicking player's own region thread, the same
threading guarantee a vanilla Bukkit entity-interact event gives you, even though the underlying packet
was actually decoded on a netty I/O thread first and had to hop across before the event was ever raised.

## Persistence

The library stores nothing itself, on disk or otherwise. It's a library, not a plugin with a data
folder, so it makes no assumptions at all about your database engine, file format, or schema. Instead it
gives you a plain, serializable snapshot type and a way back from it:

```java
List<NpcData> saved = npcs.saveAll();   // typically on shutdown; or npc.data() for just one NPC
npcs.spawnAll(saved);                   // typically on startup; or npcs.spawn(data) for just one
```

`NpcData` is a plain record of primitive/simple values (id, name, type, world/x/y/z/yaw/pitch, skin,
mirror-skin flag, equipment map, nametag lines, appearance, pose, baby flag, tab-list flag, mob
variant, owner), so it serializes cleanly with whatever you already use — Gson, Jackson, a config
library, a database row mapper, anything that can handle a POJO/record. Re-spawning from a saved
`NpcData` **keeps the original id**, so anything you have keyed on `npc.id()` elsewhere in your own
data still matches up correctly after a server restart.

`npc.owner(uuid)` / `npc.owner()` attach whoever the NPC is attributed to — typically its creator. This
is **purely informational**: FoliaNPC itself never reads this value for any permission decision, view
logic, or anything else internally. It exists solely so you can build your own permission checks on top
of it (e.g. "only the owner or a server admin may edit this NPC"), without having to maintain a separate
side-table mapping NPC id to creator yourself.

## Diagnostics

`npcs.capabilities()` returns a `Capabilities` record reporting which optional subsystems actually bound
successfully on this specific server: `skins`, `nametags`, `namePlateHiding`, `equipment`, `scale`,
`richText`, `baby`, `mobVariants`, `villagerData`. Every one of these is independently optional by design — if the reflection needed
for one of them can't resolve on a given server build, that one feature silently degrades to a no-op
rather than the whole library refusing to start, so a mismatched or unusually-patched server never turns
into a hard crash. Check `capabilities().missing()` (a `List<String>` of human-readable names) at
startup if you want to surface a single warning to your own users, or fall back to a different behavior,
rather than silently losing a feature with no indication anything's different. Anything that fails to
bind also logs its own warning line to the console at startup regardless of whether you check
`capabilities()` yourself.

| Capability | What losing it means |
|---|---|
| `skins` | `npc.skin(...)` has no visible effect; player NPCs render with the default Steve/Alex skin |
| `nametags` | Floating nametags (`npc.nametag(...)`) cannot be created at all |
| `namePlateHiding` | `nametagVisible(false)`, `glowColor`, and `collidable(false)` cannot suppress/set the team, so the vanilla plate and default collision behavior stay in effect regardless |
| `equipment` | `npc.equipment(...)` has no visible effect |
| `scale` | `npc.scale(...)` has no visible effect; the NPC always renders at 1.0 |
| `richText` | Gradients/hover text degrade to legacy section-sign color codes |
| `baby` | `npc.baby(...)` has no visible effect on any entity type |
| `mobVariants` | `npc.variant(...)` has no visible effect on any of cat/wolf/frog/rabbit/parrot/axolotl/mooshroom/horse |
| `villagerData` | `npc.villagerProfession(...)`/`villagerType(...)`/`villagerLevel(...)` have no visible effect |

`npcs.stats()` returns a `Stats` record: a cheap, instantaneous snapshot of current load — the live NPC
count, the number of viewer *pairings* (one NPC seen by 20 different players counts as 20, not 1), the
running total of packets sent since startup, and the duration of the most recent tick pass in
milliseconds. It's cheap enough to poll on a timer for a live admin dashboard or a `/npcs debug` command.

`npcs.diag()` returns a one-line human-readable string (`npcs=N trackedPlayers=M`) suitable for logging
or a quick chat message, without needing to format a `Stats` record yourself.

`npcs.setDebug(true)` turns on verbose logging: every time an NPC is newly shown to a viewer, and every
packet-send failure, is logged at a visible level instead of being silently handled. Leave this off in
production — it's meant for diagnosing a specific problem, not for routine operation.

## Threading

Folia's one hard rule, which this entire library exists to work correctly under: **you may only touch a
player or any other real entity from the region thread that currently owns it.** Touching one from the
wrong thread — including the "global" region thread, and including a background thread from your own
async work — throws or silently corrupts state, depending on exactly what you touched.

The library handles this for you internally wherever it can:

- Player positions are snapshotted onto a plain data object (`PlayerTracker.Tracked`) on the player's own
  region thread, and the manager's per-tick visibility pass only ever reads those already-safe
  snapshots — it never calls back into a live `Player`/`Entity` object from the wrong thread.
- Every packet is sent on the *receiving* player's own region thread, scheduled via
  `entity.getScheduler()`.
- Anything that needs to touch **world/block data** rather than an entity (currently: only
  `navigateTo`'s route search) is scheduled via the **region** scheduler tied to a specific location,
  which is the correct primitive for that — not the global region thread, which is legal for
  entity-agnostic, world-agnostic work like dispatching a console command, but is **not** legal for
  reading blocks (see [Movement](#movement) for the specific exception this throws if you get it wrong).
- Console commands and anything else that isn't tied to one specific player or one specific chunk run on
  the **global** region scheduler.

In your own action code (see [Clicks](#clicks)), always prefer the `NpcClickContext` helpers
(`ctx.run`, `ctx.runLater`, `ctx.runGlobal`, `ctx.runAsync`) over calling Bukkit's own scheduler
directly — they already know which of the above categories your work falls into and route it correctly,
without you having to reason about Folia's region model yourself. The `Npc` handle itself is safe to
call from **any** thread at any time; every mutable field behind it is `volatile` or an appropriately
concurrent collection specifically so that holding an `Npc` reference and calling setters on it from,
say, an async database callback, is always safe.

## Troubleshooting

Concrete symptoms, ordered roughly by how often they come up, and what's actually going on when you hit
them.

### The NPC never appears at all

- Check `npcs.capabilities()` — if `skins` is `false` for a `PLAYER`-type NPC you set a skin on, the
  *NPC itself* still spawns, just with no skin, so a totally invisible NPC is not a capabilities issue.
- Check the NPC's world and position against `npc.viewDistance()` (or the global default, 48) versus
  where you're actually standing — the manager's visibility pass is distance-and-world-based and will
  correctly *not* show an NPC that's genuinely out of range or in a different world.
- Check you actually called `.spawn()` on the builder, or that `manager.create(...)`/`FoliaNpc.spawn(...)`
  didn't throw before returning.
- Check the plugin holding the `FoliaNpc` instance is actually enabled — if `plugin.isEnabled()` is
  `false` (mid-shutdown, or a plugin load-order issue), every scheduler dispatch this library makes
  silently no-ops rather than throwing, so nothing will ever be sent to anyone.
- If you're testing immediately after a fresh player join, remember the very first visibility pass for a
  newly-joined player happens on the next tick after their join event, not synchronously during it —
  give it a fraction of a second.

### The NPC appears, but has no skin / the wrong skin

- `skin()`/`mirrorSkin()` only apply to `EntityType.PLAYER` NPCs. Check `npc.type()`.
- Check `capabilities().skins()` — if it's `false`, the profile-with-textures reflection path never
  resolved on this server build, and skins can never apply regardless of anything else you do.
- `fetchSkin`/`fetchSkinFromUrl` are asynchronous — if you call `npc.skin(...)` at all before the future
  completes, you'll briefly see the default skin. This is expected; there's no synchronous skin-fetch
  path by design (Mojang's API is a real network call).
- A skin fetch that fails (bad player name, Mojang API down, Mineskin rate limit) never caches the
  failure, but it also never retroactively applies once it *does* succeed unless you called `.thenAccept`
  on the returned future in the first place — check you're not silently swallowing the future.
- Mirror mode (`mirrorSkin(true)`) overrides whatever static skin is set, for every viewer, using *their
  own* skin — if you expected a specific static skin and instead everyone sees themselves, mirror mode is
  almost certainly still on.

### The floating nametag doesn't show, or the vanilla plate doesn't hide

- Check `capabilities().nametags()` for floating text, and `capabilities().namePlateHiding()` for
  suppressing the vanilla plate — both are independently optional and can fail on an unusual server build
  without breaking anything else.
- An **empty** list (`npc.nametag(List.of())`) is treated as "no floating nametag" and restores the
  vanilla plate — this is intentional, not a bug, see [Nametags](#nametags).
- If you're using a placeholder resolver and the text looks stale, check whether you're calling
  `refreshNametag()` (or have `autoRefreshNametag` set) after the underlying value actually changes —
  nametag text is not re-resolved automatically on every tick, only on an explicit refresh.

### Scale/baby/glow/collidable don't do anything

- Check the relevant `Capabilities` flag first (`scale`, `baby`) — both rely on server-specific
  reflection that can fail independently of everything else.
- `baby` is a no-op on any entity type that doesn't support an adult/baby distinction in vanilla — see
  [Baby / adult state](#baby--adult-state) for exactly how that's determined. Setting it on a `PLAYER`
  or `SKELETON` NPC, for example, is expected to visibly do nothing.
- `glowColor` cannot be combined with a custom vanilla name-plate color — see [Appearance](#appearance).
  This is an actual Minecraft engine limitation, not something this library can work around.

### `variant`/`villagerProfession`/`villagerType`/`villagerLevel` don't do anything

- Check `Capabilities.mobVariants` / `Capabilities.villagerData` first — this is, by a wide margin, the
  most version-fragile part of the entire library (see [Design](#design) and
  [Known limitations](#known-limitations)): it depends on live server registry access, and on this exact
  class name existing (`net.minecraft.resources.Identifier` in current versions, `ResourceLocation` in
  older ones) — either flag reporting `false` means that resolution failed on this server build entirely,
  and every method in this family is a guaranteed no-op regardless of anything else.
- Calling `variant(int)` on a `Cat`/`Wolf`/`Frog`, or `variant(String)` on anything else, is a no-op —
  check which overload applies to your entity type in [Mob variants](#mob-variants-cat-wolf-frog-rabbit-parrot-axolotl-mooshroom-horse).
- For the registry-backed ones (cat/wolf/frog coloring, villager profession/type), an unrecognized name
  silently fails to apply rather than throwing — double check spelling, and remember a bare name is
  assumed to be in the `minecraft:` namespace.
- For villager specifically: profession, type, *and* level all have to resolve for any of them to take
  effect. If you set a valid profession but a typo'd type, **neither** applies, not just the type — see
  [Villager profession, type, and level](#villager-profession-type-and-level).
- For the plain-int mobs (rabbit/parrot/axolotl/mooshroom/horse), remember there's no validation of the
  *value* at all beyond "is this entity type one of these five" — an out-of-range ordinal is written
  as-is and however the client happens to render it (typically it clamps or shows the last valid variant,
  but this isn't something this library controls).

### `showInTabList` doesn't show the NPC in the tab list

- It only applies to `PLAYER`-type NPCs. Other entity types never get a tab-list entry at all, by
  design, regardless of this flag.
- Toggling it triggers a full respawn — if you don't see it take effect, confirm the toggle call is
  actually being reached (log it) rather than assuming the respawn itself failed.

### Actions/click listener don't fire

- Check `cooldown()` — a click inside another click's cooldown window for the *same player* is dropped
  silently, with no event, no log line, nothing observable at all by design.
- Check whether another plugin is cancelling `NpcInteractEvent` — cancelling it stops both your listener
  and every action for that click; add a low-priority listener of your own that logs
  `event.isCancelled()` to confirm.
- Check the clicking player's tracked position isn't wildly stale relative to the NPC (see
  [Reach validation](#reach-validation)) — this only rejects genuinely implausible clicks and fails open
  when no position is known yet, but a player who just teleported through some other plugin without
  triggering a position refresh could theoretically be affected until their next tracked update.
- `Actions.connectToServer` requires you to have registered the `BungeeCord` outgoing plugin-messaging
  channel yourself — see [Clicks](#clicks). This is the single most common reason that specific action
  appears to silently do nothing.

### `navigateTo` always returns `false`

- Confirm `target` is in the *same world* as the NPC — cross-world routes are rejected immediately,
  before any search even runs, exactly like `walkTo` falling back to a teleport.
- The goal itself must be a standable position (solid ground below, open space at foot and head height)
  — a route search that starts or ends inside a wall, or hovering in mid-air with nothing solid
  underneath, cannot succeed no matter how open the terrain in between is.
- The search is bounded (128 blocks radius, 4000 expanded nodes, both by default) — a genuinely distant
  or maze-like target can exhaust the budget and fail even though a route technically exists; this is a
  deliberate tradeoff to keep worst-case search cost predictable, not a bug.
- A route that has to squeeze through a gap narrower than the search's model of "open" (diagonal
  corner-cutting is deliberately rejected, see [Movement](#movement)) will correctly fail to find a path
  through that gap even if a real player could walk through it.

### `navigateTo` throws, or logs a Folia/Canvas thread-check exception

If you see anything resembling `Cannot read world asynchronously` or `Thread failed main thread check`
coming out of `RoutePlanner`/`BukkitWorldSampler`/`AStar`, you're running a version of this library
older than the fix described in [Movement](#movement) — upgrade. If you're seeing it on a code path
*other* than `navigateTo` after modifying this library yourself, you've most likely added a new
world/block read scheduled via `Schedulers.global(...)` instead of `Schedulers.onRegion(...)` — the
global region thread is legal for entity-agnostic, world-agnostic work only.

### `FoliaNpc.create()` throws `IllegalStateException`

This means either the running server reports a version older than 1.20.6, or one of the *mandatory*
(non-optional) reflection lookups the packet layer needs to bind at all — entity spawn/move/remove
packets, the tab-list update packet, the basic entity-metadata packet — failed to resolve. Unlike the
individually-optional `Capabilities` (skins, nametags, scale, etc.), these are load-bearing for the
entire library and there is no degraded mode: either the whole thing works, or `create()` refuses to
start rather than handing you a `FoliaNpc` that would silently do nothing. Check your actual server
version and build against the exact error message, which names the specific NMS class or field that
could not be found.

### Everything looks slightly wrong only for some players

If nametags, skin layers, or other metadata-driven visuals misbehave *specifically* for players on a
different Minecraft client version than the server, look at ViaVersion (or a similar cross-version
compatibility layer) first — it rewrites entity metadata packets in flight to match what the older/newer
client expects, and that rewriting can miss or mistranslate fields this library sends that weren't
present in the version ViaVersion is translating for. This is a limitation of running a cross-version
setup at all, not something specific to FoliaNPC.

### Memory or thread-count grows across repeated `/reload`s

Confirm `close()` is actually being called in your plugin's `onDisable()` — see [Quick start](#quick-start).
Skipping it leaves the injected netty packet-read handler attached to every still-connected player's
pipeline, and leaves the skin-fetch HTTP client's background thread pool running indefinitely, both of
which outlive the reload and accumulate with each subsequent one.

## Known limitations

- **No gravity, no physics.** Position never changes except through `teleport`, `walkTo`, or
  `navigateTo`. An NPC spawned in mid-air, or left standing where the ground is later removed, will
  float indefinitely — nothing in this library will ever move it on its own to compensate.
- `navigateTo`'s pathfinding is a simple bounded grid search (see [Movement](#movement)), not full mob
  AI: no doors, no ladders, no water, no multi-block structure awareness, and a bounded search radius and
  node budget that a sufficiently distant or maze-like target can exhaust.
- Typed mob-variant support covers baby state, villager profession/type/level, and cat/wolf/frog/
  rabbit/parrot/axolotl/mooshroom/horse coloring — see [Mob variants](#mob-variants-cat-wolf-frog-rabbit-parrot-axolotl-mooshroom-horse)
  and [Villager profession, type, and level](#villager-profession-type-and-level). This is the single
  most version-fragile part of the library: it depends on live server registry access and on specific
  NMS class names (`Identifier` vs. the older `ResourceLocation`, entity classes that have moved between
  packages across versions) that this library tries several known variants of but cannot guarantee for
  every future Minecraft release. Everything else (chicken/cow/pig/painting variants, and any field on
  any mob not listed above) has no typed API and falls back to the raw `metadata` escape hatch.
- Glow colour cannot be combined with a custom vanilla name-plate colour — an actual Minecraft engine
  constraint (both come from the same team-color slot), not something this library chooses to restrict.
- ViaVersion (or similar) can rewrite entity metadata when a client's version differs from the server's;
  nametags and skin layers are the parts most likely to be affected for cross-version clients.
- The reflection layer (`internal/protocol/nms`) has no automated test coverage, because it needs a live
  server to exercise real NMS classes. The unit test suite covers all the pure logic that can be tested
  without one — visibility, geometry, click routing, cooldowns, actions, nametag layout, events, skin
  caching, and the pathfinding algorithm itself (via a fake in-memory grid) — but anything that actually
  talks to NMS, including skin mirroring's texture substitution, the pathfinder's real block reads, and
  every mob-variant/villager-data registry lookup, has to be verified in game on each supported
  Minecraft version. The manager-level wiring for mob variants and villager data (that the right value
  ends up in the right snapshot field, round-trips through `NpcData`, and doesn't force a respawn) is
  unit tested; only the actual registry resolution and packet construction is not.

## Building

```bash
mvn package
```

Produces a plain library jar with no plugin descriptor, and runs the full unit test suite as part of the
build — a failing test fails the build.

```bash
mvn install
```

Same, but also installs the jar to your local Maven repository so another local project (a plugin that
depends on this library, or a separate test harness) can resolve it as a Maven dependency without
needing it published anywhere.
