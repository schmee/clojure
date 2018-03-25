package clojure.lang;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class ReflectionCallSite extends MutableCallSite {
  public static final MethodType TYPE = MethodType.methodType(Object.class, Object.class, Object[].class);
  public static final int N_PARAMS = TYPE.parameterCount();
  public static final Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodHandle IS_INSTANCE;
  private static final MethodHandle INVOKE_INSTANCE_METHOD;
  private static final MethodHandle INVOKE_STATIC_METHOD;
  private static final MethodHandle CACHE_FIRST_METHOD;
  private static final MethodHandle STATIC_CACHE;
  private static final MethodHandle BOX_ARGS;
  private static final MethodHandle CLASS_EQUALS;
  private static final MethodHandle EQUALS;
  static {
    try {
        IS_INSTANCE = LOOKUP.findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
        INVOKE_INSTANCE_METHOD = LOOKUP.findStatic(Reflector.class, "invokeInstanceMethod", MethodType.methodType(Object.class, Object.class, String.class, Object[].class));
        INVOKE_STATIC_METHOD = LOOKUP.findStatic(Reflector.class, "invokeStaticMethod", MethodType.methodType(Object.class, Class.class, String.class, Object[].class));
        CACHE_FIRST_METHOD = LOOKUP.findVirtual(ReflectionCallSite.class, "cacheFirstMethod", MethodType.methodType(Object.class, Object.class, Object[].class));
        CLASS_EQUALS = LOOKUP.findStatic(ReflectionCallSite.class, "classEquals", MethodType.methodType(boolean.class, Class.class, Class[].class, Class.class, Object[].class));
        EQUALS = LOOKUP.findVirtual(Object.class, "equals", MethodType.methodType(boolean.class, Object.class));
        STATIC_CACHE = LOOKUP.findVirtual(ReflectionCallSite.class, "staticCache", MethodType.methodType(Object.class, Class.class, Object[].class));
        BOX_ARGS = LOOKUP.findStatic(Reflector.class, "boxArgs", MethodType.methodType(Object[].class, Class[].class, Object[].class));
    } catch (Exception e) {
      throw new RuntimeException("Failed to init bootstrap methods", e);
    }
  }

  public final String methodName;

  public ReflectionCallSite(String methodName) {
    super(TYPE);
    this.methodName = methodName;
    this.setTarget(CACHE_FIRST_METHOD.bindTo(this));
  }

  private ReflectionCallSite(String methodName, MethodType type, MethodHandle target) {
    super(type);
    this.methodName = methodName;
    this.setTarget(target.bindTo(this));
  }

  public final synchronized Object cacheFirstMethod(Object target, Object[] args) {
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
    MethodHandle genericHandle = unreflected.asType(genericType).asSpreader(1, Object[].class, nParams);
    MethodHandle boxer = BOX_ARGS.bindTo(m.getParameterTypes());

    MethodHandle guard = IS_INSTANCE.bindTo(c);
    MethodHandle cached = MethodHandles.filterArguments(genericHandle, 1, boxer);
    MethodHandle invoker = MethodHandles.insertArguments(INVOKE_INSTANCE_METHOD, 1, methodName);
    MethodHandle guarded = MethodHandles.guardWithTest(guard, cached, invoker);
    this.setTarget(guarded);
    MutableCallSite.syncAll(new MutableCallSite[]{this});
    return Reflector.invokeMethod(m, target, args);
  }

  public final synchronized Object staticCache(Class c, Object[] args) {
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
    MethodHandle boxer = BOX_ARGS.bindTo(paramTypes);

    boolean hasOverloads = methods.size() > 1;
    MethodHandle guard;
    if (hasOverloads) {
      guard = MethodHandles.insertArguments(CLASS_EQUALS, 0, c, paramTypes);
    } else {
      MethodHandle classEq = EQUALS
        .asType(MethodType.methodType(boolean.class, Class.class, Class.class))
        .bindTo(c);
      guard = MethodHandles.dropArguments(classEq, 2, Object[].class);
    }
    MethodHandle cached = MethodHandles.filterArguments(genericHandle, 1, boxer);
    MethodHandle invoker = MethodHandles.insertArguments(INVOKE_STATIC_METHOD, 1, methodName);
    MethodHandle guarded = MethodHandles.guardWithTest(guard, cached, invoker);
    this.setTarget(guarded);
    MutableCallSite.syncAll(new MutableCallSite[]{this});
    return Reflector.invokeStaticMethod(c, methodName, args);
  }

  @SuppressWarnings("unused")
  private static final boolean classEquals(Class c1, Class[] paramTypes, Class c2, Object[] whatever) {
    return c1.equals(c2) && Reflector.isCongruent(paramTypes, whatever);
  }

  public static final CallSite createStaticCache(String methodName) {
		MethodType type = MethodType.methodType(Object.class, Class.class, Object[].class);
    return new ReflectionCallSite(methodName, type, STATIC_CACHE);
  }
}
