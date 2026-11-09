# VillagerWorkers

Build a settlement with settlers who work and live their own lives. Server-side only, no client mods.

## Creating a settlement

Right-click a **bell** → create a settlement. You get the **Settlement Ledger**. Right-clicking the bell (or anything,
with the ledger in hand) opens the settlement menu.
The settlement border is shown with particles while you hold the ledger, or via **Show border** in the menu.

## Settlers

- **Recruit** at the bell (price in `recruit-cost`): a new settler walks into the settlement.
- Or **hire** a wild villager: right-click it with the ledger. If it's far away, it follows you when you're near.
- Right-click a settler: it says hi. With the ledger: its menu (rename, leave workplace, dismiss).

## Workplaces

Place a **composter** anywhere in the settlement: that's a farm. A free settler takes it and becomes a farmer, working the
fields around it (radius set with the ledger on the composter). Composters already there when you create the settlement count too.

## Storage

Settlers use the chests and barrels in the settlement (on by default, `chests-allowed-by-default`).
- Ledger on a chest: allow/deny putting things in and taking things out.
- Settlement menu: **All chests ON / OFF**.
- Harvests go to an allowed chest that already holds that item, otherwise the nearest one with space.
- Farmers fetch seeds from storage when they run out.

## Targets

Settlement menu → **Targets**: e.g. 256 wheat. Farmers stop harvesting a crop once settlement storage holds that much
(they keep replanting).

## Free time

Walks, chats with speech bubbles, looking around, sleeping in free beds near the bell at night.

## Build the jar

Push to GitHub: the workflow builds it under Actions → Artifacts.
