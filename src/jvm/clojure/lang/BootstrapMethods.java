package clojure.lang;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

public class BootstrapMethods {

        private static final MethodHandle MAP;
        private static final MethodHandle MAP_UNIQUE;
        private static final MethodHandle ARRAY_MAP;
        private static final MethodHandle ARRAY_MAP_UNIQUE;
        private static final MethodHandle ATOMIC_GET;

        public static final MethodHandle RT_MAP;
        public static final MethodHandle VECTOR;

        private static final MethodType IPERSISTENT_MAP_TYPE = MethodType.methodType(IPersistentMap.class, Object[].class);

        private static MethodHandle mapCreator(MethodHandle src) {
        	return src.asType(IPERSISTENT_MAP_TYPE).asVarargsCollector(Object[].class);
        }

    static {
        try {
                MethodHandles.Lookup lk = MethodHandles.lookup();

                MethodType vectype = MethodType.methodType(IPersistentVector.class, Object[].class);
                VECTOR = lk.findStatic(RT.class, "vector", vectype);

                MethodType amaptype = MethodType.methodType(PersistentArrayMap.class, Object[].class);
                MethodType pmaptype = MethodType.methodType(PersistentHashMap.class, Object[].class);

                MAP              = mapCreator(lk.findStatic(PersistentHashMap.class, "createWithCheck", pmaptype));
                MAP_UNIQUE       = mapCreator(lk.findStatic(PersistentHashMap.class, "create", pmaptype));

                ARRAY_MAP        = mapCreator(lk.findStatic(PersistentArrayMap.class, "createWithCheck", amaptype));
                ARRAY_MAP_UNIQUE = mapCreator(lk.findConstructor(PersistentArrayMap.class, MethodType.methodType(void.class, Object[].class)));

                RT_MAP = lk.findStatic(RT.class, "map", IPERSISTENT_MAP_TYPE);
				  ATOMIC_GET = lk.findVirtual(AtomicBoolean.class, "get", MethodType.methodType(boolean.class));	
        } catch (Exception e) {
                System.err.println(e);
                throw new RuntimeException("Couldn't init bootstrapmethods");
        }
        }

        public static CallSite keywordExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String sym) {
                Keyword k = Keyword.intern(sym);
                return new ConstantCallSite(MethodHandles.constant(Keyword.class, k));
        }

        public static CallSite createVector(MethodHandles.Lookup lk, String methodName, MethodType t) {
                return new ConstantCallSite(VECTOR.asType(t));
        }

        public static CallSite createMap(MethodHandles.Lookup lk, String methodName, MethodType t) {
		            MethodHandle mh = t.parameterCount() <= PersistentArrayMap.HASHTABLE_THRESHOLD ?
			              ARRAY_MAP : MAP;
                return new ConstantCallSite(mh.asType(t));
        }

        public static CallSite createMapUnique(MethodHandles.Lookup lk, String methodName, MethodType t){
                MethodHandle mh = t.parameterCount() <= PersistentArrayMap.HASHTABLE_THRESHOLD ?
                    ARRAY_MAP_UNIQUE : MAP_UNIQUE;
                return new ConstantCallSite(mh.asType(t));
        }

        public static CallSite keywordInvoke(MethodHandles.Lookup lk, String methodName, MethodType t, String rep){
	    Keyword k = Keyword.intern(rep);
	    return KeywordInvokeCallSite.create(k);
	}

    public static CallSite createInstanceReflectionCache(MethodHandles.Lookup lk, String methodName, MethodType t, String reflectName) {
	    return ReflectionCallSite.createInstanceCache(reflectName);
    }

    public static CallSite createStaticReflectionCache(MethodHandles.Lookup lk, String methodName, MethodType t, String reflectName) {
	    return ReflectionCallSite.createStaticCache(reflectName);
    }

  public static class VarCallSite extends MutableCallSite {
		public final String varName; // for debug
		public final String varNs; // for debug

    public VarCallSite(MethodType t, String varNs, String varName) {
        super(t);
				this.varNs = varNs;
				this.varName = varName;
    }

    public void replaceRoot(Object o) {
      Object old;
      try {
        MethodHandle mh = getTarget();
        System.out.println(mh.type());
        old = (Object) mh.invokeExact();
      } catch (Throwable t) {
        throw new RuntimeException("asdfasdf");
      }
      System.out.format("--- DEOPT --- var: %s/%s, new: %s\n", varNs, varName, o);
      MethodHandle mh = MethodHandles.constant(Object.class, o);
      setTarget(mh);
      syncAll(new MutableCallSite[]{this});
    }
  }

  public static synchronized CallSite varExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var var = RT.var(varNs, varName);
    if (var.cs != null)
      return var.cs;

    VarCallSite cs = new VarCallSite(MethodType.methodType(Object.class), varNs, varName);
    var.setCallSite(cs);
    Object v = var.getRawRoot();
    MethodHandle mh = MethodHandles.constant(Object.class, v);
    cs.setTarget(mh);
    return cs;
  }

  public static final class DynVarCallSite extends VarCallSite {
    public DynVarCallSite(MethodType t, String varNs, String varName) { super(t, varNs, varName); }
    public void replaceRoot(Object o) { }
  }

  public static synchronized CallSite dynamicVarExpr(MethodHandles.Lookup lk, String methodName, MethodType t, String varNs, String varName) {
    Var var = RT.var(varNs, varName);
    if (var.cs != null)
      return var.cs;

    MethodHandle root = Var.ROOT.bindTo(var);
    MethodHandle cache = Var.DEREF.bindTo(var);
    MethodHandle test = MethodHandles.foldArguments(ATOMIC_GET, Var.THREAD_BOUND.bindTo(var));
    MethodHandle guarded = MethodHandles.guardWithTest(test, cache, root);
    DynVarCallSite cs = new DynVarCallSite(MethodType.methodType(Object.class), varNs, varName);
    var.setCallSite(cs);
    cs.setTarget(guarded);
    return cs;
  }  
}
