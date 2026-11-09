# VillagerWorkers

Build a settlement with settlers who work and live their own lives. Server-side only, no client mods.

## Creating a settlement

Right-click a **bell** → create a settlement. You get the **Settlement Ledger** (one per player).
- **Bell menu**: recruit, show border, get a ledger, rename, disband.
- **Ledger menu** (right-click with the ledger): targets, use all chests, settlers, when attacked, settlers may eat, guards use potions.
The settlement border (orange) and work areas (green) are shown as walls of particles (only for you).
Bell menu → **Show border**: When holding ledger / On / Off.

## Settlers

- **Recruit** at the bell (price in `recruit-cost`): a new settler walks into the settlement.
- Or **hire** a wild villager: right-click it with the ledger. If it's far away, it follows you when you're near.
- Right-click a settler: it says hi. With the ledger: its menu (rename, inventory, role worker/guard, guard settings, leave workplace, dismiss).

## Workplaces

Place a **composter** anywhere in the settlement: that's a farm. A free settler takes it and becomes a farmer, working the
fields around it (radius set with the ledger on the composter). Composters already there when you create the settlement count too.

## Storage

Settlers use the chests and barrels in the settlement (on by default, `chests-allowed-by-default`).
- Ledger on a chest: allow/deny putting things in and taking things out.
- Ledger menu: **Use all chests: On/Off**.
- Harvests go to an allowed chest that already holds that item, otherwise the nearest one with space.
- Farmers fetch seeds from storage when they run out.
- No room left: settlers keep the goods in their pockets (9 slots), stop harvesting and you get a message (at most every 5 minutes).

## Targets

Ledger menu → **Targets**: e.g. 256 wheat. Farmers stop harvesting a crop once settlement storage holds that much
(they keep replanting).

## Settlers

Settlers are player-like (Mannequins): their own skin (`skins` in the config), their job under their name,
and their tool in hand (farmers carry a hoe). Old villager settlers are converted automatically.
Hiring a wild villager turns it into a settler; dismissing a settler turns it back into a villager.

## Free time

During the day: walks, chats with speech bubbles, reading (on a bench or standing), making music together,
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
**Take cover** (hide under a roof near the gathering spot) or **Call to arms** (borrow a weapon from storage, fight, bring it back;
no weapon → take cover).

All settlers can be hurt and die, but never by players. They heal slowly (`regen-seconds`) and eat food from storage
below `eat-below-health-percent` if **Settlers may eat** is on; guards eat first. **Guards use potions** (off by default):
guards carry and drink healing potions in a fight. Health shows under the name when hurt (red when below half) and in the
settlers list. Dead settlers drop what they carried and wore.

## Build the jar

Push to GitHub: the workflow builds it under Actions → Artifacts.
