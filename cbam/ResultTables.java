package cbam;

import cbam.Data.ProblemData;
import cbam.PlanModel.Plan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a solved Plan into plain tables (column names + rows), so a GUI (or any other front end)
 * can show them without printing to the console. It does not change Report or Scenarios.
 */
public final class ResultTables {
    private ResultTables() {}

    /** A simple table: header names and rows of Strings / Numbers. */
    public record Table(String[] columns, Object[][] rows) {}

    /** Profit and loss, in EUR. */
    public static Table profitAndLoss(Plan plan) {
        Plan.Summary s = plan.summary();
        Object[][] rows = {
                {"Revenue", s.revenue()},
                {"Production cost", -s.productionCost() + 0.0},
                {"Transport cost", -s.transportCost() + 0.0},
                {"Carbon tax (EU-bound)", -s.carbonTax() + 0.0},
                {"Inventory holding", -s.holdingCost() + 0.0},
                {"Credit sales", s.creditIncome()},
                {"Credit purchases", -s.creditCost() + 0.0},
                {"PROFIT", s.profit()}
        };
        return new Table(new String[]{"Item", "EUR"}, rows);
    }

    /** Physical totals: tons, rail share, emissions, credits. */
    public static Table physical(Plan plan) {
        Plan.Summary s = plan.summary();
        double rail = 100.0 * s.railTons() / Math.max(1e-9, s.totalTons());
        Object[][] rows = {
                {"Shipped (t)", s.totalTons()},
                {"Shipped by rail (%)", rail},
                {"Emissions (tCO2)", s.emissions()},
                {"Allowance (tCO2)", plan.d.params().allowance()},
                {"Credits bought (tCO2)", plan.creditsBought()},
                {"Credits sold (tCO2)", plan.creditsSold()}
        };
        return new Table(new String[]{"Measure", "Value"}, rows);
    }

    /** Constraints with a shadow price above the threshold, largest first (Balance rows are skipped). */
    public static Table duals(Plan plan, int maxRows) {
        List<Object[]> list = new ArrayList<>();
        int n = plan.solution.constraintCount();
        for (int k = 0; k < n; k++) {
            String name = plan.solution.constraintName(k);
            if (name.startsWith("Balance")) continue;
            double v = plan.solution.dual(k);
            if (Math.abs(v) > 0.01) list.add(new Object[]{name, v, meaning(name)});
        }
        // order: CarbonCap, CreditCap, then capacity-type limits, then demand rows; largest first inside a group
        list.sort(Comparator.comparingInt((Object[] r) -> group((String) r[0]))
                .thenComparingDouble(r -> -Math.abs((Double) r[1])));
        if (list.size() > maxRows) list = list.subList(0, maxRows);
        return new Table(new String[]{"Constraint", "Shadow price", "Meaning"}, list.toArray(new Object[0][]));
    }

    private static int group(String name) {
        if (name.equals("CarbonCap")) return 0;
        if (name.equals("CreditCap")) return 1;
        if (name.startsWith("Demand")) return 3;
        return 2;
    }

    private static String meaning(String name) {
        if (name.equals("CarbonCap")) return "EUR gained per extra allowed tCO2 (marginal abatement cost)";
        if (name.equals("CreditCap")) return "EUR gained per extra credit that may be bought";
        if (name.startsWith("Capacity")) return "EUR per extra production hour";
        if (name.startsWith("Demand")) return "EUR per extra ton of demand";
        if (name.startsWith("ModeCap")) return "EUR per extra ton of mode capacity";
        if (name.startsWith("Storage")) return "EUR per extra ton of storage";
        return "";
    }

    /** All shipments with a positive quantity. */
    public static Table shipments(Plan plan) {
        ProblemData d = plan.d;
        List<Object[]> rows = new ArrayList<>();
        int F = d.facilities().size(), C = d.customers().size(), P = d.products().size();
        int M = d.modes().size(), T = d.params().periods();
        for (int t = 0; t < T; t++)
            for (int i = 0; i < F; i++)
                for (int j = 0; j < C; j++)
                    for (int p = 0; p < P; p++)
                        for (int m = 0; m < M; m++) {
                            double q = plan.ship(i, j, p, m, t);
                            if (q > 1e-9)
                                rows.add(new Object[]{t, d.facilities().get(i).name(), d.customers().get(j).name(),
                                        d.products().get(p).name(), d.modes().get(m).name(), q});
                        }
        return new Table(new String[]{"Period", "From", "To", "Product", "Mode", "Tons"}, rows.toArray(new Object[0][]));
    }

    /** Production and end-of-period stock for every facility, product and period. */
    public static Table production(Plan plan) {
        ProblemData d = plan.d;
        List<Object[]> rows = new ArrayList<>();
        for (int t = 0; t < d.params().periods(); t++)
            for (int i = 0; i < d.facilities().size(); i++)
                for (int p = 0; p < d.products().size(); p++)
                    rows.add(new Object[]{t, d.facilities().get(i).name(), d.products().get(p).name(),
                            plan.prod(i, p, t), plan.inv(i, p, t)});
        return new Table(new String[]{"Period", "Facility", "Product", "Produced (t)", "End stock (t)"},
                rows.toArray(new Object[0][]));
    }

    /** Tons shipped per period and mode, handy for a stacked chart. */
    public static Table modeByPeriod(Plan plan) {
        ProblemData d = plan.d;
        int M = d.modes().size(), T = d.params().periods();
        String[] cols = new String[M + 2];
        cols[0] = "Period";
        for (int m = 0; m < M; m++) cols[m + 1] = d.modes().get(m).name() + " (t)";
        cols[M + 1] = "Total (t)";
        Object[][] rows = new Object[T][M + 2];
        for (int t = 0; t < T; t++) {
            rows[t][0] = t;
            double total = 0;
            for (int m = 0; m < M; m++) {
                double sum = 0;
                for (int i = 0; i < d.facilities().size(); i++)
                    for (int j = 0; j < d.customers().size(); j++)
                        for (int p = 0; p < d.products().size(); p++) sum += plan.ship(i, j, p, m, t);
                rows[t][m + 1] = sum;
                total += sum;
            }
            rows[t][M + 1] = total;
        }
        return new Table(cols, rows);
    }

    // ------------------------------------------------------------------ scenario sweeps

    /** One row of a sweep: the value tried and the main outcomes. */
    public record SweepRow(double value, boolean optimal, String status, double profit, double emissions,
                           double railPct, double bought, double sold) {}

    /** Solves the model for one data set and returns the headline numbers. */
    public static SweepRow solveRow(double value, ProblemData data) {
        Plan plan = new PlanModel(data).solve();
        if (!plan.solution.isOptimal())
            return new SweepRow(value, false, plan.solution.status().toString(), Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        Plan.Summary s = plan.summary();
        double rail = 100.0 * s.railTons() / Math.max(1e-9, s.totalTons());
        return new SweepRow(value, true, "OPTIMAL", s.profit(), s.emissions(), rail,
                plan.creditsBought(), plan.creditsSold());
    }

    public static Table sweepTable(String label, List<SweepRow> rows) {
        Object[][] out = new Object[rows.size()][];
        for (int k = 0; k < rows.size(); k++) {
            SweepRow r = rows.get(k);
            out[k] = new Object[]{r.value(), r.optimal() ? r.profit() : "", r.optimal() ? r.emissions() : "",
                    r.optimal() ? r.railPct() : "", r.optimal() ? r.bought() : "", r.optimal() ? r.sold() : "", r.status()};
        }
        return new Table(new String[]{label, "Profit", "Emissions", "Rail %", "Credits bought", "Credits sold", "Status"}, out);
    }

    /** Writes a table as a CSV file (dot decimals, comma separator, so Excel with dot decimals reads it). */
    public static void writeCsv(Table t, java.nio.file.Path file) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(String.join(",", t.columns())).append('\n');
        for (Object[] row : t.rows()) {
            for (int c = 0; c < row.length; c++) {
                if (c > 0) sb.append(',');
                Object v = row[c];
                if (v instanceof Double dv) sb.append(String.format(java.util.Locale.US, "%.2f", dv));
                else sb.append(v);
            }
            sb.append('\n');
        }
        if (file.toAbsolutePath().getParent() != null)
            java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        java.nio.file.Files.writeString(file, sb.toString());
    }
}