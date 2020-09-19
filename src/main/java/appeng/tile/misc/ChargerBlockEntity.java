/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.tile.misc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Direction;

import alexiil.mc.lib.attributes.Simulation;
import alexiil.mc.lib.attributes.item.FixedItemInv;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.PowerUnits;
import appeng.api.definitions.IItemDefinition;
import appeng.api.definitions.IMaterials;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.implementations.tiles.ICrankable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.core.Api;
import appeng.core.settings.TickRates;
import appeng.me.GridAccessException;
import appeng.tile.grid.AENetworkPowerBlockEntity;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.util.Platform;
import appeng.util.inv.InvOperation;
import appeng.util.inv.filter.IAEItemFilter;
import appeng.util.item.AEItemStack;

public class ChargerBlockEntity extends AENetworkPowerBlockEntity implements ICrankable, IGridTickable {
    private static final int POWER_MAXIMUM_AMOUNT = 1600;
    private static final int POWER_THRESHOLD = POWER_MAXIMUM_AMOUNT - 1;
    private static final int POWER_PER_CRANK_TURN = 160;

    private final AppEngInternalInventory inv = new AppEngInternalInventory(this, 1, 1);

    private final FixedItemInv externalInv = inv.createFiltered(new ChargerInvFilter());

    public ChargerBlockEntity(BlockEntityType<?> tileEntityTypeIn) {
        super(tileEntityTypeIn);
        this.getProxy().setValidSides(EnumSet.noneOf(Direction.class));
        this.getProxy().setFlags();
        this.setInternalMaxPower(POWER_MAXIMUM_AMOUNT);
        this.getProxy().setIdlePowerUsage(0);
    }

    @Override
    public AECableType getCableConnectionType(final AEPartLocation dir) {
        return AECableType.COVERED;
    }

    @Override
    protected boolean readFromStream(final PacketByteBuf data) throws IOException {
        final boolean c = super.readFromStream(data);
        try {
            final IAEItemStack item = AEItemStack.fromPacket(data);
            final ItemStack is = item.createItemStack();
            this.inv.setInvStack(0, is, Simulation.ACTION);
        } catch (final Throwable t) {
            this.inv.setInvStack(0, ItemStack.EMPTY, Simulation.ACTION);
        }
        return c; // TESR doesn't need updates!
    }

    @Override
    protected void writeToStream(final PacketByteBuf data) throws IOException {
        super.writeToStream(data);
        final AEItemStack is = AEItemStack.fromItemStack(this.inv.getInvStack(0));
        if (is != null) {
            is.writeToPacket(data);
        }
    }

    @Override
    public void setOrientation(final Direction inForward, final Direction inUp) {
        super.setOrientation(inForward, inUp);
        this.getProxy().setValidSides(EnumSet.of(this.getUp(), this.getUp().getOpposite()));
        this.setPowerSides(EnumSet.of(this.getUp(), this.getUp().getOpposite()));
    }

    @Override
    public boolean canTurn() {
        return this.getInternalCurrentPower() < this.getInternalMaxPower();
    }

    @Override
    public void applyTurn() {
        this.injectExternalPower(PowerUnits.AE, POWER_PER_CRANK_TURN, Actionable.MODULATE);

        final ItemStack myItem = this.inv.getInvStack(0);
        if (this.getInternalCurrentPower() > POWER_THRESHOLD) {
            final IMaterials materials = Api.instance().definitions().materials();

            if (materials.certusQuartzCrystal().isSameAs(myItem)) {
                this.extractAEPower(this.getInternalMaxPower(), Actionable.MODULATE, PowerMultiplier.CONFIG);

                materials.certusQuartzCrystalCharged().maybeStack(myItem.getCount())
                        .ifPresent(charged -> this.inv.setInvStack(0, charged, Simulation.ACTION));
            }
        }
    }

    @Override
    public boolean canCrankAttach(final Direction directionToCrank) {
        return this.getUp() == directionToCrank || this.getUp().getOpposite() == directionToCrank;
    }

    @Override
    public FixedItemInv getInternalInventory() {
        return inv;
    }

    @NotNull
    @Override
    public FixedItemInv getExternalInventory() {
        return externalInv;
    }

    @Override
    public void onChangeInventory(final FixedItemInv inv, final int slot, final InvOperation mc,
            final ItemStack removed, final ItemStack added) {
        try {
            this.getProxy().getTick().wakeDevice(this.getProxy().getNode());
        } catch (final GridAccessException e) {
            // :P
        }

        this.markForUpdate();
    }

    public void activate(final PlayerEntity player) {
        if (!Platform.hasPermissions(new DimensionalCoord(this), player)) {
            return;
        }

        final ItemStack myItem = this.inv.getInvStack(0);
        if (myItem.isEmpty()) {
            ItemStack held = player.inventory.getMainHandStack();

            if (Api.instance().definitions().materials().certusQuartzCrystal().isSameAs(held)
                    || Platform.isChargeable(held)) {
                held = player.inventory.removeStack(player.inventory.selectedSlot, 1);
                this.inv.setInvStack(0, held, Simulation.ACTION);
            }
        } else {
            final List<ItemStack> drops = new ArrayList<>();
            drops.add(myItem);
            this.inv.setInvStack(0, ItemStack.EMPTY, Simulation.ACTION);
            Platform.spawnDrops(this.world, this.pos.offset(this.getForward()), drops);
        }
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(TickRates.Charger.getMin(), TickRates.Charger.getMin(), false, true);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int TicksSinceLastCall) {
        return this.doWork() ? TickRateModulation.FASTER : TickRateModulation.SLEEP;
    }

    private boolean doWork() {
        final ItemStack myItem = this.inv.getInvStack(0);
        boolean changed = false;

        if (!myItem.isEmpty()) {
            final IMaterials materials = Api.instance().definitions().materials();

            if (Platform.isChargeable(myItem)) {
                final IAEItemPowerStorage ps = (IAEItemPowerStorage) myItem.getItem();

                if (ps.getAEMaxPower(myItem) > ps.getAECurrentPower(myItem)) {
                    final double chargeRate = Api.instance().registries().charger().getChargeRate(myItem.getItem());

                    double extractedAmount = this.extractAEPower(chargeRate, Actionable.MODULATE,
                            PowerMultiplier.CONFIG);

                    final double missingChargeRate = chargeRate - extractedAmount;
                    final double missingAEPower = ps.getAEMaxPower(myItem) - ps.getAECurrentPower(myItem);
                    final double toExtract = Math.min(missingChargeRate, missingAEPower);

                    try {
                        extractedAmount += this.getProxy().getEnergy().extractAEPower(toExtract, Actionable.MODULATE,
                                PowerMultiplier.ONE);
                    } catch (GridAccessException e1) {
                        // Ignore.
                    }

                    if (extractedAmount > 0) {
                        final double adjustment = ps.injectAEPower(myItem, extractedAmount, Actionable.MODULATE);

                        this.setInternalCurrentPower(this.getInternalCurrentPower() + adjustment);

                        changed = true;
                    }
                }
            } else if (this.getInternalCurrentPower() > POWER_THRESHOLD
                    && materials.certusQuartzCrystal().isSameAs(myItem)) {
                if (Platform.getRandomFloat() > 0.8f) // simulate wait
                {
                    this.extractAEPower(this.getInternalMaxPower(), Actionable.MODULATE, PowerMultiplier.CONFIG);

                    materials.certusQuartzCrystalCharged().maybeStack(myItem.getCount())
                            .ifPresent(charged -> this.inv.setInvStack(0, charged, Simulation.ACTION));

                    changed = true;
                }
            }
        }

        // charge from the network!
        if (this.getInternalCurrentPower() < POWER_THRESHOLD) {
            try {
                final double toExtract = Math.min(800.0, this.getInternalMaxPower() - this.getInternalCurrentPower());
                final double extracted = this.getProxy().getEnergy().extractAEPower(toExtract, Actionable.MODULATE,
                        PowerMultiplier.ONE);

                this.injectExternalPower(PowerUnits.AE, extracted, Actionable.MODULATE);
            } catch (final GridAccessException e) {
                // continue!
            }

            changed = true;
        }

        if (changed) {
            this.markForUpdate();
        }

        return true;
    }

    private static class ChargerInvFilter implements IAEItemFilter {
        @Override
        public boolean allowInsert(FixedItemInv inv, final int i, final ItemStack itemstack) {
            final IItemDefinition cert = Api.instance().definitions().materials().certusQuartzCrystal();

            return Platform.isChargeable(itemstack) || cert.isSameAs(itemstack);
        }

        @Override
        public boolean allowExtract(FixedItemInv inv, final int slotIndex, int amount) {
            ItemStack extractedItem = inv.getInvStack(slotIndex);

            if (Platform.isChargeable(extractedItem)) {
                final IAEItemPowerStorage ips = (IAEItemPowerStorage) extractedItem.getItem();
                if (ips.getAECurrentPower(extractedItem) >= ips.getAEMaxPower(extractedItem)) {
                    return true;
                }
            }

            return Api.instance().definitions().materials().certusQuartzCrystalCharged().isSameAs(extractedItem);
        }
    }
}