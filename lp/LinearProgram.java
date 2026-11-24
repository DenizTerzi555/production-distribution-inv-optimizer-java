package lp;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;


public class LinearProgram {
    private static final double EPS = 1e-9;
    // ------------- model data --------------
    private final List<String> varNames = new ArrayList<>();
    private final List<Double> objective = new ArrayList<>();
    private final Map<String, Integer> varIndex = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    /** One constraint. Coefficients will be stored differently. */
    public static class Row {
        final String name;
        final ConstraintType type;
        final double rhs;
        final Map<Integer, Double> coefs = new TreeMap<>();

        Row(String name, ConstraintType type, double rhs) {
            this.name = name;
            this.type = type;
            this.rhs = rhs;
        }
        /** Adds coef * x[variable] to the left hand side, return so the calls can be chained */
        public Row add(int variable, double coef) {
            coefs.merge(variable, coef, Double::sum);
            return this;
        }
    }
    public int addVariable(String name, double objectiveCoefficient) {
        if (varIndex.containsKey(name)) throw new IllegalArgumentException("Duplicate variable name: " + name);
        int index = varNames.size();
        varNames.add(name);
        objective.add(objectiveCoefficient);
        varIndex.put(name, index);
        return index;
    }
    public Row addConstraint(String name, ConstraintType type, double rhs) {
        Row r = new Row(name, type, rhs);
        rows.add(r);
        return r;
    }
    public int variableCount() {return varNames.size(); }
    public int constraintCount() {return rows.size();}
    public double constraintRhs (int row) {return rows.get(row).rhs; }

    /** When true, the tableau is printed after every pivot */
    public void setVerbose(boolean verbose) {this.verbose = verbose;}

    // ------------------- solver state ------------------
    private double[][] t;       // the tableau: m+1 rows, n+1 columns
    private int[] basis;        // basis[i] = column that is basic in row i
    private int m, N;
    private int iterations;
    private boolean verbose = false;
    private String[] label;     // column labels used when printing
    /** Solves the model with the Simplex method */
    public LpSolution solve() {
        m = rows.size();
        int n = varNames.size();
        iterations = 0;

        // --------- 1) Normalizing every row so the right hand side is >= 0
        double[] sign = new double[m];
        ConstraintType[] type = new ConstraintType[m];
        int[] slackCol = new int[m];
        int[] artCol = new int[m];
        int col = n;
        for (int i = 0; i < m; i++) {
            Row r = rows.get(i);
            sign[i] = r.rhs < 0 ? -1.0: 1.0;
            type[i] = sign[i] < 0 ? r.type.flipped() : r.type;
            slackCol[i] = -1;
            artCol[i] = -1;
            if (type[i] != ConstraintType.EQ) slackCol[i] = col++;  // slack (<=) or surplus (>=)
        }
        int firstArtificial = col;
        for (int i = 0; i < m; i++) {
            if (type[i] != ConstraintType.LE) artCol[i] = col++;  // artificial for >= and =
        }
        N = col;
        label = new String[N];
        for (int j = 0; j < n; j++) label[j] = "x" + (j + 1);
        for (int i = 0; i < m; i++) {
            if (slackCol[i] >= 0) label[slackCol[i]] = "s" + (i + 1);
            if (artCol[i] >= 0) label[artCol[i]] = "a" + (i + 1);
        }
        // --------- 2) Filling the tableau ------------
        t = new double[m + 1][N + 1];
        basis = new int[m];
        for (int i = 0; i < m; i++) {
            Row r = rows.get(i);
            for (Map.Entry<Integer, Double> e : r.coefs.entrySet()) t[i][e.getKey()] = sign[i] * e.getValue();
            t[i][N] = sign[i] * r.rhs;
            if (slackCol[i] >= 0) t[i][slackCol[i]] = (type[i] == ConstraintType.LE) ? 1.0: -1.0;
            if (artCol[i] >= 0) {
                t[i][artCol[i]] = 1.0;
                basis[i] = artCol[i];
            } else {
                basis[i] = slackCol[i];
            }
        }
        // ---------------- 3) Finding the feasible starting point
        if (firstArtificial < N) {
            for (int j = firstArtificial; j < N; j++) t[m][j] = 1.0;    // maximise
            for (int i = 0; i < m; i++) {
                if (basis[i] >= firstArtificial) subtractRow(m, i, 1.0);    // make basic columns zero in row m
            }
            if (verbose) printTableau("Phase 1 start! (objective row = minimise the artificial variables)");
            LpSolution.Status st = iterate(firstArtificial);
            if (st == LpSolution.Status.ITERATION_LIMIT) return build(st, n, sign, type, slackCol, artCol);
            double sumB = 0;
            for (int i = 0; i < m; i++) sumB += Math.abs(t[i][N]);
            if (t[m][N] < -1e-7 * (1.0 + sumB)) {
                return build(LpSolution.Status.INFEASIBLE, n, sign, type, slackCol, artCol);
            }
            // push lefotver artificial variables out of the basis
            for (int i = 0; i < m; i++) {
                if (basis[i] >= firstArtificial) {
                    for (int j = 0; j < firstArtificial; j++) {
                        if (Math.abs(t[i][j]) > 1e-7) { pivot(i, j); break; }
                    }
                    // if no column was found the row is redundant
                }
            }
        }
        // ------------- 4) Phase 2: Optimizing the real objective
        for (int j = 0; j <= N; j++) t[m][j] = 0.0;
        for (int j = 0; j < n; j++) t[m][j] = -objective.get(j);
        for (int i = 0; i < m; i++) {
            double f = t[m][basis[i]];
            if (Math.abs(f) > 0) subtractRow(m, i, f);
        }
        if (verbose) printTableau ("Phase 2 start! (real objective, artificial columns frozen)");
        LpSolution.Status st = iterate(firstArtificial);
        return build(st, n, sign, type, slackCol, artCol);
    }
    /** Runs Simplex iterations; only columns below allowedCols enter the basis. */
    private LpSolution.Status iterate(int allowedCols) {
        int limit = 100_000;
        int degenerateStreak = 0;
        int count = 0;
        while (count < limit) {
            boolean bland = degenerateStreak > 50;
            int col = -1;
            double best = -EPS;
            for (int j = 0; j < allowedCols; j++) {
                if (t[m][j] < best) {
                    col = j;
                    if (bland) break;
                    best = t[m][j];
                }
            }
            if (col == -1) return LpSolution.Status.OPTIMAL;
            int row = -1;
            double minRatio = Double.POSITIVE_INFINITY;
            for (int i = 0; i < m; i++) {
                if (t[i][col] > EPS) {
                    double ratio = t[i][N] / t[i][col];
                    if (ratio < minRatio - EPS
                        || (Math.abs(ratio - minRatio) <= EPS && row != -1 && basis[i] < basis[row])) {
                        minRatio = ratio;
                        row = i;
                    }
                }
            }
            if (row == -1) return LpSolution.Status.UNBOUNDED;
            degenerateStreak = (minRatio <= EPS) ? degenerateStreak + 1 : 0;

            if (verbose)System.out.println("Pivot: " + label[col] + " enters, row" + (row + 1) + " (" + label[basis[row]] + ") leaves.");
            pivot(row, col);
            count++;
            iterations++;
            if(verbose)printTableau("Tableau after pivot " + iterations);
        }
        return LpSolution.Status.ITERATION_LIMIT;
    }
    private void pivot(int row, int col) {
        double p = t[row][col];
        for (int j = 0; j <= N; j++) t[row][j] /= p;
        for (int i = 0; i <= m; i++) {
            if (i == row) continue;
            double f = t[i][col];
            if (Math.abs(f) < 1e-12) continue;
            subtractRow(i, row, f);
        }
        basis[row] = col;
    }
    /** t[target] -= factor * t[source] */
    private void subtractRow(int target, int source, double factor) {
        for (int j = 0; j <= N; j++) t[target][j] -= factor * t[source][j];
    }
    private LpSolution build(LpSolution.Status st, int n, double[] sign, ConstraintType[] type,
                             int[] slackCol, int[] artCol) {
        double[] x = new double[n];
        for (int i = 0; i < m; i++) {
            if (basis[i] < n) x[basis[i]] = Math.max(0.0, t[i][N]);
        }
        double[] duals = new double[m];
        for (int i = 0; i < m; i++) {
            double y;
            if (type[i] == ConstraintType.LE)  y= t[m][slackCol[i]];
            else if (type[i] == ConstraintType.GE)  y= -t[m][slackCol[i]];
            else    y = t[m][artCol[i]];
            duals[i] = sign[i] * y + 0.0;
        }
        double[] reduced = new double[n];
        for (int j = 0; j < n; j++) reduced[j] = Math.max(0.0, t[m][j]) + 0.0;
        String[] vn = varNames.toArray(new String[0]);
        String[] rn = new String[m];
        for (int i = 0; i < m; i++) rn[i] = rows.get(i).name;
        double z = (st == LpSolution.Status.OPTIMAL) ? t[m][N] : Double.NaN;
        return new LpSolution(st, z, x, duals, reduced, iterations, vn, rn);
    }
    private void printTableau(String title) {
        System.out.println(title);
        StringBuilder head = new StringBuilder(String.format("%-5s|", "basis"));
        for (int j = 0; j < N; j++) head.append(String.format("%8s", label[j]));
        head.append(String.format("%9s", "RHS"));
        System.out.println(head);
        for (int i = 0; i <= m; i++) {
            StringBuilder sb = new StringBuilder(String.format("%-5s|", i < m ? label[basis[i]]: "z"));
            for (int j = 0; j <= N; j++)
                sb.append(String.format(java.util.Locale.US, j == N ? "%9.3f": "%8.3f", t[i][j] + 0.0));
            System.out.println(sb);
        }
        System.out.println();
    }
    // ------- export for independent checking -------
    /**
     * Wries the model as JSON
     */
    public void exportJson(Path file, LpSolution solution) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        int n = varNames.size();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n \"maximize\" : true, \n \"c\": [");
        for (int j = 0; j < n; j++) sb.append(j == 0 ? "" : ", ").append(objective.get(j));
        sb.append("],\n \"rows\" : [\n");
            for (int i = 0; i < rows.size(); i++) {
                Row r =rows.get(i);
                double[] a = new double[n];
                for (Map.Entry<Integer, Double> e : r.coefs.entrySet()) a[e.getKey()] = e.getValue();
                sb.append("     {\"type\" : \"").append(r.type.symbol()).append("\", \"rhs\": ").append(r.rhs).append(", \"a\": [");
                for (int j =0; j < n; j++) sb.append(j == 0 ? "" : ", ").append(a[j]);
                sb.append("]}").append(i + 1 < rows.size() ? "," : "").append("\n");
            }
            sb.append("     ], \n   \"java_status\": \"").append(solution.status()).append("\"");
            if (solution.isOptimal()){
                sb.append(",\n    \"java_objective\": ").append(solution.objective());
                sb.append(",\n  \"java_x\" : [");
                for (int j = 0; j < n; j++) sb.append(j == 0 ? "" : ", ").append(solution.value(j));
                sb.append("]");
            }
            sb.append("\n}\n");
            Files.writeString(file, sb.toString());
    }
}
