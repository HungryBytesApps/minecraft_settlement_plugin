# Settlement plugin

A settlement at a bell with settlers who work and live their own lives. Paper, server-side only.
Private plugin: everything you need to know is in this file.

## Permissions

Everyone can use it, no permissions needed.
- **Founder** (who created the settlement): everything.
- **Members** (bell → Members): use the ledger (settlers, workstations, storage targets, chests, hiring
  villagers) and settler menus, and get a ledger / notifications at the bell. Not: the other bell settings,
  dismissing settlers, expansions. Members also get the settlement's notifications.
- **Other players**: nothing; settlers just say hi.
- `settlement.admin` (ops by default): everything everywhere, and ignores `max-settlements-per-player` and
  `max-settlers-per-settlement`.

## Starting a settlement

Right-click a **bell** → create a settlement. The settlement is the square around the bell
(`settlement-radius` blocks in every direction). You get the **Settlement Ledger** (one per player).

### Size and shape
- **Radius** (bell, founder): slider `settlement-radius-min` to `-max` (16–64), applied when you go back.
- **Expansions**: place a bell inside the settlement and right-click it → Create expansion. It adds a square of
  its own (radius `expansion-radius-min` to `-max`, 4–24) to make any shape. Right-click it again for its radius
  or Remove expansion; breaking the bell removes it too.
- Settlements never overlap: growing stops at a neighbour, and a new settlement near another one gets a smaller
  radius (refused if not even the minimum fits).
- Shrinking or removing is refused when a workstation with workers or an expansion bell would fall outside.
- Everything follows the shape: border particles (outer edge only), patrols, fields, chests, loaded chunks, alarms.

### Bell menu (the settlement's rules)
- **Recruit settler**: costs items, more for every settler (`recruit-cost` tiers, `recruit-cost-increase`).
  A new settler (or a hired villager) walks to the bell and waits there for instructions (`newcomer-wait-seconds`, default 180),
  or until you give it a job or make it a guard; then it goes about its free time.
- **Rename**, **Radius**.
- **Auto assign jobs** (off by default): settlers without a job take a free workstation by themselves.
- **Use all chests**: On = settlers use every chest and barrel; Off = only the ones you allow with the ledger.
- **When attacked**: Take cover or Call to arms (see Defense).
- **Settlers consumption**: Off / Food only / Food & Potions (guards also drink healing potions in a fight).
- **Get a ledger**.
- **Notifications** (per player): which messages you get in the chat (under attack / all clear, missing
  tools & weapons, storage full, settler died) and whether the alarm bell rings.
- **Show border** (per player): particles along the border: when holding the ledger / on / off.
- **Members** and **Disband settlement** (founder only): all settlers drop their things and walk away.

### The ledger
Right-click with it:
- a **chest or barrel**: may settlers put things in / take things out? **Filter**: Off / Prefer (the chosen
  items go here first, others may too) / Only (nothing else goes in); **Filter items**: pick a group and click
  item icons. Settlers store an item in a chest whose filter lists it first, then where it already is, then
  anywhere. Taking: from every allowed chest.
   **Private**: only you can open, change or
  break it (ops with `settlement.admin` can open it too); settlers never use it (bell and ledger settings don't change that), hoppers can't take from it,
  explosions don't break it. Stored on the chest itself, so it stays private even after disbanding.
  Only its owner can place a chest next to it to make one big private chest; anyone else's chest stays separate.
- a **workstation**: its menu (workers, in use, settings).
- a **wild villager**: hire it. It walks to the settlement, or follows you when it's far away.
- anything else: the ledger menu: **Settlers** (click a row for its menu), **Workstations**, **Storage targets**,
  **Problems**: what went wrong lately (missing tools or materials, full storage, chests or workstations they
  can't reach, settlers that got stuck, attacks, deaths), newest first with how long ago, 100 lines, pages of 15.
  The same problem shows again only after 10 minutes; the log is empty again after a restart. **Clear** empties it.

Right-click one of your settlers (no ledger needed): its menu: rename, inventory (a chest window: take
things out or put things in), worker / guard, guard settings, leave workplace, dismiss (drops its things
and walks out of the settlement for good). While the menu is open it stops and looks at you.
Other players' settlers just say hi.

## Workstations

Place one in the settlement. Ledger on it → **Workers (n)**: add as many settlers as you like (free settlers
first, then ones with another job). Click a worker for its menu (Back returns to the list); **Remove** next to
it takes it off. All workers work in the whole settlement and share
the work. **In use** (checkbox) switches the workstation off without removing the workers.

- **Composter – farmer**: first harvests everything ripe, then replants. Wheat, carrots, potatoes, beetroot,
  melons and pumpkins (from stems), sugar cane, cactus and bamboo (bottom piece stays), sweet berries, cocoa.
  Replants with the same seed as before (or a neighbour's). Fetches seeds from storage.
  Melon blocks from slices when you set a melon block target. Nether wart on soul sand.
  - **Make compost**: Off / On / Overflow only (only its own harvest, never things from storage) → bone meal.
  - **Use bone meal**: on crops that are still needed.
- **Smoker – cook**: cooks raw food, and makes crafting-table food by storage target (no target = don't make):
  bread, cookies, cake (the buckets go back), pumpkin pie, mushroom stew, beetroot soup, rabbit stew. Sugar and
  bowls it makes itself (bowls only when there are none in storage); milk it asks the herder for.
- **Blast furnace – smelter**: ores and raw metal.
- **Furnace – furnace worker**: only what smokers and blast furnaces can't do (stone types, glass, bricks,
  dyes, charcoal…) and **only what you set a storage target for**.
- Furnace jobs fetch fuel from storage (coal, charcoal, wood).
- **Loom – herder**: per kind of animal a checkbox and **Adults** (2–20): breeds up to that number with food
  from storage and slaughters the extra adults. Unchecked kinds are left alone (no breeding, no killing).
  Shears sheep (shears from storage), collects drops and eggs, closes gates behind it, climbs over a fence
  with a carpet on top. **Bees** (On): harvests full beehives and bee nests, honey with a glass
  bottle (made from glass) and honeycomb with shears, by storage target; it lights a campfire under the hive for a
  moment first (free, players can't use or break it). A hive without room for one is left alone (notification
  "Beehive needs room for a campfire"). Milks a cow when someone asks for milk. **Throw eggs**: throws spare eggs into the chicken pen for chicks, only when there are
  fewer chickens than wanted and the eggs' storage target is reached (no egg target = no throwing).
- **Fletching table – lumberjack**: fells real trees (logs on dirt with natural leaves) with an axe from
  storage, inside or outside the settlement (toggle). **Replant saplings** On/Off.
- **Brewing stand – brewer**: brews every potion (also long, strong, splash and lingering) by storage target
  (Storage targets → pick an effect; no target = don't brew), with the real brewing stand: fills glass bottles at
  water near the stand, nether wart, then the ingredients one by one, blaze powder as fuel. Makes blaze powder,
  glistering melon, golden carrots, fermented spider eyes, magma cream, sugar and bottles itself; asks for glass
  and gold. Things only mobs or the world give (ghast tears, rabbit feet, phantom membranes, dragon's breath,
  turtle helmets, breeze rods...) must be in storage.
- **Barrel by water – fisher**: a barrel with water within 2 blocks is a fishing spot (other barrels stay storage).
  Each fisher picks its own spot on the shore within 7 blocks of the barrel (at least 3 blocks from other fishers)
  and fishes there with a rod from storage, faster in the rain. With the **PacketEvents** plugin on the server it
  looks like real fishing: players nearby see a fake player in the fisher's place (no name, not in the tab list) with a
  real rod, line and bobber; the fisher skins for it are the MineSkin links in `fisher-skins` in the config (fetched once at
  startup, matched by the skin's name, kept in `fisher-skins-cache.yml`; none = a default skin). Without PacketEvents: a simple bobber and a lead as line. Every 4 catches it keeps
  the catch in the barrel (3 slots; a pile of fish on the lid shows it) and takes it to the chests when it fills up. While
  a fisher works there the barrel is only for the fisher (players can't open it; settlers may take the fish out;
  broken: it drops its contents). Without a fisher it's a normal barrel (the workstation menu has its
  **Chest settings**). Catches like vanilla: fish 85%, junk 10%, treasure 5% only in open water (5x5, 2 deep, nothing on
  top; the workstation menu says if it is). Fish whose storage target is reached go back into the water; junk
  only when chosen in the **Junk filter**; treasure always kept.
- **Smithing table – smith**: weapons, armor, bows and arrows, **only what you set a storage target for**.

Tools wear out (axes per log, shears per sheep, weapons per hit, bows per shot, shields per blocked hit;
Unbreaking helps). Missing tool → a chat message (at most every 5 minutes per settler).

## Asking each other

A settler that needs an ingredient another job makes (glass from the furnace worker, gold ingots from the
smelter, milk from the herder...) walks over and asks for it, then waits. The other makes just enough, whatever
the storage targets say, walks over and hands it over (the item goes from hand to hand), "Here you go",
"Thanks". If it can't (nothing to make it from), it says sorry and they don't ask again for 2 minutes.
Ingredients never need storage targets: sugar, glass bottles, bowls, planks, blaze powder, gold nuggets and the
like are made by whoever needs them.

## Storage and targets

- Harvests go to an allowed chest that already holds that item, otherwise the nearest one with room.
- No room: settlers keep things in their pockets (9 slots), stop, and you get a message.
- Ledger → **Storage targets**: per workstation its products with −64/−16/+16/+64 and ∞.
  - No target = ∞ (keep making it), 0 = don't keep any.
  - Smith and furnace worker: no target or 0 = don't make it.
  - Settlers stop making a product when storage holds the target. Farmers keep replanting.

## Settlers

- Player-like (Mannequins). Above them "Name [Job]" (health when hurt), under it what they're doing.
- Names from `names-male` / `names-female`; rename in the settler menu.
- **Skins**: 8 people (4 men, 4 women), each with an outfit per job (`settler-skins: true`).
  A settler is always the same person; the outfit follows the job. The images are in the server resource
  pack: `assets/settlers/textures/skins/<person>_<outfit>.png` (outfits: settler, farmer, cook, smelter,
  furnace_worker, herder, lumberjack, smith, guard). Women use slim arms. Players without the pack see a
  missing texture. `job-skins` in the config overrides a job; `settler-skins: false` uses `skins`.
- They heal slowly (`regen-seconds`) and eat from storage below `eat-below-health-percent` (if consumption allows).
- Players can't hurt them. Monsters can; when they die they drop everything.
- A settler that falls somewhere it can't get out of is brought back to the bell (poof).

### Free time
Conversations of 2–3 settlers (128 stories built in, add your own in `my-conversations.yml`) with a
mumbling voice (`talk-sounds`, `voice-sound`), music bands of 2–5 playing along with a music disc (heard only near the band; `music-every-minutes`, default
20, per settlement; `music-volume`; `music-discs`), putting on a record
from storage at a jukebox, reading, walks, pottering around the workplace, visiting someone at work.
Evening: together at the spot with the most seats close together (stairs, slabs, carpets; a campfire counts
extra), or at the bell. `sit-height` if they sit too high or low.

### Weather
Rain: settlers with nothing to do go inside (buildings with a door first). Thunder: everyone except guards
goes under a real roof. They close the doors behind them.

## Guards and defense

Settler menu → **Role: Guard**. Guards take the best weapon, armor, shield, bow and arrows (`arrows-to-carry`)
from chests settlers may take from, and pick up loot after a fight.
- **Stance**: Defensive (stays at its post: **Set post here**, **Guard radius**, default `guard-radius`) or
  Patrol (walks along the border).
- **Weapon**: Auto (bow at range, melee up close), Melee or Archer.
- Creepers: knocked back, and guards run 9+ blocks away when one hisses.
- A monster that can't be reached for a while is ignored (it doesn't keep the alarm going forever).

Monsters in the settlement ring the bell. Other settlers **Take cover** (into a building with a door, doors
closed) or with **Call to arms** borrow a weapon from storage, fight, and bring it back.

## Other config

`keep-settlements-loaded` (true): the settlement's chunks stay loaded so it keeps working while you're away.
`border-particle`, `show-names`, `work-speed`, `walk-speed`, `work-at-night`, `seeds-to-keep`, `chat-lines`.

## Command and logs

`/settlers info`: build number and diagnostics of the settler you look at.
The console shows the build at startup. Errors are logged with their stack trace, at most once a minute per
settler or task, so one broken settler never stops the others.

## Data

`plugins/Settlement/`: `config.yml`, `towns.yml` (settlements), `my-conversations.yml`.

## Build the jar

Upload `src` to GitHub: the workflow builds it under Actions → Artifacts.
