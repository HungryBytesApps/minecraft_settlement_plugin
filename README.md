# VillagerWorkers

Build a settlement with settlers who work and live their own lives. Server-side only, no client mods.

## Creating a settlement

Right-click a **bell** → create a settlement. You get the **Settlement Ledger** (one per player).
- **Bell menu**: recruit, show border, get a ledger, rename, disband.
- **Bell menu** (the settlement's rules): recruit (gets more expensive per settler, `recruit-cost` tiers), rename, show border, auto assign jobs, use all chests, when attacked, settlers consumption (Off / Food only / Food & Potions), get a ledger, disband.
- **Ledger menu** (running the settlement): settlers table (click a row for its menu), workstations, storage targets.
The settlement border is shown with particles, only for you and only near you (`border-particle`, `work-area-particle` in the config).
Bell menu → **Show border**: When holding ledger / On / Off.

## Settlers

- **Recruit** at the bell (price in `recruit-cost`): a new settler walks into the settlement.
- Or **hire** a wild villager: right-click it with the ledger. If it's far away, it follows you when you're near.
- Right-click your settler: its menu (others just say hi) (rename, skin, inventory as a chest window, role worker/guard, guard settings, leave workplace, dismiss).

## Workplaces

Place a workplace block in the settlement and add as many settlers as you like (they all work in the whole settlement and share the work) (ledger on it → Choose settler), or turn on "Free settlers take jobs" at the bell. Ledger on it: its settings (sliders apply when you close the window).
- **Composter** – farmer: harvests (all ripe crops first), then replants all farmland in the settlement with the same crop. Also melons and pumpkins from stems, sugar cane, cactus and bamboo (keeps the bottom piece), sweet berries and cocoa. Makes melon blocks from slices when you set a melon target. Make compost: Off / On / Overflow only (only its own harvest, never things from storage). Use bone meal: On/Off (only on crops still needed).
- **Smoker** – cook, **Blast furnace** – smelter, **Furnace** – furnace worker (choose: food, ores, stone & building, other):
  fetch things to cook/smelt and fuel (coal, charcoal, wood) from storage, collect the results.
- **Loom** – herder (shuts gates right behind it; can climb over a fence with a carpet on top): keeps each kind of animal in its area at its own herd size (set per kind; 0 = leave alone): breeds with food from storage, slaughters the extra adults,
  shears sheep (shears from storage), throws eggs into the chickens' pen for chicks, brings meat, wool, leather, eggs and feathers to storage.
- **Fletching table** – lumberjack: fells real trees (logs on dirt with natural leaves) inside the settlement or just outside it (toggle), with an axe from storage, replants saplings.
- **Smithing table** – smith: makes weapons, armor, bows and arrows from storage materials, only what you ask for
  (What to make: how many to keep in stock, 0 = don't make).

## Storage

Settlers use the chests and barrels in the settlement (on by default, `chests-allowed-by-default`).
- Ledger on a chest: allow/deny putting things in and taking things out.
- Ledger menu: **Use all chests: On/Off**.
- Harvests go to an allowed chest that already holds that item, otherwise the nearest one with space.
- Farmers fetch seeds from storage when they run out.
- No room left: settlers keep the goods in their pockets (9 slots), stop harvesting and you get a message (at most every 5 minutes).

## Storage targets

Ledger menu → **Storage targets**: per workstation, its products in alphabetical order (icon, in storage / target). Click one to change its target with −/+ buttons.
No limit by default (keep producing). Settlers stop making a product once storage holds the target (farmers keep replanting).

## Settlers

Settlers are player-like (Mannequins): their own skin (`skins` in the config), with a name that fits it (`names-male`, `names-female`, `skin-gender`), their job under their name,
and only tools they really carry in hand. Look at a settler and it looks back at you. They turn their heads smoothly, glance around, nod while chatting, crouch to work the fields, really eat, draw their bows and blow their horns. Old villager settlers are converted automatically.
Skins per job: `job-skins` in the config (url:/value: from mineskin.org, texture: from a resource pack, or player:Name).
Own skins: put them in the server resource pack and add `texture:namespace:path/to/skin` (or `...:slim`) to `skins` in the config.
Hiring a wild villager turns it into a settler; dismissing a settler turns it back into a villager.

## Weather

When it rains, settlers with nothing to do go inside. In a thunderstorm everyone (except guards) goes under the nearest roof in the settlement, even from work. Rain: buildings with a door first (anywhere in the settlement).

## Free time

During the day: conversations of 2 or 3 settlers telling little stories (128 built in, add your own in `my-conversations.yml`), with a mumbling voice per settler (`talk-sounds`, `voice-sound`), pottering around their workplace, walks, reading (on a bench or standing), making music together,
visiting someone at work, sitting down, looking around.

In the evening everyone gets together at the gathering spot: the place in the settlement with the most seats
close together (stairs, bottom slabs, cushions; a campfire nearby counts extra), or the bell. They sit, talk,
play music and read until morning. Tune `sit-height` if they sit too high or low.

## Guards and defense

Settler menu → **Role: Guard**. Guards take the best weapon, armor, a bow and arrows (`arrows-to-carry`) only from chests
settlers may take from. No weapon there → a warning, and "no weapon!" in the settlers list.
- **Stance**: Defensive (stays at its post, fights mobs within its radius, default `guard-radius` 16; set with
  **Set post here** and **Guard radius**), Patrol (walks mostly along the inside of the border, sometimes through the middle),
  Escort (follows you inside the settlement).
- **Weapon**: Auto, Melee or Archer. Archers get arrows as soon as they run out; others refill only after a fight.
- A guard that gets hit goes after its attacker. Guards keep guarding at night.

Hostile mobs in the settlement ring the bell and you get a message. Other settlers react as set in the ledger:
**Take cover** (run into the nearest building with a door and close it behind them; otherwise under a roof near the gathering spot; nowhere to hide: they run around the settlement, away from the monsters) or **Call to arms** (borrow a weapon from storage, fight, bring it back;
no weapon → take cover).

All settlers can be hurt and die, but never by players. They heal slowly (`regen-seconds`) and eat food from storage
below `eat-below-health-percent` if **Settlers may eat** is on; guards eat first. **Guards use potions** (off by default):
guards carry and drink healing potions in a fight. Health shows under the name when hurt (red when below half) and in the
settlers list. Dead settlers drop what they carried and wore.

## Build the jar

Push to GitHub: the workflow builds it under Actions → Artifacts.


Above each settler: its job (and health when hurt), under it what it's doing. Names show in the menus and conversations.
