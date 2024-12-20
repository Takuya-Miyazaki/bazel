// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization.testutils;

import static com.google.common.collect.Iterables.isEmpty;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.getClassInfo;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.getFieldInfo;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Fingerprinter.computeFingerprints;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.ClosedClassInfo;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.FieldInfo;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.ObjectInfo;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A utility for creating high fidelity string dumps of arbitrary objects.
 *
 * <p>Uses reflection to perform depth-first traversal of arbitrary objects and formats them as an
 * indented, multiline string.
 *
 * <p>This class exists mainly to help test and debug serialization. Consequently, it skips {@code
 * transient} fields. It also performs reference-based memoization to handle cyclic structures or
 * structures that would have an exponential path structure, for example, {@code NestedSets}.
 */
public final class Dumper {
  @Nullable // optionally used to lookup serialization constants
  private final ObjectCodecRegistry registry;

  /**
   * Fingerprints for references.
   *
   * <p>Even if this is present, not all references will have fingerprints. In particular, anything
   * where {@link #shouldInline} is true or inner elements of object cycles will not have
   * fingerprints.
   */
  @Nullable // optional behavior
  private final IdentityHashMap<Object, String> fingerprints;

  /**
   * Stores an identifier for every object traversed.
   *
   * <p>When an object is encountered again, it is represented with just its type and previous
   * identifier instead of being fully expanded.
   */
  private final IdentityHashMap<Object, Integer> referenceIds = new IdentityHashMap<>();

  /** The current indentation level. */
  private int indent = 0;

  private final StringBuilder out = new StringBuilder();

  private Dumper(
      @Nullable ObjectCodecRegistry registry,
      @Nullable IdentityHashMap<Object, String> fingerprints) {
    this.registry = registry;
    this.fingerprints = fingerprints;
  }

  /**
   * Formats an arbitrary object into a string.
   *
   * <p>The format is verbose and suitable for tests and debugging.
   *
   * @return a multiline String representation of {@code obj} without a trailing newline
   */
  public static String dumpStructure(@Nullable ObjectCodecRegistry registry, Object obj) {
    var deep = new Dumper(registry, /* fingerprints= */ null);
    deep.outputObject(obj);
    return deep.out.toString();
  }

  public static String dumpStructure(Object obj) {
    return dumpStructure(/* registry= */ null, obj);
  }

  /**
   * Formats an arbitrary object into a string.
   *
   * <p>Similar to {@link #dumpStructure} but applies fingerprint-based deduplication.
   */
  public static String dumpStructureWithEquivalenceReduction(
      @Nullable ObjectCodecRegistry registry, Object obj) {
    var deep = new Dumper(registry, computeFingerprints(obj));
    deep.outputObject(obj);
    return deep.out.toString();
  }

  public static String dumpStructureWithEquivalenceReduction(Object obj) {
    return dumpStructureWithEquivalenceReduction(/* registry= */ null, obj);
  }

  /** Formats an arbitrary object into {@link #out}. */
  private void outputObject(Object obj) {
    if (obj == null) {
      out.append("null");
      return;
    }

    var type = obj.getClass();

    if (registry != null) {
      Integer maybeConstantTag = registry.maybeGetTagForConstant(obj);
      if (maybeConstantTag != null) {
        out.append(getTypeName(type))
            .append("[SERIALIZATION_CONSTANT:")
            .append(maybeConstantTag)
            .append("]");
        return;
      }
    }

    if (WeakReference.class.isAssignableFrom(type)) {
      // A WeakReference is always be deserialized with empty referents. No information other than
      // the presence of the WeakReference can be expected to match upon deserialization.
      out.append(WeakReference.class.getCanonicalName());
      return;
    }

    if (shouldInline(type)) {
      out.append(obj);
      return;
    }

    int id;
    String fingerprint;
    if (fingerprints != null && ((fingerprint = fingerprints.get(obj)) != null)) {
      // There's a fingerprint for `obj`. Uses it to lookup a reference ID.
      Integer previousId = referenceIds.get(fingerprint);
      if (previousId != null) {
        // An object having this fingerprint has been observed previously. Outputs only a
        // backreference.
        outputIdentifier(type, previousId);
        return;
      }
      // In the case of a backreference to the inner member of an object cycle, the object could
      // have a fingerprint, but no backreference based on that fingerprint. It might instead be
      // possible to find that backreference directly through its reference here.
      previousId = referenceIds.get(obj);
      if (previousId != null) {
        outputIdentifier(type, previousId);
        return;
      }
      // No backreferences were found. Inserts a new reference entry.
      id = referenceIds.size();
      referenceIds.put(fingerprint, id);
    } else {
      // No fingerprint is available. Deduplicates by object reference.
      Integer previousId = referenceIds.get(obj);
      if (previousId != null) {
        // This instance has been observed previously. Outputs only a backreference.
        outputIdentifier(type, previousId);
        return;
      }
      id = referenceIds.size();
      referenceIds.put(obj, id);
    }

    // All non-inlined, non-backreference objects are represented like
    // "<type name>(<id>) [<contents>]".
    //
    // <contents> depends on the type, but is generally a sequence of recursively formatted objects.
    // For arrays and iterables, this is the sequence of elements, for maps, it is an alternating
    // sequence of keys and values and for any other type of object, it is a sequence of its fields,
    // like "<field name>=<object>".
    outputIdentifier(type, id);
    out.append(" [");
    indent++;

    boolean addedLine; // True if the <content> includes a newline.
    if (type.isArray()) {
      addedLine = outputArrayElements(obj);
    } else if (obj instanceof Map) {
      addedLine = outputMapEntries((Map<?, ?>) obj);
    } else if (obj instanceof Collection) {
      addedLine = outputCollectionElements((Collection<?>) obj);
    } else {
      addedLine = outputObjectFields(obj);
    }
    indent--;

    if (addedLine) {
      // The <content> sequence typically emits a newline per-sequence element, like
      // \n<indent><e1>\n<indent><e2>\n<indent><e3>, which would look like the following.
      //
      //   <type name>(id) [
      //     <e1>
      //     <e2>
      //     <e3>▊
      //
      // The code below emits a newline and indents to the parent's indentation level before
      // emitting the closing bracket.
      //
      //   <type name>(id) [
      //     <e1>
      //     <e2>
      //     <e3>
      //   ]▊
      //
      // When the <content> sequence is empty, or one of the special cased arrays that do not
      // emit newlines, no trailing newline is needed before the closing bracket, as in the
      // following examples.
      //
      //   <type name>(id) []▊
      // or
      //   byte[](1234) [DEADBEEF]▊
      //
      // Note that the output always leaves the cursor at the end of the last written line. The
      // caller should add a trailing newline if needed.
      addNewlineAndIndent();
    }
    out.append(']');
  }

  /** Emits an object identifier like {@code "<type name>(<id>)"}. */
  private void outputIdentifier(Class<?> type, int id) {
    out.append(getTypeName(type)).append('(').append(id).append(')');
  }

  static String getTypeName(Class<?> type) {
    String name = type.getCanonicalName();
    if (name == null) {
      // According to the documentation for `Class.getCanonicalName`, not all classes have one.
      // Falls back on the name in such cases. (It's unclear if this code is reachable because
      // synthetic types are inlined).
      name = type.getName();
    }
    return name;
  }

  private boolean outputArrayElements(Object arr) {
    var componentType = arr.getClass().getComponentType();
    if (componentType.equals(byte.class)) {
      // It's a byte array. Outputs as hex.
      for (byte b : (byte[]) arr) {
        out.append(String.format("%02X", b));
      }
      return false;
    }

    // In theory, there could be special casing WeakReferences here, to match the handling in
    // `outputObject`. However, since Java does not support generic arrays we don't expect to
    // encounter an array of WeakReferences.

    if (shouldInline(componentType)) {
      // It's a type that should be inlined. Outputs elements delimited by commas.
      boolean isFirst = true;
      for (int i = 0; i < Array.getLength(arr); i++) {
        if (isFirst) {
          isFirst = false;
        } else {
          out.append(", ");
        }
        out.append(Array.get(arr, i));
      }
      return false;
    }

    for (int i = 0; i < Array.getLength(arr); i++) {
      addNewlineAndIndent();
      outputObject(Array.get(arr, i));
    }
    return Array.getLength(arr) > 0;
  }

  private boolean outputMapEntries(Map<?, ?> map) {
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      addNewlineAndIndent();
      out.append("key=");
      outputObject(entry.getKey());

      addNewlineAndIndent();
      out.append("value=");
      outputObject(entry.getValue());
    }
    return !map.isEmpty();
  }

  private boolean outputCollectionElements(Collection<?> collection) {
    for (var next : collection) {
      addNewlineAndIndent();
      outputObject(next);
    }
    return !isEmpty(collection);
  }

  private boolean outputObjectFields(Object obj) {
    ImmutableList<FieldInfo> fieldInfo = getFieldInfo(obj.getClass());
    for (FieldInfo info : fieldInfo) {
      addNewlineAndIndent();
      outputField(obj, info);
    }
    return !fieldInfo.isEmpty();
  }

  private void outputField(Object parent, FieldInfo info) {
    switch (info) {
      case PrimitiveInfo primitiveInfo -> primitiveInfo.output(parent, out);
      case ObjectInfo objectInfo -> {
        out.append(objectInfo.name()).append('=');
        outputObject(objectInfo.getFieldValue(parent));
      }
    }
  }

  private void addNewlineAndIndent() {
    out.append('\n');
    for (int i = 0; i < indent; i++) {
      out.append("  "); // Indentation is 2 spaces.
    }
  }

  static boolean shouldInline(Class<?> type) {
    if (type.isArray()) {
      return false;
    }
    if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
      // These types have custom handling and do not depend on reflective class information.
      return false;
    }
    return type.isPrimitive()
        || DIRECT_INLINE_TYPES.contains(type)
        || type.isSynthetic()
        // Reflectively inaccessible classes will be represented directly using their string
        // representations as there's nothing else we can do with them.
        //
        // TODO: b/331765692 - this might cause a loss of fidelity. Consider including a hash of
        // the serialized representation in such cases.
        || getClassInfo(type) instanceof ClosedClassInfo;
  }

  private static final ImmutableSet<Class<?>> WRAPPER_TYPES =
      ImmutableSet.of(
          Byte.class,
          Short.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class,
          Boolean.class,
          Character.class);

  private static final ImmutableSet<Class<?>> DIRECT_INLINE_TYPES =
      ImmutableSet.<Class<?>>builder()
          .addAll(WRAPPER_TYPES)
          // Treats Strings as values for readability of the output. It might be good to make this
          // configurable later on.
          .add(String.class)
          // The string representation of a Class is sufficient to identify it.
          .add(Class.class)
          .build();
}
