package de.neemann.digital.gui.components.modification;

import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.graphics.Vector;

/**
 * Modifier to insert a wire.
 * Created by hneemann on 26.05.17.
 */
public class ModifyInsertWire implements Modification {
    private final Vector p1;
    private final Vector p2;

    /**
     * Creates a new instance
     *
     * @param w the wire to insert
     */
    public ModifyInsertWire(Wire w) {
        p1 = w.p1;
        p2 = w.p2;
    }

    @Override
    public void modify(Circuit circuit) {
        circuit.add(new Wire(p1, p2));
    }

    /**
     * @return null if this is a wire with zero length
     */
    public Modification checkIfLenZero() {
        if ((p1.x == p2.x) && (p1.y == p2.y))
            return null;
        else
            return this;
    }
}