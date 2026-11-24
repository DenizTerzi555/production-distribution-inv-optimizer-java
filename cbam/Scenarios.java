package cbam;
import cbam.Data.ProblemData;
import cbam.PlanModel.Plan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * Runs the model many times with one parameter changed, and saves a comparison table.
 */

public final class Scenarios {
    private Scenarios(){}
    /**
     * @param base --> the base data set
     * @param values --> the values to try
     * @param change --> a function that returns a modified copy of the data for one value
     */
    public static void sweep(String label, ProblemData base, double[] values,
                             DoubleFunction<ProblemData> change, Path csv) throws IOException{
        StringBuilder sb = new StringBuilder(label + ",profit,emissions,rail_share_pct,credits_bought,credits_sold,status\n");
        System.out.println(String.format(Locale.US, "%n=== Sweep of %s ===", label));
        System.out.println(String.format(Locale.US, "%12s %14s %12s %10s %12s %12s",
                label, "profit", "emissions", "rail %", "bought", "sold"));
        for (double v : values) {
            Plan plan = new PlanModel(change.apply(v)).solve();
            if (!plan.solution.isOptimal()) {
                System.out.println(v + " -> " + plan.solution.status());
                sb.append(String.format(Locale.US, "%s,,,,,,%s%n", v, plan.solution.status()));
                continue;
            }
            Plan.Summary s = plan.summary();
            double rail = 100 * s.railTons() / Math.max(1e-9, s.totalTons());
            System.out.println(String.format(Locale.US, "%,12.0f %,14.0f %,12.0f %9.1f%% %,12.0f %,12.0f",
                    v, s.profit(), s.emissions(), rail, plan.creditsBought(), plan.creditsSold()));
            sb.append(String.format(Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.2f,OPTIMAL%n",
                    v, s.profit(), s.emissions(), rail, plan.creditsBought(), plan.creditsSold()));
        }
        Files.createDirectories(csv.toAbsolutePath().getParent());
        Files.writeString(csv, sb.toString());
    }
}
