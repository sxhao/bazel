// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.syntax;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.syntax.EvalException.EvalExceptionWithJavaCause;
import com.google.devtools.build.lib.syntax.SkylarkType.SkylarkFunctionType;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

/**
 * A function class for Skylark built in functions. Supports mandatory and optional arguments.
 * All usable arguments have to be specified. In case of ambiguous arguments (a parameter is
 * specified as positional and keyword arguments in the function call) an exception is thrown.
 */
public abstract class SkylarkFunction extends AbstractFunction {

  private ImmutableList<String> parameters;
  private int mandatoryParamNum;
  private boolean configured = false;
  private Class<?> objectType;

  /**
   * Creates a SkylarkFunction with the given name. 
   */
  public SkylarkFunction(String name) {
    super(name);
  }

  /**
   * Configures the parameter of this Skylark function using the annotation.
   */
  @VisibleForTesting
  public void configure(SkylarkBuiltin annotation) {
    Preconditions.checkState(!configured);
    Preconditions.checkArgument(getName().equals(annotation.name()));
    mandatoryParamNum = 0;
    ImmutableList.Builder<String> paramListBuilder = ImmutableList.builder();
    for (SkylarkBuiltin.Param param : annotation.mandatoryParams()) {
      paramListBuilder.add(param.name());
      mandatoryParamNum++;
    }
    for (SkylarkBuiltin.Param param : annotation.optionalParams()) {
      paramListBuilder.add(param.name());
    }
    parameters = paramListBuilder.build();
    this.objectType = annotation.objectType().equals(Object.class) ? null : annotation.objectType();
    configured = true;
  }

  /**
   * Returns true if the SkylarkFunction is configured.
   */
  public boolean isConfigured() {
    return configured;
  }

  @Override
  public Class<?> getObjectType() {
    return objectType;
  }

  @Override
  public Object call(List<Object> args,
                     Map<String, Object> kwargs,
                     FuncallExpression ast,
                     Environment env)
      throws EvalException, InterruptedException {

    Preconditions.checkState(configured, "Function " + getName() + " was not configured");
    try {
      ImmutableMap.Builder<String, Object> arguments = new ImmutableMap.Builder<>();
      if (objectType != null && !FuncallExpression.isNamespace(objectType)) {
        arguments.put("self", args.remove(0));
      }

      int maxParamNum = parameters.size();
      int paramNum = args.size() + kwargs.size();

      if (paramNum < mandatoryParamNum || paramNum > maxParamNum) {
        throw new EvalException(ast.getLocation(),
            String.format("incorrect number of arguments %s (expected %s - %s)",
                paramNum, mandatoryParamNum, maxParamNum));
      }

      for (int i = 0; i < mandatoryParamNum; i++) {
        Preconditions.checkState(i < args.size() || kwargs.containsKey(parameters.get(i)),
            String.format("missing mandatory parameter: %s", parameters.get(i)));
      }

      for (int i = 0; i < args.size(); i++) {
        arguments.put(parameters.get(i), args.get(i));
      }

      for (Entry<String, Object> kwarg : kwargs.entrySet()) {
        int idx = parameters.indexOf(kwarg.getKey()); 
        if (idx < 0) {
          throw new EvalException(ast.getLocation(),
              String.format("unknown keyword argument: %s", kwarg.getKey()));
        }
        if (idx < args.size()) {
          throw new EvalException(ast.getLocation(),
              String.format("ambiguous argument: %s", kwarg.getKey()));
        }
        arguments.put(kwarg.getKey(), kwarg.getValue());
      }

      return call(arguments.build(), ast, env);
    } catch (ConversionException | IllegalArgumentException | IllegalStateException
        | ClassCastException | ClassNotFoundException | ExecutionException e) {
      if (e.getMessage() != null) {
        throw new EvalException(ast.getLocation(), e.getMessage());
      } else {
        // TODO(bazel-team): ideally this shouldn't happen, however we need this for debugging
        throw new EvalExceptionWithJavaCause(ast.getLocation(), e);
      }
    }
  }

  /**
   * The actual function call. All positional and keyword arguments are put in the
   * arguments map.
   */
  protected abstract Object call(
      Map<String, Object> arguments, FuncallExpression ast, Environment env) throws EvalException,
      ConversionException,
      IllegalArgumentException,
      IllegalStateException,
      ClassCastException,
      ClassNotFoundException,
      ExecutionException;

  /**
   * An intermediate class to provide a simpler interface for Skylark functions.
   */
  public abstract static class SimpleSkylarkFunction extends SkylarkFunction {

    public SimpleSkylarkFunction(String name) {
      super(name);
    }

    @Override
    protected final Object call(
        Map<String, Object> arguments, FuncallExpression ast, Environment env) throws EvalException,
        ConversionException,
        IllegalArgumentException,
        IllegalStateException,
        ClassCastException,
        ExecutionException {
      return call(arguments, ast.getLocation());
    }

    /**
     * The actual function call. All positional and keyword arguments are put in the
     * arguments map.
     */
    protected abstract Object call(Map<String, Object> arguments, Location loc)
        throws EvalException,
        ConversionException,
        IllegalArgumentException,
        IllegalStateException,
        ClassCastException,
        ExecutionException;
  }

  /**
   * Collects the SkylarkFunctions from the fields of the class of the object parameter
   * and adds them into the builder.
   */
  public static void collectSkylarkFunctionsFromFields(
      Class<?> type, Object object, ImmutableList.Builder<Function> builder) {
    for (Field field : type.getDeclaredFields()) {
      if (SkylarkFunction.class.isAssignableFrom(field.getType())
          && field.isAnnotationPresent(SkylarkBuiltin.class)) {
        try {
          field.setAccessible(true);
          SkylarkFunction function = (SkylarkFunction) field.get(object);
          SkylarkBuiltin annotation = field.getAnnotation(SkylarkBuiltin.class);
          // TODO(bazel-team): we need this because of the static functions. We need
          // static functions because of the testing. The tests use a mixture of Skylark
          // and non Skylark rules. this causes the problem. As soon as we have only
          // Skylark rules in the SkylarkTests we can clean this up.
          if (!function.isConfigured()) {
            function.configure(annotation);
          }
          builder.add(function);
        } catch (IllegalArgumentException | IllegalAccessException e) {
          // This should never happen.
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * Collects the SkylarkFunctions from the fields of the class of the object parameter
   * and adds their class and their corresponding return value to the builder.
   */
  public static void collectSkylarkFunctionReturnTypesFromFields(Class<?> classObject,
      Map<SkylarkType, Map<String, SkylarkType>> builtIn) {
    for (Field field : classObject.getDeclaredFields()) {
      if (SkylarkFunction.class.isAssignableFrom(field.getType())
          && field.isAnnotationPresent(SkylarkBuiltin.class)) {
        try {
          field.setAccessible(true);
          SkylarkBuiltin annotation = field.getAnnotation(SkylarkBuiltin.class);
          // TODO(bazel-team): infer the correct types.
          SkylarkType objectType = annotation.objectType().equals(Object.class)
              ? SkylarkType.GLOBAL
              : SkylarkType.of(annotation.objectType());
          if (!builtIn.containsKey(objectType)) {
            builtIn.put(objectType, new HashMap<String, SkylarkType>());
          }
          // TODO(bazel-team): add parameters to SkylarkFunctionType
          SkylarkType returnType = getReturnType(annotation);
          builtIn.get(objectType).put(annotation.name(),
              SkylarkFunctionType.of(annotation.name(), returnType));
        } catch (IllegalArgumentException e) {
          // This should never happen.
          throw new RuntimeException(e);
        }
      }
    }
  }

  private static SkylarkType getReturnType(SkylarkBuiltin annotation) {
    return annotation.returnType().equals(Object.class)
        ? SkylarkType.UNKNOWN : SkylarkType.of(annotation.returnType());
  }

  public static <TYPE> Iterable<TYPE> castList(
      Object obj, final Class<TYPE> type, final String what) throws ConversionException {
    if (obj == null) {
      return ImmutableList.of();
    }
    return Iterables.transform(Type.LIST.convert(obj, what),
        new com.google.common.base.Function<Object, TYPE>() {
          @Override
          public TYPE apply(Object input) {
            try {
              return type.cast(input);
            } catch (ClassCastException e) {
              throw new IllegalArgumentException(String.format(
                  "expected %s type for '%s' but got %s instead",
                  type.getSimpleName(), what, EvalUtils.getDatatypeName(input)));
            }
          }
    });
  }

  public static <KEY_TYPE, VALUE_TYPE> ImmutableMap<KEY_TYPE, VALUE_TYPE> toMap(
      Iterable<Map.Entry<KEY_TYPE, VALUE_TYPE>> obj) {
    ImmutableMap.Builder<KEY_TYPE, VALUE_TYPE> builder = ImmutableMap.builder();
    for (Map.Entry<KEY_TYPE, VALUE_TYPE> entry : obj) {
      builder.put(entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  public static <KEY_TYPE, VALUE_TYPE> Iterable<Map.Entry<KEY_TYPE, VALUE_TYPE>> castMap(Object obj,
      final Class<KEY_TYPE> keyType, final Class<VALUE_TYPE> valueType, final String what) {
    if (obj == null) {
      return ImmutableList.of();
    }
    if (!(obj instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(String.format(
          "expected a dictionary for %s but got %s instead",
          what, EvalUtils.getDatatypeName(obj)));
    }
    return Iterables.transform(((Map<?, ?>) obj).entrySet(),
        new com.google.common.base.Function<Map.Entry<?, ?>, Map.Entry<KEY_TYPE, VALUE_TYPE>>() {
          // This is safe. We check the type of the key-value pairs for every entry in the Map.
          // In Map.Entry the key always has the type of the first generic parameter, the
          // value has the second.
          @SuppressWarnings("unchecked")
            @Override
            public Map.Entry<KEY_TYPE, VALUE_TYPE> apply(Map.Entry<?, ?> input) {
            if (keyType.isAssignableFrom(input.getKey().getClass())
                && valueType.isAssignableFrom(input.getValue().getClass())) {
              return (Map.Entry<KEY_TYPE, VALUE_TYPE>) input;
            }
            throw new IllegalArgumentException(String.format(
                "expected <%s, %s> type for '%s' but got <%s, %s> instead",
                keyType.getSimpleName(), valueType.getSimpleName(), what,
                EvalUtils.getDatatypeName(input.getKey()),
                EvalUtils.getDatatypeName(input.getValue())));
          }
        });
  }

  public static <TYPE> TYPE cast(Object elem, Class<TYPE> type, String what, Location loc)
      throws EvalException {
    try {
      return type.cast(elem);
    } catch (ClassCastException e) {
      throw new EvalException(loc, String.format("expected %s for '%s' but got %s instead",
          type.getSimpleName(), what, EvalUtils.getDatatypeName(elem)));
    }
  }
}
