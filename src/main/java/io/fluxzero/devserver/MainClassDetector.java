/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class MainClassDetector {
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;
    private static final int PUBLIC_STATIC = 0x0001 | 0x0008;
    private static final int STATIC = 0x0008;
    private static final String MAIN_DESCRIPTOR = "([Ljava/lang/String;)V";

    private MainClassDetector() {
    }

    static String detect(Path classesDirectory) throws IOException {
        if (!Files.isDirectory(classesDirectory)) {
            throw new IllegalStateException("No compiled application classes found in " + classesDirectory);
        }
        List<String> candidates = candidates(classesDirectory);
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No application main class detected. Set --main-class or "
                                            + "FLUXZERO_MAIN_CLASS.");
        }
        throw new IllegalStateException("Multiple application main classes detected: "
                                        + String.join(", ", candidates)
                                        + ". Select one with --main-class or FLUXZERO_MAIN_CLASS.");
    }

    static List<String> candidates(Path classesDirectory) throws IOException {
        return candidates(classesDirectory, PUBLIC_STATIC);
    }

    static List<String> testCandidates(Path classesDirectory) throws IOException {
        return candidates(classesDirectory, STATIC);
    }

    private static List<String> candidates(Path classesDirectory, int requiredAccess) throws IOException {
        if (!Files.isDirectory(classesDirectory)) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        try (var files = Files.walk(classesDirectory)) {
            for (Path classFile : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted().toList()) {
                try {
                    String candidate = readMainClass(classFile, requiredAccess);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                } catch (IOException ignored) {
                    // Maven success is authoritative; tolerate unrelated or tool-generated class-like files.
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static String readMainClass(Path classFile, int requiredAccess) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            if (input.readInt() != CLASS_FILE_MAGIC) {
                throw new IOException("Invalid class file: " + classFile);
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            ConstantPool constantPool = readConstantPool(input);
            input.readUnsignedShort();
            int thisClass = input.readUnsignedShort();
            input.readUnsignedShort();
            skipUnsignedShorts(input, input.readUnsignedShort());
            skipMembers(input, input.readUnsignedShort());

            boolean hasMain = false;
            int methodCount = input.readUnsignedShort();
            for (int index = 0; index < methodCount; index++) {
                int access = input.readUnsignedShort();
                String name = constantPool.utf8(input.readUnsignedShort());
                String descriptor = constantPool.utf8(input.readUnsignedShort());
                int attributeCount = input.readUnsignedShort();
                skipAttributes(input, attributeCount);
                if ((access & requiredAccess) == requiredAccess
                    && "main".equals(name) && MAIN_DESCRIPTOR.equals(descriptor)) {
                    hasMain = true;
                }
            }
            return hasMain ? constantPool.className(thisClass).replace('/', '.') : null;
        }
    }

    private static ConstantPool readConstantPool(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndices = new int[count];
        for (int index = 1; index < count; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[index] = input.readUTF();
                case 3, 4 -> input.skipNBytes(4);
                case 5, 6 -> {
                    input.skipNBytes(8);
                    index++;
                }
                case 7 -> classNameIndices[index] = input.readUnsignedShort();
                case 8, 16, 19, 20 -> input.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unsupported class-file constant pool tag " + tag);
            }
        }
        return new ConstantPool(utf8, classNameIndices);
    }

    private static void skipMembers(DataInputStream input, int memberCount) throws IOException {
        for (int index = 0; index < memberCount; index++) {
            input.skipNBytes(6);
            skipAttributes(input, input.readUnsignedShort());
        }
    }

    private static void skipAttributes(DataInputStream input, int attributeCount) throws IOException {
        for (int index = 0; index < attributeCount; index++) {
            input.skipNBytes(2);
            input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
        }
    }

    private static void skipUnsignedShorts(DataInputStream input, int count) throws IOException {
        input.skipNBytes((long) count * Short.BYTES);
    }

    private record ConstantPool(String[] utf8, int[] classNameIndices) {
        String utf8(int index) throws IOException {
            if (index <= 0 || index >= utf8.length || utf8[index] == null) {
                throw new IOException("Invalid class-file UTF-8 constant index " + index);
            }
            return utf8[index];
        }

        String className(int index) throws IOException {
            if (index <= 0 || index >= classNameIndices.length) {
                throw new IOException("Invalid class-file class constant index " + index);
            }
            return utf8(classNameIndices[index]);
        }
    }
}
