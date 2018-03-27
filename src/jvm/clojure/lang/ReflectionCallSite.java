package clojure.lang;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.util.List;

public final class ReflectionCallSite extends MutableCallSite {
  public static final MethodType INSTANCE_TYPE = MethodType.methodType(Object.class, Object.class, Object[].class);
	public static final MethodType STATIC_TYPE = MethodType.methodType(Object.class, Class.class, Object[].class);

  private static final Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodHandle IS_INSTANCE;
  private static final MethodHandle GET_CLASS;
  private static final MethodHandle INVOKE_INSTANCE_METHOD;
  private static final MethodHandle INVOKE_STATIC_METHOD;
  private static final MethodHandle INSTANCE_METHOD_CACHE;
  private static final MethodHandle STATIC_METHOD_CACHE;
  private static final MethodHandle BOX_ARGS;
  private static final MethodHandle CLASS_AND_ARGUMENTS_MATCH;
  private static final MethodHandle CLASS_MATCHES;
  static {
    try {
        IS_INSTANCE = LOOKUP.findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
        GET_CLASS = LOOKUP.findVirtual(Object.class, "getClass", MethodType.methodType(Class.class));
        INVOKE_INSTANCE_METHOD = LOOKUP.findStatic(Reflector.class, "invokeInstanceMethod", MethodType.methodType(Object.class, Object.class, String.class, Object[].class));
        INVOKE_STATIC_METHOD = LOOKUP.findStatic(Reflector.class, "invokeStaticMethod", MethodType.methodType(Object.class, Class.class, String.class, Object[].class));
        CLASS_AND_ARGUMENTS_MATCH = LOOKUP.findStatic(ReflectionCallSite.class, "classAndArgumentsMatch", MethodType.methodType(boolean.class, Class.class, Class[].class, Class.class, Object[].class));
        CLASS_MATCHES = LOOKUP
          .findVirtual(Object.class, "equals", MethodType.methodType(boolean.class, Object.class))
          .asType(MethodType.methodType(boolean.class, Class.class, Class.class));
        INSTANCE_METHOD_CACHE = LOOKUP.findVirtual(ReflectionCallSite.class, "instanceMethodCache", INSTANCE_TYPE);
        STATIC_METHOD_CACHE = LOOKUP.findVirtual(ReflectionCallSite.class, "staticMethodCache", STATIC_TYPE);
        BOX_ARGS = LOOKUP.findStatic(Reflector.class, "boxArgs", MethodType.methodType(Object[].class, Class[].class, Object[].class));
    } catch (Exception e) {
      throw new RuntimeException("Failed to init bootstrap methods", e);
    }
  }

  public final String methodName;

  private ReflectionCallSite(String methodName, MethodType type, MethodHandle target) {
    super(type);
    this.methodName = methodName;
    this.setTarget(target.bindTo(this));
  }

  private final synchronized Object instanceMethodCache(Object target, Object[] args) {
    Class c = target.getClass();
    List methods = Reflector.getMethods(c, args.length, methodName, false);
    Method m = Reflector.findMatchingMethod(methodName, methods, target, args);
    MethodHandle unreflected = null;
    try {
      unreflected = LOOKUP.unreflect(m);
    } catch (Throwable t) {
      throw new RuntimeException("Failed to unreflect method", t);
    }
    MethodType genericType = unreflected
      .type()
      .changeReturnType(Object.class)
      .changeParameterType(0, Object.class);
    int nParams = m.getParameterCount();
    Class[] paramTypes = m.getParameterTypes();
    MethodHandle genericHandle = unreflected.asType(genericType).asSpreader(1, Object[].class, nParams);
    MethodHandle boxer = BOX_ARGS.bindTo(paramTypes);

    boolean hasOverloads = methods.size() > 1;
    MethodHandle guard;
    if (hasOverloads) {
			MethodHandle classArgsCheck = MethodHandles.insertArguments(CLASS_AND_ARGUMENTS_MATCH, 0, c, paramTypes);
      guard = MethodHandles.filterArguments(classArgsCheck, 0, GET_CLASS);
    } else {
      guard = IS_INSTANCE.bindTo(c);
    }
    MethodHandle cached = MethodHandles.filterArguments(genericHandle, 1, boxer);
    MethodHandle invoker = MethodHandles.insertArguments(INVOKE_INSTANCE_METHOD, 1, methodName);
    MethodHandle guarded = MethodHandles.guardWithTest(guard, cached, invoker);
    this.setTarget(guarded);
    MutableCallSite.syncAll(new MutableCallSite[]{this});
    return Reflector.invokeMethod(m, target, args);
  }

  private final synchronized Object staticMethodCache(Class c, Object[] args) {
    List methods = Reflector.getMethods(c, args.length, methodName, true);
    Method m = Reflector.findMatchingMethod(methodName, methods, null, args);
    MethodHandle unreflected = null;
    try {
      unreflected = LOOKUP.unreflect(m);
    } catch (Throwable t) {
      throw new RuntimeException("Failed to unreflect method", t);
    }
    MethodType genericType = unreflected
      .type()
      .changeReturnType(Object.class);
    int nParams = m.getParameterCount();
    Class[] paramTypes = m.getParameterTypes();
    MethodHandle genericHandle = MethodHandles.dropArguments(
      unreflected.asType(genericType).asSpreader(0, Object[].class, nParams),
      0,
      Class.class
    );

    boolean hasOverloads = methods.size() > 1;
    MethodHandle guard;
    if (hasOverloads) {
      guard = MethodHandles.insertArguments(CLASS_AND_ARGUMENTS_MATCH, 0, c, paramTypes);
    } else {
      MethodHandle classMatches = CLASS_MATCHES.bindTo(c);
      guard = MethodHandles.dropArguments(classMatches, 2, Object[].class);
    }
    MethodHandle boxer = BOX_ARGS.bindTo(paramTypes);
    MethodHandle cached = MethodHandles.filterArguments(genericHandle, 1, boxer);
    MethodHandle invoker = MethodHandles.insertArguments(INVOKE_STATIC_METHOD, 1, methodName);
    MethodHandle guarded = MethodHandles.guardWithTest(guard, cached, invoker);
    this.setTarget(guarded);
    MutableCallSite.syncAll(new MutableCallSite[]{this});
    return Reflector.invokeStaticMethod(c, methodName, args);
  }

  private static final boolean classAndArgumentsMatch(Class c1, Class[] paramTypes, Class c2, Object[] args) {
    return c1.equals(c2) && Reflector.isCongruent(paramTypes, args);
  }

  public static final CallSite createInstanceCache(String methodName) {
    return new ReflectionCallSite(methodName, INSTANCE_TYPE, INSTANCE_METHOD_CACHE);
  }

  public static final CallSite createStaticCache(String methodName) {
    return new ReflectionCallSite(methodName, STATIC_TYPE, STATIC_METHOD_CACHE);
  }

}
