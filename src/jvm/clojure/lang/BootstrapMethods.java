package clojure.lang;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.concurrent.atomic.AtomicBoolean;

public class BootstrapMethods {
  private static final MethodHandle ATOMIC_GET;
  static {
    Lookup lookup = MethodHandles.lookup();
    try {
        ATOMIC_GET = lookup.findVirtual(AtomicBoolean.class, "get", MethodType.methodType(boolean.class));
    } catch (Exception e) {
      throw new RuntimeException("Failed to init bootstrap methods");
    }
  }

  public static CallSite varExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var v = RT.var(varNs, varName);
    MethodHandle mh = Var.ROOT.bindTo(v);
    return new ConstantCallSite(mh);
  }

  public static CallSite dynamicVarExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var v = RT.var(varNs, varName);
    MethodHandle root = Var.ROOT.bindTo(v);
    MethodHandle cache = Var.DEREF.bindTo(v);
    MethodHandle test = MethodHandles.foldArguments(ATOMIC_GET, Var.THREAD_BOUND.bindTo(v));
    MethodHandle guarded = MethodHandles.guardWithTest(test, cache, root);
    CallSite cs = new MutableCallSite(MethodType.methodType(Object.class));
    cs.setTarget(guarded);
    return cs;
  }
}
