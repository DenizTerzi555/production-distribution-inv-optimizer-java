import json, glob, os, sys
import numpy as np
from scipy.optimize import linprog

TOL = 1e-6
LE = {"<=", "≤", "LE", "LEQ"}
GE = {">=", "≥", "GE", "GEQ"}
EQ = {"=", "==", "EQ"}
SCIPY_STATUS = {0: "OPTIMAL", 2: "INFEASIBLE", 3: "UNBOUNDED"}

def kind(sym):
    s = str(sym).strip().upper()
    if s in LE: return "LE"
    if s in GE: return "GE"
    if s in EQ: return "EQ"
    raise ValueError(f"Unknown constraint type: {sym}")

def check(path):
    name = os.path.basename(path)
    p = json.load(open(path))
    c = np.array(p["c"], dtype=float)
    rows = p["rows"]
    A = np.array([r["a"] for r in rows], dtype=float)
    b = np.array([r["rhs"] for r in rows], dtype=float)
    t = np.array([kind(r["type"]) for r in rows])
    sign = -1.0 if p["maximize"] else 1.0   # linprog always minimizes

    leq, geq, eq = (t == "LE"), (t == "GE"), (t == "EQ")
    A_ub = np.vstack([A[leq], -A[geq]]) if (leq.any() or geq.any()) else None
    b_ub = np.concatenate([b[leq], -b[geq]]) if A_ub is not None else None
    A_eq = A[eq] if eq.any() else None
    b_eq = b[eq] if eq.any() else None

    res = linprog(sign * c, A_ub=A_ub, b_ub=b_ub, A_eq=A_eq, b_eq=b_eq,
                  bounds=(0, None), method="highs")

    sp_status = SCIPY_STATUS.get(res.status, f"OTHER({res.status})")
    jv_status = p["java_status"]

    if sp_status != "OPTIMAL" or jv_status != "OPTIMAL":
        ok = (sp_status == jv_status)
        print(f"{name}: java={jv_status} scipy={sp_status} -> {'OK' if ok else 'MISMATCH'}")
        return ok

    py_obj = sign * res.fun
    diff = abs(py_obj - p["java_objective"])
    ok = diff <= TOL * max(1.0, abs(py_obj))
    print(f"{name}: java={p['java_objective']:.6f} scipy={py_obj:.6f} "
          f"diff={diff:.2e} -> {'OK' if ok else 'MISMATCH'}")

    # Check that the Java plan is feasible (ties can give different plans)
    if "java_x" in p:
        x = np.array(p["java_x"], dtype=float)
        viol = float(-x.min())
        if A_ub is not None: viol = max(viol, float(np.max(A_ub @ x - b_ub)))
        if A_eq is not None: viol = max(viol, float(np.max(np.abs(A_eq @ x - b_eq))))
        print(f"   Java plan max constraint violation: {viol:.2e}")
        ok = ok and viol <= 1e-6
    return ok

if __name__ == "__main__":
    folder = sys.argv[1] if len(sys.argv) > 1 else "exports"
    files = sorted(glob.glob(f"{folder}/*.json"))
    if not files:
        print(f"No JSON files in {folder}")
        sys.exit(1)
    results = [check(f) for f in files]
    print("ALL PASS" if all(results) else "SOME FAILED")