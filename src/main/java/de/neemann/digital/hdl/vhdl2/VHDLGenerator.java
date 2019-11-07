/*
 * Copyright (c) 2018 Helmut Neemann.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.hdl.vhdl2;

import de.neemann.digital.core.NodeException;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.PinException;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.hdl.hgs.HGSEvalException;
import de.neemann.digital.hdl.model2.HDLCircuit;
import de.neemann.digital.hdl.model2.HDLException;
import de.neemann.digital.hdl.model2.HDLModel;
import de.neemann.digital.hdl.model2.HDLNet;
import de.neemann.digital.hdl.model2.clock.HDLClockIntegrator;
import de.neemann.digital.hdl.printer.CodePrinter;
import de.neemann.digital.lang.Lang;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Used to create the vhdl output.
 * Takes the circuit, generates the hdl model ({@link de.neemann.digital.hdl.model2.HDLModel}.
 * After that the clocks are handled for the specific board.
 * And after that the {@link de.neemann.digital.hdl.vhdl2.VHDLCreator} is used to write the model
 * to a {@link CodePrinter} instance.
 */
public class VHDLGenerator implements Closeable {

    private final ElementLibrary library;
    private final CodePrinter out;
    private ArrayList<File> testBenches;
    private HDLModel model;
    private HDLClockIntegrator clockIntegrator;

    /**
     * Creates a new exporter
     *
     * @param library the library
     * @param out     the output stream
     */
    public VHDLGenerator(ElementLibrary library, CodePrinter out) {
        this.library = library;
        this.out = out;
    }

    /**
     * Exports the given circuit
     *
     * @param circuit the circuit to export
     * @return this for chained calls
     * @throws IOException IOException
     */
    public VHDLGenerator export(Circuit circuit) throws IOException {
        try {

            if (!circuit.getAttributes().get(Keys.ROMMANAGER).isEmpty())
                throw new HDLException(Lang.get("err_centralDefinedRomsAreNotSupported"));

            model = new HDLModel(library).create(circuit, clockIntegrator);
            for (HDLCircuit hdlCircuit : model)
                hdlCircuit.applyDefaultOptimizations();

            model.renameLabels(new VHDLRenaming());

            for (HDLCircuit hdlCircuit : model)
                checkForUniqueNetNames(hdlCircuit);

            out.println("-- generated by Digital. Don't modify this file!");
            out.println("-- Any changes will be lost if this file is regenerated.");

            new VHDLCreator(out, library).printHDLCircuit(model.getMain());

            File outFile = out.getFile();
            if (outFile != null) {
                testBenches = new VHDLTestBenchCreator(circuit, model)
                        .write(outFile)
                        .getTestFileWritten();
            }

            return this;
        } catch (PinException | NodeException | HDLException | HGSEvalException e) {
            throw new IOException(Lang.get("err_vhdlExporting"), e);
        }
    }

    private void checkForUniqueNetNames(HDLCircuit hdlCircuit) throws HDLException {
        ArrayList<HDLNet> nets = hdlCircuit.getNets();
        // try to resolve duplicate names
        for (HDLNet n : nets)
            if (n.isUserNamed())
                for (HDLNet nn : nets)
                    if (n.getName().equalsIgnoreCase(nn.getName()) && n != nn) {
                        String newName = "s_" + n.getName();
                        int i = 1;
                        while (exits(newName, nets))
                            newName = "s_" + n.getName() + (i++);
                        n.setName(newName);
                    }

        // throw an exception if there is still a duplicate name
        for (int i = 0; i < nets.size(); i++) {
            final HDLNet n1 = nets.get(i);
            for (int j = i + 1; j < nets.size(); j++) {
                final HDLNet n2 = nets.get(j);
                if (n1.getName().equalsIgnoreCase(n2.getName()))
                    throw new HDLException(
                            Lang.get("err_namesAreNotUnique_N", n1.getName() + "==" + n2.getName()),
                            hdlCircuit.getOrigin());
            }
        }
    }

    private boolean exits(String newName, ArrayList<HDLNet> nets) {
        for (HDLNet n : nets)
            if (n.getName().equalsIgnoreCase(newName))
                return true;
        return false;
    }

    /**
     * Sets a clock integrator
     *
     * @param clockIntegrator the clock integrator
     */
    public void setClockIntegrator(HDLClockIntegrator clockIntegrator) {
        this.clockIntegrator = clockIntegrator;
    }

    /**
     * @return the test bench files, maybe null
     */
    public ArrayList<File> getTestBenches() {
        return testBenches;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /**
     * @return the hdl model
     */
    public HDLModel getModel() {
        return model;
    }
}
