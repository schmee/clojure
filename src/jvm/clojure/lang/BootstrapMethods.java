package clojure.lang;

import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BootstrapMethods {
  private static final MethodHandle ATOMIC_GET;
  static {
    Lookup lookup = MethodHandles.lookup();
    try {
        ATOMIC_GET = lookup.findVirtual(AtomicBoolean.class, "get", MethodType.methodType(boolean.class));
    } catch (Exception e) {
      throw new RuntimeException("Failed to init bootstrap methods");
    }
  }

  public static final class VarCallSite extends MutableCallSite {
    public VarCallSite(MethodType t) {
        super(t);
    }

    public void replaceRoot(Object o) {
      // Object old;
      // try {
      //   MethodHandle mh = getTarget();
      //   System.out.println(mh.type());
      //   old = mh.asType(MethodHandle(Object.class)).invokeExact();
      // } catch (Throwable t) {
      //   throw new RuntimeException("asdfasdf");
      // }
      // System.out.println("--- DEOPT --- " + " new " + o);
      MethodHandle mh = MethodHandles.constant(Object.class, o);
      setTarget(mh);
      syncAll(new MutableCallSite[]{this});
    }
  }

  public static CallSite varExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var var = RT.var(varNs, varName);
    VarCallSite cs = new VarCallSite(methodType(Object.class));
    var.setCallSite(cs);
    Object v = var.getRawRoot();
    MethodHandle mh = MethodHandles.constant(Object.class, v);
    cs.setTarget(mh);
    return cs;
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
