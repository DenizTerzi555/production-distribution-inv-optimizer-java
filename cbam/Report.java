package cbam;
import cbam.PlanModel.Plan;
import lp.LpSolution;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.nio.file.Path;

/**
 * Turns a solved Plan into a console output and CSV files.
 */

public final class Report {
    private Report() {}
    private static String f(String format, Object... args) {return String.format(Locale.US, format, args);}
    public static void printSummary(Plan plan) {
        LpSolution s = plan.solution;
        System.out.println(f("Status: %s | LP objective: %,.2f | iterations: %d | variables: %d | constraints: %d",
                s.status(), s.objective(), s.iterations(), s.variableCount(), s.constraintCount()));
        if (!s.isOptimal()) return;

        Plan.Summary x = plan.summary();
        System.out.println("\n--- Profit and loss (EUR) ---");
        System.out.println(f("Revenue       %,15.0f", x.revenue()));
        System.out.println(f("Production cost       %,15.0f", 0.0 - x.productionCost()));
        System.out.println(f("Transport cost        %,15.0f", 0.0 - x.transportCost()));
        System.out.println(f("Carbon tax (EU-bound) %,15.0f", 0.0 - x.carbonTax()));
        System.out.println(f("Inventory holding     %,15.0f", 0.0 - x.holdingCost()));
        System.out.println(f("Credit sales          %,15.0f", x.creditIncome()));
        System.out.println(f("Credit purchases      %,15.0f", 0.0 - x.creditCost()));
        System.out.println(f("PROFIT                %,15.0f", x.profit()));
        System.out.println("\n--- Physical results ---");
        System.out.println(f("Shipped: %,.0f t | by rail: %.1f%% | emissions: %,.0f tCO2 | allowance: %,.0f tCO2",
                x.totalTons(), 100 * x.railTons() / Math.max(1e-9, x.totalTons()), x.emissions(), plan.d.params().allowance()));
        System.out.println(f("Credits sold: %,.0f | credits bought: %,.0f", plan.creditsSold(), plan.creditsBought()));
        System.out.println("\n--- Most valuable constraints (shadow price > 0.01 EUR per unit) ---");
        for (int k = 0; k < s.constraintCount(); k++) {
            String n = s.constraintName(k);
            if (n.equals("CarbonCap"))
                System.out.println(f("%-42s %10.2f <- value of 1 more allowed tCO2 (marginal abatement cost)", n, s.dual(k)));
            else if (n.equals("CreditCap"))
                System.out.println(f("%-42s %10.2f <- value of 1 more credit allowed", n, s.dual(k)));
        }

        int shown = 0;
        for (int k = 0; k < s.constraintCount(); k++) {
            String n = s.constraintName(k);
            if (n.startsWith("Balance") || n.equals("CarbonCap") || n.equals("CreditCap")) continue;
            if (Math.abs(s.dual(k)) > 0.01 && shown < 12) {
                System.out.println(f("%-42s %10.2f", n, s.dual(k)));
                shown++;
            }
        }
    }
    public static void writeShipmentsCsv(Plan plan, Path file) throws IOException {
        var d = plan.d;
        StringBuilder sb = new StringBuilder("period,from,to,product,mode,tons\n");
        for (int t = 0; t < d.params().periods(); t++)
            for (int i = 0; i < d.facilities().size(); i++)
                for (int j = 0; j < d.customers().size(); j++)
                    for (int p = 0; p < d.products().size(); p++)
                        for (int m = 0; m < d.modes().size(); m++) {
                            double q = plan.ship(i, j, p, m, t);
                            if (q > 1e-6)
                                sb.append(f("%d,%s,%s,%s,%s,%.2f%n", t, d.facilities().get(i).name(), d.customers().get(j).name(),
                                        d.products(). get(p).name(), d.modes().get(m).name(), q));
                        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString());
    }
    public static void writeProductionCsv(Plan plan, Path file) throws IOException {
        var d = plan.d;
        StringBuilder sb = new StringBuilder("period,facility,product,produced_tons,end_stock_tons\n");
        for (int t = 0; t < d.params().periods(); t++)
            for (int i = 0; i < d.facilities().size(); i++)
                for (int p = 0; p < d.products().size(); p++)
                    sb.append(f("%d,%s,%s,%.2f,%.2f%n", t, d.facilities().get(i).name(), d.products().get(p).name(),
                            plan.prod(i, p, t), plan.inv(i, p, t)));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString());
    }
}
