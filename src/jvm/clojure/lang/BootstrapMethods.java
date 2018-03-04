package clojure.lang;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

public class BootstrapMethods {
  public static CallSite varExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var v = RT.var(varNs, varName);
    MethodHandle mh = Var.ROOT.bindTo(v);
    return new ConstantCallSite(mh);
  }
}
