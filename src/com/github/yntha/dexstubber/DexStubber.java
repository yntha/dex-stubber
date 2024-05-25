package com.github.yntha.dexstubber;

import com.google.common.io.Files;
import com.googlecode.d2j.dex.Dex2jar;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.formatter.DexFormatter;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21s;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DexStubber {
  private static MethodImplementation getMethodImplementation(Method method) throws Exception {
    final List<Instruction> instructions = new ArrayList<>();
    int regCount = 1; // `this` is implicitly the first register

    if (AccessFlags.STATIC.isSet(method.getAccessFlags())) {
      regCount = 0;
    }

    switch (method.getReturnType()) {
      case "V":
        instructions.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));

        break;
      case "Z":
      case "B":
      case "S":
      case "C":
      case "F":
      case "I":
        instructions.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0));

        instructions.add(new ImmutableInstruction11x(Opcode.RETURN, 0));

        regCount = 2;

        break;
      case "J":
      case "D":
        instructions.add(new ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 0));

        instructions.add(new ImmutableInstruction11x(Opcode.RETURN_WIDE, 0));

        regCount = 3;

        break;
      default:
        if (method.getReturnType().startsWith("[") || method.getReturnType().endsWith(";")) {
          instructions.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0));

          instructions.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));

          regCount = 2;

          break;
        }

        throw new Exception("Unknown return type");
    }

    return new ImmutableMethodImplementation(
        regCount + method.getParameters().size(), instructions, null, null);
  }

  public static void main(String[] args) throws Exception {
    JFileChooser fileChooser = new JFileChooser();

    if (fileChooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
      return;
    }

    File dexFile = fileChooser.getSelectedFile();
    DexFile dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault());
    Set<ClassDef> classes = new HashSet<>();

    System.out.println("Stubbing " + dexFile.getAbsolutePath());

    for (final ClassDef classDef : dex.getClasses()) {
      final Set<Method> methods = new HashSet<>();

      for (final Method method : classDef.getMethods()) {
        final int methodAccessFlags = method.getAccessFlags();

        System.out.println("Stubbing " + DexFormatter.INSTANCE.getMethodDescriptor(method));

        if (AccessFlags.ABSTRACT.isSet(methodAccessFlags)
            || AccessFlags.NATIVE.isSet(methodAccessFlags)) {
          continue;
        }

        methods.add(
            new ImmutableMethod(
                method.getDefiningClass(),
                method.getName(),
                method.getParameters(),
                method.getReturnType(),
                methodAccessFlags,
                null,
                null,
                DexStubber.getMethodImplementation(method)));
      }

      classes.add(
          new ImmutableClassDef(
              classDef.getType(),
              classDef.getAccessFlags(),
              classDef.getSuperclass(),
              classDef.getInterfaces(),
              classDef.getSourceFile(),
              classDef.getAnnotations(),
              classDef.getFields(),
              methods));

      System.out.println("Stubbed all methods in " + classDef.getType());
    }

    System.out.println("Converting dex to jar...");

    DexPool dexPool = new DexPool(Opcodes.getDefault());
    MemoryDataStore dataStore = new MemoryDataStore();

    for (final ClassDef classDef : classes) {
      dexPool.internClass(classDef);
    }

    dexPool.writeTo(dataStore);

    final byte[] dexDataBuf = dataStore.getBuffer();
    final File outputDir = dexFile.getParentFile();
    final String jarFile = Files.getNameWithoutExtension(dexFile.getName()) + ".jar";
    final File outputJar = new File(outputDir, jarFile);

    // now write the output to a jar file
    Dex2jar.from(dexDataBuf)
        .reUseReg(false)
        .topoLogicalSort()
        .skipDebug(false)
        .optimizeSynchronized(false)
        .printIR(false)
        .noCode(false)
        .skipExceptions(false)
        .to(Path.of(outputJar.getAbsolutePath()));

    System.out.println("Done.");
  }
}
