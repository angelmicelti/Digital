package de.neemann.digital.hdl.vhdl;

import de.neemann.digital.core.NodeException;
import de.neemann.digital.core.io.Const;
import de.neemann.digital.core.io.Ground;
import de.neemann.digital.core.io.VDD;
import de.neemann.digital.core.wiring.Splitter;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.PinException;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.library.ElementNotFoundException;
import de.neemann.digital.hdl.model.*;
import de.neemann.digital.hdl.printer.CodePrinter;
import de.neemann.digital.hdl.printer.CodePrinterStr;
import de.neemann.digital.hdl.vhdl.boards.BoardInterface;
import de.neemann.digital.hdl.vhdl.boards.BoardProvider;
import de.neemann.digital.hdl.vhdl.lib.VHDLEntitySimple;
import de.neemann.digital.lang.Lang;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Exports the given circuit to vhdl
 */
public class VHDLExporter implements Closeable {
    /**
     * suffix for signals which are mapped output ports
     */
    public static final String SIG_SUFFIX = "_sig";

    private final CodePrinter out;
    private final ElementLibrary library;
    private final VHDLLibrary vhdlLibrary;
    private BoardInterface board;

    /**
     * Creates a new exporter
     *
     * @param library the library
     * @throws IOException IOException
     */
    public VHDLExporter(ElementLibrary library) throws IOException {
        this(library, new CodePrinterStr());
    }

    /**
     * Creates a new exporter
     *
     * @param library the library
     * @param out     the output stream
     * @throws IOException IOException
     */
    public VHDLExporter(ElementLibrary library, CodePrinter out) throws IOException {
        this.library = library;
        this.out = out;
        vhdlLibrary = new VHDLLibrary();
    }

    /**
     * Writes the file to the given stream
     *
     * @param circuit the circuit to export
     * @return this for chained calls
     * @throws IOException IOException
     */
    public VHDLExporter export(Circuit circuit) throws IOException {
        out.println("-- auto generated by Digital\n");

        try {
            File f = out.getFile();
            if (f != null)
                board = BoardProvider.getInstance().getBoard(circuit);

            ModelList modelList = new ModelList(library);
            HDLModel model = new HDLModel(circuit, library, modelList).setName("main");

            fixClocks(model);

            export(model);

            for (HDLModel m : modelList) {
                out.println();
                out.println("-- " + m.getName());
                out.println();
                if (m.getClocks() != null)
                    throw new HDLException(Lang.get("err_clockOnlyAllowedInRoot"));

                export(m);
            }

            vhdlLibrary.finish(out);

            if (board != null)
                board.writeFiles(f, model);

        } catch (HDLException | PinException | NodeException e) {
            e.setOrigin(circuit.getOrigin());
            throw new IOException(Lang.get("err_exporting_vhdl"), e);
        } catch (ElementNotFoundException e) {
            throw new IOException(Lang.get("err_exporting_vhdl"), e);
        }
        return this;
    }

    private void export(HDLModel model) throws PinException, HDLException, ElementNotFoundException, NodeException, IOException {
        SplitterHandler splitterHandler = new SplitterHandler(model, out);

        out.println("LIBRARY ieee;");
        out.println("USE ieee.std_logic_1164.all;");
        out.println("USE ieee.numeric_std.all;\n");
        out.print("entity ").print(model.getName()).println(" is").inc();
        writePort(out, model.getPorts());
        out.dec().println("end " + model.getName() + ";");

        out.println("\narchitecture " + model.getName() + "_arch of " + model.getName() + " is");

        HashSet<String> componentsWritten = new HashSet<>();
        for (HDLNode node : model)
            if (node.is(Splitter.DESCRIPTION))
                splitterHandler.register(node);
            else if (!isConstant(node)) {
                String nodeName = getVhdlEntityName(node);
                if (!componentsWritten.contains(nodeName)) {
                    writeComponent(node);
                    componentsWritten.add(nodeName);
                }
            }

        out.println().inc();
        for (Signal sig : model.getSignals()) {
            if (!sig.isInPort()) {
                out.print("signal ");
                out.print(sig.getName());
                if (sig.isOutPort())
                    out.print(SIG_SUFFIX);
                out.print(": ");
                out.print(VHDLEntitySimple.getType(sig.getBits()));
                out.println(";");
            }
        }

        out.dec().println("begin").inc();

        // map output ports to signals
        for (Signal sig : model.getSignals()) {
            if (sig.isOutPort())
                out.print(sig.getName()).print(" <= ").print(sig.getName()).print(SIG_SUFFIX).println(";");
        }

        for (Signal s : model.getSignals()) {
            if (s.isConstant()) {
                s.setIsWritten();
                out.print(s.getName());
                out.print(" <= ");
                if (s.getBits() > 1)
                    out.print("std_logic_vector(to_unsigned(").print(s.getConstant()).print(",").print(s.getBits()).println("));");
                else
                    out.print("'").print(s.getConstant()).println("';");
            }
        }

        splitterHandler.write();

        int g = 0;
        for (HDLNode node : model)
            if (!node.is(Splitter.DESCRIPTION) && !isConstant(node)) {
                out.print("gate").print(g++).print(" : ").println(getVhdlEntityName(node)).inc();
                vhdlLibrary.writeGenericMap(out, node);
                writePortMap(node);
                out.dec();
            }

        // direct connection from input to output
        for (Port o : model.getPorts().getOutputs()) {
            if (!o.getSignal().isWritten()) {
                ArrayList<Port> ports = o.getSignal().getPorts();
                Port inPort = null;
                for (Port p : ports) {
                    if (p.getDirection() == Port.Direction.in) {
                        if (inPort != null)
                            throw new HDLException("wrong interconnect");
                        inPort = p;
                    }
                }
                if (inPort == null)
                    throw new HDLException("wrong interconnect");

                out.print(o.getName()).print(" <= ").print(inPort.getName()).println(";");
            }
        }

        out.dec().print("end ").print(model.getName()).println("_arch;");
    }

    private boolean isConstant(HDLNode node) {
        return node.is(Ground.DESCRIPTION)
                || node.is(VDD.DESCRIPTION)
                || node.is(Const.DESCRIPTION);
    }

    private void writePortMap(HDLNode node) throws HDLException, IOException {
        out.println("port map (").inc();
        Separator comma = new Separator(",\n");
        for (Port p : node.getPorts()) {
            if (p.getSignal() != null) {
                comma.check(out);
                out.print(p.getName() + " => " + p.getSignal().getName());
                if (p.getSignal().isOutPort())
                    out.print(SIG_SUFFIX);
                if (p.getDirection() == Port.Direction.out)
                    p.getSignal().setIsWritten();
            }
        }
        out.println(" );").dec();
    }

    private String getVhdlEntityName(HDLNode node) throws HDLException {
        if (node.isCustom())
            return node.getVisualElement().getElementName().replace('.', '_');
        else
            return vhdlLibrary.getName(node);
    }

    private void writeComponent(HDLNode node) throws ElementNotFoundException, NodeException, PinException, HDLException, IOException {
        out.println().inc();
        out.print("component ").println(getVhdlEntityName(node)).inc();
        vhdlLibrary.writeDeclaration(out, node);
        out.dec().println("end component;").dec();
    }

    private void writePort(CodePrinter out, Ports ports) throws HDLException, IOException {
        out.println("port (");
        Separator semic = new Separator(";\n");
        for (Port p : ports) {
            semic.check(out);
            out.print("  ");
            out.print(p.getName());
            out.print(": ");
            out.print(VHDLEntitySimple.getDirection(p));
            out.print(" ");
            out.print(VHDLEntitySimple.getType(p.getBits()));
        }
        out.println(" );");
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
     * Implements the clock fixing.
     * Introduces aditional nodes to reduce the clock speed
     *
     * @param model the model to modify
     */
    protected void fixClocks(HDLModel model) {
        if (model.getClocks() != null && board != null)
            model.integrateClocks(board.getClockPeriod());
    }
}
