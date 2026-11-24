package cbam;
import cbam.Data.*;
import lp.ConstraintType;
import lp.LinearProgram;
import lp.LpSolution;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/**
 * Multi-period production + inventory + distribution model with a carbon tax and carbon-credit trading.
 * With periods = 1 it is the simple single-period model.
 *
 *  Mathematical Model:
 *  ship[i][j][p][m][t]
 *  tons of product p sent from facility i to customer j by mode m in period t
 *  prod[i][p][t]
 *  tons produced
 *  inv[i][p][t]
 *  tons in stock at the END of period t
 *  sell, buy
 *  carbon credits sold / bought
 */

public class PlanModel {
    private final ProblemData d;
    private final LinearProgram lp = new LinearProgram();
    // variable index tables
    private int[][][][][] ship;
    private int[][][] prod, inv;
    private int sellVar, buyVar;

    public PlanModel(ProblemData d) {
        this.d = d;
        build();
    }

    public LinearProgram program() {
        return lp;
    }

    // Indices of every shipment variable that serves customer j with product p in period t
    public List<Integer> shipVariablesTo(int j, int p, int t) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < ship.length; i++) {
            for (int m = 0; m < ship[i][j][p].length; m++) {
                if (ship[i][j][p][m][t] >= 0) result.add(ship[i][j][p][m][t]);
            }
        }
        return result;
    }

    // --------------------- 1. Building the LP ----------------------
    private void build() {
        int F = d.facilities().size(), C = d.customers().size(), P = d.products().size();
        int M = d.modes().size(), T = d.params().periods();
        Params pr = d.params();
        // ----------------- variables and objective coefficients
        ship = new int[F][C][P][M][T];
        for (int[][][][] a : ship) for (int[][][] b : a) for (int[][] c : b) for (int[] e : c) Arrays.fill(e, -1);
        prod = new int[F][P][T];
        inv = new int[F][P][T];
        for (int i = 0; i < F; i++)
            for (int j = 0; j < C; j++)
                for (int p = 0; p < P; p++)
                    for (int m = 0; m < M; m++)
                        for (int t = 0; t < T; t++) {
                            if (!d.modeAllowed()[p][m]) continue;
                            if (d.demand()[j][p][t] <= 0) continue;
                            double transport = d.modes().get(m).costPerTonKm() * d.distanceKm()[i][j];
                            double tax = d.customers().get(j).euBound()
                                    ? pr.carbonTax() * d.embeddedPlusRouteEmission(i, j, p, m) : 0.0;
                            double profit = d.price()[j][p] - transport - tax;
                            ship[i][j][p][m][t] = lp.addVariable(name("ship", d.facilities().get(i).name(), d.customers().get(j).name(),
                                    d.products().get(p).name(), d.modes().get(m).name(), t), profit);
                        }
        for (int i = 0; i < F; i++)
            for (int p = 0; p < P; p++)
                for (int t = 0; t < T; t++) {
                    prod[i][p][t] = lp.addVariable(name("prod", d.facilities().get(i).name(), d.products().get(p).name(), t),
                            -d.prodCost()[i][p]);
                    inv[i][p][t] = lp.addVariable(name("inv", d.facilities().get(i).name(), d.products().get(p).name(), t),
                            -d.products().get(p).holdingCostPerTPerPeriod());
                }
        sellVar = lp.addVariable("creditsSold", pr.creditSellPrice());
        buyVar = lp.addVariable("creditsBought", -pr.creditBuyPrice());
        // ------- inventory balance ---------
        for (int i = 0; i < F; i++)
            for (int p = 0; p < P; p++)
                for (int t = 0; t < T; t++) {
                    LinearProgram.Row r = lp.addConstraint(name("Balance", d.facilities().get(i).name(), d.products().get(p).name(), t),
                            ConstraintType.EQ, 0.0);
                    r.add(prod[i][p][t], 1).add(inv[i][p][t], -1);
                    if (t > 0) r.add(inv[i][p][t - 1], 1);
                    for (int j = 0; j < C; j++)
                        for (int m = 0; m < M; m++)
                            if (ship[i][j][p][m][t] >= 0) r.add(ship[i][j][p][m][t], -1);
                }
        // ---------- production capacity and storage capacity per facility and period ------------
        for (int i = 0; i < F; i++)
            for (int t = 0; t < T; t++) {
                LinearProgram.Row cap = lp.addConstraint(name("Capacity", d.facilities().get(i).name(), t),
                        ConstraintType.LE, d.facilities().get(i).capHoursPerPeriod());
                LinearProgram.Row sto = lp.addConstraint(name("Storage", d.facilities().get(i).name(), t),
                        ConstraintType.LE, d.facilities().get(i).storageCapTons());
                for (int p = 0; p < P; p++) {
                    cap.add(prod[i][p][t], d.products().get(p).hoursPerTon());
                    sto.add(inv[i][p][t], 1);
                }
            }
        // -------- customer demand ---------
        for (int j = 0; j < C; j++)
            for (int p = 0; p < P; p++)
                for (int t = 0; t < T; t++) {
                    if (d.demand()[j][p][t] <= 0) continue;
                    LinearProgram.Row r = lp.addConstraint(name("Demand", d.customers().get(j).name(), d.products().get(p).name(), t),
                            ConstraintType.LE, d.demand()[j][p][t]);
                    for (int i = 0; i < F; i++)
                        for (int m = 0; m < M; m++)
                            if (ship[i][j][p][m][t] >= 0) r.add(ship[i][j][p][m][t], 1);
                }
        // -------- capacity of each transport mode per facility and period ---------
        for (int i = 0; i < F; i++)
            for (int m = 0; m < M; m++)
                for (int t = 0; t < T; t++) {
                    LinearProgram.Row r = lp.addConstraint(name("ModeCap", d.facilities().get(i).name(), d.modes().get(m).name(), t),
                            ConstraintType.LE, d.modeCap()[i][m]);
                    for (int j = 0; j < C; j++)
                        for (int p = 0; p < P; p++)
                            if (ship[i][j][p][m][t] >= 0) r.add(ship[i][j][p][m][t], 1);
                }
        // --------- carbon cap for the whole horizon, with credit trading
        // emissions + sold - bought <= allowance
        LinearProgram.Row carbon = lp.addConstraint("CarbonCap", ConstraintType.LE, pr.allowance());
        for (int i = 0; i < F; i++)
            for (int p = 0; p < P; p++)
                for (int t = 0; t < T; t++)
                    carbon.add(prod[i][p][t], d.products().get(p).prodEmissionTPerT());
        for (int i = 0; i < F; i++)
            for (int j = 0; j < C; j++)
                for (int p = 0; p < P; p++)
                    for (int m = 0; m < M; m++)
                        for (int t = 0; t < T; t++)
                            if (ship[i][j][p][m][t] >= 0) carbon.add(ship[i][j][p][m][t], d.routeEmission(i, j, m));
        carbon.add(sellVar, 1).add(buyVar, -1);
        if (Double.isFinite(pr.creditBuyCap()))
            lp.addConstraint("CreditCap", ConstraintType.LE,
                    pr.creditBuyCap()).add(buyVar, 1);
    }

    private static String name(String kind, Object... parts) {
        StringBuilder sb = new StringBuilder(kind).append('[');
        for (int k = 0; k < parts.length; k++) sb.append(k == 0 ? "" : ",").append(parts[k]);
        return sb.append(']').toString();
    }

    // ------------------------- 2. Solve and read the answer
    public Plan solve() {
        return new Plan(d, this, lp.solve());
    }
    public Plan solveAndExport(String fileName) throws java.io.IOException {
        Plan plan = solve();
        lp.exportJson(java.nio.file.Path.of("exports", fileName), plan.solution);
        return plan;
    }

    /**
     * Solution wrapper with helper methods that translate raw variable values
     */
    public static class Plan {
        public final ProblemData d;
        private final PlanModel model;
        public final LpSolution solution;

        Plan(ProblemData d, PlanModel model, LpSolution solution) {
            this.d = d;
            this.model = model;
            this.solution = solution;
        }

        public double ship(int i, int j, int p, int m, int t) {
            int v = model.ship[i][j][p][m][t];
            return v < 0 ? 0.0 : solution.value(v);
        }

        public double prod(int i, int p, int t) {
            return solution.value(model.prod[i][p][t]);
        }

        public double inv(int i, int p, int t) {
            return solution.value(model.inv[i][p][t]);
        }

        public double creditsSold() {
            return solution.value(model.sellVar);
        }

        public double creditsBought() {
            return solution.value(model.buyVar);
        }

        /**
         * Money and physical totals, computed from the solution
         */
        public record Summary(double revenue, double productionCost, double transportCost, double carbonTax,
                              double holdingCost, double creditIncome, double creditCost,
                              double emissions, double railTons, double totalTons, double profit) {}
        public Summary summary() {
            int F = d.facilities().size(), C = d.customers().size(), P = d.products().size();
            int M = d.modes().size(), T = d.params().periods();
            double revenue = 0, prodCost = 0, transport = 0, tax = 0, hold = 0, emissions = 0, railTons = 0, tons = 0;
            for (int i = 0; i < F; i++)
                for (int p = 0; p < P; p++)
                    for (int t = 0; t < T; t++) {
                        double q = prod(i, p, t);
                        prodCost += d.prodCost()[i][p] * q;
                        emissions += d.products().get(p).prodEmissionTPerT() * q;
                        hold += d.products().get(p).holdingCostPerTPerPeriod() * inv(i, p, t);
                    }
            for (int i = 0; i < F; i++)
                for (int j = 0; j < C; j++)
                    for (int p = 0; p < P; p++)
                        for (int m = 0; m < M; m++)
                            for (int t = 0; t < T; t++) {
                                double q = ship(i, j, p, m, t);
                                if (q <= 0) continue;
                                revenue += d.price()[j][p] * q;
                                transport += d.modes().get(m).costPerTonKm() * d.distanceKm()[i][j] * q;
                                emissions += d.routeEmission(i, j, m) * q;
                                if (d.customers().get(j).euBound())
                                    tax += d.params().carbonTax() * d.embeddedPlusRouteEmission(i, j, p, m) * q;
                                tons += q;
                                if (d.modes().get(m).name().equalsIgnoreCase("Rail")) railTons += q;
                            }
            double income = d.params().creditSellPrice() * creditsSold();
            double cost = d.params().creditBuyPrice() * creditsBought();
            double profit = revenue - prodCost - transport - tax - hold + income - cost;
            return new Summary(revenue, prodCost, transport, tax, hold, income, cost, emissions, railTons, tons, profit);

        }
    }
}