package clojure.lang;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.util.List;

public final class ReflectionCallSite extends MutableCallSite {
  public static final MethodType TYPE = MethodType.methodType(Object.class, Object.class, Object[].class);
  public static final int N_PARAMS = TYPE.parameterCount();
  public static final Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodHandle IS_INSTANCE;
  private static final MethodHandle INVOKE_INSTANCE_METHOD;
  private static final MethodHandle CACHE_FIRST_METHOD;
  private static final MethodHandle BOX_ARGS;
  static {
    try {
        IS_INSTANCE = LOOKUP.findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
        INVOKE_INSTANCE_METHOD = LOOKUP.findStatic(Reflector.class, "invokeInstanceMethod", MethodType.methodType(Object.class, Object.class, String.class, Object[].class));
        CACHE_FIRST_METHOD = LOOKUP.findVirtual(ReflectionCallSite.class, "cacheFirstMethod", MethodType.methodType(Object.class, Object.class, Object[].class));
        BOX_ARGS = LOOKUP.findStatic(Reflector.class, "boxArgs", MethodType.methodType(Object[].class, Class[].class, Object[].class));
    } catch (Exception e) {
      throw new RuntimeException("Failed to init bootstrap methods");
    }
  }

  public final String methodName;

  public ReflectionCallSite(String methodName) {
    super(TYPE);
    this.methodName = methodName;
    this.setTarget(CACHE_FIRST_METHOD.bindTo(this));
  }

  public final synchronized Object cacheFirstMethod(Object target, Object[] args) {
    List methods = Reflector.getMethods(target.getClass(), args.length, methodName, false);
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

    MethodHandle guard = IS_INSTANCE.bindTo(target.getClass());
    MethodHandle cached = MethodHandles.filterArguments(genericHandle, 1, boxer);
    MethodHandle invoker = MethodHandles.insertArguments(INVOKE_INSTANCE_METHOD, 1, methodName);
    MethodHandle guarded = MethodHandles.guardWithTest(guard, cached, invoker);
    this.setTarget(guarded);
    MutableCallSite.syncAll(new MutableCallSite[]{this});
    return Reflector.invokeMethod(m, target, args);
  }
}
