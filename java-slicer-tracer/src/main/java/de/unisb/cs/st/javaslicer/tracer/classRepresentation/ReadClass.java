package de.unisb.cs.st.javaslicer.tracer.classRepresentation;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;

import org.objectweb.asm.Type;

public class ReadClass {

    private final String internalClassName;
    private final String className;
    private final byte[] classByteCode;
    private final ArrayList<ReadMethod> methods = new ArrayList<ReadMethod>();

    public ReadClass(final String internalClassName, final byte[] classBytecode) {
        this.internalClassName = internalClassName;
        this.className = Type.getObjectType(internalClassName).getClassName();
        this.classByteCode = classBytecode;
    }

    public void addMethod(final ReadMethod method) {
        this.methods.add(method);
    }

    public void ready() {
        this.methods.trimToSize();
    }

    public String getClassName() {
        return this.className;
    }

    public void writeOut(final ObjectOutputStream out) throws IOException {
        out.writeObject(this.internalClassName);
        out.writeObject(this.className);
        out.writeInt(this.classByteCode.length);
        out.write(this.classByteCode);
        out.writeInt(this.methods.size());
        for (final ReadMethod rm: this.methods) {
            rm.writeOut(out);
        }
    }

    public static ReadClass readFrom(final ObjectInputStream in) throws IOException {
        try {
            final String intName = (String) in.readObject();
            final String className = (String) in.readObject();
            final byte[] bytecode = new byte[in.readInt()];
            in.read(bytecode, 0, bytecode.length);
            final ReadClass rc = new ReadClass(intName, bytecode);
            assert rc.className != null && rc.className.equals(className);
            int numMethods = in.readInt();
            rc.methods.ensureCapacity(numMethods);
            while (numMethods-- > 0)
                rc.methods.add(ReadMethod.readFrom(in, rc));
            return rc;
        } catch (final ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}