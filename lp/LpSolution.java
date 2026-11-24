package lp;
import java.util.HashMap;
import java.util.Map;
/** Result of solving a Linear Program. */

public final class LpSolution {
    public enum Status { OPTIMAL, INFEASIBLE, UNBOUNDED, ITERATION_LIMIT }
    private final Status status;
    private final double objective;
    private final double[] values;
    private final double[] duals;
    private final double[] reduced;
    private final int iterations;
    private final String[] varNames;
    private final String[] rowNames;
    private final Map<String, Integer> varIndex = new HashMap<>();
    private final Map<String, Integer> rowIndex = new HashMap<>();
    LpSolution(Status status, double objective, double[] values, double[] duals, double[] reduced,
               int iterations, String[] varNames, String[] rowNames) {
        this.status = status;
        this.objective = objective;
        this.values = values;
        this.duals = duals;
        this.reduced = reduced;
        this.iterations = iterations;
        this.varNames = varNames;
        this.rowNames = rowNames;
        for (int i = 0; i < varNames.length; i++) varIndex.put(varNames[i], i);
        for (int i = 0; i < rowNames.length; i++) rowIndex.put(rowNames[i], i);
    }
    public Status status()   {return status; }
    public boolean isOptimal()  {return status == Status.OPTIMAL; }
    public double objective()   {return objective; }
    public int iterations()     {return iterations; }
    public double value(int variable)     {return values[variable]; }
    public double dual(int constraint)    {return duals[constraint]; }
    /**
     * Reduced cost of a variable: how much its profit per unit would have to
     * IMPROVE BEFORE the model would use it.
     * It is 0 for variables in the plan and >= 0 for the others
     */
    public double reducedCost(int variable) {return reduced[variable]; }

    /** Value of a variable looked up by its name */
    public double value (String name) {
        Integer i = varIndex.get(name);
        if (i == null) throw new IllegalArgumentException("Unknown variable: " + name);
        return values[i];
    }

    /** Shadow price of a constraint looked up by its name */
    public double dual(String name) {
        Integer i = rowIndex.get(name);
        if (i == null) throw new IllegalArgumentException("Unknown constraint: " + name);
        return duals[i];
    }
    public double reducedCost(String name) {
        Integer i = varIndex.get(name);
        if (i == null) throw new IllegalArgumentException("Unknown variable: " + name);
        return reduced[i];
    }
    public int variableCount() {return values.length; }
    public int constraintCount()  {return duals.length; }
    public String variableName(int i)  {return varNames[i]; }
    public String constraintName(int i) {return rowNames[i]; }
}
