/*
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * M7d (fork): slow mana regen, mirrors Regeneration's shape — partial-point
 * accumulation, LockedFloor/Vault gated via Regeneration.regenOn(). Chalice/
 * salt/RingOfEnergy are HP-specific and intentionally NOT wired here.
 */

package com.shatteredpixel.shatteredpixeldungeon.actors.buffs;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.modding.BalanceConfig;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.utils.Bundle;

public class ManaRegen extends Buff {

	{
		// act after the hero (same slot as Regeneration) so mana refunds land
		// right after a turn is spent, not before.
		actPriority = HERO_PRIO - 1;
	}

	private float partialRegen = 0f;

	// M9c: delay now lives in BalanceConfig (default 8f, mod-overridable). Read
	// at use so a mod's balance override takes effect without recompiling.
	private static float manaDelay() { return BalanceConfig.MANA_REGEN_DELAY; }

	@Override
	public boolean act() {
		if (target.isAlive() && target instanceof Hero) {
			Hero hero = (Hero) target;
			if (Regeneration.regenOn() && hero.MP < hero.MPMax) {
				partialRegen += 1f / manaDelay();
				if (partialRegen >= 1f) {
					hero.MP = Math.min(hero.MPMax, hero.MP + (int) partialRegen);
					partialRegen -= (int) partialRegen;
				}
			}
			spend( TICK );
		} else {
			diactivate();
		}
		return true;
	}

	@Override
	public int icon() {
		return BuffIndicator.NONE; // M7d: no dedicated icon asset yet (simplified).
	}

	private static final String PARTIAL = "partial_mana";

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		bundle.put(PARTIAL, partialRegen);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		partialRegen = bundle.getFloat(PARTIAL);
	}
}
