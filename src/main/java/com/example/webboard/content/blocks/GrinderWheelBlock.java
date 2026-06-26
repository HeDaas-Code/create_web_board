package com.example.webboard.content.blocks;

import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;

/**
 * Grinder wheel — a shaft-style kinetic block (rotation axis along its longest direction).
 *
 * Why extends ShaftBlock and not KinecticBlock:
 *   ShaftBlock already wires rotation axis from blockstate AXIS property,
 *   handles speed-overflow checks, and propagates to neighbours via RotationPropagator.
 *   We only need to give it a stress-impact (energy cost) via the BlockEntity.
 */
public class GrinderWheelBlock extends ShaftBlock {
    public GrinderWheelBlock(Properties properties) {
        super(properties);
    }
}