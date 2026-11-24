package cbam;
import cbam.Data.ProblemData;
import cbam.PlanModel.Plan;
import java.nio.file.Path;

/**
 * Command-line entry point.
 * Usage: java -cp out cbam.App <dataFolder> [sweep | export]
 * Sweep values below are tuned for data/largedata (base allowance 57,000).
 */

public class App {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -cp out cbam.App <dataFolder> [sweep | export]");
            return;
        }
        Path dataDir = Path.of(args[0]);
        String command = args.length > 1 ? args[1] : "";
        ProblemData data = CsvLoader.load(dataDir);
        System.out.println("Loaded " + dataDir + ": " + data.facilities().size() + " facilities, " +
                data.customers().size() + " customers, " + data.products().size() + " products, " +
                data.modes().size() + " modes, " + data.params().periods() + " period(s)");
        java.util.List<String> checks = DataValidator.validate(data);
        checks.forEach(System.out::println);
        if (DataValidator.hasErrors(checks)) {
            System.out.println("Fix the ERROR lines above before solving.");
            return;
        }
        PlanModel model = new PlanModel(data);
        Plan plan = model.solve();
        Report.printSummary(plan);
        if (plan.solution.isOptimal()) {
            double obj = plan.solution.objective();
            double diff = Math.abs(plan.summary().profit() - obj);
            boolean ok = diff <= 1e-6 * Math.max(1.0, Math.abs(obj));
            System.out.println(String.format(java.util.Locale.US,
                    "Consistency check: | report profit - LP objective | = %.6f %s", diff, ok ? "OK" : "MISMATCH"));
        }
        Path out = Path.of("results");
        Report.writeShipmentsCsv(plan, out.resolve("shipments.csv"));
        Report.writeProductionCsv(plan, out.resolve("production.csv"));
        System.out.println("\nWrote results/shipments.csv and results/production.csv");

        if (command.equals("export")) {
            model.program().exportJson(out.resolve("model.json"), plan.solution);
            System.out.println("Wrote results/model.json  (check it with: python tools/verify_lp.py results/model.json)");
        }
        if (command.equals("sweep")) {
            // 1) carbon tax
            Scenarios.sweep("carbonTax", data, new double[]{0, 25, 50, 75, 80, 85, 90, 95, 100, 150, 200},
                    v -> data.withParams(data.params().withCarbonTax(v)), out.resolve("tax_sweep.csv"));

            // 2) emission allowance (unlimited credits)
            Scenarios.sweep("allowance", data, new double[]{40000, 45000, 50000, 55000, 57000, 60000, 65000},
                    v -> data.withParams(data.params().withAllowance(v)), out.resolve("allowance_sweep.csv"));

            // 3) credit purchase cap at the base allowance (57,000): the cap never binds here
            double allow = data.params().allowance();
            Scenarios.sweep("creditCapPctOfAllowance", data,
                    new double[]{0, 2, 4, 6, 8, 10, 12, 14, Double.POSITIVE_INFINITY},
                    v -> data.withParams(data.params().withCreditBuyCap(allow * v / 100.0)),
                    out.resolve("credit_cap_sweep.csv"));

            // 4) credit purchase cap with a tight allowance (50,000): the cap binds up to about 14%
            ProblemData tight = data.withParams(data.params().withAllowance(50000));
            Scenarios.sweep("creditCapPct_allow50000", tight,
                    new double[]{0, 2, 4, 6, 8, 10, 12, 14, 16, Double.POSITIVE_INFINITY},
                    v -> tight.withParams(tight.params().withCreditBuyCap(50000 * v / 100.0)),
                    out.resolve("credit_cap_sweep_allow50000.csv"));
        }
    }
}