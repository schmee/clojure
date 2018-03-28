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

  public static class VarCallSite extends MutableCallSite {
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

  public static synchronized CallSite varExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var var = RT.var(varNs, varName);
    if (var.cs != null)
      return var.cs;

    VarCallSite cs = new VarCallSite(methodType(Object.class));
    var.setCallSite(cs);
    Object v = var.getRawRoot();
    MethodHandle mh = MethodHandles.constant(Object.class, v);
    cs.setTarget(mh);
    return cs;
  }

  public static final class DynVarCallSite extends VarCallSite {
    public DynVarCallSite(MethodType t) {
        super(t);
    }

    public void replaceRoot(Object o) {
      //
    }
  }

  public static synchronized CallSite dynamicVarExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var var = RT.var(varNs, varName);
    if (var.cs != null)
      return var.cs;

    MethodHandle root = Var.ROOT.bindTo(var);
    MethodHandle cache = Var.DEREF.bindTo(var);
    MethodHandle test = MethodHandles.foldArguments(ATOMIC_GET, Var.THREAD_BOUND.bindTo(var));
    MethodHandle guarded = MethodHandles.guardWithTest(test, cache, root);
    DynVarCallSite cs = new DynVarCallSite(MethodType.methodType(Object.class));
    var.setCallSite(cs);
    cs.setTarget(guarded);
    return cs;
  }
}
